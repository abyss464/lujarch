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

package org.lujarch.linux.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity

/**
 * The Linux development environment's pages as other parts of the system open them: the row in
 * System, Settings search and the Terminal app (its tile Remote access and the Mode Linux).
 */
class LinuxActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(LinuxPages.byAction(intent.action).title)

    override fun createFragment(): Fragment = LinuxPages.byAction(intent.action).fragment.getDeclaredConstructor().newInstance()
}

/** The pages Settings search and the other apps open, each by its own action. */
object LinuxPages {
    class Page(val action: String, val fragment: Class<out Fragment>, @StringRes val title: Int)

    const val ACTION_LINUX = "org.lujarch.linux.action.LINUX"
    const val ACTION_SERVICES = "org.lujarch.linux.action.SERVICES"
    const val ACTION_REMOTE = "org.lujarch.linux.action.REMOTE"

    private val main = Page(ACTION_LINUX, LinuxFragment::class.java, R.string.lnx_title)
    private val services = Page(ACTION_SERVICES, ServicesFragment::class.java, R.string.lnx_services)
    private val remote = Page(ACTION_REMOTE, RemoteFragment::class.java, R.string.lnx_remote)

    fun byAction(action: String?): Page =
        when (action) {
            ACTION_SERVICES -> services
            ACTION_REMOTE -> remote
            else -> main
        }
}

/** The Linux development environment in Settings search: its pages and every switch. */
class LinuxContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = context.getString(R.string.lnx_title)
        fun entry(key: String, @StringRes title: Int, @StringRes keywords: Int, action: String = LinuxPages.ACTION_LINUX) =
            SearchEntry(
                key = key,
                title = context.getString(title),
                summary = "",
                keywords = context.getString(keywords),
                screenTitle = screen,
                action = action,
            )
        val entries = mutableListOf(
            SearchEntry(
                key = "lujarch_linux",
                title = screen,
                summary = "",
                keywords = context.getString(R.string.lnx_search_keywords),
                screenTitle = "",
                action = LinuxPages.ACTION_LINUX,
            ),
            entry(LinuxFragment.KEY_ENABLED, R.string.lnx_use, R.string.lnx_search_keywords),
            entry(LinuxFragment.KEY_INSTALL, R.string.lnx_install, R.string.lnx_search_install_keywords),
            entry(LinuxFragment.KEY_SETUP, R.string.lnx_setup_again, R.string.lnx_search_install_keywords),
            entry(LinuxFragment.KEY_OPEN_KIND, R.string.lnx_open_kind, R.string.lnx_search_terminal_keywords),
            entry(LinuxFragment.KEY_COLORS, R.string.lnx_colors, R.string.lnx_search_terminal_keywords),
            entry(LinuxFragment.KEY_KEYS_ROW, R.string.lnx_keys_row, R.string.lnx_search_terminal_keywords),
            entry(LinuxFragment.KEY_ROOT_SESSIONS, R.string.lnx_root_sessions, R.string.lnx_search_root_keywords),
            entry(LinuxFragment.KEY_MEMORY, R.string.lnx_memory, R.string.lnx_search_memory_keywords),
            entry(LinuxFragment.KEY_SWAP, R.string.lnx_swap, R.string.lnx_search_memory_keywords),
            entry(LinuxFragment.KEY_DESKTOP, R.string.lnx_open_desktop, R.string.lnx_search_desktop_keywords),
            entry(LinuxFragment.KEY_DESKTOP_INSTALL, R.string.lnx_install_desktop, R.string.lnx_search_desktop_keywords),
            entry("lujarch_linux_services_page", R.string.lnx_services, R.string.lnx_search_services_keywords, LinuxPages.ACTION_SERVICES),
            entry("lujarch_linux_remote_page", R.string.lnx_remote, R.string.lnx_search_remote_keywords, LinuxPages.ACTION_REMOTE),
        )
        for ((part, texts) in LinuxFragment.LEND) {
            entries += entry(LinuxFragment.KEY_LEND_PREFIX + part, texts.first, R.string.lnx_search_lend_keywords)
        }
        return entries
    }
}

/**
 * The row in Settings > System: its live summary (`com.android.settings.summary_uri`, method
 * `getDynamicSummary`). Only Settings (the system uid) and this app are answered.
 */
class LinuxSummaryProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            Log.w(TAG, "the System row's summary refused to uid $uid")
            return null
        }
        if (method != METHOD_GET_SUMMARY) return null
        val context = context ?: return null
        val identity = Binder.clearCallingIdentity()
        return try {
            val status = runBlocking {
                withTimeoutOrNull(TIMEOUT_MS) {
                    try {
                        LinuxClient.call("getStatus") { it.status }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "the System row's summary: ${e.message}")
                        null
                    }
                }
            }
            Bundle().apply { putString(META_SUMMARY, LinuxPage.stateLabel(context, status)) }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val METHOD_GET_SUMMARY = "getDynamicSummary"
        const val META_SUMMARY = "com.android.settings.summary"
        const val TIMEOUT_MS = 3000L
    }
}
