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

package org.lujarch.installer.sheet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lujarch.installer.TAG

/**
 * Keeps an install going while its sheet is closed (Continue in background), with its progress in a
 * notification and its result in another once it ends with the sheet closed.
 */
class InstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val following = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val work = Work.find(intent?.getStringExtra(EXTRA_WORK))
        if (work == null || !work.running) {
            startForeground(FOREGROUND_ID, progress(this, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            if (following.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(FOREGROUND_ID, progress(this, work), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!following.add(work.id)) return START_NOT_STICKY
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (work.running) {
                nm.notify(FOREGROUND_ID, progress(this@InstallService, work))
                delay(500)
            }
            following.remove(work.id)
            if (work.inBackground) nm.notify(work.id.hashCode(), result(this@InstallService, work))
            if (following.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
    }

    companion object {
        private const val EXTRA_WORK = "org.lujarch.installer.extra.WORK"
        private const val CHANNEL = "installs"
        private const val FOREGROUND_ID = 0x1a57

        fun follow(context: Context, id: String) {
            try {
                context.startForegroundService(Intent(context, InstallService::class.java).putExtra(EXTRA_WORK, id))
            } catch (e: Exception) {
                Log.e(TAG, "install $id goes on without its notification", e)
            }
        }

        /** The sheet of [work], opened again from its notification. */
        fun sheet(context: Context, work: Work): PendingIntent =
            PendingIntent.getActivity(
                context, work.id.hashCode(),
                Intent(context, InstallActivity::class.java)
                    .putExtra(InstallActivity.EXTRA_WORK, work.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun channel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.ins_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun progress(context: Context, work: Work?): Notification {
            channel(context)
            val b =
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ins_ic_install)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_PROGRESS)
            if (work == null) return b.setContentTitle(context.getString(R.string.ins_installing)).build()
            val current = work.apps.firstOrNull { work.status[it.packageName] is AppStatus.Working }
            val st = current?.let { work.status[it.packageName] as AppStatus.Working }
            b.setContentIntent(sheet(context, work))
            if (current != null && st != null) {
                b.setContentTitle(Texts.workTitle(context, current, null) + " · " + current.label)
                b.setContentText(Texts.step(context, st.step))
                b.setProgress(1000, (st.progress * 1000).toInt(), false)
            } else {
                b.setContentTitle(context.getString(R.string.ins_installing))
                b.setProgress(0, 0, true)
            }
            return b.build()
        }

        private fun result(context: Context, work: Work): Notification {
            channel(context)
            val todo = work.apps.filter { work.status[it.packageName] !is AppStatus.Skipped }
            val done = todo.count { work.status[it.packageName] is AppStatus.Done }
            val b =
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ins_ic_install)
                    .setAutoCancel(true)
                    .setContentIntent(sheet(context, work))
            val one = todo.singleOrNull()
            if (one != null) {
                b.setContentTitle(Texts.workTitle(context, one, work.status[one.packageName]) + " · " + one.label)
                (work.status[one.packageName] as? AppStatus.Failed)?.let {
                    b.setContentText(org.lujarch.installer.InstallFailures.reason(context, it.failure.legacy, it.failure.status))
                }
            } else {
                b.setContentTitle(context.getString(R.string.ins_batch_result, done, todo.size))
            }
            return b.build()
        }
    }
}
