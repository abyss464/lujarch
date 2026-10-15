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

import android.app.ActivityThread
import android.content.Context
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.ServiceManager
import android.os.ServiceSpecificException
import android.provider.Settings
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.lujarch.proxy.IProxy
import org.lujarch.proxy.Status
import org.lujarch.settings.pages.Failure
import org.lujarch.settings.pages.R as PagesR

/** Log tag of the proxy's pages. */
const val TAG = "lujarch_proxy"

/**
 * A failed request to the proxy's system service; [kind] and [code] select the sentence shown to
 * the owner, [detail] (the service's own text) is shown only behind Details.
 */
class ProxyException(
    val kind: Kind,
    val command: String,
    val detail: String,
    val httpStatus: Int = 0,
    cause: Throwable? = null,
    val code: String? = null,
    /** REMOTE: the service refused the request (else it could not carry it out). */
    val refused: Boolean = false,
) : Exception("$command: $detail", cause), Failure {
    enum class Kind {
        /** The service is not running. */
        UNAVAILABLE,
        /** No answer within the request's time. */
        TIMEOUT,
        /** The connection broke (a stream). */
        IO,
        /** The answer is not what was expected. */
        PROTOCOL,
        /** The service refused or failed the request. */
        REMOTE,
        /** The service runs another manager command. */
        BUSY,
        /** The core's controller answered with an HTTP error status. */
        HTTP,
        /** The service ended a stream. */
        CLOSED,
    }

    override val unavailable: Boolean
        get() = kind == Kind.UNAVAILABLE

    override fun describe(context: Context): String =
        when (kind) {
            Kind.UNAVAILABLE -> context.getString(PagesR.string.error_unavailable_sentence)
            Kind.TIMEOUT -> context.getString(PagesR.string.error_timeout)
            Kind.IO -> context.getString(PagesR.string.error_io_sentence)
            Kind.PROTOCOL -> context.getString(PagesR.string.error_protocol_sentence)
            Kind.REMOTE ->
                context.getString(if (refused) PagesR.string.error_refused else PagesR.string.error_failed)
            Kind.BUSY -> context.getString(R.string.error_busy)
            Kind.HTTP -> context.getString(R.string.error_http, httpStatus)
            Kind.CLOSED -> context.getString(PagesR.string.error_closed)
        }

    override val details: String
        get() = buildString {
            append(command)
            if (httpStatus > 0) append("\nHTTP ").append(httpStatus)
            if (detail.isNotBlank()) append("\n\n").append(detail)
        }
}

/** The proxy's state as its pages read it. */
data class ProxyInfo(
    /** Use proxy (PRX-1). */
    val mihomoEnabled: Boolean,
    /** One of IProxy's STATE_ constants. */
    val mihomoState: String,
    val status: Status,
) {
    /** A core runs and its controller answers (the last working configuration included). */
    val controllerUp: Boolean
        get() = mihomoState == STATE_RUNNING || mihomoState == STATE_FALLBACK

    companion object {
        const val STATE_OFF = IProxy.STATE_OFF
        const val STATE_STARTING = IProxy.STATE_STARTING
        const val STATE_RUNNING = IProxy.STATE_RUNNING
        const val STATE_FALLBACK = IProxy.STATE_FALLBACK
        const val STATE_NO_CONFIG = IProxy.STATE_NO_CONFIG
        const val STATE_CRASH_LOOP = IProxy.STATE_CRASH_LOOP
    }
}

/** A run of the manager. */
data class ManagerResult(val code: Int, val output: String) {
    val success: Boolean
        get() = code == 0
}

/**
 * The proxy's system service (lujarch_proxy) and its switch. Every call runs off the main thread;
 * cancelling the caller stops the wait (the service finishes the request on its own).
 */
object ProxyClient {
    const val TIMEOUT_SHORT_MS = 8_000L
    const val TIMEOUT_API_MS = 30_000L
    const val TIMEOUT_MANAGER_MS = 320_000L

    /** The switch of PRX-1 (settings.toml of proxy). */
    const val SETTING_ENABLED = "lujarch_proxy_enabled"
    const val SETTING_REPAIR = "lujarch_proxy_repair"
    const val SETTING_IPV6_BLOCK = "lujarch_proxy_ipv6_block"

    private const val SERVICE = "lujarch_proxy"

    private fun service(command: String): IProxy =
        IProxy.Stub.asInterface(ServiceManager.checkService(SERVICE))
            ?: throw ProxyException(ProxyException.Kind.UNAVAILABLE, command, "$SERVICE is not running")

    /** A call to the service on an IO thread, its failures as [ProxyException]. */
    private suspend fun <T> call(command: String, timeoutMs: Long, block: (IProxy) -> T): T =
        try {
            withTimeout(timeoutMs) { withContext(Dispatchers.IO) { block(service(command)) } }
        } catch (e: TimeoutCancellationException) {
            throw ProxyException(
                ProxyException.Kind.TIMEOUT,
                command,
                "no answer within ${timeoutMs / 1000} s",
                cause = e,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProxyException) {
            throw e
        } catch (e: Exception) {
            throw mapError(e, command)
        }

    private fun mapError(e: Throwable, command: String): ProxyException =
        when (e) {
            is ServiceSpecificException ->
                when (e.errorCode) {
                    IProxy.ERROR_BUSY ->
                        ProxyException(ProxyException.Kind.BUSY, command, e.message ?: "busy")
                    IProxy.ERROR_REFUSED ->
                        ProxyException(
                            ProxyException.Kind.REMOTE,
                            command,
                            e.message ?: "",
                            refused = true,
                        )
                    else -> ProxyException(ProxyException.Kind.REMOTE, command, e.message ?: "")
                }
            is DeadObjectException ->
                ProxyException(ProxyException.Kind.UNAVAILABLE, command, e.toString(), cause = e)
            is RemoteException, is IOException ->
                ProxyException(ProxyException.Kind.IO, command, e.toString(), cause = e)
            else -> ProxyException(ProxyException.Kind.PROTOCOL, command, e.toString(), cause = e)
        }

    private val app: Context
        get() = ActivityThread.currentApplication()

    fun enabled(context: Context = app): Boolean =
        Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1

    /** Turns the proxy on or off (PRX-1); the service follows the switch. */
    suspend fun setEnabled(on: Boolean, context: Context = app) {
        val written =
            withContext(Dispatchers.IO) {
                Settings.Global.putInt(context.contentResolver, SETTING_ENABLED, if (on) 1 else 0)
            }
        if (!written) {
            throw ProxyException(ProxyException.Kind.REMOTE, "setting", "$SETTING_ENABLED not written")
        }
        Log.i(TAG, "proxy turned ${if (on) "on" else "off"}")
    }

    suspend fun status(): Status = call("status", TIMEOUT_SHORT_MS) { it.status }

    suspend fun info(): ProxyInfo {
        val status = status()
        return ProxyInfo(enabled(), status.state ?: "", status)
    }

    suspend fun restart() = call("restart", TIMEOUT_SHORT_MS) { it.restart() }

    /**
     * A controller request. Returns the reply body (JSONObject, JSONArray, String or null); an
     * HTTP status outside 2xx throws [ProxyException.Kind.HTTP].
     */
    suspend fun api(
        method: String,
        path: String,
        body: Any? = null,
        timeoutMs: Long = TIMEOUT_API_MS,
    ): Any? {
        val command = "$method $path"
        val reply = call(command, timeoutMs) { it.controller(method, path, body?.toString()) }
        val replyBody = normalizeJson(reply.body)
        if (reply.status !in 200..299) {
            val message =
                when (replyBody) {
                    is JSONObject ->
                        replyBody.stringOrNull("message")
                            ?: replyBody.stringOrNull("error")
                            ?: replyBody.toString()
                    null -> ""
                    else -> replyBody.toString().trim()
                }
            throw ProxyException(ProxyException.Kind.HTTP, command, message.take(500), reply.status)
        }
        return replyBody
    }

    /** A controller stream (/traffic, /logs?level=...): one JSON value per event. */
    fun stream(path: String): Flow<Any> = callbackFlow {
        val command = "stream $path"
        val socket =
            try {
                call(command, TIMEOUT_SHORT_MS) { it.stream(path) }
            } catch (e: ProxyException) {
                close(e)
                awaitClose {}
                return@callbackFlow
            }
        val job =
            launch(Dispatchers.IO) {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(socket).bufferedReader().use { reader ->
                        while (true) {
                            val line =
                                reader.readLine()
                                    ?: throw ProxyException(
                                        ProxyException.Kind.CLOSED,
                                        command,
                                        "the stream ended",
                                    )
                            if (line.isBlank()) continue
                            val data = normalizeJson(line.trim()) ?: continue
                            if (trySendBlocking(data).isClosed) break
                        }
                    }
                    channel.close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProxyException) {
                    channel.close(e)
                } catch (e: IOException) {
                    channel.close(ProxyException(ProxyException.Kind.IO, command, e.toString(), cause = e))
                }
            }
        awaitClose {
            job.cancel()
            try {
                socket.close()
            } catch (e: IOException) {
                Log.w(TAG, "closing $command: ${e.message}")
            }
        }
    }

    /**
     * A run of the manager. [input] becomes its stdin (a subscription address, an imported file)
     * for the commands that read it; it never appears in logs or errors.
     */
    suspend fun manager(
        args: List<String>,
        timeoutMs: Long = TIMEOUT_MANAGER_MS,
        input: ByteArray? = null,
    ): ManagerResult {
        val command = "manager ${args.firstOrNull() ?: ""}"
        if (input == null) {
            val reply = call(command, timeoutMs) { it.manager(args.toTypedArray(), null) }
            return ManagerResult(reply.status, reply.body ?: "")
        }
        val (read, write) = ParcelFileDescriptor.createPipe()
        return withContext(Dispatchers.IO) {
            val writer = launch {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(input) }
                } catch (e: IOException) {
                    // The manager stopped reading: its own answer says why.
                    Log.w(TAG, "$command: input not fully written: ${e.message}")
                }
            }
            try {
                val reply =
                    read.use { fd -> call(command, timeoutMs) { it.manager(args.toTypedArray(), fd) } }
                ManagerResult(reply.status, reply.body ?: "")
            } finally {
                writer.cancel()
            }
        }
    }

    /**
     * Writes the manager's export to [out]: every file of the proxy, with subscription addresses
     * and node passwords when [secrets] is set. Returns the manager's exit code.
     */
    suspend fun exportTo(secrets: Boolean, out: OutputStream): Int {
        val (read, write) = ParcelFileDescriptor.createPipe()
        return withContext(Dispatchers.IO) {
            val copier = launch {
                ParcelFileDescriptor.AutoCloseInputStream(read).use { it.copyTo(out) }
            }
            val code = write.use { fd -> call("export", TIMEOUT_MANAGER_MS) { it.exportTo(secrets, fd) } }
            copier.join()
            code
        }
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
internal fun JSONObject.stringOrNull(name: String): String? {
    val value = opt(name)
    if (value == null || value == JSONObject.NULL) return null
    return value.toString().ifEmpty { null }
}

/** true, 1, "1" or "true". */
internal fun JSONObject.looseBoolean(name: String): Boolean =
    when (val value = opt(name)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }
