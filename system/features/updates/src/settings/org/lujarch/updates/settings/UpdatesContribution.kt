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

package org.lujarch.updates.settings

import android.content.Context
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.appString

/**
 * Confirm automatically, in System updates' preferences, and Install local update, on System
 * updates' page, found by Settings search; the latter is titled by System updates' own string.
 */
class UpdatesContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> =
        listOfNotNull(
            SearchEntry(
                key = "lujarch_updates_auto_confirm",
                title = context.getString(R.string.lujarch_updates_auto_confirm),
                summary = context.getString(R.string.lujarch_updates_auto_confirm_summary),
                keywords = context.getString(R.string.lujarch_updates_auto_confirm_keywords),
                screenTitle = context.getString(R.string.lujarch_updates_screen),
                action = "org.lujarch.updates.action.UPDATE_PREFERENCES",
            ),
            appString(context, UPDATER, "local_update_import")?.let {
                SearchEntry(
                    key = "lujarch_updates_install_local",
                    title = it,
                    summary = appString(context, UPDATER, "local_update_import_summary") ?: "",
                    keywords = "",
                    screenTitle = context.getString(R.string.lujarch_updates_screen),
                    action = "android.settings.SYSTEM_UPDATE_SETTINGS",
                    targetPackage = UPDATER,
                )
            },
        )

    private companion object {
        const val UPDATER = "org.lineageos.updater"
    }
}
