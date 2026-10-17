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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.lujarch.linux.client.TAG

/**
 * Android's permission dialogs for the parts Linux is lent (LNX-7), over the home screen while
 * Linux is on it: each permission at most once while the parts are served (LinuxPartsService),
 * asked one after the other through [PermissionActivity]. On the main thread.
 */
object LinuxWindows {
    private class Ask(val permissions: Array<String>, val done: (Boolean) -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val asked = HashSet<String>()
    private val queue = ArrayDeque<Ask>()
    private var asking: Ask? = null
    private var serving = false

    /** The parts are served: Linux is on screen. */
    fun start() {
        main.post {
            serving = true
            asked.clear()
        }
    }

    /** Linux left the screen: what is still asked is not granted. */
    fun stop() {
        main.post {
            serving = false
            val pending = ArrayList(queue)
            queue.clear()
            asking?.let { pending.add(0, it) }
            asking = null
            for (ask in pending) ask.done(false)
        }
    }

    /** Any thread; [done] runs on the main thread with whether one of [permissions] is granted. */
    fun requestPermissions(context: Context, permissions: Array<String>, done: (Boolean) -> Unit) {
        main.post {
            if (permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                done(true)
            } else if (permissions.any { it in asked } || !serving) {
                done(false)
            } else {
                asked.addAll(permissions)
                queue.addLast(Ask(permissions, done))
                askNext(context)
            }
        }
    }

    private fun askNext(context: Context) {
        if (asking != null) return
        val ask = queue.removeFirstOrNull() ?: return
        asking = ask
        Log.i(TAG, "asking Android for ${ask.permissions.joinToString { it.substringAfterLast('.') }} for Linux")
        try {
            context.startActivity(
                Intent(context, PermissionActivity::class.java)
                    .putExtra(PermissionActivity.EXTRA_PERMISSIONS, ask.permissions)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "Android's permission dialog cannot be shown: ${e.message}")
            asking = null
            ask.done(false)
            askNext(context)
        }
    }

    /** From [PermissionActivity]'s result. */
    fun onPermissionsResult(context: Context, permissions: Array<String>) {
        val ask = asking ?: return
        asking = null
        // No permissions in the result: the request was cancelled; it may come again.
        if (permissions.isEmpty()) asked.removeAll(ask.permissions.toSet())
        val granted = ask.permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        Log.i(TAG, "${ask.permissions.joinToString { it.substringAfterLast('.') }} for Linux: ${if (granted) "granted" else "not granted"}")
        ask.done(granted)
        askNext(context)
    }
}

/** Shows Android's own permission dialog for one ask of [LinuxWindows], over the home screen. */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        if (permissions.isNullOrEmpty()) {
            LinuxWindows.onPermissionsResult(this, emptyArray())
            finish()
            return
        }
        if (savedInstanceState == null) requestPermissions(permissions, REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) LinuxWindows.onPermissionsResult(this, permissions)
        finish()
    }

    companion object {
        const val EXTRA_PERMISSIONS = "org.lujarch.linux.extra.PERMISSIONS"
        private const val REQUEST_PERMISSIONS = 0x4c58
    }
}
