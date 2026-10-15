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
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import kotlinx.coroutines.Job
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * One rule set: on or off, the devices it applies to, its place among the
 * sets, and its rules in order, each with where its traffic goes. Rules can
 * be added (or a list imported from a file), changed, moved within the set or
 * to another set, and removed; the set's targets can be changed together and
 * the set removed. Removals ask first; every change is applied at once. A
 * rule changed elsewhere in the meantime is refused and the list reloads.
 */
class RuleSetFragment : BasePreferenceFragment() {
    private lateinit var setName: String
    private lateinit var headerPref: Preference
    private lateinit var taskPref: Preference
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var platformPref: ListPreference
    private lateinit var positionPref: Preference
    private lateinit var removePref: Preference
    private lateinit var rulesCategory: PreferenceCategory
    private var rules: RuleList? = null
    /** Every set in order (names, sizes), for positions and moves between sets. */
    private var allSets: RuleSets? = null
    private var targets: List<RouteTarget> = emptyList()
    private var loadJob: Job? = null

    private val openList = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) launchUi { importList(uri) }
    }

    private val set: RuleSetDetail?
        get() = rules?.sets?.firstOrNull { it.name == setName }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        setName = requireArguments().getString(ARG_SET) ?: ""
        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        headerPref = header.add(Preference(prefContext)) {
            key = "name"
            title = setName
            setIcon(R.drawable.ic_rule)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        taskPref = header.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                TaskFragment.open(requireContext(), RulesFragment.KEY)
                true
            }
        }
        mainSwitch = screen.add(MainSwitchPreference(prefContext)) {
            key = "enabled"
            setTitle(R.string.px_rule_set_use)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                val on = value as Boolean
                val title = getString(if (on) R.string.px_rules_turning_on else R.string.px_rules_turning_off, setName)
                change(title, getString(if (on) R.string.px_rules_on else R.string.px_rules_off, setName)) {
                    ProxyManager.setRuleSetEnabled(setName, on)
                }
                false
            }
        }
        val settings = screen.add(PreferenceCategory(prefContext)) {
            key = "settings"
            setTitle(R.string.px_rule_set_settings)
        }
        platformPref = settings.add(ListPreference(prefContext)) {
            key = "platform"
            setTitle(R.string.px_rule_platform)
            setDialogTitle(R.string.px_rule_platform)
            entries = arrayOf(getString(R.string.px_rule_platform_all), getString(R.string.px_rule_platform_android), getString(R.string.px_rule_platform_desktop))
            entryValues = arrayOf("all", "android", "desktop")
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                val p = value as String
                change(getString(R.string.px_rule_platform_setting, setName), getString(R.string.px_rule_platform_set, setName)) {
                    ProxyCommands.setPlatform(setName, p)
                }
                false
            }
        }
        positionPref = settings.add(Preference(prefContext)) {
            key = "position"
            setTitle(R.string.px_rule_set_position)
            setOnPreferenceClickListener {
                askSetPosition()
                true
            }
        }
        val actions = screen.add(PreferenceCategory(prefContext)) {
            key = "actions"
            setTitle(R.string.px_rule_actions)
        }
        actions.add(Preference(prefContext)) {
            key = "add"
            setTitle(R.string.px_rule_add)
            setIcon(R.drawable.ic_px_add)
            setOnPreferenceClickListener {
                addRule()
                true
            }
        }
        actions.add(Preference(prefContext)) {
            key = "import"
            setTitle(R.string.px_rule_import)
            setSummary(R.string.px_rule_import_summary)
            setIcon(R.drawable.ic_px_import)
            setOnPreferenceClickListener {
                if (ProxyTasks.running() != null) {
                    Toast.makeText(requireContext(), R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
                } else {
                    openList.launch(arrayOf("*/*"))
                }
                true
            }
        }
        actions.add(Preference(prefContext)) {
            key = "retarget"
            setTitle(R.string.px_rule_retarget)
            setSummary(R.string.px_rule_retarget_set_summary)
            setIcon(R.drawable.ic_px_edit)
            setOnPreferenceClickListener {
                RulesFragment.askRetarget(this@RuleSetFragment, targets, setName)
                true
            }
        }
        removePref = actions.add(Preference(prefContext)) {
            key = "remove"
            setTitle(R.string.px_rule_set_remove)
            setIcon(R.drawable.ic_px_delete)
            setOnPreferenceClickListener {
                askRemoveSet()
                true
            }
        }
        rulesCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "rules"
            setTitle(R.string.px_rule_list)
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_rule_set_footer)
            order = Int.MAX_VALUE
        }
    }

    private var closing = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wasRunning = false
                ProxyTasks.tasks.collect {
                    showTask()
                    val task = ProxyTasks[RulesFragment.KEY]
                    val running = ProxyTasks.running() != null
                    if (wasRunning && !running) {
                        if (closing && task?.state == ProxyTasks.State.DONE) {
                            activity?.finish()
                            return@collect
                        }
                        closing = false
                        refresh()
                    }
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
            // This set's rules only: a routing file can hold thousands of rules.
            rules = attempt("rules", R.string.px_error_load_rules) { ProxyCommands.rules(setName) } ?: return@launchUi
            allSets = attempt("sets", R.string.px_error_load_rules) { ProxyManager.ruleSets() }
            targets = attempt("targets", R.string.error_load_targets) { AppRouteTasks.targets() }.orEmpty()
            render()
        }
    }

    /** How many sets there are (this set's page loads only its own rules). */
    private val setCount: Int
        get() = allSets?.sets?.size ?: ((set?.index ?: 0) + 1)

    private fun render() {
        val context = context ?: return
        if (rules == null) return
        val s = set
        if (s == null) {
            headerPref.setSummary(R.string.px_rule_set_gone)
            return
        }
        val busy = ProxyTasks.running() != null
        headerPref.summary = ProxyFormat.join(
            context,
            listOf(
                resources.getQuantityString(R.plurals.px_rules, s.rules.size, s.rules.size),
                getString(R.string.px_rule_set_position_of, s.index + 1, setCount),
                if (s.holdsMatch) getString(R.string.px_rules_holds_final) else null,
            ),
        )
        mainSwitch.isChecked = s.enabled
        mainSwitch.isEnabled = !busy
        platformPref.value = s.platform ?: "all"
        platformPref.summary = platformPref.entry
        platformPref.isEnabled = !busy
        positionPref.summary = getString(R.string.px_rule_set_position_of, s.index + 1, setCount)
        positionPref.isEnabled = !busy
        removePref.isEnabled = !busy && !s.holdsMatch
        removePref.summary = if (s.holdsMatch) getString(R.string.px_rule_set_remove_final) else null

        rulesCategory.removeAll()
        if (s.rules.isEmpty()) {
            rulesCategory.add(Preference(prefContext)) {
                key = "none"
                setTitle(R.string.px_rule_none)
                isSelectable = false
            }
        }
        for (r in s.rules) {
            rulesCategory.add(Preference(prefContext)) {
                key = "rule:${r.index}"
                title = if (r.type == "MATCH") getString(R.string.px_rules_final) else r.pattern
                summary = ProxyFormat.join(
                    context,
                    listOf(
                        if (r.type == "MATCH") null else RuleEditor.typeLabel(context, r.type),
                        getString(
                            R.string.px_rule_goes_to,
                            // DIRECT and REJECT in words; the manager names them as the configuration does
                            r.group?.takeUnless { it == "DIRECT" || it == "REJECT" }
                                ?: RuleEditor.targetText(context, r.target, targets),
                        ),
                        if (!r.applies) getString(R.string.px_rule_not_here) else null,
                    ),
                )
                isEnabled = !busy
                setOnPreferenceClickListener {
                    ruleActions(r)
                    true
                }
            }
        }
    }

    private fun showTask() {
        val context = context ?: return
        val task = ProxyTasks[RulesFragment.KEY]
        taskPref.isVisible = task != null
        if (task != null) {
            taskPref.title = task.title
            taskPref.summary = taskStatus(context, task)
        }
    }

    /**
     * Runs a rule change as the Rules task and applies the configuration;
     * [done] is its summary. The work never touches the page (it may be gone).
     */
    private fun change(title: String, done: String, closeAfter: Boolean = false, @OutlivesPage save: suspend ProxyTasks.Scope.() -> Unit) {
        val context = requireContext()
        val started = ProxyTasks.start(context, RulesFragment.KEY, title) {
            step(this.context.getString(R.string.px_step_saving))
            save()
            applyConfig()
            summary(done)
        }
        if (!started) {
            Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
            return
        }
        closing = closeAfter
        showTask()
    }

    private fun askRemoveSet() {
        val rules = set?.rules?.size ?: 0
        confirm(
            "px-rule-set-remove", R.string.px_rule_set_remove,
            { resources.getQuantityString(R.plurals.px_rule_set_remove_confirm, rules, setName, rules) }, R.string.px_action_remove,
        ) {
            change(getString(R.string.px_rule_set_removing, setName), getString(R.string.px_rule_set_removed, setName), closeAfter = true) {
                ProxyCommands.removeSet(setName)
            }
        }
    }

    private fun addRule() {
        val all = rules ?: return
        RuleEditor.show(this, R.string.px_rule_add, all.types, targets, null, askPosition = true) { rule, position ->
            change(getString(R.string.px_rule_adding, setName), getString(R.string.px_rule_added, setName)) {
                ProxyCommands.addRule(rule, setName, position)
            }
        }
    }

    private fun ruleActions(r: RuleInfo) {
        val s = set ?: return
        val types = rules?.types.orEmpty()
        val last = s.rules.indexOfLast { it.type != "MATCH" }
        showDialog("px-rule-actions") { d ->
            val items = ArrayList<Pair<String, () -> Unit>>()
            if (r.type == "MATCH") {
                items += getString(R.string.px_rule_final_change) to { RouteTargetFragment.openForFinalRule(requireContext(), r.target) }
            } else {
                items += getString(R.string.px_rule_edit) to { editRule(r, types) }
                if (r.index > 0) items += getString(R.string.px_edit_move_up) to { move(r, setName, r.index - 1) }
                if (r.index < last) items += getString(R.string.px_edit_move_down) to { move(r, setName, r.index + 1) }
                items += getString(R.string.px_rule_move_to_set) to { askMoveToSet(r) }
                items += getString(R.string.px_edit_remove) to { askRemoveRule(r) }
            }
            AlertDialog.Builder(d.context)
                .setTitle(r.text)
                .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun editRule(r: RuleInfo, types: List<String>) {
        RuleEditor.show(this, R.string.px_rule_edit, types, targets, r, askPosition = false) { rule, _ ->
            change(getString(R.string.px_rule_changing, r.pattern), getString(R.string.px_rule_changed, r.pattern)) {
                ProxyCommands.editRule(r, rule)
            }
        }
    }

    private fun askRemoveRule(r: RuleInfo) = confirm(
        "px-rule-remove", R.string.px_edit_remove, { getString(R.string.px_rule_remove_confirm, r.text) }, R.string.px_action_remove,
    ) {
        change(getString(R.string.px_rule_removing, r.pattern), getString(R.string.px_rule_removed, r.pattern)) {
            ProxyCommands.removeRule(r)
        }
    }

    private fun move(r: RuleInfo, toSet: String, toIndex: Int) {
        change(getString(R.string.px_rule_moving, r.pattern), getString(R.string.px_rule_moved, r.pattern)) {
            ProxyCommands.moveRule(r, toSet, toIndex)
        }
    }

    private fun askMoveToSet(r: RuleInfo) {
        val others = allSets?.sets?.filter { it.name != setName }.orEmpty()
        if (others.isEmpty()) return
        showDialog("px-rule-move-to-set") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(R.string.px_rule_move_to_set)
                .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                    // At the end of the other set (the manager keeps it before a final rule).
                    move(r, others[which].name, others[which].rules)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun askSetPosition() {
        val count = setCount
        val index = set?.index ?: return
        showDialog("px-rule-set-position") { d ->
            val context = d.context
            val input = formField(context, R.string.px_rule_position_hint).apply {
                id = R.id.px_rule_set_position
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((index + 1).toString())
            }
            val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
            form.addView(input)
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.px_rule_set_position)
                .setMessage(getString(R.string.px_rule_set_position_message, count))
                .setView(form)
                .setPositiveButton(R.string.px_action_save_apply, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val n = input.text.toString().trim().toIntOrNull()
                    if (n == null || n < 1 || n > count) {
                        input.error = getString(R.string.px_rule_position_invalid)
                    } else {
                        dialog.dismiss()
                        change(getString(R.string.px_rule_set_moving, setName), getString(R.string.px_rule_set_moved, setName, n)) {
                            ProxyCommands.moveSet(setName, n - 1)
                        }
                    }
                }
            }
            dialog
        }
    }

    /** A rule list from a file, appended to this set (one rule per line). */
    private suspend fun importList(uri: Uri) {
        val context = context ?: return
        val bytes = readDocument(context, uri) ?: return // lifecycle: data, read with the page's context
        val lines = String(bytes, Charsets.UTF_8).lines().count { it.isNotBlank() && !it.trimStart().startsWith("#") }
        confirm(
            "px-rule-import", R.string.px_rule_import,
            { resources.getQuantityString(R.plurals.px_rule_import_confirm, lines, lines, setName) }, R.string.px_action_import,
        ) {
            change(getString(R.string.px_rule_importing, setName), getString(R.string.px_rule_imported, setName)) {
                val out = ProxyCommands.importRules(setName, bytes)
                output(out)
            }
        }
    }

    companion object {
        private const val ARG_SET = "set"

        fun open(context: Context, set: String) {
            Pages.open(context, RuleSetFragment::class.java, R.string.px_rule_set_title, arguments = Bundle().apply { putString(ARG_SET, set) })
        }
    }
}
