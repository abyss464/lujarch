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

package org.lujarch.vault.core.aegis

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.Json
import org.lujarch.vault.core.Secret
import org.lujarch.vault.core.bool
import org.lujarch.vault.core.crypto.Aes
import org.lujarch.vault.core.crypto.Scrypt
import org.lujarch.vault.core.list
import org.lujarch.vault.core.long
import org.lujarch.vault.core.obj
import org.lujarch.vault.core.otp.Base32
import org.lujarch.vault.core.otp.OtpParams
import org.lujarch.vault.core.otp.OtpType
import org.lujarch.vault.core.str

class AegisException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** Not an Aegis vault file, or a version we do not know. */
        FORMAT,
        /** No password slot in the header. */
        NO_PASSWORD_SLOT,
        WRONG_PASSWORD,
        /** The stored master key does not open the database (vault replaced). */
        WRONG_KEY,
    }
}

/** One account of the Aegis vault. [otp] is null for types without a code here (Yandex, mOTP). */
class AegisEntry(
    val uuid: String,
    val type: String,
    val name: String,
    val issuer: String,
    val note: String,
    val groups: List<String>,
    val favorite: Boolean,
    val icon: ByteArray?,
    val iconMime: String?,
    val otp: OtpParams?,
) {
    fun wipe() {
        otp?.wipe()
    }
}

/**
 * Aegis vault version 1 (aegis.json): password slots (scrypt, AES-256-GCM
 * wrapped master key) and the AES-256-GCM encrypted database. Only password
 * slots are used; biometric slots are bound to Aegis' own Keystore key.
 * [edit] writes the database back the way Aegis does, so the file stays
 * one that Aegis can import.
 */
class AegisVault private constructor(private val root: Map<String, Any?>) {
    private val header = root.obj("header") ?: throw format("no header")

    val encrypted: Boolean
        get() = header["slots"] != null

    /** Derives the key of the first password slot that accepts [password] and returns the master key. */
    fun unlockWithPassword(password: ByteArray): ByteArray {
        val slots = header.list("slots") ?: throw format("vault is not encrypted")
        var tried = 0
        for (s in slots) {
            @Suppress("UNCHECKED_CAST")
            val slot = s as? Map<String, Any?> ?: continue
            if (slot.long("type") != SLOT_PASSWORD) continue
            tried++
            val n = slot.long("n")?.toInt() ?: throw format("slot without n")
            val r = slot.long("r")?.toInt() ?: throw format("slot without r")
            val p = slot.long("p")?.toInt() ?: throw format("slot without p")
            val salt = hex(slot.str("salt"), "salt")
            val derived = Scrypt.derive(password, salt, n, r, p, 32)
            try {
                val keyParams = slot.obj("key_params") ?: throw format("slot without key_params")
                val encrypted = hex(slot.str("key"), "key")
                val nonce = hex(keyParams.str("nonce"), "nonce")
                val tag = hex(keyParams.str("tag"), "tag")
                return try {
                    Aes.gcmDecrypt(derived, nonce, Bytes.concat(encrypted, tag))
                } catch (e: AEADBadTagException) {
                    continue
                }
            } finally {
                Bytes.wipe(derived)
            }
        }
        if (tried == 0) throw AegisException(AegisException.Reason.NO_PASSWORD_SLOT, "no password slot")
        throw AegisException(AegisException.Reason.WRONG_PASSWORD, "password does not open any slot")
    }

    /** Decrypts the database with [masterKey] and returns its entries. */
    fun open(masterKey: ByteArray): List<AegisEntry> {
        val plain = decrypt(masterKey)
        try {
            return parseDb(String(plain, Charsets.UTF_8))
        } finally {
            Bytes.wipe(plain)
        }
    }

    /**
     * Applies [change] to the database's entries (the parsed JSON objects,
     * so fields this reader does not know survive) and returns the vault
     * file with the database encrypted again under [masterKey] with a fresh
     * nonce; the slots and the rest of the file stay as they were.
     */
    fun edit(masterKey: ByteArray, change: (MutableList<MutableMap<String, Any?>>) -> Unit): ByteArray {
        val params = header.obj("params") ?: throw format("no params")
        val plain = decrypt(masterKey)
        val parsed = try {
            Json.parse(String(plain, Charsets.UTF_8))
        } catch (e: Json.ParseError) {
            throw format("database is not JSON: ${e.message}")
        } finally {
            Bytes.wipe(plain)
        }
        @Suppress("UNCHECKED_CAST")
        val db = parsed as? MutableMap<String, Any?> ?: throw format("database is not an object")
        @Suppress("UNCHECKED_CAST")
        val entries = db["entries"] as? MutableList<MutableMap<String, Any?>> ?: throw format("database without entries")
        change(entries)
        val newPlain = Json.write(db).toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val sealed = try {
            Aes.gcmEncrypt(masterKey, nonce, newPlain)
        } finally {
            Bytes.wipe(newPlain)
        }
        val newParams = LinkedHashMap(params)
        newParams["nonce"] = Bytes.hex(nonce)
        newParams["tag"] = Bytes.hex(sealed.copyOfRange(sealed.size - TAG_SIZE, sealed.size))
        val newHeader = LinkedHashMap(header)
        newHeader["params"] = newParams
        val newRoot = LinkedHashMap(root)
        newRoot["header"] = newHeader
        newRoot["db"] = Base64.getEncoder().encodeToString(sealed.copyOfRange(0, sealed.size - TAG_SIZE))
        return Json.write(newRoot).toByteArray(Charsets.UTF_8)
    }

    private fun decrypt(masterKey: ByteArray): ByteArray {
        val params = header.obj("params") ?: throw format("no params")
        val nonce = hex(params.str("nonce"), "nonce")
        val tag = hex(params.str("tag"), "tag")
        val ciphertext = try {
            Base64.getMimeDecoder().decode(root.str("db") ?: throw format("db is not a string"))
        } catch (e: IllegalArgumentException) {
            throw format("db is not base64")
        }
        return try {
            Aes.gcmDecrypt(masterKey, nonce, Bytes.concat(ciphertext, tag))
        } catch (e: AEADBadTagException) {
            throw AegisException(AegisException.Reason.WRONG_KEY, "master key does not open the database", e)
        }
    }

    /**
     * The database's entries as stored (JSON objects), for a merge into
     * another vault: [masterKey] opens an encrypted file, null reads an
     * unencrypted export.
     */
    fun rawEntries(masterKey: ByteArray?): List<MutableMap<String, Any?>> {
        val db: Any? = if (masterKey == null) {
            if (encrypted) throw format("vault is encrypted")
            root["db"]
        } else {
            val plain = decrypt(masterKey)
            try {
                Json.parse(String(plain, Charsets.UTF_8))
            } catch (e: Json.ParseError) {
                throw format("database is not JSON: ${e.message}")
            } finally {
                Bytes.wipe(plain)
            }
        }
        @Suppress("UNCHECKED_CAST")
        val entries = (db as? Map<String, Any?>)?.list("entries") ?: throw format("database without entries")
        @Suppress("UNCHECKED_CAST")
        return entries.filterIsInstance<MutableMap<*, *>>() as List<MutableMap<String, Any?>>
    }

    /** Entries of an unencrypted vault (export without a password). */
    fun openPlain(): List<AegisEntry> {
        @Suppress("UNCHECKED_CAST")
        val db = root["db"] as? Map<String, Any?> ?: throw format("db is not an object")
        return parseEntries(db)
    }

    companion object {
        private const val SLOT_PASSWORD = 1L
        private const val NONCE_SIZE = 12
        private const val TAG_SIZE = 16

        /**
         * Adds the [incoming] entries that [entries] does not hold yet (the
         * same uuid, or the same type and key) and returns how many were
         * added. Group memberships are dropped: the groups are the other
         * vault's.
         */
        fun merge(entries: MutableList<MutableMap<String, Any?>>, incoming: List<MutableMap<String, Any?>>): Int {
            val uuids = entries.mapNotNullTo(HashSet()) { it["uuid"] as? String }
            val keys = entries.mapNotNullTo(HashSet()) { identity(it) }
            var added = 0
            for (e in incoming) {
                val uuid = e["uuid"] as? String
                val id = identity(e)
                if (uuid in uuids || (id != null && id in keys)) continue
                e.remove("group")
                e["groups"] = ArrayList<Any?>()
                entries.add(e)
                uuid?.let { uuids += it }
                id?.let { keys += it }
                added++
            }
            return added
        }

        /** Type and normalized key of an entry: the same account under another name or uuid. */
        private fun identity(e: Map<String, Any?>): String? {
            @Suppress("UNCHECKED_CAST")
            val info = e["info"] as? Map<String, Any?> ?: return null
            val secret = info.str("secret")?.uppercase()?.filter { it != ' ' && it != '=' }?.takeIf { it.isNotEmpty() } ?: return null
            return "${e["type"]}:$secret"
        }

        /** A new entry for [p] in the shape Aegis writes (database version 3). */
        fun newEntry(p: OtpParams): MutableMap<String, Any?> {
            val key = p.secret.revealBytes()
            val secret = try {
                Base32.encode(key)
            } finally {
                Bytes.wipe(key)
            }
            val info = LinkedHashMap<String, Any?>()
            info["secret"] = secret
            info["algo"] = p.algorithm.uppercase().replace("-", "").removePrefix("HMAC")
            info["digits"] = p.digits.toLong()
            if (p.type == OtpType.HOTP) info["counter"] = p.counter else info["period"] = p.period.toLong()
            val e = LinkedHashMap<String, Any?>()
            e["type"] = when (p.type) {
                OtpType.TOTP -> "totp"
                OtpType.HOTP -> "hotp"
                OtpType.STEAM -> "steam"
            }
            e["uuid"] = UUID.randomUUID().toString()
            e["name"] = p.account
            e["issuer"] = p.issuer
            e["note"] = ""
            e["favorite"] = false
            e["icon"] = null
            e["info"] = info
            e["groups"] = ArrayList<Any?>()
            return e
        }

        fun parse(file: ByteArray): AegisVault {
            val root = try {
                Json.parse(String(file, Charsets.UTF_8))
            } catch (e: Json.ParseError) {
                throw AegisException(AegisException.Reason.FORMAT, "not JSON: ${e.message}", e)
            }
            @Suppress("UNCHECKED_CAST")
            val obj = root as? Map<String, Any?> ?: throw format("not a JSON object")
            val version = obj.long("version")
            if (version != 1L) throw format("vault version $version")
            return AegisVault(obj)
        }

        private fun parseDb(json: String): List<AegisEntry> {
            @Suppress("UNCHECKED_CAST")
            val db = try {
                Json.parse(json) as? Map<String, Any?>
            } catch (e: Json.ParseError) {
                throw format("database is not JSON: ${e.message}")
            } ?: throw format("database is not an object")
            return parseEntries(db)
        }

        private fun parseEntries(db: Map<String, Any?>): List<AegisEntry> {
            val version = db.long("version") ?: throw format("database without version")
            if (version < 1 || version > 3) throw format("database version $version")
            // Version 3 keeps groups in a list referenced by uuid; version 2 names the group in the entry.
            val groupNames = HashMap<String, String>()
            db.list("groups")?.forEach { g ->
                @Suppress("UNCHECKED_CAST")
                val m = g as? Map<String, Any?> ?: return@forEach
                val uuid = m.str("uuid") ?: return@forEach
                groupNames[uuid] = m.str("name") ?: ""
            }
            val entries = db.list("entries") ?: throw format("database without entries")
            val out = ArrayList<AegisEntry>(entries.size)
            try {
                for (e in entries) {
                    @Suppress("UNCHECKED_CAST")
                    val m = e as? Map<String, Any?> ?: continue
                    out.add(parseEntry(m, groupNames))
                }
            } catch (t: Throwable) {
                out.forEach { it.wipe() }
                throw t
            }
            return out
        }

        private fun parseEntry(m: Map<String, Any?>, groupNames: Map<String, String>): AegisEntry {
            val type = m.str("type") ?: throw format("entry without type")
            val name = m.str("name") ?: ""
            val issuer = m.str("issuer") ?: ""
            val info = m.obj("info") ?: throw format("entry without info")
            val groups = m.list("groups")?.mapNotNull { (it as? String)?.let { id -> groupNames[id] } }
                ?: listOfNotNull(m.str("group")?.takeIf { it.isNotEmpty() })
            val otpType = when (type) {
                "totp" -> OtpType.TOTP
                "hotp" -> OtpType.HOTP
                "steam" -> OtpType.STEAM
                else -> null
            }
            val otp = otpType?.let {
                val secretText = info.str("secret") ?: throw format("entry without secret")
                val key = Base32.decode(secretText) ?: throw format("secret of an entry is not Base32")
                OtpParams(
                    type = it,
                    secret = Secret(key),
                    algorithm = info.str("algo") ?: "SHA1",
                    digits = info.long("digits")?.toInt() ?: if (it == OtpType.STEAM) 5 else 6,
                    period = info.long("period")?.toInt()?.takeIf { p -> p > 0 } ?: 30,
                    counter = info.long("counter") ?: 0,
                    issuer = issuer,
                    account = name,
                )
            }
            val icon = m.str("icon")?.let {
                try {
                    Base64.getMimeDecoder().decode(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            return AegisEntry(
                uuid = m.str("uuid") ?: "",
                type = type,
                name = name,
                issuer = issuer,
                note = m.str("note") ?: "",
                groups = groups,
                favorite = m.bool("favorite") ?: false,
                icon = icon,
                iconMime = m.str("icon_mime"),
                otp = otp,
            )
        }

        private fun hex(s: String?, what: String): ByteArray =
            s?.let { Bytes.fromHex(it) } ?: throw format("$what is not hex")

        private fun format(message: String) = AegisException(AegisException.Reason.FORMAT, message)
    }
}
