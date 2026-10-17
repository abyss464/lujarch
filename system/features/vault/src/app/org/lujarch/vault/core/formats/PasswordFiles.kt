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

import org.lujarch.vault.core.Json
import org.lujarch.vault.core.PasswordDatabase

/**
 * The formats of the passwords' file, told apart by content, never by name: a KDBX database
 * (KDBX 3.1 and 4.x, KeePass' signature), a 1Password 1PUX archive (a zip), a Bitwarden JSON
 * export (unencrypted or protected with a password), or a CSV export with a known header row.
 */
object PasswordFiles {
    sealed class Kind {
        /** Opened with its password and key file by the KDBX reader. */
        object Kdbx : Kind()

        /** A Bitwarden export protected with a password. */
        class Encrypted(val export: Bitwarden.Encrypted) : Kind() {
            /** The file's format line; the decrypted export's own format. */
            val format: String
                get() = "Bitwarden JSON · ${export.kdfName} · AES-256"

            fun open(key: ByteArray, fileName: String): PasswordDatabase =
                Bitwarden.read(export.decrypt(key), fileName, format)
        }

        /** A file without a password of its own: read as it is. */
        class Plain internal constructor(private val reader: (String) -> PasswordDatabase) : Kind() {
            fun read(fileName: String): PasswordDatabase = reader(fileName)
        }
    }

    private val KDBX_SIGNATURE = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte())

    fun detect(data: ByteArray): Kind {
        if (data.size >= 4 && data.copyOfRange(0, 4).contentEquals(KDBX_SIGNATURE)) return Kind.Kdbx
        if (OnePux.isZip(data)) return Kind.Plain { name -> OnePux.read(data, name) }
        val text = textOf(data) ?: notPasswords("neither text nor a known binary format")
        val start = text.trimStart()
        if (start.startsWith("{")) {
            val root = try {
                @Suppress("UNCHECKED_CAST")
                Json.parse(start) as? Map<String, Any?>
            } catch (e: Json.ParseError) {
                notPasswords("JSON that does not parse: ${e.message}")
            } ?: notPasswords("JSON that is not an object")
            if (!Bitwarden.isExport(root)) notPasswords("JSON of another shape")
            Bitwarden.encrypted(root)?.let { return Kind.Encrypted(it) }
            return Kind.Plain { name -> Bitwarden.read(root, name, "Bitwarden JSON") }
        }
        val rows = Csv.parse(text)
        val kind = rows.firstOrNull()?.let { PasswordCsv.kind(it) } ?: notPasswords("text without a password export's header")
        return Kind.Plain { name -> PasswordCsv.read(rows, name, kind) }
    }
}
