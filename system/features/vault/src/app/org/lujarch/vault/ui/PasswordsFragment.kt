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
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.ZeroStatePreference
import java.text.Collator
import org.lujarch.vault.R
import org.lujarch.vault.core.KpEntry
import org.lujarch.vault.core.KpGroup
import org.lujarch.vault.data.KeePassData
import org.lujarch.vault.data.Source
import org.lujarch.vault.data.VaultState

/**
 * KeePass groups and entries, one group per page (the root group first);
 * searching covers the whole database (title, user name, URL, tags, group).
 */
class PasswordsFragment : BasePreferenceFragment(), MenuProvider {
    private lateinit var groupsCategory: PreferenceCategory
    private lateinit var entriesCategory: PreferenceCategory
    private lateinit var messagePref: ZeroStatePreference
    private var shown: KeePassData? = null
    private var query = ""

    private val groupUuid: String?
        get() = arguments?.getString(ARG_GROUP)

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        query = savedInstanceState?.getString(STATE_QUERY) ?: ""
        messagePref = screen.add(ZeroStatePreference(prefContext)) {
            key = "message"
            setIcon(R.drawable.ic_key)
            isVisible = false
        }
        groupsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "groups"
            setTitle(R.string.passwords_groups)
        }
        entriesCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "entries"
            setTitle(R.string.passwords_entries)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_QUERY, query)
    }

    override fun onVaultState(state: VaultState) {
        val data = state.keepassData
        if (data !== shown) {
            shown = data
            render()
        }
        if (data == null) {
            showMessage(
                when (state.keepass) {
                    is Source.NotSetUp -> R.string.status_not_set_up
                    is Source.Off -> R.string.status_off
                    is Source.Loading -> R.string.status_unlocking
                    is Source.Failed -> R.string.status_failed
                    else -> R.string.status_locked
                },
            )
        }
    }

    private fun showMessage(res: Int) {
        messagePref.setTitle(res)
        messagePref.isVisible = true
        groupsCategory.isVisible = false
        entriesCategory.isVisible = false
    }

    private fun render() {
        groupsCategory.removeAll()
        entriesCategory.removeAll()
        val data = shown ?: return
        val collator = Collator.getInstance()
        val needle = query.trim().lowercase()
        val entries: List<KpEntry>
        val groups: List<KpGroup>
        if (needle.isNotEmpty()) {
            groups = emptyList()
            entries = data.db.entries.filter { matches(it, needle) }
        } else {
            val group = groupUuid?.let { findGroup(data.db.root, it) } ?: data.db.root
            groups = group.groups
            entries = group.entries
        }
        for (g in groups.sortedWith { a, b -> collator.compare(a.name, b.name) }) {
            val count = g.allEntries().size
            groupsCategory.addPreference(Preference(prefContext).apply {
                key = "group:${g.uuid}"
                title = g.name
                summary = resources.getQuantityString(R.plurals.passwords_count, count, count)
                setIcon(R.drawable.ic_folder)
                setOnPreferenceClickListener {
                    Pages.open(requireContext(), PasswordsFragment::class.java, R.string.passwords_title, g.name,
                        Bundle().apply { putString(ARG_GROUP, g.uuid) })
                    true
                }
            })
        }
        for (e in entries.sortedWith { a, b -> collator.compare(a.title, b.title) }) {
            entriesCategory.addPreference(Preference(prefContext).apply {
                key = "entry:${e.uuid}"
                title = e.title.ifEmpty { getString(R.string.entry_untitled) }
                summary = if (needle.isNotEmpty() && e.path.isNotEmpty()) {
                    listOf(e.username, e.path.joinToString(" / ")).filter { it.isNotEmpty() }.joinToString(" · ")
                } else {
                    e.username
                }
                setIcon(R.drawable.ic_key)
                setOnPreferenceClickListener {
                    Pages.open(requireContext(), EntryFragment::class.java, R.string.entry_title, e.title,
                        Bundle().apply { putString(EntryFragment.ARG_UUID, e.uuid) })
                    true
                }
            })
        }
        groupsCategory.isVisible = groups.isNotEmpty()
        entriesCategory.isVisible = entries.isNotEmpty()
        messagePref.isVisible = groups.isEmpty() && entries.isEmpty()
        if (messagePref.isVisible) {
            messagePref.setTitle(if (needle.isNotEmpty()) R.string.passwords_no_match else R.string.passwords_empty_group)
        }
    }

    private fun matches(e: KpEntry, needle: String): Boolean =
        e.title.lowercase().contains(needle) || e.username.lowercase().contains(needle) ||
            e.url.lowercase().contains(needle) || e.tags.lowercase().contains(needle) ||
            e.path.any { it.lowercase().contains(needle) }

    private fun findGroup(g: KpGroup, uuid: String): KpGroup? {
        if (g.uuid == uuid) return g
        for (c in g.groups) findGroup(c, uuid)?.let { return it }
        return null
    }

    // ---- menu ----

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        val item = menu.add(Menu.NONE, MENU_SEARCH, 0, R.string.menu_search).setIcon(R.drawable.ic_search)
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
        if (query.isNotEmpty()) {
            val saved = query
            item.expandActionView()
            searchView.setQuery(saved, false)
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

    companion object {
        const val ARG_GROUP = "group"
        private const val STATE_QUERY = "query"
        private const val MENU_SEARCH = 1
    }
}
