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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import kotlinx.coroutines.Job
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * One custom group: what it collects (its filters), whether it is one
 * adaptive group or three variants, the test parameters its variants
 * use — the ones of their kind (Core settings > Group testing) unless this
 * group sets its own; an adaptive group has only its probe address, its live
 * page and the decision parameters — and removing it. The page has a fixed
 * title; the group heads the list, so a long name never changes the title bar.
 */
class CustomGroupFragment : BasePreferenceFragment() {
    private lateinit var groupKey: String
    private lateinit var headPref: Preference
    private lateinit var taskPref: Preference
    private lateinit var testCategory: PreferenceCategory
    private lateinit var adaptivePref: SwitchPreferenceCompat
    private lateinit var livePref: Preference
    private lateinit var paramsPref: Preference
    private lateinit var footerPref: FooterPreference
    private var group: CustomGroup? = null
    private var loadJob: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        groupKey = requireArguments().getString(ARG_KEY) ?: ""
        val head = screen.add(PreferenceCategory(prefContext)) { key = "head" }
        headPref = head.add(Preference(prefContext)) {
            key = "group"
            title = groupKey
            setSummary(PagesR.string.loading)
            isSelectable = false
            isCopyingEnabled = true
        }
        taskPref = head.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                TaskFragment.open(requireContext(), CustomGroupsFragment.KEY)
                true
            }
        }
        val choice = screen.add(PreferenceCategory(prefContext)) {
            key = "choice"
            setTitle(R.string.px_ad_choice)
        }
        adaptivePref = choice.add(SwitchPreferenceCompat(prefContext)) {
            key = "adaptive"
            setTitle(R.string.px_ad_custom_switch)
            isPersistent = false
            // Aligned with the rows of its card, which have icons.
            isIconSpaceReserved = true
            setOnPreferenceChangeListener { _, value ->
                askAdaptive(value as Boolean)
                false
            }
        }
        livePref = choice.add(Preference(prefContext)) {
            key = "live"
            setTitle(R.string.px_ad_custom_live)
            setIcon(R.drawable.ic_groups)
            isVisible = false
            setOnPreferenceClickListener {
                group?.let { AdaptiveGroupFragment.open(requireContext(), it.adaptiveGroup) }
                true
            }
        }
        paramsPref = choice.add(Preference(prefContext)) {
            key = "params"
            setTitle(R.string.px_ad_params)
            setSummary(R.string.px_ad_params_entry_summary)
            setIcon(R.drawable.ic_tune)
            isVisible = false
            setOnPreferenceClickListener {
                AdaptiveParamsFragment.open(requireContext())
                true
            }
        }
        testCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "test"
            setTitle(R.string.px_gt_title)
        }
        val actions = screen.add(PreferenceCategory(prefContext)) { key = "actions" }
        actions.add(Preference(prefContext)) {
            key = "remove"
            setTitle(R.string.px_custom_remove)
            setIcon(R.drawable.ic_px_delete)
            setOnPreferenceClickListener {
                askRemove()
                true
            }
        }
        footerPref = screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_custom_group_footer)
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
                    render()
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
            group = attempt("group", R.string.px_error_load_custom) { ProxyManager.customGroup(groupKey) } ?: return@launchUi
            render()
        }
    }

    private fun render() {
        val context = context ?: return
        val g = group ?: return
        headPref.summary = ProxyFormat.join(
            context,
            listOf(
                g.country,
                ProxyFormat.netTypeLabel(context, g.netType),
                g.nameContains?.let { getString(R.string.px_custom_name_contains, it) },
                g.nodes?.let { resources.getQuantityString(R.plurals.px_nodes, it, it) },
                g.fallbackTo?.let { getString(R.string.px_custom_fallback_to, ProxyFormat.targetLabel(context, it)) },
            ),
        )
        val enabled = ProxyTasks.running() == null
        adaptivePref.isChecked = g.adaptive
        adaptivePref.isEnabled = enabled
        adaptivePref.setSummary(if (g.adaptive) R.string.px_ad_custom_on else R.string.px_ad_custom_off)
        livePref.isVisible = g.adaptive && g.adaptiveBuilt
        livePref.summary = g.adaptiveGroup
        paramsPref.isVisible = g.adaptive
        // An adaptive group is one group with a probe address, not three variants.
        footerPref.setTitle(if (g.adaptive) R.string.px_ad_custom_group_footer else R.string.px_custom_group_footer)
        testCategory.removeAll()
        // An adaptive group measures its members itself: only its probe address applies.
        testCategory.setTitle(if (g.adaptive) R.string.px_ad_custom_probe_category else R.string.px_gt_title)
        for (field in FIELDS) {
            if (g.adaptive && field.name != "url") continue
            val own = g.testSet[field.name] != null
            val value = effective(g, field.name)
            if (field.name == "lazy") {
                testCategory.add(SwitchPreferenceCompat(prefContext)) {
                    key = field.name
                    setTitle(field.title)
                    setSummary(R.string.px_gt_lazy_summary)
                    isPersistent = false
                    isEnabled = enabled
                    isChecked = value as? Boolean ?: true
                    setOnPreferenceChangeListener { _, on ->
                        save(field, (on as Boolean).toString())
                        false
                    }
                }
                continue
            }
            testCategory.add(Preference(prefContext)) {
                key = field.name
                setTitle(titleOf(field))
                summary = label(context, field, value, own)
                isEnabled = enabled
                setOnPreferenceClickListener {
                    edit(field, value)
                    true
                }
            }
        }
    }

    /** The name of [field] on this page: an adaptive group probes with its address. */
    private fun titleOf(field: Field): Int =
        if (group?.adaptive == true && field.name == "url") R.string.px_ad_custom_probe_url else field.title

    /** The value this group's variants use for [field]: its own, else its kind's. */
    private fun effective(g: CustomGroup, field: String): Any? {
        // An adaptive group probes the address its fallback variant would test.
        val kind = when {
            field == "strategy" -> "load-balance"
            g.adaptive -> "fallback"
            else -> "auto"
        }
        return g.test[kind]?.get(field)
    }

    private fun label(context: Context, field: Field, value: Any?, own: Boolean): String {
        val text = when {
            value == null -> getString(R.string.px_opt_core_default)
            field.name == "interval" -> resources.getQuantityString(
                R.plurals.px_opt_seconds,
                (value as Number).toInt(),
                (value as Number).toInt(),
            )
            field.name == "tolerance" || field.name == "timeout" -> getString(R.string.px_gt_ms, (value as Number).toLong())
            field.name == "strategy" -> strategyLabel(value.toString())
            else -> value.toString()
        }
        return if (own) text else getString(R.string.px_custom_test_follows, text)
    }

    private fun strategyLabel(value: String): String = when (value) {
        "round-robin" -> getString(R.string.px_gt_strategy_round_robin)
        "sticky-sessions" -> getString(R.string.px_gt_strategy_sticky)
        else -> getString(R.string.px_gt_strategy_hashing)
    }

    private fun edit(field: Field, value: Any?) {
        when (field.name) {
            "strategy" -> showDialog("px-custom-strategy") { d ->
                val values = GroupTestFragment.STRATEGIES
                val labels = values.map { strategyLabel(it) } + getString(R.string.px_custom_test_default)
                AlertDialog.Builder(d.context)
                    .setTitle(field.title)
                    .setItems(labels.toTypedArray()) { _, which ->
                        save(field, values.getOrNull(which) ?: DEFAULT)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
            }
            "url" -> askText(
                field, value?.toString(), R.string.px_gt_url_hint,
                if (group?.adaptive == true) R.string.px_ad_custom_probe_url_message else R.string.px_gt_url_message,
            ) {
                if (KeysFragment.isTestUrl(it)) it else null
            }
            else -> askText(field, (value as? Number)?.toLong()?.toString(), field.hint, field.message) { text ->
                val n = text.toLongOrNull()
                if (n != null && n >= field.min && n <= field.max) text else null
            }
        }
    }

    /** The value typed in; [check] returns it as the manager takes it, or null. */
    private fun askText(field: Field, current: String?, hint: Int, message: Int, @OutlivesPage check: (String) -> String?) =
        showDialog("px-custom-value") { d -> textDialog(d.context, field, current, hint, message, check) }

    private fun textDialog(context: Context, field: Field, current: String?, hint: Int, message: Int, check: (String) -> String?): Dialog {
        val input = formField(context, hint).apply {
            id = R.id.px_custom_value
            inputType = if (field.name == "url") {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            setText(current ?: "")
        }
        val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
        form.addView(numberUnit(hint)?.let { withUnit(context, input, getString(it)) } ?: input)
        val dialog = AlertDialog.Builder(context)
            .setTitle(titleOf(field))
            .setMessage(message)
            .setView(form)
            .setPositiveButton(R.string.px_action_save_apply, null)
            .setNeutralButton(R.string.px_custom_test_default) { _, _ -> save(field, DEFAULT) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = check(input.text.toString().trim())
                if (value == null) {
                    input.error = getString(R.string.px_edit_invalid)
                } else {
                    dialog.dismiss()
                    save(field, value)
                }
            }
        }
        input.requestFocus()
        return dialog
    }

    /** Saves one parameter of this group and applies the configuration. */
    private fun save(field: Field, value: String) {
        val label = getString(titleOf(field))
        start(getString(R.string.px_core_changing, label)) {
            step(context.getString(R.string.px_step_saving))
            if (field.name == "url") {
                ProxyCommands.setGroupTestUrl(groupKey, value.takeIf { it != DEFAULT })
            } else {
                ProxyCommands.setGroupTest(groupKey, field.name, value)
            }
            applyConfig()
            summary(context.getString(R.string.px_core_changed, label))
        }
    }

    /** One adaptive group in place of the three variants, or back: says what changes, then applies it. */
    private fun askAdaptive(on: Boolean) {
        val adaptiveGroup = group?.adaptiveGroup ?: return
        confirm(
            "px-custom-adaptive",
            if (on) R.string.px_ad_custom_on_title else R.string.px_ad_custom_off_title,
            {
                if (on) getString(R.string.px_ad_custom_on_confirm, groupKey, adaptiveGroup) else getString(R.string.px_ad_custom_off_confirm, groupKey)
            },
            if (on) R.string.px_ad_custom_on_action else R.string.px_ad_custom_off_action,
        ) {
            val title = getString(if (on) R.string.px_ad_custom_turning_on else R.string.px_ad_custom_turning_off, groupKey)
            start(title) {
                step(context.getString(R.string.px_step_saving))
                ProxyManager.setAdaptive(groupKey, on)
                applyConfig()
                summary(context.getString(if (on) R.string.px_ad_custom_turned_on else R.string.px_ad_custom_turned_off, groupKey))
            }
        }
    }

    private fun askRemove() {
        confirm("px-custom-remove", R.string.px_custom_remove, { getString(R.string.px_custom_remove_confirm, groupKey) }, R.string.px_action_remove) {
            start(getString(R.string.px_custom_removing, groupKey)) {
                step(context.getString(R.string.px_step_saving))
                ProxyManager.removeCustomGroup(groupKey)
                applyConfig()
                summary(context.getString(R.string.px_custom_removed, groupKey))
            }
            // The group is gone: its page with it (the list shows the task).
            requireActivity().finish()
        }
    }

    private fun start(title: String, @OutlivesPage work: suspend ProxyTasks.Scope.() -> Unit) {
        if (!ProxyTasks.start(requireContext(), CustomGroupsFragment.KEY, title, work)) {
            Toast.makeText(requireContext(), R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
        }
        showTask()
    }

    private fun showTask() {
        val context = context ?: return
        val task = ProxyTasks[CustomGroupsFragment.KEY]
        taskPref.isVisible = task != null
        if (task != null) {
            taskPref.title = task.title
            taskPref.summary = taskStatus(context, task)
        }
    }

    /** One test parameter of a group, with the limits the manager checks. */
    private class Field(
        val name: String,
        val title: Int,
        val hint: Int = 0,
        val message: Int = 0,
        val min: Long = 0,
        val max: Long = 0,
    )

    companion object {
        private const val ARG_KEY = "group"
        private const val DEFAULT = "default"

        private val FIELDS = listOf(
            Field("url", R.string.px_gt_url, R.string.px_gt_url_hint, R.string.px_gt_url_message),
            Field("interval", R.string.px_gt_interval, R.string.px_opt_seconds_hint, R.string.px_gt_interval_message, 10, 86_400),
            Field("tolerance", R.string.px_gt_tolerance, R.string.px_gt_ms_hint, R.string.px_gt_tolerance_message, 0, 10_000),
            Field("timeout", R.string.px_gt_timeout, R.string.px_gt_ms_hint, R.string.px_gt_timeout_message, 100, 60_000),
            Field("lazy", R.string.px_gt_lazy),
            Field("strategy", R.string.px_gt_strategy),
        )

        fun open(context: Context, key: String) {
            Pages.open(
                context,
                CustomGroupFragment::class.java,
                R.string.px_custom_group_title,
                arguments = Bundle().apply { putString(ARG_KEY, key) },
            )
        }
    }
}
