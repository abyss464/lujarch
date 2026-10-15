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

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.ZeroStatePreference
import kotlinx.coroutines.Job
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog

/** A provider name the manager and the proxy daemon accept. */
fun isProviderName(name: String): Boolean =
    name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= 64 && !name.startsWith("-") &&
        !name.startsWith(".") && name.none { it == '/' || it == '\\' || it.isISOControl() }

/**
 * Asks for a subscription: name and URL (masked, kept out of autofill and
 * keyboard learning), or only the URL when [name] is given. The URL is
 * handed to [onDone] and from there only to the manager's stdin. A page
 * dialog of [page]: a recreated window keeps the name and asks for the URL
 * again (a secret field, never saved).
 */
fun <P : Fragment> askSubscription(
    page: P,
    name: String?,
    @RebuiltWithPage onDone: P.(name: String, url: String, fetch: Boolean) -> Unit,
) = page.showDialog("px-subscription") { d -> subscriptionDialog(this, d.context, name, onDone) }

private fun <P : Fragment> subscriptionDialog(
    page: P,
    context: Context,
    name: String?,
    onDone: P.(name: String, url: String, fetch: Boolean) -> Unit,
): Dialog {
    val view = LayoutInflater.from(context).inflate(R.layout.px_dialog_subscription, null)
    val nameInput = view.findViewById<EditText>(R.id.px_sub_name)
    val urlInput = view.findViewById<EditText>(R.id.px_sub_url)
    val fetch = view.findViewById<CheckBox>(R.id.px_sub_fetch)
    if (name != null) {
        nameInput.visibility = View.GONE
        fetch.visibility = View.GONE
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle(if (name == null) R.string.px_sub_add else R.string.px_sub_replace_url)
        .setMessage(if (name == null) R.string.px_sub_add_message else R.string.px_sub_replace_url_message)
        .setView(view)
        .setPositiveButton(if (name == null) R.string.px_action_add else R.string.px_action_replace, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name ?: nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            when {
                !isProviderName(n) -> nameInput.error = context.getString(R.string.px_sub_name_invalid)
                !(url.startsWith("https://") || url.startsWith("http://")) || url.any { it.isWhitespace() } ->
                    urlInput.error = context.getString(R.string.px_sub_url_invalid)
                else -> {
                    // Clear the field before the dialog goes away.
                    urlInput.text.clear()
                    dialog.dismiss()
                    page.onDone(n, url, fetch.isChecked)
                }
            }
        }
    }
    (if (name == null) nameInput else urlInput).requestFocus()
    return dialog
}

/**
 * Subscriptions and manual node lists, one row per provider: nodes, age,
 * usage and expiry in the summary; a row opens the provider's page. The
 * list comes from local files first, then the usage from each subscription
 * server.
 */
class SubscriptionsFragment : BasePreferenceFragment(), ManualNodesPage {
    private lateinit var taskPref: Preference
    private lateinit var locationsPref: Preference
    private lateinit var listCategory: PreferenceCategory
    private lateinit var emptyPref: ZeroStatePreference
    private val providerPrefs = HashMap<String, Preference>()
    private var providers: List<ProviderInfo> = emptyList()
    private var loadJob: Job? = null
    override val manualNodes = ManualNodes(this) { key ->
        lastKey = key
        showTask()
    }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val actions = screen.add(PreferenceCategory(prefContext)) { key = "actions" }
        actions.add(Preference(prefContext)) {
            key = "add"
            setTitle(R.string.px_sub_add)
            setIcon(R.drawable.ic_px_add)
            setOnPreferenceClickListener {
                askAdd()
                true
            }
        }
        actions.add(Preference(prefContext)) {
            key = "add_manual"
            setTitle(R.string.px_sub_add_manual)
            setSummary(R.string.px_sub_add_manual_summary)
            setIcon(R.drawable.ic_px_import)
            setOnPreferenceClickListener {
                manualNodes.addNew(providers.map { it.name })
                true
            }
        }
        actions.add(Preference(prefContext)) {
            key = "refresh_all"
            setTitle(R.string.px_sub_refresh_all)
            setSummary(R.string.px_sub_refresh_all_summary)
            setIcon(R.drawable.ic_refresh)
            setOnPreferenceClickListener {
                askRefreshAll()
                true
            }
        }
        taskPref = actions.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                taskKey()?.let { TaskFragment.open(requireContext(), it) }
                true
            }
        }
        locationsPref = actions.add(Preference(prefContext)) {
            key = "locations"
            setIcon(R.drawable.ic_error)
            isVisible = false
            setOnPreferenceClickListener {
                NodeLocationsFragment.open(requireContext(), null)
                true
            }
        }
        listCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "providers"
            setTitle(R.string.px_subs_title)
            isOrderingAsAdded = false
            isVisible = false
        }
        emptyPref = screen.add(ZeroStatePreference(prefContext)) {
            key = "empty"
            setIcon(R.drawable.ic_subscriptions)
            setTitle(PagesR.string.loading)
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_sub_footer)
            order = Int.MAX_VALUE
        }
    }

    private fun askAdd() = askSubscription(this, null) { name, url, fetch -> add(name, url, fetch) }

    private fun askRefreshAll() = confirm(
        "px-refresh-all", R.string.px_sub_refresh_all, { getString(R.string.px_sub_refresh_all_confirm) }, R.string.px_action_refresh,
    ) {
        start(KEY_ALL, getString(R.string.px_sub_refresh_all)) {
            step(context.getString(R.string.px_step_downloading))
            ProxyManager.run(listOf("fetch"))
            locateStep()
            applyConfig()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wasRunning = false
                ProxyTasks.tasks.collect {
                    showTask()
                    val running = ProxyTasks.running() != null
                    if (wasRunning && !running) refresh()
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
            // Local files first (instant), then the usage from the servers.
            attempt("status", R.string.px_error_load_subs) { ProxyManager.status(offline = true) }?.let {
                providers = it.providers
                showLocations(it)
                render()
            } ?: run {
                emptyPref.setTitle(R.string.px_sub_unavailable)
                return@launchUi
            }
            attempt("usage", R.string.px_error_load_usage) { ProxyManager.status(offline = false) }?.let {
                providers = it.providers
                render()
            }
        }
    }

    private fun render() {
        val context = context ?: return
        val keep = providers.map { it.name }.toHashSet()
        providerPrefs.keys.filter { it !in keep }.forEach { name ->
            providerPrefs.remove(name)?.let { listCategory.removePreference(it) }
        }
        providers.forEachIndexed { index, p ->
            val pref = providerPrefs.getOrPut(p.name) {
                listCategory.add(Preference(prefContext)) {
                    key = "provider:" + p.name
                    title = p.name
                    setOnPreferenceClickListener {
                        SubscriptionFragment.open(requireContext(), p.name)
                        true
                    }
                }
            }
            pref.order = index
            pref.setIcon(if (p.manual) R.drawable.ic_px_import else R.drawable.ic_subscriptions)
            pref.summary = ProxyFormat.providerSummary(context, p)
        }
        listCategory.isVisible = providers.isNotEmpty()
        emptyPref.setTitle(R.string.px_sub_empty)
        emptyPref.isVisible = providers.isEmpty()
    }

    /** Nodes without a location are only in GLOBAL: say so, and lead to where they get one. */
    private fun showLocations(status: ManagerStatus) {
        val total = status.poolNodes ?: 0
        val missing = total - (status.poolClassified ?: total)
        locationsPref.isVisible = missing > 0
        if (missing > 0) {
            locationsPref.title = resources.getQuantityString(R.plurals.px_loc_warning, missing, missing)
            locationsPref.setSummary(R.string.px_loc_warning_summary)
        }
    }

    private fun taskKey(): String? {
        val running = ProxyTasks.running()?.key
        if (running != null && (running == KEY_ALL || running.startsWith(SubscriptionFragment.KEY_PREFIX))) return running
        return lastKey?.takeIf { ProxyTasks[it] != null }
    }

    private var lastKey: String? = null

    private fun showTask() {
        val context = context ?: return
        val task = taskKey()?.let { ProxyTasks[it] }
        taskPref.isVisible = task != null
        if (task == null) return
        taskPref.title = task.title
        taskPref.summary = taskStatus(context, task)
    }

    private fun start(key: String, title: String, @OutlivesPage work: suspend ProxyTasks.Scope.() -> Unit) {
        if (!ProxyTasks.start(requireContext(), key, title, work)) {
            Toast.makeText(requireContext(), R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
            return
        }
        lastKey = key
        showTask()
    }

    private fun add(name: String, url: String, fetch: Boolean) {
        if (providers.any { it.name == name }) {
            Toast.makeText(requireContext(), getString(R.string.px_sub_exists, name), Toast.LENGTH_LONG).show()
            return
        }
        start(SubscriptionFragment.KEY_PREFIX + name, getString(R.string.px_sub_adding, name)) {
            step(context.getString(if (fetch) R.string.px_step_downloading else R.string.px_step_saving))
            ProxyManager.addSubscription(name, url, fetch)
            if (fetch) {
                // New nodes join the country, line-type and custom groups only with a location.
                locateStep()
                applyConfig()
            }
            summary(context.getString(if (fetch) R.string.px_sub_added else R.string.px_sub_added_not_fetched))
        }
    }

    companion object {
        private const val KEY_ALL = "subscriptions"

        fun open(context: Context) {
            Pages.open(context, SubscriptionsFragment::class.java, R.string.px_subs_title)
        }
    }
}

/** Status line of a proxy task for the pages' status rows. */
fun taskStatus(context: Context, task: ProxyTasks.Task): String = when (task.state) {
    ProxyTasks.State.RUNNING -> task.step ?: context.getString(R.string.px_task_running)
    ProxyTasks.State.DONE -> task.summary ?: context.getString(R.string.px_task_done)
    ProxyTasks.State.FAILED -> context.getString(R.string.px_task_failed_summary, taskError(context, task.error))
}
