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
import android.text.format.DateUtils
import android.text.format.Formatter
import java.text.NumberFormat
import org.lujarch.settings.pages.Format

/** Localized texts shared by the proxy pages. */
object ProxyFormat {
    fun size(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

    /**
     * A subscription's traffic as the services count it: binary units, so a
     * "100 GB" plan (100 × 1024³ bytes) reads 100 GB, not the 107 GB of
     * [size]'s decimal units.
     */
    fun quota(context: Context, bytes: Long): String {
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1024 && unit < QUOTA_UNITS.size - 1) {
            value /= 1024
            unit++
        }
        val digits = when {
            unit == 0 || value >= 100 -> 0
            value >= 10 -> 1
            else -> 2
        }
        val number = NumberFormat.getInstance(context.resources.configuration.locales[0]).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = digits
        }.format(value)
        return context.getString(R.string.px_quota, number, QUOTA_UNITS[unit])
    }

    private val QUOTA_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /** "3 hours ago", localized; "just now" under a minute (Format.ago). */
    fun ago(context: Context, millis: Long): CharSequence = Format.ago(context, millis, abbreviated = true)

    /** Date and time, localized ("Sep 28, 01:05"). */
    fun dateTime(context: Context, millis: Long): String = DateUtils.formatDateTime(
        context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

    fun join(context: Context, parts: List<CharSequence?>): String =
        parts.filterNotNull().filter { it.isNotEmpty() }.joinToString(context.getString(R.string.separator))

    fun modeLabel(context: Context, mode: String?): String? = when (mode) {
        Mihomo.MODE_RULE -> context.getString(R.string.px_mode_rule)
        Mihomo.MODE_GLOBAL -> context.getString(R.string.px_mode_global)
        Mihomo.MODE_DIRECT -> context.getString(R.string.px_mode_direct)
        else -> null
    }

    /** The TUN network stack as the controller reports it ("Mixed", "gVisor", "System"). */
    fun stackLabel(context: Context, stack: String): String = when (stack.lowercase()) {
        "mixed" -> context.getString(R.string.px_stack_mixed)
        "system" -> context.getString(R.string.px_stack_system)
        "gvisor" -> context.getString(R.string.px_stack_gvisor)
        else -> stack
    }

    fun stateLabel(context: Context, state: String): String = when (state) {
        ProxyInfo.STATE_RUNNING -> context.getString(R.string.px_state_running)
        ProxyInfo.STATE_FALLBACK -> context.getString(R.string.px_state_fallback)
        ProxyInfo.STATE_NO_CONFIG -> context.getString(R.string.px_state_no_config)
        ProxyInfo.STATE_CRASH_LOOP -> context.getString(R.string.px_state_crash_loop)
        ProxyInfo.STATE_STARTING -> context.getString(R.string.px_state_starting)
        ProxyInfo.STATE_OFF -> context.getString(R.string.px_status_off)
        "" -> context.getString(R.string.status_unknown)
        else -> state
    }

    /** One line for a subscription: nodes, age, usage and expiry. */
    fun providerSummary(context: Context, p: ProviderInfo): String {
        val res = context.resources
        val parts = ArrayList<CharSequence?>()
        if (p.manual) parts.add(context.getString(R.string.px_sub_manual))
        parts.add(p.nodes?.let { res.getQuantityString(R.plurals.px_nodes, it, it) }
            ?: context.getString(R.string.px_sub_not_fetched))
        p.fetchedAt?.let { parts.add(context.getString(R.string.px_sub_updated, ago(context, it))) }
        p.usage?.let { u ->
            if (u.total > 0) {
                parts.add(context.getString(R.string.px_sub_used_of, quota(context, u.used), quota(context, u.total)))
            } else if (u.used > 0) {
                parts.add(context.getString(R.string.px_sub_used, quota(context, u.used)))
            }
            u.expireAt?.let { parts.add(expiry(context, it)) }
        }
        return join(context, parts)
    }

    fun expiry(context: Context, at: Long): String {
        val days = ((at - System.currentTimeMillis()) / DateUtils.DAY_IN_MILLIS).toInt()
        return if (at <= System.currentTimeMillis()) {
            context.getString(R.string.px_sub_expired)
        } else {
            context.resources.getQuantityString(R.plurals.px_sub_expires_in, days, days)
        }
    }

    /** Group or proxy type of the controller. */
    fun typeLabel(context: Context, type: String): String = when (type.lowercase()) {
        "selector" -> context.getString(R.string.px_type_selector)
        "urltest", "url-test" -> context.getString(R.string.px_type_urltest)
        "fallback" -> context.getString(R.string.px_type_fallback)
        "loadbalance", "load-balance" -> context.getString(R.string.px_type_loadbalance)
        "adaptive" -> context.getString(R.string.px_type_adaptive)
        "relay" -> context.getString(R.string.px_type_relay)
        "direct" -> context.getString(R.string.px_type_direct)
        "reject", "rejectdrop" -> context.getString(R.string.px_type_reject)
        else -> type
    }

    /**
     * A manager target in words: DIRECT and REJECT, a country with its line
     * type ("JP/residential": Japan, residential), fallback and load-balance
     * variants; custom group keys as they are.
     */
    fun targetLabel(context: Context, target: String): String {
        val (kind, key) = when {
            target.startsWith("fallback:") -> R.string.px_type_fallback to target.removePrefix("fallback:")
            target.startsWith("balance:") -> R.string.px_type_loadbalance to target.removePrefix("balance:")
            else -> null to target
        }
        val base = when {
            key == "DIRECT" -> context.getString(R.string.px_target_direct)
            key == "REJECT" -> context.getString(R.string.px_target_reject)
            key.contains('/') -> {
                val country = key.substringBefore('/')
                val net = netTypeLabel(context, key.substringAfter('/'))
                context.getString(R.string.px_target_country_net, country, net)
            }
            else -> key
        }
        return kind?.let { context.getString(R.string.px_target_variant, base, context.getString(it)) } ?: base
    }

    /** Line type of a node class (`datacenter`, `residential`, `starlink`). */
    fun netTypeLabel(context: Context, netType: String?): String? = when (netType?.lowercase()) {
        "datacenter" -> context.getString(R.string.px_net_datacenter)
        "residential" -> context.getString(R.string.px_net_residential)
        "starlink" -> context.getString(R.string.px_net_starlink)
        null -> null
        else -> netType
    }

    /** What a backup was made before: "Before importing the routing rules". */
    fun backupReason(context: Context, reason: String): String {
        val words = reason.split(' ')
        return when {
            words.firstOrNull() == "restore" -> context.getString(R.string.px_backup_before_restore)
            // "remove ruleset <file>"
            words.firstOrNull() == "remove" -> context.getString(R.string.px_backup_before_ruleset_remove)
            words.getOrNull(1) == "geodata" -> context.getString(R.string.px_backup_before_geodata)
            words.getOrNull(1) == "cache" -> context.getString(R.string.px_backup_before_cache)
            words.getOrNull(1) == "config" -> context.getString(R.string.px_backup_before_config)
            words.getOrNull(1) == "routing" -> context.getString(R.string.px_backup_before_routing)
            words.getOrNull(1) == "providers" -> context.getString(R.string.px_backup_before_providers)
            words.getOrNull(1) == "bundle" -> context.getString(R.string.px_backup_before_bundle)
            words.getOrNull(1) == "ruleset" -> context.getString(R.string.px_backup_before_ruleset)
            else -> reason
        }
    }

    /** A logical file of the manager in words. */
    fun fileLabel(context: Context, logical: String): String = when {
        logical == "config.yaml" -> context.getString(R.string.px_file_config)
        logical == "routing.yaml" -> context.getString(R.string.px_file_routing)
        logical == "providers.yaml" -> context.getString(R.string.px_file_providers)
        logical == "cache/classify-cache.json" -> context.getString(R.string.px_file_cache)
        logical.startsWith("reference/") ->
            context.getString(R.string.px_file_nodes_of, logical.removePrefix("reference/").removeSuffix(".yaml"))
        logical.startsWith("rulesets/") -> context.getString(R.string.px_file_ruleset, logical.removePrefix("rulesets/"))
        logical.startsWith("geodata/") -> context.getString(R.string.px_file_geodata, logical.removePrefix("geodata/"))
        else -> logical
    }

    /** The files of an import or backup, grouped ("Node lists (3)"). */
    fun filesLabel(context: Context, files: List<String>): String {
        val nodes = files.count { it.startsWith("reference/") }
        val others = files.filterNot { it.startsWith("reference/") }.map { fileLabel(context, it) }
        val parts = ArrayList<String>(others)
        if (nodes > 0) parts.add(context.resources.getQuantityString(R.plurals.px_file_node_lists, nodes, nodes))
        return parts.joinToString(context.getString(R.string.px_list_separator))
    }

    /** Result line of a build: "328 nodes, 216 groups, 189 rules". */
    fun counts(context: Context, proxies: Int?, groups: Int?, rules: Int?): String? {
        if (proxies == null || groups == null || rules == null) return null
        val res = context.resources
        return context.getString(
            R.string.px_counts,
            res.getQuantityString(R.plurals.px_nodes, proxies, proxies),
            res.getQuantityString(R.plurals.px_groups, groups, groups),
            res.getQuantityString(R.plurals.px_rules, rules, rules),
        )
    }

    fun delay(context: Context, delay: Int?, tested: Boolean): String = when {
        delay == null -> context.getString(R.string.px_delay_untested)
        delay <= 0 && tested -> context.getString(R.string.px_delay_timeout)
        delay <= 0 -> context.getString(R.string.px_delay_untested)
        else -> context.getString(R.string.px_delay_ms, delay)
    }
}
