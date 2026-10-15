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

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Log tag of the page framework. */
const val TAG = "lujarch_settings"

/** A failure that says itself to the owner; a feature's errors implement it. */
interface Failure {
    /** One sentence in the owner's language, with no internal names. */
    fun describe(context: Context): String

    /** The system's own text, shown only behind Details; null when the sentence says it all. */
    val details: String?

    /** The service behind the page cannot be reached: every other failure follows from it. */
    val unavailable: Boolean
        get() = false
}

/** A failure whose message is already written for the owner (localized). */
class UserMessageException(message: String) : Exception(message)

/**
 * The words for failures: one sentence in the owner's language ([describe]), and the system's own
 * text ([details]) only behind Details.
 */
object Errors {
    fun isUnavailable(t: Throwable): Boolean = (t as? Failure)?.unavailable == true

    fun describe(context: Context, t: Throwable): String =
        when (t) {
            is Failure -> t.describe(context)
            is UserMessageException ->
                t.message ?: context.getString(R.string.error_unexpected_sentence)
            else -> context.getString(R.string.error_unexpected_sentence)
        }

    fun details(t: Throwable): String? =
        when (t) {
            is Failure -> t.details
            is UserMessageException -> null
            else -> t.toString()
        }

    /** The sentence and, behind Details, the system's text, for a page dialog. */
    fun dialog(context: Context, title: CharSequence, t: Throwable, onDetails: () -> Unit): Dialog {
        val builder =
            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(describe(context, t))
                .setPositiveButton(android.R.string.ok, null)
        if (details(t) != null) {
            builder.setNeutralButton(R.string.error_details) { _, _ -> onDetails() }
        }
        return builder.create()
    }

    /** The system's own text of [t], selectable for copying, for a page dialog. */
    fun detailsDialog(context: Context, title: CharSequence, t: Throwable): Dialog {
        val pad = InputDialog.dp(context, 24)
        val text =
            TextView(context).apply {
                text = (details(t) ?: describe(context, t)).takeLast(MAX_DETAILS)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(pad, InputDialog.dp(context, 12), pad, 0)
            }
        val scroll = ScrollView(context).apply { addView(text) }
        return AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.action_close, null)
            .create()
    }

    private const val MAX_DETAILS = 8000
}
