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

package org.lujarch.vault.system

import android.database.Cursor
import android.database.MatrixCursor
import android.provider.SearchIndexablesContract
import android.provider.SearchIndexablesProvider
import org.lujarch.vault.R
import org.lujarch.vault.ui.HomeFragment
import org.lujarch.vault.ui.SearchKeys
import org.lujarch.vault.ui.SearchResultActivity
import org.lujarch.vault.ui.SettingsFragment

/**
 * The vault's pages in Settings search (by their titles and keywords in the three languages), under
 * the vault's home in Settings: Passwords, passkeys & accounts > Lujarch
 * Vault, the preferred service.
 * SettingsIntelligence queries every provider with the action
 * SEARCH_INDEXABLES_PROVIDER whose read and write permission is
 * READ_SEARCH_INDEXABLES and whose app is a system app
 * (PreIndexDataCollector.isWellKnownProvider). It builds a result's
 * breadcrumb from the row's class and screen title and from site map pairs
 * of classes it knows titles for (IndexDataConverter.convertSiteMapPairs,
 * SiteMapManager.buildBreadCrumb): each row carries the class and title of
 * the page it is on, and [querySiteMapPairs] puts the vault's home under
 * Settings' accounts page and the settings page under the home. Titles and
 * keywords are in the current language; the index is rebuilt when the
 * language or this app's version changes. Results open
 * [SearchResultActivity], which only search (and Settings) may start.
 */
class VaultSearchIndexablesProvider : SearchIndexablesProvider() {
    override fun onCreate(): Boolean = true

    override fun queryXmlResources(projection: Array<out String>?): Cursor =
        MatrixCursor(SearchIndexablesContract.INDEXABLES_XML_RES_COLUMNS)

    override fun queryNonIndexableKeys(projection: Array<out String>?): Cursor =
        MatrixCursor(SearchIndexablesContract.NON_INDEXABLES_KEYS_COLUMNS)

    override fun queryRawData(projection: Array<out String>?): Cursor {
        val ctx = context ?: return MatrixCursor(SearchIndexablesContract.INDEXABLES_RAW_COLUMNS)
        val cursor = MatrixCursor(SearchIndexablesContract.INDEXABLES_RAW_COLUMNS)
        val app = ctx.getString(R.string.app_name)
        val settings = ctx.getString(R.string.search_settings_title)
        val home = Screen(HOME_CLASS, app)
        val settingsPage = Screen(SETTINGS_CLASS, settings)
        fun row(key: String, title: Int, summary: Int, keywords: Int, screen: Screen, icon: Int) {
            val r = arrayOfNulls<Any>(SearchIndexablesContract.INDEXABLES_RAW_COLUMNS.size)
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_RANK] = RANK
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_TITLE] = ctx.getString(title)
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_SUMMARY_ON] = if (summary != 0) ctx.getString(summary) else null
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_KEYWORDS] = ctx.getString(keywords)
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_SCREEN_TITLE] = screen.title
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_CLASS_NAME] = screen.cls
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_ICON_RESID] = icon
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_ACTION] = ACTION_SEARCH_RESULT
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_PACKAGE] = ctx.packageName
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_CLASS] = SearchResultActivity::class.java.name
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_KEY] = key
            r[SearchIndexablesContract.COLUMN_INDEX_RAW_USER_ID] = -1
            cursor.addRow(r)
        }
        row(SearchKeys.HOME, R.string.app_name, 0, R.string.search_keywords_vault, home, R.drawable.ic_vault)
        row(SearchKeys.CODES, R.string.codes_title, 0, R.string.search_keywords_codes, home, R.drawable.ic_codes)
        row(SearchKeys.PASSWORDS, R.string.passwords_title, 0, R.string.search_keywords_passwords, home, R.drawable.ic_key)
        row(SearchKeys.SOURCES, R.string.sources_title, 0, R.string.search_keywords_sources, home, R.drawable.ic_setup)
        row(SearchKeys.SETTINGS, R.string.search_settings_title, R.string.settings_summary, R.string.search_keywords_settings, home, R.drawable.ic_settings)
        row(SearchKeys.CLIPBOARD, R.string.settings_clear_clipboard, 0, R.string.search_keywords_settings, settingsPage, R.drawable.ic_settings)
        row(SearchKeys.FORGET_POLICY, R.string.settings_forget_policy, 0, R.string.search_keywords_settings, settingsPage, R.drawable.ic_settings)
        row(SearchKeys.EXCLUDED, R.string.settings_excluded, 0, R.string.search_keywords_excluded, settingsPage, R.drawable.ic_settings)
        return cursor
    }

    /** The page a row is on: its class and title, which the breadcrumb shows. */
    private class Screen(val cls: String, val title: String)

    /**
     * The vault's home under Passwords, passkeys & accounts (Settings'
     * AccountDashboardFragment, where the preferred service's gear opens
     * it), the settings page under the home: (parent class, child class).
     * SettingsIntelligence adds the titles from the rows.
     */
    override fun querySiteMapPairs(): Cursor {
        val cursor = MatrixCursor(SearchIndexablesContract.SITE_MAP_COLUMNS)
        for ((parent, child) in listOf(ACCOUNTS_CLASS to HOME_CLASS, HOME_CLASS to SETTINGS_CLASS)) {
            cursor.addRow(arrayOf(parent, null, child, null))
        }
        return cursor
    }

    companion object {
        /** The action of the result intents; SearchResultActivity's filter. */
        const val ACTION_SEARCH_RESULT = "org.lujarch.vault.action.SEARCH_RESULT"
        private const val RANK = 2
        /** Settings' Passwords, passkeys & accounts page. */
        private const val ACCOUNTS_CLASS = "com.android.settings.accounts.AccountDashboardFragment"
        private val HOME_CLASS: String = HomeFragment::class.java.name
        private val SETTINGS_CLASS: String = SettingsFragment::class.java.name
    }
}
