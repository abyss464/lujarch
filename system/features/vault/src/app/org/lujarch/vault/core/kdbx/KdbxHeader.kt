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

import org.lujarch.vault.core.ByteReader
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.KdbxFormatError

/** Why a database could not be opened; the UI maps each reason to a message. */
class KdbxException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** Not a KeePass 2 file. */
        NOT_KDBX,
        /** KeePass 1 (.kdb) file. */
        KDB1,
        UNSUPPORTED_VERSION,
        /** Twofish or another cipher we do not implement; the message names it. */
        UNSUPPORTED_CIPHER,
        UNSUPPORTED_KDF,
        /** Password or key file wrong (header HMAC or start bytes do not match). */
        WRONG_KEY,
        /** Damaged file: hash or block HMAC mismatch, bad compression or XML. */
        CORRUPT,
    }
}

enum class CipherKind(val uuid: String, val label: String) {
    AES256("31c1f2e6bf714350be5805216afc5aff", "AES-256"),
    CHACHA20("d6038a2b8b6f4cb5a524339a31dbb59a", "ChaCha20"),
    TWOFISH("ad68f29f576f4bb9a36ad47af965346c", "Twofish"),
    AES128("61ab05a1946441c38d743a563df8dd35", "AES-128");

    companion object {
        fun of(uuid: ByteArray): CipherKind? = entries.firstOrNull { it.uuid == Bytes.hex(uuid) }
    }
}

sealed class Kdf {
    abstract val summary: KdfSummary

    class AesKdf(val rounds: Long, val seed: ByteArray) : Kdf() {
        override val summary: KdfSummary
            get() = KdfSummary("AES-KDF", rounds = rounds)
    }

    class Argon2Kdf(
        val type: Int,
        val salt: ByteArray,
        val parallelism: Long,
        val memoryBytes: Long,
        val iterations: Long,
        val version: Int,
        val secret: ByteArray?,
        val associated: ByteArray?,
    ) : Kdf() {
        override val summary: KdfSummary
            get() = KdfSummary(
                if (type == org.lujarch.vault.core.crypto.Argon2.TYPE_ID) "Argon2id" else "Argon2d",
                iterations = iterations,
                memoryBytes = memoryBytes,
                parallelism = parallelism,
            )
    }

    companion object {
        const val UUID_AES_KDBX3 = "c9d9f39a628a4460bf740d08c18a4fea"
        const val UUID_AES_KDBX4 = "7c02bb8279a74ac0927d114a00648238"
        const val UUID_ARGON2D = "ef636ddf8c29444b91f7a9a403e30a0c"
        const val UUID_ARGON2ID = "9e298b1956db4773b23dfc3ec6f0a1e6"
    }
}

/** KDF parameters for display; no salt or seed. */
data class KdfSummary(
    val name: String,
    val rounds: Long = 0,
    val iterations: Long = 0,
    val memoryBytes: Long = 0,
    val parallelism: Long = 0,
)

/** Outer header of a KDBX 3.1 or 4.x file. */
class KdbxHeader(
    val major: Int,
    val minor: Int,
    val cipher: CipherKind,
    val compressed: Boolean,
    val masterSeed: ByteArray,
    val encryptionIv: ByteArray,
    val kdf: Kdf,
    /** KDBX 3.1 only: inner stream key and id, start bytes. */
    val protectedStreamKey: ByteArray?,
    val streamStartBytes: ByteArray?,
    val innerStreamId: Int,
    /** Offset of the first byte after the header (end-of-header field included). */
    val headerEnd: Int,
) {
    val versionLabel: String
        get() = "$major.$minor"

    /**
     * SHA-256 over the KDF and its parameters, seed or salt included: two
     * files with the same fingerprint and composite key have the same
     * transformed key, so a cached transformed key applies exactly when this
     * matches. Not secret (the header is plaintext).
     */
    fun kdfFingerprint(): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        fun part(tag: String, value: ByteArray?) {
            md.update(tag.toByteArray())
            md.update(Bytes.i32leBytes(value?.size ?: -1))
            value?.let { md.update(it) }
        }
        when (val k = kdf) {
            is Kdf.AesKdf -> {
                part("aes-kdf", null)
                part("rounds", Bytes.u64leBytes(k.rounds))
                part("seed", k.seed)
            }
            is Kdf.Argon2Kdf -> {
                part("argon2", Bytes.i32leBytes(k.type))
                part("version", Bytes.i32leBytes(k.version))
                part("iterations", Bytes.u64leBytes(k.iterations))
                part("memory", Bytes.u64leBytes(k.memoryBytes))
                part("parallelism", Bytes.u64leBytes(k.parallelism))
                part("salt", k.salt)
                part("secret", k.secret)
                part("associated", k.associated)
            }
        }
        return md.digest()
    }

    companion object {
        private const val SIG1 = 0x9AA2D903L
        private const val SIG2_KDBX = 0xB54BFB67L
        private const val SIG2_KDB1 = 0xB54BFB65L

        fun parse(data: ByteArray): KdbxHeader {
            try {
                return parseInner(data)
            } catch (e: KdbxFormatError) {
                throw KdbxException(KdbxException.Reason.CORRUPT, "header: ${e.message}", e)
            }
        }

        private fun parseInner(data: ByteArray): KdbxHeader {
            if (data.size < 12) throw KdbxException(KdbxException.Reason.NOT_KDBX, "file too short")
            val r = ByteReader(data)
            val sig1 = r.u32()
            val sig2 = r.u32()
            if (sig1 != SIG1) throw KdbxException(KdbxException.Reason.NOT_KDBX, "not a KeePass file")
            if (sig2 == SIG2_KDB1) throw KdbxException(KdbxException.Reason.KDB1, "KeePass 1 database")
            if (sig2 != SIG2_KDBX) throw KdbxException(KdbxException.Reason.NOT_KDBX, "not a KeePass 2 file")
            val version = r.u32()
            val minor = (version and 0xffff).toInt()
            val major = (version ushr 16).toInt()
            if (major != 3 && major != 4) {
                throw KdbxException(KdbxException.Reason.UNSUPPORTED_VERSION, "KDBX version $major.$minor")
            }
            val v4 = major == 4

            var cipherId: ByteArray? = null
            var compression = 0L
            var masterSeed: ByteArray? = null
            var iv: ByteArray? = null
            var transformSeed: ByteArray? = null
            var transformRounds = 0L
            var kdfParams: Map<String, Any>? = null
            var streamKey: ByteArray? = null
            var startBytes: ByteArray? = null
            var streamId = 0

            while (true) {
                val id = r.u8()
                val len = if (v4) r.i32() else r.u16()
                if (len < 0) throw KdbxFormatError("negative header field length")
                val value = r.bytes(len)
                when (id) {
                    0 -> break
                    2 -> cipherId = value
                    3 -> compression = if (value.size >= 4) Bytes.u32le(value, 0) else -1
                    4 -> masterSeed = value
                    5 -> transformSeed = value
                    6 -> transformRounds = if (value.size >= 8) Bytes.u64le(value, 0) else -1
                    7 -> iv = value
                    8 -> streamKey = value
                    9 -> startBytes = value
                    10 -> streamId = if (value.size >= 4) Bytes.i32le(value, 0) else -1
                    11 -> kdfParams = VariantDictionary.parse(value)
                    // 1 comment, 12 public custom data: nothing to use.
                    else -> Bytes.wipe(value)
                }
            }

            val cipherBytes = cipherId ?: throw KdbxFormatError("no cipher id")
            val cipher = CipherKind.of(cipherBytes) ?: throw KdbxException(
                KdbxException.Reason.UNSUPPORTED_CIPHER, "unknown cipher ${Bytes.hex(cipherBytes)}"
            )
            if (cipher == CipherKind.TWOFISH || cipher == CipherKind.AES128) {
                throw KdbxException(KdbxException.Reason.UNSUPPORTED_CIPHER, "cipher ${cipher.label} is not supported")
            }
            if (compression != 0L && compression != 1L) throw KdbxFormatError("unknown compression $compression")
            val seed = masterSeed ?: throw KdbxFormatError("no master seed")
            if (seed.size != 32) throw KdbxFormatError("master seed of ${seed.size} bytes")
            val encIv = iv ?: throw KdbxFormatError("no encryption IV")
            val ivSize = if (cipher == CipherKind.CHACHA20) 12 else 16
            if (encIv.size != ivSize) throw KdbxFormatError("IV of ${encIv.size} bytes for ${cipher.label}")

            val kdf = if (v4) {
                kdfFromParams(kdfParams ?: throw KdbxFormatError("no KDF parameters"))
            } else {
                val s = transformSeed ?: throw KdbxFormatError("no transform seed")
                if (s.size != 32 || transformRounds < 0) throw KdbxFormatError("bad AES-KDF parameters")
                Kdf.AesKdf(transformRounds, s)
            }
            if (!v4) {
                if (streamKey == null || startBytes == null) throw KdbxFormatError("KDBX 3 header incomplete")
            }
            return KdbxHeader(
                major, minor, cipher, compression == 1L, seed, encIv, kdf,
                streamKey, startBytes, streamId, r.pos,
            )
        }

        private fun kdfFromParams(p: Map<String, Any>): Kdf {
            val uuid = (p["\$UUID"] as? ByteArray)?.let { Bytes.hex(it) } ?: throw KdbxFormatError("KDF without \$UUID")
            return when (uuid) {
                Kdf.UUID_AES_KDBX3, Kdf.UUID_AES_KDBX4 -> {
                    val rounds = p["R"] as? Long ?: throw KdbxFormatError("AES-KDF without rounds")
                    val seed = p["S"] as? ByteArray ?: throw KdbxFormatError("AES-KDF without seed")
                    if (seed.size != 32) throw KdbxFormatError("AES-KDF seed of ${seed.size} bytes")
                    Kdf.AesKdf(rounds, seed)
                }
                Kdf.UUID_ARGON2D, Kdf.UUID_ARGON2ID -> {
                    val salt = p["S"] as? ByteArray ?: throw KdbxFormatError("Argon2 without salt")
                    val parallelism = (p["P"] as? Long) ?: throw KdbxFormatError("Argon2 without parallelism")
                    val memory = (p["M"] as? Long) ?: throw KdbxFormatError("Argon2 without memory")
                    val iterations = (p["I"] as? Long) ?: throw KdbxFormatError("Argon2 without iterations")
                    val version = ((p["V"] as? Long) ?: 0x13L).toInt()
                    if (version != 0x10 && version != 0x13) {
                        throw KdbxException(KdbxException.Reason.UNSUPPORTED_KDF, "Argon2 version $version")
                    }
                    Kdf.Argon2Kdf(
                        type = if (uuid == Kdf.UUID_ARGON2ID) org.lujarch.vault.core.crypto.Argon2.TYPE_ID
                        else org.lujarch.vault.core.crypto.Argon2.TYPE_D,
                        salt = salt,
                        parallelism = parallelism,
                        memoryBytes = memory,
                        iterations = iterations,
                        version = version,
                        secret = p["K"] as? ByteArray,
                        associated = p["A"] as? ByteArray,
                    )
                }
                else -> throw KdbxException(KdbxException.Reason.UNSUPPORTED_KDF, "unknown KDF $uuid")
            }
        }
    }
}

/** KDBX 4 VariantDictionary (KDF parameters): values are Long, Boolean, String or ByteArray. */
object VariantDictionary {
    fun parse(data: ByteArray): Map<String, Any> {
        val r = ByteReader(data)
        val version = r.u16()
        if ((version and 0xff00) != 0x0100) throw KdbxFormatError("VariantDictionary version ${version.toString(16)}")
        val out = HashMap<String, Any>()
        while (true) {
            val type = r.u8()
            if (type == 0) break
            val keyLen = r.i32()
            if (keyLen < 0) throw KdbxFormatError("negative key length")
            val key = String(r.bytes(keyLen), Charsets.UTF_8)
            val valueLen = r.i32()
            if (valueLen < 0) throw KdbxFormatError("negative value length")
            val v = r.bytes(valueLen)
            out[key] = when (type) {
                0x04 -> if (v.size == 4) Bytes.u32le(v, 0) else throw KdbxFormatError("bad UInt32")
                0x05 -> if (v.size == 8) Bytes.u64le(v, 0) else throw KdbxFormatError("bad UInt64")
                0x08 -> if (v.size == 1) v[0].toInt() != 0 else throw KdbxFormatError("bad Bool")
                0x0C -> if (v.size == 4) Bytes.i32le(v, 0).toLong() else throw KdbxFormatError("bad Int32")
                0x0D -> if (v.size == 8) Bytes.u64le(v, 0) else throw KdbxFormatError("bad Int64")
                0x18 -> String(v, Charsets.UTF_8)
                0x42 -> v
                else -> throw KdbxFormatError("unknown VariantDictionary type $type")
            }
        }
        return out
    }
}
