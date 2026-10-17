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

import java.util.Locale
import org.lujarch.vault.core.PasswordDatabase
import org.lujarch.vault.core.PasswordEntry
import org.lujarch.vault.core.match.EntryMatcher

/** RFC 4180 CSV: quoted fields with doubled quotes and line breaks, CRLF or LF rows. */
internal object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        var atStart = true
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else when (c) {
                '"' -> if (atStart) quoted = true else field.append(c)
                ',' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    atStart = true
                    i++
                    continue
                }
                '\r', '\n' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString())
                    field.setLength(0)
                    if (row.size > 1 || row[0].isNotEmpty()) rows.add(row)
                    row = ArrayList()
                    atStart = true
                    i++
                    continue
                }
                else -> field.append(c)
            }
            atStart = false
            i++
        }
        if (quoted) damaged("a quoted CSV field does not end")
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.size > 1 || row[0].isNotEmpty()) rows.add(row)
        }
        return rows
    }
}

/**
 * Password exports as CSV, recognized by their header row: Chrome, Edge and Brave
 * (name, url, username, password, note), Firefox (url, username, password, httpRealm, ...),
 * Safari (Title, URL, Username, Password, Notes, OTPAuth), Bitwarden (folder, ..., login_uri,
 * login_username, login_password, login_totp), LastPass (url, username, password, totp, extra,
 * name, grouping, fav), Proton Pass (type, name, url, email, username, password, note, totp, ...,
 * vault), KeePassXC (Group, Title, Username, Password, URL, Notes, TOTP, ...), Dashlane
 * (username, username2, username3, title, password, note, url, category, otpSecret or otpUrl),
 * and others with the same column names. Columns that are not one of the standard fields
 * become fields of their own, except bookkeeping columns (times, ids, flags).
 */
internal object PasswordCsv {
    private val TITLE = listOf("title", "name")
    private val URL = listOf("url", "login_uri", "website", "web site", "uri", "login url")
    private val USER = listOf("username", "login_username", "user name", "login", "login name", "user")
    private val PASSWORD = listOf("password", "login_password")
    private val NOTES = listOf("notes", "note", "extra", "comments")
    private val OTP = listOf("totp", "login_totp", "otpauth", "otp", "otpsecret", "otpurl", "otp secret")
    private val GROUP = listOf("group", "grouping", "folder", "vault")
    private val TAGS = listOf("tags")
    private val EMAIL = listOf("email")
    private val IGNORED = setOf(
        "httprealm", "formactionorigin", "guid", "timecreated", "timelastused", "timepasswordchanged",
        "icon", "last modified", "created", "fav", "favorite", "reprompt", "type", "createtime", "modifytime",
        "archived", "category", "fields",
    )

    /** The format's name for the state page when [header] is a password export's, else null. */
    fun kind(header: List<String>): String? {
        val h = header.map { norm(it) }.toSet()
        if (PASSWORD.none { it in h }) return null
        return when {
            "login_password" in h -> "Bitwarden"
            "httprealm" in h || "formactionorigin" in h -> "Firefox"
            "grouping" in h && "extra" in h -> "LastPass"
            "vault" in h && ("createtime" in h || "email" in h) -> "Proton Pass"
            "username2" in h || "otpsecret" in h || "otpurl" in h -> "Dashlane"
            "group" in h && "title" in h -> "KeePassXC"
            "otpauth" in h && "title" in h && ("archived" in h || "favorite" in h) -> "1Password"
            "otpauth" in h && "title" in h -> "Safari"
            "name" in h && "url" in h && "username" in h -> "Chrome"
            else -> ""
        }
    }

    fun read(rows: List<List<String>>, fileName: String, kind: String): PasswordDatabase {
        val header = rows.first().map { norm(it) }
        fun col(names: List<String>): Int = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1
        val title = col(TITLE)
        val url = col(URL)
        val user = col(USER)
        val password = col(PASSWORD)
        val notes = col(NOTES)
        val otp = col(OTP)
        val group = col(GROUP)
        val tags = col(TAGS)
        val email = col(EMAIL)
        val custom = if (kind == "Bitwarden") header.indexOf("fields") else -1
        val used = setOf(title, url, user, password, notes, otp, group, tags)
        val builder = DatabaseBuilder(fileName, if (kind.isEmpty()) "CSV" else "CSV · $kind")
        try {
            for (row in rows.drop(1)) {
                fun at(i: Int): String = if (i >= 0 && i < row.size) row[i] else ""
                val fields = EntryFields()
                val link = at(url).trim()
                // LastPass marks secure notes with the address http://sn.
                val webLink = link.takeUnless { kind == "LastPass" && it == "http://sn" }.orEmpty()
                val name = at(title).ifBlank { EntryMatcher.hostOf(webLink.substringBefore(',')).orEmpty() }
                val userName = at(user).ifEmpty { at(email) }
                fields.plain(PasswordEntry.TITLE, name)
                fields.plain(PasswordEntry.USERNAME, userName)
                fields.secret(PasswordEntry.PASSWORD, at(password))
                // Bitwarden joins an item's addresses with commas.
                if (kind == "Bitwarden") webLink.split(',').forEach { fields.url(it) } else fields.url(webLink)
                fields.plain(PasswordEntry.NOTES, at(notes))
                fields.otp(at(otp))
                for ((i, column) in header.withIndex()) {
                    if (i in used || column in IGNORED || (i == email && userName == at(email))) continue
                    fields.plain(rows.first()[i].trim(), at(i))
                }
                // Bitwarden's own fields: one "name: value" line each.
                for (line in at(custom).lines()) {
                    val colon = line.indexOf(": ")
                    if (colon > 0) fields.plain(line.substring(0, colon), line.substring(colon + 2))
                }
                if (fields.fields.isEmpty()) continue
                var path = at(group).split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
                // KeePassXC names the root group first.
                if (kind == "KeePassXC" && path.isNotEmpty()) path = path.drop(1)
                builder.add(path, null, at(tags), fields)
            }
        } catch (t: Throwable) {
            builder.wipe()
            throw t
        }
        return builder.build()
    }

    private fun norm(s: String): String = s.trim().removePrefix("﻿").lowercase(Locale.ROOT)
}
