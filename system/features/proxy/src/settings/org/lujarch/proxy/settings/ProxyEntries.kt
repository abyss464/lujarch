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

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.settings.AppInfoRow
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity
import org.lujarch.settings.pages.Errors

/** The proxy's state in one line, for the Settings tile in Network & internet and the Quick Settings tile. */
object ProxySummary {
    data class State(val info: ProxyInfo?, val mode: String?)

    suspend fun load(): State {
        val info = try {
            ProxyClient.info()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "proxy summary: ${e.message}")
            return State(null, null)
        }
        val mode = if (info.controllerUp) {
            try {
                Mihomo.configs().mode
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        return State(info, mode)
    }

    /** "On · Rule", "On · Running on the last working configuration", "Off". */
    fun text(context: Context, s: State): String {
        val info = s.info ?: return context.getString(R.string.status_unknown)
        if (!info.mihomoEnabled) return context.getString(R.string.px_summary_off)
        // Turned on a moment ago: the service has not started the core yet.
        if (info.mihomoState == ProxyInfo.STATE_OFF) {
            return context.getString(R.string.px_summary_on, context.getString(R.string.px_state_starting))
        }
        val detail = if (info.mihomoState == ProxyInfo.STATE_RUNNING) {
            ProxyFormat.modeLabel(context, s.mode) ?: ProxyFormat.stateLabel(context, info.mihomoState)
        } else {
            ProxyFormat.stateLabel(context, info.mihomoState)
        }
        return context.getString(R.string.px_summary_on, detail)
    }

    /**
     * Tells Settings that the state changed: the Network & internet tile's summary and switch (the
     * Quick Settings tile reads it whenever the panel shows it). Settings is told again at every
     * change of the state for a minute after, while the service starts or stops the core.
     */
    fun changed(context: Context) {
        val app = context.applicationContext
        notify(app)
        follower?.cancel()
        follower = followScope.launch {
            var last: String? = null
            repeat(FOLLOW_SECONDS) {
                val state = try {
                    ProxyClient.status().state
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "proxy summary: ${e.message}")
                    null
                }
                if (state != last) {
                    if (last != null) notify(app)
                    last = state
                }
                delay(1000)
            }
        }
    }

    private fun notify(context: Context) {
        context.contentResolver.notifyChange(ProxySummaryProvider.SUMMARY_URI, null)
        context.contentResolver.notifyChange(ProxySummaryProvider.SWITCH_URI, null)
    }

    private const val FOLLOW_SECONDS = 60
    private val followScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var follower: Job? = null
}

/**
 * The proxy's pages as other parts of the system open them: the Proxy row in Network & internet,
 * the Quick Settings tile's long press, App info's Proxy row ([ACTION_APP_ROUTE] with the app's
 * package) and Settings search ([ProxyPages]). A search result's key, when it names a row, is
 * scrolled into view.
 */
class ProxyActivity : BaseActivity() {
    private val appPackage: String?
        get() = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)?.takeIf { intent.action == ACTION_APP_ROUTE }

    override fun pageTitle(): CharSequence {
        if (appPackage != null) return getText(R.string.routing_title)
        return getText(ProxyPages.byAction(intent.action)?.title ?: R.string.px_title)
    }

    override fun createFragment(): Fragment? {
        if (intent.action != ACTION_APP_ROUTE) {
            val page = ProxyPages.byAction(intent.action) ?: ProxyPages.all.first()
            return page.fragment.getDeclaredConstructor().newInstance()
        }
        val pkg = appPackage
        if (pkg == null || !isPackageName(pkg)) {
            Log.w(TAG, "APP_ROUTE without a valid ${Intent.EXTRA_PACKAGE_NAME}")
            return null
        }
        val label = try {
            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        return AppRouteFragment().apply { arguments = AppRouteFragment.arguments(pkg, label) }
    }

    companion object {
        const val ACTION_PROXY = "org.lujarch.proxy.action.PROXY"
        const val ACTION_APP_ROUTE = "org.lujarch.proxy.action.APP_ROUTE"

        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun isPackageName(name: String): Boolean = name.length <= 255 && PACKAGE.matches(name)
    }
}

/** The proxy's pages that Settings search finds, each opened by its own action. */
object ProxyPages {
    class Page(
        val key: String,
        val action: String,
        val fragment: Class<out Fragment>,
        @StringRes val title: Int,
        @StringRes val keywords: Int,
    )

    private const val ACTION = "org.lujarch.proxy.action."

    val all = listOf(
        Page("lujarch_proxy", ProxyActivity.ACTION_PROXY, ProxyFragment::class.java, R.string.px_title, R.string.search_proxy_keywords),
        Page("lujarch_proxy_status", ACTION + "STATUS", ProxyStatusFragment::class.java, R.string.px_status_page_title, R.string.search_proxy_status_keywords),
        Page("lujarch_proxy_routing", ACTION + "APPS", AppRoutingFragment::class.java, R.string.routing_title, R.string.search_proxy_routing_keywords),
        Page("lujarch_proxy_include", ACTION + "INCLUDE", TunIncludeFragment::class.java, R.string.px_include_title, R.string.search_proxy_routing_keywords),
        Page("lujarch_proxy_groups", ACTION + "GROUPS", GroupsFragment::class.java, R.string.px_groups_title, R.string.search_proxy_groups_keywords),
        Page("lujarch_proxy_subscriptions", ACTION + "SUBSCRIPTIONS", SubscriptionsFragment::class.java, R.string.px_subs_title, R.string.search_proxy_subs_keywords),
        Page("lujarch_proxy_locations", ACTION + "LOCATIONS", NodeLocationsFragment::class.java, R.string.px_loc_title, R.string.search_proxy_locations_keywords),
        Page("lujarch_proxy_connections", ACTION + "CONNECTIONS", ConnectionsFragment::class.java, R.string.px_connections_title, R.string.search_proxy_connections_keywords),
        Page("lujarch_proxy_logs", ACTION + "LOGS", LogsFragment::class.java, R.string.px_logs_title, R.string.search_proxy_logs_keywords),
        Page("lujarch_proxy_rules", ACTION + "RULES", RulesFragment::class.java, R.string.px_rules_title, R.string.search_proxy_rules_keywords),
        Page("lujarch_proxy_core", ACTION + "CORE", CoreSettingsFragment::class.java, R.string.px_core_title, R.string.search_proxy_core_keywords),
        Page("lujarch_proxy_adaptive", ACTION + "ADAPTIVE", AdaptiveParamsFragment::class.java, R.string.px_ad_params_title, R.string.search_proxy_adaptive_keywords),
        Page("lujarch_proxy_files", ACTION + "FILES", ConfigFilesFragment::class.java, R.string.px_files_title, R.string.search_proxy_files_keywords),
        Page("lujarch_proxy_dns", ACTION + "DNS", DnsFragment::class.java, R.string.px_dnsc_title, R.string.search_proxy_dns_keywords),
        Page("lujarch_proxy_sniffer", ACTION + "SNIFFER", SnifferFragment::class.java, R.string.px_snf_title, R.string.search_proxy_sniffer_keywords),
        Page("lujarch_proxy_geodata", ACTION + "GEODATA", GeodataFragment::class.java, R.string.px_core_geo_category, R.string.search_proxy_geodata_keywords),
    )

    fun byAction(action: String?): Page? = all.firstOrNull { it.action == action }
}

/** The proxy in App info (each app's route) and in Settings search. */
class ProxyContribution : Contribution {
    override fun appInfoRows(context: Context, packageName: String, userId: Int): List<AppInfoRow> {
        val routing = runBlocking {
            withTimeoutOrNull(ROW_TIMEOUT_MS) {
                try {
                    AppRouteTasks.load()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "App info: per-app routing unavailable: ${e.message}")
                    null
                }
            }
        } ?: AppRouteTasks.routing.value
        val summary = routing?.let { appRouteLabel(context, it.route(packageName)) }
            ?: context.getString(R.string.status_unknown)
        return listOf(
            AppInfoRow(
                key = "lujarch_proxy_route",
                title = context.getString(R.string.px_title),
                summary = summary,
                action = ProxyActivity.ACTION_APP_ROUTE,
            )
        )
    }

    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = context.getString(R.string.px_title)
        val pages = ProxyPages.all.map { page ->
            SearchEntry(
                key = page.key,
                title = context.getString(page.title),
                summary = "",
                keywords = context.getString(page.keywords),
                screenTitle = if (page.action == ProxyActivity.ACTION_PROXY) "" else screen,
                action = page.action,
            )
        }
        val switches = listOf(
            SearchEntry(ProxyFragment.KEY_ENABLED, context.getString(R.string.px_main_switch), "",
                context.getString(R.string.search_proxy_keywords), screen, ProxyActivity.ACTION_PROXY),
            SearchEntry(ProxyFragment.KEY_REPAIR, context.getString(R.string.px_repair_title),
                context.getString(R.string.px_repair_summary), context.getString(R.string.search_proxy_repair_keywords),
                screen, ProxyActivity.ACTION_PROXY),
            SearchEntry(CoreSettingsFragment.KEY_IPV6_BLOCK, context.getString(R.string.px_ipv6_block),
                context.getString(R.string.px_ipv6_block_summary), context.getString(R.string.search_proxy_ipv6_keywords),
                context.getString(R.string.px_core_title), ProxyPages.all.first { it.key == "lujarch_proxy_core" }.action),
        )
        return pages + switches
    }

    private companion object {
        const val ROW_TIMEOUT_MS = 3000L
    }
}

/**
 * Quick Settings tile "Proxy": the state and mode as subtitle; a tap turns the
 * proxy on or off through the same daemon command as the Proxy page's switch
 * (after unlocking on a secure lock screen); a long press opens the Proxy page.
 */
class ProxyTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var enabled: Boolean? = null
    private var busy = false

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch {
                // While the panel shows the tile: follow the service as it starts or stops.
                while (true) {
                    update()
                    delay(if (busy) 1000L else 3000L)
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isSecure && isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        val on = enabled ?: return
        if (busy) return
        busy = true
        qsTile?.let {
            it.state = Tile.STATE_UNAVAILABLE
            it.subtitle = getString(if (on) R.string.px_tile_turning_off else R.string.px_tile_turning_on)
            it.updateTile()
        }
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch {
            try {
                ProxyClient.setEnabled(!on)
                enabled = !on
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "proxy tile: turning the proxy ${if (on) "off" else "on"} failed: ${e.message}", e)
            }
            busy = false
            ProxySummary.changed(applicationContext)
            update()
        }
    }

    private suspend fun update() {
        val state = ProxySummary.load()
        val tile = qsTile ?: return
        val info = state.info
        enabled = info?.mihomoEnabled
        tile.label = getString(R.string.px_title)
        when {
            info == null -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = getString(R.string.status_unknown)
            }
            busy -> return
            info.mihomoEnabled -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = if (info.mihomoState == ProxyInfo.STATE_RUNNING) {
                    ProxyFormat.modeLabel(this, state.mode) ?: ProxyFormat.stateLabel(this, info.mihomoState)
                } else {
                    ProxyFormat.stateLabel(this, info.mihomoState)
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.px_summary_off)
            }
        }
        tile.updateTile()
    }
}

/**
 * The Proxy tile in Settings > Network & internet: its live summary
 * (`com.android.settings.summary_uri`, method `getDynamicSummary`) and its
 * inline switch (`com.android.settings.switch_uri`, methods `isChecked` and
 * `onCheckedChanged`, the calls of SettingsLib's EntriesProvider), the same
 * action as the Quick Settings tile and the Proxy page's main switch
 * (the row carries the concept's one switch).
 * Settings observes [SUMMARY_URI] and [SWITCH_URI]. Only Settings (the
 * system uid) and this app are answered.
 */
class ProxySummaryProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            Log.w(TAG, "proxy tile refused to uid $uid")
            return null
        }
        val context = context ?: return null
        val identity = Binder.clearCallingIdentity()
        return try {
            when (method) {
                METHOD_GET_SUMMARY -> {
                    val state = runBlocking { withTimeoutOrNull(TIMEOUT_MS) { ProxySummary.load() } }
                    Bundle().apply {
                        putString(
                            META_SUMMARY,
                            state?.let { ProxySummary.text(context, it) } ?: context.getString(R.string.status_unknown),
                        )
                    }
                }
                METHOD_IS_CHECKED -> {
                    val state = runBlocking { withTimeoutOrNull(TIMEOUT_MS) { ProxySummary.load() } }
                    val info = state?.info
                    Bundle().apply {
                        putBoolean(EXTRA_CHECKED, info != null && info.mihomoEnabled)
                    }
                }
                METHOD_ON_CHECKED_CHANGED -> setEnabled(context, extras?.getBoolean(EXTRA_CHECKED) ?: return null)
                else -> null
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * The switch turned [on]. A failure keeps the switch as
     * it was (Settings flips it back) and says why in Settings' toast.
     */
    private fun setEnabled(context: Context, on: Boolean): Bundle {
        val failure: Exception? = runBlocking {
            try {
                ProxyClient.setEnabled(on)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }
        val result = Bundle()
        if (failure == null) {
            Log.i(TAG, "proxy turned ${if (on) "on" else "off"} from the Network & internet tile")
            result.putBoolean(EXTRA_SET_CHECKED_ERROR, false)
        } else {
            Log.e(TAG, "proxy tile: turning the proxy ${if (on) "on" else "off"} failed: ${failure.message}", failure)
            result.putBoolean(EXTRA_SET_CHECKED_ERROR, true)
            result.putString(
                EXTRA_SET_CHECKED_ERROR_MESSAGE,
                context.getString(if (on) R.string.error_proxy_on else R.string.error_proxy_off) + "\n" +
                    Errors.describe(context, failure),
            )
        }
        ProxySummary.changed(context)
        return result
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "org.lujarch.proxy.tile"
        private const val METHOD_GET_SUMMARY = "getDynamicSummary"
        /** SettingsLib EntriesProvider's switch calls and extras. */
        private const val METHOD_IS_CHECKED = "isChecked"
        private const val METHOD_ON_CHECKED_CHANGED = "onCheckedChanged"
        private const val EXTRA_CHECKED = "checked_state"
        private const val EXTRA_SET_CHECKED_ERROR = "set_checked_error"
        private const val EXTRA_SET_CHECKED_ERROR_MESSAGE = "set_checked_error_message"
        private const val META_SUMMARY = "com.android.settings.summary"
        private const val TIMEOUT_MS = 3000L
        private const val KEY = "lujarch_proxy"
        /** What Settings builds from the tile's summary_uri, switch_uri and keyhint (TileUtils.getCompleteUri). */
        val SUMMARY_URI: Uri = Uri.parse("content://$AUTHORITY/$METHOD_GET_SUMMARY/$KEY")
        val SWITCH_URI: Uri = Uri.parse("content://$AUTHORITY/$METHOD_IS_CHECKED/$KEY")
    }
}
