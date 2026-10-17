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

import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.PasswordDatabase
import org.lujarch.vault.core.PasswordEntry
import org.lujarch.vault.core.PasswordField
import org.lujarch.vault.core.PasswordGroup
import org.lujarch.vault.core.Secret

/** Why a picked file cannot be read; the pages map each reason to a message. */
class FileFormatException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** The file holds no passwords in a format the vault reads. */
        NOT_PASSWORDS,
        /** The file holds no 2FA accounts in a format the vault reads. */
        NOT_CODES,
        /** The file's password does not open it. */
        WRONG_PASSWORD,
        /** A Bitwarden export encrypted with the account's key, which only the account opens. */
        ACCOUNT_KEY,
        /** The file is damaged: a check failed after the key was right, or its structure is broken. */
        DAMAGED,
        /** The stored key is of another kind than the file needs (it changed format): set up again. */
        NEEDS_SETUP,
    }
}

internal fun notPasswords(message: String): Nothing = throw FileFormatException(FileFormatException.Reason.NOT_PASSWORDS, message)

internal fun notCodes(message: String): Nothing = throw FileFormatException(FileFormatException.Reason.NOT_CODES, message)

internal fun damaged(message: String, cause: Throwable? = null): Nothing =
    throw FileFormatException(FileFormatException.Reason.DAMAGED, message, cause)

/** The file as text: UTF-8 without a byte order mark; null when it is not valid UTF-8. */
internal fun textOf(data: ByteArray): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(java.nio.ByteBuffer.wrap(data)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        return null
    }
    return text.removePrefix("﻿")
}

/** A stable identifier from the parts that name an entry or a group, for files that carry none. */
internal fun stableId(vararg parts: String): String =
    Bytes.hex(Bytes.sha256(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))).substring(0, 32)

/**
 * The fields of one imported entry in KeePass' names (Title, UserName, Password, URL, Notes),
 * further URLs as KeePass2Android's KP2A_URL_n, a code as KeePassXC's otp, and the file's own
 * fields under their names; empty values are left out.
 */
internal class EntryFields {
    val fields = ArrayList<PasswordField>()
    private val names = HashSet<String>()
    private var urls = 0

    fun plain(name: String, value: String?) {
        if (value.isNullOrEmpty()) return
        fields.add(PasswordField(unique(name), value, null))
    }

    fun secret(name: String, value: String?) {
        if (value.isNullOrEmpty()) return
        fields.add(PasswordField(unique(name), null, Secret.of(value)))
    }

    fun url(value: String?) {
        val v = value?.trim()
        if (v.isNullOrEmpty()) return
        if (PasswordEntry.URL !in names) plain(PasswordEntry.URL, v) else plain("KP2A_URL_${++urls}", v)
    }

    fun otp(value: String?) {
        val v = value?.trim()
        if (v.isNullOrEmpty() || OTP in names) return
        secret(OTP, v)
    }

    private fun unique(name: String): String {
        val base = name.ifBlank { "?" }
        var n = base
        var i = 2
        while (n in names) n = "$base (${i++})"
        names += n
        return n
    }

    fun wipe() = fields.forEach { it.wipe() }

    companion object {
        const val OTP = "otp"
    }
}

/** Groups and entries of an imported file, built from each entry's group path. */
internal class DatabaseBuilder(private val fileName: String, private val format: String) {
    private class Node(val name: String, val path: List<String>) {
        val groups = LinkedHashMap<String, Node>()
        val entries = ArrayList<PasswordEntry>()
    }

    private val root = Node(fileName, emptyList())
    private var count = 0

    fun add(path: List<String>, id: String?, tags: String, fields: EntryFields) {
        val clean = path.map { it.trim() }.filter { it.isNotEmpty() }
        var node = root
        for (name in clean) node = node.groups.getOrPut(name) { Node(name, node.path + name) }
        val title = fields.fields.firstOrNull { it.name == PasswordEntry.TITLE }?.value().orEmpty()
        val user = fields.fields.firstOrNull { it.name == PasswordEntry.USERNAME }?.value().orEmpty()
        val url = fields.fields.firstOrNull { it.name == PasswordEntry.URL }?.value().orEmpty()
        val uuid = id?.takeIf { it.isNotBlank() } ?: stableId(format, (count).toString(), title, user, url)
        count++
        node.entries.add(PasswordEntry(uuid, clean, fields.fields, tags, expires = false, expiryMillis = 0))
    }

    fun build(): PasswordDatabase {
        fun group(n: Node): PasswordGroup = PasswordGroup(
            uuid = if (n.path.isEmpty()) "" else stableId("group", *n.path.toTypedArray()),
            name = n.name,
            groups = n.groups.values.map { group(it) },
            entries = n.entries,
        )
        return PasswordDatabase(fileName, group(root), format)
    }

    /** Wipes what was added when the file turns out unreadable halfway. */
    fun wipe() {
        fun walk(n: Node) {
            n.entries.forEach { it.wipe() }
            n.groups.values.forEach { walk(it) }
        }
        walk(root)
    }
}
