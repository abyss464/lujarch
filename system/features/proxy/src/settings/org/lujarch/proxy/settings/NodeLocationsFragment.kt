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
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import kotlinx.coroutines.Job
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * Where the nodes are (country and line type), which puts them into the
 * country, line-type and custom groups: nodes without a location first (they
 * are only in GLOBAL), then the located ones. Locate them through their exit
 * address (the way the computer does it), set a location by hand for one node
 * or for every node still without one, or forget a location so the next
 * locating runs again for that node. All nodes, or one provider's.
 */
class NodeLocationsFragment : BasePreferenceFragment() {
    private var provider: String? = null
    private lateinit var headerPref: Preference
    private lateinit var taskPref: Preference
    private lateinit var locatePref: Preference
    private lateinit var byHandAllPref: Preference
    private lateinit var missingCategory: PreferenceCategory
    private lateinit var locatedCategory: PreferenceCategory
    private var locations: NodeLocations? = null
    private var loadJob: Job? = null

    private val nodes: List<NodeLocation>
        get() = locations?.nodes.orEmpty().filter { provider == null || it.provider == provider }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        provider = arguments?.getString(ARG_PROVIDER)
        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        headerPref = header.add(Preference(prefContext)) {
            key = "summary"
            title = provider ?: getString(R.string.px_loc_all_nodes)
            setIcon(R.drawable.ic_groups)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        taskPref = header.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                TaskFragment.open(requireContext(), KEY)
                true
            }
        }
        val actions = screen.add(PreferenceCategory(prefContext)) { key = "actions" }
        locatePref = actions.add(Preference(prefContext)) {
            key = "locate"
            setTitle(R.string.px_loc_locate)
            setSummary(R.string.px_loc_locate_summary)
            setIcon(PagesR.drawable.ic_search)
            setOnPreferenceClickListener {
                askLocate()
                true
            }
        }
        byHandAllPref = actions.add(Preference(prefContext)) {
            key = "by_hand_all"
            setTitle(R.string.px_loc_by_hand_all)
            setSummary(R.string.px_loc_by_hand_all_summary)
            setIcon(R.drawable.ic_px_edit)
            setOnPreferenceClickListener {
                val missing = nodes.filter { !it.located }.map { it.name }
                if (missing.isNotEmpty()) askByHand(missing, all = true)
                true
            }
        }
        missingCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "missing"
            setTitle(R.string.px_loc_missing)
        }
        locatedCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "located"
            setTitle(R.string.px_loc_located)
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_loc_footer)
            order = Int.MAX_VALUE
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
                    // The rows are enabled only while no task runs.
                    if (wasRunning != running) render()
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
            locations = attempt("locations", R.string.px_error_load_locations) { ProxyCommands.locations() } ?: return@launchUi
            render()
        }
    }

    private fun render() {
        val context = context ?: return
        if (locations == null) return
        val list = nodes
        val missing = list.filter { !it.located }
        headerPref.summary = if (missing.isEmpty()) {
            resources.getQuantityString(R.plurals.px_loc_all_located, list.size, list.size)
        } else {
            resources.getQuantityString(R.plurals.px_loc_some_missing, missing.size, missing.size, list.size)
        }
        val busy = ProxyTasks.running() != null
        locatePref.isEnabled = !busy
        byHandAllPref.isEnabled = !busy && missing.isNotEmpty()
        missingCategory.removeAll()
        missingCategory.isVisible = missing.isNotEmpty()
        for (n in missing.take(MAX_ROWS)) {
            missingCategory.add(Preference(prefContext)) {
                key = "missing:" + n.name
                title = n.name
                summary = n.provider?.takeIf { provider == null }
                isEnabled = !busy
                setOnPreferenceClickListener {
                    askByHand(listOf(n.name))
                    true
                }
            }
        }
        locatedCategory.removeAll()
        val located = list.filter { it.located }
        locatedCategory.isVisible = located.isNotEmpty()
        for (n in located.take(MAX_ROWS)) {
            locatedCategory.add(Preference(prefContext)) {
                key = "located:" + n.name
                title = n.name
                summary = ProxyFormat.join(
                    context,
                    listOf(
                        getString(R.string.px_target_country_net, n.country ?: "", ProxyFormat.netTypeLabel(context, n.netType) ?: ""),
                        if (n.byHand) getString(R.string.px_loc_by_hand_mark) else null,
                        n.provider?.takeIf { provider == null },
                    ),
                )
                isEnabled = !busy
                setOnPreferenceClickListener {
                    locatedActions(n)
                    true
                }
            }
        }
    }

    private fun showTask() {
        val context = context ?: return
        val task = ProxyTasks[KEY]
        taskPref.isVisible = task != null
        if (task != null) {
            taskPref.title = task.title
            taskPref.summary = taskStatus(context, task)
        }
    }

    private fun askLocate() = confirm(
        "px-locate", R.string.px_loc_locate, { getString(R.string.px_loc_locate_confirm) }, R.string.px_loc_locate_action,
    ) { locate(requireContext()) }

    private fun locatedActions(n: NodeLocation) = showDialog("px-location-actions") { d ->
        val items = arrayOf(getString(R.string.px_loc_change_by_hand), getString(R.string.px_loc_forget))
        AlertDialog.Builder(d.context)
            .setTitle(n.name)
            .setItems(items) { _, which ->
                if (which == 0) {
                    askByHand(listOf(n.name))
                } else {
                    confirm(
                        "px-location-forget", R.string.px_loc_forget, { getString(R.string.px_loc_forget_confirm, n.name) },
                        R.string.px_loc_forget_action,
                    ) {
                        start(getString(R.string.px_loc_forgetting, n.name), getString(R.string.px_loc_forgotten, n.name)) {
                            ProxyCommands.forgetLocations(listOf(n.name))
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    /** Country and line type for [names] ([all]: every node without a location, else one node). */
    private fun askByHand(names: List<String>, all: Boolean = false) = showDialog("px-location") { d ->
        val title = if (all) resources.getQuantityString(R.plurals.px_loc_by_hand_all_title, names.size, names.size) else names.first()
        locationDialog(d.context, title) { country, netType ->
            start(
                getString(R.string.px_loc_setting, title),
                resources.getQuantityString(R.plurals.px_loc_set, names.size, names.size),
            ) { ProxyCommands.locateByHand(names, country, netType) }
        }
    }

    private fun start(title: String, done: String, @OutlivesPage save: suspend ProxyTasks.Scope.() -> Unit) {
        val context = requireContext()
        val started = ProxyTasks.start(context, KEY, title) {
            step(this.context.getString(R.string.px_step_saving))
            save()
            applyConfig()
            summary(done)
        }
        if (!started) Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
        showTask()
    }

    companion object {
        const val KEY = "locations"
        private const val ARG_PROVIDER = "provider"
        private const val MAX_ROWS = 500

        fun open(context: Context, provider: String?) {
            Pages.open(
                context,
                NodeLocationsFragment::class.java,
                R.string.px_loc_title,
                arguments = Bundle().apply { putString(ARG_PROVIDER, provider) },
            )
        }

        /** Locates the nodes without a location (a proxy task), then applies the configuration. */
        fun locate(context: Context) {
            val started = ProxyTasks.start(context, KEY, context.getString(R.string.px_loc_locating)) {
                locateStep()
                applyConfig()
            }
            if (!started) Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
        }

        /** A country code and a line type, for a page dialog (the fields have ids: a recreated window keeps them). */
        fun locationDialog(context: Context, title: String, onDone: (country: String, netType: String) -> Unit): Dialog {
            val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
            val country = formField(context, R.string.px_manual_country_hint).apply {
                id = R.id.px_manual_country
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            form.addView(country)
            form.addView(TextView(context).apply {
                setText(R.string.px_manual_net_type)
                setPadding(0, 24, 0, 0)
            })
            val types = listOf("residential", "datacenter", "starlink")
            val typeIds = listOf(R.id.px_manual_residential, R.id.px_manual_datacenter, R.id.px_manual_starlink)
            val group = RadioGroup(context)
            types.forEachIndexed { i, t ->
                group.addView(RadioButton(context).apply {
                    id = typeIds[i]
                    text = ProxyFormat.netTypeLabel(context, t)
                })
            }
            group.check(typeIds[1])
            form.addView(group)
            val dialog = AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(R.string.px_loc_by_hand_message)
                .setView(form)
                .setPositiveButton(R.string.px_action_save_apply, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val cc = country.text.toString().trim().uppercase()
                    if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) {
                        country.error = context.getString(R.string.px_manual_country_invalid)
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    onDone(cc, types[typeIds.indexOf(group.checkedRadioButtonId).coerceIn(0, types.size - 1)])
                }
            }
            country.requestFocus()
            return dialog
        }
    }
}

/**
 * The locating step of a proxy task (after nodes were added or downloaded):
 * finds the nodes without a location through their exit address. A failure
 * leaves the nodes unlocated and the task goes on; the result says how many
 * nodes still have no location.
 */
suspend fun ProxyTasks.Scope.locateStep() {
    step(context.getString(R.string.px_step_locating))
    try {
        val (result, output) = ProxyCommands.probe()
        output(output)
        note(result)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w(TAG, "locating nodes failed: ${e.message}")
        note(LocateFailed(e))
    }
}

/** The locating step failed; the nodes stay without a location. */
data class LocateFailed(val error: Throwable)
