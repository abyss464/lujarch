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

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.BidiFormatter
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.ZeroStatePreference
import java.text.Collator
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * Live connections (GET /connections polled), newest first, with the app
 * that made each one (label and icon) and a filter by app. A row opens the
 * details; closing a connection is a separate button there.
 */
class ConnectionsFragment : BasePreferenceFragment(), MenuProvider {
    private lateinit var totalsPref: Preference
    private lateinit var filterPref: Preference
    private lateinit var listCategory: PreferenceCategory
    private lateinit var emptyPref: ZeroStatePreference
    private val connectionPrefs = HashMap<String, Preference>()
    private var snapshot: ConnectionsSnapshot? = null
    private var connections: Map<String, Connection> = emptyMap()
    private val apps = HashMap<String, AppLabel?>()
    /** Key of the app filtered on ([appKey]), or null for all apps. */
    private var filter: String? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private class AppLabel(val label: String, val icon: Drawable?)

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        filter = savedInstanceState?.getString(STATE_FILTER)
        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        totalsPref = header.add(Preference(prefContext)) {
            key = "totals"
            setTitle(PagesR.string.loading)
            isSelectable = false
        }
        filterPref = header.add(Preference(prefContext)) {
            key = "filter"
            setTitle(R.string.px_conn_filter)
            setIcon(R.drawable.ic_px_filter)
            setOnPreferenceClickListener {
                chooseFilter()
                true
            }
        }
        listCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "connections"
            setTitle(R.string.px_conn_active)
            isOrderingAsAdded = false
            isVisible = false
        }
        emptyPref = screen.add(ZeroStatePreference(prefContext)) {
            key = "empty"
            setIcon(R.drawable.ic_connections)
            setTitle(R.string.px_conn_empty)
            isVisible = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, filter)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    attempt("connections", R.string.error_load_connections) { Mihomo.connections() }?.let {
                        snapshot = it
                        render()
                    }
                    withTimeoutOrNull(POLL_MS) { wake.receive() }
                }
            }
        }
    }

    override fun refresh() {
        wake.trySend(Unit)
    }

    /**
     * Groups connections by app: the package the core named, else the core
     * itself (its own DNS and health-check connections have the type "Inner"
     * and no uid), else the uid, else none.
     */
    private fun appKey(c: Connection): String? = when {
        c.process.isNotEmpty() -> c.process
        c.type.equals(TYPE_INNER, ignoreCase = true) -> KEY_CORE
        c.uid >= 0 -> "uid:${c.uid}"
        else -> null
    }

    private fun app(c: Connection): AppLabel? {
        val key = appKey(c) ?: return null
        if (apps.containsKey(key)) return apps[key]
        if (key == KEY_CORE) {
            return AppLabel(getString(R.string.px_conn_app_core), requireContext().getDrawable(R.drawable.ic_proxy))
                .also { apps[key] = it }
        }
        val pm = requireContext().packageManager
        val packages = when {
            c.process.isNotEmpty() -> listOf(c.process)
            else -> pm.getPackagesForUid(c.uid)?.toList().orEmpty()
        }
        var result: AppLabel? = null
        for (name in packages) {
            try {
                val info = pm.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0))
                result = AppLabel(info.loadLabel(pm).toString(), info.loadIcon(pm))
                break
            } catch (e: PackageManager.NameNotFoundException) {
                // Not a package (a plain process name): shown as is.
            }
        }
        if (result == null && c.process.isNotEmpty()) result = AppLabel(c.process, null)
        // A uid without a package (a system process): named by its uid, never shown as a bare number.
        if (result == null && c.process.isEmpty() && c.uid >= 0) {
            result = AppLabel(
                if (c.uid == 0) getString(R.string.px_conn_app_root) else getString(R.string.px_conn_app_uid, c.uid),
                null,
            )
        }
        apps[key] = result
        return result
    }

    private fun render() {
        val context = context ?: return
        val snapshot = snapshot ?: return
        val all = snapshot.connections
        val filtered = filter?.let { f -> all.filter { appKey(it) == f } } ?: all
        totalsPref.title = resources.getQuantityString(R.plurals.px_conn_count, all.size, all.size)
        totalsPref.summary = getString(
            R.string.px_conn_totals,
            Format.upDown(context, Format.bytes(context, snapshot.uploadTotal), Format.bytes(context, snapshot.downloadTotal)),
        )
        filterPref.summary = filter?.let { f ->
            val label = all.firstOrNull { appKey(it) == f }?.let { app(it)?.label } ?: f.removePrefix("uid:")
            resources.getQuantityString(R.plurals.px_conn_filter_app, filtered.size, label, filtered.size)
        } ?: getString(R.string.px_conn_filter_all)

        // Newest first; the controller's start times are RFC 3339 strings.
        val shown = filtered.sortedByDescending { it.start }.take(MAX_SHOWN)
        connections = shown.associateBy { it.id }
        connectionPrefs.keys.filter { it !in connections }.forEach { id ->
            connectionPrefs.remove(id)?.let { listCategory.removePreference(it) }
        }
        shown.forEachIndexed { index, connection ->
            val pref = connectionPrefs.getOrPut(connection.id) {
                listCategory.add(Preference(prefContext)) {
                    key = "connection:" + connection.id
                    isIconSpaceReserved = true
                    setOnPreferenceClickListener {
                        showDetails(connection.id)
                        true
                    }
                }
            }
            pref.order = index
            pref.title = connection.target
            pref.summary = summary(connection)
            app(connection)?.icon?.let { if (pref.icon !== it) pref.icon = it }
        }
        listCategory.isVisible = shown.isNotEmpty()
        listCategory.title = if (filtered.size > shown.size) {
            getString(R.string.px_conn_active_limited, shown.size)
        } else {
            getString(R.string.px_conn_active)
        }
        emptyPref.setTitle(if (filter == null) R.string.px_conn_empty else R.string.px_conn_empty_app)
        emptyPref.isVisible = shown.isEmpty()
    }

    /** Only what the core reported: a missing app or rule is left out, not shown as unknown. */
    private fun summary(c: Connection): String {
        val context = requireContext()
        val lines = ArrayList<String>(4)
        ProxyFormat.join(context, listOf(app(c)?.label, c.network.uppercase().ifEmpty { null }))
            .takeIf { it.isNotEmpty() }?.let(lines::add)
        ruleText(c)?.let { lines.add(getString(R.string.px_conn_rule, it)) }
        if (c.chain.isNotEmpty()) lines.add(getString(R.string.px_conn_chain, chainText(c)))
        lines.add(Format.upDown(context, Format.bytes(context, c.upload), Format.bytes(context, c.download)))
        return lines.joinToString("\n")
    }

    /**
     * The outbound chain; DIRECT and REJECT in words. The arrow follows the layout direction
     * and each name is isolated, so a right-to-left line reads from the first hop on the right.
     */
    private fun chainText(c: Connection): String {
        val bidi = BidiFormatter.getInstance()
        return c.chain.joinToString(getString(R.string.px_chain_separator)) {
            bidi.unicodeWrap(RulesFragment.targetLabel(requireContext(), it))
        }
    }

    private fun ruleText(c: Connection): String? = when {
        c.rule.isEmpty() -> null
        c.rulePayload.isEmpty() -> c.rule
        else -> getString(R.string.px_conn_rule_payload, c.rule, c.rulePayload)
    }

    /** The apps of the connections the page shows are the dialog's: a recreated page offers the same. */
    private fun chooseFilter() {
        val all = snapshot?.connections.orEmpty()
        val counts = LinkedHashMap<String, Int>()
        all.forEach { c -> appKey(c)?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        val collator = Collator.getInstance()
        val names = counts.keys.associateWith { k -> all.first { appKey(it) == k }.let { app(it)?.label } }
        val keys = counts.keys.sortedWith { a, b -> collator.compare(names[a] ?: a, names[b] ?: b) }
        val current = filter
        showDialog("px-connections-filter") { d ->
            val labels = ArrayList<String>()
            labels.add(getString(R.string.px_conn_filter_all))
            keys.forEach { k ->
                val label = names[k] ?: k.removePrefix("uid:")
                labels.add(resources.getQuantityString(R.plurals.px_conn_filter_app, counts[k] ?: 0, label, counts[k] ?: 0))
            }
            val checked = current?.let { keys.indexOf(it) + 1 } ?: 0
            AlertDialog.Builder(d.context)
                .setTitle(R.string.px_conn_filter)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                    filter = if (which == 0) null else keys[which - 1]
                    dialog.dismiss()
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    /** The connection as the page showed it is the dialog's. */
    private fun showDetails(id: String) {
        val c = connections[id] ?: return
        val appLabel = app(c)?.label
        showDialog("px-connection") { d -> detailsDialog(d.context, c, appLabel) }
    }

    private fun detailsDialog(context: Context, c: Connection, appLabel: String?): Dialog {
        val rows = ArrayList<String>()
        fun row(label: Int, value: String?) {
            if (!value.isNullOrEmpty()) rows.add(getString(R.string.detail_row, getString(label), value))
        }
        row(R.string.px_conn_detail_host, c.host)
        row(R.string.px_conn_detail_destination, joinHostPort(c.destinationIp, c.destinationPort))
        row(R.string.px_conn_detail_source, joinHostPort(c.sourceIp, c.sourcePort))
        row(R.string.px_conn_detail_app, listOfNotNull(appLabel, c.process.ifEmpty { null }).distinct().joinToString(" / "))
        row(
            R.string.px_conn_detail_network,
            listOf(c.network.uppercase(), c.type.takeUnless { it.equals(TYPE_INNER, ignoreCase = true) } ?: "")
                .filter { it.isNotEmpty() }.joinToString(" / "),
        )
        row(R.string.px_conn_detail_rule, ruleText(c))
        row(R.string.px_conn_detail_chain, chainText(c))
        row(R.string.px_conn_detail_upload, Format.bytes(context, c.upload))
        row(R.string.px_conn_detail_download, Format.bytes(context, c.download))
        startMillis(c.start)?.let { row(R.string.px_conn_detail_start, Format.time(context, it)) }
        return AlertDialog.Builder(context)
            .setTitle(c.target)
            .setMessage(rows.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.px_conn_close) { _, _ -> close(c.id) }
            .create()
    }

    private fun joinHostPort(host: String, port: String): String = when {
        host.isEmpty() -> ""
        port.isEmpty() -> host
        host.contains(':') -> "[$host]:$port"
        else -> "$host:$port"
    }

    private fun startMillis(start: String): Long? = try {
        if (start.isEmpty()) null else OffsetDateTime.parse(start).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        Log.w(TAG, "unexpected connection start time: $start")
        null
    }

    private fun close(id: String) {
        launchUi {
            act(R.string.error_close_connection) { Mihomo.closeConnection(id) }
            refresh()
        }
    }

    private fun closeAll() {
        confirm(
            "px-connections-close-all",
            R.string.px_conn_close_all,
            { getString(R.string.px_conn_close_all_confirm) },
            R.string.px_conn_close_all,
        ) {
            launchUi {
                act(R.string.error_close_connection) { Mihomo.closeAllConnections() }
                refresh()
            }
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menu.add(Menu.NONE, MENU_CLOSE_ALL, 0, R.string.px_conn_close_all)
            .setIcon(R.drawable.ic_close_all)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != MENU_CLOSE_ALL) return false
        closeAll()
        return true
    }

    companion object {
        private const val POLL_MS = 2000L
        private const val MAX_SHOWN = 200
        private const val MENU_CLOSE_ALL = 1
        private const val STATE_FILTER = "filter"
        /** Connection type of the core's own connections (DNS, health checks). */
        private const val TYPE_INNER = "Inner"
        private const val KEY_CORE = "core:"
    }
}
