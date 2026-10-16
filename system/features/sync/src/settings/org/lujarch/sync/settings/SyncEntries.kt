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

package org.lujarch.sync.settings

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
 * Sync's pages as other parts of the system open them: the Sync row in Connected devices and
 * Settings search ([SyncPages]).
 */
class SyncActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(SyncPages.byAction(intent.action)?.title ?: R.string.sync_title)

    override fun createFragment(): Fragment =
        (SyncPages.byAction(intent.action) ?: SyncPages.all.first()).fragment.getDeclaredConstructor().newInstance()
}

/** Sync's pages that Settings search finds, each opened by its own action. */
object SyncPages {
    class Page(
        val key: String,
        val action: String,
        val fragment: Class<out Fragment>,
        @StringRes val title: Int,
        @StringRes val keywords: Int,
        /** The page's switches Settings search finds: each preference's key and title. */
        val switches: List<Pair<String, Int>> = emptyList(),
    )

    const val ACTION_SYNC = "org.lujarch.sync.action.SYNC"
    private const val ACTION = "org.lujarch.sync.action."

    val all = listOf(
        Page("lujarch_sync", ACTION_SYNC, SyncFragment::class.java, R.string.sync_title, R.string.search_sync_keywords,
            listOf("wifi_only" to R.string.sync_wifi_only, "charging_only" to R.string.sync_charging_only)),
        Page("lujarch_sync_identity", ACTION + "IDENTITY", IdentityFragment::class.java,
            R.string.sync_identity_title, R.string.search_sync_identity_keywords),
        Page("lujarch_sync_network", ACTION + "NETWORK", SyncOptionsFragment::class.java,
            R.string.sync_network_title, R.string.search_sync_network_keywords,
            listOf("globalAnnounceEnabled" to R.string.sync_global_discovery,
                "localAnnounceEnabled" to R.string.sync_local_discovery,
                "relaysEnabled" to R.string.sync_relays, "natEnabled" to R.string.sync_nat)),
    )

    fun byAction(action: String?): Page? = all.firstOrNull { it.action == action }
}

/** Sync in Settings search. */
class SyncContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = context.getString(R.string.sync_title)
        return SyncPages.all.flatMap { page ->
            listOf(
                SearchEntry(
                    key = page.key,
                    title = context.getString(page.title),
                    summary = "",
                    keywords = context.getString(page.keywords),
                    screenTitle = if (page.action == SyncPages.ACTION_SYNC) "" else screen,
                    action = page.action,
                )
            ) + page.switches.map { (key, title) ->
                SearchEntry(
                    key = key,
                    title = context.getString(title),
                    summary = "",
                    keywords = context.getString(page.keywords),
                    screenTitle = context.getString(page.title),
                    action = page.action,
                )
            }
        }
    }
}

/**
 * The Sync row in Settings > Connected devices: its live summary (`com.android.settings.summary_uri`,
 * method `getDynamicSummary`). Only Settings (the system uid) and this app are answered.
 */
class SyncSummaryProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            Log.w(TAG, "sync row refused to uid $uid")
            return null
        }
        if (method != METHOD_GET_SUMMARY) return null
        val context = context ?: return null
        val identity = Binder.clearCallingIdentity()
        return try {
            val info = runBlocking {
                withTimeoutOrNull(TIMEOUT_MS) {
                    try {
                        SyncClient.info()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "sync summary: ${e.message}")
                        null
                    }
                }
            }
            Bundle().apply {
                putString(
                    META_SUMMARY,
                    info?.let { syncStateSummary(context, it) } ?: context.getString(R.string.status_unknown),
                )
            }
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
