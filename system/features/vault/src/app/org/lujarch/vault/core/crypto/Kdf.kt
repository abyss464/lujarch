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

package org.lujarch.vault.core.crypto

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.lujarch.vault.core.Bytes

/**
 * Argon2 through JNI (jni/argon2_jni.c, reference implementation). The
 * library is liblujarch_vault_jni: inside the APK on the phone, built by
 * tests/run.sh for the host tests.
 */
object Argon2 {
    const val TYPE_D = 0
    const val TYPE_ID = 2
    const val VERSION_13 = 0x13

    private val loaded: Throwable? by lazy {
        try {
            System.loadLibrary("lujarch_vault_jni")
            null
        } catch (e: Throwable) {
            e
        }
    }

    fun hash(
        type: Int,
        version: Int,
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray?,
        associated: ByteArray?,
        iterations: Long,
        memoryBytes: Long,
        parallelism: Long,
        outLength: Int = 32,
    ): ByteArray {
        loaded?.let { throw IllegalStateException("Argon2 library not available: ${it.message}", it) }
        if (iterations !in 1..Int.MAX_VALUE.toLong()) throw IllegalArgumentException("Argon2 iterations out of range")
        if (parallelism !in 1..0xFFFFFF) throw IllegalArgumentException("Argon2 parallelism out of range")
        val kib = memoryBytes / 1024
        // 4 GiB is far above anything a phone can allocate; the limit keeps the int conversion exact.
        if (kib < 8 || kib > 4L * 1024 * 1024) throw IllegalArgumentException("Argon2 memory out of range")
        return nativeHash(
            type, version, password, salt, secret, associated,
            iterations.toInt(), kib.toInt(), parallelism.toInt(), outLength,
        )
    }

    @JvmStatic
    private external fun nativeHash(
        type: Int,
        version: Int,
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray?,
        associated: ByteArray?,
        iterations: Int,
        memoryKiB: Int,
        parallelism: Int,
        outLength: Int,
    ): ByteArray
}

/** scrypt (RFC 7914) for the Aegis password slot; only runs at setup. */
object Scrypt {
    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "scrypt N must be a power of two" }
        require(r in 1..64 && p in 1..16) { "scrypt r/p out of range" }
        require(n.toLong() * r * 128 <= 512L * 1024 * 1024) { "scrypt memory too large" }
        val blockLen = 128 * r
        val b = pbkdf2Sha256(password, salt, p * blockLen)
        val x = IntArray(32 * r)
        val v = IntArray(32 * r * n)
        val y = IntArray(32 * r)
        val t = IntArray(16)
        val o = IntArray(16)
        try {
            for (i in 0 until p) {
                val off = i * blockLen
                for (k in 0 until 32 * r) x[k] = Bytes.i32le(b, off + 4 * k)
                roMix(x, v, y, t, o, n, r)
                for (k in 0 until 32 * r) {
                    val w = x[k]
                    b[off + 4 * k] = w.toByte()
                    b[off + 4 * k + 1] = (w ushr 8).toByte()
                    b[off + 4 * k + 2] = (w ushr 16).toByte()
                    b[off + 4 * k + 3] = (w ushr 24).toByte()
                }
            }
            return pbkdf2Sha256(password, b, dkLen)
        } finally {
            Bytes.wipe(b)
            x.fill(0)
            v.fill(0)
            y.fill(0)
            t.fill(0)
            o.fill(0)
        }
    }

    private fun roMix(x: IntArray, v: IntArray, y: IntArray, t: IntArray, o: IntArray, n: Int, r: Int) {
        val words = 32 * r
        for (i in 0 until n) {
            System.arraycopy(x, 0, v, i * words, words)
            blockMix(x, y, t, o, r)
        }
        for (i in 0 until n) {
            // Integerify: first word of the last 64-byte block, reduced mod N (N is a power of two).
            val j = x[(2 * r - 1) * 16] and (n - 1)
            val base = j * words
            for (k in 0 until words) x[k] = x[k] xor v[base + k]
            blockMix(x, y, t, o, r)
        }
    }

    /** scrypt BlockMix with Salsa20/8; [t] and [o] are 16-word scratch arrays. */
    private fun blockMix(b: IntArray, y: IntArray, t: IntArray, o: IntArray, r: Int) {
        System.arraycopy(b, (2 * r - 1) * 16, t, 0, 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) t[k] = t[k] xor b[i * 16 + k]
            Salsa20.salsaCore(t, o, 8)
            System.arraycopy(o, 0, t, 0, 16)
            // Even blocks go to the first half of the output, odd blocks to the second.
            val dest = (i / 2 + (i % 2) * r) * 16
            System.arraycopy(t, 0, y, dest, 16)
        }
        System.arraycopy(y, 0, b, 0, 32 * r)
    }

    /** PBKDF2-HMAC-SHA256 with one iteration over raw password bytes. */
    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty key is not accepted by SecretKeySpec; HMAC pads the key with zeros anyway.
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        val out = ByteArray(dkLen)
        var block = 1
        var pos = 0
        while (pos < dkLen) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val u = mac.doFinal()
            val n = minOf(32, dkLen - pos)
            System.arraycopy(u, 0, out, pos, n)
            Bytes.wipe(u)
            pos += n
            block++
        }
        return out
    }
}

/**
 * PBKDF2 (RFC 8018) over the password's own bytes with the platform's HMAC: the
 * platform's PBKDF2 key factories take the password as characters and choose
 * their own byte encoding, which the exports this reads do not share.
 */
object Pbkdf2 {
    fun derive(macAlgorithm: String, password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations in 1..10_000_000) { "PBKDF2 iterations out of range" }
        val mac = Mac.getInstance(macAlgorithm)
        // An empty key is not accepted by SecretKeySpec; HMAC pads the key with zeros anyway.
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, macAlgorithm))
        val hLen = mac.macLength
        val out = ByteArray(dkLen)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        var block = 1
        var pos = 0
        try {
            while (pos < dkLen) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                mac.doFinal(u, 0)
                u.copyInto(t)
                for (i in 1 until iterations) {
                    mac.update(u)
                    mac.doFinal(u, 0)
                    for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                val n = minOf(hLen, dkLen - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n
                block++
            }
            return out
        } finally {
            Bytes.wipe(u, t)
        }
    }
}

/** HKDF-Expand (RFC 5869) with HMAC-SHA256: [prk] is used as the pseudorandom key as it is. */
object Hkdf {
    fun expandSha256(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "HKDF length out of range" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(prev)
            mac.update(info)
            mac.update(counter.toByte())
            val t = mac.doFinal()
            Bytes.wipe(prev)
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            prev = t
            counter++
        }
        Bytes.wipe(prev)
        return out
    }
}

/** AES operations of the readers, through the platform's JCA provider. */
object Aes {
    fun cbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun gcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextAndTag)
    }

    /** The ciphertext followed by the 16-byte tag. */
    fun gcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(plain)
    }

    /** KeePass AES-KDF: [rounds] AES-256-ECB encryptions of both halves of [key], then SHA-256. */
    fun kdf(key: ByteArray, seed: ByteArray, rounds: Long): ByteArray {
        require(key.size == 32 && seed.size == 32) { "AES-KDF needs a 32-byte key and seed" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"))
        val buf = key.copyOf()
        try {
            var i = 0L
            while (i < rounds) {
                // update() on both 16-byte blocks at once: one call per round keeps the loop cheap.
                cipher.update(buf, 0, 32, buf, 0)
                i++
            }
            return Bytes.sha256(buf)
        } finally {
            Bytes.wipe(buf)
        }
    }
}
