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

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import org.json.JSONArray
import org.json.JSONObject

/** Live controller settings (GET /configs). */
data class MihomoConfigs(
    val mode: String,
    val tunEnabled: Boolean,
    val tunStack: String?,
    val logLevel: String? = null,
)

/** A proxy or proxy group (GET /proxies). */
data class ProxyItem(
    val name: String,
    val type: String,
    val now: String?,
    val members: List<String>,
    /** Manual selection pinned on an automatic group (URLTest, Fallback, Adaptive). */
    val fixed: String?,
    /** Last measured delay in ms: null when never tested, 0 when the test failed. */
    val delay: Int?,
    val testUrl: String?,
    /** What an adaptive group knows and decides; null for other types. */
    val adaptive: AdaptiveInfo? = null,
) {
    val isGroup: Boolean
        get() = members.isNotEmpty() || Mihomo.GROUP_TYPES.any { it.equals(type, ignoreCase = true) }

    /** Groups whose member can be chosen with PUT /proxies/{name}. */
    val isSelectable: Boolean
        get() = Mihomo.SELECTABLE_TYPES.any { it.equals(type, ignoreCase = true) }

    val isAdaptive: Boolean
        get() = type.equals(Mihomo.TYPE_ADAPTIVE, ignoreCase = true)
}

/** One member of an adaptive group on one network, as the group estimates it. */
data class AdaptiveMember(
    val name: String,
    /** Used only while every other member is failing. */
    val lastResort: Boolean,
    /** `ok`, `failing` (success below 50%) or `unknown` (nothing measured here). */
    val state: String,
    /** Probability of a working connection before the deadline, 0 to 1. */
    val success: Double,
    /** Milliseconds to the first byte of a working connection (mean). */
    val latency: Long,
    /** Milliseconds after which an attempt counts as lost. */
    val deadline: Long,
    /** Expected milliseconds to a working connection: the group prefers the lowest. */
    val cost: Long,
    val known: Boolean,
    val successes: Long,
    val failures: Long,
    /** Last measurement, ms since the epoch; 0 when none. */
    val lastSample: Long,
)

/** One network an adaptive group has statistics for. */
data class AdaptiveNetwork(
    val key: String,
    /** `wifi`, `cellular`, `ethernet` or `other`. */
    val networkClass: String,
    val current: Boolean,
    val lastUsed: Long,
    /** The member a connection tries first there. */
    val now: String?,
    /** `best`, `kept`, `learning`, `last-resort` or `pinned`. */
    val reason: String,
    /** Members in the order a connection tries them there. */
    val order: List<String>,
    val members: List<AdaptiveMember>,
    val connections: Long,
    val raced: Long,
    val notFirst: Long,
    val failed: Long,
    val probes: Long,
)

data class AdaptiveInfo(
    /** Key of the network the phone is on. */
    val network: String,
    val params: Map<String, Long>,
    val networks: List<AdaptiveNetwork>,
) {
    val current: AdaptiveNetwork?
        get() = networks.find { it.current }
}

/** Snapshot of GET /proxies with groups in configuration order. */
data class ProxySnapshot(val items: Map<String, ProxyItem>, val groups: List<ProxyItem>)

data class Connection(
    val id: String,
    val host: String,
    val destinationIp: String,
    val destinationPort: String,
    val sourceIp: String,
    val sourcePort: String,
    val network: String,
    val type: String,
    val process: String,
    val processPath: String,
    val uid: Int,
    val rule: String,
    val rulePayload: String,
    /** Outbound chain from the first hop (outermost group) to the node. */
    val chain: List<String>,
    val upload: Long,
    val download: Long,
    val start: String,
) {
    val target: String
        get() {
            val name = host.ifEmpty { destinationIp }
            return if (destinationPort.isEmpty()) name else "$name:$destinationPort"
        }
}

data class ConnectionsSnapshot(
    val uploadTotal: Long,
    val downloadTotal: Long,
    val connections: List<Connection>,
)

/** One second of traffic from /traffic, bytes per second. */
data class Traffic(val up: Long, val down: Long)

/** A rule provider of the running core; [remote] ones have a download address. */
data class RuleProviderState(val name: String, val remote: Boolean, val rules: Int)

/** One line from /logs. */
data class LogLine(val level: String, val payload: String, val receivedAt: Long)

/** mihomo controller requests, all through the proxy daemon (the app never holds the secret). */
object Mihomo {
    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
    const val DELAY_TIMEOUT_MS = 5000

    const val MODE_RULE = "rule"
    const val MODE_GLOBAL = "global"
    const val MODE_DIRECT = "direct"

    const val TYPE_ADAPTIVE = "Adaptive"
    val GROUP_TYPES = listOf("Selector", "URLTest", "Fallback", "LoadBalance", "Relay", TYPE_ADAPTIVE)
    val SELECTABLE_TYPES = listOf("Selector", "URLTest", "Fallback", TYPE_ADAPTIVE)
    val LOG_LEVELS = listOf("debug", "info", "warning", "error")

    /** Levels of the core's own log (`log-level`); silent writes nothing. */
    val CORE_LOG_LEVELS = listOf("debug", "info", "warning", "error", "silent")

    /** Percent-encodes one path segment (group and node names hold spaces, slashes, emoji). */
    fun segment(name: String): String = Uri.encode(name)

    /** The core's version (GET /version). */
    suspend fun version(): String? =
        (ProxyClient.api("GET", "/version") as? JSONObject)?.stringOrNull("version")

    suspend fun configs(): MihomoConfigs {
        val body = asObject(ProxyClient.api("GET", "/configs"), "GET /configs")
        val tun = body.optJSONObject("tun")
        return MihomoConfigs(
            mode = body.stringOrNull("mode")?.lowercase() ?: "",
            tunEnabled = tun?.looseBoolean("enable") ?: false,
            tunStack = tun?.stringOrNull("stack"),
            logLevel = body.stringOrNull("log-level"),
        )
    }

    /** Live mode of the running core; the persisted one is the manager's (`config set mode`). */
    suspend fun setMode(mode: String) {
        ProxyClient.api("PATCH", "/configs", JSONObject().put("mode", mode))
    }

    /** Live log level of the running core. */
    suspend fun setLogLevel(level: String) {
        ProxyClient.api("PATCH", "/configs", JSONObject().put("log-level", level))
    }

    /** Downloads GeoIP and GeoSite data from the config's sources and reloads them. */
    suspend fun updateGeoData() {
        ProxyClient.api("POST", "/configs/geo", timeoutMs = 120_000L)
    }

    /** The running core's rule providers (GET /providers/rules). */
    suspend fun ruleProviders(): List<RuleProviderState> {
        val body = asObject(ProxyClient.api("GET", "/providers/rules"), "GET /providers/rules")
        val providers = body.optJSONObject("providers") ?: return emptyList()
        return providers.keys().asSequence().mapNotNull { name ->
            val p = providers.optJSONObject(name) ?: return@mapNotNull null
            RuleProviderState(
                name = p.stringOrNull("name") ?: name,
                remote = p.stringOrNull("vehicleType").equals("HTTP", ignoreCase = true),
                rules = p.optInt("ruleCount"),
            )
        }.toList()
    }

    /** Downloads one rule provider's list again (PUT /providers/rules/{name}). */
    suspend fun updateRuleProvider(name: String) {
        ProxyClient.api("PUT", "/providers/rules/${segment(name)}", timeoutMs = 60_000L)
    }

    /** One proxy or group (GET /proxies/{name}). */
    suspend fun proxy(name: String): ProxyItem {
        val body = asObject(ProxyClient.api("GET", "/proxies/${segment(name)}"), "GET /proxies/{name}")
        return parseProxy(name, body)
    }

    /** Delay of one node in ms; 0 when the test failed or timed out. */
    suspend fun proxyDelay(name: String, testUrl: String? = null): Int {
        val url = Uri.encode(testUrl?.ifEmpty { null } ?: DEFAULT_TEST_URL)
        val path = "/proxies/${segment(name)}/delay?url=$url&timeout=$DELAY_TIMEOUT_MS"
        return try {
            val body = ProxyClient.api("GET", path, timeoutMs = DELAY_TIMEOUT_MS + ProxyClient.TIMEOUT_API_MS)
            (body as? JSONObject)?.optInt("delay", 0) ?: 0
        } catch (e: ProxyException) {
            // The controller answers 408 (timeout) or 503 (error) for a failed test.
            if (e.kind == ProxyException.Kind.HTTP && e.httpStatus in listOf(408, 503, 504)) 0 else throw e
        }
    }

    suspend fun proxies(): ProxySnapshot {
        val body = asObject(ProxyClient.api("GET", "/proxies"), "GET /proxies")
        val proxies = body.optJSONObject("proxies") ?: JSONObject()
        val items = LinkedHashMap<String, ProxyItem>()
        val names = proxies.keys()
        while (names.hasNext()) {
            val name = names.next()
            val obj = proxies.optJSONObject(name) ?: continue
            items[name] = parseProxy(name, obj)
        }
        // GLOBAL lists every group and proxy in configuration order.
        val order = ArrayList<String>()
        items[GLOBAL]?.members?.let { order.addAll(it) }
        items.keys.sorted().forEach { if (it !in order) order.add(it) }
        val groups = order.mapNotNull { items[it] }.filter { it.isGroup && it.name != GLOBAL }.toMutableList()
        items[GLOBAL]?.let { groups.add(it) }
        return ProxySnapshot(items, groups)
    }

    suspend fun select(group: String, member: String) {
        ProxyClient.api("PUT", "/proxies/${segment(group)}", JSONObject().put("name", member))
    }

    /** Drops a pinned selection so an automatic group chooses again. */
    suspend fun unfix(group: String) {
        ProxyClient.api("DELETE", "/proxies/${segment(group)}")
    }

    /** Tests every member of [group]; returns name to delay (0 when the test failed). */
    suspend fun groupDelay(group: String, testUrl: String?): Map<String, Int> {
        val url = Uri.encode(testUrl?.ifEmpty { null } ?: DEFAULT_TEST_URL)
        val path = "/group/${segment(group)}/delay?url=$url&timeout=$DELAY_TIMEOUT_MS"
        val body = ProxyClient.api("GET", path, timeoutMs = DELAY_TIMEOUT_MS + ProxyClient.TIMEOUT_API_MS)
        val result = HashMap<String, Int>()
        if (body is JSONObject) {
            val keys = body.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = body.optInt(key, 0)
            }
        }
        return result
    }

    suspend fun connections(): ConnectionsSnapshot {
        val body = asObject(ProxyClient.api("GET", "/connections"), "GET /connections")
        val list = body.optJSONArray("connections") ?: JSONArray()
        val connections = ArrayList<Connection>(list.length())
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            parseConnection(obj)?.let { connections.add(it) }
        }
        return ConnectionsSnapshot(
            uploadTotal = body.optLong("uploadTotal"),
            downloadTotal = body.optLong("downloadTotal"),
            connections = connections,
        )
    }

    suspend fun closeConnection(id: String) {
        ProxyClient.api("DELETE", "/connections/${segment(id)}")
    }

    suspend fun closeAllConnections() {
        ProxyClient.api("DELETE", "/connections")
    }

    /**
     * The core sends a /traffic sample every second, the first one within
     * about a second: a stream silent for longer than these is closed and a
     * new one opened ([collectWithin]).
     */
    const val TRAFFIC_FIRST_SAMPLE_MS = 5000L
    const val TRAFFIC_SAMPLE_GAP_MS = 5000L

    fun traffic(): Flow<Traffic> = ProxyClient.stream("/traffic").mapNotNull { data ->
        (data as? JSONObject)?.let { Traffic(it.optLong("up"), it.optLong("down")) }
    }

    fun logs(level: String): Flow<LogLine> = ProxyClient.stream("/logs?level=$level").mapNotNull { data ->
        when (data) {
            is JSONObject -> LogLine(
                level = data.stringOrNull("type") ?: level,
                payload = data.stringOrNull("payload") ?: "",
                receivedAt = System.currentTimeMillis(),
            )
            is String -> LogLine(level, data, System.currentTimeMillis())
            else -> null
        }
    }

    private const val GLOBAL = "GLOBAL"

    private fun parseProxy(name: String, obj: JSONObject): ProxyItem {
        val all = obj.optJSONArray("all")
        val members = ArrayList<String>()
        if (all != null) for (i in 0 until all.length()) all.optString(i).takeIf { it.isNotEmpty() }?.let(members::add)
        val history = obj.optJSONArray("history")
        val delay = if (history != null && history.length() > 0) {
            history.optJSONObject(history.length() - 1)?.optInt("delay", 0)
        } else {
            null
        }
        return ProxyItem(
            name = name,
            type = obj.stringOrNull("type") ?: "",
            now = obj.stringOrNull("now"),
            members = members,
            fixed = obj.stringOrNull("fixed"),
            delay = delay,
            testUrl = obj.stringOrNull("testUrl"),
            adaptive = obj.optJSONObject("adaptive")?.let(::parseAdaptive),
        )
    }

    private fun parseAdaptive(a: JSONObject): AdaptiveInfo {
        val params = LinkedHashMap<String, Long>()
        a.optJSONObject("params")?.let { p -> for (k in p.keys()) params[k] = p.optLong(k) }
        val networks = ArrayList<AdaptiveNetwork>()
        val list = a.optJSONArray("networks") ?: JSONArray()
        for (i in 0 until list.length()) {
            val n = list.optJSONObject(i) ?: continue
            val members = ArrayList<AdaptiveMember>()
            val ms = n.optJSONArray("members") ?: JSONArray()
            for (j in 0 until ms.length()) {
                val m = ms.optJSONObject(j) ?: continue
                members += AdaptiveMember(
                    name = m.optString("name"),
                    lastResort = m.optBoolean("lastResort"),
                    state = m.optString("state"),
                    success = m.optDouble("success", 0.0),
                    latency = m.optLong("latency"),
                    deadline = m.optLong("deadline"),
                    cost = m.optLong("cost"),
                    known = m.optBoolean("known"),
                    successes = m.optLong("successes"),
                    failures = m.optLong("failures"),
                    lastSample = m.optLong("lastSample"),
                )
            }
            val order = ArrayList<String>()
            n.optJSONArray("order")?.let { o -> for (j in 0 until o.length()) order += o.optString(j) }
            networks += AdaptiveNetwork(
                key = n.optString("key"),
                networkClass = n.optString("class"),
                current = n.optBoolean("current"),
                lastUsed = n.optLong("lastUsed"),
                now = n.stringOrNull("now"),
                reason = n.optString("reason"),
                order = order,
                members = members,
                connections = n.optLong("connections"),
                raced = n.optLong("raced"),
                notFirst = n.optLong("notFirst"),
                failed = n.optLong("failed"),
                probes = n.optLong("probes"),
            )
        }
        return AdaptiveInfo(network = a.optString("network"), params = params, networks = networks)
    }

    private fun parseConnection(obj: JSONObject): Connection? {
        val id = obj.stringOrNull("id") ?: return null
        val meta = obj.optJSONObject("metadata") ?: JSONObject()
        val chains = obj.optJSONArray("chains")
        val chain = ArrayList<String>()
        // The controller lists the chain from the node outwards.
        if (chains != null) for (i in chains.length() - 1 downTo 0) chain.add(chains.optString(i))
        return Connection(
            id = id,
            host = meta.stringOrNull("host") ?: meta.stringOrNull("sniffHost") ?: "",
            destinationIp = meta.stringOrNull("destinationIP") ?: "",
            destinationPort = meta.stringOrNull("destinationPort") ?: "",
            sourceIp = meta.stringOrNull("sourceIP") ?: "",
            sourcePort = meta.stringOrNull("sourcePort") ?: "",
            network = meta.stringOrNull("network") ?: "",
            type = meta.stringOrNull("type") ?: "",
            process = meta.stringOrNull("process") ?: "",
            processPath = meta.stringOrNull("processPath") ?: "",
            uid = meta.optInt("uid", -1),
            rule = obj.stringOrNull("rule") ?: "",
            rulePayload = obj.stringOrNull("rulePayload") ?: "",
            chain = chain,
            upload = obj.optLong("upload"),
            download = obj.optLong("download"),
            start = obj.stringOrNull("start") ?: "",
        )
    }

    private fun asObject(body: Any?, what: String): JSONObject =
        body as? JSONObject ?: throw ProxyException(
            ProxyException.Kind.PROTOCOL, what, "expected a JSON object, got ${body?.javaClass?.simpleName ?: "nothing"}"
        )
}
