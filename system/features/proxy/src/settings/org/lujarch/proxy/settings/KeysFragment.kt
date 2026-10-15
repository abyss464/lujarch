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
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Job
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog

/**
 * Base of the pages that show and change the proxy core's settings
 * (`config get --json` keys): each row is bound to one key, shows its
 * effective value, and a change saves it and applies the configuration as a
 * proxy task (key [taskKey]). Values the phone recommends ([riskKeys]) say
 * what a change breaks before it is made and can be restored in one tap;
 * a value an older system service still forces is read-only with its reason.
 */
abstract class KeysFragment : BasePreferenceFragment() {
    protected var config: CoreConfig? = null
    private lateinit var taskPref: Preference
    private lateinit var riskPref: Preference
    private val renderers = ArrayList<(CoreConfig) -> Unit>()
    private val editable = ArrayList<Preference>()

    /**
     * The number rows' settings by key, made with the page's rows: a number
     * dialog built again for a recreated page takes them (and their checks)
     * from that page.
     */
    private val numberRows = HashMap<String, NumberRow>()

    private class NumberRow(
        val title: Int,
        val min: Long,
        val max: Long,
        val hint: Int,
        val optional: Boolean,
        val message: Int?,
        val orZero: Boolean,
        val check: ((Long) -> String?)?,
        val defaultValue: Long?,
    )
    private var loadJob: Job? = null

    /** The proxy task these pages share. */
    protected open val taskKey: String = CoreSettingsFragment.KEY

    /**
     * The keys of this page whose value the phone recommends: while one of
     * them is set to something else, a row at the top says so and restores
     * every recommended value.
     */
    protected open val riskKeys: List<String> = emptyList()

    /** Adds the page's rows (below the task row). */
    protected abstract fun buildRows(screen: PreferenceScreen, savedInstanceState: Bundle?)

    final override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val top = screen.add(PreferenceCategory(prefContext)) { key = "task_category" }
        riskPref = top.add(Preference(prefContext)) {
            key = "risk"
            order = -1
            setIcon(R.drawable.ic_error)
            isVisible = false
            setOnPreferenceClickListener {
                askRestoreRecommended()
                true
            }
        }
        taskPref = top.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                TaskFragment.open(requireContext(), taskKey)
                true
            }
        }
        buildRows(screen, savedInstanceState)
        setEditable(false)
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
                    setEditable(!running && config != null)
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
            config = attempt("settings", R.string.px_error_load_settings) { ProxyCommands.coreConfig() } ?: return@launchUi
            render()
        }
    }

    protected fun render() {
        val c = config ?: return
        if (context == null) return
        for (r in renderers) r(c)
        showRisk(c)
        setEditable(ProxyTasks.running() == null)
    }

    // ---- values the phone recommends ----

    /** The page's risky keys that are not at their recommended value. */
    private fun deviations(c: CoreConfig): List<CoreKey> = c.deviations.filter { it.name in riskKeys }

    private fun showRisk(c: CoreConfig) {
        val list = deviations(c)
        riskPref.isVisible = list.isNotEmpty()
        if (list.isEmpty()) return
        riskPref.title = resources.getQuantityString(R.plurals.px_risk_note, list.size, list.size)
        val names = list.joinToString(getString(R.string.px_list_separator)) { getString(riskTitle(it.name)) }
        riskPref.summary = getString(R.string.px_risk_note_summary, names)
    }

    /** Names every setting away from its recommendation with what it breaks, and restores them. */
    private fun askRestoreRecommended() {
        val c = config ?: return
        val list = deviations(c)
        if (list.isEmpty()) return
        showDialog("px-risk-restore") { d ->
            val text = list.joinToString("\n\n") {
                getString(R.string.px_risk_entry, getString(riskTitle(it.name)), riskText(d.context, it.risk))
            }
            AlertDialog.Builder(d.context)
                .setTitle(resources.getQuantityString(R.plurals.px_risk_note, list.size, list.size))
                .setMessage(text + "\n\n" + getString(R.string.px_risk_restore_message))
                .setPositiveButton(R.string.px_risk_restore) { _, _ -> restoreRecommended() }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun restoreRecommended() {
        startAction(getString(R.string.px_risk_restore), getString(R.string.px_risk_restored)) {
            ProxyCommands.restoreRecommended()
        }
    }

    /**
     * A switch for a value the phone recommends: turning it away from the
     * recommendation says what that breaks and asks first. An older system
     * service that still forces the value shows it read-only with its reason.
     */
    protected fun riskySwitchRow(group: PreferenceCategory, key: String, title: Int, summary: Int) {
        val pref = group.add(SwitchPreferenceCompat(prefContext)) {
            this.key = key
            setTitle(title)
            setSummary(summary)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                val on = value as Boolean
                val k = config?.get(key)
                if (k?.recommended == on) {
                    // Back to the recommendation: the key goes, so the profile fills it in.
                    change(key, if (k?.optional == true) DEFAULT else on.toString(), getString(title))
                } else {
                    switchRisky(key, title, on)
                }
                false
            }
        }
        bindRisky(pref, key) { c -> pref.isChecked = c.bool(key) ?: true }
    }

    /**
     * A row opening the editor of a list the phone recommends (the DNS
     * redirection): the warning comes before the editor opens.
     */
    protected fun riskyListRow(group: PreferenceCategory, key: String, title: Int, kind: ListEditFragment.Kind) {
        val pref = group.add(Preference(prefContext)) {
            this.key = key
            setTitle(title)
            setOnPreferenceClickListener {
                if (config?.get(key)?.deviates == true) ListEditFragment.open(requireContext(), key, title, kind) else openRiskyList(key, title, kind)
                true
            }
        }
        bindRisky(pref, key) { c ->
            pref.summary = c.list(key).joinToString(getString(R.string.px_list_separator))
                .ifEmpty { getString(R.string.px_core_dns_none) }
        }
    }

    /** Renders a risky row: its value, the recommendation while it differs, the legacy reason. */
    private fun bindRisky(pref: Preference, key: String, render: (CoreConfig) -> Unit) {
        editable += pref
        onRender { c ->
            val k = c[key]
            render(c)
            val value = pref.summary ?: ""
            if (k?.forced != null) {
                // A system service of an older image sets the value itself.
                pref.isSelectable = false
                pref.summary = getString(R.string.px_forced_value, value, riskText(requireContext(), k.forced))
            } else if (k?.deviates == true) {
                pref.summary = getString(R.string.px_risk_value, value, recommendedText(k))
            }
        }
    }

    /** The recommended value of a risky key in words. */
    private fun recommendedText(k: CoreKey): String = when (val r = k.recommended) {
        is Boolean -> onOff(r)
        is List<*> -> r.joinToString(getString(R.string.px_list_separator)) { e ->
            (e as? Map<*, *>)?.let { m -> "${m["scheme"]}://${m["address"]}" } ?: e.toString()
        }
        else -> r?.toString() ?: ""
    }

    private fun switchRisky(key: String, title: Int, on: Boolean) = askRisky(key, title) { change(key, on.toString(), getString(title)) }

    private fun openRiskyList(key: String, title: Int, kind: ListEditFragment.Kind) =
        askRisky(key, title) { ListEditFragment.open(requireContext(), key, title, kind) }

    private fun askRisky(key: String, title: Int, @RebuiltWithPage onConfirm: KeysFragment.() -> Unit) {
        val reason = config?.get(key)?.risk
        showDialog("px-risk") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(title)
                .setMessage(riskText(d.context, reason) + "\n\n" + getString(R.string.px_risk_confirm_tail))
                .setPositiveButton(R.string.px_risk_change_anyway) { _, _ -> onConfirm() }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    protected fun onOff(v: Boolean?): String = getString(if (v == true) R.string.px_opt_on else R.string.px_opt_off)

    private fun setEditable(enabled: Boolean) {
        for (p in editable) p.isEnabled = enabled
    }

    private fun showTask() {
        val context = context ?: return
        val task = ProxyTasks[taskKey]
        taskPref.isVisible = task != null
        if (task != null) {
            taskPref.title = task.title
            taskPref.summary = taskStatus(context, task)
        }
    }

    /** Registers [block] to run with every loaded configuration. */
    protected fun onRender(block: (CoreConfig) -> Unit) {
        renderers += block
    }

    // ---- rows ----

    /**
     * A switch for a boolean key. [default] is the core's value while the
     * config leaves the key out. [confirmOn]/[confirmOff] ask first.
     */
    protected fun switchRow(
        group: PreferenceCategory,
        key: String,
        title: Int,
        summary: Int?,
        default: Boolean,
        confirmOn: Int? = null,
        confirmOff: Int? = null,
    ): SwitchPreferenceCompat {
        val pref = group.add(SwitchPreferenceCompat(prefContext)) {
            this.key = key
            setTitle(title)
            summary?.let { setSummary(it) }
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                val on = value as Boolean
                val ask = if (on) confirmOn else confirmOff
                if (ask != null) {
                    confirmSwitch(key, title, ask, on)
                } else {
                    change(key, on.toString(), getString(title))
                }
                false
            }
        }
        bind(pref, key) { c ->
            pref.isChecked = c.bool(key) ?: default
        }
        return pref
    }

    private fun confirmSwitch(key: String, title: Int, ask: Int, on: Boolean) = confirm(
        "px-switch", title, { getString(ask) }, if (on) R.string.px_action_turn_on else R.string.px_action_turn_off,
    ) {
        change(key, on.toString(), getString(title))
    }

    /**
     * A choice among [values] ([labels] in words). An optional key gets a
     * first entry "Core default" ([defaultLabel] says what that is).
     */
    protected fun listRow(
        group: PreferenceCategory,
        key: String,
        title: Int,
        values: List<String>,
        labels: List<String>,
        defaultLabel: String? = null,
        summaries: List<String>? = null,
    ): ListPreference {
        val allValues = if (defaultLabel != null) listOf(DEFAULT) + values else values
        val allLabels = if (defaultLabel != null) listOf(defaultLabel) + labels else labels
        val pref = group.add(ListPreference(prefContext)) {
            this.key = key
            setTitle(title)
            setDialogTitle(title)
            entries = allLabels.toTypedArray()
            entryValues = allValues.toTypedArray()
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                change(key, value as String, getString(title))
                false
            }
        }
        bind(pref, key) { c ->
            val v = c[key]?.let { k -> if (k.set || defaultLabel == null) c.string(key) else null }
            val current = v?.takeIf { it in values } ?: if (defaultLabel != null) DEFAULT else values.firstOrNull()
            pref.value = current
            val i = allValues.indexOf(current)
            val label = allLabels.getOrNull(i) ?: v ?: ""
            pref.summary = summaries?.getOrNull(values.indexOf(current))?.let { getString(R.string.px_opt_value_detail, label, it) } ?: label
        }
        return pref
    }

    /**
     * A whole number (seconds, bytes, hours: [format] shows it) typed in a
     * dialog; optional keys can go back to the core's default. [message]
     * says in the dialog what the number decides. [orZero]: 0 (off) is
     * taken besides [min] to [max], as the manager takes it.
     */
    protected fun numberRow(
        group: PreferenceCategory,
        key: String,
        title: Int,
        min: Long,
        max: Long,
        hint: Int,
        format: (Long) -> String,
        defaultText: String? = null,
        message: Int? = null,
        orZero: Boolean = false,
        check: ((Long) -> String?)? = null,
        defaultValue: Long? = null,
    ): Preference {
        numberRows[key] = NumberRow(title, min, max, hint, defaultText != null, message, orZero, check, defaultValue)
        val pref = group.add(Preference(prefContext)) {
            this.key = key
            setTitle(title)
            setOnPreferenceClickListener {
                askNumber(key)
                true
            }
        }
        bind(pref, key) { c ->
            pref.summary = c.number(key)?.let(format) ?: defaultText ?: ""
        }
        return pref
    }

    /**
     * A row for a URL key (a group's test address, a geodata download): the
     * address is typed in a dialog whose neutral button goes back to the
     * default. [defaultLabel] is the summary while the key has no value.
     */
    protected fun urlRow(
        group: PreferenceCategory,
        key: String,
        title: Int,
        message: Int,
        hint: Int,
        defaultLabel: () -> String,
    ): Preference = pageRow(group, key, title, summary = { c -> c.url(key) ?: defaultLabel() }) {
        askUrl(key, title, message, hint)
    }

    private fun askUrl(key: String, title: Int, message: Int, hint: Int) {
        val current = config?.url(key) ?: ""
        showDialog("px-url") { d -> urlDialog(d.context, key, title, message, hint, current) }
    }

    private fun urlDialog(context: Context, key: String, title: Int, message: Int, hint: Int, current: String): Dialog {
        val input = formField(context, hint).apply {
            id = R.id.px_key_url
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(current)
        }
        val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
        form.addView(input)
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(form)
            .setPositiveButton(R.string.px_action_save_apply, null)
            .setNeutralButton(R.string.px_opt_use_default) { _, _ -> changeJson(key, null, getString(title)) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                if (!isTestUrl(url)) {
                    input.error = getString(R.string.px_geo_url_invalid)
                } else {
                    dialog.dismiss()
                    changeJson(key, url, getString(title))
                }
            }
        }
        input.requestFocus()
        return dialog
    }

    /** A row opening another page. */
    protected fun pageRow(group: PreferenceCategory, key: String, title: Int, summary: (CoreConfig) -> String, open: () -> Unit): Preference {
        val pref = group.add(Preference(prefContext)) {
            this.key = key
            setTitle(title)
            setOnPreferenceClickListener {
                open()
                true
            }
        }
        onRender { c -> pref.summary = summary(c) }
        return pref
    }

    private fun bind(pref: Preference, key: String, render: (CoreConfig) -> Unit) {
        editable += pref
        onRender { c ->
            // A system service of an older image sets a few values itself.
            val forced = c[key]?.forced
            render(c)
            if (forced != null) {
                pref.isSelectable = false
                pref.summary = getString(R.string.px_forced_value, pref.summary ?: "", riskText(requireContext(), forced))
            }
        }
    }

    // ---- changes ----

    private fun askNumber(key: String) {
        val current = config?.number(key)
        showDialog("px-number") { d ->
            val row = numberRows[key] ?: return@showDialog null
            numberDialog(d.context, key, row, current)
        }
    }

    private fun numberDialog(context: Context, key: String, row: NumberRow, current: Long?): Dialog {
        val title = getString(row.title)
        val min = row.min
        val max = row.max
        val hint = row.hint
        val optional = row.optional
        val message = row.message
        val orZero = row.orZero
        val check = row.check
        val defaultValue = row.defaultValue
        val input = formField(context, hint).apply {
            id = R.id.px_key_number
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        current?.let { input.setText(it.toString()) }
        val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
        form.addView(numberUnit(hint)?.let { withUnit(context, input, getString(it)) } ?: input)
        val range = getString(if (orZero) R.string.px_opt_number_range_or_zero else R.string.px_opt_number_range, min, max)
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message?.let { getString(it) + "\n\n" + range } ?: range)
            .setView(form)
            .setPositiveButton(R.string.px_action_save_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (optional) builder.setNeutralButton(R.string.px_opt_use_default, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = input.text.toString().trim().toLongOrNull()
                val conflict = n?.let { check?.invoke(it) }
                if (n == null || (n < min && !(orZero && n == 0L)) || n > max) {
                    input.error = range
                } else if (conflict != null) {
                    // A rule between keys, which the manager would refuse.
                    input.error = conflict
                } else {
                    dialog.dismiss()
                    change(key, n.toString(), title)
                }
            }
            if (optional) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val conflict = defaultValue?.let { check?.invoke(it) }
                    if (conflict != null) {
                        input.error = conflict
                    } else {
                        dialog.dismiss()
                        change(key, DEFAULT, title)
                    }
                }
            }
        }
        input.requestFocus()
        return dialog
    }

    /** Saves one scalar key and applies the configuration. */
    protected fun change(key: String, value: String, label: String) {
        startChange(label) { ProxyCommands.set(key, value) }
    }

    /** Saves one JSON key (a list, servers, policy, protocols, a URL) and applies the configuration. */
    protected fun changeJson(key: String, value: Any?, label: String) {
        startChange(label) { ProxyCommands.setJson(key, value) }
    }

    protected fun startChange(label: String, @OutlivesPage save: suspend () -> Unit) {
        startAction(
            getString(R.string.px_core_changing, label),
            getString(R.string.px_core_changed, label),
            save,
        )
    }

    /** A change with a title of its own, saved and applied like [startChange]. */
    protected fun startAction(title: String, done: String, @OutlivesPage save: suspend () -> Unit) {
        val context = requireContext()
        val started = ProxyTasks.start(context, taskKey, title) {
            step(this.context.getString(R.string.px_step_saving))
            save()
            applyConfig()
            summary(done)
        }
        if (!started) Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
        showTask()
    }

    companion object {
        const val DEFAULT = "default"

        /** What a value away from the phone's recommendation breaks, in words. */
        fun riskText(context: Context, reason: String?): String = context.getString(
            when (reason) {
                "tun-only" -> R.string.px_risk_tun_only
                "tun-routes" -> R.string.px_risk_tun_routes
                "tun-loop" -> R.string.px_risk_tun_loop
                "dns-hijack" -> R.string.px_risk_dns_hijack
                "dns-resolver" -> R.string.px_risk_dns_resolver
                else -> R.string.px_forced_other
            },
        )

        /** A first check of an http(s) address; the manager and the core check again. */
        fun isTestUrl(url: String): Boolean =
            (url.startsWith("https://") || url.startsWith("http://")) && url.length >= 10 && url.none { it.isWhitespace() }

        /** The name of a recommended value's setting, as its own page shows it. */
        fun riskTitle(key: String): Int = when (key) {
            "tun.enable" -> R.string.px_tun_enable
            "tun.auto-route" -> R.string.px_tun_auto_route
            "tun.auto-detect-interface" -> R.string.px_tun_detect
            "tun.dns-hijack" -> R.string.px_tun_hijack
            else -> R.string.px_dnsc_enable
        }
    }
}
