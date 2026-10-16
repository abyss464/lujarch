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

package org.lujarch.linux.terminal

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * The desktop's connection (LNX-6): RFB 3.8 (RFC 6143) over the connection lujarch_linux opens to
 * the desktop Linux serves at /run/lujarch/desktop, without a password (the socket is reachable
 * only through lujarch_linux). Pixels come raw or copied, 32 bits as 0x00RRGGBB; the
 * framebuffer follows the screen's size when the desktop can change its size
 * (ExtendedDesktopSize). The clipboard goes both ways while it is lent.
 */
class Rfb(private val listener: Listener) {
    interface Listener {
        /** The framebuffer is new (its size changed); from the connection's thread. */
        fun onFramebuffer(width: Int, height: Int)

        /** Pixels of the rectangle changed in [pixels] (row-major, the framebuffer's width). */
        fun onUpdate(pixels: IntArray, x: Int, y: Int, width: Int, height: Int)

        fun onClipboard(text: String)

        fun onBell()

        /** The connection ended; [error] says why when it did not end by [close]. */
        fun onClosed(error: String?)
    }

    class ProtocolException(message: String) : IOException(message)

    @Volatile private var pfd: ParcelFileDescriptor? = null
    private var output: DataOutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var closing = false
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile private var resizable = false
    private var pixels = IntArray(0)

    /** Runs the connection on a thread of its own until it ends. */
    fun start(connection: ParcelFileDescriptor, wantWidth: Int, wantHeight: Int) {
        pfd = connection
        Thread({ run(connection, wantWidth, wantHeight) }, "rfb").start()
    }

    fun close() {
        closing = true
        pfd?.let {
            try {
                Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR)
            } catch (e: ErrnoException) {
                // Ended already.
            }
        }
        writer.shutdown()
    }

    private fun run(connection: ParcelFileDescriptor, wantWidth: Int, wantHeight: Int) {
        var error: String? = null
        try {
            val input = DataInputStream(BufferedInputStream(FileInputStream(connection.fileDescriptor), 1 shl 16))
            val out = DataOutputStream(FileOutputStream(connection.fileDescriptor))
            output = out
            handshake(input, out)
            send {
                // SetPixelFormat: 32 bits, depth 24, little endian, true color, 0x00RRGGBB.
                it.writeByte(0)
                it.write(ByteArray(3))
                it.writeByte(32)
                it.writeByte(24)
                it.writeByte(0)
                it.writeByte(1)
                it.writeShort(255)
                it.writeShort(255)
                it.writeShort(255)
                it.writeByte(16)
                it.writeByte(8)
                it.writeByte(0)
                it.write(ByteArray(3))
                // SetEncodings: CopyRect, Raw, DesktopSize, ExtendedDesktopSize.
                val encodings = intArrayOf(1, 0, -223, -308)
                it.writeByte(2)
                it.writeByte(0)
                it.writeShort(encodings.size)
                for (e in encodings) it.writeInt(e)
            }
            requestUpdate(false)
            loop(input, wantWidth, wantHeight)
        } catch (e: IOException) {
            if (!closing) error = e.message ?: e.javaClass.simpleName
        } finally {
            try {
                connection.close()
            } catch (e: IOException) {
                Log.w(TAG, "desktop: close: ${e.message}")
            }
            writer.shutdown()
            listener.onClosed(error)
        }
    }

    private fun handshake(input: DataInputStream, out: DataOutputStream) {
        val version = ByteArray(12)
        input.readFully(version)
        val text = String(version, StandardCharsets.US_ASCII)
        if (!text.startsWith("RFB ")) throw ProtocolException("not a desktop (RFB) connection")
        out.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        val count = input.readUnsignedByte()
        if (count == 0) throw ProtocolException("the desktop refused: ${reason(input)}")
        val types = ByteArray(count)
        input.readFully(types)
        if (types.none { it.toInt() == 1 }) {
            throw ProtocolException("the desktop asks for a password; it is served without one at /run/lujarch/desktop")
        }
        out.writeByte(1)
        out.flush()
        if (input.readInt() != 0) throw ProtocolException("the desktop refused: ${reason(input)}")
        // ClientInit: shared, so that the desktop stays when another viewer connects.
        out.writeByte(1)
        out.flush()
        width = input.readUnsignedShort()
        height = input.readUnsignedShort()
        input.readFully(ByteArray(16))
        val nameLength = input.readInt()
        if (nameLength < 0 || nameLength > 4096) throw ProtocolException("a damaged greeting")
        input.readFully(ByteArray(nameLength))
        pixels = IntArray(width * height)
        listener.onFramebuffer(width, height)
    }

    private fun reason(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0 || length > 4096) return "?"
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun loop(input: DataInputStream, wantWidth: Int, wantHeight: Int) {
        var sizeAsked = false
        while (true) {
            when (val type = input.readUnsignedByte()) {
                0 -> {
                    input.readUnsignedByte()
                    val rects = input.readUnsignedShort()
                    for (i in 0 until rects) {
                        val x = input.readUnsignedShort()
                        val y = input.readUnsignedShort()
                        val w = input.readUnsignedShort()
                        val h = input.readUnsignedShort()
                        when (val encoding = input.readInt()) {
                            0 -> raw(input, x, y, w, h)
                            1 -> copy(input, x, y, w, h)
                            -223 -> resize(w, h)
                            -308 -> {
                                val screens = input.readUnsignedByte()
                                input.readFully(ByteArray(3 + 16 * screens))
                                resizable = true
                                if (x == 0 || y == 0) resize(w, h)
                            }
                            else -> throw ProtocolException("an encoding not asked for: $encoding")
                        }
                    }
                    if (resizable && !sizeAsked && wantWidth > 0 && (wantWidth != width || wantHeight != height)) {
                        sizeAsked = true
                        setDesktopSize(wantWidth, wantHeight)
                    }
                    requestUpdate(true)
                }
                1 -> {
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                    val count = input.readUnsignedShort()
                    input.readFully(ByteArray(count * 6))
                }
                2 -> listener.onBell()
                3 -> {
                    input.readFully(ByteArray(3))
                    val length = input.readInt()
                    if (length < 0) {
                        // The extended clipboard's messages: not asked for.
                        input.readFully(ByteArray(-length))
                    } else {
                        if (length > MAX_CLIPBOARD) throw ProtocolException("a clipboard larger than 1 MiB")
                        val bytes = ByteArray(length)
                        input.readFully(bytes)
                        listener.onClipboard(String(bytes, StandardCharsets.ISO_8859_1))
                    }
                }
                else -> throw ProtocolException("a message not known: $type")
            }
        }
    }

    private fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) throw ProtocolException("a desktop of ${w}x$h")
        width = w
        height = h
        pixels = IntArray(w * h)
        listener.onFramebuffer(w, h)
    }

    private fun raw(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        if (x + w > width || y + h > height) throw ProtocolException("a rectangle outside the desktop")
        val row = ByteArray(w * 4)
        for (j in 0 until h) {
            input.readFully(row)
            var o = (y + j) * width + x
            var i = 0
            while (i < row.size) {
                pixels[o++] = (0xff shl 24) or
                    ((row[i + 2].toInt() and 0xff) shl 16) or
                    ((row[i + 1].toInt() and 0xff) shl 8) or
                    (row[i].toInt() and 0xff)
                i += 4
            }
        }
        listener.onUpdate(pixels, x, y, w, h)
    }

    private fun copy(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val sx = input.readUnsignedShort()
        val sy = input.readUnsignedShort()
        if (x + w > width || y + h > height || sx + w > width || sy + h > height) {
            throw ProtocolException("a rectangle outside the desktop")
        }
        val rows = Array(h) { j -> pixels.copyOfRange((sy + j) * width + sx, (sy + j) * width + sx + w) }
        for (j in 0 until h) System.arraycopy(rows[j], 0, pixels, (y + j) * width + x, w)
        listener.onUpdate(pixels, x, y, w, h)
    }

    private fun requestUpdate(incremental: Boolean) = send {
        it.writeByte(3)
        it.writeByte(if (incremental) 1 else 0)
        it.writeShort(0)
        it.writeShort(0)
        it.writeShort(width)
        it.writeShort(height)
    }

    private fun setDesktopSize(w: Int, h: Int) = send {
        it.writeByte(251)
        it.writeByte(0)
        it.writeShort(w)
        it.writeShort(h)
        it.writeByte(1)
        it.writeByte(0)
        it.writeInt(0)
        it.writeShort(0)
        it.writeShort(0)
        it.writeShort(w)
        it.writeShort(h)
        it.writeInt(0)
    }

    fun key(keysym: Int, down: Boolean) = send {
        it.writeByte(4)
        it.writeByte(if (down) 1 else 0)
        it.writeShort(0)
        it.writeInt(keysym)
    }

    fun pointer(buttons: Int, x: Int, y: Int) = send {
        it.writeByte(5)
        it.writeByte(buttons)
        it.writeShort(x.coerceIn(0, maxOf(width - 1, 0)))
        it.writeShort(y.coerceIn(0, maxOf(height - 1, 0)))
    }

    fun clipboard(text: String) = send {
        val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
        it.writeByte(6)
        it.write(ByteArray(3))
        it.writeInt(bytes.size)
        it.write(bytes)
    }

    private fun send(block: (DataOutputStream) -> Unit) {
        val out = output ?: return
        try {
            writer.execute {
                try {
                    synchronized(out) {
                        block(out)
                        out.flush()
                    }
                } catch (e: IOException) {
                    if (!closing) Log.w(TAG, "desktop: ${e.message}")
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // The connection ended.
        }
    }

    private companion object {
        const val MAX_CLIPBOARD = 1 shl 20
        const val MAX_PIXELS = 8192L * 8192L
    }
}
