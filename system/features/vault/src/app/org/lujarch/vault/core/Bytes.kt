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

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Byte helpers shared by the readers. The core package has no Android
 * dependencies so that the host tests (tests/run.sh) run it on a plain JVM.
 */
object Bytes {
    /** Overwrites key material; the JVM gives no stronger guarantee than this. */
    fun wipe(vararg arrays: ByteArray?) {
        for (a in arrays) a?.fill(0)
    }

    fun wipe(array: CharArray?) {
        array?.fill('\u0000')
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmac(algorithm: String, key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** Comparison whose time does not depend on where the arrays differ. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)

    fun i32le(b: ByteArray, off: Int): Int = u32le(b, off).toInt()

    fun u64le(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }

    fun u64leBytes(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (8 * i)).toByte() }

    fun i32leBytes(v: Int): ByteArray = ByteArray(4) { i -> (v ushr (8 * i)).toByte() }

    fun u64beBytes(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (8 * (7 - i))).toByte() }

    fun hex(b: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(digits[(x.toInt() shr 4) and 0xf]).append(digits[x.toInt() and 0xf])
        }
        return sb.toString()
    }

    /** Decodes hex, ignoring whitespace; null when the text is not hex. */
    fun fromHex(text: CharSequence): ByteArray? {
        val clean = StringBuilder(text.length)
        for (c in text) if (!c.isWhitespace()) clean.append(c)
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[2 * i], 16)
            val lo = Character.digit(clean[2 * i + 1], 16)
            if (hi < 0 || lo < 0) {
                wipe(out)
                return null
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** Reads little-endian values from a byte array, failing with [KdbxFormatError] at the end. */
class ByteReader(val data: ByteArray, var pos: Int = 0, val limit: Int = data.size) {
    val remaining: Int
        get() = limit - pos

    fun require(n: Int) {
        if (n < 0 || n > remaining) throw KdbxFormatError("unexpected end of data")
    }

    fun u8(): Int {
        require(1)
        return data[pos++].toInt() and 0xff
    }

    fun u16(): Int {
        require(2)
        return Bytes.u16le(data, pos).also { pos += 2 }
    }

    fun u32(): Long {
        require(4)
        return Bytes.u32le(data, pos).also { pos += 4 }
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        require(8)
        return Bytes.u64le(data, pos).also { pos += 8 }
    }

    fun bytes(n: Int): ByteArray {
        require(n)
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun skip(n: Int) {
        require(n)
        pos += n
    }
}

/** A ByteArrayOutputStream whose internal buffer can be overwritten after use. */
class WipeableBuffer(size: Int) : java.io.ByteArrayOutputStream(size) {
    fun wipe() {
        buf.fill(0)
        reset()
    }
}

/** Structural error in a file; mapped to a user-visible reason by the readers. */
class KdbxFormatError(message: String) : Exception(message)
