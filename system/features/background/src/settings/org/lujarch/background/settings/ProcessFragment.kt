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

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
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
 * Settings > Apps > (app) > Background > (process) (BG-7): the process's depth, the reasons it
 * is there with who caused each and the system's priority, what it uses in the last 24 hours,
 * its threads by CPU time, read only; Pause this process and End this process act once.
 */
class ProcessFragment : BasePreferenceFragment() {
    private var pid = 0
    private lateinit var packageName: String
    private var label = ""
    private lateinit var idPref: Preference
    private lateinit var nowPref: NowPreference
    private lateinit var reasonsPref: PreferenceCategory
    private lateinit var usePref: TilesPreference
    private lateinit var threadsPref: PreferenceCategory
    private lateinit var actionsPref: ActionButtonsPreference
    private var loop: Job? = null
    private var gone = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val args = requireArguments()
        pid = args.getInt(ARG_PID)
        packageName = args.getString(AppBackgroundFragment.ARG_PACKAGE).orEmpty()
        label = args.getString(AppBackgroundFragment.ARG_LABEL) ?: packageName
        val context = prefContext
        idPref = screen.add(HeaderPreference(context)) {
            key = "process"
            title = args.getString(ARG_NAME)
        }
        nowPref = screen.add(NowPreference(context)) { key = "now" }
        reasonsPref = screen.add(PreferenceCategory(context)) {
            key = "reasons"
            setTitle(R.string.background_process_why)
        }
        val use = screen.add(PreferenceCategory(context)) {
            key = "use"
            setTitle(R.string.background_process_use)
        }
        usePref = use.add(TilesPreference(context)) { key = "use_tiles" }
        threadsPref = screen.add(PreferenceCategory(context)) {
            key = "threads"
            setTitle(R.string.background_process_threads)
        }
        actionsPref = screen.add(ActionButtonsPreference(context)) {
            key = "process_actions"
            setButton1Text(R.string.background_pause_process)
            setButton1Icon(R.drawable.ic_background_pause)
            setButton1OnClickListener { perform(PAUSE) }
            setButton2Text(R.string.background_end_process)
            setButton2Icon(R.drawable.ic_background_end)
            setButton2OnClickListener { perform(END) }
        }
        screen.add(FooterPreference(context)) {
            key = "process_footer"
            title = getString(R.string.background_process_footer, label)
            setLearnMoreText(getString(R.string.background_process_footer_link, label))
            setLearnMoreAction { AppBackgroundFragment.open(requireContext(), packageName, label) }
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
        val p = attempt("process", R.string.background_error_load) {
            BackgroundClient.call { it.getProcess(pid) }
        }
        if (p == null) {
            if (!pageAlive) return
            nowPref.depth = LujarchBackground.DEPTH_RECLAIMED
            nowPref.state = LujarchBackground.depthName(prefContext, LujarchBackground.DEPTH_RECLAIMED)
            nowPref.text = getString(R.string.background_process_gone)
            nowPref.update()
            actionsPref.setButton1Enabled(false)
            actionsPref.setButton2Enabled(false)
            gone = true
            return
        }
        val network = orNull("network use") { networkUse(requireContext(), p.uid) }
        render(p, network)
    }

    private fun render(p: LujarchBackgroundProcess, network: Long?) {
        if (!pageAlive) return
        val context = prefContext
        idPref.summary = getString(R.string.background_process_id, p.pid, p.uid,
            AppBackgroundFragment.duration(context, System.currentTimeMillis() - p.startedMillis))
        nowPref.depth = p.depth
        nowPref.state = LujarchBackground.depthName(context, p.depth)
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
        val threads = p.threads ?: emptyArray()
        usePref.tiles = listOf(
            getString(R.string.background_use_memory) to Formatter.formatShortFileSize(context, p.memoryBytes),
            getString(R.string.background_use_swap) to Formatter.formatShortFileSize(context, p.swapBytes),
            getString(R.string.background_use_cpu) to cpu(context, p.cpuMs),
            getString(R.string.background_use_wakeups) to
                resources.getQuantityString(R.plurals.background_times, p.wakeups, p.wakeups),
            getString(R.string.background_use_network) to
                (network?.let { Formatter.formatShortFileSize(context, it) } ?: "—"),
            getString(R.string.background_use_threads) to threads.size.toString(),
        )
        threadsPref.removeAll()
        threads.take(MAX_THREADS).forEachIndexed { i, t ->
            threadsPref.add(LinePreference(context)) {
                key = "thread_$i"
                showWho = false
                text = t.name
                lead = t.state
                value = cpu(context, t.cpuMs)
            }
        }
        actionsPref.setButton1Enabled(!p.frozen && p.depth != LujarchBackground.DEPTH_ON_SCREEN)
        actionsPref.setButton2Enabled(true)
    }

    /** The app's use of the networks in the last 24 hours (the kernel counts it per app, not per process). */
    private suspend fun networkUse(context: Context, uid: Int): Long = withContext(Dispatchers.IO) {
        val nsm = context.getSystemService(NetworkStatsManager::class.java)!!
        val end = System.currentTimeMillis()
        val start = end - DAY_MS
        var total = 0L
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            nsm.queryDetailsForUid(type, null, start, end, uid).use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    total += bucket.rxBytes + bucket.txBytes
                }
            }
        }
        total
    }

    private fun perform(what: Int) {
        launchUi {
            act(if (what == PAUSE) R.string.background_error_pause else R.string.background_error_end) {
                BackgroundClient.call { if (what == PAUSE) it.pauseProcess(pid) else it.endProcess(pid) }
            }
            delay(1_000)
            load()
        }
    }

    private fun cpu(context: Context, ms: Long): String =
        if (ms < 60_000) context.getString(R.string.background_duration_seconds, ms / 1000)
        else AppBackgroundFragment.duration(context, ms)

    companion object {
        const val ARG_PID = "pid"
        const val ARG_NAME = "name"
        private const val PAUSE = 1
        private const val END = 2
        private const val REFRESH_MS = 5_000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val MAX_THREADS = 20

        fun open(context: Context, pid: Int, name: String, packageName: String, label: String?) {
            Pages.open(context, ProcessFragment::class.java, R.string.background_process_title, title = name,
                arguments = AppBackgroundFragment.args(packageName, label).apply {
                    putInt(ARG_PID, pid)
                    putString(ARG_NAME, name)
                })
        }
    }
}
