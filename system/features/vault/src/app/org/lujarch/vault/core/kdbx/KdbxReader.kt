/*
 * Copyright (C) 2026 The Lujarch Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lujarch.vault.core.kdbx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import javax.crypto.BadPaddingException
import org.lujarch.vault.core.ByteReader
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.KdbxDatabase
import org.lujarch.vault.core.KdbxFormatError
import org.lujarch.vault.core.KdbxInfo
import org.lujarch.vault.core.WipeableBuffer
import org.lujarch.vault.core.crypto.Aes
import org.lujarch.vault.core.crypto.Argon2
import org.lujarch.vault.core.crypto.ChaCha20
import org.lujarch.vault.core.crypto.Salsa20
import org.lujarch.vault.core.crypto.StreamCipher

/**
 * Reads KDBX 3.1 and 4.x databases (KeePass, KeePassXC, KeePassDX):
 * key transformation (AES-KDF, Argon2d, Argon2id), header HMAC and HMAC block
 * stream (4.x) or start bytes and hashed block stream (3.1), AES-256-CBC or
 * ChaCha20 payload, gzip, inner random stream for protected values, XML.
 */
object KdbxReader {
    private const val MAX_XML_BYTES = 64 * 1024 * 1024
    /** Four times the payload for small databases, as before; large ones grow from here. */
    private const val INITIAL_GUNZIP_BYTES = 16L * 1024 * 1024
    private val SALSA20_IV = byteArrayOf(0xE8.toByte(), 0x30, 0x09, 0x4B, 0x97.toByte(), 0x20, 0x5D, 0x2A)
    private const val STREAM_NONE = 0
    private const val STREAM_SALSA20 = 2
    private const val STREAM_CHACHA20 = 3

    /** Timing of the last [open] step that dominates unlock time; for the state page. */
    class Result(val database: KdbxDatabase, val kdfMillis: Long, val totalMillis: Long)

    /** Runs the KDF on [compositeKey] and decrypts [file]. */
    fun open(file: ByteArray, compositeKey: ByteArray): Result {
        val start = System.nanoTime()
        val header = KdbxHeader.parse(file)
        val kdfStart = System.nanoTime()
        val transformed = transformKey(header.kdf, compositeKey)
        val kdfMillis = (System.nanoTime() - kdfStart) / 1_000_000
        try {
            val db = openWithKey(file, header, transformed)
            return Result(db, kdfMillis, (System.nanoTime() - start) / 1_000_000)
        } finally {
            Bytes.wipe(transformed)
        }
    }

    /**
     * Decrypts [file] with an already transformed key (the KDF output for
     * [header]'s KDF parameters): no KDF runs. A key made for other
     * parameters fails like a wrong password (WRONG_KEY).
     */
    fun openWithKey(file: ByteArray, header: KdbxHeader, transformedKey: ByteArray): KdbxDatabase =
        if (header.major == 4) readV4(file, header, transformedKey) else readV3(file, header, transformedKey)

    fun transformKey(kdf: Kdf, compositeKey: ByteArray): ByteArray = when (kdf) {
        is Kdf.AesKdf -> Aes.kdf(compositeKey, kdf.seed, kdf.rounds)
        is Kdf.Argon2Kdf -> Argon2.hash(
            kdf.type, kdf.version, compositeKey, kdf.salt, kdf.secret, kdf.associated,
            kdf.iterations, kdf.memoryBytes, kdf.parallelism, 32,
        )
    }

    private fun readV4(file: ByteArray, header: KdbxHeader, transformed: ByteArray): KdbxDatabase {
        val r = ByteReader(file, header.headerEnd)
        val headerBytes = file.copyOfRange(0, header.headerEnd)
        val storedHash = guard { r.bytes(32) }
        if (!Bytes.constantTimeEquals(Bytes.sha256(headerBytes), storedHash)) corrupt("header hash does not match")
        val storedHmac = guard { r.bytes(32) }

        val hmacBase = Bytes.sha512(header.masterSeed, transformed, byteArrayOf(1))
        val cipherKey = Bytes.sha256(header.masterSeed, transformed)
        var plain: ByteArray? = null
        var xml: ByteArray? = null
        try {
            val headerKey = Bytes.sha512(Bytes.u64leBytes(-1L), hmacBase)
            val headerHmac = Bytes.hmac("HmacSHA256", headerKey, headerBytes)
            Bytes.wipe(headerKey)
            if (!Bytes.constantTimeEquals(headerHmac, storedHmac)) {
                throw KdbxException(KdbxException.Reason.WRONG_KEY, "header HMAC does not match")
            }

            // HMAC block stream: [hmac 32][size i32][data], block keys derived from the block index.
            val ciphertext = ByteArrayOutputStream(file.size)
            var index = 0L
            while (true) {
                val blockHmac = guard { r.bytes(32) }
                val size = guard { r.i32() }
                if (size < 0) corrupt("negative block size")
                val dataStart = r.pos
                guard { r.require(size) }
                val blockKey = Bytes.sha512(Bytes.u64leBytes(index), hmacBase)
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(javax.crypto.spec.SecretKeySpec(blockKey, "HmacSHA256"))
                mac.update(Bytes.u64leBytes(index))
                mac.update(Bytes.i32leBytes(size))
                mac.update(file, dataStart, size)
                val actual = mac.doFinal()
                Bytes.wipe(blockKey)
                if (!Bytes.constantTimeEquals(actual, blockHmac)) corrupt("block $index HMAC does not match")
                if (size == 0) break
                ciphertext.write(file, dataStart, size)
                r.pos = dataStart + size
                index++
            }
            plain = decrypt(header, cipherKey, ciphertext.toByteArray())
            val payload = if (header.compressed) gunzip(plain) else plain

            // Inner header: [type u8][size i32][data], 0 ends it.
            val ir = ByteReader(payload)
            var streamId = STREAM_NONE
            var streamKey: ByteArray? = null
            while (true) {
                val type = guard { ir.u8() }
                val size = guard { ir.i32() }
                if (size < 0) corrupt("negative inner header field")
                val value = guard { ir.bytes(size) }
                when (type) {
                    0 -> break
                    1 -> streamId = if (value.size >= 4) Bytes.i32le(value, 0) else corrupt("bad inner stream id")
                    2 -> streamKey = value
                    // 3: attachment; not shown in phase 1, dropped at once.
                    else -> Bytes.wipe(value)
                }
            }
            xml = payload.copyOfRange(ir.pos, payload.size)
            if (payload !== plain) Bytes.wipe(payload)
            val stream = innerStream(streamId, streamKey)
            Bytes.wipe(streamKey)
            try {
                val parsed = KdbxXml.parse(xml, stream, null)
                return parsed.toDatabase(info(header))
            } finally {
                stream?.wipe()
            }
        } finally {
            Bytes.wipe(hmacBase, cipherKey, plain, xml)
        }
    }

    private fun readV3(file: ByteArray, header: KdbxHeader, transformed: ByteArray): KdbxDatabase {
        val cipherKey = Bytes.sha256(header.masterSeed, transformed)
        var plain: ByteArray? = null
        var xml: ByteArray? = null
        try {
            val ciphertext = file.copyOfRange(header.headerEnd, file.size)
            plain = try {
                decrypt(header, cipherKey, ciphertext)
            } catch (e: KdbxException) {
                // CBC padding errors after a wrong key are the normal case here.
                if (e.reason == KdbxException.Reason.CORRUPT) {
                    throw KdbxException(KdbxException.Reason.WRONG_KEY, "payload does not decrypt", e)
                }
                throw e
            }
            val start = header.streamStartBytes!!
            if (plain.size < start.size || !Bytes.constantTimeEquals(plain.copyOf(start.size), start)) {
                throw KdbxException(KdbxException.Reason.WRONG_KEY, "start bytes do not match")
            }

            // Hashed block stream: [index u32][sha256 32][size i32][data], ends with size 0.
            val r = ByteReader(plain, start.size)
            val blocks = WipeableBuffer(plain.size)
            var expectedIndex = 0L
            while (true) {
                val index = guard { r.u32() }
                val hash = guard { r.bytes(32) }
                val size = guard { r.i32() }
                if (index != expectedIndex) corrupt("block index $index, expected $expectedIndex")
                if (size < 0) corrupt("negative block size")
                if (size == 0) {
                    if (hash.any { it != 0.toByte() }) corrupt("final block hash not zero")
                    break
                }
                val data = guard { r.bytes(size) }
                if (!Bytes.constantTimeEquals(Bytes.sha256(data), hash)) corrupt("block $index hash does not match")
                blocks.write(data)
                Bytes.wipe(data)
                expectedIndex++
            }
            val payload = blocks.toByteArray()
            blocks.wipe()
            xml = if (header.compressed) gunzip(payload).also { Bytes.wipe(payload) } else payload
            val streamKey = header.protectedStreamKey!!
            val stream = innerStream(header.innerStreamId, streamKey)
            try {
                val headerHash = Bytes.sha256(file.copyOfRange(0, header.headerEnd))
                val parsed = KdbxXml.parse(xml, stream, headerHash)
                return parsed.toDatabase(info(header))
            } finally {
                stream?.wipe()
            }
        } finally {
            Bytes.wipe(cipherKey, plain, xml)
        }
    }

    private fun decrypt(header: KdbxHeader, key: ByteArray, data: ByteArray): ByteArray = when (header.cipher) {
        CipherKind.AES256 -> try {
            Aes.cbcDecrypt(key, header.encryptionIv, data)
        } catch (e: BadPaddingException) {
            corrupt("payload padding is invalid")
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            corrupt("payload is not a multiple of the AES block size")
        }
        CipherKind.CHACHA20 -> {
            val c = ChaCha20(key, header.encryptionIv, 0)
            val out = data.copyOf()
            c.xorInPlace(out)
            c.wipe()
            out
        }
        else -> throw KdbxException(KdbxException.Reason.UNSUPPORTED_CIPHER, "cipher ${header.cipher.label} is not supported")
    }

    private fun innerStream(id: Int, key: ByteArray?): StreamCipher? = when (id) {
        STREAM_NONE -> null
        STREAM_SALSA20 -> {
            val k = Bytes.sha256(key ?: corrupt("no inner stream key"))
            Salsa20(k, SALSA20_IV).also { Bytes.wipe(k) }
        }
        STREAM_CHACHA20 -> {
            val h = Bytes.sha512(key ?: corrupt("no inner stream key"))
            ChaCha20(h.copyOfRange(0, 32), h.copyOfRange(32, 44), 0).also { Bytes.wipe(h) }
        }
        else -> throw KdbxException(KdbxException.Reason.UNSUPPORTED_CIPHER, "inner stream $id is not supported")
    }

    private fun gunzip(data: ByteArray): ByteArray {
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                // Checked before each write, so the buffer never grows past the limit.
                val out = WipeableBuffer(minOf(data.size.toLong() * 4, INITIAL_GUNZIP_BYTES).toInt())
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size().toLong() + n > MAX_XML_BYTES) corrupt("decompressed payload larger than 64 MiB")
                    out.write(buf, 0, n)
                }
                Bytes.wipe(buf)
                return out.toByteArray().also { out.wipe() }
            }
        } catch (e: java.io.IOException) {
            throw KdbxException(KdbxException.Reason.CORRUPT, "gzip: ${e.message}", e)
        }
    }

    private fun info(header: KdbxHeader) = KdbxInfo(
        version = header.versionLabel,
        cipher = header.cipher.label,
        kdf = header.kdf.summary,
        compressed = header.compressed,
    )

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: KdbxFormatError) {
        throw KdbxException(KdbxException.Reason.CORRUPT, e.message ?: "format error", e)
    }

    private fun corrupt(message: String): Nothing = throw KdbxException(KdbxException.Reason.CORRUPT, message)
}
