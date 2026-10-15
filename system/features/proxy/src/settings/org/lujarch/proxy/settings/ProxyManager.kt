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

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A subscription or manual provider (`status --json`). */
data class ProviderInfo(
    val name: String,
    val manual: Boolean,
    /** Host of the subscription URL (never the URL: its path carries the access token). */
    val host: String?,
    val nodes: Int?,
    /** Node list downloaded at, ms since the epoch. */
    val fetchedAt: Long?,
    val usage: Usage?,
    /** Usage was asked of the server (online status). */
    val usageChecked: Boolean,
) {
    data class Usage(val upload: Long, val download: Long, val total: Long, val expireAt: Long?) {
        val used: Long
            get() = upload + download
    }
}

/** `status --json`. */
data class ManagerStatus(
    val providers: List<ProviderInfo>,
    val poolNodes: Int?,
    /** Nodes of the pool with a location (country and line type). */
    val poolClassified: Int?,
    val poolError: String?,
    val rulesTotal: Int?,
    val defaultTarget: String?,
    val liveConfigAt: Long?,
    val lastGoodAt: Long?,
    val lastGoodSameAsLive: Boolean?,
)

/** One DNS server of `config get --json`: kind (udp, https, tls, quic, ...), host, group it goes through. */
data class DnsServer(val kind: String, val host: String, val via: String?)

/** `config get --json`: the tunables, as the generated config gets them. */
data class CoreSettings(
    val mode: String,
    val logLevel: String,
    val tunStack: String,
    val sniffer: Boolean,
    val snifferProtocols: List<String>,
    val unifiedDelay: Boolean,
    val tcpConcurrent: Boolean,
    val geoAutoUpdate: Boolean,
    val geoUpdateHours: Int,
    val dnsMode: String?,
    val nameservers: List<DnsServer>,
    val fallback: List<DnsServer>,
    val defaultNameservers: List<DnsServer>,
    val proxyServerNameservers: List<DnsServer>,
    val fakeIpFilter: Int,
    val nameserverPolicy: Int,
    /** Values the phone recommends that the configuration sets to something else. */
    val deviations: Int,
)

data class RuleSetInfo(
    val name: String,
    val index: Int,
    val enabled: Boolean,
    val platform: String?,
    val rules: Int,
    val holdsMatch: Boolean,
)

/** `rule sets --json`: the sets in order and the final MATCH target. */
data class RuleSets(val sets: List<RuleSetInfo>, val defaultTarget: String?, val defaultGroup: String?)

/** `rule match <domain> --json`; [set] is null when no domain rule matches. */
data class RuleMatch(
    val domain: String,
    val set: String?,
    val index: Int,
    val rule: String?,
    val target: String?,
    val group: String?,
)

data class CustomGroup(
    val key: String,
    val nameContains: String?,
    val country: String?,
    val netType: String?,
    val fallbackTo: String?,
    val proxies: Int?,
    /** Test parameters this group sets itself, per field (`interval`, `url`, ...). */
    val testSet: Map<String, Any?> = emptyMap(),
    /** The test parameters its variants are built with, per kind (`auto`, `fallback`, `load-balance`). */
    val test: Map<String, Map<String, Any?>> = emptyMap(),
    /** Listed as an adaptive group, and built so on this phone. */
    val adaptive: Boolean = false,
    val adaptiveBuilt: Boolean = false,
    /** Members that are nodes: [proxies] without the group of [fallbackTo]. */
    val nodes: Int? = null,
) {
    /** The name of its adaptive group in the running core. */
    val adaptiveGroup: String
        get() = "$key - Adaptive"
}

/** A file of `files --json`; geodata files name their [kind] (mmdb, geoip, geosite, asn). */
data class FileInfo(val name: String, val size: Long?, val modifiedAt: Long?, val kind: String? = null) {
    val present: Boolean
        get() = size != null
}

data class FilesInfo(
    val config: FileInfo,
    val routing: FileInfo,
    val providers: FileInfo,
    val cache: FileInfo,
    val referenceCount: Int,
    val referenceSize: Long,
    val rulesets: List<FileInfo>,
    val geodata: List<FileInfo>,
    val live: FileInfo,
    val lastGood: FileInfo,
    val lastGoodSameAsLive: Boolean?,
    val backups: Int,
)

data class BackupInfo(
    val id: String,
    val createdAt: Long,
    /** `import config`, `import routing`, ..., `restore <id>`. */
    val reason: String,
    val files: List<String>,
)

/** `import ... --json`, `restore --json`. */
data class ImportReport(
    val kind: String,
    val backup: String?,
    val changed: List<String>,
    val removed: List<String>,
    val keptSets: List<String>,
    val movedPaths: List<String>,
    val fetched: List<String>,
    /** Providers without a node list, left out of the checked configuration. */
    val missingNodes: List<String>,
    val proxies: Int,
    val groups: Int,
    val rules: Int,
)

/** `build --json`, `validate --json`, `rollback --json`. */
data class ConfigResult(
    val ok: Boolean,
    val error: String?,
    val proxies: Int?,
    val groups: Int?,
    val rules: Int?,
    val skippedRules: Int?,
    val unresolved: List<String>,
    /** Providers without a node list, left out. */
    val missingNodes: List<String>,
    /** build: healthy, core-down or written; validate: ok or skipped. */
    val outcome: String?,
    val log: List<String>,
    /** Nodes without a location: in no country, line-type or custom group (only GLOBAL). */
    val unclassified: Int = 0,
)

/**
 * A `--json` command answered `{"ok": false, "error": ...}`; [error] is the
 * manager's message (English, shown only behind "Detailed output").
 */
class ManagerJsonException(args: List<String>, val error: String, output: String) : ManagerException(args, 1, output)

/**
 * The mihomo-manager commands of the proxy pages, through the proxy daemon. Only the
 * `--json` outputs are parsed; other commands are judged by their exit code.
 * the proxy daemon runs one manager command at a time: the app's commands wait in
 * line and identical reads share one run ([ManagerCalls]), for up to 30 s.
 */
object ProxyManager {
    /** How long a restart may take: the configuration's check and the core's start. */
    private const val RESTART_TIMEOUT_MS = 60_000L

    /** One run; a non-zero exit throws [ManagerException] when [check]. */
    suspend fun run(args: List<String>, input: ByteArray? = null, check: Boolean = true): ManagerResult {
        val result = ManagerCalls.run(args, input)
        if (check && !result.success) throw ManagerException(args, result.code, result.output)
        return result
    }

    /**
     * A `--json` command: its JSON line, also when it exited with 1 and said
     * `"ok": false` (thrown as [ManagerJsonException] unless [allowFailure]).
     */
    suspend fun json(args: List<String>, input: ByteArray? = null, allowFailure: Boolean = false): Pair<JSONObject, String> {
        val result = run(args, input, check = false)
        val obj = parseLastObject(result.output)
        if (obj == null) {
            if (!result.success) throw ManagerException(args, result.code, result.output)
            throw ProxyException(
                ProxyException.Kind.PROTOCOL, "mihomo.manager ${args.joinToString(" ")}", "output is not JSON"
            )
        }
        if (!allowFailure && (!result.success || obj.optBoolean("ok", true) == false)) {
            throw ManagerJsonException(args, obj.stringOrNull("error") ?: "exit code ${result.code}", result.output)
        }
        return obj to result.output
    }

    /** The last output line holding a JSON object (the manager prints one). */
    private fun parseLastObject(output: String): JSONObject? {
        for (line in output.lineSequence().toList().asReversed()) {
            val t = line.trim()
            if (!t.startsWith("{")) continue
            return try {
                JSONObject(t)
            } catch (e: JSONException) {
                null
            }
        }
        return null
    }

    // ---- status ----

    suspend fun status(offline: Boolean): ManagerStatus {
        val args = if (offline) listOf("status", "--offline", "--json") else listOf("status", "--json")
        val o = json(args).first
        val providers = o.optJSONArray("providers").objects().map { p ->
            val usage = p.optJSONObject("usage")?.let { u ->
                ProviderInfo.Usage(
                    upload = u.optLong("upload"),
                    download = u.optLong("download"),
                    total = u.optLong("total"),
                    expireAt = u.optLong("expire").takeIf { it > 0 }?.times(1000),
                )
            }
            ProviderInfo(
                name = p.optString("name"),
                manual = p.optString("kind") == "manual",
                host = p.stringOrNull("host"),
                nodes = p.intOrNull("nodes"),
                fetchedAt = p.longOrNull("fetched")?.times(1000),
                usage = usage,
                usageChecked = p.optBoolean("usage_checked"),
            )
        }
        val pool = o.optJSONObject("pool")
        val rules = o.optJSONObject("rules")
        val live = o.optJSONObject("live_config")
        val good = o.optJSONObject("last_good")
        return ManagerStatus(
            providers = providers,
            poolNodes = pool?.intOrNull("nodes"),
            poolClassified = pool?.intOrNull("classified"),
            poolError = pool?.stringOrNull("error"),
            rulesTotal = rules?.intOrNull("total"),
            defaultTarget = rules?.stringOrNull("default"),
            liveConfigAt = live?.longOrNull("mtime")?.times(1000),
            lastGoodAt = good?.longOrNull("mtime")?.times(1000),
            lastGoodSameAsLive = good?.takeIf { it.has("same_as_live") && !it.isNull("same_as_live") }
                ?.optBoolean("same_as_live"),
        )
    }

    // ---- tunables ----

    suspend fun settings(): CoreSettings {
        val o = json(listOf("config", "get", "--json")).first
        val tun = o.optJSONObject("tun") ?: JSONObject()
        val sniffer = o.optJSONObject("sniffer") ?: JSONObject()
        val dns = o.optJSONObject("dns") ?: JSONObject()
        fun servers(key: String) = dns.optJSONArray(key).objects().map {
            DnsServer(it.optString("kind"), it.optString("host"), it.stringOrNull("via"))
        }
        return CoreSettings(
            mode = o.optString("mode"),
            logLevel = o.optString("log-level"),
            tunStack = tun.optString("stack", "mixed"),
            sniffer = sniffer.optBoolean("enable"),
            snifferProtocols = sniffer.optJSONArray("protocols").strings(),
            unifiedDelay = o.optBoolean("unified-delay"),
            tcpConcurrent = o.optBoolean("tcp-concurrent"),
            geoAutoUpdate = o.optBoolean("geo-auto-update"),
            geoUpdateHours = o.optInt("geo-update-interval", 24),
            dnsMode = dns.stringOrNull("enhanced-mode"),
            nameservers = servers("nameserver"),
            fallback = servers("fallback"),
            defaultNameservers = servers("default-nameserver"),
            proxyServerNameservers = servers("proxy-server-nameserver"),
            fakeIpFilter = dns.optInt("fake-ip-filter"),
            nameserverPolicy = dns.optInt("nameserver-policy"),
            deviations = o.optJSONArray("deviations")?.length() ?: 0,
        )
    }

    suspend fun setSetting(key: String, value: String) {
        run(listOf("config", "set", key, value))
    }

    // ---- rules ----

    suspend fun ruleSets(): RuleSets {
        val o = json(listOf("rule", "sets", "--json")).first
        val sets = o.optJSONArray("sets").objects().map {
            RuleSetInfo(
                name = it.optString("name"),
                index = it.optInt("index"),
                enabled = it.optBoolean("enabled", true),
                platform = it.stringOrNull("platform"),
                rules = it.optInt("rules"),
                holdsMatch = it.optBoolean("match"),
            )
        }
        val default = o.optJSONObject("default")
        return RuleSets(sets, default?.stringOrNull("target"), default?.stringOrNull("group"))
    }

    suspend fun setRuleSetEnabled(name: String, enabled: Boolean) {
        run(listOf("rule", if (enabled) "enable" else "disable", name))
    }

    suspend fun setDefaultTarget(target: String) {
        run(listOf("rule", "default", target))
    }

    suspend fun ruleMatch(domain: String): RuleMatch {
        val o = json(listOf("rule", "match", domain, "--json")).first
        return RuleMatch(
            domain = o.optString("domain", domain),
            set = o.stringOrNull("set"),
            index = o.optInt("index", -1),
            rule = o.stringOrNull("rule"),
            target = o.stringOrNull("target"),
            group = o.stringOrNull("group"),
        )
    }

    // ---- custom groups ----

    suspend fun customGroups(): List<CustomGroup> =
        json(listOf("custom-group", "list", "--json")).first.optJSONArray("groups").objects().map {
            @Suppress("UNCHECKED_CAST")
            val test = (plain(it.optJSONObject("test")) as? Map<String, Map<String, Any?>>).orEmpty()
                .mapValues { (_, params) -> withUrlText(params) }
            @Suppress("UNCHECKED_CAST")
            val own = withUrlText((plain(it.optJSONObject("test_set")) as? Map<String, Any?>).orEmpty())
            CustomGroup(
                key = it.optString("key"),
                nameContains = it.stringOrNull("name_contains"),
                country = it.stringOrNull("country"),
                netType = it.stringOrNull("net_type"),
                fallbackTo = it.stringOrNull("fallback_to"),
                proxies = it.intOrNull("proxies"),
                testSet = own,
                test = test,
                adaptive = it.optBoolean("adaptive"),
                adaptiveBuilt = it.optBoolean("adaptive_built"),
                // A manager without the field counts the last resort as a node.
                nodes = it.intOrNull("nodes") ?: it.intOrNull("proxies"),
            )
        }

    /**
     * Test parameters with the address as text: the manager gives it as scheme and address,
     * which the proxy daemon's URL redaction leaves (a test address is not a secret).
     */
    private fun withUrlText(params: Map<String, Any?>): Map<String, Any?> {
        val url = params["url"] as? Map<*, *> ?: return params
        val scheme = url["scheme"] as? String
        val address = url["address"] as? String
        return params + ("url" to if (scheme != null && address != null) "$scheme://$address" else null)
    }

    suspend fun addCustomGroup(group: CustomGroup) {
        val args = mutableListOf("custom-group", "add", group.key)
        group.nameContains?.let { args += listOf("--name-contains", it) }
        group.country?.let { args += listOf("--country", it) }
        group.netType?.let { args += listOf("--net-type", it) }
        group.fallbackTo?.let { args += listOf("--fallback-to", it) }
        run(args)
    }

    suspend fun removeCustomGroup(key: String) {
        run(listOf("custom-group", "remove", key))
    }

    /** One adaptive group in place of the custom group's three, or back. */
    suspend fun setAdaptive(key: String, on: Boolean) {
        run(listOf("custom-group", "adaptive", key, if (on) "on" else "off"))
    }

    // ---- providers ----

    suspend fun providerNodes(name: String): List<String> =
        json(listOf("provider", "nodes", name, "--json")).first.optJSONArray("nodes").strings()

    /** Adds a subscription; the URL goes on stdin. Downloads its nodes unless [fetch] is false. */
    suspend fun addSubscription(name: String, url: String, fetch: Boolean) {
        val args = mutableListOf("provider", "add", name, "--url-stdin")
        if (!fetch) args += "--no-fetch"
        run(args, input = url.toByteArray(Charsets.UTF_8))
    }

    /** Replaces a subscription's URL (stdin) and downloads its nodes. */
    suspend fun replaceSubscriptionUrl(name: String, url: String) {
        run(listOf("provider", "update", name, "--url-stdin"), input = url.toByteArray(Charsets.UTF_8))
    }

    /** Downloads one subscription's nodes again. */
    suspend fun refreshSubscription(name: String) {
        run(listOf("provider", "update", name))
    }

    suspend fun removeProvider(name: String, purge: Boolean) {
        run(if (purge) listOf("provider", "remove", name, "--purge") else listOf("provider", "remove", name))
    }

    /** Replaces (or extends) a manual provider's nodes from [text] (proxy URIs or YAML). */
    suspend fun setManualNodes(name: String, text: ByteArray, append: Boolean, country: String?, netType: String?) {
        val args = mutableListOf("provider", "add-node", name, "-")
        if (append) args += "--append"
        if (country != null && netType != null) args += listOf("--country", country, "--net-type", netType)
        run(args, input = text)
    }

    // ---- files ----

    suspend fun files(): FilesInfo {
        val o = json(listOf("files", "--json")).first
        val files = o.optJSONObject("files") ?: JSONObject()
        fun info(name: String, obj: JSONObject?) = FileInfo(
            name = name,
            size = obj?.takeIf { it.optBoolean("present") }?.longOrNull("size"),
            modifiedAt = obj?.longOrNull("mtime")?.times(1000),
        )
        fun list(key: String) = o.optJSONArray(key).objects().map {
            FileInfo(it.optString("name"), it.longOrNull("size"), it.longOrNull("mtime")?.times(1000), it.stringOrNull("kind"))
        }
        val reference = o.optJSONObject("reference") ?: JSONObject()
        val lastGood = o.optJSONObject("last_good")
        return FilesInfo(
            config = info("config.yaml", files.optJSONObject("config.yaml")),
            routing = info("routing.yaml", files.optJSONObject("routing.yaml")),
            providers = info("providers.yaml", files.optJSONObject("providers.yaml")),
            cache = info("cache/classify-cache.json", files.optJSONObject("cache/classify-cache.json")),
            referenceCount = reference.optInt("count"),
            referenceSize = reference.optLong("size"),
            rulesets = list("rulesets"),
            geodata = list("geodata"),
            live = info("config.yaml", o.optJSONObject("live")),
            lastGood = info("config.last-good.yaml", lastGood),
            lastGoodSameAsLive = lastGood?.takeIf { it.has("same_as_live") }?.optBoolean("same_as_live"),
            backups = o.optInt("backups"),
        )
    }

    suspend fun backups(): List<BackupInfo> =
        json(listOf("backups", "--json")).first.optJSONArray("backups").objects().map {
            BackupInfo(
                id = it.optString("id"),
                createdAt = it.optLong("created") * 1000,
                reason = it.optString("reason"),
                files = it.optJSONArray("files").strings(),
            )
        }

    enum class ImportKind(val arg: String) {
        CONFIG("config"), ROUTING("routing"), PROVIDERS("providers"), BUNDLE("bundle"), RULESET("ruleset"),
        /** The node locations (`cache/classify-cache.json`). */
        CACHE("cache"),
        /** A geodata database; the name is its kind (mmdb, geoip, geosite, asn). */
        GEODATA("geodata"),
    }

    /** Imports [bytes]: validated, the replaced files backed up, then installed (not applied). */
    suspend fun import(kind: ImportKind, bytes: ByteArray, whole: Boolean = false, name: String? = null): Pair<ImportReport, String> {
        val args = mutableListOf("import", kind.arg)
        if (name != null) args += name
        if (whole) args += "--whole"
        args += "--json"
        val (o, output) = json(args, input = bytes)
        return report(o) to output
    }

    suspend fun restore(id: String?): Pair<ImportReport, String> {
        val args = if (id == null) listOf("restore", "--json") else listOf("restore", id, "--json")
        val (o, output) = json(args)
        return report(o) to output
    }

    /** An import shape given as it is (geodata, node locations, a rule list's removal). */
    suspend fun importRaw(args: List<String>, bytes: ByteArray?): Pair<ImportReport, String> {
        val (o, output) = json(args, input = bytes)
        return report(o) to output
    }

    private fun report(o: JSONObject) = ImportReport(
        kind = o.optString("kind"),
        backup = o.stringOrNull("backup"),
        changed = o.optJSONArray("changed").strings(),
        removed = o.optJSONArray("removed").strings(),
        keptSets = o.optJSONArray("kept_sets").strings(),
        movedPaths = o.optJSONArray("moved_paths").strings(),
        fetched = o.optJSONArray("fetched").strings(),
        missingNodes = o.optJSONArray("missing_nodes").strings(),
        proxies = o.optInt("proxies"),
        groups = o.optInt("groups"),
        rules = o.optInt("rules"),
    )

    /** The manager's files as one JSON bundle; subscriptions and node lists only with [secrets]. */
    suspend fun export(secrets: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val code = ProxyClient.exportTo(secrets, out)
        val text = out.toString(Charsets.UTF_8.name()).trim()
        if (code != 0) throw ManagerException(if (secrets) listOf("export", "--secrets") else listOf("export"), code, "")
        // Refuse anything but a bundle, so a warning never ends up in the file.
        try {
            val o = JSONObject(text)
            if (o.optString("format") != "lujarch-proxy") throw JSONException("not a bundle")
        } catch (e: JSONException) {
            throw ProxyException(ProxyException.Kind.PROTOCOL, "mihomo.manager export", "output is not a bundle")
        }
        return (text + "\n").toByteArray(Charsets.UTF_8)
    }

    // ---- config lifecycle ----

    suspend fun build(): Pair<ConfigResult, String> = configResult(listOf("build", "--json"))

    suspend fun validate(): Pair<ConfigResult, String> = configResult(listOf("validate", "--json"))

    suspend fun rollback(): Pair<ConfigResult, String> = configResult(listOf("rollback", "--json"))

    private suspend fun configResult(args: List<String>): Pair<ConfigResult, String> {
        val (o, output) = json(args, allowFailure = true)
        val result = ConfigResult(
            ok = o.optBoolean("ok"),
            error = o.stringOrNull("error"),
            proxies = o.intOrNull("proxies"),
            groups = o.intOrNull("groups"),
            rules = o.intOrNull("rules"),
            skippedRules = o.intOrNull("skipped_rules"),
            unresolved = o.optJSONArray("unresolved").strings(),
            missingNodes = o.optJSONArray("missing_nodes").strings(),
            outcome = o.stringOrNull("outcome") ?: o.stringOrNull("validation"),
            log = o.optJSONArray("log").strings(),
            unclassified = o.optInt("unclassified"),
        )
        return result to output
    }

    /** Stops and starts the core, and waits until the new one runs. */
    suspend fun coreRestart() {
        val before = ProxyClient.status().startedAt
        ProxyClient.restart()
        fun up(state: String?) = state == ProxyInfo.STATE_RUNNING || state == ProxyInfo.STATE_FALLBACK
        fun ended(state: String?) = state == ProxyInfo.STATE_NO_CONFIG ||
            state == ProxyInfo.STATE_CRASH_LOOP || state == ProxyInfo.STATE_OFF
        val status = withTimeoutOrNull(RESTART_TIMEOUT_MS) {
            var status = ProxyClient.status()
            while (!(up(status.state) && status.startedAt != before) && !ended(status.state)) {
                delay(500)
                status = ProxyClient.status()
            }
            status
        }
        if (status == null || !up(status.state)) {
            throw ProxyException(
                ProxyException.Kind.REMOTE, "restart", "the core did not start again (${status?.state})",
            )
        }
    }

    /**
     * A new controller secret for this device: it is written into the secret
     * file, the installed configuration and its last-good copy, and the
     * running core is made to use it (a hot reload, else a restart). The
     * secret itself never reaches this app.
     */
    suspend fun rotateSecret(): SecretRotation {
        val (o, output) = json(listOf("secret", "rotate", "--json"))
        return SecretRotation(o.optBoolean("restarted"), o.optJSONArray("files").strings().size, output)
    }

    /** `custom-group list --json` of one group: its own test parameters and the effective ones. */
    suspend fun customGroup(key: String): CustomGroup? = customGroups().find { it.key == key }
}

/** `secret rotate --json`: [restarted] when the core had to be restarted for it. */
data class SecretRotation(val restarted: Boolean, val files: Int, val output: String)

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> opt(i)?.takeIf { it != JSONObject.NULL }?.toString() }
}

internal fun JSONObject.intOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

internal fun JSONObject.longOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null
