/*
 * Copyright (C) 2026 The Lujarch Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lujarch.battery.settings

import android.content.Context
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.appString

/**
 * The two switches of Battery usage's menu, found by Settings search; a result opens Battery usage,
 * whose menu holds them. Their titles are Settings'.
 */
class BatteryContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = appString(context, SETTINGS, "advanced_battery_title") ?: return emptyList()
        return ROWS.mapNotNull { (key, title) ->
            appString(context, SETTINGS, title)?.let {
                SearchEntry(
                    key = key,
                    title = it,
                    summary = "",
                    keywords = "",
                    screenTitle = screen,
                    action = ACTION,
                    targetPackage = SETTINGS,
                )
            }
        }
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
        const val ACTION = "com.android.settings.battery.action.POWER_USAGE_ADVANCED"
        val ROWS =
            listOf(
                "lujarch_battery_record" to "lujarch_battery_menu_record",
                "lujarch_battery_full_history" to "lujarch_battery_menu_full_history",
            )
    }
}
