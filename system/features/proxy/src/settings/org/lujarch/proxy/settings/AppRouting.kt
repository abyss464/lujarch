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

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.lujarch.settings.pages.Failure
import org.lujarch.settings.pages.Format

/** A mihomo-manager run that exited with a non-zero code; [output] is redacted by the proxy daemon. */
open class ManagerException(val args: List<String>, val code: Int, val output: String) :
    Exception("mihomo-manager ${args.joinToString(" ")} exited with $code: ${lastLine(output)}"),
    Failure {
    override fun describe(context: Context): String = ProxyErrors.describe(context, this)

    override val details: String
        get() = buildString {
            append(args.joinToString(" ")).append("\nexit ").append(code)
            val text = Format.stripAnsi(output).trim()
            if (text.isNotEmpty()) append("\n\n").append(text)
        }

    /** The line that says what failed (the manager prints "error: ..." last). */
    val reason: String
        get() = lastLine(output)

    companion object {
        fun lastLine(text: String): String =
            text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: ""
    }
}

/** One per-app rule of `mihomo-manager app list --json`. */
data class AppRule(
    /** Android package name. */
    val app: String,
    /** Routing target in manager syntax: DIRECT, REJECT, JP/residential, fallback:ABLENET, ... */
    val target: String,
    /** Group the target selects in the built config (a controller group name), DIRECT or REJECT; null when unknown. */
    val group: String?,
    val set: String,
    val index: Int,
    /** Rules of a disabled rule set are not in the config. */
    val enabled: Boolean,
)

/** What decides where an app's traffic goes. */
sealed interface AppRoute {
    /** No per-app rule: the domain and IP rules decide each connection. */
    data object FollowRules : AppRoute

    /** The app's own rule (the first enabled one). */
    data class OwnRule(val rule: AppRule) : AppRoute

    /** On the TUN exclude list: the app's traffic never enters the TUN. */
    data object Bypass : AppRoute

    /** The TUN include list is in use and the app is not on it, so its traffic never enters the TUN. */
    data object NotIncluded : AppRoute
}

/**
 * Per-app routing as saved in mihomo-manager's data (`app list --json`).
 * Apps that share a uid ([SharedUsers]) have one route: the rule saved
 * under their shared user, and every TUN list entry of the uid.
 */
class AppRouting(
    val rules: List<AppRule>,
    val tunInclude: List<String>,
    val tunExclude: List<String>,
    val sharedUsers: SharedUsers = SharedUsers.NONE,
) {
    /**
     * Packages with a rule or on a TUN list. A shared user stands for its
     * installed packages; one without installed packages is listed by its
     * name, so that its rules can still be removed.
     */
    val packages: Set<String> = (rules.map { it.app } + tunInclude + tunExclude)
        .flatMap { name -> sharedUsers.packagesOf(name).ifEmpty { listOf(name) } }
        .toHashSet()

    fun withSharedUsers(users: SharedUsers) = AppRouting(rules, tunInclude, tunExclude, users)

    /** The name a rule for [packageName] is saved under: its shared user, or the package. */
    fun ruleKey(packageName: String): String = sharedUsers.ruleKey(packageName)

    /** Every name of [packageName]'s uid: the package, the others that share its uid and their shared user. */
    fun namesOf(packageName: String): Set<String> =
        buildSet {
            add(packageName)
            addAll(sharedUsers.members(packageName))
            sharedUsers.sharedUser(packageName)?.let { add(it) }
        }

    /**
     * The names whose rules route the app in the core: its shared user, and
     * the package itself while it is the only installed package of its uid
     * (the core names a uid of several packages by the shared user).
     */
    private fun ruleNames(packageName: String): Set<String> {
        val user = sharedUsers.sharedUser(packageName) ?: return setOf(packageName)
        return if (sharedUsers.members(packageName).size > 1) setOf(user) else setOf(user, packageName)
    }

    /** Rules saved for the app: on the package itself or on its shared user. */
    fun rulesOf(packageName: String): List<AppRule> {
        val user = sharedUsers.sharedUser(packageName)
        return rules.filter { it.app == packageName || it.app == user }
    }

    fun route(packageName: String): AppRoute {
        val names = namesOf(packageName)
        return when {
            names.any { it in tunExclude } -> AppRoute.Bypass
            tunInclude.isNotEmpty() && names.none { it in tunInclude } -> AppRoute.NotIncluded
            else -> {
                // The first enabled rule in rule order, as the core matches them.
                val ruled = ruleNames(packageName)
                rules.firstOrNull { it.enabled && it.app in ruled }?.let { AppRoute.OwnRule(it) } ?: AppRoute.FollowRules
            }
        }
    }

    /** The app has something of its own: a rule (also in a disabled set) or a TUN list entry of its uid. */
    fun hasOwnEntry(packageName: String): Boolean =
        rulesOf(packageName).isNotEmpty() || namesOf(packageName).any { it in tunInclude || it in tunExclude }
}

/** A target `app add` accepts (`app targets --json`). */
data class RouteTarget(
    /** Manager syntax, what `app add` takes. */
    val target: String,
    /** Group name in the built config (as the controller shows it), or DIRECT/REJECT. */
    val group: String,
    /** builtin, custom, country, class (country and line type) or all. */
    val kind: String,
    /** url-test, fallback, load-balance or adaptive; null for DIRECT and REJECT. */
    val type: String?,
    val proxies: Int?,
    /** Members that are nodes and members that are groups (a custom group's last resort). */
    val nodes: Int? = null,
    val memberGroups: Int? = null,
) {
    /**
     * A group the pickers always show: lowest delay, or adaptive (one group
     * in place of a custom group's three, ). Fallback and load-balance
     * groups are variants behind a switch.
     */
    val isPrimary: Boolean
        get() = type == TYPE_URL_TEST || type == TYPE_ADAPTIVE

    companion object {
        const val DIRECT = "DIRECT"
        const val REJECT = "REJECT"
        const val KIND_BUILTIN = "builtin"
        const val KIND_CUSTOM = "custom"
        const val KIND_COUNTRY = "country"
        const val KIND_CLASS = "class"
        const val KIND_ALL = "all"
        const val TYPE_URL_TEST = "url-test"
        const val TYPE_ADAPTIVE = "adaptive"
    }
}

/**
 * The per-app routing commands of mihomo-manager through the proxy daemon. Only the
 * JSON outputs are parsed; the other commands are judged by their exit code.
 */
object AppRoutingClient {
    val LIST = listOf("app", "list", "--json")
    val TARGETS = listOf("app", "targets", "--json")

    /** Characters the proxy daemon accepts in an `app add` target (docs/settings.md): these ASCII ones and any non-ASCII. */
    private val TARGET_CHARS = Regex("^[A-Za-z0-9_./:+@\\u0080-\\uFFFF][A-Za-z0-9_./:+@\\u0080-\\uFFFF-]*$")

    fun isUsableTarget(target: String): Boolean =
        TARGET_CHARS.matches(target) && target.toByteArray(Charsets.UTF_8).size <= 128

    /**
     * Runs a manager command in the app's line of manager commands
     * ([ManagerCalls]); a non-zero exit throws [ManagerException].
     */
    suspend fun run(args: List<String>): ManagerResult {
        val result = ManagerCalls.run(args)
        if (!result.success) throw ManagerException(args, result.code, result.output)
        return result
    }

    suspend fun list(): AppRouting = parseList(run(LIST).output)

    suspend fun targets(): List<RouteTarget> = parseTargets(run(TARGETS).output)

    fun parseList(output: String): AppRouting {
        val obj = parseObject(output, LIST)
        val apps = obj.optJSONArray("apps") ?: throw invalid(LIST, "no \"apps\" array")
        val rules = ArrayList<AppRule>(apps.length())
        for (i in 0 until apps.length()) {
            val a = apps.optJSONObject(i) ?: continue
            val app = a.stringOrNull("app") ?: continue
            val target = a.stringOrNull("target") ?: continue
            rules.add(
                AppRule(
                    app = app,
                    target = target,
                    group = a.stringOrNull("group"),
                    set = a.stringOrNull("set") ?: "",
                    index = a.optInt("index", -1),
                    enabled = a.optBoolean("enabled", true),
                )
            )
        }
        val tun = obj.optJSONObject("tun") ?: JSONObject()
        return AppRouting(rules, strings(tun.optJSONArray("include")), strings(tun.optJSONArray("exclude")))
    }

    fun parseTargets(output: String): List<RouteTarget> {
        val obj = parseObject(output, TARGETS)
        val list = obj.optJSONArray("targets") ?: throw invalid(TARGETS, "no \"targets\" array")
        val targets = ArrayList<RouteTarget>(list.length())
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val target = t.stringOrNull("target") ?: continue
            targets.add(
                RouteTarget(
                    target = target,
                    group = t.stringOrNull("group") ?: target,
                    kind = t.stringOrNull("kind") ?: "",
                    type = t.stringOrNull("type"),
                    proxies = if (t.has("proxies")) t.optInt("proxies") else null,
                    nodes = if (t.has("nodes")) t.optInt("nodes") else null,
                    memberGroups = if (t.has("member_groups")) t.optInt("member_groups") else null,
                )
            )
        }
        return targets
    }

    private fun parseObject(output: String, args: List<String>): JSONObject = try {
        JSONObject(output.trim())
    } catch (e: JSONException) {
        throw invalid(args, "not JSON: ${e.message}", e)
    }

    private fun invalid(args: List<String>, detail: String, cause: Throwable? = null) =
        ProxyException(ProxyException.Kind.PROTOCOL, "mihomo.manager ${args.joinToString(" ")}", detail, cause = cause)

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotEmpty() } }
    }
}
