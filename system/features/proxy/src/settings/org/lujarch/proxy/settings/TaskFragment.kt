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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.OutputPreference
import org.lujarch.settings.pages.Pages

/**
 * Localized description of a failed proxy task; the manager's and the
 * service's own (English) text is only in the detailed output.
 */
fun taskError(context: Context, t: Throwable?): String = ProxyErrors.describe(context, t)

/**
 * The progress and result of one proxy task ([ProxyTasks]): what it did in
 * words (counts, files, backup), and the manager's raw output only behind
 * "Detailed output".
 */
class TaskFragment : BasePreferenceFragment() {
    private lateinit var key: String
    private lateinit var statePref: Preference
    private lateinit var resultCategory: PreferenceCategory
    private lateinit var detailsSwitch: SwitchPreferenceCompat
    private lateinit var outputCategory: PreferenceCategory
    private lateinit var outputPref: OutputPreference
    private var showDetails = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        key = requireArguments().getString(ARG_KEY) ?: ""
        showDetails = savedInstanceState?.getBoolean(STATE_DETAILS) ?: false
        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        statePref = header.add(Preference(prefContext)) {
            key = "state"
            isSelectable = false
            setTitle(R.string.px_task_none)
        }
        resultCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "result"
            setTitle(R.string.px_result_category)
            isVisible = false
        }
        val details = screen.add(PreferenceCategory(prefContext)) { key = "details" }
        detailsSwitch = details.add(SwitchPreferenceCompat(prefContext)) {
            key = "show_details"
            setTitle(R.string.px_details)
            setSummary(R.string.px_details_summary)
            isPersistent = false
            isChecked = showDetails
            isVisible = false
            setOnPreferenceChangeListener { _, value ->
                showDetails = value as Boolean
                render()
                true
            }
        }
        outputCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "output_category"
            isVisible = false
        }
        outputPref = outputCategory.add(OutputPreference(prefContext)) { key = "output" }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DETAILS, showDetails)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProxyTasks.tasks.collect { render() }
            }
        }
    }

    private fun row(title: CharSequence, summary: CharSequence?, onClick: (() -> Unit)? = null) {
        resultCategory.add(Preference(prefContext)) {
            this.title = title
            this.summary = summary
            isSelectable = onClick != null
            isCopyingEnabled = onClick == null
            if (onClick != null) {
                setOnPreferenceClickListener {
                    onClick()
                    true
                }
            }
        }
    }

    private fun render() {
        val context = context ?: return
        val task = ProxyTasks[key]
        resultCategory.removeAll()
        if (task == null) {
            statePref.setTitle(R.string.px_task_none)
            statePref.summary = null
            resultCategory.isVisible = false
            detailsSwitch.isVisible = false
            outputCategory.isVisible = false
            return
        }
        statePref.title = task.title
        statePref.summary = when (task.state) {
            ProxyTasks.State.RUNNING -> task.step ?: getString(R.string.px_task_running)
            ProxyTasks.State.DONE -> task.summary ?: getString(R.string.px_task_done_at, Format.time(context, task.finishedAt))
            ProxyTasks.State.FAILED -> getString(R.string.px_task_failed_at, Format.time(context, task.finishedAt))
        }
        if (task.state == ProxyTasks.State.FAILED) {
            val hasOutput = !task.output.isNullOrBlank()
            row(
                getString(R.string.px_result_error),
                if (hasOutput) getString(R.string.px_err_see_details, taskError(context, task.error)) else taskError(context, task.error),
            )
        }
        for (note in task.notes) {
            when (note) {
                is ProbeResult -> locateRows(context, note)
                is LocateFailed -> row(getString(R.string.px_loc_result), getString(R.string.px_loc_result_failed, ProxyErrors.describe(context, note.error)))
            }
        }
        // The locating step already says how many nodes have no location.
        val located = task.notes.any { it is ProbeResult }
        when (val data = task.data) {
            is ConfigResult -> configRows(context, data, showUnlocated = !located)
            is ImportReport -> importRows(context, data)
        }
        if (task.applied) row(getString(R.string.px_result_applied), getString(R.string.px_result_applied_summary))
        resultCategory.isVisible = resultCategory.preferenceCount > 0
        val output = task.output?.let { Format.stripAnsi(it).trimEnd() }
        detailsSwitch.isVisible = !task.running && !output.isNullOrEmpty()
        outputCategory.isVisible = detailsSwitch.isVisible && showDetails
        if (outputCategory.isVisible) outputPref.text = output ?: ""
    }

    private fun configRows(context: Context, r: ConfigResult, showUnlocated: Boolean = true) {
        ProxyFormat.counts(context, r.proxies, r.groups, r.rules)?.let { row(getString(R.string.px_result_config), it) }
        when (r.outcome) {
            "healthy" -> row(getString(R.string.px_result_core), getString(R.string.px_result_core_healthy))
            "core-down" -> row(getString(R.string.px_result_core), getString(R.string.px_result_core_down))
            "written" -> row(getString(R.string.px_result_core), getString(R.string.px_result_core_written))
            "ok" -> row(getString(R.string.px_result_check), getString(R.string.px_result_check_ok))
            "skipped" -> row(getString(R.string.px_result_check), getString(R.string.px_result_check_skipped))
        }
        r.skippedRules?.takeIf { it > 0 }?.let {
            row(getString(R.string.px_result_skipped), resources.getQuantityString(R.plurals.px_result_skipped_rules, it, it))
        }
        if (r.unresolved.isNotEmpty()) {
            row(getString(R.string.px_result_unresolved), getString(R.string.px_result_unresolved_summary, Format.names(requireContext(), r.unresolved)))
        }
        if (r.missingNodes.isNotEmpty()) {
            row(getString(R.string.px_result_missing), r.missingNodes.joinToString(getString(R.string.px_list_separator)))
        }
        if (showUnlocated && r.unclassified > 0) {
            row(
                getString(R.string.px_loc_result),
                resources.getQuantityString(R.plurals.px_loc_result_missing, r.unclassified, r.unclassified),
            ) { NodeLocationsFragment.open(context, null) }
        }
        if (r.outcome == null && r.log.isNotEmpty() && r.ok) {
            // rollback: what it restored, in the manager's words
            row(getString(R.string.px_result_rollback), getString(R.string.px_result_rollback_done))
        }
    }

    private fun importRows(context: Context, r: ImportReport) {
        if (r.changed.isNotEmpty()) row(getString(R.string.px_result_changed), ProxyFormat.filesLabel(context, r.changed))
        if (r.removed.isNotEmpty()) row(getString(R.string.px_result_removed), ProxyFormat.filesLabel(context, r.removed))
        if (r.keptSets.isNotEmpty()) row(getString(R.string.px_result_kept_sets), r.keptSets.joinToString(getString(R.string.px_list_separator)))
        if (r.movedPaths.isNotEmpty()) {
            row(getString(R.string.px_result_moved), getString(R.string.px_result_moved_summary, r.movedPaths.joinToString(getString(R.string.px_list_separator))))
        }
        if (r.fetched.isNotEmpty()) row(getString(R.string.px_result_fetched), r.fetched.joinToString(getString(R.string.px_list_separator)))
        if (r.missingNodes.isNotEmpty()) {
            row(getString(R.string.px_result_missing), r.missingNodes.joinToString(getString(R.string.px_list_separator)))
        }
        if (r.kind != "geodata") {
            ProxyFormat.counts(context, r.proxies, r.groups, r.rules)?.let { row(getString(R.string.px_result_checked), it) }
        }
        r.backup?.let { row(getString(R.string.px_result_backup), getString(R.string.px_result_backup_summary)) }
    }

    /** The locating step: how many nodes it located, how many still have no location. */
    private fun locateRows(context: Context, r: ProbeResult) {
        val summary = if (r.total == r.classifiedBefore) {
            getString(R.string.px_loc_result_none_needed)
        } else {
            resources.getQuantityString(R.plurals.px_loc_result_located, r.located, r.located)
        }
        row(getString(R.string.px_loc_result), summary)
        if (r.unclassified > 0) {
            row(
                getString(R.string.px_loc_result_missing_title),
                resources.getQuantityString(R.plurals.px_loc_result_missing, r.unclassified, r.unclassified),
            ) { NodeLocationsFragment.open(context, null) }
        }
    }

    companion object {
        private const val ARG_KEY = "key"
        private const val STATE_DETAILS = "details"
        const val KEY_APPLY = "apply"
        const val KEY_CHECK = "check"
        const val KEY_ROLLBACK = "rollback"
        const val KEY_RESTART = "restart"

        fun open(context: Context, key: String) {
            Pages.open(
                context,
                TaskFragment::class.java,
                R.string.px_task_page_title,
                arguments = Bundle().apply { putString(ARG_KEY, key) },
            )
        }
    }
}
