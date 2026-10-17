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

import java.io.ByteArrayInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.Json
import org.lujarch.vault.core.PasswordDatabase
import org.lujarch.vault.core.PasswordEntry
import org.lujarch.vault.core.WipeableBuffer
import org.lujarch.vault.core.list
import org.lujarch.vault.core.obj
import org.lujarch.vault.core.str

/**
 * 1Password's export (1PUX): a zip archive whose export.data holds the accounts, their vaults
 * and the items, each with its overview (title, addresses, tags) and details (login fields,
 * notes, sections of fields). Archived items are left out, as 1Password leaves them out of its
 * lists; each vault is a group.
 */
object OnePux {
    private const val DATA = "export.data"
    private const val MAX_DATA_BYTES = 64 * 1024 * 1024

    fun isZip(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() && data[2].toInt() == 3 && data[3].toInt() == 4

    fun read(data: ByteArray, fileName: String): PasswordDatabase {
        val json = exportData(data)
        val root = try {
            Json.parse(String(json, Charsets.UTF_8))
        } catch (e: Json.ParseError) {
            damaged("$DATA is not JSON: ${e.message}", e)
        } finally {
            Bytes.wipe(json)
        }
        @Suppress("UNCHECKED_CAST")
        val obj = root as? Map<String, Any?> ?: damaged("$DATA is not an object")
        val builder = DatabaseBuilder(fileName, "1PUX")
        try {
            for (account in obj.list("accounts").orEmpty()) {
                @Suppress("UNCHECKED_CAST")
                val a = account as? Map<String, Any?> ?: continue
                for (vault in a.list("vaults").orEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val v = vault as? Map<String, Any?> ?: continue
                    val vaultName = v.obj("attrs")?.str("name").orEmpty()
                    for (item in v.list("items").orEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        val i = item as? Map<String, Any?> ?: continue
                        if (i.str("state") == "archived") continue
                        val (fields, tags) = entry(i)
                        builder.add(listOf(vaultName), i.str("uuid"), tags, fields)
                    }
                }
            }
        } catch (t: Throwable) {
            builder.wipe()
            throw t
        }
        return builder.build()
    }

    private fun entry(item: Map<String, Any?>): Pair<EntryFields, String> {
        val fields = EntryFields()
        val overview = item.obj("overview")
        val details = item.obj("details")
        fields.plain(PasswordEntry.TITLE, overview?.str("title"))
        val logins = details?.list("loginFields").orEmpty().mapNotNull { it as? Map<*, *> }
        fun login(designation: String) = logins.firstOrNull { it["designation"] == designation }?.get("value") as? String
        fields.plain(PasswordEntry.USERNAME, login("username"))
        fields.secret(PasswordEntry.PASSWORD, login("password") ?: details?.str("password"))
        fields.url(overview?.str("url"))
        overview?.list("urls")?.forEach { u -> fields.url((u as? Map<*, *>)?.get("url") as? String) }
        fields.plain(PasswordEntry.NOTES, details?.str("notesPlain"))
        for (section in details?.list("sections").orEmpty()) {
            val s = section as? Map<*, *> ?: continue
            for (f in (s["fields"] as? List<*>).orEmpty()) {
                val field = f as? Map<*, *> ?: continue
                val name = (field["title"] as? String)?.takeIf { it.isNotBlank() } ?: (field["id"] as? String).orEmpty()
                val value = field["value"] as? Map<*, *> ?: continue
                val (kind, content) = value.entries.firstOrNull() ?: continue
                when (kind) {
                    "totp" -> (content as? String)?.let { if (it.isNotBlank()) fields.otp(it) }
                    "concealed", "creditCardNumber" -> fields.secret(name, content as? String)
                    "url" -> fields.plain(name, content as? String)
                    else -> fields.plain(name, text(content))
                }
            }
        }
        val tags = overview?.list("tags").orEmpty().filterIsInstance<String>().joinToString(";")
        return fields to tags
    }

    /** A field's value as text: strings and numbers as they are, an object's values joined. */
    private fun text(content: Any?): String? = when (content) {
        is String -> content
        is Long, is Double, is Boolean -> content.toString()
        is Map<*, *> -> content.values.mapNotNull { text(it) }.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null }
        else -> null
    }

    private fun exportData(data: ByteArray): ByteArray {
        try {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name != DATA) continue
                    val buffer = WipeableBuffer(64 * 1024)
                    val chunk = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            val n = zip.read(chunk)
                            if (n < 0) break
                            if (buffer.size() + n > MAX_DATA_BYTES) damaged("$DATA is larger than ${MAX_DATA_BYTES shr 20} MiB")
                            buffer.write(chunk, 0, n)
                        }
                        return buffer.toByteArray()
                    } finally {
                        Bytes.wipe(chunk)
                        buffer.wipe()
                    }
                }
            }
        } catch (e: ZipException) {
            damaged("not a readable zip archive: ${e.message}", e)
        } catch (e: java.io.IOException) {
            damaged("reading the archive failed: ${e.message}", e)
        }
        notPasswords("a zip archive without $DATA")
    }
}
