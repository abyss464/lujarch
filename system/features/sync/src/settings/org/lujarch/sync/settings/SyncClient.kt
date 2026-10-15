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

import android.app.ActivityThread
import android.content.Context
import android.os.DeadObjectException
import android.os.RemoteException
import android.os.ServiceManager
import android.os.ServiceSpecificException
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.lujarch.settings.pages.Failure
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.sync.ISync

/** Log tag of the sync pages. */
const val TAG = "lujarch_sync"

/**
 * A failed request to the sync service; [kind] and [code] select the sentence shown to the owner,
 * [detail] (the service's own text) is shown only behind Details.
 */
class SyncException(
    val kind: Kind,
    val command: String,
    val detail: String,
    val httpStatus: Int = 0,
    cause: Throwable? = null,
    /** REMOTE: the service's name of the condition ("folder_in_use", "sync_stopped", ...). */
    val code: String? = null,
    val refused: Boolean = false,
) : Exception("$command: $detail", cause), Failure {
    enum class Kind { UNAVAILABLE, TIMEOUT, IO, PROTOCOL, REMOTE, BUSY, HTTP, CLOSED }

    override val unavailable: Boolean
        get() = kind == Kind.UNAVAILABLE

    override fun describe(context: Context): String =
        when (kind) {
            Kind.UNAVAILABLE -> context.getString(PagesR.string.error_unavailable_sentence)
            Kind.TIMEOUT -> context.getString(PagesR.string.error_timeout)
            Kind.IO, Kind.CLOSED -> context.getString(PagesR.string.error_io_sentence)
            Kind.PROTOCOL -> context.getString(PagesR.string.error_protocol_sentence)
            Kind.BUSY -> context.getString(PagesR.string.error_busy_service)
            Kind.HTTP -> context.getString(R.string.error_sync_http, httpStatus)
            Kind.REMOTE ->
                context.getString(
                    when (code) {
                        "not_allowed" -> R.string.error_code_not_allowed
                        "sync_stopped" -> R.string.error_code_sync_stopped
                        "sync_unreachable" -> R.string.error_code_sync_unreachable
                        "folder_in_use" -> R.string.error_code_folder_in_use
                        "no_folder" -> R.string.error_code_no_folder
                        "folder_not_movable" -> R.string.error_code_folder_not_movable
                        "override_deletes" -> R.string.error_code_override_deletes
                        "versions_in_folder" -> R.string.error_code_versions_in_folder
                        else -> if (refused) PagesR.string.error_refused else PagesR.string.error_failed
                    }
                )
        }

    override val details: String
        get() = buildString {
            append(command)
            if (httpStatus > 0) append("\nHTTP ").append(httpStatus)
            if (code != null) append("\n").append(code)
            if (detail.isNotBlank()) append("\n\n").append(detail)
        }
}

/** Use sync and the service's state. */
data class SyncInfo(val syncEnabled: Boolean, val syncState: String) {
    companion object {
        const val STATE_RUNNING = ISync.STATE_RUNNING
        const val STATE_WAITING = ISync.STATE_WAITING
        const val STATE_NO_CONFIG = ISync.STATE_NO_CONFIG
        const val STATE_CRASH_LOOP = ISync.STATE_CRASH_LOOP
        const val STATE_STARTING = ISync.STATE_STARTING
        const val STATE_OFF = ISync.STATE_OFF
    }
}

/** The engine's device identity on this device. */
data class IdentityState(
    /** The engine has an identity to start with (it may create the key and certificate then). */
    val present: Boolean,
    val hasConfig: Boolean,
    val enabled: Boolean,
    val state: String?,
    val backups: List<IdentityBackup>,
)

/** A previous identity kept when it was replaced; [name] is YYYYMMDD-HHMMSS[-N]. */
data class IdentityBackup(val name: String, val deviceId: String?, val complete: Boolean)

/** What installing an identity did. */
data class IdentityInstalled(
    /** The engine runs again with it (else it starts with it when sync may run). */
    val restarted: Boolean,
    val enabled: Boolean,
    /** The new device ID (imports). */
    val deviceId: String?,
    /** Folder ID -> directory of the sync storage (imported configurations). */
    val folders: Map<String, String>,
)

/** What the Sync page shows of the service besides the engine. */
data class HealthState(
    val syncEnabled: Boolean,
    val syncState: String?,
    val syncHomeOk: Boolean,
    val syncIdentity: Boolean,
    /** What a stopped sync waits for ("wifi", "charging"); empty when it is not waiting. */
    val syncWaiting: List<String> = emptyList(),
)

/** The sync service (lujarch_sync) and its switches. Every call runs off the main thread. */
object SyncClient {
    const val TIMEOUT_SHORT_MS = 8_000L
    const val TIMEOUT_API_MS = 40_000L

    const val SETTING_ENABLED = "lujarch_sync_enabled"
    const val SETTING_WIFI_ONLY = "lujarch_sync_wifi_only"
    const val SETTING_CHARGING_ONLY = "lujarch_sync_charging_only"

    private const val SERVICE = "lujarch_sync"

    private val app: Context
        get() = ActivityThread.currentApplication()

    private val CODES = mapOf(
        ISync.ERROR_NOT_ALLOWED to "not_allowed",
        ISync.ERROR_STOPPED to "sync_stopped",
        ISync.ERROR_UNREACHABLE to "sync_unreachable",
        ISync.ERROR_FOLDER_IN_USE to "folder_in_use",
        ISync.ERROR_NO_FOLDER to "no_folder",
        ISync.ERROR_NOT_MOVABLE to "folder_not_movable",
        ISync.ERROR_OVERRIDE_DELETES to "override_deletes",
        ISync.ERROR_VERSIONS_IN_FOLDER to "versions_in_folder",
    )

    private fun service(command: String): ISync =
        ISync.Stub.asInterface(ServiceManager.checkService(SERVICE))
            ?: throw SyncException(SyncException.Kind.UNAVAILABLE, command, "$SERVICE is not running")

    private suspend fun <T> call(command: String, timeoutMs: Long, block: (ISync) -> T): T =
        try {
            withTimeout(timeoutMs) { withContext(Dispatchers.IO) { block(service(command)) } }
        } catch (e: TimeoutCancellationException) {
            throw SyncException(SyncException.Kind.TIMEOUT, command, "no answer within ${timeoutMs / 1000} s", cause = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: ServiceSpecificException) {
            val code = CODES[e.errorCode]
            throw SyncException(
                SyncException.Kind.REMOTE, command, e.message ?: "", code = code,
                refused = e.errorCode == ISync.ERROR_REFUSED || e.errorCode == ISync.ERROR_NOT_ALLOWED,
            )
        } catch (e: DeadObjectException) {
            throw SyncException(SyncException.Kind.UNAVAILABLE, command, e.toString(), cause = e)
        } catch (e: RemoteException) {
            throw SyncException(SyncException.Kind.IO, command, e.toString(), cause = e)
        }

    private fun json(command: String, text: String): JSONObject =
        try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw SyncException(SyncException.Kind.PROTOCOL, command, "not JSON: ${e.message}", cause = e)
        }

    fun enabled(context: Context = app): Boolean =
        Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1

    suspend fun info(): SyncInfo {
        val status = call("status", TIMEOUT_SHORT_MS) { it.status }
        return SyncInfo(enabled(), status.state ?: "")
    }

    /** Turns sync on or off (SYN-1); the service follows the switch. */
    suspend fun syncEnable(on: Boolean) {
        setGlobal(SETTING_ENABLED, on)
        Log.i(TAG, "sync turned ${if (on) "on" else "off"}")
    }

    private suspend fun setGlobal(key: String, on: Boolean) {
        val written = withContext(Dispatchers.IO) {
            Settings.Global.putInt(app.contentResolver, key, if (on) 1 else 0)
        }
        if (!written) throw SyncException(SyncException.Kind.REMOTE, "setting", "$key not written")
    }

    /**
     * A REST request. Returns the reply body (JSONObject, JSONArray, String or null); an HTTP
     * status outside 2xx throws [SyncException.Kind.HTTP].
     */
    suspend fun syncApi(method: String, path: String, body: Any? = null, timeoutMs: Long = TIMEOUT_API_MS): Any? {
        val command = "$method $path"
        val reply = call(command, timeoutMs) { it.api(method, path, body?.toString()) }
        val value = normalizeJson(reply.body)
        if (reply.status !in 200..299) {
            val message = when (value) {
                is JSONObject -> value.stringOrNull("message") ?: value.stringOrNull("error") ?: value.toString()
                null -> ""
                else -> value.toString().trim()
            }
            throw SyncException(SyncException.Kind.HTTP, command, message.take(500), reply.status)
        }
        return value
    }

    suspend fun dirs(): JSONObject = json("dirs", call("dirs", TIMEOUT_SHORT_MS) { it.dirs() })

    suspend fun ignored(op: String, device: String?, name: String?, address: String?): JSONObject =
        json("ignored", call("ignored", TIMEOUT_API_MS) { it.ignored(op, device, name, address) })

    /** The run conditions; each argument that is not null is changed first (SYN-2). */
    suspend fun conditions(wifiOnly: Boolean? = null, chargingOnly: Boolean? = null): JSONObject {
        wifiOnly?.let { setGlobal(SETTING_WIFI_ONLY, it) }
        chargingOnly?.let { setGlobal(SETTING_CHARGING_ONLY, it) }
        return json("conditions", call("conditions", TIMEOUT_SHORT_MS) { it.conditions() })
    }

    suspend fun moveFolder(folder: String, dir: String): JSONObject =
        json("moveFolder", call("moveFolder", TIMEOUT_API_MS) { it.moveFolder(folder, dir) })

    suspend fun health(): HealthState {
        val status = call("status", TIMEOUT_SHORT_MS) { it.status }
        return HealthState(
            syncEnabled = enabled(),
            syncState = status.state,
            syncHomeOk = status.storageOk,
            syncIdentity = status.identity,
            syncWaiting = (status.waiting ?: "").split(',').filter { it.isNotEmpty() },
        )
    }

    suspend fun identity(): IdentityState {
        val r = json("identity", call("identity", TIMEOUT_SHORT_MS) { it.identity() })
        val backups = r.optJSONArray("backups") ?: JSONArray()
        return IdentityState(
            present = r.optBoolean("present"),
            hasConfig = r.optBoolean("config"),
            enabled = r.optBoolean("enabled"),
            state = r.stringOrNull("state"),
            backups = (0 until backups.length()).mapNotNull { i ->
                val b = backups.optJSONObject(i) ?: return@mapNotNull null
                IdentityBackup(b.optString("name"), b.stringOrNull("device"), b.optBoolean("complete"))
            },
        )
    }

    /**
     * Imports a certificate and key (and optionally a configuration) chosen by the owner. The
     * contents go to the service and are never logged.
     */
    suspend fun importIdentity(cert: String, key: String, config: String?, keepConfig: Boolean): IdentityInstalled =
        installed(
            json("importIdentity", call("importIdentity", TIMEOUT_API_MS) {
                it.importIdentity(cert.toByteArray(), key.toByteArray(), config?.toByteArray(), keepConfig)
            })
        )

    suspend fun generateIdentity(keepConfig: Boolean): IdentityInstalled =
        installed(json("generateIdentity", call("generateIdentity", TIMEOUT_API_MS) { it.generateIdentity(keepConfig) }))

    suspend fun restoreIdentity(backup: String): IdentityInstalled =
        installed(json("restoreIdentity", call("restoreIdentity", TIMEOUT_API_MS) { it.restoreIdentity(backup) }))

    private fun installed(r: JSONObject): IdentityInstalled {
        val folders = LinkedHashMap<String, String>()
        val list = r.optJSONArray("folders") ?: JSONArray()
        for (i in 0 until list.length()) {
            val f = list.optJSONObject(i) ?: continue
            folders[f.optString("id")] = f.optString("dir")
        }
        return IdentityInstalled(
            restarted = r.optBoolean("restarted"),
            enabled = r.optBoolean("enabled"),
            deviceId = r.stringOrNull("device"),
            folders = folders,
        )
    }
}

/** A JSON text becomes its value; other strings stay as they are. */
internal fun normalizeJson(value: Any?): Any? {
    if (value == null || value == JSONObject.NULL) return null
    if (value is String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSONTokener(trimmed).nextValue()
            } catch (e: JSONException) {
                Log.w(TAG, "body looks like JSON but does not parse: ${e.message}")
            }
        }
    }
    return value
}

/** A string value, or null when absent, JSON null or empty. */
/** A boolean that the service may write as a JSON boolean or as the text "true". */
internal fun JSONObject.looseBoolean(name: String): Boolean = when (val v = opt(name)) {
    is Boolean -> v
    is String -> v.equals("true", ignoreCase = true)
    else -> false
}

internal fun JSONObject.stringOrNull(name: String): String? {
    val value = opt(name)
    if (value == null || value == JSONObject.NULL) return null
    return value.toString().ifEmpty { null }
}
