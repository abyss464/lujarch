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

package org.lujarch.vault.autofill

import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.autofill.AutofillManager
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.SearchView
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.ZeroStatePreference
import java.text.Collator
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.core.PasswordEntry
import org.lujarch.vault.data.PasswordData
import org.lujarch.vault.data.Source
import org.lujarch.vault.data.Vault
import org.lujarch.vault.data.VaultState
import org.lujarch.vault.ui.BaseActivity
import org.lujarch.vault.ui.BasePreferenceFragment
import org.lujarch.vault.ui.Errors

/**
 * Opened by the autofill framework through our authentication intents:
 * MODE_RESPONSE waits for the vault to load and returns the fill response;
 * MODE_PICK lists every entry (with search) and returns the chosen one's
 * dataset. Not exported: only our own pending intents start it.
 */
class AutofillActivity : BaseActivity() {
    override fun pageTitle(): CharSequence =
        getText(if (intent.getStringExtra(EXTRA_MODE) == MODE_PICK) R.string.autofill_pick_title else R.string.app_name)

    override fun createFragment(): Fragment = AutofillFragment()

    companion object {
        const val EXTRA_MODE = "org.lujarch.vault.extra.AUTOFILL_MODE"
        const val MODE_PICK = "pick"
        const val MODE_RESPONSE = "response"
    }
}

class AutofillFragment : BasePreferenceFragment(), MenuProvider {
    private lateinit var messagePref: ZeroStatePreference
    private lateinit var entriesCategory: PreferenceCategory
    private var form: ParsedForm? = null
    private var inline: InlineSuggestionsRequest? = null
    private var shown: PasswordData? = null
    private var query = ""
    private var done = false

    private val pick: Boolean
        get() = requireActivity().intent.getStringExtra(AutofillActivity.EXTRA_MODE) == AutofillActivity.MODE_PICK

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val intent = requireActivity().intent
        val structure = intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        inline = intent.getParcelableExtra(AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST, InlineSuggestionsRequest::class.java)
        form = structure?.let { FormParser.parse(it) }?.takeIf { !it.isEmpty && !VaultAutofillService.isOwnPackage(it.packageName) }
        messagePref = screen.add(ZeroStatePreference(prefContext)) {
            key = "message"
            setIcon(R.drawable.ic_key)
            setTitle(R.string.status_unlocking)
        }
        entriesCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "entries"
            setTitle(R.string.passwords_entries)
            isVisible = false
        }
        if (form == null) {
            Log.w(TAG, "autofill activity started without a usable form")
            finish(null)
        }
    }

    override fun onVaultState(state: VaultState) {
        val f = form ?: return
        if (done) return
        val data = state.passwordsData
        if (data == null) {
            shown = null
            entriesCategory.removeAll()
            messagePref.isVisible = true
            entriesCategory.isVisible = false
            when (val k = state.passwords) {
                is Source.Failed -> {
                    messagePref.setTitle(R.string.status_failed)
                    showBanner(getString(R.string.error_load_passwords), Errors.describe(requireContext(), k.error))
                }
                is Source.NotSetUp -> messagePref.setTitle(R.string.status_not_set_up)
                is Source.Off -> messagePref.setTitle(R.string.status_off)
                else -> messagePref.setTitle(R.string.status_unlocking)
            }
            return
        }
        showBanner(null, null)
        if (!pick) {
            finish(FillBuilder.response(requireContext(), f, data, inline, withPicker = Vault.prefs.autofillSearchItem))
            return
        }
        if (data !== shown) {
            shown = data
            render()
        }
    }

    private fun render() {
        val data = shown ?: return
        entriesCategory.removeAll()
        val needle = query.trim().lowercase()
        val collator = Collator.getInstance()
        val list = data.db.entries
            .filter { e ->
                needle.isEmpty() || e.title.lowercase().contains(needle) || e.username.lowercase().contains(needle) ||
                    e.url.lowercase().contains(needle)
            }
            .sortedWith { a, b -> collator.compare(a.title, b.title) }
        for (e in list) {
            entriesCategory.addPreference(Preference(prefContext).apply {
                key = "entry:${e.uuid}"
                title = e.title.ifEmpty { getString(R.string.entry_untitled) }
                summary = listOf(e.username, e.path.joinToString(" / ")).filter { it.isNotEmpty() }.joinToString(" · ")
                setIcon(R.drawable.ic_key)
                setOnPreferenceClickListener {
                    choose(e)
                    true
                }
            })
        }
        entriesCategory.isVisible = list.isNotEmpty()
        messagePref.isVisible = list.isEmpty()
        if (list.isEmpty()) messagePref.setTitle(R.string.passwords_no_match)
    }

    private fun choose(entry: PasswordEntry) {
        val f = form ?: return
        val dataset = FillBuilder.entryDataset(requireContext(), f, entry, null)
        if (dataset == null) {
            Toast.makeText(requireContext(), R.string.autofill_nothing_to_fill, Toast.LENGTH_SHORT).show()
            return
        }
        finish(dataset)
    }

    /** Returns [result] (a FillResponse or Dataset) to the autofill framework; null cancels. */
    private fun finish(result: android.os.Parcelable?) {
        done = true
        val activity = requireActivity()
        if (result == null) {
            activity.setResult(Activity.RESULT_CANCELED)
        } else {
            activity.setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result))
        }
        activity.finish()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        if (!pick) return
        val item = menu.add(Menu.NONE, 1, 0, R.string.menu_search).setIcon(R.drawable.ic_search)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        val searchView = SearchView(requireActivity().actionBar?.themedContext ?: requireContext())
        searchView.queryHint = getString(R.string.passwords_search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean {
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(text: String?): Boolean {
                query = text ?: ""
                render()
                return true
            }
        })
        item.actionView = searchView
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
}
