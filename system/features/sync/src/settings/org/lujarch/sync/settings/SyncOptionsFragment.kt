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

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog
import org.lujarch.settings.pages.InputDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * How this device finds and reaches the others: discovery and its servers,
 * where this device listens, relays and their servers, NAT traversal, how
 * often an unreachable device is tried again, rate limits. Every list shows
 * what is set and can go back to Syncthing's default.
 */
class SyncOptionsFragment : SyncPageFragment() {
    private val switches = LinkedHashMap<String, SwitchPreferenceCompat>()
    private lateinit var serversPref: Preference
    private lateinit var listenPref: Preference
    private lateinit var relayServersPref: Preference
    private lateinit var reconnectPref: Preference
    private lateinit var sendPref: Preference
    private lateinit var recvPref: Preference
    private var options: SyncOptions? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val discovery = screen.add(PreferenceCategory(prefContext)) {
            key = "discovery"
            setTitle(R.string.sync_discovery_category)
        }
        addSwitch(discovery, "globalAnnounceEnabled", R.string.sync_global_discovery, R.string.sync_global_discovery_summary)
        serversPref = discovery.add(Preference(prefContext)) {
            key = "discovery_servers"
            setTitle(R.string.sync_discovery_servers)
            isEnabled = false
            setOnPreferenceClickListener {
                editServers()
                true
            }
        }
        addSwitch(discovery, "localAnnounceEnabled", R.string.sync_local_discovery, R.string.sync_local_discovery_summary)

        val connections = screen.add(PreferenceCategory(prefContext)) {
            key = "connections"
            setTitle(R.string.sync_connections_category)
        }
        listenPref = connections.add(Preference(prefContext)) {
            key = "listen"
            setTitle(R.string.sync_listen)
            isEnabled = false
            setOnPreferenceClickListener {
                editListen()
                true
            }
        }
        addSwitch(connections, "relaysEnabled", R.string.sync_relays, R.string.sync_relays_summary)
        relayServersPref = connections.add(Preference(prefContext)) {
            key = "relay_servers"
            setTitle(R.string.sync_relay_servers)
            isEnabled = false
            setOnPreferenceClickListener {
                editRelays()
                true
            }
        }
        addSwitch(connections, "natEnabled", R.string.sync_nat, R.string.sync_nat_summary)
        reconnectPref = connections.add(Preference(prefContext)) {
            key = "reconnect"
            setTitle(R.string.sync_reconnect)
            isEnabled = false
            setOnPreferenceClickListener {
                editReconnect()
                true
            }
        }

        val rates = screen.add(PreferenceCategory(prefContext)) {
            key = "rates"
            setTitle(R.string.sync_rates_category)
        }
        sendPref = rates.add(Preference(prefContext)) {
            key = "send"
            setTitle(R.string.sync_rate_send)
            isEnabled = false
            setOnPreferenceClickListener {
                editRate(R.string.sync_rate_send, "maxSendKbps", options?.maxSendKbps ?: 0)
                true
            }
        }
        recvPref = rates.add(Preference(prefContext)) {
            key = "recv"
            setTitle(R.string.sync_rate_recv)
            isEnabled = false
            setOnPreferenceClickListener {
                editRate(R.string.sync_rate_recv, "maxRecvKbps", options?.maxRecvKbps ?: 0)
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_network_footer)
        }
    }

    private fun addSwitch(category: PreferenceCategory, option: String, @StringRes title: Int, @StringRes summary: Int) {
        switches[option] = category.add(SwitchPreferenceCompat(prefContext)) {
            key = option
            setTitle(title)
            setSummary(summary)
            isPersistent = false
            isEnabled = false
            setOnPreferenceChangeListener { _, value ->
                change(R.string.error_sync_change, { Syncthing.patchOptions(JSONObject().put(option, value as Boolean)) })
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            val o = attempt("options", R.string.error_load_sync) { Syncthing.options() } ?: return@launchUi
            options = o
            switches["globalAnnounceEnabled"]?.isChecked = o.globalAnnounce
            switches["localAnnounceEnabled"]?.isChecked = o.localAnnounce
            switches["relaysEnabled"]?.isChecked = o.relays
            switches["natEnabled"]?.isChecked = o.nat
            switches.values.forEach { it.isEnabled = true }
            serversPref.summary = withNote(serversLabel(o.globalAnnounceServers),
                if (o.globalAnnounce) null else R.string.sync_discovery_off_note)
            val (direct, relays) = split(o.listenAddresses)
            listenPref.summary = listLabel(direct, R.string.sync_listen_default, R.string.sync_listen_none)
            relayServersPref.summary = withNote(listLabel(relays, R.string.sync_relay_default, R.string.sync_relay_none),
                if (o.relays) null else R.string.sync_relays_off_note)
            reconnectPref.summary = getString(R.string.sync_reconnect_value, o.reconnectionIntervalS)
            sendPref.summary = rate(o.maxSendKbps)
            recvPref.summary = rate(o.maxRecvKbps)
            for (p in listOf(serversPref, listenPref, relayServersPref, reconnectPref, sendPref, recvPref)) p.isEnabled = true
        }
    }

    // ---- lists ----

    private fun withNote(text: String, @StringRes note: Int?): String =
        if (note == null) text else text + "\n" + getString(note)

    /** A list for a row: "default" in words, other entries one per line, or "none". */
    private fun listLabel(entries: List<String>, @StringRes default: Int, @StringRes none: Int): String =
        if (entries.isEmpty()) getString(none)
        else entries.joinToString("\n") { if (it == Syncthing.DEFAULT) getString(default) else it }

    private fun serversLabel(servers: List<String>): String =
        if (servers.isEmpty()) getString(R.string.sync_discovery_none)
        else servers.joinToString("\n") {
            when (it) {
                Syncthing.DEFAULT -> getString(R.string.sync_discovery_default)
                "default-v4" -> getString(R.string.sync_discovery_default_v4)
                "default-v6" -> getString(R.string.sync_discovery_default_v6)
                else -> it
            }
        }

    /**
     * listenAddresses as two lists: where this device listens (tcp://,
     * quic://) and the relays it joins (relay://, dynamic+https://). Syncthing's
     * "default" stands for both and is in both.
     */
    private fun split(all: List<String>): Pair<List<String>, List<String>> {
        val direct = all.filter { it == Syncthing.DEFAULT || !isRelay(it) }
        val relays = all.filter { it == Syncthing.DEFAULT || isRelay(it) }
        return direct to relays
    }

    private fun isRelay(address: String) = address.startsWith("relay://") || address.startsWith("dynamic+")

    /**
     * The two lists as one listenAddresses: "default" once when both keep it;
     * when only one does, it stands for its own part of Syncthing's default
     * (TCP and QUIC on 22000, or the public relay pool).
     */
    private fun join(direct: List<String>, relays: List<String>): List<String> {
        val d = Syncthing.DEFAULT in direct
        val r = Syncthing.DEFAULT in relays
        val directPart = direct.flatMap { if (it == Syncthing.DEFAULT) if (r) emptyList() else Syncthing.DEFAULT_DIRECT else listOf(it) }
        val relayPart = relays.flatMap {
            if (it == Syncthing.DEFAULT) if (d) emptyList() else listOf(Syncthing.DEFAULT_RELAY_POOL) else listOf(it)
        }
        val list = ((if (d && r) listOf(Syncthing.DEFAULT) else emptyList()) + directPart + relayPart).distinct()
        // Syncthing's default spelled out is kept as "default".
        return if (list.toSet() == (Syncthing.DEFAULT_DIRECT + Syncthing.DEFAULT_RELAY_POOL).toSet()) listOf(Syncthing.DEFAULT)
        else list
    }

    /**
     * One list in a text field, one entry per line, with "Restore default"
     * (Syncthing's own value for that list). [check] tells whether an
     * entry has the right shape; the system service checks hosts, ports
     * and IDs.
     */
    private fun editList(
        @StringRes title: Int,
        @StringRes message: Int,
        current: List<String>,
        @OutlivesPage check: (String) -> Boolean,
        @RebuiltWithPage onDone: SyncOptionsFragment.(List<String>) -> Unit,
    ) = showDialog("sync-list") { d ->
        InputDialog.create(
            d.context, title,
            message = getString(message),
            initial = current.joinToString("\n"),
            singleLine = false,
            neutral = R.string.sync_restore_default,
            onNeutral = { onDone(listOf(Syncthing.DEFAULT)) },
            validate = { text ->
                val list = SyncDialogs.parseList(text)
                when {
                    list.size > SyncDialogs.MAX_LIST -> getString(R.string.sync_list_too_long, SyncDialogs.MAX_LIST)
                    else -> list.firstOrNull { !check(it) }?.let { getString(R.string.sync_list_invalid_entry, it) }
                }
            },
        ) { text -> onDone(SyncDialogs.parseList(text)) }
    }

    private fun editServers() {
        val o = options ?: return
        editList(R.string.sync_discovery_servers, R.string.sync_discovery_message, o.globalAnnounceServers,
            { it == Syncthing.DEFAULT || it == "default-v4" || it == "default-v6" || DISCOVERY.matches(it) }) { list ->
            change(R.string.error_sync_change, {
                Syncthing.patchOptions(JSONObject().put("globalAnnounceServers", JSONArray(list)))
            })
        }
    }

    private fun editListen() {
        val o = options ?: return
        val (direct, relays) = split(o.listenAddresses)
        editList(R.string.sync_listen, R.string.sync_listen_message, direct,
            { it == Syncthing.DEFAULT || LISTEN.matches(it) }) { list ->
            setListen(join(list, relays))
        }
    }

    private fun editRelays() {
        val o = options ?: return
        val (direct, relays) = split(o.listenAddresses)
        editList(R.string.sync_relay_servers, R.string.sync_relay_message, relays,
            { it == Syncthing.DEFAULT || RELAY.matches(it) }) { list ->
            setListen(join(direct, list))
        }
    }

    private fun setListen(list: List<String>) {
        change(R.string.error_sync_change, { Syncthing.patchOptions(JSONObject().put("listenAddresses", JSONArray(list))) })
    }

    private fun editReconnect() {
        val interval = options?.reconnectionIntervalS ?: return
        showDialog("sync-reconnect") { d ->
            InputDialog.create(
                d.context, R.string.sync_reconnect,
                message = getString(R.string.sync_reconnect_message),
                initial = interval.toString(),
                numeric = true,
                validate = { text ->
                    val n = text.toIntOrNull()
                    if (n == null || n < 5 || n > 86_400) getString(R.string.sync_reconnect_invalid) else null
                },
            ) { text ->
                change(R.string.error_sync_change, { Syncthing.patchOptions(JSONObject().put("reconnectionIntervalS", text.toInt())) })
            }
        }
    }

    // ---- rates ----

    private fun rate(kib: Int): String =
        if (kib <= 0) getString(R.string.sync_rate_unlimited) else getString(R.string.sync_rate_value, kib)

    private fun editRate(@StringRes title: Int, option: String, current: Int) = showDialog("sync-rate") { d ->
        InputDialog.create(
            d.context, title,
            message = getString(R.string.sync_rate_message),
            initial = current.toString(),
            numeric = true,
            validate = { text ->
                val n = text.toIntOrNull()
                if (n == null || n < 0 || n > 10_000_000) getString(R.string.sync_number_invalid) else null
            },
        ) { text -> change(R.string.error_sync_change, { Syncthing.patchOptions(JSONObject().put(option, text.toInt())) }) }
    }

    private companion object {
        // Shapes only; the system service checks each part.
        val LISTEN = Regex("^(tcp|tcp4|tcp6|quic|quic4|quic6)://[^\\s/?@]*:[0-9]+/?$")
        val RELAY = Regex("^(relay://[^\\s/?@]+:[0-9]+/?(\\?\\S*)?|dynamic\\+https?://[^\\s/?@]+(/\\S*)?)$")
        val DISCOVERY = Regex("^https?://[^\\s/?@]+(/[^\\s?]*)?(\\?\\S*)?$")
    }
}
