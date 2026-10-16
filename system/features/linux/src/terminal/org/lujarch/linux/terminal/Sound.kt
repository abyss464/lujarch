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
import android.util.Log
import java.io.FileInputStream
import java.io.IOException

/**
 * The desktop's sound on the phone (LNX-6): what Linux plays at /run/lujarch/sound, 16-bit
 * little-endian PCM at 48 kHz in stereo, played as media while the desktop is on screen.
 */
class Sound {
    @Volatile private var pfd: ParcelFileDescriptor? = null
    @Volatile private var closing = false

    fun start(connection: ParcelFileDescriptor) {
        pfd = connection
        Thread({ play(connection) }, "sound").start()
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
    }

    private fun play(connection: ParcelFileDescriptor) {
        val format = AudioFormat.Builder()
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, RATE / 10 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val input = FileInputStream(connection.fileDescriptor)
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                track.write(buffer, 0, n)
            }
        } catch (e: IOException) {
            if (!closing) Log.w(TAG, "sound: ${e.message}")
        } finally {
            track.release()
            try {
                connection.close()
            } catch (e: IOException) {
                Log.w(TAG, "sound: close: ${e.message}")
            }
        }
    }

    private companion object {
        const val RATE = 48000
    }
}
