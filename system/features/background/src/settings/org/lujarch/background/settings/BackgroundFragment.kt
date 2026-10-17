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
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.app.LujarchBackground
import com.android.internal.app.LujarchBackgroundApp
import com.android.settingslib.widget.GroupSectionDividerMixin
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Pages

/**
 * Settings > System > Background (BG-2): the memory in use as four parts, every app with a
 * process grouped by depth (or sorted by memory, battery use or wakeups), each with its reason
 * and who caused it; Show system processes adds the system's own. A row opens the app's page;
 * the page has no other switches.
 */
class BackgroundFragment : BasePreferenceFragment() {
    private lateinit var memoryPref: MemoryPreference
    private lateinit var sortPref: SortPreference
    private lateinit var listPref: PreferenceCategory
    private lateinit var loadingPref: Preference
    private lateinit var systemPref: SwitchPreferenceCompat
    private var sort = SORT_DEPTH
    private var apps: List<LujarchBackgroundApp> = emptyList()
    private var loop: Job? = null
    private val icons = HashMap<String, android.graphics.drawable.Drawable?>()

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        sort = savedInstanceState?.getInt(STATE_SORT) ?: SORT_DEPTH
        memoryPref = screen.add(MemoryPreference(prefContext)) { key = "memory" }
        sortPref = screen.add(SortPreference(prefContext)) {
            key = "sort"
            selected = sort
            onSort = {
                sort = it
                render()
            }
        }
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
            setTitle(R.string.background_show_system)
            isPersistent = false
            isChecked = showSystem(prefContext)
            setOnPreferenceChangeListener { _, value ->
                Settings.Secure.putInt(prefContext.contentResolver, SETTING_SHOW_SYSTEM, if (value as Boolean) 1 else 0)
                render()
                true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SORT, sort)
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
        val memory = attempt("memory", R.string.background_error_load) {
            BackgroundClient.call { it.memory }
        }
        if (memory != null) memoryPref.memory = memory
        val list = attempt("apps", R.string.background_error_load) {
            BackgroundClient.call { it.getApps(user, true).toList() }
        } ?: return
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
        systemPref.summary = resources.getQuantityString(R.plurals.background_system_count,
            apps.count { it.system }, apps.count { it.system })
        listPref.removeAll()
        if (shown.isEmpty()) {
            listPref.addPreference(loadingPref.apply { setTitle(R.string.background_none) })
            return
        }
        when (sort) {
            SORT_DEPTH -> {
                for (depth in 0 until LujarchBackground.DEPTH_COUNT) {
                    val group = shown.filter { it.depth == depth }.sortedByDescending { it.memoryBytes }
                    if (group.isEmpty()) continue
                    listPref.addPreference(DepthHeaderPreference(context).apply {
                        key = "depth_$depth"
                        this.depth = depth
                        count = group.size
                        note = context.getString(DEPTH_NOTES[depth])
                    })
                    group.forEach { listPref.addPreference(row(context, it)) }
                }
            }
            SORT_MEMORY -> shown.sortedByDescending { it.memoryBytes }.forEach { listPref.addPreference(row(context, it)) }
            SORT_BATTERY -> shown.sortedByDescending { it.batteryMah }.forEach { listPref.addPreference(row(context, it)) }
            else -> shown.sortedByDescending { it.wakeups }.forEach { listPref.addPreference(row(context, it)) }
        }
    }

    private fun row(context: Context, a: LujarchBackgroundApp): AppRowPreference =
        AppRowPreference(context).apply {
            key = "app_${a.packageName}"
            title = a.label
            icon = icons[a.packageName]
            depth = a.depth
            smallWindow = a.smallWindow
            who = a.who
            whoName = a.whoName
            reason = a.reason
            value = when (sort) {
                SORT_BATTERY -> NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
                    .format(a.batteryPercent / 100)
                SORT_WAKEUPS -> resources.getQuantityString(R.plurals.background_times, a.wakeups, a.wakeups)
                else -> if (a.memoryBytes > 0) Formatter.formatShortFileSize(context, a.memoryBytes) else "—"
            }
            setOnPreferenceClickListener {
                AppBackgroundFragment.open(requireContext(), a.packageName, a.label)
                true
            }
        }

    /** By depth, memory, battery use or wakeups (BG-2), on the page itself rather than a card. */
    class SortPreference(context: Context) : Preference(context), GroupSectionDividerMixin {
        var selected = SORT_DEPTH
        var onSort: (Int) -> Unit = {}

        init {
            layoutResource = R.layout.background_sort
            isSelectable = false
            isPersistent = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val chips = holder.findViewById(R.id.background_sort) as LinearLayout
            chips.removeAllViews()
            SORTS.forEachIndexed { i, res ->
                chips.addView(TextView(context).apply {
                    setText(res)
                    isSelected = i == selected
                    setBackgroundResource(R.drawable.background_sort_chip)
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                    setTextColor(context.getColorStateList(R.color.background_sort_chip_text))
                    val h = dp(context, 14f)
                    setPadding(h, dp(context, 8f), h, dp(context, 8f))
                    isClickable = true
                    setOnClickListener {
                        selected = i
                        notifyChanged()
                        onSort(i)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(context, 8f)
                })
            }
            holder.isDividerAllowedAbove = false
            holder.isDividerAllowedBelow = false
        }
    }

    companion object {
        const val KEY_SHOW_SYSTEM = "show_system_processes"
        const val SETTING_SHOW_SYSTEM = "lujarch_background_show_system_processes"
        private const val STATE_SORT = "sort"
        private const val REFRESH_MS = 5_000L
        const val SORT_DEPTH = 0
        const val SORT_MEMORY = 1
        const val SORT_BATTERY = 2
        const val SORT_WAKEUPS = 3
        private val SORTS = intArrayOf(
            R.string.background_sort_depth,
            R.string.background_sort_memory,
            R.string.background_sort_battery,
            R.string.background_sort_wakeups,
        )
        private val DEPTH_NOTES = intArrayOf(
            R.string.background_note_on_screen,
            R.string.background_note_running,
            R.string.background_note_messages,
            R.string.background_note_paused,
            R.string.background_note_reclaimed,
            R.string.background_note_stopped,
        )

        fun showSystem(context: Context): Boolean =
            Settings.Secure.getInt(context.contentResolver, SETTING_SHOW_SYSTEM, 0) != 0
    }
}
