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
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference

/**
 * How the generated proxy groups measure their nodes: the address every test
 * fetches, how often it runs, how long one test may take, whether a group is
 * only tested while it is in use, how much faster another node must be before
 * a lowest-delay group switches, and how a load-balance group spreads
 * connections. Per group kind; a custom group can have its own values
 * (Proxy groups > a custom group). A change is applied with a rebuild.
 */
class GroupTestFragment : KeysFragment() {
    override fun buildRows(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        for (kind in KINDS) {
            val group = screen.add(PreferenceCategory(prefContext)) {
                key = kind.name
                setTitle(kind.title)
            }
            urlRow(group, "${kind.name}.url", R.string.px_gt_url, R.string.px_gt_url_message, R.string.px_gt_url_hint) {
                getString(R.string.px_gt_default)
            }
            numberRow(
                group, "${kind.name}.interval", R.string.px_gt_interval, 10, 86_400, R.string.px_opt_seconds_hint,
                format = { seconds(it) }, defaultText = getString(R.string.px_gt_default),
            )
            if (kind.tolerance) {
                numberRow(
                    group, "${kind.name}.tolerance", R.string.px_gt_tolerance, 0, 10_000, R.string.px_gt_ms_hint,
                    format = { millis(it) }, defaultText = getString(R.string.px_gt_default),
                )
            }
            numberRow(
                group, "${kind.name}.timeout", R.string.px_gt_timeout, 100, 60_000, R.string.px_gt_ms_hint,
                format = { millis(it) }, defaultText = getString(R.string.px_opt_core_default),
            )
            switchRow(group, "${kind.name}.lazy", R.string.px_gt_lazy, R.string.px_gt_lazy_summary, default = true)
            if (kind.strategy) {
                listRow(
                    group, "${kind.name}.strategy", R.string.px_gt_strategy,
                    values = STRATEGIES,
                    labels = listOf(
                        getString(R.string.px_gt_strategy_hashing),
                        getString(R.string.px_gt_strategy_round_robin),
                        getString(R.string.px_gt_strategy_sticky),
                    ),
                    summaries = listOf(
                        getString(R.string.px_gt_strategy_hashing_summary),
                        getString(R.string.px_gt_strategy_round_robin_summary),
                        getString(R.string.px_gt_strategy_sticky_summary),
                    ),
                )
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_gt_footer)
            order = Int.MAX_VALUE
        }
    }

    private fun seconds(n: Long): String = resources.getQuantityString(R.plurals.px_opt_seconds, n.toInt(), n.toInt())

    private fun millis(n: Long): String = getString(R.string.px_gt_ms, n)

    /** One group kind with the parameters it takes (mihomo refuses the others). */
    private class Kind(val name: String, val title: Int, val tolerance: Boolean = false, val strategy: Boolean = false)

    companion object {
        /** The `config get --json` key of each kind, and which parameters it has. */
        private val KINDS = listOf(
            Kind("group-test.auto", R.string.px_type_urltest, tolerance = true),
            Kind("group-test.fallback", R.string.px_type_fallback),
            Kind("group-test.load-balance", R.string.px_type_loadbalance, strategy = true),
        )
        val STRATEGIES = listOf("consistent-hashing", "round-robin", "sticky-sessions")
    }
}
