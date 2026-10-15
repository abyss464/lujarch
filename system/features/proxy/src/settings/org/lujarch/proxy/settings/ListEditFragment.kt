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
import android.text.InputType
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog

/**
 * One list setting of the core (DNS servers, domains, addresses, GeoSite
 * categories): its entries in order, add one, and per entry move it up or
 * down, change it or remove it (asks first). DNS servers are shown as kind,
 * host and group only (a DoH path can hold an account id): a kept server
 * goes back to the manager by its position, never as text. Every change is
 * saved and applied at once.
 */
class ListEditFragment : KeysFragment() {
    enum class Kind { SERVERS, DOMAINS, ADDRESSES, WORDS, HIJACK }

    private lateinit var settingKey: String
    private lateinit var kind: Kind
    private var titleRes = 0
    private lateinit var entriesCategory: PreferenceCategory

    /** An entry as the manager takes it back: a kept server by position, or text. */
    private sealed class Entry {
        data class Kept(val index: Int, val server: DnsServer) : Entry()
        data class Text(val text: String) : Entry()
    }

    override fun buildRows(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val args = requireArguments()
        settingKey = args.getString(ARG_KEY) ?: ""
        kind = Kind.valueOf(args.getString(ARG_KIND) ?: Kind.WORDS.name)
        titleRes = args.getInt(ARG_TITLE)
        val actions = screen.add(PreferenceCategory(prefContext)) { key = "actions" }
        actions.add(Preference(prefContext)) {
            this.key = "add"
            setTitle(R.string.px_edit_add)
            setIcon(R.drawable.ic_px_add)
            setOnPreferenceClickListener {
                askAdd()
                true
            }
        }
        actions.add(Preference(prefContext)) {
            this.key = "default"
            setTitle(R.string.px_edit_default)
            setSummary(R.string.px_edit_default_summary)
            setIcon(R.drawable.ic_restore)
            setOnPreferenceClickListener {
                askDefault()
                true
            }
        }
        entriesCategory = screen.add(PreferenceCategory(prefContext)) {
            this.key = "entries"
            setTitle(titleRes)
        }
        onRender { renderEntries() }
        screen.add(FooterPreference(prefContext)) {
            this.key = "footer"
            setTitle(
                when (kind) {
                    Kind.SERVERS -> R.string.px_edit_servers_footer
                    Kind.DOMAINS -> R.string.px_edit_domains_footer
                    Kind.ADDRESSES -> R.string.px_edit_addresses_footer
                    Kind.WORDS -> R.string.px_edit_words_footer
                    Kind.HIJACK -> R.string.px_edit_hijack_footer
                },
            )
            order = Int.MAX_VALUE
        }
    }

    private fun entries(): List<Entry> {
        val c = config ?: return emptyList()
        return if (kind == Kind.SERVERS) {
            c.servers(settingKey).mapIndexed { i, s -> Entry.Kept(i, s) }
        } else {
            c.list(settingKey).map { Entry.Text(it) }
        }
    }

    private fun label(e: Entry): String = when (e) {
        is Entry.Kept -> DnsFragment.serverLabel(requireContext(), e.server)
        is Entry.Text -> e.text
    }

    private fun renderEntries() {
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
                title = label(e)
                isEnabled = ProxyTasks.running() == null
                setOnPreferenceClickListener {
                    entryActions(list, i)
                    true
                }
            }
        }
    }

    /** Adds to the list the page showed (a recreated page answers before its own list has loaded). */
    private fun askAdd() {
        val list = entries()
        askText(null) { text -> save(list + Entry.Text(text), getString(R.string.px_edit_adding, text.take(60))) }
    }

    private fun askDefault() = confirm(
        "px-edit-default", R.string.px_edit_default, { getString(R.string.px_edit_default_confirm) }, R.string.px_action_restore,
    ) {
        changeJson(settingKey, null, getString(titleRes))
    }

    /** The list as the page showed it is the dialog's: its actions change that list. */
    private fun entryActions(list: List<Entry>, i: Int) = showDialog("px-edit-entry") { d ->
        val e = list[i]
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (i > 0) actions += getString(R.string.px_edit_move_up) to {
            save(list.toMutableList().apply { add(i - 1, removeAt(i)) }, getString(R.string.px_edit_moving, label(e)))
        }
        if (i < list.size - 1) actions += getString(R.string.px_edit_move_down) to {
            save(list.toMutableList().apply { add(i + 1, removeAt(i)) }, getString(R.string.px_edit_moving, label(e)))
        }
        actions += getString(R.string.px_edit_change) to {
            askText(if (e is Entry.Text) e.text else null) { text ->
                save(list.toMutableList().apply { set(i, Entry.Text(text)) }, getString(R.string.px_edit_changing, label(e)))
            }
        }
        actions += getString(R.string.px_edit_remove) to {
            confirm("px-edit-remove", R.string.px_edit_remove, { getString(R.string.px_edit_remove_confirm, label(e)) }, R.string.px_action_remove) {
                save(list.toMutableList().apply { removeAt(i) }, getString(R.string.px_edit_removing, label(e)))
            }
        }
        AlertDialog.Builder(d.context)
            .setTitle(label(e))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun save(list: List<Entry>, label: String) {
        val value = list.map {
            when (it) {
                is Entry.Kept -> mapOf("keep" to it.index)
                is Entry.Text -> it.text
            }
        }
        changeJson(settingKey, value, label)
    }

    private fun askText(current: String?, @RebuiltWithPage onDone: ListEditFragment.(String) -> Unit) = showDialog("px-edit-text") { d ->
        val context = d.context
        val input = formField(context, hintFor(kind)).apply {
            id = R.id.px_edit_text
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (current != null) setText(current)
        }
        val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
        form.addView(input)
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (current == null) R.string.px_edit_add else R.string.px_edit_change)
            .setMessage(messageFor(kind))
            .setView(form)
            .setPositiveButton(R.string.px_action_save_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (!valid(kind, text)) {
                    input.error = getString(R.string.px_edit_invalid)
                } else {
                    input.text.clear()
                    dialog.dismiss()
                    onDone(text)
                }
            }
        }
        input.requestFocus()
        dialog
    }

    companion object {
        private const val ARG_KEY = "key"
        private const val ARG_KIND = "kind"
        private const val ARG_TITLE = "title"

        fun open(context: Context, key: String, title: Int, kind: Kind) {
            Pages.open(
                context,
                ListEditFragment::class.java,
                title,
                arguments = Bundle().apply {
                    putString(ARG_KEY, key)
                    putString(ARG_KIND, kind.name)
                    putInt(ARG_TITLE, title)
                },
            )
        }

        private fun hintFor(kind: Kind) = when (kind) {
            Kind.SERVERS -> R.string.px_edit_server_hint
            Kind.DOMAINS -> R.string.px_edit_domain_hint
            Kind.ADDRESSES -> R.string.px_edit_address_hint
            Kind.WORDS -> R.string.px_edit_word_hint
            Kind.HIJACK -> R.string.px_edit_hijack_hint
        }

        private fun messageFor(kind: Kind) = when (kind) {
            Kind.SERVERS -> R.string.px_edit_server_message
            Kind.DOMAINS -> R.string.px_edit_domain_message
            Kind.ADDRESSES -> R.string.px_edit_address_message
            Kind.WORDS -> R.string.px_edit_word_message
            Kind.HIJACK -> R.string.px_edit_hijack_message
        }

        /** A first check; the manager checks again and the core validates the result. */
        fun valid(kind: Kind, text: String): Boolean {
            if (text.isEmpty() || text.length > 1024 || text.any { it.isISOControl() }) return false
            return when (kind) {
                Kind.SERVERS -> {
                    val main = text.substringBefore('#')
                    main.isNotEmpty() && !main.contains(' ') &&
                        (main == "system" || !main.contains("://") ||
                            main.substringBefore("://").lowercase() in setOf("udp", "tcp", "tls", "https", "quic", "dhcp", "http3", "h3", "rcode", "system"))
                }
                Kind.DOMAINS, Kind.WORDS -> !text.contains(' ')
                Kind.ADDRESSES -> Regex("^[0-9a-fA-F:.]+/\\d{1,3}$").matches(text)
                // A DNS redirection: [tcp://|udp://]<address or "any">:<port>.
                Kind.HIJACK -> {
                    val main = if (text.contains("://")) {
                        if (text.substringBefore("://").lowercase() !in setOf("tcp", "udp")) return false
                        text.substringAfter("://")
                    } else {
                        text
                    }
                    val host = main.substringBeforeLast(':', "")
                    val port = main.substringAfterLast(':', "").toIntOrNull() ?: 0
                    host.isNotEmpty() && !host.contains(' ') && port in 1..65535
                }
            }
        }
    }
}
