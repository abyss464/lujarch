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

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.AppSwitchPreference
import com.android.settingslib.widget.TopIntroPreference
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.lujarch.settings.pages.AppListFragment
import org.lujarch.settings.pages.R as PagesR

/**
 * Which apps use the proxy: every app (the list is empty), or only the apps
 * on the list; every other app then connects directly, outside the proxy.
 * One switch per app; a change is saved and applied like a per-app route.
 */
class TunIncludeFragment : AppListFragment<AppSwitchPreference>() {
    private lateinit var statePref: Preference
    private lateinit var runPref: Preference
    private var routing: AppRouting? = null

    override val showSystemSummaryRes = R.string.px_include_show_system_summary
    override val appsCategoryRes = R.string.px_include_apps
    override val footerRes = R.string.px_include_footer

    override fun buildHeader(screen: PreferenceScreen, savedInstanceState: Bundle?): PreferenceCategory {
        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.px_include_intro)
        }
        val status = screen.add(PreferenceCategory(prefContext)) { key = "status" }
        statePref = status.add(Preference(prefContext)) {
            key = "state"
            setIcon(R.drawable.ic_info)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        runPref = status.add(Preference(prefContext)) {
            key = "run"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                val run = AppRouteTasks.run.value
                if (AppRouteTasks.pendingBuild.value && !AppRouteTasks.rebuild(run?.packageName ?: "", run?.label ?: "")) {
                    Toast.makeText(requireContext(), R.string.error_busy, Toast.LENGTH_LONG).show()
                }
                true
            }
        }
        return status
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(AppRouteTasks.run, AppRouteTasks.pendingBuild) { run, pending -> run to pending }
                        .collect { (run, pending) ->
                            val context = context ?: return@collect
                            val status = appRouteRunStatus(context, run, pending)
                            runPref.isVisible = status != null
                            if (status != null) {
                                runPref.title = status.first
                                runPref.summary = status.second
                                runPref.isSelectable = run?.running != true
                            }
                            rebindApps()
                        }
                }
                launch {
                    AppRouteTasks.routing.collect { state ->
                        if (state != null) {
                            routing = state
                            showState()
                            rebindApps()
                        }
                    }
                }
            }
        }
    }

    override suspend fun loadState() {
        attempt("routes", R.string.error_load_routes) { AppRouteTasks.load() }?.let {
            routing = it
            showState()
        }
    }

    private fun showState() {
        val include = routing?.tunInclude.orEmpty()
        statePref.title = if (include.isEmpty()) {
            getString(R.string.px_include_all)
        } else {
            resources.getQuantityString(R.plurals.px_include_only, include.size, include.size)
        }
        statePref.setSummary(if (include.isEmpty()) R.string.px_include_all_summary else R.string.px_include_only_summary)
    }

    override fun extraPackages(): Collection<String> = routing?.tunInclude.orEmpty()

    override fun isPinned(app: AppEntry): Boolean = routing?.tunInclude?.contains(app.packageName) == true

    override fun categoryTitle(): CharSequence {
        val count = routing?.tunInclude?.size ?: return getString(R.string.px_include_apps)
        return resources.getQuantityString(R.plurals.px_include_apps_count, count, count)
    }

    override fun createAppPref(app: AppEntry): AppSwitchPreference =
        AppSwitchPreference(prefContext).apply {
            key = "app:" + app.packageName
            isPersistent = false
            isIconSpaceReserved = true
            title = app.label
            setOnPreferenceChangeListener { _, value ->
                if (!AppRouteTasks.apply(app.packageName, app.label, RouteChoice.Include(value as Boolean))) {
                    Toast.makeText(requireContext(), R.string.error_busy, Toast.LENGTH_LONG).show()
                }
                rebindApps()
                false
            }
        }

    override fun bindAppPref(pref: AppSwitchPreference, app: AppEntry) {
        val run = AppRouteTasks.run.value
        val runningHere = run != null && run.running && run.packageName == app.packageName
        val included = routing?.tunInclude?.contains(app.packageName) == true
        val choice = run?.choice
        pref.isChecked = if (runningHere && choice is RouteChoice.Include) choice.on else included
        pref.isEnabled = routing != null && !AppRouteTasks.running()
        pref.summary = when {
            runningHere -> getString(R.string.routing_applying_short)
            app.info == null -> getString(R.string.routing_not_installed)
            routing?.tunExclude?.contains(app.packageName) == true -> getString(R.string.px_include_excluded)
            else -> app.packageName
        }
        if (app.icon != null) pref.icon = app.icon
    }
}
