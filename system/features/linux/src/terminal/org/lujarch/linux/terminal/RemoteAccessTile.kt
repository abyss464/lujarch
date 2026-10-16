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

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lujarch.linux.ILinux
import org.lujarch.linux.RemoteStatus

/**
 * Quick Settings > Remote access (LNX-9): shows when it runs and switches it between Always and
 * While charging; a long press opens its page in Settings (ModesActivity).
 */
class RemoteAccessTile : TileService() {
    private var scope: CoroutineScope? = null
    private var remote: RemoteStatus? = null
    private var busy = false

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch {
                while (true) {
                    update()
                    delay(UPDATE_MS)
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isSecure && isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        val current = remote ?: return
        if (busy) return
        busy = true
        val next = if (current.mode == ILinux.REMOTE_ALWAYS) ILinux.REMOTE_CHARGING else ILinux.REMOTE_ALWAYS
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch {
            val written = withContext(Dispatchers.IO) {
                Settings.Global.putString(contentResolver, "lujarch_linux_remote", next)
            }
            if (!written) Log.e(TAG, "Remote access tile: the mode was not written")
            busy = false
            update()
        }
    }

    private suspend fun update() {
        remote = try {
            Linux.call("getRemote") { it.remote }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinuxException) {
            Log.w(TAG, "Remote access tile: ${e.message}")
            null
        }
        val tile = qsTile ?: return
        val r = remote
        tile.label = getString(R.string.remote_access)
        when {
            r == null -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = null
            }
            r.mode == ILinux.REMOTE_OFF -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.remote_off)
            }
            else -> {
                tile.state = Tile.STATE_ACTIVE
                val mode = getString(if (r.mode == ILinux.REMOTE_ALWAYS) R.string.remote_always else R.string.remote_charging)
                tile.subtitle = when (r.state) {
                    ILinux.RELAY_RUNNING -> getString(R.string.remote_running, mode)
                    ILinux.RELAY_WAITING -> getString(R.string.remote_waiting, mode)
                    ILinux.RELAY_FAILED -> getString(R.string.remote_failed, mode)
                    else -> mode
                }
            }
        }
        tile.updateTile()
    }

    private companion object {
        const val UPDATE_MS = 3000L
    }
}
