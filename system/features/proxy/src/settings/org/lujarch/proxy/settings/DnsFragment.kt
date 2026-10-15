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
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.showDialog

/**
 * The core's DNS: the mode (placeholder addresses, real addresses with the
 * domain remembered, or plain answers), the placeholder range and which
 * names get real addresses, every server list, the per-name servers, the
 * fallback filter and the resolver switches. The resolver itself and the
 * hijack of the phone's DNS answer for every name on the phone: they are
 * changeable with what a change breaks said first.
 */
class DnsFragment : KeysFragment() {
    override val riskKeys = listOf("dns.enable", "tun.dns-hijack")

    override fun buildRows(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val mode = screen.add(PreferenceCategory(prefContext)) {
            key = "mode"
            setTitle(R.string.px_dnsc_mode_category)
        }
        riskySwitchRow(mode, "dns.enable", R.string.px_dnsc_enable, R.string.px_dnsc_enable_summary)
        listRow(
            mode, "dns.enhanced-mode", R.string.px_core_dns_mode,
            values = listOf("fake-ip", "redir-host", "normal"),
            labels = listOf(getString(R.string.px_core_dns_fake_ip), getString(R.string.px_dnsc_mapping), getString(R.string.px_dnsc_normal)),
            defaultLabel = getString(R.string.px_dnsc_mode_default),
            summaries = listOf(
                getString(R.string.px_dnsc_fake_ip_summary),
                getString(R.string.px_dnsc_mapping_summary),
                getString(R.string.px_dnsc_normal_summary),
            ),
        )
        pageRowText(mode, "dns.fake-ip-range", R.string.px_dnsc_range, R.string.px_dnsc_range_hint, default = "198.18.0.1/16") { v ->
            Regex("^(\\d{1,3}\\.){3}\\d{1,3}/(\\d|[12]\\d|30)$").matches(v)
        }
        listRow(
            mode, "dns.fake-ip-filter-mode", R.string.px_dnsc_filter_mode,
            values = listOf("blacklist", "whitelist"),
            labels = listOf(getString(R.string.px_dnsc_filter_listed_real), getString(R.string.px_dnsc_filter_listed_fake)),
            defaultLabel = getString(R.string.px_dnsc_filter_default),
        )
        listPage(mode, "dns.fake-ip-filter", R.string.px_dnsc_filter, ListEditFragment.Kind.DOMAINS)

        val servers = screen.add(PreferenceCategory(prefContext)) {
            key = "servers"
            setTitle(R.string.px_dnsc_servers_category)
        }
        for ((key, title) in SERVER_KEYS) {
            pageRow(servers, key, title, summary = { c -> serversSummary(requireContext(), c.servers(key)) }) {
                ListEditFragment.open(requireContext(), key, title, ListEditFragment.Kind.SERVERS)
            }
        }
        pageRow(servers, "dns.nameserver-policy", R.string.px_core_dns_rules_policy, summary = { c ->
            val n = c.policy().size
            if (n == 0) getString(R.string.px_edit_none) else resources.getQuantityString(R.plurals.px_core_dns_policy, n, n)
        }) { Pages.open(requireContext(), PolicyFragment::class.java, R.string.px_core_dns_rules_policy) }

        val fallback = screen.add(PreferenceCategory(prefContext)) {
            key = "fallback_filter"
            setTitle(R.string.px_dnsc_fallback_filter)
        }
        // These conditions only choose between the servers and the fallback servers.
        val noFallback = fallback.add(Preference(prefContext)) {
            key = "fallback_filter_note"
            setSummary(R.string.px_dnsc_fallback_filter_unused)
            setIcon(R.drawable.ic_info)
            isSelectable = false
            isVisible = false
        }
        onRender { c -> noFallback.isVisible = c.servers("dns.fallback").isEmpty() }
        switchRow(fallback, "dns.fallback-filter.geoip", R.string.px_dnsc_ff_geoip, R.string.px_dnsc_ff_geoip_summary, default = true)
        pageRowText(fallback, "dns.fallback-filter.geoip-code", R.string.px_dnsc_ff_code, R.string.px_manual_country_hint, default = "CN") { v ->
            v.length == 2 && v.all { it.isLetter() }
        }
        listPage(fallback, "dns.fallback-filter.geosite", R.string.px_dnsc_ff_geosite, ListEditFragment.Kind.WORDS)
        listPage(fallback, "dns.fallback-filter.ipcidr", R.string.px_dnsc_ff_ipcidr, ListEditFragment.Kind.ADDRESSES)
        listPage(fallback, "dns.fallback-filter.domain", R.string.px_dnsc_ff_domain, ListEditFragment.Kind.DOMAINS)

        val resolver = screen.add(PreferenceCategory(prefContext)) {
            key = "resolver"
            setTitle(R.string.px_dnsc_resolver)
        }
        switchRow(resolver, "dns.ipv6", R.string.px_dnsc_ipv6, R.string.px_dnsc_ipv6_summary, default = false)
        switchRow(resolver, "dns.prefer-h3", R.string.px_dnsc_h3, R.string.px_dnsc_h3_summary, default = false)
        switchRow(resolver, "dns.respect-rules", R.string.px_dnsc_respect, R.string.px_dnsc_respect_summary, default = false)
        switchRow(resolver, "dns.use-hosts", R.string.px_dnsc_hosts, R.string.px_dnsc_hosts_summary, default = true)
        switchRow(resolver, "dns.use-system-hosts", R.string.px_dnsc_system_hosts, R.string.px_dnsc_system_hosts_summary, default = true)
        riskyListRow(resolver, "tun.dns-hijack", R.string.px_tun_hijack, ListEditFragment.Kind.HIJACK)

        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_dnsc_footer)
            order = Int.MAX_VALUE
        }
    }

    private fun listPage(group: PreferenceCategory, key: String, title: Int, kind: ListEditFragment.Kind) {
        pageRow(group, key, title, summary = { c ->
            c.list(key).let { l -> if (l.isEmpty()) getString(R.string.px_edit_none) else resources.getQuantityString(R.plurals.px_edit_entries, l.size, l.size) }
        }) { ListEditFragment.open(requireContext(), key, title, kind) }
    }

    /** A short text value (a range, a country code) typed in a dialog; "default" goes back. */
    private fun pageRowText(
        group: PreferenceCategory,
        key: String,
        title: Int,
        hint: Int,
        default: String,
        @OutlivesPage valid: (String) -> Boolean,
    ) {
        pageRow(group, key, title, summary = { c -> c.string(key) ?: getString(R.string.px_opt_default_value, default) }) {
            askText(key, title, hint, valid)
        }
    }

    private fun askText(key: String, title: Int, hint: Int, @OutlivesPage valid: (String) -> Boolean) {
        val current = config?.string(key) ?: ""
        showDialog("px-dns-text") { d ->
            val context = d.context
            val input = formField(context, hint).apply {
                id = R.id.px_key_text
                inputType = InputType.TYPE_CLASS_TEXT
                setText(current)
            }
            val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
            form.addView(input)
            val dialog = AlertDialog.Builder(context)
                .setTitle(title)
                .setView(form)
                .setPositiveButton(R.string.px_action_save_apply, null)
                .setNeutralButton(R.string.px_opt_use_default) { _, _ -> change(key, DEFAULT, getString(title)) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val v = input.text.toString().trim()
                    if (!valid(v)) {
                        input.error = getString(R.string.px_dnsc_invalid)
                    } else {
                        dialog.dismiss()
                        change(key, v, getString(title))
                    }
                }
            }
            input.requestFocus()
            dialog
        }
    }

    companion object {
        private val SERVER_KEYS = listOf(
            "dns.nameserver" to R.string.px_core_dns_servers,
            "dns.fallback" to R.string.px_core_dns_fallback,
            "dns.default-nameserver" to R.string.px_core_dns_bootstrap,
            "dns.proxy-server-nameserver" to R.string.px_core_dns_node_servers,
            "dns.direct-nameserver" to R.string.px_dnsc_direct_servers,
        )

        /** "Placeholder addresses · 3 servers" for the core settings page. */
        fun summary(context: Context, c: CoreConfig): String {
            val mode = when (c.string("dns.enhanced-mode")) {
                "fake-ip" -> context.getString(R.string.px_core_dns_fake_ip)
                "redir-host" -> context.getString(R.string.px_dnsc_mapping)
                "normal" -> context.getString(R.string.px_dnsc_normal)
                else -> null
            }
            val n = c.servers("dns.nameserver").size
            return ProxyFormat.join(context, listOf(mode, context.resources.getQuantityString(R.plurals.px_dnsc_servers, n, n)))
        }

        /** Servers as "dns.alidns.com (HTTPS)", one per line. */
        fun serversSummary(context: Context, list: List<DnsServer>): String =
            if (list.isEmpty()) context.getString(R.string.px_edit_none) else list.joinToString("\n") { serverLabel(context, it) }

        fun serverLabel(context: Context, s: DnsServer): String {
            val kind = when (s.kind) {
                "https" -> context.getString(R.string.px_dns_doh)
                "tls" -> context.getString(R.string.px_dns_dot)
                "quic" -> context.getString(R.string.px_dns_doq)
                "udp" -> context.getString(R.string.px_dns_udp)
                "tcp" -> context.getString(R.string.px_dns_tcp)
                "system" -> context.getString(R.string.px_dns_system)
                "dhcp" -> context.getString(R.string.px_dns_dhcp)
                else -> s.kind
            }
            val base = if (s.kind == "system") kind else context.getString(R.string.px_dns_server, s.host, kind)
            return if (s.via != null) context.getString(R.string.px_dns_server_via, base, s.via) else base
        }
    }
}
