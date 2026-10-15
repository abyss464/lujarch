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

package org.lujarch.vault.core

/**
 * A protected value (password, protected custom field, OTP secret) kept as
 * UTF-8 bytes so it can be overwritten when the vault locks. A String made
 * with [reveal] cannot be wiped; callers keep it only while it is shown or
 * filled.
 */
class Secret(bytes: ByteArray) {
    private var data: ByteArray? = bytes

    val isEmpty: Boolean
        get() = data?.isEmpty() ?: true

    fun reveal(): String = data?.let { String(it, Charsets.UTF_8) } ?: ""

    /** A copy of the bytes; the caller wipes it after use. */
    fun revealBytes(): ByteArray = data?.copyOf() ?: ByteArray(0)

    fun wipe() {
        data?.let { Bytes.wipe(it) }
        data = null
    }

    override fun toString(): String = "Secret(***)"

    companion object {
        fun of(text: String): Secret = Secret(text.toByteArray(Charsets.UTF_8))
    }
}

/** One string field of a KeePass entry; protected fields hold their value in [secret]. */
class KpField(val name: String, val plain: String?, val secret: Secret?) {
    val isProtected: Boolean
        get() = secret != null

    fun value(): String = plain ?: secret?.reveal() ?: ""

    /** Empty without revealing a protected value. */
    val isEmpty: Boolean
        get() = plain?.isEmpty() ?: (secret?.isEmpty ?: true)

    fun wipe() {
        secret?.wipe()
    }
}

class KpEntry(
    val uuid: String,
    /** Group names from the root group (excluded) down to the entry's group. */
    val path: List<String>,
    val fields: List<KpField>,
    val tags: String,
    val expires: Boolean,
    /** Expiry time in epoch milliseconds, meaningful when [expires]. */
    val expiryMillis: Long,
) {
    private fun field(name: String): KpField? = fields.firstOrNull { it.name == name }

    val title: String
        get() = field(TITLE)?.value().orEmpty()

    val username: String
        get() = field(USERNAME)?.value().orEmpty()

    val url: String
        get() = field(URL)?.value().orEmpty()

    val notes: String
        get() = field(NOTES)?.value().orEmpty()

    val password: KpField?
        get() = field(PASSWORD)

    /** Every field that is not one of the five standard ones. */
    val customFields: List<KpField>
        get() = fields.filter { it.name !in STANDARD }

    fun get(name: String): KpField? = field(name)

    fun wipe() {
        fields.forEach { it.wipe() }
    }

    companion object {
        const val TITLE = "Title"
        const val USERNAME = "UserName"
        const val PASSWORD = "Password"
        const val URL = "URL"
        const val NOTES = "Notes"
        val STANDARD = setOf(TITLE, USERNAME, PASSWORD, URL, NOTES)
    }
}

class KpGroup(
    val uuid: String,
    val name: String,
    val groups: List<KpGroup>,
    val entries: List<KpEntry>,
) {
    /** Entries of this group and every subgroup. */
    fun allEntries(): List<KpEntry> {
        val out = ArrayList<KpEntry>()
        fun walk(g: KpGroup) {
            out.addAll(g.entries)
            g.groups.forEach { walk(it) }
        }
        walk(this)
        return out
    }
}

/** A decrypted KeePass database, recycle bin and history already removed. */
class KdbxDatabase(
    val name: String,
    val root: KpGroup,
    val info: KdbxInfo,
) {
    val entries: List<KpEntry> by lazy { root.allEntries() }

    fun wipe() {
        entries.forEach { it.wipe() }
    }
}

/** Format facts shown on the state page; never contains key material. */
data class KdbxInfo(
    val version: String,
    val cipher: String,
    val kdf: org.lujarch.vault.core.kdbx.KdfSummary,
    val compressed: Boolean,
)
