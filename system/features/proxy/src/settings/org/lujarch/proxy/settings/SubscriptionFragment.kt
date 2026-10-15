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
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.UsageProgressBarPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * One subscription or manual node list: nodes, update time, usage and
 * expiry; update it now, test its nodes' delay (all or one), replace its URL
 * or nodes, remove it. Changes apply the configuration at once and show
 * their progress in the status row.
 */
class SubscriptionFragment : BasePreferenceFragment(), ManualNodesPage {
    private lateinit var name: String
    private lateinit var headerPref: Preference
    private lateinit var taskPref: Preference
    private lateinit var usagePref: UsageProgressBarPreference
    private lateinit var refreshPref: Preference
    private lateinit var urlPref: Preference
    private lateinit var nodesFilePref: Preference
    private lateinit var testPref: Preference
    private lateinit var locationsPref: Preference
    private lateinit var nodesCategory: PreferenceCategory
    private val nodePrefs = HashMap<String, Preference>()

    private var provider: ProviderInfo? = null
    private var nodes: List<String> = emptyList()
    private val delays = HashMap<String, Int>()
    private val testingNodes = HashSet<String>()
    private var testing = false
    private var testedAt = 0L
    private var coreUp = false
    private var removing = false
    private var loadJob: Job? = null
    override val manualNodes = ManualNodes(this) { showTask() }

    private val key: String
        get() = KEY_PREFIX + name

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        name = requireArguments().getString(ARG_NAME) ?: ""
        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        headerPref = header.add(Preference(prefContext)) {
            key = "name"
            title = name
            setIcon(R.drawable.ic_subscriptions)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        taskPref = header.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                TaskFragment.open(requireContext(), this@SubscriptionFragment.key)
                true
            }
        }
        usagePref = header.add(UsageProgressBarPreference(prefContext)) {
            key = "usage"
            isVisible = false
        }

        val actions = screen.add(PreferenceCategory(prefContext)) { key = "actions" }
        refreshPref = actions.add(Preference(prefContext)) {
            key = "refresh"
            setTitle(R.string.px_sub_refresh)
            setSummary(R.string.px_sub_refresh_summary)
            setIcon(R.drawable.ic_refresh)
            isVisible = false
            setOnPreferenceClickListener {
                askRefresh()
                true
            }
        }
        testPref = actions.add(Preference(prefContext)) {
            key = "test"
            setTitle(R.string.px_sub_test)
            setSummary(R.string.px_sub_test_summary)
            setIcon(R.drawable.ic_timer)
            setOnPreferenceClickListener {
                testAll()
                true
            }
        }
        urlPref = actions.add(Preference(prefContext)) {
            key = "url"
            setTitle(R.string.px_sub_replace_url)
            setSummary(R.string.px_sub_replace_url_summary)
            setIcon(R.drawable.ic_px_edit)
            isVisible = false
            setOnPreferenceClickListener {
                askUrl()
                true
            }
        }
        nodesFilePref = actions.add(Preference(prefContext)) {
            key = "nodes_file"
            setTitle(R.string.px_manual_replace)
            setSummary(R.string.px_manual_replace_summary)
            setIcon(R.drawable.ic_px_import)
            isVisible = false
            setOnPreferenceClickListener {
                manualNodes.replace(name)
                true
            }
        }
        locationsPref = actions.add(Preference(prefContext)) {
            key = "locations"
            setTitle(R.string.px_loc_title)
            setIcon(R.drawable.ic_groups)
            isVisible = false
            setOnPreferenceClickListener {
                NodeLocationsFragment.open(requireContext(), name)
                true
            }
        }
        actions.add(Preference(prefContext)) {
            key = "remove"
            setTitle(R.string.px_sub_remove)
            setIcon(R.drawable.ic_px_delete)
            setOnPreferenceClickListener {
                askRemove()
                true
            }
        }
        nodesCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "nodes"
            setTitle(R.string.px_sub_nodes)
            isOrderingAsAdded = false
            isVisible = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wasRunning = false
                ProxyTasks.tasks.collect {
                    showTask()
                    val task = ProxyTasks[key]
                    val running = task?.running == true
                    if (wasRunning && !running) {
                        // A removed subscription has no page any more.
                        if (removing && task?.state == ProxyTasks.State.DONE) {
                            activity?.finish()
                            return@collect
                        }
                        removing = false
                        refresh()
                    }
                    wasRunning = running
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
            val status = attempt("status", R.string.px_error_load_subs) { ProxyManager.status(offline = true) }
            provider = status?.providers?.firstOrNull { it.name == name }
            render()
            if (status != null && provider == null) return@launchUi
            nodes = attempt("nodes", R.string.px_error_load_nodes) { ProxyManager.providerNodes(name) }.orEmpty()
            attempt("locations", R.string.px_error_load_locations) { ProxyCommands.locations() }?.let { l ->
                showLocations(l.nodes.filter { it.provider == name })
            }
            coreUp = orNull("core state") { ProxyClient.info().controllerUp } ?: false
            render()
            if (provider?.manual == false) {
                attempt("usage", R.string.px_error_load_usage) { ProxyManager.status(offline = false) }
                    ?.providers?.firstOrNull { it.name == name }?.let {
                        provider = it
                        render()
                    }
            }
        }
    }

    /** "3 of 40 nodes have no location" (they are only in GLOBAL), or all located. */
    private fun showLocations(own: List<NodeLocation>) {
        if (own.isEmpty()) return
        val missing = own.count { !it.located }
        locationsPref.isVisible = true
        locationsPref.summary = if (missing == 0) {
            resources.getQuantityString(R.plurals.px_loc_all_located, own.size, own.size)
        } else {
            resources.getQuantityString(R.plurals.px_loc_some_missing, missing, missing, own.size)
        }
        locationsPref.setIcon(if (missing > 0) R.drawable.ic_error else R.drawable.ic_groups)
    }

    private fun render() {
        val context = context ?: return
        val p = provider
        if (p == null) {
            headerPref.setSummary(R.string.px_sub_missing)
            return
        }
        headerPref.summary = ProxyFormat.join(
            context,
            listOf(
                if (p.manual) getString(R.string.px_sub_manual) else p.host,
                p.nodes?.let { resources.getQuantityString(R.plurals.px_nodes, it, it) } ?: getString(R.string.px_sub_not_fetched),
                p.fetchedAt?.let { getString(R.string.px_sub_updated, ProxyFormat.ago(requireContext(), it)) },
            ),
        )
        refreshPref.isVisible = !p.manual
        urlPref.isVisible = !p.manual
        nodesFilePref.isVisible = p.manual
        val u = p.usage
        usagePref.isVisible = u != null && u.total > 0
        if (u != null && u.total > 0) {
            usagePref.setUsageSummary(ProxyFormat.quota(context, u.used))
            usagePref.setTotalSummary(getString(R.string.px_sub_usage_total, ProxyFormat.quota(context, u.total)))
            usagePref.setPercent(u.used.coerceAtMost(u.total), u.total)
            usagePref.setBottomSummary(
                ProxyFormat.join(
                    context,
                    listOf(
                        getString(R.string.px_sub_remaining, ProxyFormat.quota(context, (u.total - u.used).coerceAtLeast(0))),
                        u.expireAt?.let { ProxyFormat.expiry(context, it) },
                    ),
                )
            )
        }
        val running = ProxyTasks.running() != null
        refreshPref.isEnabled = !running
        urlPref.isEnabled = !running
        nodesFilePref.isEnabled = !running
        testPref.isEnabled = coreUp && !testing && nodes.isNotEmpty()
        testPref.summary = when {
            !coreUp -> getString(R.string.px_needs_running)
            testing -> getString(R.string.px_group_testing)
            testedAt > 0 -> getString(R.string.px_group_tested_at, Format.time(context, testedAt))
            else -> getString(R.string.px_sub_test_summary)
        }
        renderNodes()
    }

    private fun renderNodes() {
        val context = context ?: return
        val keep = nodes.toHashSet()
        nodePrefs.keys.filter { it !in keep }.forEach { n -> nodePrefs.remove(n)?.let { nodesCategory.removePreference(it) } }
        nodes.forEachIndexed { index, node ->
            val pref = nodePrefs.getOrPut(node) {
                nodesCategory.add(Preference(prefContext)) {
                    key = "node:$node"
                    title = node
                    setOnPreferenceClickListener {
                        testOne(node)
                        true
                    }
                }
            }
            pref.order = index
            pref.isEnabled = coreUp
            pref.summary = when {
                node in testingNodes -> getString(R.string.px_group_testing)
                else -> ProxyFormat.delay(context, delays[node], tested = node in delays)
            }
        }
        nodesCategory.title = resources.getQuantityString(R.plurals.px_sub_nodes_count, nodes.size, nodes.size)
        nodesCategory.isVisible = nodes.isNotEmpty()
    }

    private fun testAll() {
        if (testing) return
        testing = true
        render()
        launchUi {
            try {
                act(R.string.error_delay) {
                    // A few at a time: every test opens a connection through the node.
                    val permits = Semaphore(PARALLEL_TESTS)
                    coroutineScope {
                        nodes.map { node ->
                            async {
                                permits.withPermit {
                                    delays[node] = Mihomo.proxyDelay(node)
                                    renderNodes()
                                }
                            }
                        }.awaitAll()
                    }
                }
                testedAt = System.currentTimeMillis()
            } finally {
                testing = false
                ifPageAlive { render() }
            }
        }
    }

    private fun testOne(node: String) {
        if (!testingNodes.add(node)) return
        renderNodes()
        launchUi {
            try {
                act(R.string.error_delay) { delays[node] = Mihomo.proxyDelay(node) }
            } finally {
                testingNodes.remove(node)
                ifPageAlive { renderNodes() }
            }
        }
    }

    private fun askRefresh() = confirm(
        "px-sub-refresh", R.string.px_sub_refresh, { getString(R.string.px_sub_refresh_confirm, name) }, R.string.px_action_refresh,
    ) {
        start(getString(R.string.px_sub_refreshing, name)) {
            step(context.getString(R.string.px_step_downloading))
            ProxyManager.refreshSubscription(name)
            locateStep()
            applyConfig()
            summary(context.getString(R.string.px_sub_refreshed))
        }
    }

    private fun askUrl() = askSubscription(this, name) { n, url, _ ->
        start(getString(R.string.px_sub_replacing, n)) {
            step(context.getString(R.string.px_step_downloading))
            ProxyManager.replaceSubscriptionUrl(n, url)
            locateStep()
            applyConfig()
            summary(context.getString(R.string.px_sub_url_replaced))
        }
    }

    private fun askRemove() {
        // The choice is the dialog's own state: a recreated window shows it.
        val purge = booleanArrayOf(true)
        showDialog("px-sub-remove") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(getString(R.string.px_sub_remove_title, name))
                .setMultiChoiceItems(arrayOf(getString(R.string.px_sub_remove_purge)), purge) { _, _, checked -> purge[0] = checked }
                .setPositiveButton(R.string.px_action_remove) { _, _ ->
                    removing = true
                    val withFiles = purge[0]
                    start(getString(R.string.px_sub_removing, name)) {
                        step(context.getString(R.string.px_step_saving))
                        ProxyManager.removeProvider(name, withFiles)
                        applyConfig()
                        summary(context.getString(R.string.px_sub_removed, name))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun start(title: String, @OutlivesPage work: suspend ProxyTasks.Scope.() -> Unit) {
        if (!ProxyTasks.start(requireContext(), key, title, work)) {
            Toast.makeText(requireContext(), R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
        }
        showTask()
    }

    private fun showTask() {
        val context = context ?: return
        val task = ProxyTasks[key]
        taskPref.isVisible = task != null
        if (task != null) {
            taskPref.title = task.title
            taskPref.summary = taskStatus(context, task)
        }
        render()
    }

    companion object {
        const val KEY_PREFIX = "provider:"
        private const val ARG_NAME = "name"
        private const val PARALLEL_TESTS = 8

        fun open(context: Context, name: String) {
            Pages.open(
                context,
                SubscriptionFragment::class.java,
                R.string.px_sub_page_title,
                arguments = Bundle().apply { putString(ARG_NAME, name) },
            )
        }
    }
}
