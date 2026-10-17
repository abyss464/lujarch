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
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.ActionButtonsPreference
import com.android.settingslib.widget.ZeroStatePreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.core.PasswordEntry
import org.lujarch.vault.core.PasswordField
import org.lujarch.vault.core.otp.Otp
import org.lujarch.vault.core.otp.OtpParams
import org.lujarch.vault.data.Clipboard
import org.lujarch.vault.data.PasswordData
import org.lujarch.vault.data.VaultState

/**
 * One password entry: copy user name, password, URL; reveal the password and
 * protected fields (fingerprint or screen lock first, a setting); notes and
 * every other field. Revealed values are hidden again when the page stops.
 */
class EntryFragment : BasePreferenceFragment() {
    private lateinit var actions: ActionButtonsPreference
    private lateinit var main: PreferenceCategory
    private lateinit var other: PreferenceCategory
    private lateinit var details: PreferenceCategory
    private lateinit var messagePref: ZeroStatePreference
    private var shown: PasswordData? = null
    private var entry: PasswordEntry? = null
    private var otpParams: OtpParams? = null
    private var codePref: CodePreference? = null
    private val secretPrefs = ArrayList<FieldPreference>()

    private val uuid: String
        get() = requireArguments().getString(ARG_UUID, "")

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        messagePref = screen.add(ZeroStatePreference(prefContext)) {
            key = "message"
            setIcon(R.drawable.ic_key)
            isVisible = false
        }
        actions = screen.add(ActionButtonsPreference(prefContext)) {
            key = "actions"
            setButton1Text(R.string.action_copy_username)
            setButton1Icon(R.drawable.ic_person)
            setButton1OnClickListener { entry?.let { copyField(it.get(PasswordEntry.USERNAME), R.string.copied_username) } }
            setButton2Text(R.string.action_copy_password)
            setButton2Icon(R.drawable.ic_key)
            setButton2OnClickListener { entry?.let { copyField(it.password, R.string.copied_password) } }
            setButton3Text(R.string.action_open_url)
            setButton3Icon(R.drawable.ic_open)
            setButton3OnClickListener { entry?.let { openUrl(it.url) } }
            setButton4Visible(false)
        }
        main = screen.add(PreferenceCategory(prefContext)) { key = "main" }
        other = screen.add(PreferenceCategory(prefContext)) {
            key = "other"
            setTitle(R.string.entry_other_fields)
        }
        details = screen.add(PreferenceCategory(prefContext)) {
            key = "details"
            setTitle(R.string.entry_details)
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val now = System.currentTimeMillis()
                    codePref?.tick(now)
                    delay(1000 - now % 1000)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        secretPrefs.forEach { it.setRevealed(false) }
    }

    override fun onDestroy() {
        otpParams?.wipe()
        otpParams = null
        super.onDestroy()
    }

    override fun onVaultState(state: VaultState) {
        val data = state.passwordsData
        if (data === shown) return
        shown = data
        val e = data?.db?.entries?.firstOrNull { it.uuid == uuid }
        entry = e
        render(e)
    }

    private fun render(e: PasswordEntry?) {
        main.removeAll()
        other.removeAll()
        details.removeAll()
        secretPrefs.clear()
        codePref = null
        otpParams?.wipe()
        otpParams = null
        val visible = e != null
        messagePref.isVisible = !visible
        actions.isVisible = visible
        main.isVisible = visible
        details.isVisible = visible
        if (e == null) {
            messagePref.setTitle(if (shown == null) R.string.status_locked else R.string.entry_missing)
            other.isVisible = false
            return
        }
        actions.setButton1Enabled(e.username.isNotEmpty())
        actions.setButton2Enabled(e.password?.isEmpty == false)
        actions.setButton3Enabled(e.url.isNotBlank())

        addField(main, R.string.field_username, e.get(PasswordEntry.USERNAME), R.string.copied_username)
        addField(main, R.string.field_password, e.password, R.string.copied_password, forceMasked = true)
        addField(main, R.string.field_url, e.get(PasswordEntry.URL), R.string.copied_url)
        if (Otp.hasOtp(e)) {
            otpParams = try {
                Otp.fromEntry(e)
            } catch (ex: Exception) {
                Log.w(TAG, "OTP settings of an entry cannot be read: ${ex.message}")
                null
            }
            codePref = main.add(CodePreference(prefContext, otpParams, getString(R.string.field_otp))) {
                key = "otp"
                setOnPreferenceClickListener {
                    currentCode?.let {
                        Clipboard.copy(requireContext(), getString(R.string.clip_label_code), it)
                        Toast.makeText(requireContext(), R.string.copied_code, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }
            codePref?.tick(System.currentTimeMillis())
        }
        addField(main, R.string.field_notes, e.get(PasswordEntry.NOTES), R.string.copied_notes)

        val custom = e.customFields
        for (f in custom) addField(other, 0, f, R.string.copied_field, title = f.name)
        other.isVisible = custom.isNotEmpty()

        if (e.path.isNotEmpty()) addInfo(R.string.entry_group, e.path.joinToString(" / "))
        if (e.tags.isNotBlank()) addInfo(R.string.entry_tags, e.tags)
        if (e.expires) addInfo(R.string.entry_expires, Format.dateTime(requireContext(), e.expiryMillis))
        details.isVisible = details.preferenceCount > 0
    }

    private fun addField(
        category: PreferenceCategory,
        titleRes: Int,
        field: PasswordField?,
        copiedRes: Int,
        forceMasked: Boolean = false,
        title: String? = null,
    ) {
        if (field == null && !forceMasked) return
        if (field != null && field.isEmpty && !forceMasked && titleRes == R.string.field_notes) return
        val masked = forceMasked || field?.isProtected == true
        val pref = FieldPreference(prefContext, masked, if (masked) ::onRevealClicked else null)
        pref.key = "field:" + (title ?: getString(titleRes))
        pref.title = title ?: getString(titleRes)
        pref.bindField(field)
        pref.setOnPreferenceClickListener {
            copyField(field, copiedRes)
            true
        }
        category.addPreference(pref)
        if (masked) secretPrefs.add(pref)
    }

    private fun addInfo(titleRes: Int, value: String) {
        details.addPreference(Preference(prefContext).apply {
            setTitle(titleRes)
            summary = value
            isSelectable = false
            isIconSpaceReserved = false
        })
    }

    private fun onRevealClicked(pref: FieldPreference) {
        if (pref.revealed) {
            pref.setRevealed(false)
            return
        }
        RevealAuth.run(this) { pref.setRevealed(true) }
    }

    /** Protected fields (the password among them) ask first when "copying needs verification" is on. */
    private fun copyField(field: PasswordField?, copiedRes: Int) {
        if (field == null || field.isEmpty) return
        val copy = {
            Clipboard.copy(requireContext(), field.name, field.value())
            Toast.makeText(requireContext(), copiedRes, Toast.LENGTH_SHORT).show()
        }
        if (field.isProtected) RevealAuth.runForCopy(this) { copy() } else copy()
    }

    private fun openUrl(url: String) {
        val u = url.trim()
        if (u.isEmpty() || u.startsWith("androidapp://", ignoreCase = true)) return
        val uri = Uri.parse(if (u.contains("://")) u else "https://$u")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.error_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ARG_UUID = "uuid"
    }
}
