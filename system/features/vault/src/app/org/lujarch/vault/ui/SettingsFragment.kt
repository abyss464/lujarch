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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.data.Clipboard
import org.lujarch.vault.data.Vault
import org.lujarch.vault.data.VaultPrefs
import org.lujarch.vault.data.VaultState

/**
 * Everyday options first (verification, clipboard, forgetting keys,
 * autofill and the system's own pages for the preferred service), the
 * diagnostics in "About the vault", and the destructive actions in their own
 * group at the bottom, each behind a confirmation.
 */
class SettingsFragment : BasePreferenceFragment() {
    private lateinit var clipboardPref: ListPreference
    private lateinit var forgetPolicyPref: ListPreference
    private lateinit var autofillPref: Preference
    private lateinit var matchPref: ListPreference
    private lateinit var excludedPref: Preference
    private lateinit var keyFilePref: Preference
    private lateinit var forgetPref: Preference

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val prefs = Vault.prefs

        val security = screen.add(PreferenceCategory(prefContext)) {
            key = "security"
            setTitle(R.string.settings_security)
        }
        security.add(SwitchPreferenceCompat(prefContext)) {
            key = "reveal_needs_auth"
            setTitle(R.string.settings_reveal_auth)
            setSummary(R.string.settings_reveal_auth_summary)
            isPersistent = false
            isChecked = prefs.revealNeedsAuth
            setOnPreferenceChangeListener { _, v ->
                prefs.revealNeedsAuth = v as Boolean
                true
            }
        }
        security.add(SwitchPreferenceCompat(prefContext)) {
            key = "copy_needs_auth"
            setTitle(R.string.settings_copy_auth)
            setSummary(R.string.settings_copy_auth_summary)
            isPersistent = false
            isChecked = prefs.copyNeedsAuth
            setOnPreferenceChangeListener { _, v ->
                prefs.copyNeedsAuth = v as Boolean
                true
            }
        }
        clipboardPref = security.add(ListPreference(prefContext)) {
            key = "clear_clipboard"
            setTitle(R.string.settings_clear_clipboard)
            setDialogTitle(R.string.settings_clear_clipboard)
            isPersistent = false
            entries = CLIPBOARD_LABELS.map { getString(it) }.toTypedArray()
            entryValues = Clipboard.DELAYS_S.map { it.toString() }.toTypedArray()
            value = prefs.clipboardClearSeconds.toString().takeIf { it in entryValues.map(CharSequence::toString) }
                ?: DEFAULT_CLIPBOARD_S.toString()
            setOnPreferenceChangeListener { _, v ->
                prefs.clipboardClearSeconds = (v as String).toInt()
                renderClipboard(v)
                true
            }
        }
        forgetPolicyPref = security.add(ListPreference(prefContext)) {
            key = "forget_policy"
            setTitle(R.string.settings_forget_policy)
            setDialogTitle(R.string.settings_forget_policy)
            isPersistent = false
            entries = FORGET_LABELS.map { getString(it.second) }.toTypedArray()
            entryValues = FORGET_LABELS.map { it.first.key }.toTypedArray()
            value = prefs.forgetPolicy.key
            setOnPreferenceChangeListener { _, v ->
                Vault.setForgetPolicy(VaultPrefs.ForgetPolicy.fromKey(v as String))
                renderForgetPolicy(v)
                true
            }
        }

        val autofill = screen.add(PreferenceCategory(prefContext)) {
            key = "autofill"
            setTitle(R.string.settings_autofill_category)
        }
        autofillPref = autofill.add(Preference(prefContext)) {
            key = "autofill_service"
            setTitle(R.string.settings_autofill)
            setOnPreferenceClickListener {
                // The system's own picker: the user chooses the service there.
                open(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(packageUri()))
                true
            }
        }
        autofill.add(Preference(prefContext)) {
            key = "credential_providers"
            setTitle(R.string.settings_credential_providers)
            setSummary(R.string.settings_credential_providers_summary)
            setOnPreferenceClickListener {
                // The native page for the preferred service and the other providers (not duplicated here).
                open(Intent(ACTION_CREDENTIAL_PROVIDER).setData(packageUri()))
                true
            }
        }
        autofill.add(SwitchPreferenceCompat(prefContext)) {
            key = "autofill_search_item"
            setTitle(R.string.settings_autofill_search)
            setSummary(R.string.settings_autofill_search_summary)
            isPersistent = false
            isChecked = prefs.autofillSearchItem
            setOnPreferenceChangeListener { _, v ->
                prefs.autofillSearchItem = v as Boolean
                true
            }
        }
        matchPref = autofill.add(ListPreference(prefContext)) {
            key = "autofill_match"
            setTitle(R.string.settings_autofill_match)
            setDialogTitle(R.string.settings_autofill_match)
            isPersistent = false
            entries = arrayOf(getString(R.string.match_subdomains), getString(R.string.match_host))
            entryValues = arrayOf(MATCH_SUBDOMAINS, MATCH_HOST)
            value = if (prefs.autofillSubdomains) MATCH_SUBDOMAINS else MATCH_HOST
            setOnPreferenceChangeListener { _, v ->
                prefs.autofillSubdomains = v == MATCH_SUBDOMAINS
                renderMatch(v as String)
                true
            }
        }
        excludedPref = autofill.add(Preference(prefContext)) {
            key = "autofill_excluded"
            setTitle(R.string.settings_excluded)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), ExclusionsFragment::class.java, R.string.settings_excluded)
                true
            }
        }

        val about = screen.add(PreferenceCategory(prefContext)) { key = "about_group" }
        about.add(Preference(prefContext)) {
            key = "about"
            setTitle(R.string.settings_about)
            setSummary(R.string.settings_about_summary)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), AboutFragment::class.java, R.string.settings_about)
                true
            }
        }

        val reset = screen.add(PreferenceCategory(prefContext)) {
            key = "reset"
            setTitle(R.string.settings_reset)
        }
        keyFilePref = reset.add(Preference(prefContext)) {
            key = "delete_keyfile"
            setTitle(R.string.settings_delete_keyfile)
            setOnPreferenceClickListener {
                confirmDeleteKeyFile()
                true
            }
        }
        forgetPref = reset.add(Preference(prefContext)) {
            key = "forget"
            setTitle(R.string.settings_forget)
            setSummary(R.string.settings_forget_summary)
            setOnPreferenceClickListener {
                confirmForget()
                true
            }
        }

        renderClipboard(clipboardPref.value)
        renderForgetPolicy(forgetPolicyPref.value)
        renderMatch(matchPref.value)
    }

    private fun confirmDeleteKeyFile() = confirm(
        "delete-key-file", R.string.settings_delete_keyfile, { getString(R.string.settings_delete_keyfile_confirm) },
        R.string.settings_delete_keyfile_action,
    ) {
        Vault.deleteImportedKeyFile("on request")
        renderKeyFile()
    }

    private fun confirmForget() = confirm(
        "forget-keys", R.string.settings_forget, { getString(R.string.settings_forget_confirm) }, R.string.settings_forget_action,
    ) { Vault.forgetKeys() }

    private fun packageUri(): Uri = Uri.parse("package:" + requireContext().packageName)

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "no activity for ${intent.action}", e)
            Toast.makeText(requireContext(), R.string.error_no_settings, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderClipboard(value: String) {
        val i = Clipboard.DELAYS_S.indexOf(value.toIntOrNull() ?: DEFAULT_CLIPBOARD_S).coerceAtLeast(0)
        clipboardPref.summary = getString(R.string.settings_clear_clipboard_summary, getString(CLIPBOARD_LABELS[i]))
    }

    private fun renderForgetPolicy(value: String) {
        val policy = VaultPrefs.ForgetPolicy.fromKey(value)
        forgetPolicyPref.summary = if (policy == VaultPrefs.ForgetPolicy.NEVER) {
            getString(R.string.settings_forget_policy_never_summary)
        } else {
            getString(R.string.settings_forget_policy_summary, getString(FORGET_LABELS.first { it.first == policy }.second))
        }
    }

    private fun renderMatch(value: String) {
        matchPref.setSummary(if (value == MATCH_SUBDOMAINS) R.string.match_subdomains_summary else R.string.match_host_summary)
    }

    private fun renderKeyFile() {
        val f = Vault.importedKeyFile
        // Only there while a key file copied from a computer waits on the phone.
        keyFilePref.isVisible = f.isFile
        if (f.isFile) keyFilePref.summary = getString(R.string.settings_delete_keyfile_summary, Format.bytes(requireContext(), f.length()))
    }

    override fun onResume() {
        super.onResume()
        renderKeyFile()
        val afm = requireContext().getSystemService(AutofillManager::class.java)
        autofillPref.setSummary(
            if (afm?.hasEnabledAutofillServices() == true) R.string.settings_autofill_on else R.string.settings_autofill_off,
        )
        val excluded = Vault.prefs.autofillExcludedPackages.size + Vault.prefs.autofillExcludedSites.size
        excludedPref.summary = if (excluded == 0) {
            getString(R.string.settings_excluded_none)
        } else {
            resources.getQuantityString(R.plurals.settings_excluded_count, excluded, excluded)
        }
    }

    override fun onVaultState(state: VaultState) {
        forgetPref.isEnabled = state.anySetUp
    }

    companion object {
        private const val DEFAULT_CLIPBOARD_S = 30

        /** Settings.ACTION_CREDENTIAL_PROVIDER, a flagged API; Settings' Passwords, passkeys & accounts page. */
        private const val ACTION_CREDENTIAL_PROVIDER = "android.settings.CREDENTIAL_PROVIDER"
        private const val MATCH_SUBDOMAINS = "subdomains"
        private const val MATCH_HOST = "host"

        /** Labels of Clipboard.DELAYS_S, in the same order. */
        private val CLIPBOARD_LABELS = intArrayOf(
            R.string.clipboard_never, R.string.clipboard_after_15s, R.string.clipboard_after_30s,
            R.string.clipboard_after_1m, R.string.clipboard_after_2m, R.string.clipboard_after_5m,
        )

        private val FORGET_LABELS = listOf(
            VaultPrefs.ForgetPolicy.NEVER to R.string.forget_never,
            VaultPrefs.ForgetPolicy.RESTART to R.string.forget_restart,
            VaultPrefs.ForgetPolicy.IDLE_1D to R.string.forget_idle_1d,
            VaultPrefs.ForgetPolicy.IDLE_7D to R.string.forget_idle_7d,
            VaultPrefs.ForgetPolicy.IDLE_30D to R.string.forget_idle_30d,
        )
    }
}
