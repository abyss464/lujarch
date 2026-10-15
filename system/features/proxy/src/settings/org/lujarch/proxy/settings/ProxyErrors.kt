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
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.lujarch.settings.pages.Errors

/**
 * Runs a user action of a proxy page (no task); a failure is logged and
 * shown in a toast in words ([ProxyErrors.describe]). Returns true on success.
 */
suspend fun proxyAction(context: Context, @StringRes action: Int, block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "${context.resources.getResourceEntryName(action)} failed: ${e.message}", e)
        // A failure that reaches a cancelled page ends it (kotlinx hands it over before the cancellation).
        currentCoroutineContext().ensureActive()
        Toast.makeText(context, context.getString(action) + "\n" + ProxyErrors.describe(context, e), Toast.LENGTH_LONG).show()
        false
    }

/**
 * Failures of the proxy's configuration commands in words. The manager and
 * the Lujarch system service report in English; the pages say what happened
 * in the page's language and keep the original text for "Detailed output"
 * ([rawText]).
 */
object ProxyErrors {
    /** What went wrong, localized. */
    fun describe(context: Context, t: Throwable?): String = when (t) {
        null -> ""
        is ProxyTasks.ConfigFailedException -> context.getString(configFailure(t.result.error ?: ""))
        is ManagerException -> context.getString(managerFailure(managerText(t)))
        is ProxyException -> when {
            t.kind == ProxyException.Kind.BUSY || (t.kind == ProxyException.Kind.REMOTE && t.detail == "busy") ->
                context.getString(R.string.error_busy)
            t.kind == ProxyException.Kind.REMOTE -> context.getString(R.string.px_err_refused)
            else -> Errors.describe(context, t)
        }
        else -> Errors.describe(context, t)
    }

    /** The original text of a failure, for "Detailed output". */
    fun rawText(t: Throwable?): String? = when (t) {
        null -> null
        is ManagerException -> t.output.ifBlank { null }
        is ProxyException -> t.detail.ifBlank { null }
        is ProxyTasks.ConfigFailedException -> t.result.error
        else -> t.message
    }

    /** The manager's own message: the JSON "error", else its last output line. */
    private fun managerText(t: ManagerException): String = when (t) {
        is ManagerJsonException -> t.error
        else -> t.reason
    }

    private fun configFailure(error: String): Int = when {
        "reload rejected" in error -> R.string.px_err_reload
        "health check failed" in error -> R.string.px_err_reload
        "Validate: FAILED" in error -> R.string.px_err_core_check
        "no last-good config" in error -> R.string.px_err_no_last_good
        else -> managerFailure(error)
    }

    private fun managerFailure(text: String): Int {
        val m = text.removePrefix("error: ")
        return when {
            "does not pass the core" in m || "Validate: FAILED" in m -> R.string.px_err_core_check
            "reload rejected" in m || "health check failed" in m -> R.string.px_err_reload
            "no last-good config" in m -> R.string.px_err_no_last_good
            "the list changed" in m -> R.string.px_err_list_changed
            "unknown rule type" in m || "needs a pattern" in m || "invalid rule" in m || "invalid compound rule" in m ||
                "takes only a target" in m || "of the list" in m || "has no target" in m -> R.string.px_err_rule
            "the list holds no rule" in m -> R.string.px_err_rule
            "no set named" in m -> R.string.px_err_no_set
            "MATCH" in m && ("holds" in m || "stays" in m || "catch-all" in m) -> R.string.px_err_final_rule
            "profile:" in m || "set by the android profile" in m -> R.string.px_err_forced
            "has no download URL" in m -> R.string.px_err_ruleset_in_use
            "no rule list" in m -> R.string.px_err_file_gone
            "not a MaxMind database" in m || "list (.dat)" in m || "too small to be geodata" in m -> R.string.px_err_geodata_format
            "does not accept the new geodata" in m -> R.string.px_err_geodata_refused
            "larger than" in m -> R.string.px_err_too_large
            "probe instance failed" in m || "API did not become ready" in m || "spawn mihomo" in m -> R.string.px_err_probe
            "no node named" in m -> R.string.px_err_no_node
            "download subscription" in m || "fetch provider" in m || "fetch failed" in m -> R.string.px_err_download
            "no URL on stdin" in m || "must start with http" in m || "URL on stdin" in m -> R.string.px_err_url
            "no provider has a node list" in m -> R.string.px_err_no_nodes
            "controller did not come up" in m -> R.string.px_err_core_start
            "there is no backup" in m || "no backup '" in m -> R.string.px_err_no_backup
            "not UTF-8" in m || "parse " in m || "has no 'payload'" in m || "not a lujarch-proxy bundle" in m ||
                "bundle version" in m || "lists no provider" in m || "holds no file" in m -> R.string.px_err_file
            "must be" in m || "is not JSON" in m || "is not a DNS server" in m || "invalid" in m -> R.string.px_err_value
            else -> R.string.px_err_manager
        }
    }
}
