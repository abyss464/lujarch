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
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.AppPreference
import com.android.settingslib.widget.TopIntroPreference
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.lujarch.settings.pages.AppListFragment
import org.lujarch.settings.pages.Errors

/** Short description of an app's route, for lists and summaries. */
fun appRouteLabel(context: Context, route: AppRoute): String = when (route) {
    AppRoute.FollowRules -> context.getString(R.string.route_follow_rules_short)
    AppRoute.Bypass -> context.getString(R.string.route_bypass_short)
    AppRoute.NotIncluded -> context.getString(R.string.px_route_not_included_short)
    is AppRoute.OwnRule -> when (route.rule.target) {
        RouteTarget.DIRECT -> context.getString(R.string.route_direct_short)
        RouteTarget.REJECT -> context.getString(R.string.route_reject_short)
        else -> context.getString(R.string.route_group_short, route.rule.group ?: route.rule.target)
    }
}

/** Status line of the latest per-app route change, or null when there is nothing to show. */
fun appRouteRunStatus(context: Context, run: AppRouteTasks.Run?, pending: Boolean): Pair<String, String?>? {
    if (run == null) {
        return if (pending) context.getString(R.string.routing_pending) to context.getString(R.string.routing_pending_summary) else null
    }
    val title = if (run.choice == null) {
        context.getString(R.string.routing_rebuilding)
    } else {
        context.getString(R.string.routing_applying, run.label)
    }
    return when (run.step) {
        AppRouteTasks.Step.SAVING -> title to if (run.commands > 1) {
            context.getString(R.string.routing_step_saving_count, run.finishedCommands + 1, run.commands)
        } else {
            context.getString(R.string.routing_step_saving)
        }
        AppRouteTasks.Step.APPLYING -> title to context.getString(R.string.routing_step_applying)
        AppRouteTasks.Step.DONE -> if (pending) {
            context.getString(R.string.routing_pending) to context.getString(R.string.routing_pending_summary)
        } else {
            null
        }
        AppRouteTasks.Step.FAILED -> {
            val failed = when {
                run.choice == null -> context.getString(R.string.routing_failed_rebuild)
                run.failedStep == AppRouteTasks.Step.APPLYING -> context.getString(R.string.routing_failed_apply, run.label)
                else -> context.getString(R.string.routing_failed_save, run.label)
            }
            val error = run.error?.let { Errors.describe(context, it) } ?: ""
            failed to if (pending) context.getString(R.string.routing_failed_retry, error) else error
        }
    }
}

/**
 * Per-app routing: installed apps with their route; tapping one opens
 * [AppRouteFragment]. The routes live in mihomo-manager's rules (rule set
 * android-apps) and TUN lists, read with `app list --json`.
 */
class AppRoutingFragment : AppListFragment<AppPreference>() {
    private lateinit var statusPref: Preference

    private var routing: AppRouting? = null

    override val showSystemSummaryRes = R.string.routing_show_system_summary
    override val appsCategoryRes = R.string.routing_apps_category
    override val footerRes = R.string.px_routing_footer

    override fun buildHeader(screen: PreferenceScreen, savedInstanceState: Bundle?): PreferenceCategory {
        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.routing_intro)
        }
        val status = screen.add(PreferenceCategory(prefContext)) { key = "status" }
        statusPref = status.add(Preference(prefContext)) {
            key = "run"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                onStatusClick()
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
                        .collect { (run, pending) -> showRun(run, pending) }
                }
                launch {
                    // A change reloads the rules when it finishes.
                    AppRouteTasks.routing.collect { state ->
                        if (state != null) {
                            routing = state
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
        }
    }

    override fun extraPackages(): Collection<String> = routing?.packages ?: emptySet()

    override fun isPinned(app: AppEntry): Boolean = routing?.hasOwnEntry(app.packageName) == true

    override fun categoryTitle(): CharSequence {
        val state = routing ?: return getString(R.string.routing_apps_category)
        val count = apps.count { state.hasOwnEntry(it.packageName) }
        return resources.getQuantityString(R.plurals.routing_apps_category_count, count, count)
    }

    override fun createAppPref(app: AppEntry): AppPreference =
        AppPreference(prefContext).apply {
            key = "app:" + app.packageName
            isPersistent = false
            isIconSpaceReserved = true
            title = app.label
            setOnPreferenceClickListener {
                AppRouteFragment.open(requireContext(), app.packageName, app.label)
                true
            }
        }

    override fun bindAppPref(pref: AppPreference, app: AppEntry) {
        val context = requireContext()
        val run = AppRouteTasks.run.value
        val route = routing?.route(app.packageName)
        val parts = ArrayList<String>(3)
        parts.add(
            when {
                run != null && run.running && run.packageName == app.packageName -> getString(R.string.routing_applying_short)
                route == null -> getString(R.string.status_unknown)
                else -> appRouteLabel(context, route)
            }
        )
        // One route for every app of a shared uid: say so where it covers more than this one.
        if ((routing?.sharedUsers?.members(app.packageName)?.size ?: 0) > 1) {
            parts.add(getString(R.string.px_route_shared_uid_short))
        }
        if (app.info == null) parts.add(getString(R.string.routing_not_installed))
        pref.summary = parts.joinToString(getString(R.string.separator))
        if (app.icon != null) pref.icon = app.icon
    }

    private fun showRun(run: AppRouteTasks.Run?, pending: Boolean) {
        val context = context ?: return
        val status = appRouteRunStatus(context, run, pending)
        statusPref.isVisible = status != null
        if (status != null) {
            statusPref.title = status.first
            statusPref.summary = status.second
            statusPref.isSelectable = run?.running != true
        }
        rebindApps()
    }

    private fun onStatusClick() {
        val run = AppRouteTasks.run.value
        if (AppRouteTasks.pendingBuild.value) {
            if (!AppRouteTasks.rebuild(run?.packageName ?: "", run?.label ?: "")) {
                Toast.makeText(requireContext(), R.string.error_busy, Toast.LENGTH_LONG).show()
            }
        } else if (run != null && run.packageName.isNotEmpty()) {
            AppRouteFragment.open(requireContext(), run.packageName, run.label)
        }
    }
}
