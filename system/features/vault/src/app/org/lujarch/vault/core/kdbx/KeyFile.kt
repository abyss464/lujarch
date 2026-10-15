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
import javax.xml.parsers.SAXParserFactory
import org.lujarch.vault.core.Bytes
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * KeePass key files, in KeePass' own order: XML (version 1.0: base64 data,
 * 2.0: hex data with a hash check), 32 raw bytes, 64 hex characters,
 * otherwise the SHA-256 of the whole file.
 */
object KeyFile {
    class InvalidKeyFile(message: String) : Exception(message)

    /** The 32-byte (or, for odd XML v1 files, as stored) key file component. */
    fun load(file: ByteArray): ByteArray {
        if (looksLikeXml(file)) {
            parseXml(file)?.let { return it }
        }
        if (file.size == 32) return file.copyOf()
        if (file.size == 64) {
            Bytes.fromHex(String(file, Charsets.ISO_8859_1))?.let { return it }
        }
        return Bytes.sha256(file)
    }

    /** SHA-256(component keys): password hash first, then the key file key. */
    fun compositeKey(password: ByteArray?, keyFileKey: ByteArray?): ByteArray {
        require(password != null || keyFileKey != null) { "a password or a key file is needed" }
        val parts = ArrayList<ByteArray>(2)
        val passwordHash = password?.let { Bytes.sha256(it) }
        passwordHash?.let { parts.add(it) }
        keyFileKey?.let { parts.add(it) }
        return try {
            Bytes.sha256(*parts.toTypedArray())
        } finally {
            Bytes.wipe(passwordHash)
        }
    }

    private fun looksLikeXml(file: ByteArray): Boolean {
        var i = 0
        if (file.size >= 3 && file[0] == 0xEF.toByte() && file[1] == 0xBB.toByte() && file[2] == 0xBF.toByte()) i = 3
        while (i < file.size && (file[i] == ' '.code.toByte() || file[i] == '\n'.code.toByte() ||
                file[i] == '\r'.code.toByte() || file[i] == '\t'.code.toByte())) i++
        return i < file.size && file[i] == '<'.code.toByte()
    }

    /** null when the file is not a KeePass XML key file; throws when it is one but is broken. */
    private fun parseXml(file: ByteArray): ByteArray? {
        val handler = KeyFileHandler()
        try {
            val parser = XmlSafety.newSaxParser()
            parser.parse(InputSource(ByteArrayInputStream(file)), handler)
        } catch (e: Exception) {
            return null
        }
        val data = handler.data ?: return null
        if (!handler.sawKeyFile) return null
        val version = handler.version?.trim() ?: return null
        return when {
            version.startsWith("1.") -> try {
                Base64.getMimeDecoder().decode(data.trim())
            } catch (e: IllegalArgumentException) {
                throw InvalidKeyFile("key file data is not base64")
            }
            version.startsWith("2.") -> {
                val key = Bytes.fromHex(data) ?: throw InvalidKeyFile("key file data is not hex")
                val expected = handler.hash?.let { Bytes.fromHex(it) }
                if (expected != null) {
                    val actual = Bytes.sha256(key).copyOf(expected.size.coerceAtMost(32))
                    if (!Bytes.constantTimeEquals(actual, expected)) {
                        Bytes.wipe(key)
                        throw InvalidKeyFile("key file hash does not match its data")
                    }
                }
                key
            }
            else -> throw InvalidKeyFile("key file version $version is not supported")
        }
    }

    private class KeyFileHandler : DefaultHandler() {
        var sawKeyFile = false
        var version: String? = null
        var data: String? = null
        var hash: String? = null
        private val path = ArrayList<String>()
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            path.add(qName)
            text.setLength(0)
            if (path.size == 1 && qName == "KeyFile") sawKeyFile = true
            if (path == listOf("KeyFile", "Key", "Data")) hash = attributes.getValue("Hash")
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (path) {
                listOf("KeyFile", "Meta", "Version") -> version = text.toString()
                listOf("KeyFile", "Key", "Data") -> data = text.toString()
            }
            path.removeAt(path.size - 1)
            text.setLength(0)
        }
    }
}

/** SAX parser setup shared by the key file and database readers: no DTDs, no external entities. */
internal object XmlSafety {
    fun newSaxParser(): javax.xml.parsers.SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        // Feature names differ between providers (Xerces on the JVM, Expat on Android):
        // set what the provider knows and skip the rest.
        for ((feature, value) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )) {
            try {
                factory.setFeature(feature, value)
            } catch (e: Exception) {
                // Not supported by this provider.
            }
        }
        return factory.newSAXParser()
    }
}
