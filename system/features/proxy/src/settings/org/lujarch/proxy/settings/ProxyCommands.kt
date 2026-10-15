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

import org.json.JSONArray
import org.json.JSONObject

/**
 * One setting of `config get --json` ("keys"): its effective value, whether
 * the manager's config sets it, and, for the five values the phone needs to
 * proxy at all, what a change breaks and the recommended value.
 */
data class CoreKey(
    val name: String,
    /** Boolean, Int, Long, String, List or Map; null when the core's default applies. */
    val value: Any?,
    val set: Boolean,
    /** What a change breaks: `tun-only`, `tun-routes`, `tun-loop`, `dns-hijack`, `dns-resolver`. */
    val risk: String?,
    /** The value this phone recommends (same shape as [value]), for a risky key. */
    val recommended: Any?,
    /**
     * A system service of an older image forced this value instead of
     * recommending it: then the row is shown read-only with the reason.
     */
    val forced: String?,
    /** word, bool, int, country, cidr, list, servers, policy, sniff, url. */
    val kind: String,
    /** `default` (or null for JSON keys) takes it back to the default. */
    val optional: Boolean,
) {
    /** A risky key the configuration sets to something else than the recommendation. */
    val deviates: Boolean
        get() = risk != null && recommended != null && value != recommended
}

/** `config get --json` in full: every key, and what the installed config holds for the live keys. */
data class CoreConfig(
    val keys: Map<String, CoreKey>,
    val installedMode: String?,
    val installedLogLevel: String?,
) {
    operator fun get(name: String): CoreKey? = keys[name]

    /** The risky keys away from their recommended value, in the order the manager lists them. */
    val deviations: List<CoreKey>
        get() = keys.values.filter { it.deviates }

    fun bool(name: String): Boolean? = keys[name]?.value as? Boolean
    fun string(name: String): String? = keys[name]?.value as? String
    fun number(name: String): Long? = (keys[name]?.value as? Number)?.toLong()

    /** A list key's entries as text (URLs joined back from scheme and address). */
    fun list(name: String): List<String> = (keys[name]?.value as? List<*>).orEmpty().mapNotNull {
        when (it) {
            is String -> it
            is Number -> it.toString()
            is Map<*, *> -> joinUrl(it)
            else -> null
        }
    }

    /** DNS servers of a server-list key (never their full text). */
    fun servers(name: String): List<DnsServer> = (keys[name]?.value as? List<*>).orEmpty().mapNotNull { server(it) }

    /** nameserver-policy entries in order. */
    fun policy(): List<PolicyEntry> = (keys["dns.nameserver-policy"]?.value as? List<*>).orEmpty().mapNotNull { e ->
        val m = e as? Map<*, *> ?: return@mapNotNull null
        PolicyEntry(m["pattern"] as? String ?: "", (m["servers"] as? List<*>).orEmpty().mapNotNull { server(it) })
    }

    /** A URL key as text, or null for the core's default. */
    fun url(name: String): String? = (keys[name]?.value as? Map<*, *>)?.let { joinUrl(it) }

    /** Sniffer protocols: name -> ports and override. */
    fun sniff(): Map<String, SniffProtocol> {
        val m = keys["sniffer.sniff"]?.value as? Map<*, *> ?: return emptyMap()
        return m.entries.mapNotNull { (k, v) ->
            val proto = k as? String ?: return@mapNotNull null
            val conf = v as? Map<*, *> ?: return@mapNotNull null
            proto to SniffProtocol(
                ports = (conf["ports"] as? List<*>).orEmpty().mapNotNull { it?.toString()?.removeSuffix(".0") },
                overrideDestination = conf["override-destination"] as? Boolean,
            )
        }.toMap()
    }

    companion object {
        private fun joinUrl(m: Map<*, *>): String? {
            val scheme = m["scheme"] as? String ?: return null
            val address = m["address"] as? String ?: return null
            return "$scheme://$address"
        }

        private fun server(v: Any?): DnsServer? {
            val m = v as? Map<*, *> ?: return null
            return DnsServer(m["kind"] as? String ?: "", m["host"] as? String ?: "", m["via"] as? String)
        }
    }
}

data class PolicyEntry(val pattern: String, val servers: List<DnsServer>)

data class SniffProtocol(val ports: List<String>, val overrideDestination: Boolean?)

/** `probe --json`. */
data class ProbeResult(
    val total: Int,
    val classifiedBefore: Int,
    val located: Int,
    val failed: Int,
    val skipped: Int,
    val unclassified: Int,
)

/** A node of `classify --json`. */
data class NodeLocation(
    val name: String,
    val provider: String?,
    val country: String?,
    val netType: String?,
    val byHand: Boolean,
) {
    val located: Boolean
        get() = country != null
}

data class NodeLocations(val nodes: List<NodeLocation>, val unclassified: Int)

/** A rule of `rule list --json`. */
data class RuleInfo(
    val set: String,
    val index: Int,
    /** Manager syntax: `DOMAIN-SUFFIX,example.com,JP/residential`. */
    val text: String,
    val type: String,
    val pattern: String,
    val target: String,
    val params: String?,
    /** The group the target selects in the built config, or null. */
    val group: String?,
    /** The rule reaches this phone's configuration (platform, process rules). */
    val applies: Boolean,
)

data class RuleSetDetail(
    val name: String,
    val index: Int,
    val enabled: Boolean,
    val platform: String?,
    val holdsMatch: Boolean,
    val rules: List<RuleInfo>,
)

data class RuleProviderInfo(
    val name: String,
    val type: String,
    val behavior: String,
    val file: String?,
    val remote: Boolean,
)

data class RuleList(
    val sets: List<RuleSetDetail>,
    val providers: List<RuleProviderInfo>,
    /** The rule types the manager takes. */
    val types: List<String>,
)

/** Parsed JSON as plain Kotlin values (Map, List, String, Number, Boolean, null). */
internal fun plain(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.keys().asSequence().associateWith { plain(value.opt(it)) }
    is JSONArray -> (0 until value.length()).map { plain(value.opt(it)) }
    else -> value
}

/** Manager commands of the proxy settings, node location, rule and geodata pages. */
object ProxyCommands {
    suspend fun coreConfig(): CoreConfig {
        val o = ProxyManager.json(listOf("config", "get", "--json")).first
        val keys = o.optJSONObject("keys") ?: JSONObject()
        val map = LinkedHashMap<String, CoreKey>()
        for (name in keys.keys()) {
            val k = keys.optJSONObject(name) ?: continue
            map[name] = CoreKey(
                name = name,
                value = plain(k.opt("value")),
                set = k.optBoolean("set"),
                risk = k.stringOrNull("risk"),
                recommended = plain(k.opt("recommended")),
                forced = k.stringOrNull("forced"),
                kind = k.optString("class"),
                optional = k.optBoolean("optional"),
            )
        }
        val live = o.optJSONObject("installed")?.optJSONObject("live")
        return CoreConfig(map, live?.stringOrNull("mode"), live?.stringOrNull("log-level"))
    }

    /** A scalar key; `default` takes an optional key back to the default. */
    suspend fun set(key: String, value: String) {
        ProxyManager.run(listOf("config", "set", key, value))
    }

    /** A list, server list, policy, sniffer protocols or URL key as one JSON value (null: the default). */
    suspend fun setJson(key: String, value: Any?) {
        ProxyManager.run(listOf("config", "set", key, "--stdin"), input = json(value))
    }

    /**
     * Takes the five values the phone needs to proxy at all back to the
     * recommended ones (the profile fills them in again); returns the keys
     * that were changed.
     */
    suspend fun restoreRecommended(): List<String> {
        val o = ProxyManager.json(listOf("config", "restore-recommended", "--json")).first
        return o.optJSONArray("changed").strings()
    }

    // ---- group test parameters ----

    /** One test parameter of a custom group; `default` follows its kind again. */
    suspend fun setGroupTest(group: String, field: String, value: String) {
        ProxyManager.run(listOf("custom-group", "test", group, field, value))
    }

    /** The test address of a custom group (on stdin); null follows its kind again. */
    suspend fun setGroupTestUrl(group: String, url: String?) {
        ProxyManager.run(listOf("custom-group", "test", group, "url", "--stdin"), input = json(url))
    }

    private fun json(value: Any?): ByteArray {
        val text = when (value) {
            null -> "null"
            is String -> JSONObject.quote(value)
            is Collection<*> -> JSONArray(value.map { toJson(it) }).toString()
            is Map<*, *> -> (toJson(value) as JSONObject).toString()
            else -> value.toString()
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun toJson(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is Collection<*> -> JSONArray(v.map { toJson(it) })
        else -> v
    }

    // ---- node locations ----

    /** Locates the nodes without a location (exit IP through each node); at most [budgetSeconds]. */
    suspend fun probe(budgetSeconds: Int = 180): Pair<ProbeResult, String> {
        val (o, output) = ProxyManager.json(listOf("probe", "--budget", budgetSeconds.toString(), "--json"))
        return ProbeResult(
            total = o.optInt("total"),
            classifiedBefore = o.optInt("classified_before"),
            located = o.optInt("located"),
            failed = o.optInt("failed"),
            skipped = o.optInt("skipped"),
            unclassified = o.optInt("unclassified"),
        ) to output
    }

    suspend fun locations(): NodeLocations {
        val o = ProxyManager.json(listOf("classify", "--json")).first
        val nodes = o.optJSONArray("nodes").objects().map {
            NodeLocation(
                name = it.optString("name"),
                provider = it.stringOrNull("provider"),
                country = it.stringOrNull("country"),
                netType = it.stringOrNull("net_type"),
                byHand = it.optBoolean("by_hand"),
            )
        }
        return NodeLocations(nodes, o.optInt("unclassified"))
    }

    suspend fun locateByHand(names: List<String>, country: String, netType: String) {
        ProxyManager.run(listOf("classify", "set", country, netType), input = names(names))
    }

    suspend fun forgetLocations(names: List<String>) {
        ProxyManager.run(listOf("classify", "forget"), input = names(names))
    }

    private fun names(names: List<String>) = JSONArray(names).toString().toByteArray(Charsets.UTF_8)

    // ---- rules ----

    suspend fun rules(set: String? = null): RuleList {
        val args = if (set == null) listOf("rule", "list", "--json") else listOf("rule", "list", "--set", set, "--json")
        val o = ProxyManager.json(args).first
        val sets = o.optJSONArray("sets").objects().map { s ->
            val name = s.optString("name")
            RuleSetDetail(
                name = name,
                index = s.optInt("index"),
                enabled = s.optBoolean("enabled", true),
                platform = s.stringOrNull("platform"),
                holdsMatch = s.optBoolean("match"),
                rules = s.optJSONArray("rules").objects().map { r ->
                    RuleInfo(
                        set = name,
                        index = r.optInt("index"),
                        text = r.optString("rule"),
                        type = r.optString("type"),
                        pattern = r.optString("pattern"),
                        target = r.optString("target"),
                        params = r.stringOrNull("params"),
                        group = r.stringOrNull("group"),
                        applies = r.optBoolean("applies", true),
                    )
                },
            )
        }
        val providers = o.optJSONArray("providers").objects().map {
            RuleProviderInfo(
                name = it.optString("name"),
                type = it.optString("type"),
                behavior = it.optString("behavior"),
                file = it.stringOrNull("file"),
                remote = it.optBoolean("remote"),
            )
        }
        return RuleList(sets, providers, o.optJSONArray("types").strings())
    }

    suspend fun addRule(rule: String, set: String, index: Int?) {
        val args = mutableListOf("rule", "add", rule, "--set", set)
        if (index != null) args += listOf("--index", index.toString())
        ProxyManager.run(args)
    }

    suspend fun editRule(at: RuleInfo, rule: String) {
        ProxyManager.run(listOf("rule", "edit", "${at.set}:${at.index}", rule, "--expect", at.text))
    }

    suspend fun removeRule(at: RuleInfo) {
        ProxyManager.run(listOf("rule", "remove", "${at.set}:${at.index}", "--expect", at.text))
    }

    suspend fun moveRule(at: RuleInfo, toSet: String, toIndex: Int) {
        ProxyManager.run(listOf("rule", "move", "${at.set}:${at.index}", "$toSet:$toIndex", "--expect", at.text))
    }

    suspend fun moveSet(set: String, index: Int) {
        ProxyManager.run(listOf("rule", "move-set", set, index.toString()))
    }

    /** android, desktop or all. */
    suspend fun setPlatform(set: String, platform: String) {
        ProxyManager.run(listOf("rule", "platform", set, platform))
    }

    suspend fun removeSet(set: String) {
        ProxyManager.run(listOf("rule", "remove-set", set))
    }

    suspend fun regroup() {
        ProxyManager.run(listOf("rule", "regroup"))
    }

    /** A rule list (one rule per line) appended to [set]. */
    suspend fun importRules(set: String, bytes: ByteArray): String =
        ProxyManager.run(listOf("rule", "import", "-", "--set", set), input = bytes).output

    /** Rules whose pattern contains [pattern], or whose target is [from], get [to] (in [set] only when given). */
    suspend fun retarget(to: String, pattern: String?, from: String?, set: String?): String {
        val args = mutableListOf("rule", "retarget", "--to", to)
        if (pattern != null) args += listOf("--pattern", pattern)
        if (from != null) args += listOf("--from", from)
        if (set != null) args += listOf("--set", set)
        return ProxyManager.run(args).output
    }

    // ---- files ----

    enum class Geo(val arg: String) { MMDB("mmdb"), GEOIP("geoip"), GEOSITE("geosite"), ASN("asn") }

    suspend fun importGeodata(kind: Geo, bytes: ByteArray): Pair<ImportReport, String> =
        ProxyManager.importRaw(listOf("import", "geodata", kind.arg, "--json"), bytes)

    suspend fun importLocations(bytes: ByteArray): Pair<ImportReport, String> =
        ProxyManager.importRaw(listOf("import", "cache", "--json"), bytes)

    suspend fun removeRuleset(name: String): Pair<ImportReport, String> =
        ProxyManager.importRaw(listOf("import", "ruleset", name, "--remove", "--json"), null)
}
