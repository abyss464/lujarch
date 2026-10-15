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
import android.content.pm.PackageManager
import android.icu.text.ListFormatter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.AppHeaderPreference
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.OutputPreference
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR

/**
 * The route of one app: follow the rules, direct, a proxy group, reject, or
 * bypass the proxy (TUN exclude). A choice is saved and applied to the
 * running core at once ([AppRouteTasks]); the page shows the progress and,
 * on failure, the error and the manager's output.
 */
class AppRouteFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private lateinit var label: String

    private lateinit var headerPref: AppHeaderPreference
    private lateinit var statusPref: Preference
    private lateinit var disabledPref: Preference
    private lateinit var includePref: Preference
    private lateinit var sharedPref: Preference
    private lateinit var followPref: SelectorWithWidgetPreference
    private lateinit var directPref: SelectorWithWidgetPreference
    private lateinit var groupPref: SelectorWithWidgetPreference
    private lateinit var rejectPref: SelectorWithWidgetPreference
    private lateinit var bypassPref: SelectorWithWidgetPreference
    private lateinit var outputCategory: PreferenceCategory
    private lateinit var outputPref: OutputPreference

    private var routing: AppRouting? = null
    private var loadJob: Job? = null
    /** Labels of the packages the note on a shared uid names, by package. */
    private val memberLabels = HashMap<String, String>()

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val args = requireArguments()
        packageName = args.getString(ARG_PACKAGE) ?: ""
        label = args.getString(ARG_LABEL) ?: packageName

        headerPref = screen.add(AppHeaderPreference(prefContext)) {
            key = "header"
            title = label
            summary = packageName
            isCopyingEnabled = true
        }
        val status = screen.add(PreferenceCategory(prefContext)) { key = "status" }
        statusPref = status.add(Preference(prefContext)) {
            key = "run"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                if (AppRouteTasks.pendingBuild.value && !AppRouteTasks.rebuild(packageName, label)) {
                    Toast.makeText(requireContext(), R.string.error_busy, Toast.LENGTH_LONG).show()
                }
                true
            }
        }
        // A rule in a turned-off set: the Rules page turns the set on.
        disabledPref = status.add(Preference(prefContext)) {
            key = "disabled_set"
            setIcon(R.drawable.ic_info)
            isVisible = false
            setOnPreferenceClickListener {
                Pages.open(requireContext(), RulesFragment::class.java, R.string.px_rules_title)
                true
            }
        }
        includePref = status.add(Preference(prefContext)) {
            key = "tun_include"
            setIcon(PagesR.drawable.ic_apps)
            isVisible = false
            setOnPreferenceClickListener {
                val included = routing?.tunInclude?.contains(packageName) == true
                if (!AppRouteTasks.apply(packageName, label, RouteChoice.Include(!included))) {
                    Toast.makeText(requireContext(), R.string.error_busy, Toast.LENGTH_LONG).show()
                }
                render()
                true
            }
        }

        // An app that shares its uid: one route for all apps of the uid.
        sharedPref = status.add(Preference(prefContext)) {
            key = "shared_uid"
            setIcon(R.drawable.ic_info)
            setTitle(R.string.px_route_shared_uid_title)
            isSelectable = false
            isVisible = false
        }

        val routes = screen.add(PreferenceCategory(prefContext)) {
            key = "routes"
            setTitle(R.string.route_category)
        }
        followPref = addChoice(routes, "follow", R.string.route_follow_rules, R.string.route_follow_rules_summary) {
            choose(RouteChoice.FollowRules)
        }
        directPref = addChoice(routes, "direct", R.string.route_direct, R.string.route_direct_summary) {
            choose(RouteChoice.Target(RouteTarget.DIRECT))
        }
        // One affordance: the row opens the group picker (no extra widget).
        groupPref = addChoice(routes, "group", R.string.route_group, R.string.route_group_choose) {
            RouteTargetFragment.open(requireContext(), packageName, label)
        }
        rejectPref = addChoice(routes, "reject", R.string.route_reject, R.string.route_reject_summary) {
            choose(RouteChoice.Target(RouteTarget.REJECT))
        }
        bypassPref = addChoice(routes, "bypass", R.string.route_bypass, R.string.px_route_bypass_summary) {
            choose(RouteChoice.Bypass)
        }

        outputCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "output_category"
            setTitle(R.string.manager_output)
            isVisible = false
        }
        outputPref = outputCategory.add(OutputPreference(prefContext)) { key = "output" }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_route_footer)
        }
        render()
    }

    private fun addChoice(
        group: PreferenceCategory,
        key: String,
        title: Int,
        summary: Int,
        onClick: () -> Unit,
    ): SelectorWithWidgetPreference = group.add(SelectorWithWidgetPreference(prefContext)) {
        this.key = key
        setTitle(title)
        setSummary(summary)
        isPersistent = false
        isEnabled = false
        setOnClickListener { onClick() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadIcon()
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(AppRouteTasks.run, AppRouteTasks.pendingBuild) { run, pending -> run to pending }
                        .collect { render() }
                }
                launch {
                    AppRouteTasks.routing.collect { state ->
                        if (state != null) {
                            routing = state
                            render()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = launchUi {
            attempt("routes", R.string.error_load_routes) { AppRouteTasks.load() }?.let {
                routing = it
                render()
            }
        }
    }

    private fun loadIcon() {
        val pm = requireContext().packageManager
        launchUi {
            var installed = true
            val icon = withContext(Dispatchers.IO) {
                try {
                    pm.getApplicationIcon(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    // Not installed for this user; its rules can still be removed.
                    installed = false
                    null
                } catch (e: RuntimeException) {
                    Log.w(TAG, "icon of $packageName failed to load", e)
                    pm.defaultActivityIcon
                }
            }
            if (icon != null) headerPref.icon = icon
            if (!installed) headerPref.setSecondSummary(R.string.routing_not_installed)
        }
    }

    // ---- rendering ----

    private fun render() {
        val context = context ?: return
        val state = routing
        val route = state?.route(packageName)
        val run = AppRouteTasks.run.value?.takeIf { it.packageName == packageName || it.choice == null }
        val pending = AppRouteTasks.pendingBuild.value
        val busy = AppRouteTasks.running()
        val runningHere = run?.running == true && run.choice != null

        // While a change of this app runs, its choice is shown as selected.
        val shownChoice: RouteChoice? = if (runningHere && run?.choice !is RouteChoice.Include) {
            run?.choice
        } else {
            route?.let { choiceOf(it) }
        }
        followPref.isChecked = shownChoice == RouteChoice.FollowRules
        directPref.isChecked = shownChoice == RouteChoice.Target(RouteTarget.DIRECT)
        rejectPref.isChecked = shownChoice == RouteChoice.Target(RouteTarget.REJECT)
        bypassPref.isChecked = shownChoice == RouteChoice.Bypass
        groupPref.isChecked = shownChoice is RouteChoice.Target &&
            shownChoice.target != RouteTarget.DIRECT && shownChoice.target != RouteTarget.REJECT
        groupPref.summary = when {
            route is AppRoute.OwnRule && groupPref.isChecked && !runningHere -> route.rule.group ?: route.rule.target
            else -> getString(R.string.route_group_choose)
        }
        val enabled = state != null && !busy
        for (pref in listOf(followPref, directPref, groupPref, rejectPref, bypassPref)) pref.isEnabled = enabled

        headerPref.summary = if (route != null) {
            getString(R.string.route_header_summary, packageName, appRouteLabel(context, route))
        } else {
            packageName
        }

        // A rule in a disabled set does nothing; say so instead of hiding it.
        val disabled = state?.rulesOf(packageName)?.filter { !it.enabled }.orEmpty()
        disabledPref.isVisible = disabled.isNotEmpty()
        if (disabled.isNotEmpty()) {
            disabledPref.title = getString(R.string.route_disabled_set, disabled.first().set)
            disabledPref.summary = getString(R.string.px_route_disabled_set_summary)
        }
        showShared(state)
        // The include list: while it has apps, only they use the proxy.
        val include = state?.tunInclude.orEmpty()
        includePref.isVisible = include.isNotEmpty()
        includePref.isEnabled = !busy
        if (include.isNotEmpty()) {
            val included = packageName in include
            includePref.setTitle(if (included) R.string.px_route_included else R.string.px_route_not_included)
            includePref.setSummary(if (included) R.string.px_route_included_summary else R.string.px_route_not_included_summary)
        }

        val status = appRouteRunStatus(context, run, pending)
        val doneAt = run?.takeIf { it.step == AppRouteTasks.Step.DONE && it.choice != null && !pending }?.finishedAt
        statusPref.isVisible = status != null || doneAt != null
        statusPref.isSelectable = !busy && pending
        if (doneAt != null) {
            statusPref.setTitle(R.string.routing_applied)
            statusPref.summary = getString(R.string.routing_applied_at, Format.time(context, doneAt))
        } else if (status != null) {
            statusPref.title = status.first
            statusPref.summary = status.second
        }

        val output = run?.takeIf { it.step == AppRouteTasks.Step.FAILED }?.output
        outputCategory.isVisible = output != null
        if (output != null) outputPref.text = Format.stripAnsi(output).trimEnd().ifEmpty { getString(R.string.manager_no_output) }
    }

    /** The note on a shared uid: which apps share this app's route, and the name it is saved under. */
    private fun showShared(state: AppRouting?) {
        val users = state?.sharedUsers
        val user = users?.sharedUser(packageName)
        sharedPref.isVisible = user != null
        if (users == null || user == null) return
        val members = users.members(packageName)
        sharedPref.summary = if (members.size > 1) {
            val missing = members.filter { it !in memberLabels }
            if (missing.isNotEmpty()) loadLabels(missing)
            val labels = members.map { memberLabels[it] ?: it }
            getString(R.string.px_route_shared_uid_summary_many, ListFormatter.getInstance().format(labels), user)
        } else {
            getString(R.string.px_route_shared_uid_summary_one, user)
        }
    }

    private fun loadLabels(packages: List<String>) {
        val pm = requireContext().packageManager
        packages.forEach { memberLabels[it] = it }
        launchUi {
            val labels = withContext(Dispatchers.IO) {
                packages.associateWith { pkg ->
                    try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        pkg
                    }
                }
            }
            memberLabels.putAll(labels)
            render()
        }
    }

    private fun choiceOf(route: AppRoute): RouteChoice? = when (route) {
        AppRoute.FollowRules -> RouteChoice.FollowRules
        AppRoute.Bypass -> RouteChoice.Bypass
        AppRoute.NotIncluded -> null
        is AppRoute.OwnRule -> RouteChoice.Target(route.rule.target)
    }

    // ---- actions ----

    private fun choose(choice: RouteChoice) {
        val route = routing?.route(packageName) ?: return
        if (choiceOf(route) == choice && !AppRouteTasks.pendingBuild.value) return
        if (!AppRouteTasks.apply(packageName, label, choice)) {
            Toast.makeText(requireContext(), R.string.error_busy, Toast.LENGTH_LONG).show()
        }
        render()
    }

    companion object {
        private const val ARG_PACKAGE = "package"
        private const val ARG_LABEL = "label"

        fun arguments(packageName: String, label: String) = Bundle().apply {
            putString(ARG_PACKAGE, packageName)
            putString(ARG_LABEL, label)
        }

        fun open(context: Context, packageName: String, label: String) {
            // A fixed page title as on every proxy page; the app heads the list.
            Pages.open(
                context,
                AppRouteFragment::class.java,
                R.string.routing_title,
                arguments = Bundle().apply {
                    putString(ARG_PACKAGE, packageName)
                    putString(ARG_LABEL, label)
                },
            )
        }
    }
}
