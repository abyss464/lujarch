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

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The Terminal app's windows of Linux (the terminal and the desktop) as the lent parts see them:
 * [LinuxParts] serves while one of them is started, and when one window covers the other the new
 * one starts before the old one stops, so the parts go on across the switch. The window in front
 * is the resumed one, else the one started last: Android's permission dialogs go to it, each
 * permission at most once while Linux stays on screen. On the main thread.
 */
object LinuxWindows {
    private class Ask(val permissions: Array<String>, val done: (Boolean) -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val started = ArrayList<Activity>()
    private var resumed: Activity? = null
    private var parts: LinuxParts? = null
    private val asked = HashSet<String>()
    private val queue = ArrayDeque<Ask>()
    private var asking: Ask? = null

    private val front: Activity? get() = resumed ?: started.lastOrNull()

    fun onStart(activity: Activity) {
        started.remove(activity)
        started.add(activity)
        if (parts == null) {
            asked.clear()
            parts = LinuxParts(activity.applicationContext).also { it.start() }
        }
    }

    fun onStop(activity: Activity) {
        if (!started.remove(activity)) return
        if (resumed === activity) resumed = null
        if (started.isEmpty()) {
            val pending = ArrayList(queue)
            queue.clear()
            asking?.let { pending.add(0, it) }
            asking = null
            for (ask in pending) ask.done(false)
            parts?.stop()
            parts = null
        }
    }

    fun onResume(activity: Activity) {
        resumed = activity
    }

    fun onPause(activity: Activity) {
        if (resumed === activity) resumed = null
    }

    /** Any thread; [done] runs on the main thread with whether one of [permissions] is granted. */
    fun requestPermissions(context: Context, permissions: Array<String>, done: (Boolean) -> Unit) {
        main.post {
            if (permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                done(true)
            } else if (permissions.any { it in asked } || started.isEmpty()) {
                done(false)
            } else {
                asked.addAll(permissions)
                queue.addLast(Ask(permissions, done))
                askNext()
            }
        }
    }

    private fun askNext() {
        if (asking != null) return
        val ask = queue.removeFirstOrNull() ?: return
        val activity = front
        if (activity == null || activity.isFinishing) {
            ask.done(false)
            askNext()
            return
        }
        asking = ask
        Log.i(TAG, "asking Android for ${ask.permissions.joinToString { it.substringAfterLast('.') }} for Linux")
        activity.requestPermissions(ask.permissions, REQUEST_PERMISSIONS)
    }

    /** From the windows' onRequestPermissionsResult. */
    fun onPermissionsResult(context: Context, requestCode: Int, permissions: Array<String>) {
        if (requestCode != REQUEST_PERMISSIONS) return
        val ask = asking ?: return
        asking = null
        // No permissions in the result: the request was cancelled; it may come again.
        if (permissions.isEmpty()) asked.removeAll(ask.permissions.toSet())
        val granted = ask.permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        Log.i(TAG, "${ask.permissions.joinToString { it.substringAfterLast('.') }} for Linux: ${if (granted) "granted" else "not granted"}")
        ask.done(granted)
        askNext()
    }

    const val REQUEST_PERMISSIONS = 0x4c58
}
