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
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.internal.app.LujarchBackground
import com.android.internal.app.LujarchBackgroundProcess
import com.android.settingslib.widget.ActionButtonsPreference
import com.android.settingslib.widget.FooterPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Pages

/**
 * Settings > System > Memory > (app) > (process) (BG-7): the process's state and why, in plain
 * words with who caused it; its memory as actual use, resident and compressed; its place in the
 * order of reclaiming with the system's score in brackets; its CPU time today; its threads by
 * CPU time, read only. End this process ends it once; Force stop is Android's. One of Lujarch's
 * system services has no app: its page has neither.
 */
class ProcessFragment : BasePreferenceFragment() {
    private var pid = 0
    /** The app's package; null for one of Lujarch's system services. */
    private var packageName: String? = null
    private var label = ""
    private lateinit var idPref: Preference
    private lateinit var nowPref: NowPreference
    private lateinit var reasonsPref: PreferenceCategory
    private lateinit var memoryPref: Preference
    private lateinit var reclaimPref: Preference
    private lateinit var cpuPref: Preference
    private lateinit var threadsPref: PreferenceCategory
    private var actionsPref: ActionButtonsPreference? = null
    private var loop: Job? = null
    private var gone = false
    private var allThreads = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val args = requireArguments()
        pid = args.getInt(ARG_PID)
        packageName = args.getString(AppMemoryFragment.ARG_PACKAGE)
        val name = args.getString(ARG_NAME).orEmpty()
        label = args.getString(AppMemoryFragment.ARG_LABEL) ?: name
        val context = prefContext
        idPref = screen.add(HeaderPreference(context)) {
            key = "process"
            title = title(context, name, packageName, label)
        }
        nowPref = screen.add(NowPreference(context)) { key = "now" }
        reasonsPref = screen.add(PreferenceCategory(context)) {
            key = "reasons"
            setTitle(R.string.memory_process_why)
        }
        val use = screen.add(PreferenceCategory(context)) { key = "use" }
        memoryPref = use.add(Preference(context)) {
            key = "memory"
            setTitle(R.string.memory_title)
            isSelectable = false
        }
        reclaimPref = use.add(Preference(context)) {
            key = "reclaim_order"
            setTitle(R.string.memory_process_reclaim)
            isSelectable = false
        }
        cpuPref = use.add(Preference(context)) {
            key = "cpu"
            setTitle(R.string.memory_process_cpu)
            isSelectable = false
        }
        threadsPref = screen.add(PreferenceCategory(context)) {
            key = "threads"
            setTitle(R.string.memory_process_threads)
        }
        val pkg = packageName
        if (pkg != null) {
            actionsPref = screen.add(ActionButtonsPreference(context)) {
                key = "process_actions"
                setButton1Text(R.string.memory_end_process)
                setButton1Icon(R.drawable.ic_memory_end)
                setButton1OnClickListener { end() }
                setButton2Text(R.string.memory_force_stop)
                setButton2Icon(R.drawable.ic_memory_force_stop)
                setButton2OnClickListener { confirmForceStop(pkg) }
            }
        }
        screen.add(FooterPreference(context)) {
            key = "process_footer"
            title = if (pkg != null) getString(R.string.memory_process_footer, label)
                else getString(R.string.memory_service_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        loop = launchUi {
            while (!gone) {
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
        val p = attempt("process", R.string.memory_error_load) {
            BackgroundClient.call { it.getProcess(pid) }
        }
        if (!pageAlive) return
        if (p == null) {
            nowPref.state = LujarchBackground.RUN_RECLAIMED.toInt()
            nowPref.stateName = LujarchBackground.stateName(prefContext, nowPref.state)
            nowPref.text = getString(R.string.memory_process_gone)
            nowPref.update()
            actionsPref?.setButton1Enabled(false)
            gone = true
            return
        }
        render(p)
    }

    private fun render(p: LujarchBackgroundProcess) {
        val context = prefContext
        idPref.summary = packageName?.let { getString(R.string.memory_process_id, it, p.pid) }
            ?: getString(R.string.memory_service_id, p.pid)
        nowPref.state = p.state
        nowPref.stateName = LujarchBackground.stateName(context, p.state)
        nowPref.text = p.reason
        nowPref.update()
        reasonsPref.removeAll()
        p.reasons?.forEachIndexed { i, r ->
            reasonsPref.add(LinePreference(context)) {
                key = "reason_$i"
                who = r.who
                whoName = r.whoName
                text = r.text
            }
        }
        memoryPref.summary = getString(R.string.memory_process_memory,
            Formatter.formatShortFileSize(context, p.memoryBytes),
            Formatter.formatShortFileSize(context, p.rssBytes),
            Formatter.formatShortFileSize(context, p.swapBytes))
        val order = if (p.reclaimOrder > 0) getString(R.string.memory_reclaim_order, p.reclaimOrder)
            else null
        reclaimPref.summary = listOfNotNull(order, p.priority).joinToString("\n")
        reclaimPref.isVisible = order != null || p.priority != null
        cpuPref.summary = getString(R.string.memory_process_cpu_today, cpu(context, p.cpuMs))

        val threads = p.threads ?: emptyArray()
        threadsPref.title = resources.getQuantityString(R.plurals.memory_process_threads_count,
            threads.size, threads.size)
        threadsPref.removeAll()
        if (p.frozen) {
            threadsPref.add(Preference(context)) {
                key = "threads_paused"
                setSummary(R.string.memory_process_threads_paused)
                isSelectable = false
            }
        }
        val shown = if (allThreads) threads.size else minOf(threads.size, FEW_THREADS)
        threads.take(shown).forEachIndexed { i, t ->
            threadsPref.add(LinePreference(context)) {
                key = "thread_$i"
                showWho = false
                text = t.name
                lead = t.state
                value = cpu(context, t.cpuMs)
            }
        }
        if (shown < threads.size) {
            threadsPref.add(Preference(context)) {
                key = "threads_all"
                title = resources.getQuantityString(R.plurals.memory_process_threads_all,
                    threads.size, threads.size)
                setOnPreferenceClickListener {
                    allThreads = true
                    render(p)
                    true
                }
            }
        }
        actionsPref?.setButton1Enabled(true)
    }

    private fun end() {
        launchUi {
            act(R.string.memory_error_end) {
                BackgroundClient.call { it.endProcess(pid) }
            }
            delay(1_000)
            load()
        }
    }

    /** Android's force stop, with Android's own question first. */
    private fun confirmForceStop(pkg: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.memory_force_stop_title)
            .setMessage(R.string.memory_force_stop_text)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                launchUi {
                    act(R.string.memory_error_end) {
                        withContext(Dispatchers.IO) {
                            requireContext().getSystemService(ActivityManager::class.java)!!
                                .forceStopPackage(pkg)
                        }
                    }
                    delay(1_000)
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cpu(context: Context, ms: Long): String =
        if (ms < 60_000) context.getString(R.string.memory_duration_seconds, ms / 1000)
        else AppMemoryFragment.duration(context, ms)

    companion object {
        const val ARG_PID = "pid"
        const val ARG_NAME = "name"
        private const val REFRESH_MS = 5_000L
        private const val FEW_THREADS = 5

        /** "WeChat · Main process" for an app's process; a service by its own name. */
        private fun title(context: Context, name: String, packageName: String?, label: String): String =
            if (packageName == null) name
            else context.getString(R.string.memory_process_of, label,
                AppMemoryFragment.processLabel(context, name, packageName))

        /** An app's process page; packageName null for one of Lujarch's system services. */
        fun open(context: Context, pid: Int, name: String, packageName: String?, label: String?) {
            Pages.open(context, ProcessFragment::class.java, R.string.memory_process_title,
                arguments = Bundle().apply {
                    putString(AppMemoryFragment.ARG_PACKAGE, packageName)
                    putString(AppMemoryFragment.ARG_LABEL, label)
                    putInt(ARG_PID, pid)
                    putString(ARG_NAME, name)
                })
        }
    }
}
