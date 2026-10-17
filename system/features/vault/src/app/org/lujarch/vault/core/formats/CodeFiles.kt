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


package org.lujarch.vault.core.formats

import java.net.URLDecoder
import java.util.Base64
import javax.crypto.AEADBadTagException
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.Json
import org.lujarch.vault.core.crypto.Aes
import org.lujarch.vault.core.crypto.Argon2
import org.lujarch.vault.core.crypto.Pbkdf2
import org.lujarch.vault.core.aegis.AegisVault
import org.lujarch.vault.core.list
import org.lujarch.vault.core.long
import org.lujarch.vault.core.obj
import org.lujarch.vault.core.otp.Base32
import org.lujarch.vault.core.otp.InvalidOtp
import org.lujarch.vault.core.otp.Otp
import org.lujarch.vault.core.otp.OtpParams
import org.lujarch.vault.core.otp.OtpType
import org.lujarch.vault.core.str

/**
 * The files of authenticator apps whose accounts the vault takes in, told apart by content:
 * Aegis (plain or encrypted), 2FAS (plain or encrypted), andOTP (plain JSON or encrypted),
 * FreeOTP+ (JSON), Bitwarden and Bitwarden Authenticator (JSON, plain or password-protected),
 * Proton Authenticator (JSON, plain or encrypted), Steam Guard's own file, and text with otpauth://, steam:// or Google Authenticator's
 * otpauth-migration:// links, one per line (Ente Auth's plain export, FreeOTP+'s URI list), or
 * other JSON that carries such links. Accounts come out in the shape of the vault's own file.
 */
object CodeFiles {
    /** Accounts read from a file, and how many of its accounts could not be read. */
    class Accounts(val entries: List<MutableMap<String, Any?>>, val skipped: Int)

    class Detected internal constructor(
        /** The format's name, for the log. */
        val format: String,
        /** The file needs its password. */
        val encrypted: Boolean,
        /** An encrypted Aegis vault: it becomes the vault's own file as it is. */
        val store: Boolean,
        private val reader: (ByteArray?) -> Accounts,
    ) {
        /** The accounts; [password] opens an encrypted file. */
        fun accounts(password: ByteArray?): Accounts {
            if (encrypted && password == null) throw FileFormatException(FileFormatException.Reason.WRONG_PASSWORD, "no password")
            return reader(password)
        }
    }

    private const val MIGRATION = "otpauth-migration://"
    private val LINK = Regex("(?i)(?:otpauth|otpauth-migration|steam)://[^\\s,\"']+")
    private val PROTON_AAD = "proton.authenticator.export.v1".toByteArray()
    private const val TWOFAS_ITERATIONS = 10_000
    private const val ANDOTP_SALT = 12
    private const val GCM_NONCE = 12
    private const val GCM_TAG = 16

    fun detect(data: ByteArray): Detected {
        val text = textOf(data)
        if (text == null) {
            // andOTP's encrypted backup is the one binary format: iterations, salt, nonce, ciphertext.
            if (data.size >= 4 + ANDOTP_SALT + GCM_NONCE + GCM_TAG) return Detected("andOTP", true, false) { pw -> andOtpEncrypted(data, pw!!) }
            notCodes("neither text nor a known binary format")
        }
        val start = text.trimStart()
        if (start.startsWith("{") || start.startsWith("[")) {
            val root = try {
                Json.parse(start)
            } catch (e: Json.ParseError) {
                notCodes("JSON that does not parse: ${e.message}")
            }
            return detectJson(data, root)
        }
        return Detected("otpauth links", false, false) { lines(text) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun detectJson(data: ByteArray, root: Any?): Detected {
        if (root is List<*>) {
            if (root.isNotEmpty() && root.all { it is Map<*, *> && it.containsKey("secret") }) {
                return Detected("andOTP", false, false) { andOtp(root) }
            }
            return Detected("otpauth links", false, false) { links(root) }
        }
        val obj = root as? Map<String, Any?> ?: notCodes("JSON that is neither an object nor a list")
        return when {
            obj.containsKey("header") && obj.containsKey("db") -> {
                val vault = AegisVault.parse(data)
                if (vault.encrypted) {
                    Detected("Aegis", true, true) { pw ->
                        val master = vault.unlockWithPassword(pw!!)
                        try {
                            Accounts(vault.rawEntries(master), 0)
                        } finally {
                            Bytes.wipe(master)
                        }
                    }
                } else {
                    Detected("Aegis", false, false) { Accounts(vault.rawEntries(null), 0) }
                }
            }
            obj.containsKey("schemaVersion") && (obj.containsKey("services") || obj.containsKey("servicesEncrypted")) -> {
                val sealed = obj.str("servicesEncrypted")?.takeIf { it.isNotEmpty() }
                if (sealed != null) {
                    Detected("2FAS", true, false) { pw -> twoFas(twoFasDecrypt(sealed, pw!!)) }
                } else {
                    Detected("2FAS", false, false) { twoFas(obj.list("services").orEmpty()) }
                }
            }
            obj["tokens"] is List<*> -> Detected("FreeOTP+", false, false) { freeOtp(obj.list("tokens").orEmpty()) }
            Bitwarden.isExport(obj) -> {
                val sealed = Bitwarden.encrypted(obj)
                if (sealed != null) {
                    Detected("Bitwarden", true, false) { pw ->
                        val key = sealed.deriveKey(pw!!)
                        try {
                            bitwarden(sealed.decrypt(key))
                        } finally {
                            Bytes.wipe(key)
                        }
                    }
                } else {
                    Detected("Bitwarden", false, false) { bitwarden(obj) }
                }
            }
            obj.list("entries")?.any { (it as? Map<*, *>)?.get("content") is Map<*, *> } == true ->
                Detected("Proton Authenticator", false, false) { proton(obj.list("entries").orEmpty()) }
            obj["shared_secret"] is String || (obj["accounts"] as? Map<*, *>)?.values?.any { (it as? Map<*, *>)?.get("shared_secret") is String } == true ->
                Detected("Steam", false, false) { steam(obj) }
            obj.containsKey("version") && obj["salt"] is String && obj["content"] is String ->
                Detected("Proton Authenticator", true, false) { pw -> protonEncrypted(obj, pw!!) }
            else -> Detected("otpauth links", false, false) { links(obj) }
        }
    }

    /** Collects accounts in the vault file's shape; an account that cannot be read is counted, not added. */
    private class Collector {
        val entries = ArrayList<MutableMap<String, Any?>>()
        var skipped = 0

        fun add(note: String = "", favorite: Boolean = false, make: () -> OtpParams) {
            val p = try {
                make()
            } catch (e: InvalidOtp) {
                skipped++
                return
            } catch (e: IllegalArgumentException) {
                skipped++
                return
            }
            try {
                val e = AegisVault.newEntry(p)
                e["note"] = note
                e["favorite"] = favorite
                entries.add(e)
            } finally {
                p.wipe()
            }
        }

        /** An otpauth or steam link; [issuer] and [account] fill in what the link lacks. */
        fun link(text: String, issuer: String = "", account: String = "", note: String = "", favorite: Boolean = false) {
            val t = text.trim()
            if (t.startsWith(MIGRATION, ignoreCase = true)) {
                try {
                    migration(t, this)
                } catch (e: FileFormatException) {
                    skipped++
                }
                return
            }
            add(note, favorite) {
                val p = Otp.parseLink(t, issuer, account)
                if (p.issuer.isNotEmpty() && p.account.isNotEmpty()) p else
                    OtpParams(p.type, p.secret, p.algorithm, p.digits, p.period, p.counter, p.issuer.ifEmpty { issuer }, p.account.ifEmpty { account })
            }
        }

        fun result() = Accounts(entries, skipped)
    }

    private fun type(name: String?): OtpType = when (name?.uppercase()) {
        "HOTP" -> OtpType.HOTP
        "STEAM" -> OtpType.STEAM
        else -> OtpType.TOTP
    }

    private fun base32(text: String?): ByteArray = Base32.decode(text.orEmpty()) ?: throw InvalidOtp("secret is not Base32")

    // ---- 2FAS ----

    /** servicesEncrypted: "ciphertext and tag:salt:iv", each base64; PBKDF2-SHA256 (10 000 rounds), AES-256-GCM. */
    private fun twoFasDecrypt(sealed: String, password: ByteArray): List<Any?> {
        val parts = sealed.split(':')
        if (parts.size < 3) damaged("servicesEncrypted without salt and iv")
        val (data, salt, iv) = try {
            parts.take(3).map { Base64.getDecoder().decode(it) }
        } catch (e: IllegalArgumentException) {
            damaged("servicesEncrypted is not base64", e)
        }
        val key = Pbkdf2.derive("HmacSHA256", password, salt, TWOFAS_ITERATIONS, 32)
        val plain = try {
            Aes.gcmDecrypt(key, iv, data)
        } catch (e: AEADBadTagException) {
            throw FileFormatException(FileFormatException.Reason.WRONG_PASSWORD, "the password does not open the 2FAS file", e)
        } finally {
            Bytes.wipe(key)
        }
        try {
            return Json.parse(String(plain, Charsets.UTF_8)) as? List<Any?> ?: damaged("services are not a list")
        } catch (e: Json.ParseError) {
            damaged("services are not JSON: ${e.message}", e)
        } finally {
            Bytes.wipe(plain)
        }
    }

    private fun twoFas(services: List<Any?>): Accounts {
        val c = Collector()
        for (s in services) {
            @Suppress("UNCHECKED_CAST")
            val m = s as? Map<String, Any?> ?: continue
            val otp = m.obj("otp").orEmpty()
            val t = type(otp.str("tokenType"))
            c.add {
                Otp.of(
                    t, base32(m.str("secret")), otp.str("algorithm").orEmpty(),
                    otp.long("digits")?.toInt() ?: if (t == OtpType.STEAM) 5 else 6,
                    otp.long("period")?.toInt()?.takeIf { it > 0 } ?: 30, otp.long("counter") ?: 0,
                    otp.str("issuer")?.takeIf { it.isNotBlank() } ?: m.str("name").orEmpty(),
                    otp.str("account")?.takeIf { it.isNotBlank() } ?: otp.str("label").orEmpty(),
                )
            }
        }
        return c.result()
    }

    // ---- andOTP ----

    private fun andOtp(entries: List<Any?>): Accounts {
        val c = Collector()
        for (e in entries) {
            @Suppress("UNCHECKED_CAST")
            val m = e as? Map<String, Any?> ?: continue
            val label = m.str("label").orEmpty()
            var issuer = m.str("issuer").orEmpty()
            var account = label
            if (!m.containsKey("issuer") && label.contains(" - ")) {
                issuer = label.substringBefore(" - ")
                account = label.substringAfter(" - ")
            }
            val t = type(m.str("type"))
            c.add {
                Otp.of(
                    t, base32(m.str("secret")), m.str("algorithm").orEmpty(),
                    m.long("digits")?.toInt() ?: if (t == OtpType.STEAM) 5 else 6,
                    m.long("period")?.toInt()?.takeIf { it > 0 } ?: 30, m.long("counter") ?: 0, issuer, account,
                )
            }
        }
        return c.result()
    }

    /**
     * andOTP's encrypted backup: iterations (4 bytes, big endian), salt (12), nonce (12) and the
     * AES-256-GCM ciphertext with its tag, the key PBKDF2-SHA1 of the password; older backups
     * are the nonce and ciphertext alone, the key SHA-256 of the password.
     */
    private fun andOtpEncrypted(data: ByteArray, password: ByteArray): Accounts {
        val iterations = ((data[0].toInt() and 0xff) shl 24) or ((data[1].toInt() and 0xff) shl 16) or
            ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        val attempts = ArrayList<() -> ByteArray?>()
        if (iterations in 1..10_000_000) {
            attempts += {
                val salt = data.copyOfRange(4, 4 + ANDOTP_SALT)
                val key = Pbkdf2.derive("HmacSHA1", password, salt, iterations, 32)
                try {
                    gcmOrNull(key, data, 4 + ANDOTP_SALT)
                } finally {
                    Bytes.wipe(key)
                }
            }
        }
        attempts += {
            val key = Bytes.sha256(password)
            try {
                gcmOrNull(key, data, 0)
            } finally {
                Bytes.wipe(key)
            }
        }
        for (attempt in attempts) {
            val plain = attempt() ?: continue
            try {
                @Suppress("UNCHECKED_CAST")
                val list = Json.parse(String(plain, Charsets.UTF_8)) as? List<Any?> ?: damaged("andOTP backup is not a list")
                return andOtp(list)
            } catch (e: Json.ParseError) {
                damaged("andOTP backup is not JSON: ${e.message}", e)
            } finally {
                Bytes.wipe(plain)
            }
        }
        throw FileFormatException(FileFormatException.Reason.WRONG_PASSWORD, "the password does not open the andOTP backup")
    }

    private fun gcmOrNull(key: ByteArray, data: ByteArray, offset: Int): ByteArray? {
        val nonce = data.copyOfRange(offset, offset + GCM_NONCE)
        return try {
            Aes.gcmDecrypt(key, nonce, data.copyOfRange(offset + GCM_NONCE, data.size))
        } catch (e: AEADBadTagException) {
            null
        }
    }

    // ---- FreeOTP+ ----

    private fun freeOtp(tokens: List<Any?>): Accounts {
        val c = Collector()
        for (t in tokens) {
            @Suppress("UNCHECKED_CAST")
            val m = t as? Map<String, Any?> ?: continue
            val key = m.list("secret")?.let { list -> ByteArray(list.size) { i -> ((list[i] as? Long) ?: 0L).toByte() } }
            val type = type(m.str("type"))
            c.add {
                Otp.of(
                    type, key ?: throw InvalidOtp("no secret"), m.str("algo").orEmpty(),
                    m.long("digits")?.toInt() ?: if (type == OtpType.STEAM) 5 else 6,
                    m.long("period")?.toInt()?.takeIf { it > 0 } ?: 30, m.long("counter") ?: 0,
                    m.str("issuerExt")?.takeIf { it.isNotBlank() } ?: m.str("issuerInt").orEmpty(), m.str("label").orEmpty(),
                )
            }
        }
        return c.result()
    }

    // ---- Bitwarden, Proton Authenticator, links ----

    private fun bitwarden(root: Map<String, Any?>): Accounts {
        val c = Collector()
        for ((name, totp) in Bitwarden.codes(root)) {
            if (totp.contains("://")) {
                c.link(totp, issuer = name)
            } else {
                // A bare key: Bitwarden's default settings.
                c.add { Otp.of(OtpType.TOTP, base32(totp), "SHA1", 6, 30, 0, name, "") }
            }
        }
        return c.result()
    }

    private fun proton(entries: List<Any?>): Accounts {
        val c = Collector()
        for (e in entries) {
            val m = e as? Map<*, *> ?: continue
            val content = m["content"] as? Map<*, *> ?: continue
            val uri = content["uri"] as? String ?: continue
            c.link(uri, account = (content["name"] as? String).orEmpty(), note = (m["note"] as? String).orEmpty())
        }
        return c.result()
    }

    /**
     * Proton Authenticator's encrypted export: {version, salt, content}, the key Argon2id of the
     * password (2 passes, 19 MiB, 1 lane), the content a nonce and AES-256-GCM ciphertext with
     * the export's name as associated data; inside, the plain export.
     */
    private fun protonEncrypted(obj: Map<String, Any?>, password: ByteArray): Accounts {
        val (salt, content) = try {
            Base64.getDecoder().decode(obj["salt"] as String) to Base64.getDecoder().decode(obj["content"] as String)
        } catch (e: IllegalArgumentException) {
            damaged("salt or content is not base64", e)
        }
        if (content.size < GCM_NONCE + GCM_TAG) damaged("content too short")
        val key = Argon2.hash(Argon2.TYPE_ID, Argon2.VERSION_13, password, salt, null, null, 2, 19L * 1024 * 1024, 1, 32)
        val plain = try {
            Aes.gcmDecrypt(key, content.copyOfRange(0, GCM_NONCE), content.copyOfRange(GCM_NONCE, content.size), PROTON_AAD)
        } catch (e: AEADBadTagException) {
            throw FileFormatException(FileFormatException.Reason.WRONG_PASSWORD, "the password does not open the export", e)
        } finally {
            Bytes.wipe(key)
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val inner = Json.parse(String(plain, Charsets.UTF_8)) as? Map<String, Any?> ?: damaged("decrypted export is not an object")
            return proton(inner.list("entries").orEmpty())
        } catch (e: Json.ParseError) {
            damaged("decrypted export is not JSON: ${e.message}", e)
        } finally {
            Bytes.wipe(plain)
        }
    }

    /** Steam Guard's own file: one account, or several under "accounts"; the key is the base64 shared_secret. */
    private fun steam(obj: Map<String, Any?>): Accounts {
        val c = Collector()
        val accounts = (obj["accounts"] as? Map<*, *>)?.values?.mapNotNull { it as? Map<*, *> } ?: listOf(obj)
        for (a in accounts) {
            val secret = a["shared_secret"] as? String ?: continue
            c.add {
                val key = try {
                    Base64.getMimeDecoder().decode(secret)
                } catch (e: IllegalArgumentException) {
                    throw InvalidOtp("shared_secret is not base64")
                }
                Otp.of(OtpType.STEAM, key, "SHA1", 5, 30, 0, "Steam", (a["account_name"] as? String).orEmpty())
            }
        }
        return c.result()
    }

    /** Every otpauth, steam or migration link anywhere in a JSON value. */
    private fun links(root: Any?): Accounts {
        val c = Collector()
        fun walk(v: Any?, depth: Int) {
            when (v) {
                is String -> if (isLink(v)) c.link(v)
                is Map<*, *> -> v.values.forEach { walk(it, depth + 1) }
                is List<*> -> v.forEach { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        if (c.entries.isEmpty() && c.skipped == 0) notCodes("JSON without otpauth links")
        return c.result()
    }

    /**
     * The links of a text: a line that is a link, or the links inside a line (a CSV export's
     * column); Ente Auth marks deleted accounts as trashed in codeDisplay.
     */
    private fun lines(text: String): Accounts {
        val c = Collector()
        for (line in text.lineSequence()) {
            val t = line.trim()
            val links = if (isLink(t)) listOf(t) else LINK.findAll(t).map { it.value }.toList()
            for (link in links) {
                if (link.contains("codeDisplay=", ignoreCase = true) && decode(link).replace(" ", "").contains("\"trashed\":true")) continue
                c.link(link)
            }
        }
        if (c.entries.isEmpty() && c.skipped == 0) notCodes("text without otpauth links")
        return c.result()
    }

    private fun isLink(s: String): Boolean =
        s.startsWith("otpauth://", ignoreCase = true) || s.startsWith(MIGRATION, ignoreCase = true) ||
            s.startsWith("steam://", ignoreCase = true)

    fun isMigration(text: String): Boolean = text.trim().startsWith(MIGRATION, ignoreCase = true)

    /** The accounts of a Google Authenticator export link (a QR code of its transfer screen). */
    fun migration(link: String): Accounts = Collector().also { migration(link.trim(), it) }.result()

    // ---- Google Authenticator ----

    /**
     * otpauth-migration://offline?data=<base64 of a MigrationPayload>: repeated otp_parameters
     * (1) of secret (1), name (2), issuer (3), algorithm (4: SHA1, SHA256, SHA512, MD5), digits
     * (5: six, eight), type (6: HOTP, TOTP) and counter (7).
     */
    private fun migration(link: String, c: Collector) {
        val query = link.substringAfter('?', "")
        val data = query.split('&').firstOrNull { it.startsWith("data=") }?.substring(5) ?: damaged("migration link without data")
        val raw = decode(data).trim().replace(' ', '+')
        val payload = try {
            Base64.getDecoder().decode(raw.padEnd((raw.length + 3) / 4 * 4, '='))
        } catch (e: IllegalArgumentException) {
            damaged("migration data is not base64", e)
        }
        val r = Proto(payload)
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field != 1 || wire != 2) {
                r.skip(wire)
                continue
            }
            val p = r.message()
            var secret = ByteArray(0)
            var name = ""
            var issuer = ""
            var algorithm = 1L
            var digits = 1L
            var type = 2L
            var counter = 0L
            while (p.more()) {
                val (f, w) = p.tag()
                when {
                    f == 1 && w == 2 -> secret = p.bytes()
                    f == 2 && w == 2 -> name = String(p.bytes(), Charsets.UTF_8)
                    f == 3 && w == 2 -> issuer = String(p.bytes(), Charsets.UTF_8)
                    f == 4 && w == 0 -> algorithm = p.varint()
                    f == 5 && w == 0 -> digits = p.varint()
                    f == 6 && w == 0 -> type = p.varint()
                    f == 7 && w == 0 -> counter = p.varint()
                    else -> p.skip(w)
                }
            }
            var account = name
            if (issuer.isNotEmpty() && account.startsWith("$issuer:")) account = account.substring(issuer.length + 1)
            if (issuer.isEmpty() && account.contains(':')) {
                issuer = account.substringBefore(':')
                account = account.substringAfter(':')
            }
            val key = secret
            c.add {
                Otp.of(
                    if (type == 1L) OtpType.HOTP else OtpType.TOTP, key,
                    when (algorithm) {
                        0L, 1L -> "SHA1"
                        2L -> "SHA256"
                        3L -> "SHA512"
                        else -> "MD5"
                    },
                    if (digits == 2L) 8 else 6, 30, counter, issuer.trim(), account.trim(),
                )
            }
        }
    }

    /** The protobuf wire format, as far as the migration payload needs it. */
    private class Proto(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
        fun more() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end || shift > 63) damaged("truncated migration data")
                val x = b[pos++].toInt() and 0xff
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
        }

        fun tag(): Pair<Int, Int> {
            val t = varint()
            return (t ushr 3).toInt() to (t and 7).toInt()
        }

        private fun length(): Int {
            val n = varint()
            if (n < 0 || n > end - pos) damaged("truncated migration data")
            return n.toInt()
        }

        fun bytes(): ByteArray {
            val n = length()
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun message(): Proto {
            val n = length()
            return Proto(b, pos, pos + n).also { pos += n }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> advance(length())
                5 -> advance(4)
                else -> damaged("migration data with wire type $wire")
            }
        }

        private fun advance(n: Int) {
            if (n > end - pos) damaged("truncated migration data")
            pos += n
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
