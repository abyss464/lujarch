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
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.showDialog

/**
 * Domain sniffing: on or off, which protocols are read on which ports (and
 * whether the sniffed domain replaces the destination), the domains always
 * or never sniffed, and the core's three sniffing switches.
 */
class SnifferFragment : KeysFragment() {
    private lateinit var protocolsCategory: PreferenceCategory

    override fun buildRows(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val main = screen.add(PreferenceCategory(prefContext)) { key = "main" }
        switchRow(main, "sniffer.enable", R.string.px_core_sniffer, R.string.px_core_sniffer_summary, default = false)

        protocolsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "protocols"
            setTitle(R.string.px_snf_protocols)
        }
        onRender { c -> renderProtocols(c) }

        val domains = screen.add(PreferenceCategory(prefContext)) {
            key = "domains"
            setTitle(R.string.px_snf_domains)
        }
        for ((key, title) in listOf("sniffer.force-domain" to R.string.px_snf_force_domain, "sniffer.skip-domain" to R.string.px_snf_skip_domain)) {
            pageRow(domains, key, title, summary = { c ->
                c.list(key).let { l -> if (l.isEmpty()) getString(R.string.px_edit_none) else resources.getQuantityString(R.plurals.px_edit_entries, l.size, l.size) }
            }) { ListEditFragment.open(requireContext(), key, title, ListEditFragment.Kind.DOMAINS) }
        }

        val behaviour = screen.add(PreferenceCategory(prefContext)) {
            key = "behaviour"
            setTitle(R.string.px_snf_behaviour)
        }
        switchRow(behaviour, "sniffer.override-destination", R.string.px_snf_override, R.string.px_snf_override_summary, default = true)
        switchRow(behaviour, "sniffer.parse-pure-ip", R.string.px_snf_pure_ip, R.string.px_snf_pure_ip_summary, default = true)
        switchRow(behaviour, "sniffer.force-dns-mapping", R.string.px_snf_dns_mapping, R.string.px_snf_dns_mapping_summary, default = true)

        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_snf_footer)
            order = Int.MAX_VALUE
        }
    }

    private fun renderProtocols(c: CoreConfig) {
        protocolsCategory.removeAll()
        val sniff = c.sniff()
        for (proto in PROTOCOLS) {
            val conf = sniff[proto]
            protocolsCategory.add(Preference(prefContext)) {
                key = "proto:$proto"
                title = proto
                summary = if (conf == null) {
                    getString(R.string.px_snf_proto_off)
                } else {
                    val ports = getString(R.string.px_snf_ports, conf.ports.joinToString(getString(R.string.px_list_separator)))
                    when (conf.overrideDestination) {
                        true -> getString(R.string.px_snf_ports_override, ports)
                        false -> getString(R.string.px_snf_ports_keep, ports)
                        null -> ports
                    }
                }
                isEnabled = ProxyTasks.running() == null
                setOnPreferenceClickListener {
                    editProtocol(proto, sniff)
                    true
                }
            }
        }
    }

    /** Ports of one protocol (empty: not sniffed) and its own override. */
    private fun editProtocol(proto: String, current: Map<String, SniffProtocol>) {
        val overrideAll = c()?.bool("sniffer.override-destination") ?: true
        showDialog("px-sniffer-protocol") { d -> protocolDialog(d.context, proto, current, overrideAll) }
    }

    private fun protocolDialog(context: Context, proto: String, current: Map<String, SniffProtocol>, overrideAll: Boolean): Dialog {
        val conf = current[proto]
        val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
        val ports = formField(context, R.string.px_snf_ports_hint).apply {
            id = R.id.px_sniffer_ports
            inputType = InputType.TYPE_CLASS_TEXT
            setText(conf?.ports?.joinToString(", ") ?: DEFAULT_PORTS[proto])
        }
        form.addView(ports)
        val override = CheckBox(context).apply {
            id = R.id.px_sniffer_override
            setText(R.string.px_snf_proto_override)
            isChecked = conf?.overrideDestination ?: overrideAll
        }
        form.addView(override)
        val dialog = AlertDialog.Builder(context)
            .setTitle(getString(R.string.px_snf_proto_title, proto))
            .setMessage(R.string.px_snf_proto_message)
            .setView(form)
            .setPositiveButton(R.string.px_action_save_apply, null)
            .setNeutralButton(R.string.px_snf_proto_remove) { _, _ ->
                save(current - proto, getString(R.string.px_snf_proto_title, proto))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val list = ports.text.toString().split(',', ' ', '\uFF0C').map { it.trim() }.filter { it.isNotEmpty() }
                if (list.isEmpty() || list.any { !isPort(it) }) {
                    ports.error = getString(R.string.px_snf_ports_invalid)
                } else {
                    dialog.dismiss()
                    save(current + (proto to SniffProtocol(list, override.isChecked)), getString(R.string.px_snf_proto_title, proto))
                }
            }
        }
        return dialog
    }

    private fun c(): CoreConfig? = config

    private fun save(protocols: Map<String, SniffProtocol>, label: String) {
        val value = protocols.mapValues { (_, p) ->
            val m = LinkedHashMap<String, Any>()
            m["ports"] = p.ports.map { it.toIntOrNull() ?: it }
            p.overrideDestination?.let { m["override-destination"] = it }
            m
        }
        changeJson("sniffer.sniff", if (value.isEmpty()) null else value, label)
    }

    companion object {
        private val PROTOCOLS = listOf("HTTP", "TLS", "QUIC")
        private val DEFAULT_PORTS = mapOf("HTTP" to "80, 8080-8880", "TLS" to "443, 8443", "QUIC" to "443, 8443")

        fun isPort(s: String): Boolean {
            val parts = s.split('-')
            if (parts.size > 2) return false
            val nums = parts.map { it.toIntOrNull() ?: return false }
            if (nums.any { it !in 1..65535 }) return false
            return nums.size == 1 || nums[0] <= nums[1]
        }
    }
}
