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

package org.lujarch.settings.pages

import android.content.Context
import android.text.BidiFormatter
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import java.util.Date

/** Localized number, size and time formatting. */
object Format {
    fun bytes(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

    fun speed(context: Context, bytesPerSecond: Long): String =
        context.getString(R.string.format_speed, Formatter.formatFileSize(context, bytesPerSecond))

    fun upDown(context: Context, up: String, down: String): String =
        context.getString(R.string.format_up_down, up, down)

    fun time(context: Context, millis: Long): String =
        DateFormat.getTimeFormat(context).format(Date(millis))

    /** A moment: its time today, its date and time on another day. */
    fun at(context: Context, millis: Long): String =
        if (DateUtils.isToday(millis)) {
            time(context, millis)
        } else {
            DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd Hm"),
                millis).toString()
        }

    /**
     * A time relative to now for every page: "just now" within a minute, where DateUtils at
     * minute resolution says "0 minutes ago" (or "in 0 minutes" for a time a few seconds
     * ahead), then DateUtils' "5 minutes ago", "yesterday", a date. [abbreviated]: "5 min. ago"
     * (DateUtils.FORMAT_ABBREV_RELATIVE).
     */
    fun ago(context: Context, millis: Long, abbreviated: Boolean = false): CharSequence {
        val now = System.currentTimeMillis()
        if (Math.abs(now - millis) < DateUtils.MINUTE_IN_MILLIS) return context.getString(R.string.time_just_now)
        return if (abbreviated) {
            DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        } else {
            DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS)
        }
    }

    /**
     * Names for running text: Chinese quotes each one, since quotes
     * around the whole list would read as one name; English leaves them bare.
     * Each name is isolated so that a right-to-left sentence keeps the list's
     * order and the separators between the names.
     */
    fun names(context: Context, names: Iterable<String>): String {
        val bidi = BidiFormatter.getInstance()
        return names.joinToString(context.getString(R.string.list_separator)) {
            context.getString(R.string.quoted_name, bidi.unicodeWrap(it))
        }
    }

    fun delay(context: Context, delay: Int?): String? = when {
        delay == null -> null
        delay <= 0 -> context.getString(R.string.delay_timeout)
        else -> context.getString(R.string.delay_ms, delay)
    }

    /** Removes ANSI escape sequences from tool output. */
    fun stripAnsi(text: String): String = text.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
}
