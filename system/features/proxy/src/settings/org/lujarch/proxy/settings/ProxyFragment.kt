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

package org.lujarch.proxy.settings

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR

/** The proxy's state in words. */
fun proxyStateLabel(context: Context, state: String): String = ProxyFormat.stateLabel(context, state)

/**
 * Proxy: the switch, one status row, the mode, and the entries by purpose:
 * apps (per-app routing), nodes (groups, subscriptions), activity
 * (connections, logs) and advanced (rules, core settings, configuration
 * files, apply, check, roll back, restart). Actions that change the running
 * proxy ask first.
 */
class ProxyFragment : BasePreferenceFragment() {
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var statusPref: Preference
    private lateinit var taskPref: Preference
    private val modePrefs = LinkedHashMap<String, SelectorWithWidgetPreference>()
    private lateinit var globalNodePref: Preference
    private lateinit var corePref: Preference
    private lateinit var modeNotePref: Preference
    private val runningPrefs = LinkedHashMap<Preference, Int>()

    private var info: ProxyInfo? = null
    private var configs: MihomoConfigs? = null
    /** The persisted mode (`config get`), which every restart and rebuild uses. */
    private var savedMode: String? = null
    private var globalNode: String? = null
    private var traffic: Traffic? = null
    private var switchBusy = false
    private var modeBusy = false
    private var trafficJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        mainSwitch = screen.add(MainSwitchPreference(prefContext)) {
            key = KEY_ENABLED
            setTitle(R.string.px_main_switch)
            isPersistent = false
            isEnabled = false
            setOnPreferenceChangeListener { _, value ->
                toggleProxy(value as Boolean)
                false
            }
        }
        val status = screen.add(PreferenceCategory(prefContext)) { key = "status" }
        statusPref = status.add(Preference(prefContext)) {
            key = KEY_STATUS
            setTitle(R.string.px_status_title)
            setSummary(PagesR.string.loading)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), ProxyStatusFragment::class.java, R.string.px_status_page_title)
                true
            }
        }
        taskPref = status.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                ProxyTasks.running()?.let { TaskFragment.open(requireContext(), it.key) }
                    ?: lastTaskKey?.let { TaskFragment.open(requireContext(), it) }
                true
            }
        }

        val mode = screen.add(PreferenceCategory(prefContext)) {
            key = KEY_MODE
            setTitle(R.string.px_mode_category)
        }
        addMode(mode, Mihomo.MODE_RULE, R.string.px_mode_rule, R.string.px_mode_rule_summary)
        addMode(mode, Mihomo.MODE_GLOBAL, R.string.px_mode_global, R.string.px_mode_global_summary)
        addMode(mode, Mihomo.MODE_DIRECT, R.string.px_mode_direct, R.string.px_mode_direct_summary)
        // One affordance per row: the radios choose the mode; Global's exit node is its own row.
        globalNodePref = mode.add(Preference(prefContext)) {
            key = "global_node"
            setTitle(R.string.px_mode_global_node)
            isVisible = false
            setOnPreferenceClickListener {
                GroupFragment.open(requireContext(), GLOBAL)
                true
            }
        }
        modeNotePref = mode.add(Preference(prefContext)) {
            key = "mode_note"
            setIcon(R.drawable.ic_info)
            isVisible = false
            setOnPreferenceClickListener {
                savedMode?.let { applySavedMode(it) }
                true
            }
        }

        val apps = screen.add(PreferenceCategory(prefContext)) {
            key = "apps"
            setTitle(R.string.px_apps_category)
        }
        addEntry(apps, "app_routing", R.string.routing_title, R.string.px_routing_summary, PagesR.drawable.ic_apps) {
            Pages.open(requireContext(), AppRoutingFragment::class.java, R.string.routing_title)
        }
        addEntry(apps, "tun_include", R.string.px_include_title, R.string.px_include_summary, R.drawable.ic_px_filter) {
            Pages.open(requireContext(), TunIncludeFragment::class.java, R.string.px_include_title)
        }

        val nodes = screen.add(PreferenceCategory(prefContext)) {
            key = "nodes"
            setTitle(R.string.px_nodes_category)
        }
        addRunningEntry(nodes, "groups", R.string.px_groups_title, R.string.px_groups_summary, R.drawable.ic_groups) {
            Pages.open(requireContext(), GroupsFragment::class.java, R.string.px_groups_title)
        }
        addEntry(nodes, "subscriptions", R.string.px_subs_title, R.string.px_subs_summary, R.drawable.ic_subscriptions) {
            Pages.open(requireContext(), SubscriptionsFragment::class.java, R.string.px_subs_title)
        }

        val activity = screen.add(PreferenceCategory(prefContext)) {
            key = "activity"
            setTitle(R.string.px_activity_category)
        }
        addRunningEntry(activity, "connections", R.string.px_connections_title, R.string.px_connections_summary, R.drawable.ic_connections) {
            Pages.open(requireContext(), ConnectionsFragment::class.java, R.string.px_connections_title)
        }
        addRunningEntry(activity, "logs", R.string.px_logs_title, R.string.px_logs_summary, R.drawable.ic_logs) {
            Pages.open(requireContext(), LogsFragment::class.java, R.string.px_logs_title)
        }

        val advanced = screen.add(PreferenceCategory(prefContext)) {
            key = "advanced"
            setTitle(R.string.px_advanced_category)
        }
        addEntry(advanced, "rules", R.string.px_rules_title, R.string.px_rules_summary, R.drawable.ic_rule) {
            Pages.open(requireContext(), RulesFragment::class.java, R.string.px_rules_title)
        }
        corePref = addEntry(advanced, "core", R.string.px_core_title, R.string.px_core_summary, R.drawable.ic_terminal) {
            Pages.open(requireContext(), CoreSettingsFragment::class.java, R.string.px_core_title)
        }
        addEntry(advanced, "group_test", R.string.px_gt_title, R.string.px_gt_entry_summary, R.drawable.ic_timer) {
            Pages.open(requireContext(), GroupTestFragment::class.java, R.string.px_gt_title)
        }
        addEntry(advanced, "adaptive", R.string.px_ad_params_title, R.string.px_ad_params_entry_summary, R.drawable.ic_tune) {
            AdaptiveParamsFragment.open(requireContext())
        }
        addEntry(advanced, "files", R.string.px_files_title, R.string.px_files_summary, R.drawable.ic_folder) {
            Pages.open(requireContext(), ConfigFilesFragment::class.java, R.string.px_files_title)
        }
        addEntry(advanced, "apply", R.string.px_apply_title, R.string.px_apply_summary, R.drawable.ic_build) {
            confirm("px-apply", R.string.px_apply_title, { getString(R.string.px_apply_confirm) }, R.string.px_action_apply) {
                startConfigTask(TaskFragment.KEY_APPLY, R.string.px_apply_title) { applyConfig() }
            }
        }
        addEntry(advanced, "check", R.string.px_check_title, R.string.px_check_summary, R.drawable.ic_check) {
            startConfigTask(TaskFragment.KEY_CHECK, R.string.px_check_title) {
                step(context.getString(R.string.px_step_checking))
                val (result, output) = ProxyManager.validate()
                output(output)
                data(result)
                if (!result.ok) throw ProxyTasks.ConfigFailedException(result)
            }
        }
        addEntry(advanced, "rollback", R.string.px_rollback_title, R.string.px_rollback_summary, R.drawable.ic_restore) {
            confirm("px-rollback", R.string.px_rollback_title, { getString(R.string.px_rollback_confirm) }, R.string.px_action_roll_back) {
                startConfigTask(TaskFragment.KEY_ROLLBACK, R.string.px_rollback_title) {
                    step(context.getString(R.string.px_step_rolling_back))
                    val (result, output) = ProxyManager.rollback()
                    output(output)
                    data(result)
                    if (!result.ok) throw ProxyTasks.ConfigFailedException(result)
                }
            }
        }
        addEntry(advanced, "restart", R.string.px_restart_title, R.string.px_restart_summary, R.drawable.ic_refresh) {
            confirm("px-restart", R.string.px_restart_title, { getString(R.string.px_restart_confirm) }, R.string.px_action_restart) {
                startConfigTask(TaskFragment.KEY_RESTART, R.string.px_restart_title, open = false) {
                    step(context.getString(R.string.px_step_restarting))
                    ProxyManager.coreRestart()
                    summary(context.getString(R.string.px_restart_done))
                }
            }
        }
        // PRX-4: a switch of the system, which the proxy's service reads after each network change.
        advanced.add(SwitchPreferenceCompat(prefContext)) {
            key = KEY_REPAIR
            setTitle(R.string.px_repair_title)
            setSummary(R.string.px_repair_summary)
            setIcon(R.drawable.ic_refresh)
            isPersistent = false
            isChecked = Settings.Global.getInt(prefContext.contentResolver, ProxyClient.SETTING_REPAIR, 1) == 1
            setOnPreferenceChangeListener { _, value ->
                Settings.Global.putInt(
                    prefContext.contentResolver, ProxyClient.SETTING_REPAIR, if (value as Boolean) 1 else 0,
                )
            }
        }
        // The key the pages use for the proxy's own interface; it is never shown here.
        addEntry(advanced, KEY_SECRET, R.string.px_secret_title, R.string.px_secret_summary, R.drawable.ic_key) {
            confirm("px-secret", R.string.px_secret_title, { getString(R.string.px_secret_confirm) }, R.string.px_secret_action) {
                startConfigTask(KEY_SECRET, R.string.px_secret_title) {
                    step(context.getString(R.string.px_secret_step))
                    val result = ProxyManager.rotateSecret()
                    output(result.output)
                    summary(
                        context.getString(
                            if (result.restarted) R.string.px_secret_done_restarted else R.string.px_secret_done,
                        ),
                    )
                }
            }
        }
        showInfo()
    }

    private var lastTaskKey: String? = null

    private fun startConfigTask(
        key: String,
        title: Int,
        open: Boolean = true,
        @OutlivesPage work: suspend ProxyTasks.Scope.() -> Unit,
    ) {
        val context = requireContext()
        if (!ProxyTasks.start(context, key, getString(title), work)) {
            Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
            return
        }
        lastTaskKey = key
        if (open) TaskFragment.open(context, key)
    }

    private fun addMode(group: PreferenceCategory, mode: String, title: Int, summary: Int) {
        modePrefs[mode] = group.add(SelectorWithWidgetPreference(prefContext)) {
            key = "mode_$mode"
            setTitle(title)
            setSummary(summary)
            isPersistent = false
            isEnabled = false
            setOnClickListener { setMode(mode) }
        }
    }

    private fun addEntry(
        group: PreferenceCategory,
        key: String,
        title: Int,
        summary: Int,
        icon: Int,
        onClick: () -> Unit,
    ): Preference = group.add(Preference(prefContext)) {
        this.key = key
        setTitle(title)
        setSummary(summary)
        setIcon(icon)
        setOnPreferenceClickListener {
            onClick()
            true
        }
    }

    /** An entry that needs the running core (its controller). */
    private fun addRunningEntry(group: PreferenceCategory, key: String, title: Int, summary: Int, icon: Int, onClick: () -> Unit) {
        runningPrefs[addEntry(group, key, title, summary, icon, onClick)] = summary
    }

    // ---- polling ----

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val pageScope = this
                launch {
                    var wasRunning = false
                    ProxyTasks.tasks.collect {
                        showTask()
                        // An import or a restore may bring another saved mode.
                        val running = ProxyTasks.running() != null
                        if (wasRunning && !running) {
                            refresh()
                            loadSavedMode()
                        }
                        wasRunning = running
                    }
                }
                // The manager may be busy with a task; the status must not wait for it.
                launch { loadSavedMode() }
                while (isActive) {
                    poll()
                    val up = info?.controllerUp == true
                    if (up && trafficJob?.isActive != true) {
                        trafficJob = pageScope.launch { collectTraffic() }
                    } else if (!up) {
                        trafficJob?.cancel()
                        trafficJob = null
                        traffic = null
                    }
                    withTimeoutOrNull(POLL_MS) { wake.receive() }
                }
            }
        }
    }

    override fun refresh() {
        wake.trySend(Unit)
    }

    private suspend fun loadSavedMode() {
        attempt("settings", R.string.px_error_load_settings) { ProxyManager.settings() }?.let {
            savedMode = it.mode
            // Values away from what the phone recommends belong on the page that has
            // them, but the entry to it says how many there are.
            corePref.summary = if (it.deviations > 0) {
                resources.getQuantityString(R.plurals.px_risk_note, it.deviations, it.deviations)
            } else {
                getString(R.string.px_core_summary)
            }
            showModes()
        }
    }

    private suspend fun poll() {
        val newInfo = attempt("info", R.string.error_load_status) { ProxyClient.info() }
        info = newInfo
        if (newInfo?.controllerUp == true) {
            configs = attempt("configs", R.string.error_load_configs) { Mihomo.configs() }
            globalNode = if (savedMode == Mihomo.MODE_GLOBAL || configs?.mode == Mihomo.MODE_GLOBAL) {
                orNull("global node") { Mihomo.proxy(GLOBAL).now }
            } else {
                null
            }
        } else {
            configs = null
            globalNode = null
            clearError("configs")
            clearError("traffic")
        }
        showInfo()
    }

    /**
     * The status row's live speed. A stream silent for longer than the bounds
     * ([Mihomo.TRAFFIC_FIRST_SAMPLE_MS], [Mihomo.TRAFFIC_SAMPLE_GAP_MS]) is
     * closed, its stale figures leave the row, and a new one opens at once.
     */
    private suspend fun collectTraffic() {
        var stalls = 0
        while (true) {
            try {
                Mihomo.traffic().collectWithin(Mihomo.TRAFFIC_FIRST_SAMPLE_MS, Mihomo.TRAFFIC_SAMPLE_GAP_MS) {
                    stalls = 0
                    clearError("traffic")
                    traffic = it
                    showStatus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamStalledException) {
                // Logged once per silence, not per new stream.
                if (stalls++ == 0) Log.w(TAG, "ProxyFragment: traffic stream: ${e.message}; opening a new one")
                currentCoroutineContext().ensureActive()
                traffic = null
                showStatus()
                continue
            } catch (e: Exception) {
                setError("traffic", R.string.error_traffic, e)
                currentCoroutineContext().ensureActive()
                traffic = null
                showStatus()
            }
            delay(RETRY_MS)
        }
    }

    // ---- rendering ----

    private fun showInfo() {
        val info = info
        if (info == null) {
            mainSwitch.isEnabled = false
        } else if (!switchBusy) {
            mainSwitch.isChecked = info.mihomoEnabled
            mainSwitch.isEnabled = true
        }
        val up = info?.controllerUp == true
        for ((pref, summary) in runningPrefs) {
            pref.isEnabled = up
            pref.summary = getString(if (up) summary else R.string.px_needs_running)
        }
        showStatus()
        showModes()
    }

    private fun showStatus() {
        val context = context ?: return
        val info = info
        statusPref.summary = when {
            info == null -> getString(R.string.status_unknown)
            // A safe boot set in recovery keeps the core from starting.
            !info.mihomoEnabled -> getString(R.string.px_status_off)
            !info.controllerUp -> ProxyFormat.stateLabel(context, info.mihomoState)
            else -> {
                val t = traffic
                ProxyFormat.join(
                    context,
                    listOf(
                        ProxyFormat.stateLabel(context, info.mihomoState),
                        ProxyFormat.modeLabel(context, configs?.mode),
                        t?.let {
                            Format.upDown(context, Format.speed(context, it.up), Format.speed(context, it.down))
                        },
                    ),
                )
            }
        }
    }

    /**
     * The radios show what the core runs while it is up, else the saved mode
     * (every start uses it). A running mode other than the saved one (changed
     * elsewhere, or a save that failed) gets a note that applies the saved one.
     */
    private fun showModes() {
        val context = context ?: return
        val running = configs?.mode?.takeIf { it.isNotEmpty() && info?.controllerUp == true }
        val shown = running ?: savedMode
        for ((mode, pref) in modePrefs) {
            pref.isChecked = shown == mode
            // Persisted, so a mode can be chosen while the proxy is off.
            pref.isEnabled = !modeBusy && info != null && savedMode != null
        }
        globalNodePref.isVisible = shown == Mihomo.MODE_GLOBAL
        globalNodePref.isEnabled = info?.controllerUp == true
        globalNodePref.summary = when {
            info?.controllerUp != true -> getString(R.string.px_needs_running)
            globalNode != null -> RulesFragment.targetLabel(context, globalNode!!)
            else -> getString(R.string.px_mode_global_node_choose)
        }
        val saved = savedMode
        modeNotePref.isVisible = running != null && saved != null && running != saved && !modeBusy
        if (modeNotePref.isVisible) {
            modeNotePref.title = getString(
                R.string.px_mode_differs,
                ProxyFormat.modeLabel(context, saved) ?: saved ?: "",
                ProxyFormat.modeLabel(context, running) ?: running ?: "",
            )
            modeNotePref.setSummary(R.string.px_mode_differs_action)
        }
    }

    /** The core runs another mode than the saved one: set the saved one on it. */
    private fun applySavedMode(mode: String) {
        if (modeBusy) return
        modeBusy = true
        showModes()
        launchUi {
            if (proxyAction(requireContext(), R.string.px_error_mode) { Mihomo.setMode(mode) }) {
                configs = configs?.copy(mode = mode)
                ProxySummary.changed(requireContext())
            }
            modeBusy = false
            showModes()
            refresh()
        }
    }

    private fun showTask() {
        val context = context ?: return
        val task = ProxyTasks.running() ?: lastTaskKey?.let { ProxyTasks[it] }
        taskPref.isVisible = task != null
        if (task == null) return
        lastTaskKey = task.key
        taskPref.title = task.title
        taskPref.summary = when (task.state) {
            ProxyTasks.State.RUNNING -> task.step ?: getString(R.string.px_task_running)
            ProxyTasks.State.DONE -> task.summary ?: getString(R.string.px_task_done_at, Format.time(context, task.finishedAt))
            ProxyTasks.State.FAILED -> getString(R.string.px_task_failed_tap)
        }
    }

    // ---- actions ----

    private fun toggleProxy(on: Boolean) {
        if (switchBusy) return
        switchBusy = true
        mainSwitch.isEnabled = false
        launchUi {
            if (act(if (on) R.string.error_proxy_on else R.string.error_proxy_off) { ProxyClient.setEnabled(on) }) {
                mainSwitch.isChecked = on
                ProxySummary.changed(requireContext())
            }
            switchBusy = false
            mainSwitch.isEnabled = true
            refresh()
        }
    }

    /** Saves the mode (it survives restarts and rebuilds) and applies it to the running core. */
    private fun setMode(mode: String) {
        val running = configs?.mode?.takeIf { info?.controllerUp == true }
        if (modeBusy || (savedMode == mode && (running == null || running == mode))) return
        modeBusy = true
        showModes()
        launchUi {
            if (proxyAction(requireContext(), R.string.px_error_mode) {
                    ProxyManager.setSetting("mode", mode)
                    savedMode = mode
                    if (info?.controllerUp == true) Mihomo.setMode(mode)
                }
            ) {
                configs = configs?.copy(mode = mode)
                ProxySummary.changed(requireContext())
            }
            modeBusy = false
            showModes()
            refresh()
        }
    }

    companion object {
        /**
         * Rows that Settings search scrolls into view: Use proxy, Status, Mode, Repair after
         * network changes and Change the control key (also the task key of the change).
         */
        const val KEY_ENABLED = "lujarch_proxy_enabled"
        const val KEY_STATUS = "state"
        const val KEY_MODE = "mode"
        const val KEY_REPAIR = "lujarch_proxy_repair"
        const val KEY_SECRET = "secret"

        private const val POLL_MS = 3000L
        private const val RETRY_MS = 3000L
        const val GLOBAL = "GLOBAL"
    }
}
