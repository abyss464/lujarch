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

package org.lujarch.linux.settings

import android.content.ComponentName
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
import org.lujarch.linux.ILinux
import org.lujarch.settings.pages.Failure
import org.lujarch.settings.pages.R as PagesR

/** Log tag of the Linux development environment's pages. */
const val TAG = "lujarch_linux"

/**
 * A failed request to lujarch_linux: [code] (ILinux's ERROR_ constants, 0 for none) selects the
 * sentence shown to the owner; [detail], the service's own text, shows only behind Details.
 */
class LinuxException(
    val kind: Kind,
    val command: String,
    val detail: String,
    val code: Int = 0,
    cause: Throwable? = null,
) : Exception("$command: $detail", cause), Failure {
    enum class Kind { UNAVAILABLE, TIMEOUT, REMOTE }

    override val unavailable: Boolean
        get() = kind == Kind.UNAVAILABLE

    override fun describe(context: Context): String =
        when (kind) {
            Kind.UNAVAILABLE -> context.getString(PagesR.string.error_unavailable_sentence)
            Kind.TIMEOUT -> context.getString(PagesR.string.error_timeout)
            Kind.REMOTE -> context.getString(
                when (code) {
                    ILinux.ERROR_REFUSED -> PagesR.string.error_refused
                    ILinux.ERROR_INVALID -> R.string.lnx_error_invalid
                    ILinux.ERROR_NOT_INSTALLED -> R.string.lnx_state_not_installed
                    ILinux.ERROR_NOT_RUNNING -> R.string.lnx_error_not_running
                    ILinux.ERROR_ROOT_SESSIONS_OFF -> R.string.lnx_error_root_sessions_off
                    ILinux.ERROR_BUSY -> R.string.lnx_error_busy
                    ILinux.ERROR_NO_SERVICE -> R.string.lnx_error_no_service
                    else -> PagesR.string.error_failed
                },
            )
        }

    override val details: String
        get() = when {
            detail.isBlank() -> command
            command.isBlank() -> detail
            else -> "$command\n\n$detail"
        }
}

/** lujarch_linux and the switches of settings.toml. Every call runs off the main thread. */
object LinuxClient {
    private const val SERVICE = "lujarch_linux"
    private const val TIMEOUT_MS = 15_000L

    const val ACTION_TERMINAL = "org.lujarch.linux.action.TERMINAL"
    const val ACTION_DESKTOP = "org.lujarch.linux.action.DESKTOP"
    const val EXTRA_SESSION = "org.lujarch.linux.extra.SESSION"
    const val TERMINAL_PACKAGE = "org.lujarch.linux.terminal"
    val NOTIFICATIONS_LISTENER = ComponentName(TERMINAL_PACKAGE, "$TERMINAL_PACKAGE.NotificationsListener")

    fun service(command: String): ILinux =
        ILinux.Stub.asInterface(ServiceManager.checkService(SERVICE))
            ?: throw LinuxException(LinuxException.Kind.UNAVAILABLE, command, "$SERVICE is not running")

    suspend fun <T> call(command: String, block: (ILinux) -> T): T =
        try {
            withTimeout(TIMEOUT_MS) { withContext(Dispatchers.IO) { block(service(command)) } }
        } catch (e: TimeoutCancellationException) {
            throw LinuxException(LinuxException.Kind.TIMEOUT, command, "no answer within ${TIMEOUT_MS / 1000} s", cause = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinuxException) {
            throw e
        } catch (e: ServiceSpecificException) {
            throw LinuxException(LinuxException.Kind.REMOTE, command, e.message ?: "", e.errorCode, e)
        } catch (e: DeadObjectException) {
            throw LinuxException(LinuxException.Kind.UNAVAILABLE, command, "$SERVICE stopped", cause = e)
        } catch (e: RemoteException) {
            throw LinuxException(LinuxException.Kind.UNAVAILABLE, command, e.message ?: "", cause = e)
        }

    // ---- switches (settings.toml) ----

    private fun key(name: String) = "lujarch_linux_$name"

    fun global(context: Context, name: String): String? =
        Settings.Global.getString(context.contentResolver, key(name))

    fun secure(context: Context, name: String): String? =
        Settings.Secure.getString(context.contentResolver, key(name))

    fun on(context: Context, name: String): Boolean = global(context, name) == "1"

    suspend fun setGlobal(context: Context, name: String, value: String) {
        val written = withContext(Dispatchers.IO) {
            Settings.Global.putString(context.applicationContext.contentResolver, key(name), value)
        }
        if (!written) throw LinuxException(LinuxException.Kind.REMOTE, "setting", "${key(name)} not written")
        Log.i(TAG, "${key(name)} = $value")
    }

    suspend fun setSecure(context: Context, name: String, value: String) {
        val written = withContext(Dispatchers.IO) {
            Settings.Secure.putString(context.applicationContext.contentResolver, key(name), value)
        }
        if (!written) throw LinuxException(LinuxException.Kind.REMOTE, "setting", "${key(name)} not written")
        Log.i(TAG, "${key(name)} = $value")
    }

    suspend fun setOn(context: Context, name: String, on: Boolean) = setGlobal(context, name, if (on) "1" else "0")
}
