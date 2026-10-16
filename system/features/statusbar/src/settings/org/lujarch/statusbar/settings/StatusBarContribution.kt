/*
 * Copyright (C) 2026 The Lujarch Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lujarch.statusbar.settings

import android.content.Context
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.appString

/**
 * The switches of Status bar items that the target names, found by Settings search. The page and
 * its rows are SystemUI's: their titles are read from SystemUI, which holds them.
 */
class StatusBarContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = appString(context, SYSTEMUI, "lujarch_sb_page_title") ?: return emptyList()
        return ROWS.mapNotNull { (key, title) ->
            appString(context, SYSTEMUI, title)?.let {
                SearchEntry(
                    key = key,
                    title = it,
                    summary = "",
                    keywords = "",
                    screenTitle = screen,
                    action = ACTION,
                    targetPackage = SYSTEMUI,
                )
            }
        }
    }

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        /** Status bar items, which SystemUI opens at the row a search result names. */
        const val ACTION = "com.android.settings.action.STATUS_BAR_TUNER"
        /** Each row's key in Status bar items and the name of its title. */
        val ROWS =
            listOf(
                "group_privacy" to "lujarch_sb_group_privacy",
                "lujarch_privacy_chip" to "lujarch_sb_privacy_chip",
                "lujarch_privacy_dot" to "lujarch_sb_privacy_dot",
                "lujarch_privacy_ring" to "lujarch_sb_privacy_ring",
                "lujarch_privacy_location" to "lujarch_sb_privacy_location",
                "lujarch_lyrics" to "lujarch_sb_lyrics",
            )
    }
}
