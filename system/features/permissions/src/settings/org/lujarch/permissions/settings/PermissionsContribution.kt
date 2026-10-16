/*
 * Copyright (C) 2026 The Lujarch Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lujarch.permissions.settings

import android.content.Context
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.appString

/**
 * What the permissions add to the permission controller's pages, found by Settings search: Explain
 * why apps stop in the permission manager and Other access in Special app access; their titles
 * are the permission controller's.
 */
class PermissionsContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> =
        listOfNotNull(
            entry(
                context,
                key = "lujarch_explain_crashes",
                summary = "lujarch_explain_crashes_summary",
                screen = "app_permission_manager",
                action = "android.intent.action.MANAGE_PERMISSIONS",
            ),
            entry(
                context,
                key = "lujarch_other_access",
                summary = null,
                screen = "special_app_access",
                action = "android.intent.action.MANAGE_SPECIAL_APP_ACCESSES",
            ),
        )

    /** The row [key] of the controller's page [screen], titled by the controller's string [key]. */
    private fun entry(
        context: Context,
        key: String,
        summary: String?,
        screen: String,
        action: String,
    ): SearchEntry? {
        val title = appString(context, CONTROLLER, key) ?: return null
        val screenTitle = appString(context, CONTROLLER, screen) ?: return null
        return SearchEntry(
            key = key,
            title = title,
            summary = summary?.let { appString(context, CONTROLLER, it) } ?: "",
            keywords = "",
            screenTitle = screenTitle,
            action = action,
            targetPackage = CONTROLLER,
        )
    }

    private companion object {
        const val CONTROLLER = "com.android.permissioncontroller"
    }
}
