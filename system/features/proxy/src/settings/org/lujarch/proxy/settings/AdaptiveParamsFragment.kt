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
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.Pages

/**
 * How the adaptive proxy groups decide:
 * what a parallel attempt is worth, the margin before a switch, how fast a
 * burst of failures moves a group and how long it is remembered, how often
 * an unused member is measured while the group is in use, and how long one
 * attempt may take. The values reach every adaptive group; a change is
 * applied with a rebuild, which keeps what the groups learned.
 */
class AdaptiveParamsFragment : KeysFragment() {
    override fun buildRows(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val choice = screen.add(PreferenceCategory(prefContext)) {
            key = "choice"
            setTitle(R.string.px_ad_params_choice)
        }
        numberRow(
            choice, "adaptive.race-cost", R.string.px_ad_param_race, 0, 60_000, R.string.px_gt_ms_hint,
            format = { getString(R.string.px_gt_ms, it) }, defaultText = getString(R.string.px_gt_default),
            message = R.string.px_ad_param_race_message,
        )
        numberRow(
            choice, "adaptive.hysteresis", R.string.px_ad_param_hysteresis, 0, 100, R.string.px_ad_percent_hint,
            format = { getString(R.string.px_ad_percent, it) }, defaultText = getString(R.string.px_gt_default),
            message = R.string.px_ad_param_hysteresis_message,
        )
        val memory = screen.add(PreferenceCategory(prefContext)) {
            key = "memory"
            setTitle(R.string.px_ad_params_memory)
        }
        // The slow half-life is at least the fast one (the manager and the core
        // refuse anything else): each dialog checks the value against the other
        // key as it is now, typed or default.
        numberRow(
            memory, FAST, R.string.px_ad_param_fast, 5, 3_600, R.string.px_opt_seconds_hint,
            format = { duration(requireContext(), it) }, defaultText = getString(R.string.px_gt_default),
            message = R.string.px_ad_param_fast_message,
            check = { fast ->
                val slow = config?.number(SLOW) ?: SLOW_DEFAULT
                if (fast > slow) getString(R.string.px_ad_param_fast_too_long, duration(requireContext(), slow)) else null
            },
            defaultValue = FAST_DEFAULT,
        )
        numberRow(
            memory, SLOW, R.string.px_ad_param_slow, 5, 86_400, R.string.px_opt_seconds_hint,
            format = { duration(requireContext(), it) }, defaultText = getString(R.string.px_gt_default),
            message = R.string.px_ad_param_slow_message,
            check = { slow ->
                val fast = config?.number(FAST) ?: FAST_DEFAULT
                if (slow < fast) getString(R.string.px_ad_param_slow_too_short, duration(requireContext(), fast)) else null
            },
            defaultValue = SLOW_DEFAULT,
        )
        val measure = screen.add(PreferenceCategory(prefContext)) {
            key = "measure"
            setTitle(R.string.px_ad_params_measure)
        }
        numberRow(
            measure, "adaptive.probe-interval", R.string.px_ad_param_probe, 30, 86_400, R.string.px_opt_seconds_hint,
            format = { if (it == 0L) getString(R.string.px_ad_param_probe_off) else duration(requireContext(), it) },
            defaultText = getString(R.string.px_gt_default),
            message = R.string.px_ad_param_probe_message,
            // The manager takes 0 (never) or at least 30 s.
            orZero = true,
        )
        numberRow(
            measure, "adaptive.dial-timeout", R.string.px_ad_param_timeout, 1_000, 30_000, R.string.px_gt_ms_hint,
            format = { getString(R.string.px_gt_ms, it) }, defaultText = getString(R.string.px_gt_default),
            message = R.string.px_ad_param_timeout_message,
        )
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_ad_params_footer)
            order = Int.MAX_VALUE
        }
    }

    companion object {
        private const val FAST = "adaptive.fast-half-life"
        private const val SLOW = "adaptive.slow-half-life"
        // The core's defaults (mihomo patch 0006), which an unset key has.
        private const val FAST_DEFAULT = 60L
        private const val SLOW_DEFAULT = 300L

        fun open(context: Context) {
            Pages.open(context, AdaptiveParamsFragment::class.java, R.string.px_ad_params_title)
        }

        /** Seconds in the largest whole unit: "90 seconds", "5 minutes", "1 hour". */
        fun duration(context: Context, seconds: Long): String {
            val res = context.resources
            return when {
                seconds >= 3_600 && seconds % 3_600 == 0L ->
                    res.getQuantityString(R.plurals.px_ad_hours, (seconds / 3_600).toInt(), (seconds / 3_600).toInt())
                seconds >= 60 && seconds % 60 == 0L ->
                    res.getQuantityString(R.plurals.px_ad_minutes, (seconds / 60).toInt(), (seconds / 60).toInt())
                else -> res.getQuantityString(R.plurals.px_opt_seconds, seconds.toInt(), seconds.toInt())
            }
        }
    }
}
