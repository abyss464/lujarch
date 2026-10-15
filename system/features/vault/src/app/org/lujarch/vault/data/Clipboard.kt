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

package org.lujarch.vault.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import org.lujarch.vault.TAG

/**
 * Copies a value marked sensitive (ClipDescription.EXTRA_IS_SENSITIVE: the
 * system's clipboard preview and keyboards hide it) and clears the clipboard
 * after the delay chosen in Settings (never, 15 s to 5 min).
 *
 * The clear is an exact alarm to [ClipboardClearReceiver]: a cached app is
 * frozen, so a timer in the process would not fire, and a short foreground
 * service ends after about 3 minutes. The alarm wakes the app whatever state
 * it is in; writing the clipboard needs no focus.
 */
object Clipboard {
    /** The delays offered in Settings, in seconds; 0 is "never". */
    val DELAYS_S = intArrayOf(0, 15, 30, 60, 120, 300)

    fun copy(context: Context, label: CharSequence, value: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        cm.setPrimaryClip(clip)
        val seconds = Vault.prefs.clipboardClearSeconds
        if (seconds > 0) schedule(context, seconds) else cancel(context)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ClipboardClearReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Each copy moves the clear to [seconds] after it. */
    private fun schedule(context: Context, seconds: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + seconds * 1000L
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
        } else {
            // USE_EXACT_ALARM is granted at install; this path only runs if that ever changes.
            Log.w(TAG, "exact alarms not allowed; the clipboard clear may come late")
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent(context))
        }
    }

    /** "Never": a clear scheduled for an earlier copy must not remove this one. */
    private fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }
}

/** Clears the clipboard when the alarm of the last copy fires (not exported). */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
            Log.i(TAG, "clipboard cleared")
        } catch (e: RuntimeException) {
            Log.e(TAG, "clearing the clipboard failed", e)
        }
    }
}
