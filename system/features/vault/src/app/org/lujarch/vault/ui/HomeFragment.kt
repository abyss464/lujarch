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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.TopIntroPreference
import org.lujarch.vault.R
import org.lujarch.vault.core.otp.Otp
import org.lujarch.vault.data.Source
import org.lujarch.vault.data.SourceUnavailableException
import org.lujarch.vault.data.Vault
import org.lujarch.vault.data.VaultState
import org.lujarch.vault.data.active

/** Landing page: state, 2FA codes, passwords, sources, settings. */
class HomeFragment : BasePreferenceFragment() {
    private lateinit var intro: TopIntroPreference
    private lateinit var statusPref: Preference
    private lateinit var codesPref: Preference
    private lateinit var passwordsPref: Preference
    private lateinit var sourcesPref: Preference

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        intro = screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.home_intro_not_set_up)
            isVisible = false
        }
        val vault = screen.add(PreferenceCategory(prefContext)) { key = "vault" }
        statusPref = vault.add(Preference(prefContext)) {
            key = "status"
            setTitle(R.string.home_status)
            setIcon(R.drawable.ic_lock)
            isSelectable = false
        }
        codesPref = vault.add(Preference(prefContext)) {
            key = "codes"
            setTitle(R.string.codes_title)
            setIcon(R.drawable.ic_codes)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), CodesFragment::class.java, R.string.codes_title)
                true
            }
        }
        passwordsPref = vault.add(Preference(prefContext)) {
            key = "passwords"
            setTitle(R.string.passwords_title)
            setIcon(R.drawable.ic_key)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), PasswordsFragment::class.java, R.string.passwords_title)
                true
            }
        }
        val manage = screen.add(PreferenceCategory(prefContext)) { key = "manage" }
        sourcesPref = manage.add(Preference(prefContext)) {
            key = "sources"
            setTitle(R.string.sources_title)
            setIcon(R.drawable.ic_setup)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), SourcesFragment::class.java, R.string.sources_title)
                true
            }
        }
        manage.add(Preference(prefContext)) {
            key = "settings"
            setTitle(R.string.settings_title)
            setSummary(R.string.settings_summary)
            setIcon(R.drawable.ic_settings)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), SettingsFragment::class.java, R.string.settings_title)
                true
            }
        }
    }

    override fun onVaultState(state: VaultState) {
        val ctx = requireContext()
        intro.isVisible = !state.anySetUp
        statusPref.summary = statusText(state)

        codesPref.isEnabled = state.anyActive
        val aegisCount = state.aegisData?.entries?.size
        val keepassOtp = state.keepassData?.let { d -> d.db.entries.count { Otp.hasOtp(it) } }
        codesPref.summary = when {
            aegisCount != null || keepassOtp != null -> {
                val n = (aegisCount ?: 0) + (keepassOtp ?: 0)
                resources.getQuantityString(R.plurals.codes_count, n, n)
            }
            else -> sourceSummary(state.aegis.takeIf { it.active } ?: state.keepass)
        }

        passwordsPref.isEnabled = state.keepass.active
        passwordsPref.summary = state.keepassData?.let {
            resources.getQuantityString(R.plurals.passwords_count, it.db.entries.size, it.db.entries.size)
        } ?: sourceSummary(state.keepass)

        sourcesPref.summary = getString(R.string.sources_summary, stateWord(state.keepass), stateWord(state.aegis))

        val keepassFailure = (state.keepass as? Source.Failed)?.error
        val aegisFailure = (state.aegis as? Source.Failed)?.error
        when {
            keepassFailure is SourceUnavailableException &&
                keepassFailure.reason != SourceUnavailableException.Reason.TOO_LARGE ->
                // The chosen file went away or its grant was revoked: one tap to choose it again.
                showBanner(getString(R.string.error_load_keepass), Errors.describe(ctx, keepassFailure), R.string.action_rechoose) {
                    Pages.open(
                        requireContext(), SourcesFragment::class.java, R.string.sources_title,
                        arguments = Bundle().apply { putBoolean(SourcesFragment.ARG_RECHOOSE, true) },
                    )
                }
            keepassFailure != null -> showBanner(getString(R.string.error_load_keepass), Errors.describe(ctx, keepassFailure))
            aegisFailure != null -> showBanner(getString(R.string.error_load_aegis), Errors.describe(ctx, aegisFailure))
            else -> showBanner(null, null)
        }
    }

    private fun stateWord(source: Source<*>): String = getString(
        when (source) {
            is Source.NotSetUp -> R.string.state_not_set_up
            is Source.Off -> R.string.state_off
            else -> R.string.state_set_up
        },
    )

    private fun statusText(state: VaultState): String = when {
        !state.anySetUp -> getString(R.string.status_not_set_up)
        !state.anyActive -> getString(R.string.status_off)
        Vault.deviceLocked -> getString(R.string.status_locked)
        state.loading -> getString(R.string.status_unlocking)
        state.reloading -> getString(R.string.status_reloading)
        state.keepassData != null || state.aegisData != null -> {
            val at = maxOf(state.keepassData?.loadedAt ?: 0, state.aegisData?.loadedAt ?: 0)
            getString(R.string.status_unlocked, Format.time(requireContext(), at))
        }
        state.keepass is Source.Failed || state.aegis is Source.Failed -> getString(R.string.status_failed)
        else -> getString(R.string.status_idle)
    }

    private fun sourceSummary(source: Source<*>): String = when (source) {
        is Source.NotSetUp -> getString(R.string.status_not_set_up)
        is Source.Off -> getString(R.string.status_off)
        is Source.Loading -> getString(R.string.status_unlocking)
        is Source.Failed -> getString(R.string.status_failed)
        is Source.Idle -> getString(if (Vault.deviceLocked) R.string.status_locked else R.string.status_idle)
        is Source.Ready<*> -> ""
    }
}
