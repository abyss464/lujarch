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

package org.lujarch.linux.face

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.lujarch.linux.client.Linux
import org.lujarch.linux.client.LinuxException
import org.lujarch.linux.client.TAG

/**
 * One tab: a session of lujarch_linux shown by Termux's emulator. While the app is on screen the
 * tab holds the session's stream: lujarch_linux first sends what the session wrote lately, then
 * its output; input and size changes go back. The stream is let go when the app leaves the
 * screen, and the session goes on.
 */
class TerminalTab(val id: String, client: TerminalSessionClient) : TerminalSession.HostTransport {
    var kind: String = ""
    var name: String = ""
    var busy = false
    var job: String? = null
    var awake = false

    val session = TerminalSession(TRANSCRIPT_ROWS, client).also { it.setTransport(this) }

    @Volatile private var stream: ParcelFileDescriptor? = null
    @Volatile private var output: FileOutputStream? = null
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Takes the session's stream; off the main thread. The emulator starts empty: the stream replays
     * the session's earlier output when there is any (replay).
     */
    fun attach(columns: Int, rows: Int, replay: Boolean) {
        detach()
        val pfd = Linux.blocking("attachSession") { it.attachSession(id, columns, rows) }
        stream = pfd
        output = FileOutputStream(pfd.fileDescriptor)
        // Answers to queries in the replayed output would be typed into the shell. A new session's
        // shell asks while it starts (fish: its terminal's attributes) and waits for the answer.
        if (replay) session.holdReplies(REPLAY_HOLD_MS)
        Thread({ read(pfd) }, "session-$id").start()
    }

    private fun read(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buffer = ByteArray(65536)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                session.onHostOutput(buffer, n)
            }
        } catch (e: IOException) {
            if (stream === pfd) Log.w(TAG, "session $id: stream: ${e.message}")
        }
    }

    /** Lets go of the stream; the session goes on. */
    fun detach() {
        val pfd = stream ?: return
        stream = null
        output = null
        try {
            Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR)
        } catch (e: ErrnoException) {
            // Already closed by the other end.
        }
        try {
            pfd.close()
        } catch (e: IOException) {
            Log.w(TAG, "session $id: close: ${e.message}")
        }
    }

    fun close() {
        detach()
        writer.shutdown()
    }

    override fun sendInput(data: ByteArray, offset: Int, count: Int) {
        val bytes = data.copyOfRange(offset, offset + count)
        writer.execute {
            try {
                output?.write(bytes)
            } catch (e: IOException) {
                Log.w(TAG, "session $id: input: ${e.message}")
            }
        }
    }

    override fun sendResize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        writer.execute {
            try {
                Linux.blocking("resizeSession") { it.resizeSession(id, columns, rows) }
            } catch (e: LinuxException) {
                Log.w(TAG, e.message ?: "resizeSession")
            }
        }
    }

    companion object {
        const val TRANSCRIPT_ROWS = 4000
        const val REPLAY_HOLD_MS = 1500L
    }
}
