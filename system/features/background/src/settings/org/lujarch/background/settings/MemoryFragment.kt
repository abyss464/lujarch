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
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.text.format.Formatter
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.app.LujarchBackground
import com.android.internal.app.LujarchBackgroundApp
import com.android.internal.app.LujarchBackgroundProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.BasePreferenceFragment

/**
 * Settings > System > Memory (BG-5): the device's memory as four parts and the compressed swap,
 * then the apps with a process in groups: Running in background by memory with each one's
 * reason, Kept, Paused with each one's place in the order of reclaiming, and Lujarch's system
 * services; Show system processes adds the system's own in the same groups. An app's row opens
 * its memory page, a service's its process page. The page reads again every 5 seconds while it
 * is open.
 */
class MemoryFragment : BasePreferenceFragment() {
    private lateinit var memoryPref: MemoryPreference
    private lateinit var listPref: PreferenceCategory
    private lateinit var loadingPref: Preference
    private lateinit var systemPref: SwitchPreferenceCompat
    private var apps: List<LujarchBackgroundApp> = emptyList()
    private var services: List<LujarchBackgroundProcess> = emptyList()
    private var loop: Job? = null
    private val icons = HashMap<String, android.graphics.drawable.Drawable?>()

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        memoryPref = screen.add(MemoryPreference(prefContext)) { key = "memory" }
        listPref = screen.add(PreferenceCategory(prefContext)) {
            key = "apps"
            isOrderingAsAdded = true
        }
        loadingPref = listPref.add(Preference(prefContext)) {
            key = "loading"
            setTitle(org.lujarch.settings.pages.R.string.loading)
            isSelectable = false
        }
        systemPref = screen.add(SwitchPreferenceCompat(prefContext)) {
            key = KEY_SHOW_SYSTEM
            setTitle(R.string.memory_show_system)
            isPersistent = false
            isChecked = showSystem(prefContext)
            setOnPreferenceChangeListener { _, value ->
                Settings.Secure.putInt(prefContext.contentResolver, SETTING_SHOW_SYSTEM,
                    if (value as Boolean) 1 else 0)
                render()
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
        val memory = attempt("memory", R.string.memory_error_load) {
            BackgroundClient.call { it.memory }
        }
        if (memory != null) memoryPref.memory = memory
        val list = attempt("apps", R.string.memory_error_load) {
            BackgroundClient.call { it.getApps(user, true).toList() }
        } ?: return
        services = attempt("services", R.string.memory_error_load) {
            BackgroundClient.call { it.services.toList() }
        } ?: emptyList()
        val pm = requireContext().packageManager
        val missing = list.map { it.packageName }.filter { it !in icons }
        if (missing.isNotEmpty()) {
            val loaded = withContext(Dispatchers.IO) {
                missing.associateWith { pkg ->
                    try {
                        pm.getApplicationIcon(pkg)
                    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                        null
                    }
                }
            }
            icons.putAll(loaded)
        }
        apps = list
        render()
    }

    private fun render() {
        if (!pageAlive) return
        val context = prefContext
        val showSystem = showSystem(context)
        val shown = apps.filter { showSystem || !it.system }
        val systemCount = apps.count { it.system }
        systemPref.summary = resources.getQuantityString(R.plurals.memory_system_count,
            systemCount, systemCount)
        listPref.removeAll()
        val running = shown.filter { !it.kept && (it.state == LujarchBackground.RUN_BACKGROUND.toInt()
                || it.state == LujarchBackground.RUN_SMALL_WINDOW.toInt()) }
            .sortedByDescending { it.memoryBytes }
        val kept = shown.filter { it.kept && it.processCount > 0 }
            .sortedByDescending { it.memoryBytes }
        val paused = shown.filter { it.state == LujarchBackground.RUN_PAUSED.toInt() && !it.kept }
            .sortedBy { if (it.reclaimOrder > 0) it.reclaimOrder else Int.MAX_VALUE }
        if (running.isEmpty() && kept.isEmpty() && paused.isEmpty() && services.isEmpty()) {
            listPref.addPreference(loadingPref.apply { setTitle(R.string.memory_none) })
            return
        }
        if (running.isNotEmpty()) {
            header(context, "running", LujarchBackground.RUN_BACKGROUND.toInt(),
                LujarchBackground.stateName(context, LujarchBackground.RUN_BACKGROUND.toInt()), running.size,
                getString(R.string.memory_note_running))
            running.forEach { listPref.addPreference(row(context, it, it.reason)) }
        }
        if (kept.isNotEmpty()) {
            val bytes = kept.sumOf { it.memoryBytes }
            header(context, "kept", LujarchBackground.RUN_PAUSED.toInt(),
                LujarchBackground.memoryName(context, LujarchBackground.MEMORY_KEPT), kept.size,
                getString(R.string.memory_note_kept, Formatter.formatShortFileSize(context, bytes)))
            kept.forEach { a ->
                listPref.addPreference(row(context, a, processesState(context, a)))
            }
        }
        if (paused.isNotEmpty()) {
            val processes = paused.sumOf { it.processCount }
            header(context, "paused", LujarchBackground.RUN_PAUSED.toInt(),
                LujarchBackground.stateName(context, LujarchBackground.RUN_PAUSED.toInt()),
                paused.size, resources.getQuantityString(R.plurals.memory_note_paused, processes,
                    processes))
            paused.forEach { a ->
                val parts = mutableListOf<String>()
                if (a.processCount > 1) {
                    parts += resources.getQuantityString(R.plurals.memory_processes,
                        a.processCount, a.processCount)
                }
                if (a.reclaimOrder > 0) {
                    parts += getString(R.string.memory_reclaim_order, a.reclaimOrder)
                }
                listPref.addPreference(row(context, a,
                    parts.joinToString(getString(R.string.memory_separator)).ifEmpty { a.reason }))
            }
        }
        if (services.isNotEmpty()) {
            val bytes = services.sumOf { it.memoryBytes }
            header(context, "services", LujarchBackground.RUN_BACKGROUND.toInt(),
                getString(R.string.memory_services), services.size,
                getString(R.string.memory_note_kept, Formatter.formatShortFileSize(context, bytes)))
            services.forEach { listPref.addPreference(serviceRow(context, it)) }
        }
    }

    private fun header(context: Context, key: String, state: Int, name: CharSequence, count: Int,
            note: CharSequence) {
        listPref.addPreference(StateHeaderPreference(context).apply {
            this.key = "group_$key"
            this.state = state
            this.name = name
            this.count = count
            this.note = note
        })
    }

    /** "Paused · its main process and 2 others": what a kept app's processes are doing. */
    private fun processesState(context: Context, a: LujarchBackgroundApp): String {
        val state = LujarchBackground.stateName(context, a.state)
        return if (a.processCount > 1) {
            getString(R.string.memory_state_processes, state,
                resources.getQuantityString(R.plurals.memory_processes, a.processCount,
                    a.processCount))
        } else {
            state
        }
    }

    private fun row(context: Context, a: LujarchBackgroundApp, text: CharSequence?): AppRowPreference =
        AppRowPreference(context).apply {
            key = "app_${a.packageName}"
            title = a.label
            icon = icons[a.packageName]
            state = a.state
            who = a.who
            whoName = a.whoName
            reason = text
            value = if (a.memoryBytes > 0) Formatter.formatShortFileSize(context, a.memoryBytes)
                else "—"
            setOnPreferenceClickListener {
                AppMemoryFragment.open(requireContext(), a.packageName, a.label)
                true
            }
        }

    /** One of Lujarch's system services: its name, why it runs, its memory; opens its process. */
    private fun serviceRow(context: Context, p: LujarchBackgroundProcess): AppRowPreference =
        AppRowPreference(context).apply {
            key = "service_${p.pid}"
            title = p.name
            icon = context.getDrawable(R.drawable.ic_memory_service)
            state = p.state
            who = p.who
            whoName = p.whoName
            reason = p.reason
            value = if (p.memoryBytes > 0) Formatter.formatShortFileSize(context, p.memoryBytes)
                else "—"
            setOnPreferenceClickListener {
                ProcessFragment.open(requireContext(), p.pid, p.name, null, null)
                true
            }
        }

    companion object {
        const val KEY_SHOW_SYSTEM = "show_system_processes"
        const val SETTING_SHOW_SYSTEM = "lujarch_background_show_system_processes"
        private const val REFRESH_MS = 5_000L

        fun showSystem(context: Context): Boolean =
            Settings.Secure.getInt(context.contentResolver, SETTING_SHOW_SYSTEM, 0) != 0
    }
}
