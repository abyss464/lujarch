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

package org.lujarch.vault.ui

import android.os.Bundle
import android.security.keystore.KeyProperties
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.vault.R
import org.lujarch.vault.data.DbSource
import org.lujarch.vault.data.Sources
import org.lujarch.vault.data.Vault
import org.lujarch.vault.data.VaultPrefs
import org.lujarch.vault.data.VaultState

/**
 * "About the vault": the facts of the last load of each source (file,
 * format, times, entries, last error; never a value) and where the Keystore
 * keeps the wrapping key. Read-only.
 */
class AboutFragment : BasePreferenceFragment() {
    private lateinit var keepassState: PreferenceCategory
    private lateinit var aegisState: PreferenceCategory
    private lateinit var keystorePref: Preference

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        keepassState = screen.add(PreferenceCategory(prefContext)) {
            key = "state_keepass"
            setTitle(R.string.state_keepass)
        }
        aegisState = screen.add(PreferenceCategory(prefContext)) {
            key = "state_aegis"
            setTitle(R.string.state_aegis)
        }
        val keystore = screen.add(PreferenceCategory(prefContext)) {
            key = "state_keystore"
            setTitle(R.string.state_keystore)
        }
        keystorePref = keystore.add(Preference(prefContext)) {
            key = "keystore"
            setTitle(R.string.state_keystore_key)
            isSelectable = false
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.settings_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        keystorePref.summary = when (Vault.keys.securityLevel()) {
            null -> getString(R.string.state_keystore_none)
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> getString(R.string.state_keystore_strongbox)
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> getString(R.string.state_keystore_tee)
            else -> getString(R.string.state_keystore_software)
        }
    }

    override fun onVaultState(state: VaultState) {
        // The facts come from the last load record, so they stay visible while the vault is locked.
        renderStatus(keepassState, Vault.prefs.status(VaultPrefs.Source.KEEPASS), keepass = true)
        renderStatus(aegisState, Vault.prefs.status(VaultPrefs.Source.AEGIS), keepass = false)
    }

    private fun sourceText(source: DbSource): String = when (source) {
        is DbSource.Synced -> getString(R.string.sources_database_synced, source.path, source.folder)
        is DbSource.Document -> Sources.providerLabel(requireContext(), source.uri)
            ?.let { getString(R.string.sources_database_document, source.fileName, it) }
            ?: getString(R.string.sources_database_document_plain, source.fileName)
    }

    private fun renderStatus(cat: PreferenceCategory, s: VaultPrefs.LoadStatus, keepass: Boolean) {
        cat.removeAll()
        val ctx = requireContext()
        fun row(title: Int, value: String) = cat.addPreference(Preference(prefContext).apply {
            setTitle(title)
            summary = value
            isSelectable = false
            isIconSpaceReserved = false
        })
        val prefs = Vault.prefs
        val setUp = if (keepass) Vault.keePassSetUp() else Vault.aegisSetUp()
        val enabled = if (keepass) prefs.keepassEnabled else prefs.aegisEnabled
        row(
            R.string.state_setup,
            getString(
                when {
                    !setUp -> R.string.state_not_set_up
                    !enabled -> R.string.state_off
                    else -> R.string.state_set_up
                },
            ),
        )
        if (keepass) {
            prefs.keepassSource?.let { row(R.string.state_source, sourceText(it)) }
        } else if (Vault.aegisFile.isFile) {
            row(R.string.state_source, prefs.aegisSourceName.ifEmpty { getString(R.string.sources_aegis_file_unnamed) })
        }
        if (s.fileSize >= 0) {
            val mtime = if (s.fileMtime > 0) Format.dateTime(ctx, s.fileMtime) else "?"
            row(R.string.state_file, getString(R.string.state_file_value, Format.bytes(ctx, s.fileSize), mtime))
        }
        if (s.format.isNotEmpty()) row(R.string.state_format, s.format)
        if (keepass && setUp) {
            row(R.string.state_key_file, getString(if (prefs.usesKeyFile) R.string.state_key_file_yes else R.string.state_key_file_no))
        }
        if (s.loadedAt > 0) {
            val load = if (s.kdfMillis >= 0) {
                getString(R.string.state_load_value_kdf, Format.dateTime(ctx, s.loadedAt), s.totalMillis, s.kdfMillis)
            } else {
                Format.dateTime(ctx, s.loadedAt)
            }
            row(R.string.state_last_load, load)
        }
        if (s.entries >= 0) row(R.string.state_entries, s.entries.toString())
        if (s.error.isNotEmpty()) {
            row(R.string.state_last_error, getString(R.string.state_error_value, Format.dateTime(ctx, s.errorAt), s.error))
        }
    }
}
