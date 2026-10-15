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
import android.widget.Toast
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SelectorWithWidgetPreference
import com.android.settingslib.widget.ZeroStatePreference
import kotlinx.coroutines.Job
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR

/**
 * Proxy groups an app can be routed to (`app targets --json`): custom groups,
 * countries, countries by line type and all nodes, shown by the group name
 * the running core uses. Choosing one saves and applies it and goes back to
 * the app's page, which shows the progress. The same picker chooses the
 * final rule's target (Rules), with direct and reject first.
 */
class RouteTargetFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private lateinit var label: String
    private lateinit var variantsPref: SwitchPreferenceCompat
    private lateinit var emptyPref: ZeroStatePreference
    private val categories = LinkedHashMap<String, PreferenceCategory>()
    private val targetPrefs = HashMap<String, SelectorWithWidgetPreference>()

    private var targets: List<RouteTarget>? = null
    private var current: String? = null
    /** Choosing the final rule's target instead of an app's route. */
    private var finalRule = false
    private var showVariants = false
    private var loadJob: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val args = requireArguments()
        packageName = args.getString(ARG_PACKAGE) ?: ""
        label = args.getString(ARG_LABEL) ?: packageName
        finalRule = args.getBoolean(ARG_FINAL)
        showVariants = savedInstanceState?.getBoolean(STATE_VARIANTS) ?: false

        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        variantsPref = header.add(SwitchPreferenceCompat(prefContext)) {
            key = "variants"
            setTitle(R.string.targets_variants)
            setSummary(R.string.targets_variants_summary)
            isPersistent = false
            isChecked = showVariants
            setOnPreferenceChangeListener { _, value ->
                showVariants = value as Boolean
                render()
                true
            }
        }
        for ((kind, title) in listOf(
            RouteTarget.KIND_BUILTIN to R.string.px_targets_builtin,
            RouteTarget.KIND_CUSTOM to R.string.targets_custom,
            RouteTarget.KIND_COUNTRY to R.string.targets_country,
            RouteTarget.KIND_CLASS to R.string.targets_class,
            RouteTarget.KIND_ALL to R.string.targets_all,
        )) {
            categories[kind] = screen.add(PreferenceCategory(prefContext)) {
                key = "kind:$kind"
                setTitle(title)
                isOrderingAsAdded = false
                isVisible = false
            }
        }
        emptyPref = screen.add(ZeroStatePreference(prefContext)) {
            key = "empty"
            setIcon(R.drawable.ic_groups)
            setTitle(PagesR.string.loading)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_VARIANTS, showVariants)
    }

    override fun onResume() {
        super.onResume()
        if (targets == null) refresh()
    }

    override fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = launchUi {
            current = if (finalRule) {
                requireArguments().getString(ARG_CURRENT)
            } else {
                val routing = attempt("routes", R.string.error_load_routes) { AppRouteTasks.load() }
                (routing?.route(packageName) as? AppRoute.OwnRule)?.rule?.target
            }
            val list = attempt("targets", R.string.error_load_targets) { AppRouteTasks.targets() } ?: run {
                emptyPref.setTitle(R.string.targets_unavailable)
                return@launchUi
            }
            // the proxy daemon refuses targets outside its character set; they cannot be chosen.
            // Direct and reject are choices of their own on the app's page, and part of this list for the final rule.
            targets = list.filter { (finalRule || it.kind != RouteTarget.KIND_BUILTIN) && AppRoutingClient.isUsableTarget(it.target) }
            // A current fallback or load-balance group is only visible with the variants.
            val cur = targets?.firstOrNull { it.target == current }
            if (cur != null && cur.kind != RouteTarget.KIND_BUILTIN && !cur.isPrimary && !showVariants) {
                showVariants = true
                variantsPref.isChecked = true
            }
            render()
        }
    }

    private fun render() {
        val context = context ?: return
        val all = targets ?: return
        val visible = all.filter { showVariants || it.isPrimary || it.kind == RouteTarget.KIND_BUILTIN }
        val keep = visible.map { it.target }.toHashSet()
        targetPrefs.keys.filter { it !in keep }.forEach { target ->
            targetPrefs.remove(target)?.let { pref -> categories.values.forEach { it.removePreference(pref) } }
        }
        visible.forEachIndexed { index, t ->
            val category = categories[t.kind] ?: return@forEachIndexed
            val pref = targetPrefs.getOrPut(t.target) {
                category.add(SelectorWithWidgetPreference(prefContext)) {
                    key = "target:" + t.target
                    title = if (t.kind == RouteTarget.KIND_BUILTIN) RulesFragment.targetLabel(context, t.target) else t.group
                    isPersistent = false
                    setOnClickListener { choose(t) }
                }
            }
            pref.order = index
            pref.isChecked = t.target == current
            pref.isEnabled = if (finalRule) ProxyTasks.running() == null else !AppRouteTasks.running()
            val parts = ArrayList<String>(3)
            t.type?.let { parts.add(groupTypeLabel(context, it)) }
            // Nodes and groups apart (a custom group's last resort is a group); a manager
            // without the counts gives the members only.
            (t.nodes ?: t.proxies)?.let { parts.add(resources.getQuantityString(R.plurals.group_nodes_count, it, it)) }
            t.memberGroups?.takeIf { it > 0 }?.let { parts.add(resources.getQuantityString(R.plurals.px_groups, it, it)) }
            pref.summary = parts.joinToString(getString(R.string.separator))
        }
        for ((kind, category) in categories) category.isVisible = visible.any { it.kind == kind }
        emptyPref.setTitle(R.string.targets_empty)
        emptyPref.isVisible = visible.isEmpty()
    }

    private fun choose(target: RouteTarget) {
        if (finalRule) {
            chooseFinal(target)
            return
        }
        if (target.target == current && !AppRouteTasks.pendingBuild.value) {
            requireActivity().finish()
            return
        }
        if (!AppRouteTasks.apply(packageName, label, RouteChoice.Target(target.target))) {
            Toast.makeText(requireContext(), R.string.error_busy, Toast.LENGTH_LONG).show()
            return
        }
        // The app's page shows the progress.
        requireActivity().finish()
    }

    /** Saves the final rule's target and applies the configuration; the Rules page shows the progress. */
    private fun chooseFinal(target: RouteTarget) {
        if (target.target == current) {
            requireActivity().finish()
            return
        }
        val context = requireContext()
        val shown = if (target.kind == RouteTarget.KIND_BUILTIN) RulesFragment.targetLabel(context, target.target) else target.group
        val started = ProxyTasks.start(context, RulesFragment.KEY, getString(R.string.px_rules_final_setting, shown)) {
            step(this.context.getString(R.string.px_step_saving))
            ProxyManager.setDefaultTarget(target.target)
            applyConfig()
            summary(this.context.getString(R.string.px_rules_final_set, shown))
        }
        if (!started) {
            Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
            return
        }
        requireActivity().finish()
    }

    companion object {
        private const val ARG_PACKAGE = "package"
        private const val ARG_LABEL = "label"
        private const val ARG_FINAL = "final_rule"
        private const val ARG_CURRENT = "current"
        private const val STATE_VARIANTS = "variants"

        /** Picker for the target of the final (MATCH) rule. */
        fun openForFinalRule(context: Context, current: String?) {
            Pages.open(
                context,
                RouteTargetFragment::class.java,
                R.string.px_rules_final,
                arguments = Bundle().apply {
                    putBoolean(ARG_FINAL, true)
                    putString(ARG_CURRENT, current)
                },
            )
        }

        fun open(context: Context, packageName: String, label: String) {
            Pages.open(
                context,
                RouteTargetFragment::class.java,
                R.string.route_group,
                arguments = Bundle().apply {
                    putString(ARG_PACKAGE, packageName)
                    putString(ARG_LABEL, label)
                },
            )
        }

        /** Localized group type of `app targets --json`. */
        fun groupTypeLabel(context: Context, type: String): String = when (type) {
            "url-test" -> context.getString(R.string.group_type_urltest)
            "fallback" -> context.getString(R.string.group_type_fallback)
            "load-balance" -> context.getString(R.string.group_type_loadbalance)
            RouteTarget.TYPE_ADAPTIVE -> context.getString(R.string.group_type_adaptive)
            else -> type
        }
    }
}
