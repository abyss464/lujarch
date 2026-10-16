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

package org.lujarch.linux.settings

import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.linux.ILinux
import org.lujarch.linux.ILinuxListener
import org.lujarch.linux.Status
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog
import java.util.Date
import java.util.concurrent.Executors

/**
 * A page of the Linux development environment: it loads while it is in front, again whenever
 * lujarch_linux tells of a change and every few seconds besides.
 */
abstract class LinuxPage : BasePreferenceFragment() {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val listener = object : ILinuxListener.Stub() {
        override fun onChanged() {
            wake.trySend(Unit)
        }

        override fun onSessionExited(id: String, status: Int) {
            wake.trySend(Unit)
        }

        override fun onAuthRequest(id: Int, text: String) {}

        override fun onNfcRequest(id: Int, command: ByteArray) {}
    }

    /** Loads the page's data; failures go to the banner. */
    protected abstract suspend fun load()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    load()
                    withTimeoutOrNull(POLL_MS) { wake.receive() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        background.execute {
            try {
                LinuxClient.service("registerListener").registerListener(listener)
            } catch (e: Exception) {
                Log.w(TAG, "registerListener: ${e.message}")
            }
        }
    }

    override fun onStop() {
        background.execute {
            try {
                LinuxClient.service("unregisterListener").unregisterListener(listener)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterListener: ${e.message}")
            }
        }
        super.onStop()
    }

    override fun refresh() {
        wake.trySend(Unit)
    }

    /** One of [choices] (string resources), a page dialog; [onChoose] gets the index chosen. */
    protected fun <P : LinuxPage> P.choose(
        key: String,
        @StringRes title: Int,
        choices: IntArray,
        checked: Int,
        @RebuiltWithPage onChoose: P.(Int) -> Unit,
    ) {
        showDialog(key) { d ->
            AlertDialog.Builder(d.context)
                .setTitle(title)
                .setSingleChoiceItems(choices.map { d.context.getString(it) }.toTypedArray(), checked) { dialog, which ->
                    dialog.dismiss()
                    onChoose(which)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    companion object {
        private const val POLL_MS = 3000L

        /** Calls that outlive a page: the listener's registration. */
        val background = Executors.newSingleThreadExecutor()

        fun stateLabel(context: Context, status: Status?): String =
            context.getString(
                when (status?.state) {
                    null -> R.string.lnx_status_unknown
                    ILinux.STATE_NOT_INSTALLED -> R.string.lnx_state_not_installed
                    ILinux.STATE_STOPPED -> R.string.lnx_state_stopped
                    ILinux.STATE_STARTING -> R.string.lnx_state_starting
                    ILinux.STATE_SETUP -> R.string.lnx_state_setup
                    ILinux.STATE_RUNNING -> R.string.lnx_state_running
                    else -> R.string.lnx_state_failed
                },
            )

        @StringRes
        fun kindLabel(kind: String?): Int =
            when (kind) {
                ILinux.KIND_ARCH_ROOT -> R.string.lnx_kind_arch_root
                ILinux.KIND_ANDROID_ROOT -> R.string.lnx_kind_android_root
                else -> R.string.lnx_kind_arch
            }

        fun date(context: Context, seconds: Long): String =
            DateFormat.getMediumDateFormat(context).format(Date(seconds * 1000)) + " " +
                DateFormat.getTimeFormat(context).format(Date(seconds * 1000))

        fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

        /** A text of Linux (a file, a message), monospace and selectable, for a page dialog. */
        fun textDialog(context: Context, title: CharSequence, text: String): android.app.Dialog {
            val pad = (24 * context.resources.displayMetrics.density).toInt()
            val view = android.widget.TextView(context).apply {
                this.text = text
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(pad, pad / 2, pad, 0)
            }
            val scroll = android.widget.ScrollView(context).apply { addView(view) }
            return AlertDialog.Builder(context)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        }
    }
}
