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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException
import org.lujarch.linux.ILinux
import org.lujarch.linux.client.Linux
import org.lujarch.linux.client.LinuxException
import org.lujarch.linux.client.TAG

/**
 * Android's microphone for Linux (LNX-7, Microphone): while a program in Linux reads
 * ILinux.STREAM_MICROPHONE, the microphone records into it, voice communication input (Android's
 * echo cancellation and noise suppression), 16-bit PCM at 48 kHz in mono; Android's microphone
 * indicator shows meanwhile. Android's own dialog asks for the permission the first time.
 */
class Microphone(private val context: Context, private val parts: LinuxParts) {
    @Volatile private var thread: Thread? = null
    private var wanted = false

    /** On the parts' thread: a program reads the microphone, or none does any more. */
    fun want(on: Boolean) {
        wanted = on
        if (!on) {
            stop()
            return
        }
        if (thread?.isAlive == true) return
        if (!Linux.lent(context, "microphone")) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            parts.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { granted ->
                if (granted && wanted) want(true)
                if (!granted) Log.i(TAG, "microphone: the permission is not granted; Linux records silence")
            }
            return
        }
        val fifo = try {
            Linux.blocking("openStream") { it.openStream(ILinux.STREAM_MICROPHONE) }
        } catch (e: LinuxException) {
            Log.w(TAG, e.message ?: "openStream")
            return
        }
        val t = Thread({ record(fifo) }, "LinuxMicrophone")
        thread = t
        t.start()
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        t.interrupt()
    }

    private fun build(): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder()
            .setContext(context)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min * 2, BUFFER_BYTES))
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            Log.w(TAG, "microphone: AudioRecord did not initialize")
            null
        } else {
            r
        }
    } catch (e: Exception) {
        Log.w(TAG, "microphone: AudioRecord: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    /** The recording thread: records until Linux stops reading or [stop]. */
    private fun record(fifo: ParcelFileDescriptor) {
        val rec = build()
        if (rec == null) {
            close(fifo)
            val self = Thread.currentThread()
            parts.handler.post { if (thread === self) thread = null }
            return
        }
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "microphone: recording did not start (another app records?)")
                return
            }
            Log.i(TAG, "microphone: recording for Linux")
            val fd = fifo.fileDescriptor
            val buf = ByteArray(CHUNK_BYTES)
            while (thread === Thread.currentThread()) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    Log.w(TAG, "microphone: read failed ($n)")
                    return
                }
                if (n == 0) continue
                // At most PIPE_BUF bytes, whole samples: written whole or not at all, so Linux
                // never reads half a sample. Linux lagging behind loses what does not fit.
                try {
                    Os.write(fd, buf, 0, n)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN) continue
                    if (e.errno != OsConstants.EPIPE) Log.w(TAG, "microphone: ${e.message}")
                    return
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "microphone: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                rec.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "microphone: stop: ${e.message}")
            }
            rec.release()
            close(fifo)
            Log.i(TAG, "microphone: recording stopped")
            val self = Thread.currentThread()
            parts.handler.post { if (thread === self) thread = null }
        }
    }

    private fun close(fifo: ParcelFileDescriptor) {
        try {
            fifo.close()
        } catch (e: IOException) {
            Log.w(TAG, "microphone: close: ${e.message}")
        }
    }

    private companion object {
        const val RATE = 48000
        /** 10 ms of mono 16-bit PCM, less than PIPE_BUF. */
        const val CHUNK_BYTES = RATE / 100 * 2
        /** 200 ms in Android's buffer. */
        const val BUFFER_BYTES = RATE / 5 * 2
    }
}
