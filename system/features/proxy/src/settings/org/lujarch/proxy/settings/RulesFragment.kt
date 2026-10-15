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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
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
 * Routing rules: which rule a domain matches, where traffic that no rule
 * matches goes (the final rule), the rule sets in order (each opens its page:
 * rules, on or off, devices, position), adding a rule to a set or a new one,
 * changing targets across sets, sorting the rules into sets, and the custom
 * groups rules can target. Changes apply the configuration at once.
 */
class RulesFragment : BasePreferenceFragment() {
    private lateinit var taskPref: Preference
    private lateinit var matchPref: Preference
    private lateinit var setsCategory: PreferenceCategory
    private val setPrefs = HashMap<String, Preference>()
    private var sets: RuleSets? = null
    private var ruleTypes: List<String> = emptyList()
    private var targets: List<RouteTarget> = emptyList()
    private var loadJob: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val top = screen.add(PreferenceCategory(prefContext)) { key = "top" }
        taskPref = top.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                TaskFragment.open(requireContext(), KEY)
                true
            }
        }
        top.add(Preference(prefContext)) {
            key = "check"
            setTitle(R.string.px_rules_check)
            setSummary(R.string.px_rules_check_summary)
            setIcon(PagesR.drawable.ic_search)
            setOnPreferenceClickListener {
                askDomain()
                true
            }
        }
        top.add(Preference(prefContext)) {
            key = "custom_groups"
            setTitle(R.string.px_custom_title)
            setSummary(R.string.px_custom_summary)
            setIcon(R.drawable.ic_groups)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), CustomGroupsFragment::class.java, R.string.px_custom_title)
                true
            }
        }
        val match = screen.add(PreferenceCategory(prefContext)) {
            key = "final"
            setTitle(R.string.px_rules_final_category)
        }
        matchPref = match.add(Preference(prefContext)) {
            key = "match"
            setTitle(R.string.px_rules_final)
            setSummary(PagesR.string.loading)
            setOnPreferenceClickListener {
                RouteTargetFragment.openForFinalRule(requireContext(), sets?.defaultTarget)
                true
            }
        }
        val edit = screen.add(PreferenceCategory(prefContext)) {
            key = "edit"
            setTitle(R.string.px_rule_actions)
        }
        edit.add(Preference(prefContext)) {
            key = "add"
            setTitle(R.string.px_rule_add)
            setSummary(R.string.px_rule_add_summary)
            setIcon(R.drawable.ic_px_add)
            setOnPreferenceClickListener {
                chooseSetForRule()
                true
            }
        }
        edit.add(Preference(prefContext)) {
            key = "retarget"
            setTitle(R.string.px_rule_retarget)
            setSummary(R.string.px_rule_retarget_summary)
            setIcon(R.drawable.ic_px_edit)
            setOnPreferenceClickListener {
                askRetarget(this@RulesFragment, targets, null)
                true
            }
        }
        edit.add(Preference(prefContext)) {
            key = "regroup"
            setTitle(R.string.px_rule_regroup)
            setSummary(R.string.px_rule_regroup_summary)
            setIcon(R.drawable.ic_tune)
            setOnPreferenceClickListener {
                askRegroup()
                true
            }
        }
        setsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "sets"
            setTitle(R.string.px_rules_sets)
            isOrderingAsAdded = false
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_rules_footer)
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
            sets = attempt("sets", R.string.px_error_load_rules) { ProxyManager.ruleSets() } ?: return@launchUi
            render()
            // The rule types the manager takes come with any set's list; one small set is enough.
            val smallest = sets?.sets?.minByOrNull { it.rules }?.name
            ruleTypes = smallest?.let { name -> attempt("rules", R.string.px_error_load_rules) { ProxyCommands.rules(name) }?.types }.orEmpty()
            targets = attempt("targets", R.string.error_load_targets) { AppRouteTasks.targets() }.orEmpty()
        }
    }

    private fun render() {
        val context = context ?: return
        val s = sets ?: return
        matchPref.summary = when {
            s.defaultTarget == null -> getString(R.string.px_rules_final_none)
            s.defaultGroup != null && s.defaultGroup != s.defaultTarget -> s.defaultGroup
            else -> targetLabel(context, s.defaultTarget)
        }
        val keep = s.sets.map { it.name }.toHashSet()
        setPrefs.keys.filter { it !in keep }.forEach { n -> setPrefs.remove(n)?.let { setsCategory.removePreference(it) } }
        for (set in s.sets) {
            val pref = setPrefs.getOrPut(set.name) {
                setsCategory.add(Preference(prefContext)) {
                    key = "set:" + set.name
                    title = set.name
                    setOnPreferenceClickListener {
                        RuleSetFragment.open(requireContext(), set.name)
                        true
                    }
                }
            }
            pref.order = set.index
            pref.summary = ProxyFormat.join(
                context,
                listOf(
                    getString(if (set.enabled) R.string.px_opt_on else R.string.px_opt_off),
                    resources.getQuantityString(R.plurals.px_rules, set.rules, set.rules),
                    when (set.platform) {
                        "android" -> getString(R.string.px_rules_platform_android)
                        "desktop" -> getString(R.string.px_rules_platform_desktop)
                        else -> null
                    },
                    if (set.holdsMatch) getString(R.string.px_rules_holds_final) else null,
                ),
            )
        }
        val total = s.sets.filter { it.enabled }.sumOf { it.rules }
        setsCategory.title = resources.getQuantityString(R.plurals.px_rules_sets_count, total, s.sets.size, total)
    }

    private fun showTask() {
        val context = context ?: return
        val task = ProxyTasks[KEY]
        taskPref.isVisible = task != null
        if (task != null) {
            taskPref.title = task.title
            taskPref.summary = taskStatus(context, task)
        }
        render()
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

    private fun askRegroup() = confirm(
        "px-regroup", R.string.px_rule_regroup, { getString(R.string.px_rule_regroup_confirm) }, R.string.px_rule_regroup_action,
    ) {
        start(getString(R.string.px_rule_regrouping), getString(R.string.px_rule_regrouped)) { ProxyCommands.regroup() }
    }

    /** A new rule: first the set (an existing one, or a new one by name), then the rule. */
    private fun chooseSetForRule() {
        val list = sets ?: run {
            Toast.makeText(requireContext(), PagesR.string.loading, Toast.LENGTH_SHORT).show()
            return
        }
        val names = list.sets.map { it.name }
        showDialog("px-rule-set-choice") { d ->
            val labels = names + getString(R.string.px_rule_new_set)
            AlertDialog.Builder(d.context)
                .setTitle(R.string.px_rule_choose_set)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < names.size) editNewRule(names[which]) else askNewSet(names)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    /** A new set's name; [existing]: the sets when it was asked (the dialog's, so a recreated page checks the same). */
    private fun askNewSet(existing: List<String>) = showDialog("px-rule-new-set") { d ->
        val context = d.context
        val input = formField(context, R.string.px_rule_new_set_hint).apply { id = R.id.px_rule_new_set }
        val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
        form.addView(input)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.px_rule_new_set)
            .setMessage(R.string.px_rule_new_set_message)
            .setView(form)
            .setPositiveButton(R.string.px_action_next, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                when {
                    !isProviderName(name) -> input.error = getString(R.string.px_rule_set_name_invalid)
                    name in existing -> input.error = getString(R.string.px_rule_set_exists, name)
                    else -> {
                        dialog.dismiss()
                        editNewRule(name)
                    }
                }
            }
        }
        input.requestFocus()
        dialog
    }

    private fun editNewRule(set: String) {
        RuleEditor.show(this, R.string.px_rule_add, ruleTypes, targets, null, askPosition = true) { rule, position ->
            start(getString(R.string.px_rule_adding, set), getString(R.string.px_rule_added, set)) {
                ProxyCommands.addRule(rule, set, position)
            }
        }
    }

    private fun askDomain() = showDialog("px-rules-check") { d ->
        val context = d.context
        val input = formField(context, R.string.px_rules_check_hint).apply {
            id = R.id.px_rules_check_domain
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
        form.addView(input)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.px_rules_check)
            .setMessage(R.string.px_rules_check_prompt)
            .setView(form)
            .setPositiveButton(R.string.px_action_check, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val domain = normalizeDomain(input.text.toString())
                if (domain == null) {
                    input.error = getString(R.string.px_rules_check_invalid)
                } else {
                    dialog.dismiss()
                    checkDomain(domain)
                }
            }
        }
        input.requestFocus()
        dialog
    }

    private fun checkDomain(domain: String) {
        launchUi {
            val m = attempt("match", R.string.px_error_rule_check) { ProxyManager.ruleMatch(domain) } ?: return@launchUi
            showDialog("px-rules-check-result") { d ->
                val context = d.context
                val message = if (m.set == null) {
                    getString(R.string.px_rules_check_none, domain)
                } else {
                    getString(
                        R.string.px_rules_check_result,
                        domain,
                        m.set,
                        m.index + 1,
                        m.rule ?: "",
                        m.group ?: targetLabel(context, m.target ?: ""),
                    )
                } + "\n\n" + getString(R.string.px_rules_check_note)
                val builder = AlertDialog.Builder(context)
                    .setTitle(R.string.px_rules_check)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                if (m.set != null) builder.setNeutralButton(R.string.px_rule_open_set) { _, _ -> RuleSetFragment.open(requireContext(), m.set) }
                builder.create()
            }
        }
    }

    companion object {
        const val KEY = "rules"

        /**
         * Changes the target of many rules at once: those whose pattern holds
         * a text, or those that go to one target; in one set or in all.
         */
        /** The targets chosen in the retarget form, which no field keeps; [shown] updates the window on screen. */
        private class Retarget {
            var from: String? = null
            var to: String? = null
            var shown: (() -> Unit)? = null
        }

        fun askRetarget(page: Fragment, targets: List<RouteTarget>, set: String?) {
            val chosen = Retarget()
            page.showDialog("px-retarget") { d -> retargetDialog(this, d.context, chosen, targets, set) }
        }

        private fun retargetDialog(page: Fragment, context: Context, chosen: Retarget, targets: List<RouteTarget>, set: String?): Dialog {
            val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
            val by = RadioGroup(context).apply {
                addView(RadioButton(context).apply { id = R.id.px_retarget_by_target; setText(R.string.px_rule_retarget_by_target) })
                addView(RadioButton(context).apply { id = R.id.px_retarget_by_pattern; setText(R.string.px_rule_retarget_by_pattern) })
                check(R.id.px_retarget_by_target)
            }
            form.addView(by)
            val patternInput = formField(context, R.string.px_rule_retarget_pattern_hint).apply {
                id = R.id.px_retarget_pattern
                visibility = View.GONE
            }
            form.addView(patternInput)
            val fromButton = android.widget.Button(context, null, android.R.attr.borderlessButtonStyle).apply { isAllCaps = false }
            val toButton = android.widget.Button(context, null, android.R.attr.borderlessButtonStyle).apply { isAllCaps = false }
            chosen.shown = {
                fromButton.text = chosen.from?.let { context.getString(R.string.px_rule_retarget_from_value, RuleEditor.targetText(context, it, targets)) }
                    ?: context.getString(R.string.px_rule_retarget_from)
                toButton.text = chosen.to?.let { context.getString(R.string.px_rule_retarget_to_value, RuleEditor.targetText(context, it, targets)) }
                    ?: context.getString(R.string.px_rule_retarget_to)
                if (chosen.from != null) fromButton.error = null
                if (chosen.to != null) toButton.error = null
            }
            chosen.shown?.invoke()
            fromButton.setOnClickListener {
                RuleEditor.chooseTarget(page, targets, chosen.from) { t ->
                    chosen.from = t
                    chosen.shown?.invoke()
                }
            }
            form.addView(fromButton)
            by.setOnCheckedChangeListener { _, id ->
                patternInput.visibility = if (id == R.id.px_retarget_by_pattern) View.VISIBLE else View.GONE
                fromButton.visibility = if (id == R.id.px_retarget_by_target) View.VISIBLE else View.GONE
            }
            toButton.setOnClickListener {
                RuleEditor.chooseTarget(page, targets, chosen.to) { t ->
                    chosen.to = t
                    chosen.shown?.invoke()
                }
            }
            form.addView(toButton)
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.px_rule_retarget)
                .setMessage(if (set == null) context.getString(R.string.px_rule_retarget_message_all) else context.getString(R.string.px_rule_retarget_message_set, set))
                .setView(form)
                .setPositiveButton(R.string.px_action_save_apply, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val byPattern = by.checkedRadioButtonId == R.id.px_retarget_by_pattern
                    val pattern = patternInput.text.toString().trim()
                    val target = chosen.to
                    val from = chosen.from
                    when {
                        byPattern && (pattern.isEmpty() || pattern.startsWith("-") || pattern.any { it.isISOControl() }) ->
                            patternInput.error = context.getString(R.string.px_rule_pattern_invalid)
                        !byPattern && from == null -> fromButton.error = context.getString(R.string.px_rule_target_missing)
                        target == null -> toButton.error = context.getString(R.string.px_rule_target_missing)
                        else -> {
                            dialog.dismiss()
                            val shown = RuleEditor.targetText(context, target, targets)
                            val started = ProxyTasks.start(context, KEY, context.getString(R.string.px_rule_retargeting, shown)) {
                                step(this.context.getString(R.string.px_step_saving))
                                val out = ProxyCommands.retarget(target, if (byPattern) pattern else null, if (byPattern) null else from, set)
                                output(out)
                                applyConfig()
                                summary(this.context.getString(R.string.px_rule_retargeted, shown))
                            }
                            if (!started) Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            return dialog
        }

        /**
         * An ASCII host name from user input (a URL is reduced to its host,
         * internationalized names become punycode), or null. It never starts
         * with '-', so it cannot be taken for an option.
         */
        fun normalizeDomain(input: String): String? {
            var host = input.trim().substringAfter("://")
            host = host.substringBefore('/').substringBefore('?').substringBefore('#')
            if (host.count { it == ':' } == 1) host = host.substringBefore(':')
            host = host.trimEnd('.')
            if (host.isEmpty()) return null
            val ascii = try {
                java.net.IDN.toASCII(host, java.net.IDN.ALLOW_UNASSIGNED).lowercase()
            } catch (e: IllegalArgumentException) {
                return null
            }
            return ascii.takeIf { DOMAIN.matches(it) }
        }

        private val DOMAIN = Regex("^[a-z0-9_](?:[a-z0-9_.\\-]{0,251}[a-z0-9_])?$")

        /** DIRECT and REJECT in words; group targets as they are. */
        fun targetLabel(context: android.content.Context, target: String): String = when (target) {
            "DIRECT" -> context.getString(R.string.px_target_direct)
            "REJECT" -> context.getString(R.string.px_target_reject)
            else -> target
        }
    }
}
