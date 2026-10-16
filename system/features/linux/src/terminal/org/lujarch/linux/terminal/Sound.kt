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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.IOException

/**
 * The desktop's sound on the phone (LNX-6): what Linux's desktop plays into its FIFO
 * (ILinux.openSound), 16-bit little-endian PCM at 48 kHz in stereo, played as media while the
 * desktop is on screen. The FIFO reads end of file while nothing in Linux plays into it; the
 * player waits for it then.
 */
class Sound {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(fifo: ParcelFileDescriptor) {
        running = true
        thread = Thread({ play(fifo) }, "sound").also { it.start() }
    }

    fun close() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun play(fifo: ParcelFileDescriptor) {
        val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum * 2, RATE / 10 * FRAME))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val fd = fifo.fileDescriptor
        val buffer = ByteArray(8192)
        // A frame split between two reads waits for its other part.
        var kept = 0
        try {
            track.play()
            val poll = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            while (running) {
                poll.revents = 0
                if (Os.poll(arrayOf(poll), POLL_MS) == 0) continue
                val n = try {
                    Os.read(fd, buffer, kept, buffer.size - kept)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (n <= 0) {
                    // Nothing in Linux plays into the FIFO.
                    Thread.sleep(IDLE_MS)
                    continue
                }
                val total = kept + n
                val whole = total - total % FRAME
                if (whole > 0) track.write(buffer, 0, whole)
                kept = total - whole
                if (kept > 0) System.arraycopy(buffer, whole, buffer, 0, kept)
            }
        } catch (e: ErrnoException) {
            if (running) Log.w(TAG, "sound: ${e.message}")
        } catch (e: InterruptedException) {
            // Closed.
        } finally {
            track.release()
            try {
                fifo.close()
            } catch (e: IOException) {
                Log.w(TAG, "sound: close: ${e.message}")
            }
        }
    }

    private companion object {
        const val RATE = 48000
        const val FRAME = 4
        const val POLL_MS = 500
        const val IDLE_MS = 500L
    }
}
