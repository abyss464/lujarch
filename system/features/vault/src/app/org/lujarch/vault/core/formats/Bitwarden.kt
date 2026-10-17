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

import java.util.Base64
import javax.crypto.BadPaddingException
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.Json
import org.lujarch.vault.core.PasswordDatabase
import org.lujarch.vault.core.PasswordEntry
import org.lujarch.vault.core.bool
import org.lujarch.vault.core.crypto.Aes
import org.lujarch.vault.core.crypto.Argon2
import org.lujarch.vault.core.crypto.Hkdf
import org.lujarch.vault.core.crypto.Pbkdf2
import org.lujarch.vault.core.list
import org.lujarch.vault.core.long
import org.lujarch.vault.core.obj
import org.lujarch.vault.core.str

/**
 * Bitwarden's JSON exports, of the password manager and of Bitwarden Authenticator: unencrypted,
 * or protected with a password (PBKDF2-SHA256 or Argon2id, HKDF-stretched into an AES-256-CBC key
 * and an HMAC-SHA256 key, the "2." cipher strings of the export). An export encrypted with the
 * account's key needs the account and is refused.
 */
object Bitwarden {
    private const val KDF_PBKDF2 = 0L
    private const val KDF_ARGON2ID = 1L

    /** Whether [root] has the shape of a Bitwarden export. */
    fun isExport(root: Map<String, Any?>): Boolean =
        root["items"] is List<*> || (root["encrypted"] is Boolean && (root.containsKey("data") || root.containsKey("folders")))

    fun isEncrypted(root: Map<String, Any?>): Boolean = root.bool("encrypted") == true

    /** A password-protected export; null for an unencrypted one. Refuses one encrypted with the account's key. */
    fun encrypted(root: Map<String, Any?>): Encrypted? {
        if (!isEncrypted(root)) return null
        if (root.bool("passwordProtected") != true) {
            throw FileFormatException(FileFormatException.Reason.ACCOUNT_KEY, "export encrypted with the account's key")
        }
        return Encrypted(root)
    }

    class Encrypted internal constructor(private val root: Map<String, Any?>) {
        private val salt = root.str("salt") ?: damaged("no salt")
        private val kdfType = root.long("kdfType") ?: KDF_PBKDF2
        private val iterations = root.long("kdfIterations") ?: damaged("no KDF iterations")
        private val memoryMiB = root.long("kdfMemory") ?: 0
        private val parallelism = root.long("kdfParallelism") ?: 0

        /** "PBKDF2-SHA256" or "Argon2id", for the state page. */
        val kdfName: String
            get() = if (kdfType == KDF_ARGON2ID) "Argon2id" else "PBKDF2-SHA256"

        /** Names the salt and KDF parameters: a key derived for them opens exports that share them. */
        fun fingerprint(): ByteArray = Bytes.sha256(
            "bitwarden\u0000$salt\u0000$kdfType\u0000$iterations\u0000$memoryMiB\u0000$parallelism".toByteArray(Charsets.UTF_8),
        )

        /** The export's 64-byte key (encryption key, then MAC key) from its [password]; the caller wipes it. */
        fun deriveKey(password: ByteArray): ByteArray {
            val saltBytes = salt.toByteArray(Charsets.UTF_8)
            val base = when (kdfType) {
                KDF_PBKDF2 -> Pbkdf2.derive("HmacSHA256", password, saltBytes, iterations.toInt(), 32)
                KDF_ARGON2ID -> Argon2.hash(
                    Argon2.TYPE_ID, Argon2.VERSION_13, password, Bytes.sha256(saltBytes), null, null,
                    iterations, memoryMiB * 1024 * 1024, parallelism, 32,
                )
                else -> damaged("KDF type $kdfType")
            }
            val enc = Hkdf.expandSha256(base, "enc".toByteArray(), 32)
            val mac = Hkdf.expandSha256(base, "mac".toByteArray(), 32)
            try {
                return Bytes.concat(enc, mac)
            } finally {
                Bytes.wipe(base, enc, mac)
            }
        }

        /** The decrypted export, as an unencrypted export's JSON object; WRONG_PASSWORD when [key] does not open it. */
        fun decrypt(key: ByteArray): Map<String, Any?> {
            decryptString(root.str("encKeyValidation_DO_NOT_EDIT") ?: damaged("no key check"), key).also { Bytes.wipe(it) }
            val plain = decryptString(root.str("data") ?: damaged("no data"), key)
            try {
                @Suppress("UNCHECKED_CAST")
                return Json.parse(String(plain, Charsets.UTF_8)) as? Map<String, Any?> ?: damaged("decrypted data is not an object")
            } catch (e: Json.ParseError) {
                damaged("decrypted data is not JSON: ${e.message}", e)
            } finally {
                Bytes.wipe(plain)
            }
        }
    }

    /** A "2.iv|data|mac" cipher string: HMAC-SHA256 over iv and data, then AES-256-CBC. */
    private fun decryptString(text: String, key: ByteArray): ByteArray {
        if (!text.startsWith("2.")) damaged("cipher string of type ${text.substringBefore('.')}")
        val parts = text.substring(2).split('|')
        if (parts.size != 3) damaged("cipher string without iv, data and mac")
        val (iv, data, mac) = try {
            parts.map { Base64.getDecoder().decode(it) }
        } catch (e: IllegalArgumentException) {
            damaged("cipher string is not base64", e)
        }
        val encKey = key.copyOfRange(0, 32)
        val macKey = key.copyOfRange(32, 64)
        try {
            val expected = Bytes.hmac("HmacSHA256", macKey, iv, data)
            if (!Bytes.constantTimeEquals(expected, mac)) {
                throw FileFormatException(FileFormatException.Reason.WRONG_PASSWORD, "the password does not open the export")
            }
            return try {
                Aes.cbcDecrypt(encKey, iv, data)
            } catch (e: BadPaddingException) {
                damaged("bad padding after a matching MAC", e)
            }
        } finally {
            Bytes.wipe(encKey, macKey)
        }
    }

    /** The passwords of an unencrypted export (or a decrypted one): every item not in the trash. */
    fun read(root: Map<String, Any?>, fileName: String, format: String): PasswordDatabase {
        val folders = HashMap<String, String>()
        root.list("folders")?.forEach { f ->
            @Suppress("UNCHECKED_CAST")
            val m = f as? Map<String, Any?> ?: return@forEach
            val id = m.str("id") ?: return@forEach
            folders[id] = m.str("name").orEmpty()
        }
        val builder = DatabaseBuilder(fileName, format)
        try {
            for (it in root.list("items").orEmpty()) {
                @Suppress("UNCHECKED_CAST")
                val item = it as? Map<String, Any?> ?: continue
                if (item["deletedDate"] != null) continue
                val fields = EntryFields()
                fields.plain(PasswordEntry.TITLE, item.str("name"))
                item.obj("login")?.let { login ->
                    fields.plain(PasswordEntry.USERNAME, login.str("username"))
                    fields.secret(PasswordEntry.PASSWORD, login.str("password"))
                    login.list("uris")?.forEach { u -> fields.url((u as? Map<*, *>)?.get("uri") as? String) }
                    fields.otp(login.str("totp"))
                }
                fields.plain(PasswordEntry.NOTES, item.str("notes"))
                // Cards, identities and SSH keys: their values under the export's own names.
                for (section in listOf("card", "identity", "sshKey")) {
                    item.obj(section)?.forEach { (name, value) ->
                        val text = (value as? String) ?: return@forEach
                        if (name in SECRET_KEYS) fields.secret(name, text) else fields.plain(name, text)
                    }
                }
                item.list("fields")?.forEach { f ->
                    @Suppress("UNCHECKED_CAST")
                    val m = f as? Map<String, Any?> ?: return@forEach
                    val name = m.str("name").orEmpty()
                    val value = m.str("value") ?: (m["value"] as? Boolean)?.toString()
                    when (m.long("type")) {
                        FIELD_HIDDEN -> fields.secret(name, value)
                        FIELD_LINKED -> Unit
                        else -> fields.plain(name, value)
                    }
                }
                val folder = item.str("folderId")?.let { folders[it] }.orEmpty()
                builder.add(folder.split('/'), item.str("id"), "", fields)
            }
        } catch (t: Throwable) {
            builder.wipe()
            throw t
        }
        return builder.build()
    }

    /** Name and code setting (otpauth link, steam:// link or key) of every item with one, for 2FA imports. */
    fun codes(root: Map<String, Any?>): List<Pair<String, String>> = root.list("items").orEmpty().mapNotNull {
        val item = it as? Map<*, *> ?: return@mapNotNull null
        if (item["deletedDate"] != null) return@mapNotNull null
        val totp = ((item["login"] as? Map<*, *>)?.get("totp") as? String)?.trim()?.takeIf { t -> t.isNotEmpty() }
            ?: return@mapNotNull null
        ((item["name"] as? String).orEmpty()) to totp
    }

    private const val FIELD_HIDDEN = 1L
    private const val FIELD_LINKED = 3L
    private val SECRET_KEYS = setOf("number", "code", "ssn", "passportNumber", "licenseNumber", "privateKey")
}
