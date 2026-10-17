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

package org.lujarch.linux.terminal

import android.content.Context
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.ServiceManager
import android.os.ServiceSpecificException
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lujarch.linux.ILinux
import org.lujarch.linux.ILinuxListener

/** Log tag of the Terminal app. */
const val TAG = "lujarch_linux"

/** A call to lujarch_linux that failed; [code] is one of ILinux's ERROR_ constants, 0 when the service is not there. */
class LinuxException(val code: Int, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The Linux development environment's service and its switches the app follows. */
object Linux {
    private const val SERVICE = "lujarch_linux"

    const val ACTION_SETTINGS = "org.lujarch.linux.action.LINUX"
    const val ACTION_REMOTE = "org.lujarch.linux.action.REMOTE"
    const val EXTRA_SESSION = "org.lujarch.linux.extra.SESSION"

    fun service(): ILinux =
        ILinux.Stub.asInterface(ServiceManager.checkService(SERVICE))
            ?: throw LinuxException(0, "$SERVICE is not running")

    /** A call off the main thread; failures become [LinuxException]. */
    suspend fun <T> call(what: String, block: (ILinux) -> T): T = withContext(Dispatchers.IO) { blocking(what, block) }

    fun <T> blocking(what: String, block: (ILinux) -> T): T =
        try {
            block(service())
        } catch (e: ServiceSpecificException) {
            throw LinuxException(e.errorCode, "$what: ${e.message}", e)
        } catch (e: DeadObjectException) {
            throw LinuxException(0, "$what: $SERVICE stopped", e)
        } catch (e: RemoteException) {
            throw LinuxException(0, "$what: ${e.message}", e)
        }

    private fun global(context: Context, key: String) =
        Settings.Global.getString(context.contentResolver, "lujarch_linux_$key")

    private fun secure(context: Context, key: String) =
        Settings.Secure.getString(context.contentResolver, "lujarch_linux_$key")

    fun lent(context: Context, part: String): Boolean = global(context, "lend_$part") == "1"

    fun rootSessions(context: Context): Boolean = global(context, "root_sessions") == "1"

    /** When the terminal opens: one of ILinux's KIND_ constants. */
    fun openKind(context: Context): String =
        when (val kind = secure(context, "open_kind")) {
            ILinux.KIND_ARCH_ROOT, ILinux.KIND_ANDROID_ROOT -> kind
            else -> ILinux.KIND_ARCH
        }

    /** The terminal's colors: system, classic or own. */
    fun colors(context: Context): String = secure(context, "colors") ?: "system"

    fun keysRow(context: Context): Boolean = secure(context, "keys_row") != "0"

    /** The screen stays on while the desktop is shown (the desktop's Caffeine tile, LNX-7). */
    fun caffeine(context: Context): Boolean = secure(context, "caffeine") == "1"

    fun setCaffeine(context: Context, on: Boolean) {
        Settings.Secure.putString(context.contentResolver, "lujarch_linux_caffeine", if (on) "1" else "0")
    }
}

/**
 * What lujarch_linux tells this process, on the main thread, to whoever listens now: an activity
 * on screen registers itself.
 */
class LinuxEvents(private val sink: Sink) : ILinuxListener.Stub() {
    interface Sink {
        fun onChanged()
        fun onSessionExited(id: String, status: Int)
        fun onAuthRequest(id: Int, text: String)
        fun onNfcRequest(id: Int, command: ByteArray)
        fun onClipboard(text: String)
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onChanged() {
        main.post { sink.onChanged() }
    }

    override fun onSessionExited(id: String, status: Int) {
        main.post { sink.onSessionExited(id, status) }
    }

    override fun onAuthRequest(id: Int, text: String) {
        main.post { sink.onAuthRequest(id, text) }
    }

    override fun onNfcRequest(id: Int, command: ByteArray) {
        main.post { sink.onNfcRequest(id, command) }
    }

    override fun onClipboard(text: String) {
        main.post { sink.onClipboard(text) }
    }

    // LinuxParts hears these with a listener of its own.
    override fun onAndroidSet(key: String, value: String) {}

    override fun onVibrate(pattern: String) {}

    override fun onStream(stream: String, wanted: Boolean) {}

    fun register() {
        try {
            Linux.blocking("registerListener") { it.registerListener(this) }
        } catch (e: LinuxException) {
            Log.w(TAG, e.message ?: "registerListener")
        }
    }

    fun unregister() {
        try {
            Linux.blocking("unregisterListener") { it.unregisterListener(this) }
        } catch (e: LinuxException) {
            Log.w(TAG, e.message ?: "unregisterListener")
        }
    }
}
