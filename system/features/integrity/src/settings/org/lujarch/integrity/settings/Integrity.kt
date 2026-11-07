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

package org.lujarch.integrity.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.internal.app.LujarchIntegrity
import com.android.internal.app.LujarchIntegrityStatus
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.TopIntroPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.PageRequest
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.UserMessageException

private const val ACTION_INTEGRITY = "org.lujarch.integrity.action.INTEGRITY"
private const val KEY_ENABLED = "lujarch_integrity_enabled"
private const val SLOT_STATUS = "status"
private const val MAX_KEYBOX_BYTES = 256 * 1024

class IntegrityActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getString(R.string.integrity_title)
    override fun createFragment(): Fragment = IntegrityFragment()
}

class IntegrityFragment : BasePreferenceFragment() {
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var statusPref: Preference
    private lateinit var importPref: Preference
    private lateinit var removePref: Preference

    private val keyboxFor = PageRequest(this, "integrity-keybox")
    private val pickKeybox = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (keyboxFor.take() == null || uri == null) return@registerForActivityResult
        importKeybox(uri)
    }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val resolver = requireContext().contentResolver

        mainSwitch = screen.add(MainSwitchPreference(prefContext)) {
            key = KEY_ENABLED
            setTitle(R.string.integrity_use)
            isPersistent = false
            isChecked = Settings.Global.getInt(resolver, KEY_ENABLED, 1) != 0
            setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                Settings.Global.putInt(resolver, KEY_ENABLED, if (enabled) 1 else 0)
                true
            }
        }

        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.integrity_intro)
        }

        val keyboxCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "keybox"
            setTitle(R.string.integrity_keybox_category)
        }

        statusPref = keyboxCategory.add(Preference(prefContext)) {
            key = "keybox_status"
            setTitle(R.string.integrity_keybox_status)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }

        importPref = keyboxCategory.add(Preference(prefContext)) {
            key = "import_keybox"
            setTitle(R.string.integrity_import_keybox)
            setSummary(R.string.integrity_import_keybox_summary)
            setOnPreferenceClickListener {
                keyboxFor.start("keybox")
                pickKeybox.launch(arrayOf("*/*"))
                true
            }
        }

        removePref = keyboxCategory.add(Preference(prefContext)) {
            key = "remove_keybox"
            setTitle(R.string.integrity_remove_keybox)
            isVisible = false
            setOnPreferenceClickListener {
                confirmRemoveKeybox()
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val resolver = requireContext().contentResolver
        mainSwitch.isChecked = Settings.Global.getInt(resolver, KEY_ENABLED, 1) != 0
        refresh()
    }

    override fun refresh() {
        launchUi {
            val status = attempt(SLOT_STATUS, R.string.integrity_error_load) {
                withContext(Dispatchers.IO) { LujarchIntegrity.getStatus() }
            } ?: return@launchUi
            renderStatus(status)
        }
    }

    private fun renderStatus(status: LujarchIntegrityStatus) {
        if (!status.hasKeybox) {
            statusPref.setSummary(R.string.integrity_keybox_not_imported)
            removePref.isVisible = false
            return
        }
        val algos = ArrayList<String>(2)
        if (status.hasEcKey) algos.add("EC")
        if (status.hasRsaKey) algos.add("RSA")
        val sep = getString(PagesR.string.list_separator)
        statusPref.summary = getString(
            R.string.integrity_keybox_imported,
            algos.joinToString(sep),
            status.certificateCount,
        )
        removePref.isVisible = true
    }

    private fun importKeybox(uri: Uri) {
        val appContext = requireContext().applicationContext
        launchUi {
            val ok = act(R.string.integrity_error_import) {
                val invalidMessage = getString(R.string.integrity_error_invalid_keybox)
                withContext(Dispatchers.IO) {
                    val xml = appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readNBytes(MAX_KEYBOX_BYTES + 1)
                        if (bytes.isEmpty() || bytes.size > MAX_KEYBOX_BYTES) {
                            bytes.fill(0)
                            throw UserMessageException(invalidMessage)
                        }
                        try {
                            String(bytes, Charsets.UTF_8)
                        } finally {
                            bytes.fill(0)
                        }
                    } ?: throw UserMessageException(invalidMessage)
                    if (!LujarchIntegrity.setKeybox(xml)) {
                        throw UserMessageException(invalidMessage)
                    }
                }
            }
            if (ok) refresh()
        }
    }

    private fun confirmRemoveKeybox() {
        confirm(
            key = "remove-keybox",
            title = R.string.integrity_remove_keybox,
            message = { getString(R.string.integrity_remove_keybox_message) },
            positive = R.string.integrity_remove_action,
        ) {
            launchUi {
                val ok = act(R.string.integrity_error_remove) {
                    withContext(Dispatchers.IO) { LujarchIntegrity.clearKeybox() }
                }
                if (ok) refresh()
            }
        }
    }
}

class IntegrityContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = context.getString(R.string.integrity_search_screen)
        val keywords = context.getString(R.string.integrity_search_keywords)
        return listOf(
            SearchEntry(
                key = "lujarch_integrity",
                title = context.getString(R.string.integrity_title),
                summary = context.getString(R.string.integrity_intro),
                keywords = keywords,
                screenTitle = screen,
                action = ACTION_INTEGRITY,
            ),
            SearchEntry(
                key = KEY_ENABLED,
                title = context.getString(R.string.integrity_use),
                summary = "",
                keywords = keywords,
                screenTitle = screen,
                action = ACTION_INTEGRITY,
            ),
            SearchEntry(
                key = "import_keybox",
                title = context.getString(R.string.integrity_import_keybox),
                summary = context.getString(R.string.integrity_import_keybox_summary),
                keywords = keywords,
                screenTitle = screen,
                action = ACTION_INTEGRITY,
            ),
        )
    }
}
