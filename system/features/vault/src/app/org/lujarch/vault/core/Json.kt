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
 * Minimal strict JSON parser and writer for the Aegis vault (org.json is
 * part of Android, not of the host JVM the core is tested on). Objects
 * become LinkedHashMap, arrays ArrayList, numbers Long or Double; [write]
 * takes the same shapes back, so a parsed vault keeps every field it had.
 */
object Json {
    class ParseError(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) throw ParseError("trailing data at ${p.pos}")
        return v
    }

    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Long, is Int -> sb.append(v)
            is Double -> {
                if (!v.isFinite()) throw IllegalArgumentException("number $v has no JSON form")
                sb.append(v)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, item) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k as? String ?: throw IllegalArgumentException("object key is not a string"))
                    sb.append(':')
                    write(sb, item)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    write(sb, item)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("${v.javaClass.simpleName} has no JSON form")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun value(depth: Int): Any? {
            if (depth > 64) throw ParseError("nesting too deep")
            if (pos >= s.length) throw ParseError("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw ParseError("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseError("bad literal at $pos")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (peek() == '}') {
                pos++
                return m
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseError("expected key at $pos")
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return m
                    }
                    else -> throw ParseError("expected , or } at $pos")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (peek() == ']') {
                pos++
                return l
            }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return l
                    }
                    else -> throw ParseError("expected , or ] at $pos")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw ParseError("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw ParseError("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseError("bad \\u escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: throw ParseError("bad \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseError("bad escape \\$e")
                        }
                    }
                    c < ' ' -> throw ParseError("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw ParseError("bad number '$t'")
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        private fun expect(c: Char) {
            if (peek() != c) throw ParseError("expected '$c' at $pos")
            pos++
        }
    }
}

/** Typed accessors on parsed JSON objects. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>

fun Map<String, Any?>.str(key: String): String? = this[key] as? String

fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Double -> v.toLong()
    else -> null
}

fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
