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

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log
import org.lujarch.linux.ILinux
import org.lujarch.linux.ILinuxListener
import org.lujarch.linux.client.Linux
import org.lujarch.linux.client.LinuxException
import org.lujarch.linux.client.TAG

/**
 * The parts of the phone Linux is lent that the Terminal app serves while the home screen shows
 * Linux (LNX-7): Android's quick settings (its switches and readings, and the desktop flipping
 * them), vibration, the microphone, the cameras and the location. One for the process, made by
 * [LinuxPartsService]; everything runs on its own thread.
 */
class LinuxParts(private val context: Context) {
    private val thread = HandlerThread("LinuxParts").apply { start() }
    val handler = Handler(thread.looper)

    private val listener = object : ILinuxListener.Stub() {
        override fun onChanged() {}

        override fun onSessionExited(id: String, status: Int) {}

        override fun onAuthRequest(id: Int, text: String) {}

        override fun onNfcRequest(id: Int, command: ByteArray) {}

        override fun onClipboard(text: String) {}

        override fun onAndroidSet(key: String, value: String) {
            handler.post { if (Linux.lent(context, "quick_settings")) switches.set(key, value) }
        }

        override fun onShowTerminal() {}

        override fun onVibrate(pattern: String) {
            handler.post { if (Linux.lent(context, "vibration")) vibration.play(pattern) }
        }

        override fun onStream(stream: String, wanted: Boolean) {
            handler.post { stream(stream, wanted) }
        }
    }

    private val watch = AndroidWatch(context, handler) { sendState() }
    private val switches = Switches(context)
    private val vibration = Vibration(context)
    private val microphone = Microphone(context, this)
    private val cameras = Cameras(context, this)
    private val location = Location(context, this)
    private var quickSettings = false

    private val lent = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = follow()
    }

    fun start() {
        handler.post {
            val cr = context.contentResolver
            for (part in PARTS) {
                cr.registerContentObserver(Settings.Global.getUriFor("lujarch_linux_lend_$part"), false, lent)
            }
            try {
                Linux.blocking("registerListener") { it.registerListener(listener) }
            } catch (e: LinuxException) {
                Log.w(TAG, e.message ?: "registerListener")
            }
            follow()
        }
    }

    fun stop() {
        handler.post {
            context.contentResolver.unregisterContentObserver(lent)
            try {
                Linux.blocking("unregisterListener") { it.unregisterListener(listener) }
            } catch (e: LinuxException) {
                Log.w(TAG, e.message ?: "unregisterListener")
            }
            watch.stop()
            quickSettings = false
            microphone.stop()
            cameras.stop()
            location.stop()
            thread.quitSafely()
        }
    }

    /** Android's own dialog for [permissions] over the window in front; [done] runs on this thread. */
    fun requestPermissions(permissions: Array<String>, done: (Boolean) -> Unit) {
        LinuxWindows.requestPermissions(context, permissions) { granted -> handler.post { done(granted) } }
    }

    /** A switch of the lent parts changed: what is taken back stops at once. */
    private fun follow() {
        val qs = Linux.lent(context, "quick_settings")
        if (qs && !quickSettings) {
            watch.start()
            quickSettings = true
            sendState()
        } else if (!qs && quickSettings) {
            watch.stop()
            quickSettings = false
        }
        if (!Linux.lent(context, "microphone")) microphone.stop()
        if (!Linux.lent(context, "camera")) cameras.stop()
        if (!Linux.lent(context, "location")) location.stop()
    }

    private fun stream(stream: String, wanted: Boolean) {
        when (stream) {
            ILinux.STREAM_MICROPHONE -> microphone.want(wanted)
            ILinux.STREAM_CAMERA_BACK, ILinux.STREAM_CAMERA_FRONT -> cameras.want(stream, wanted)
            ILinux.STREAM_LOCATION -> location.want(wanted)
            else -> Log.w(TAG, "lujarch_linux names a stream the app has not: $stream")
        }
    }

    private fun sendState() {
        if (!quickSettings) return
        val state = watch.read()
        try {
            Linux.blocking("setAndroidState") { it.setAndroidState(state) }
        } catch (e: LinuxException) {
            Log.w(TAG, e.message ?: "setAndroidState")
        }
    }

    companion object {
        private val PARTS = listOf("quick_settings", "vibration", "microphone", "camera", "location")

        /** Calls to lujarch_linux that may block, one after the other. */
        val background: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}
