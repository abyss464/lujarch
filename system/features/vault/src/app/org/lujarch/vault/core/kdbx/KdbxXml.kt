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

import java.io.ByteArrayInputStream
import java.util.Base64
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.PasswordDatabase
import org.lujarch.vault.core.PasswordEntry
import org.lujarch.vault.core.PasswordField
import org.lujarch.vault.core.PasswordGroup
import org.lujarch.vault.core.Secret
import org.lujarch.vault.core.crypto.StreamCipher
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * The XML document inside a KDBX payload. Every element marked
 * Protected="True" is XORed with the inner random stream in document order,
 * history entries and binaries included, since skipping one would shift the
 * stream for all later values. History and the recycle bin are dropped
 * afterwards.
 */
internal object KdbxXml {
    /** Seconds from 0001-01-01 to 1970-01-01 (KDBX 4 stores times as seconds since year 1). */
    private const val EPOCH_OFFSET_SECONDS = 62135596800L

    class Parsed(
        val name: String,
        val root: GroupBuilder?,
        val recycleBinUuid: String?,
    ) {
        fun toDatabase(format: String): PasswordDatabase {
            val rootGroup = root ?: throw KdbxException(KdbxException.Reason.CORRUPT, "no root group")
            val recycle = recycleBinUuid?.takeIf { it.isNotEmpty() && it.any { c -> c != '0' } }
            return PasswordDatabase(name, rootGroup.build(emptyList(), recycle, isRoot = true)!!, format)
        }
    }

    class GroupBuilder {
        var uuid = ""
        var name = ""
        val groups = ArrayList<GroupBuilder>()
        val entries = ArrayList<EntryBuilder>()

        /** null for the recycle bin, whose entries are wiped. */
        fun build(parentPath: List<String>, recycleBin: String?, isRoot: Boolean): PasswordGroup? {
            if (!isRoot && recycleBin != null && uuid == recycleBin) {
                wipeAll()
                return null
            }
            val path = if (isRoot) emptyList() else parentPath + name
            return PasswordGroup(
                uuid = uuid,
                name = name,
                groups = groups.mapNotNull { it.build(path, recycleBin, isRoot = false) },
                entries = entries.map { it.build(path) },
            )
        }

        private fun wipeAll() {
            entries.forEach { e -> e.fields.forEach { it.wipe() } }
            groups.forEach { it.wipeAll() }
        }
    }

    class EntryBuilder {
        var uuid = ""
        val fields = ArrayList<PasswordField>()
        var tags = ""
        var expires = false
        var expiryMillis = 0L

        fun build(path: List<String>) = PasswordEntry(uuid, path, fields, tags, expires, expiryMillis)
    }

    fun parse(xml: ByteArray, stream: StreamCipher?, v3HeaderHash: ByteArray?): Parsed {
        val handler = Handler(stream)
        try {
            XmlSafety.newSaxParser().parse(InputSource(ByteArrayInputStream(xml)), handler)
        } catch (e: KdbxException) {
            handler.wipePending()
            throw e
        } catch (e: SAXException) {
            handler.wipePending()
            (e.exception as? KdbxException)?.let { throw it }
            throw KdbxException(KdbxException.Reason.CORRUPT, "XML: ${e.message}", e)
        } catch (e: java.io.IOException) {
            handler.wipePending()
            throw KdbxException(KdbxException.Reason.CORRUPT, "XML: ${e.message}", e)
        }
        // KDBX 3.1 stores a hash of the outer header in Meta; a mismatch means a tampered header.
        if (v3HeaderHash != null && handler.headerHash != null) {
            val stored = try {
                Base64.getDecoder().decode(handler.headerHash!!.trim())
            } catch (e: IllegalArgumentException) {
                null
            }
            if (stored != null && !Bytes.constantTimeEquals(stored, v3HeaderHash)) {
                handler.wipePending()
                throw KdbxException(KdbxException.Reason.CORRUPT, "header hash in Meta does not match")
            }
        }
        return Parsed(handler.dbName, handler.root, handler.recycleBinUuid)
    }

    private class Handler(private val stream: StreamCipher?) : DefaultHandler() {
        var dbName = ""
        var root: GroupBuilder? = null
        var recycleBinUuid: String? = null
        var headerHash: String? = null

        private val path = ArrayList<String>()
        private val protectedStack = ArrayList<Boolean>()
        private val text = StringBuilder()
        private val groups = ArrayList<GroupBuilder>()
        private val entries = ArrayList<EntryBuilder>()
        /** Nesting depth of History elements: entries inside are parsed and discarded. */
        private var historyDepth = 0
        private var fieldKey: String? = null
        private var fieldPlain: String? = null
        private var fieldSecret: Secret? = null

        fun wipePending() {
            fieldSecret?.wipe()
            entries.forEach { e -> e.fields.forEach { it.wipe() } }
            fun wipeGroup(g: GroupBuilder) {
                g.entries.forEach { e -> e.fields.forEach { it.wipe() } }
                g.groups.forEach { wipeGroup(it) }
            }
            root?.let { wipeGroup(it) }
            groups.forEach { wipeGroup(it) }
        }

        private fun parent(): String? = if (path.size >= 2) path[path.size - 2] else null

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            path.add(qName)
            text.setLength(0)
            protectedStack.add(attributes.getValue("Protected")?.equals("True", ignoreCase = true) == true)
            when (qName) {
                "Group" -> if (inRoot()) groups.add(GroupBuilder())
                "History" -> if (entries.isNotEmpty()) historyDepth++
                "Entry" -> if (groups.isNotEmpty()) entries.add(EntryBuilder())
                "String" -> if (parent() == "Entry") {
                    fieldKey = null
                    fieldPlain = null
                    fieldSecret = null
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val isProtected = protectedStack.removeAt(protectedStack.size - 1)
            var protectedValue: ByteArray? = null
            if (isProtected) {
                // Decrypt every protected element, used or not, to keep the stream aligned.
                val raw = try {
                    Base64.getMimeDecoder().decode(text.toString().trim())
                } catch (e: IllegalArgumentException) {
                    throw SAXException(KdbxException(KdbxException.Reason.CORRUPT, "protected value is not base64"))
                }
                if (stream == null) {
                    Bytes.wipe(raw)
                    throw SAXException(KdbxException(KdbxException.Reason.CORRUPT, "protected value without inner stream"))
                }
                stream.xorInPlace(raw)
                protectedValue = raw
            }
            try {
                handleEnd(qName, protectedValue)
            } finally {
                // A field keeps its own copy; the decrypted buffer is always wiped here.
                protectedValue?.let { Bytes.wipe(it) }
            }
            path.removeAt(path.size - 1)
            text.setLength(0)
        }

        private fun handleEnd(qName: String, protectedValue: ByteArray?) {
            val p = parent()
            val entry = entries.lastOrNull()
            when {
                path.size == 3 && path[0] == "KeePassFile" && path[1] == "Meta" -> when (qName) {
                    "DatabaseName" -> dbName = text.toString()
                    "RecycleBinUUID" -> recycleBinUuid = uuidText()
                    "HeaderHash" -> headerHash = text.toString()
                }
                qName == "Group" && inRoot() -> {
                    val g = groups.removeAt(groups.size - 1)
                    if (groups.isEmpty()) {
                        if (root == null) root = g
                    } else {
                        groups.last().groups.add(g)
                    }
                }
                qName == "History" && entries.isNotEmpty() && historyDepth > 0 -> historyDepth--
                qName == "Entry" && entry != null -> {
                    entries.removeAt(entries.size - 1)
                    // Entries left inside History (depth counted before the pop) are old versions.
                    if (historyDepth > 0 && entries.isNotEmpty()) {
                        entry.fields.forEach { it.wipe() }
                    } else {
                        groups.lastOrNull()?.entries?.add(entry) ?: entry.fields.forEach { it.wipe() }
                    }
                }
                p == "Group" && groups.isNotEmpty() && entry == null -> when (qName) {
                    "UUID" -> groups.last().uuid = uuidText()
                    "Name" -> groups.last().name = text.toString()
                }
                entry != null && p == "Entry" -> when (qName) {
                    "UUID" -> entry.uuid = uuidText()
                    "Tags" -> entry.tags = text.toString()
                    "String" -> {
                        val key = fieldKey
                        if (key != null) {
                            entry.fields.add(PasswordField(key, if (fieldSecret == null) fieldPlain ?: "" else null, fieldSecret))
                        } else {
                            fieldSecret?.wipe()
                        }
                        fieldSecret = null
                        fieldPlain = null
                        fieldKey = null
                    }
                }
                entry != null && p == "String" && path.getOrNull(path.size - 3) == "Entry" -> when (qName) {
                    "Key" -> fieldKey = text.toString()
                    "Value" -> if (protectedValue != null) {
                        fieldSecret?.wipe()
                        fieldSecret = Secret(protectedValue.copyOf())
                    } else {
                        fieldPlain = text.toString()
                    }
                }
                entry != null && p == "Times" && path.getOrNull(path.size - 3) == "Entry" -> when (qName) {
                    "Expires" -> entry.expires = text.toString().trim().equals("True", ignoreCase = true)
                    "ExpiryTime" -> entry.expiryMillis = parseTime(text.toString())
                }
            }
        }

        /** Inside KeePassFile/Root (Groups also appear nowhere else, but be exact). */
        private fun inRoot(): Boolean = path.size >= 2 && path[0] == "KeePassFile" && path[1] == "Root"

        private fun uuidText(): String = try {
            Bytes.hex(Base64.getMimeDecoder().decode(text.toString().trim()))
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    /** KDBX 4: base64 of little-endian seconds since 0001-01-01; KDBX 3: ISO 8601. */
    fun parseTime(value: String): Long {
        val t = value.trim()
        if (t.isEmpty()) return 0
        if (!t.contains('-') && !t.contains(':')) {
            try {
                val b = Base64.getDecoder().decode(t)
                if (b.size == 8) return (Bytes.u64le(b, 0) - EPOCH_OFFSET_SECONDS) * 1000
            } catch (e: IllegalArgumentException) {
                // Not base64: fall through to ISO 8601.
            }
        }
        return try {
            java.time.Instant.parse(t).toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            0
        }
    }
}
