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

package org.lujarch.vault.system

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.data.Vault
import org.lujarch.vault.ui.CodesFragment
import org.lujarch.vault.ui.Pages
import org.lujarch.vault.ui.VaultActivity

/**
 * Quick Settings tile "2FA codes": opens the codes page (or the vault's home
 * while nothing is set up). On the lock screen it asks for the unlock first;
 * the page itself decrypts only while the phone is unlocked, as every vault
 * page does (the Keystore refuses otherwise), and keeps FLAG_SECURE. A long
 * press opens the vault's home (the manifest's QS_TILE_PREFERENCES alias,
 * which SystemUI resolves in this package).
 */
class CodesTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.subtitle = when {
            Vault.keePassActive() || Vault.aegisActive() -> null
            Vault.keePassSetUp() || Vault.aegisSetUp() -> getString(R.string.status_off)
            else -> getString(R.string.status_not_set_up)
        }
        tile.updateTile()
    }

    override fun onClick() {
        val target = if (Vault.keePassActive() || Vault.aegisActive()) {
            Pages.intent(this, CodesFragment::class.java, R.string.codes_title)
        } else {
            Intent(this, VaultActivity::class.java)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            this, 0, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun open() {
            try {
                startActivityAndCollapse(pending)
            } catch (e: RuntimeException) {
                Log.e(TAG, "the codes tile cannot open its page", e)
            }
        }
        if (isLocked) unlockAndRun { open() } else open()
    }
}
