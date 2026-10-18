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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Log
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.internal.app.LujarchBackground
import com.android.internal.app.LujarchBackgroundApp
import com.android.internal.app.LujarchBackgroundEvent
import com.android.internal.app.LujarchBackgroundMemory
import com.android.internal.app.procstats.IProcessStats
import com.android.internal.app.procstats.ProcessStats
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Pages

/**
 * Settings > Apps > (app) > Memory (BG-6): the app's average and highest use over the last 3
 * hours, its processes each with its state and memory (a row opens the process), whether it is
 * kept, its background usage level and its last reclaim, each leading to its own home.
 */
class AppMemoryFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private var label = ""
    private lateinit var headerPref: Preference
    private lateinit var processesPref: PreferenceCategory
    private lateinit var keptPref: Preference
    private lateinit var usagePref: Preference
    private lateinit var reclaimPref: Preference
    private lateinit var allPref: Preference
    private var loop: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        label = requireArguments().getString(ARG_LABEL) ?: packageName
        val context = prefContext
        headerPref = screen.add(HeaderPreference(context)) {
            key = "app"
            title = label
            setSummary(org.lujarch.settings.pages.R.string.loading)
            try {
                icon = context.packageManager.getApplicationIcon(packageName)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                Log.w(TAG, "$packageName has no icon", e)
            }
        }
        processesPref = screen.add(PreferenceCategory(context)) {
            key = "processes"
            setTitle(R.string.memory_processes_title)
        }
        val elsewhere = screen.add(PreferenceCategory(context)) {
            key = "after_leaving"
            setTitle(R.string.memory_after_leaving)
        }
        keptPref = elsewhere.add(Preference(context)) {
            key = "kept"
            setTitle(R.string.memory_keep)
            isSelectable = false
        }
        usagePref = elsewhere.add(Preference(context)) {
            key = "usage"
            title = getAppString(context, "manager_battery_usage_allow_background_usage_title")
                ?: getString(R.string.memory_usage)
            setOnPreferenceClickListener {
                openBatteryPage(true)
                true
            }
        }
        reclaimPref = elsewhere.add(Preference(context)) {
            key = "reclaim"
            setTitle(R.string.memory_last_reclaim)
            setOnPreferenceClickListener {
                openBatteryPage(false)
                true
            }
        }
        allPref = elsewhere.add(Preference(context)) {
            key = "all"
            setTitle(R.string.memory_all_apps)
            setOnPreferenceClickListener {
                startActivity(Intent(LujarchBackground.ACTION_MEMORY)
                    .setPackage(requireContext().packageName))
                true
            }
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
        val a = attempt("app", R.string.memory_error_load) {
            BackgroundClient.call { it.getApp(packageName, user) }
        }
        if (a == null) {
            headerPref.summary = getString(R.string.memory_not_installed)
            return
        }
        val reclaim = orNull("last reclaim") {
            BackgroundClient.call {
                it.getEvents(packageName, user, System.currentTimeMillis() - WEEK_MS, LATEST)
                    .firstOrNull { e -> e.kind == LujarchBackground.EVENT_ENDED }
            }
        }
        val memory = attempt("app's memory", R.string.memory_error_load) { BackgroundClient.call {
            it.memory } }
        val history = orNull("memory over 3 hours") { usedOver3Hours(a.uid) }
        render(a, reclaim, memory, history)
    }

    private fun render(a: LujarchBackgroundApp, reclaim: LujarchBackgroundEvent?,
            memory: LujarchBackgroundMemory?, history: LongArray?) {
        if (!pageAlive) return
        val context = prefContext
        headerPref.summary = if (history != null && history[0] > 0) {
            getString(R.string.memory_average, Formatter.formatShortFileSize(context, history[0]),
                Formatter.formatShortFileSize(context, history[1]))
        } else if (a.memoryBytes > 0) {
            getString(R.string.memory_now, Formatter.formatShortFileSize(context, a.memoryBytes))
        } else {
            LujarchBackground.stateName(context, a.state)
        }

        processesPref.removeAll()
        val procs = a.processes ?: emptyArray()
        if (procs.isEmpty()) {
            processesPref.add(Preference(context)) {
                key = "processes_none"
                title = LujarchBackground.stateName(context, a.state)
                summary = a.reason
                isSelectable = false
            }
        }
        procs.sortedWith(compareBy({ it.name?.contains(':') }, { it.state })).forEach { p ->
            processesPref.add(Preference(context)) {
                key = "process_${p.pid}"
                title = processLabel(context, p.name, packageName)
                val parts = mutableListOf(LujarchBackground.stateName(context, p.state))
                if (p.chosen) parts += getString(R.string.memory_chosen)
                parts += Formatter.formatShortFileSize(context, p.memoryBytes)
                if (p.connected) parts += getString(R.string.memory_connected)
                summary = parts.joinToString(getString(R.string.memory_separator))
                icon = stateIcon(context, p.state)
                setOnPreferenceClickListener {
                    ProcessFragment.open(requireContext(), p.pid, p.name.orEmpty(), packageName, label)
                    true
                }
            }
        }

        keptPref.summary = getString(if (a.kept) R.string.memory_kept_on else R.string.memory_kept_off)
        usagePref.summary = LujarchBackground.usageName(context, a.usage)
        reclaimPref.summary = reclaim?.let {
            getString(R.string.memory_reclaim_at, timeOf(context, it.timeMillis), it.text)
        } ?: getString(R.string.memory_never_reclaimed)
        reclaimPref.isSelectable = reclaim != null
        val kept = memory?.keptBytes ?: 0
        // Kept apps holding more than a quarter of the memory make the others go sooner.
        allPref.summary = getString(
            if (memory != null && kept * 4 > memory.totalBytes) R.string.memory_kept_total_much
            else R.string.memory_kept_total,
            Formatter.formatShortFileSize(context, kept))
    }

    /** App battery usage, or its Allow background usage page. */
    private fun openBatteryPage(allowBackgroundUsage: Boolean) {
        startActivity(Intent(Settings.ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL)
            .setData(Uri.fromParts("package", packageName, null))
            .setPackage(SETTINGS)
            .putExtra(LujarchBackground.EXTRA_ALLOW_BACKGROUND_USAGE, allowBackgroundUsage))
    }

    /**
     * The app's average and highest memory over the last 3 hours, in bytes, from Android's own
     * process stats; null when they cannot be read.
     */
    private suspend fun usedOver3Hours(uid: Int): LongArray? = withContext(Dispatchers.IO) {
        val service = IProcessStats.Stub.asInterface(
            ServiceManager.getService(ProcessStats.SERVICE_NAME)) ?: return@withContext null
        val pfd = service.getStatsOverTime(THREE_HOURS_MS) ?: return@withContext null
        val stats = ProcessStats(false)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stats.read(it) }
        if (stats.mReadError != null) {
            Log.w(TAG, "process stats: ${stats.mReadError}")
            return@withContext null
        }
        val versions = stats.mPackages.get(packageName, uid) ?: return@withContext null
        val totals = ProcessStats.ProcessDataCollection(ProcessStats.ALL_SCREEN_ADJ,
            ProcessStats.ALL_MEM_ADJ, ProcessStats.ALL_PROC_STATES)
        val now = SystemClock.uptimeMillis()
        var avg = 0L
        var max = 0L
        for (v in 0 until versions.size()) {
            val pkg = versions.valueAt(v)
            for (i in 0 until pkg.mProcesses.size) {
                pkg.mProcesses.valueAt(i).computeProcessData(totals, now)
                avg += totals.avgPss
                max += totals.maxPss
            }
        }
        longArrayOf(avg * 1024, max * 1024)
    }

    companion object {
        const val ARG_PACKAGE = "package"
        const val ARG_LABEL = "label"
        private const val SETTINGS = "com.android.settings"
        private const val REFRESH_MS = 5_000L
        private const val THREE_HOURS_MS = 3 * 60 * 60 * 1000L
        private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
        private const val LATEST = 200

        fun args(packageName: String, label: String?) = Bundle().apply {
            putString(ARG_PACKAGE, packageName)
            putString(ARG_LABEL, label)
        }

        fun open(context: Context, packageName: String, label: String?) {
            Pages.open(context, AppMemoryFragment::class.java, R.string.memory_title,
                arguments = args(packageName, label))
        }

        /** The main process by that name, another by its short name. */
        fun processLabel(context: Context, name: String?, packageName: String): String =
            if (name == null || name == packageName) context.getString(R.string.memory_main_process)
            else if (name.startsWith("$packageName:")) name.substring(packageName.length)
            else name

        fun timeOf(context: Context, millis: Long): String =
            if (android.text.format.DateUtils.isToday(millis)) {
                DateFormat.getTimeFormat(context).format(Date(millis))
            } else {
                DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(),
                    "MMMd Hm"), millis).toString()
            }

        fun duration(context: Context, ms: Long): String {
            val m = ms / 60_000
            return when {
                ms <= 0 -> context.getString(R.string.memory_duration_minutes, 0)
                m < 1 -> context.getString(R.string.memory_duration_less_minute)
                m < 60 -> context.getString(R.string.memory_duration_minutes, m)
                else -> context.getString(R.string.memory_duration_hours, m / 60, m % 60)
            }
        }

        /** A string of Android's Settings, so that the page uses its words. */
        fun getAppString(context: Context, name: String): String? =
            org.lujarch.settings.appString(context, SETTINGS, name)
    }
}
