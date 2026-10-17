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

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.lujarch.linux.LinuxNotification
import org.lujarch.linux.client.Linux
import org.lujarch.linux.client.LinuxException
import org.lujarch.linux.client.TAG

/**
 * Android's notifications for Linux while they are lent (LNX-7): the notifications the shade
 * shows, without ongoing ones, handed to lujarch_linux a second after the last change. Lujarch
 * Settings grants this listener its access with the switch and takes it back with it.
 */
class NotificationsListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val send = Runnable { publish() }
    private var connected = false

    override fun onListenerConnected() {
        connected = true
        schedule()
    }

    override fun onListenerDisconnected() {
        connected = false
        main.removeCallbacks(send)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = schedule()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = schedule()

    private fun schedule() {
        main.removeCallbacks(send)
        main.postDelayed(send, DELAY_MS)
    }

    private fun publish() {
        if (!connected || !Linux.lent(this, "notifications")) return
        val active = try {
            activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "notifications: ${e.message}")
            return
        }
        val list = active
            .filter { !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .map { sbn ->
                LinuxNotification().apply {
                    app = label(sbn.packageName)
                    val extras = sbn.notification.extras
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                    time = sbn.postTime
                }
            }
            .toTypedArray()
        LinuxParts.background.execute {
            try {
                Linux.blocking("setNotifications") { it.setNotifications(list) }
            } catch (e: LinuxException) {
                Log.w(TAG, e.message ?: "setNotifications")
            }
        }
    }

    private fun label(packageName: String): String =
        try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

    private companion object {
        const val DELAY_MS = 1000L
    }
}
