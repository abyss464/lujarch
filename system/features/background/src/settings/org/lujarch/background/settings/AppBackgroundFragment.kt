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

package org.lujarch.background.settings

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Bundle
import android.os.UserHandle
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.internal.app.LujarchBackground
import com.android.internal.app.LujarchBackgroundApp
import com.android.settingslib.widget.ActionButtonsPreference
import com.android.settingslib.widget.FooterPreference
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.lujarch.settings.appString
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.showDialog

/**
 * Settings > Apps > (app) > Background (BG-3): the app's depth now; Background usage, Keep and
 * Autostart drawn on the column of depths where each acts; what happened (BG-6); its processes
 * (BG-7); its time on screen, running in the background and its wakeups in the last 24 hours;
 * End background processes and Force stop.
 */
class AppBackgroundFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private var label = ""
    private var uid = -1
    private lateinit var headerPref: Preference
    private lateinit var columnPref: DepthColumnPreference
    private lateinit var eventsPref: PreferenceCategory
    private lateinit var processesPref: PreferenceCategory
    private lateinit var dayPref: TilesPreference
    private lateinit var actionsPref: ActionButtonsPreference
    private var app: LujarchBackgroundApp? = null
    private var loop: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        label = requireArguments().getString(ARG_LABEL) ?: packageName
        val context = prefContext
        uid = try {
            context.packageManager.getPackageUid(packageName, 0)
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            -1
        }
        headerPref = screen.add(HeaderPreference(context)) {
            key = "app"
            title = label
            setSummary(org.lujarch.settings.pages.R.string.loading)
            try {
                icon = context.packageManager.getApplicationIcon(packageName)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                android.util.Log.w(TAG, "$packageName has no icon", e)
            }
        }
        val after = screen.add(PreferenceCategory(context)) {
            key = "after"
            setTitle(R.string.background_after)
        }
        columnPref = after.add(DepthColumnPreference(context)) {
            key = "choices"
            onUsage = { BackgroundUsageFragment.open(requireContext(), packageName, label) }
            onKeep = { on -> setKept(on) }
            onAutostart = { chooseAutostart() }
        }
        after.add(FooterPreference(context)) {
            key = "column_note"
            setTitle(R.string.background_column_note)
        }
        eventsPref = screen.add(PreferenceCategory(context)) {
            key = LujarchBackground.KEY_WHAT_HAPPENED
            setTitle(R.string.background_what_happened)
        }
        processesPref = screen.add(PreferenceCategory(context)) {
            key = "processes"
            setTitle(R.string.background_processes)
        }
        val day = screen.add(PreferenceCategory(context)) {
            key = "day"
            setTitle(R.string.background_last_day)
        }
        dayPref = day.add(TilesPreference(context)) { key = "day_tiles" }
        actionsPref = screen.add(ActionButtonsPreference(context)) {
            key = "actions"
            setButton1Text(R.string.background_end_background)
            setButton1Icon(R.drawable.ic_background_end)
            setButton1OnClickListener { endBackground() }
            setButton2Text(R.string.background_force_stop)
            setButton2Icon(R.drawable.ic_background_force_stop)
            setButton2OnClickListener { confirmForceStop() }
        }
    }

    override fun onResume() {
        super.onResume()
        loop = launchUi {
            while (true) {
                load()
                delay(REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        loop?.cancel()
        super.onPause()
    }

    override fun refresh() {
        launchUi { load() }
    }

    private suspend fun load() {
        val user = UserHandle.myUserId()
        val a = attempt("app", R.string.background_error_load) {
            BackgroundClient.call { it.getApp(packageName, user) }
        }
        if (a == null) {
            headerPref.summary = getString(R.string.background_not_installed)
            return
        }
        app = a
        uid = a.uid
        val events = attempt("events", R.string.background_error_load) {
            BackgroundClient.call {
                it.getEvents(packageName, user, System.currentTimeMillis() - WEEK_MS, LATEST).toList()
            }
        } ?: emptyList()
        val onScreen = orNull("time on screen") { timeOnScreen(requireContext()) }
        val autostart = orNull("autostart") {
            withContext(Dispatchers.IO) { Autostart.mode(requireContext(), a.uid, packageName) }
        }
        render(a, events, onScreen, autostart)
    }

    private fun render(
        a: LujarchBackgroundApp,
        events: List<com.android.internal.app.LujarchBackgroundEvent>,
        onScreen: Long?,
        autostart: Int?,
    ) {
        if (!pageAlive) return
        val context = prefContext
        val depthName = LujarchBackground.depthName(context, a.depth)
        val parts = mutableListOf(depthName)
        if (a.memoryBytes > 0) parts += Formatter.formatShortFileSize(context, a.memoryBytes)
        // The reason under the depth, unless it only names the depth again.
        val reason = a.reason?.takeIf { it.isNotEmpty() && it != depthName }
        headerPref.summary = parts.joinToString(getString(R.string.background_separator)) +
            (reason?.let { "\n" + it } ?: "")
        columnPref.depth = a.depth
        columnPref.usage = a.usage
        columnPref.usageEnabled = !a.usageFixed
        columnPref.kept = a.kept
        columnPref.autostart = autostart?.let { Autostart.summary(context, it) } ?: ""
        columnPref.autostartEnabled = autostart != null
        columnPref.update()

        eventsPref.removeAll()
        val latest = events.take(SHOWN)
        if (latest.isEmpty()) {
            eventsPref.add(Preference(context)) {
                key = "events_none"
                setTitle(R.string.background_nothing_happened)
                isSelectable = false
            }
        }
        latest.forEachIndexed { i, e ->
            eventsPref.add(LinePreference(context)) {
                key = "event_$i"
                lead = timeOf(context, e.timeMillis)
                who = e.who
                whoName = e.whoName
                text = e.text
            }
        }
        eventsPref.add(Preference(context)) {
            key = "events_all"
            setTitle(R.string.background_all)
            setSummary(R.string.background_all_summary)
            setOnPreferenceClickListener {
                WhatHappenedFragment.open(requireContext(), packageName, label)
                true
            }
        }

        processesPref.removeAll()
        val procs = a.processes ?: emptyArray()
        processesPref.title = getString(R.string.background_processes_count, procs.size)
        if (procs.isEmpty()) {
            processesPref.add(Preference(context)) {
                key = "processes_none"
                setTitle(R.string.background_no_processes)
                isSelectable = false
            }
        }
        procs.sortedBy { it.depth }.forEach { p ->
            processesPref.add(Preference(context)) {
                key = "process_${p.pid}"
                title = p.name
                val depth = LujarchBackground.depthName(context, p.depth)
                summary = listOfNotNull(depth, p.reason?.takeIf { it.isNotEmpty() && it != depth },
                    Formatter.formatShortFileSize(context, p.memoryBytes))
                    .joinToString(getString(R.string.background_separator))
                icon = depthIcon(context, p.depth)
                setOnPreferenceClickListener {
                    ProcessFragment.open(requireContext(), p.pid, p.name.orEmpty(), packageName, label)
                    true
                }
            }
        }

        dayPref.tiles = listOf(
            getString(R.string.background_day_on_screen) to (onScreen?.let { duration(context, it) } ?: "—"),
            getString(R.string.background_day_running) to duration(context, a.backgroundMs),
            getString(R.string.background_day_woken) to
                resources.getQuantityString(R.plurals.background_times, a.wakeups, a.wakeups),
        )
        actionsPref.setButton1Enabled(a.processCount > 0)
        actionsPref.setButton2Enabled(a.depth != LujarchBackground.DEPTH_STOPPED)
    }

    private suspend fun timeOnScreen(context: Context): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val usm = context.getSystemService(UsageStatsManager::class.java)!!
        usm.queryAndAggregateUsageStats(now - DAY_MS, now)[packageName]?.totalTimeVisible ?: 0L
    }

    private fun setKept(on: Boolean) {
        launchUi {
            act(R.string.background_error_keep) {
                BackgroundClient.call { it.setKept(packageName, UserHandle.myUserId(), on) }
            }
            load()
        }
    }

    private fun chooseAutostart() {
        val appUid = uid
        val pkg = packageName
        showDialog("autostart") { d ->
            val ctx = d.context
            val current = Autostart.mode(ctx, appUid, pkg)
            AlertDialog.Builder(ctx)
                .setTitle(R.string.background_autostart)
                .setSingleChoiceItems(
                    Autostart.CHOICES.map { Autostart.label(ctx, it) }.toTypedArray(),
                    Autostart.CHOICES.indexOf(current),
                ) { dialog, which ->
                    setAutostart(Autostart.CHOICES[which])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun setAutostart(mode: Int) {
        launchUi {
            act(R.string.background_error_autostart) {
                withContext(Dispatchers.IO) { Autostart.set(requireContext(), uid, packageName, mode) }
            }
            load()
        }
    }

    private fun endBackground() {
        launchUi {
            act(R.string.background_error_end) {
                BackgroundClient.call { it.endBackgroundProcesses(packageName, UserHandle.myUserId()) }
            }
            delay(800)
            load()
        }
    }

    private fun confirmForceStop() {
        val pkg = packageName
        showDialog("force_stop") { d ->
            val ctx = d.context
            AlertDialog.Builder(ctx)
                .setTitle(appString(ctx, SETTINGS, "force_stop_dlg_title") ?: getString(R.string.background_force_stop))
                .setMessage(appString(ctx, SETTINGS, "force_stop_dlg_text"))
                .setPositiveButton(android.R.string.ok) { _, _ -> forceStop(pkg) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun forceStop(pkg: String) {
        launchUi {
            act(R.string.background_error_force_stop) {
                withContext(Dispatchers.IO) {
                    requireContext().getSystemService(ActivityManager::class.java)!!.forceStopPackage(pkg)
                }
            }
            delay(800)
            load()
        }
    }

    companion object {
        const val ARG_PACKAGE = "package"
        const val ARG_LABEL = "label"
        private const val SETTINGS = "com.android.settings"
        private const val REFRESH_MS = 5_000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val WEEK_MS = 7 * DAY_MS
        private const val LATEST = 20
        private const val SHOWN = 5

        fun args(packageName: String, label: String?) = Bundle().apply {
            putString(ARG_PACKAGE, packageName)
            putString(ARG_LABEL, label)
        }

        fun open(context: Context, packageName: String, label: String?) {
            Pages.open(context, AppBackgroundFragment::class.java, R.string.background_title,
                arguments = args(packageName, label))
        }

        fun timeOf(context: Context, millis: Long): String {
            val now = System.currentTimeMillis()
            return if (android.text.format.DateUtils.isToday(millis)) {
                DateFormat.getTimeFormat(context).format(Date(millis))
            } else {
                DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd Hm"),
                    millis).toString().also { if (now < millis) android.util.Log.w(TAG, "a line from the future") }
            }
        }

        fun duration(context: Context, ms: Long): String {
            val m = ms / 60_000
            return when {
                ms <= 0 -> context.getString(R.string.background_duration_minutes, 0)
                m < 1 -> context.getString(R.string.background_duration_less_minute)
                m < 60 -> context.getString(R.string.background_duration_minutes, m)
                else -> context.getString(R.string.background_duration_hours, m / 60, m % 60)
            }
        }
    }
}
