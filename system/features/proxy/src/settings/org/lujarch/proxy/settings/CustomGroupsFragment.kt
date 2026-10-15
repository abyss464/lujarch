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
import com.android.settingslib.widget.ZeroStatePreference
import kotlinx.coroutines.Job
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * Custom groups: node groups made by filters (country, line type, a text in
 * the node name) with an optional group to fall back to. Rules and per-app
 * routes can target them. A group's own page holds its test parameters and
 * removes it; adding or removing one applies the configuration.
 */
class CustomGroupsFragment : BasePreferenceFragment() {
    private lateinit var taskPref: Preference
    private lateinit var listCategory: PreferenceCategory
    private lateinit var emptyPref: ZeroStatePreference
    private var groups: List<CustomGroup> = emptyList()
    private var loadJob: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val top = screen.add(PreferenceCategory(prefContext)) { key = "top" }
        top.add(Preference(prefContext)) {
            key = "add"
            setTitle(R.string.px_custom_add)
            setIcon(R.drawable.ic_px_add)
            setOnPreferenceClickListener {
                askGroup()
                true
            }
        }
        taskPref = top.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                TaskFragment.open(requireContext(), KEY)
                true
            }
        }
        listCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "groups"
            setTitle(R.string.px_custom_title)
            isVisible = false
        }
        emptyPref = screen.add(ZeroStatePreference(prefContext)) {
            key = "empty"
            setIcon(R.drawable.ic_groups)
            setTitle(PagesR.string.loading)
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_custom_footer)
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
            groups = attempt("groups", R.string.px_error_load_custom) { ProxyManager.customGroups() } ?: return@launchUi
            render()
        }
    }

    private fun render() {
        val context = context ?: return
        listCategory.removeAll()
        for (g in groups) {
            listCategory.add(Preference(prefContext)) {
                key = "group:" + g.key
                title = g.key
                summary = ProxyFormat.join(
                    context,
                    listOf(
                        g.country,
                        ProxyFormat.netTypeLabel(context, g.netType),
                        g.nameContains?.let { getString(R.string.px_custom_name_contains, it) },
                        g.nodes?.let { resources.getQuantityString(R.plurals.px_nodes, it, it) },
                        if (g.adaptive) getString(R.string.px_type_adaptive) else null,
                        g.fallbackTo?.let { getString(R.string.px_custom_fallback_to, ProxyFormat.targetLabel(context, it)) },
                        g.testSet.keys.takeIf { it.isNotEmpty() }?.let { getString(R.string.px_custom_own_test) },
                    ),
                )
                setOnPreferenceClickListener {
                    CustomGroupFragment.open(requireContext(), g.key)
                    true
                }
            }
        }
        listCategory.isVisible = groups.isNotEmpty()
        emptyPref.setTitle(R.string.px_custom_empty)
        emptyPref.isVisible = groups.isEmpty()
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

    private fun start(title: String, @OutlivesPage work: suspend ProxyTasks.Scope.() -> Unit) {
        if (!ProxyTasks.start(requireContext(), KEY, title, work)) {
            Toast.makeText(requireContext(), R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
        }
        showTask()
    }

    /** The groups the page showed are the dialog's (a recreated page checks the new key against them). */
    private fun askGroup() {
        val existing = groups.map { it.key }
        showDialog("px-custom-add") { d -> groupDialog(d.context, existing) }
    }

    private fun groupDialog(context: Context, existing: List<String>): Dialog {
        val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
        val key = formField(context, R.string.px_custom_key_hint).apply { id = R.id.px_custom_key }
        val country = formField(context, R.string.px_manual_country_hint).apply {
            id = R.id.px_manual_country
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val contains = formField(context, R.string.px_custom_contains_hint).apply { id = R.id.px_custom_contains }
        val fallback = formField(context, R.string.px_custom_fallback_hint).apply { id = R.id.px_custom_fallback }
        form.addView(key)
        form.addView(country)
        form.addView(TextView(context).apply {
            setText(R.string.px_manual_net_type)
            setPadding(0, 24, 0, 0)
        })
        val types = listOf(null, "residential", "datacenter", "starlink")
        val typeIds = listOf(R.id.px_custom_any_type, R.id.px_manual_residential, R.id.px_manual_datacenter, R.id.px_manual_starlink)
        val typeGroup = RadioGroup(context)
        types.forEachIndexed { i, t ->
            typeGroup.addView(RadioButton(context).apply {
                id = typeIds[i]
                text = ProxyFormat.netTypeLabel(context, t) ?: getString(R.string.px_custom_any_type)
            })
        }
        typeGroup.check(typeIds[0])
        form.addView(typeGroup)
        form.addView(contains)
        form.addView(fallback)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.px_custom_add)
            .setMessage(R.string.px_custom_add_message)
            .setView(form)
            .setPositiveButton(R.string.px_action_save_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val k = key.text.toString().trim()
                val cc = country.text.toString().trim().uppercase().ifEmpty { null }
                val text = contains.text.toString().trim().ifEmpty { null }
                val fb = fallback.text.toString().trim().ifEmpty { null }
                val type = types[typeIds.indexOf(typeGroup.checkedRadioButtonId).coerceIn(0, types.size - 1)]
                when {
                    !isGroupKey(k) -> key.error = getString(R.string.px_custom_key_invalid)
                    k in existing -> key.error = getString(R.string.px_custom_exists, k)
                    cc != null && (cc.length != 2 || !cc.all { it in 'A'..'Z' }) ->
                        country.error = getString(R.string.px_manual_country_invalid)
                    text != null && (text.startsWith("-") || text.toByteArray().size > 64) ->
                        contains.error = getString(R.string.px_custom_contains_invalid)
                    fb != null && !AppRoutingClient.isUsableTarget(fb) ->
                        fallback.error = getString(R.string.px_custom_fallback_invalid)
                    cc == null && text == null && type == null ->
                        key.error = getString(R.string.px_custom_needs_filter)
                    else -> {
                        dialog.dismiss()
                        val group = CustomGroup(k, text, cc, type, fb, null)
                        start(getString(R.string.px_custom_adding, k)) {
                            step(this.context.getString(R.string.px_step_saving))
                            ProxyManager.addCustomGroup(group)
                            applyConfig()
                            summary(this.context.getString(R.string.px_custom_added, k))
                        }
                    }
                }
            }
        }
        key.requestFocus()
        return dialog
    }

    companion object {
        const val KEY = "custom-groups"

        /** A key the proxy daemon and the manager accept: a routing target without '/', ':', ',' or spaces. */
        fun isGroupKey(key: String): Boolean =
            AppRoutingClient.isUsableTarget(key) && key.toByteArray(Charsets.UTF_8).size <= 64 &&
                key.none { it == '/' || it == ':' } && key !in listOf("DIRECT", "REJECT", "all")
    }
}

