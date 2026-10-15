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

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog

/**
 * DNS servers for particular names (nameserver-policy), in the order the
 * core tries them: add a name pattern with its servers, move an entry, give
 * it new servers, or remove it (asks first). Stored servers are shown as
 * kind, host and group; an entry that stays goes back by its position.
 */
class PolicyFragment : KeysFragment() {
    private lateinit var entriesCategory: PreferenceCategory

    /** An entry: a kept one by position, or a new pattern with servers as text. */
    private sealed class Entry {
        data class Kept(val index: Int, val entry: PolicyEntry) : Entry()
        data class New(val pattern: String, val servers: List<String>) : Entry()
    }

    override fun buildRows(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val actions = screen.add(PreferenceCategory(prefContext)) { key = "actions" }
        actions.add(Preference(prefContext)) {
            key = "add"
            setTitle(R.string.px_pol_add)
            setIcon(R.drawable.ic_px_add)
            setOnPreferenceClickListener {
                askAdd()
                true
            }
        }
        entriesCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "entries"
            setTitle(R.string.px_core_dns_rules_policy)
        }
        onRender { renderEntries() }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_pol_footer)
            order = Int.MAX_VALUE
        }
    }

    private fun entries(): List<Entry> = config?.policy().orEmpty().mapIndexed { i, e -> Entry.Kept(i, e) }

    private fun pattern(e: Entry) = when (e) {
        is Entry.Kept -> e.entry.pattern
        is Entry.New -> e.pattern
    }

    private fun renderEntries() {
        val context = requireContext()
        val list = entries()
        entriesCategory.removeAll()
        if (list.isEmpty()) {
            entriesCategory.add(Preference(prefContext)) {
                key = "none"
                setTitle(R.string.px_edit_none)
                isSelectable = false
            }
        }
        list.forEachIndexed { i, e ->
            entriesCategory.add(Preference(prefContext)) {
                key = "entry:$i"
                title = pattern(e)
                summary = (e as Entry.Kept).entry.servers.joinToString("\n") { DnsFragment.serverLabel(context, it) }
                isEnabled = ProxyTasks.running() == null
                setOnPreferenceClickListener {
                    actions(list, i)
                    true
                }
            }
        }
    }

    /** Adds to the list the page showed (a recreated page answers before its own list has loaded). */
    private fun askAdd() {
        val list = entries()
        ask(null) { pattern, servers -> save(list + Entry.New(pattern, servers), getString(R.string.px_edit_adding, pattern)) }
    }

    /** The list as the page showed it is the dialog's: its actions change that list. */
    private fun actions(list: List<Entry>, i: Int) = showDialog("px-policy-entry") { d ->
        val e = list[i]
        val name = pattern(e)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (i > 0) items += getString(R.string.px_edit_move_up) to {
            save(list.toMutableList().apply { add(i - 1, removeAt(i)) }, getString(R.string.px_edit_moving, name))
        }
        if (i < list.size - 1) items += getString(R.string.px_edit_move_down) to {
            save(list.toMutableList().apply { add(i + 1, removeAt(i)) }, getString(R.string.px_edit_moving, name))
        }
        items += getString(R.string.px_pol_change_servers) to {
            ask(name) { pattern, servers -> save(list.toMutableList().apply { set(i, Entry.New(pattern, servers)) }, getString(R.string.px_edit_changing, name)) }
        }
        items += getString(R.string.px_edit_remove) to {
            confirm("px-policy-remove", R.string.px_edit_remove, { getString(R.string.px_edit_remove_confirm, name) }, R.string.px_action_remove) {
                save(list.toMutableList().apply { removeAt(i) }, getString(R.string.px_edit_removing, name))
            }
        }
        AlertDialog.Builder(d.context)
            .setTitle(name)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun save(list: List<Entry>, label: String) {
        val value = list.map {
            when (it) {
                is Entry.Kept -> mapOf("keep" to it.index)
                is Entry.New -> mapOf("pattern" to it.pattern, "servers" to it.servers)
            }
        }
        changeJson("dns.nameserver-policy", if (value.isEmpty()) null else value, label)
    }

    /** A name pattern (fixed when [pattern] is given) and its servers, one per line. */
    private fun ask(pattern: String?, @RebuiltWithPage onDone: PolicyFragment.(String, List<String>) -> Unit) = showDialog("px-policy") { d ->
        val context = d.context
        val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
        val patternInput = formField(context, R.string.px_pol_pattern_hint).apply {
            id = R.id.px_policy_pattern
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (pattern != null) {
                setText(pattern)
                isEnabled = false
            }
        }
        val serversInput = formField(context, R.string.px_edit_server_hint).apply {
            id = R.id.px_policy_servers
            isSingleLine = false
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        form.addView(patternInput)
        form.addView(serversInput)
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (pattern == null) R.string.px_pol_add else R.string.px_pol_change_servers)
            .setMessage(R.string.px_pol_message)
            .setView(form)
            .setPositiveButton(R.string.px_action_save_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = patternInput.text.toString().trim()
                val servers = serversInput.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                when {
                    p.isEmpty() || p.any { it.isISOControl() } -> patternInput.error = getString(R.string.px_edit_invalid)
                    servers.isEmpty() || servers.any { !ListEditFragment.valid(ListEditFragment.Kind.SERVERS, it) } ->
                        serversInput.error = getString(R.string.px_edit_invalid)
                    else -> {
                        dialog.dismiss()
                        onDone(p, servers)
                    }
                }
            }
        }
        (if (pattern == null) patternInput else serversInput).requestFocus()
        dialog
    }
}
