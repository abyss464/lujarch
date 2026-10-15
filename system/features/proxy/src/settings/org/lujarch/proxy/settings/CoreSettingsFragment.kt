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
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.UserMessageException
import org.lujarch.settings.pages.showDialog

/**
 * The core's own settings, saved in the manager's config so that every start
 * uses them: the log level (also set on the running core at once, and
 * written into the installed configuration), connections, app lookup, IPv6,
 * the virtual network interface (the values this phone recommends included,
 * each with what a change breaks), and the pages for group testing, domain
 * sniffing, DNS and geodata. A change is applied with a rebuild.
 */
class CoreSettingsFragment : KeysFragment() {
    private lateinit var logLevelPref: ListPreference
    /** The log level of the running core (null while it is off). */
    private var runningLogLevel: String? = null

    override val riskKeys = listOf(
        "tun.enable",
        "tun.auto-route",
        "tun.auto-detect-interface",
        "tun.dns-hijack",
        "dns.enable",
    )

    override fun buildRows(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val general = screen.add(PreferenceCategory(prefContext)) {
            key = "general"
            setTitle(R.string.px_opt_general)
        }
        logLevelPref = general.add(ListPreference(prefContext)) {
            key = "log-level"
            setTitle(R.string.px_core_log_level)
            setDialogTitle(R.string.px_core_log_level)
            entries = resources.getStringArray(R.array.px_core_log_levels)
            entryValues = Mihomo.CORE_LOG_LEVELS.toTypedArray()
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                setLogLevel(value as String)
                false
            }
        }
        onRender { c -> showLogLevel(c) }

        val connections = screen.add(PreferenceCategory(prefContext)) {
            key = "connections"
            setTitle(R.string.px_opt_connections)
        }
        switchRow(connections, "unified-delay", R.string.px_core_unified, R.string.px_core_unified_summary, default = false)
        switchRow(connections, "tcp-concurrent", R.string.px_core_concurrent, R.string.px_core_concurrent_summary, default = false)
        numberRow(
            connections, "keep-alive-interval", R.string.px_opt_keep_alive_interval, 1, 86_400, R.string.px_opt_seconds_hint,
            format = { seconds(it) },
        )
        numberRow(
            connections, "keep-alive-idle", R.string.px_opt_keep_alive_idle, 1, 86_400, R.string.px_opt_seconds_hint,
            format = { seconds(it) }, defaultText = getString(R.string.px_opt_core_default),
        )
        switchRow(connections, "disable-keep-alive", R.string.px_opt_keep_alive_off, R.string.px_opt_keep_alive_off_summary, default = false)
        listRow(
            connections, "global-client-fingerprint", R.string.px_opt_fingerprint,
            values = FINGERPRINTS,
            labels = FINGERPRINTS.map { fingerprintLabel(it) },
            defaultLabel = getString(R.string.px_opt_fingerprint_none),
        )
        switchRow(
            connections, "allow-lan", R.string.px_opt_allow_lan, R.string.px_opt_allow_lan_summary, default = false,
            confirmOn = R.string.px_opt_allow_lan_confirm,
        )

        val apps = screen.add(PreferenceCategory(prefContext)) {
            key = "apps"
            setTitle(R.string.px_opt_apps)
        }
        listRow(
            apps, "find-process-mode", R.string.px_opt_process,
            values = listOf("always", "strict", "off"),
            labels = listOf(getString(R.string.px_opt_process_always), getString(R.string.px_opt_process_strict), getString(R.string.px_opt_process_off)),
            summaries = listOf(
                getString(R.string.px_opt_process_always_summary),
                getString(R.string.px_opt_process_strict_summary),
                getString(R.string.px_opt_process_off_summary),
            ),
        )

        val ipv6 = screen.add(PreferenceCategory(prefContext)) {
            // Not "ipv6", the key of its switch: a page keeps each preference's
            // state under its key, and the category restored with the switch's
            // state throws when the activity is recreated.
            key = "ipv6_category"
            setTitle(R.string.px_ipv6_category)
        }
        switchRow(ipv6, "ipv6", R.string.px_ipv6_core, R.string.px_ipv6_core_summary, default = false)
        // PRX-5: a switch of the system, which the proxy's service reads at every start of the core.
        ipv6.add(androidx.preference.SwitchPreferenceCompat(prefContext)) {
            key = KEY_IPV6_BLOCK
            setTitle(R.string.px_ipv6_block)
            setSummary(R.string.px_ipv6_block_summary)
            isPersistent = false
            isChecked = ipv6BlockOn()
            setOnPreferenceChangeListener { _, value ->
                askIpv6Block(value as Boolean)
                false
            }
            onRender { _ -> isChecked = ipv6BlockOn() }
        }

        val tun = screen.add(PreferenceCategory(prefContext)) {
            key = "tun"
            setTitle(R.string.px_tun_category)
        }
        listRow(
            tun, "tun.stack", R.string.px_core_stack,
            values = STACKS.toList(),
            labels = resources.getStringArray(R.array.px_core_stacks).toList(),
        )
        numberRow(
            tun, "tun.mtu", R.string.px_tun_mtu, 576, 65_535, R.string.px_tun_mtu_hint,
            format = { it.toString() }, defaultText = getString(R.string.px_tun_mtu_default),
        )
        switchRow(tun, "tun.strict-route", R.string.px_tun_strict, R.string.px_tun_strict_summary, default = false)
        switchRow(tun, "tun.endpoint-independent-nat", R.string.px_tun_ein, R.string.px_tun_ein_summary, default = false)
        pageRow(tun, "tun.route-exclude-address", R.string.px_tun_exclude, summary = { c ->
            c.list("tun.route-exclude-address").let { l ->
                if (l.isEmpty()) getString(R.string.px_tun_exclude_none) else resources.getQuantityString(R.plurals.px_edit_entries, l.size, l.size)
            }
        }) { ListEditFragment.open(requireContext(), "tun.route-exclude-address", R.string.px_tun_exclude, ListEditFragment.Kind.ADDRESSES) }
        // What the phone needs to proxy at all; a change says what it breaks first.
        riskySwitchRow(tun, "tun.enable", R.string.px_tun_enable, R.string.px_tun_enable_summary)
        riskySwitchRow(tun, "tun.auto-route", R.string.px_tun_auto_route, R.string.px_tun_auto_route_summary)
        riskySwitchRow(tun, "tun.auto-detect-interface", R.string.px_tun_detect, R.string.px_tun_detect_summary)
        riskyListRow(tun, "tun.dns-hijack", R.string.px_tun_hijack, ListEditFragment.Kind.HIJACK)

        val more = screen.add(PreferenceCategory(prefContext)) {
            key = "more"
            setTitle(R.string.px_opt_more)
        }
        pageRow(more, "group-test", R.string.px_gt_title, summary = { c ->
            getString(R.string.px_gt_summary, seconds(c.number("group-test.auto.interval") ?: 300))
        }) { Pages.open(requireContext(), GroupTestFragment::class.java, R.string.px_gt_title) }
        pageRow(more, "adaptive", R.string.px_ad_params_title, summary = { c ->
            getString(R.string.px_ad_param_race_short, c.number("adaptive.race-cost") ?: 150)
        }) { AdaptiveParamsFragment.open(requireContext()) }
        pageRow(more, "sniffer", R.string.px_snf_title, summary = { c ->
            if (c.bool("sniffer.enable") == true) {
                val protocols = c.sniff().keys.sorted()
                if (protocols.isEmpty()) getString(R.string.px_opt_on) else getString(R.string.px_snf_on_protocols, protocols.joinToString(getString(R.string.px_list_separator)))
            } else {
                getString(R.string.px_opt_off)
            }
        }) { Pages.open(requireContext(), SnifferFragment::class.java, R.string.px_snf_title) }
        pageRow(more, "dns", R.string.px_dnsc_title, summary = { c -> DnsFragment.summary(requireContext(), c) }) {
            Pages.open(requireContext(), DnsFragment::class.java, R.string.px_dnsc_title)
        }
        pageRow(more, "geodata", R.string.px_core_geo_category, summary = { c ->
            if (c.bool("geo-auto-update") == true) {
                getString(R.string.px_geo_auto_every, hours(c.number("geo-update-interval") ?: 24))
            } else {
                getString(R.string.px_geo_manual)
            }
        }) { Pages.open(requireContext(), GeodataFragment::class.java, R.string.px_core_geo_category) }

        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_opt_footer)
            order = Int.MAX_VALUE
        }
    }

    override fun refresh() {
        super.refresh()
        launchUi {
            runningLogLevel = orNull("running log level") {
                if (ProxyClient.info().controllerUp) Mihomo.configs().logLevel else null
            }
            config?.let { showLogLevel(it) }
        }
    }

    private fun showLogLevel(c: CoreConfig) {
        val saved = c.string("log-level") ?: "info"
        logLevelPref.value = saved
        val savedLabel = logLevelPref.entry ?: saved
        val running = runningLogLevel?.lowercase()
        logLevelPref.summary = if (running != null && running != saved) {
            val i = Mihomo.CORE_LOG_LEVELS.indexOf(running)
            val runningLabel = resources.getStringArray(R.array.px_core_log_levels).getOrNull(i) ?: running
            getString(R.string.px_opt_log_level_differs, savedLabel, runningLabel)
        } else {
            savedLabel
        }
    }

    /**
     * The log level is saved (also into the installed configuration, so every
     * start uses it) and set on the running core; no rebuild.
     */
    private fun setLogLevel(level: String) {
        launchUi {
            if (proxyAction(requireContext(), R.string.px_error_log_level) {
                    ProxyCommands.set("log-level", level)
                    if (ProxyClient.info().controllerUp) {
                        Mihomo.setLogLevel(level)
                        runningLogLevel = level
                    }
                }
            ) {
                refresh()
            }
        }
    }

    private fun ipv6BlockOn(): Boolean =
        Settings.Global.getInt(requireContext().contentResolver, ProxyClient.SETTING_IPV6_BLOCK, 1) == 1

    /** The service reads the IPv6 block at every start of the core: the change asks whether to restart now. */
    private fun askIpv6Block(on: Boolean) = showDialog("px-ipv6-block") { d ->
        AlertDialog.Builder(d.context)
            .setTitle(R.string.px_ipv6_block)
            .setMessage(if (on) R.string.px_ipv6_block_on_message else R.string.px_ipv6_block_off_message)
            .setPositiveButton(R.string.px_ipv6_apply_restart) { _, _ -> changeIpv6Block(on, getString(R.string.px_ipv6_block), restart = true) }
            .setNeutralButton(R.string.px_ipv6_apply_later) { _, _ -> changeIpv6Block(on, getString(R.string.px_ipv6_block), restart = false) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun changeIpv6Block(on: Boolean, label: String, restart: Boolean) {
        val context = requireContext()
        val started = ProxyTasks.start(context, KEY, getString(R.string.px_core_changing, label)) {
            step(this.context.getString(R.string.px_step_saving))
            val written = Settings.Global.putInt(
                this.context.contentResolver, ProxyClient.SETTING_IPV6_BLOCK, if (on) 1 else 0,
            )
            if (!written) throw UserMessageException(this.context.getString(R.string.px_error_setting))
            if (restart && ProxyClient.info().mihomoEnabled) {
                step(this.context.getString(R.string.px_step_restarting))
                ProxyManager.coreRestart()
                summary(this.context.getString(R.string.px_ipv6_block_applied))
            } else {
                summary(this.context.getString(R.string.px_ipv6_block_saved))
            }
        }
        if (!started) Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
    }

    private fun seconds(n: Long): String = resources.getQuantityString(R.plurals.px_opt_seconds, n.toInt(), n.toInt())

    private fun hours(n: Long): String = resources.getQuantityString(R.plurals.px_hours, n.toInt(), n.toInt())

    private fun fingerprintLabel(f: String): String = when (f) {
        "random" -> getString(R.string.px_opt_fingerprint_random)
        "360" -> getString(R.string.px_opt_fingerprint_360)
        "qq" -> getString(R.string.px_opt_fingerprint_qq)
        else -> f.replaceFirstChar { it.uppercase() }.replace("Ios", "iOS")
    }

    companion object {
        /** The IPv6 block's row, which Settings search scrolls into view. */
        const val KEY_IPV6_BLOCK = "lujarch_proxy_ipv6_block"
        const val KEY = "core-settings"
        private val STACKS = arrayOf("mixed", "gvisor", "system")
        private val FINGERPRINTS = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random")
    }
}
