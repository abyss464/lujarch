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

package org.lujarch.proxy.settings

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.ZeroStatePreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.R as PagesR

/** Localized proxy or group type. */
fun proxyTypeLabel(context: Context, type: String): String = ProxyFormat.typeLabel(context, type)

/**
 * The running core's proxy groups in the sections of the per-app group
 * picker: custom groups, countries and regions, by line type, all nodes,
 * and the other groups (GLOBAL); lowest-delay groups by default, fallback
 * and load-balance groups behind a switch; searchable by name. Each opens
 * its node list.
 */
class GroupsFragment : BasePreferenceFragment(), MenuProvider {
    private lateinit var variantsPref: SwitchPreferenceCompat
    private lateinit var emptyPref: ZeroStatePreference
    private val categories = LinkedHashMap<String, PreferenceCategory>()
    private val groupPrefs = HashMap<String, Preference>()
    private var groups: List<ProxyItem> = emptyList()
    /** Controller group name -> section (from the manager's targets). */
    private var kinds: Map<String, String> = emptyMap()
    private var showVariants = false
    private var query = ""
    private var loadJob: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        showVariants = savedInstanceState?.getBoolean(STATE_VARIANTS) ?: false
        query = savedInstanceState?.getString(STATE_QUERY) ?: ""
        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        variantsPref = header.add(SwitchPreferenceCompat(prefContext)) {
            key = "variants"
            setTitle(R.string.px_groups_variants)
            setSummary(R.string.px_groups_variants_summary)
            isPersistent = false
            isChecked = showVariants
            setOnPreferenceChangeListener { _, value ->
                showVariants = value as Boolean
                render()
                true
            }
        }
        for ((kind, title) in listOf(
            RouteTarget.KIND_CUSTOM to R.string.targets_custom,
            RouteTarget.KIND_COUNTRY to R.string.targets_country,
            RouteTarget.KIND_CLASS to R.string.targets_class,
            RouteTarget.KIND_ALL to R.string.targets_all,
            KIND_OTHER to R.string.px_groups_other,
        )) {
            categories[kind] = screen.add(PreferenceCategory(prefContext)) {
                key = "kind:$kind"
                setTitle(title)
                isOrderingAsAdded = false
                isVisible = false
            }
        }
        emptyPref = screen.add(ZeroStatePreference(prefContext)) {
            key = "empty"
            setIcon(R.drawable.ic_groups)
            setTitle(PagesR.string.loading)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_VARIANTS, showVariants)
        outState.putString(STATE_QUERY, query)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = launchUi {
            // The sections come from the manager; without them every group is listed under "Other groups".
            val targets = async { attempt("targets", R.string.error_load_targets) { AppRouteTasks.targets() } }
            val snapshot = attempt("proxies", R.string.error_load_groups) { Mihomo.proxies() } ?: run {
                emptyPref.setTitle(R.string.px_groups_unavailable)
                return@launchUi
            }
            targets.await()?.let { list -> kinds = list.associate { it.group to it.kind } }
            groups = snapshot.groups
            render()
        }
    }

    private fun kindOf(group: ProxyItem): String = kinds[group.name] ?: KIND_OTHER

    /** Fallback and load-balance groups hide behind the switch, as in the group picker. */
    private fun isVariant(group: ProxyItem): Boolean =
        kindOf(group) != KIND_OTHER && !group.type.equals("URLTest", ignoreCase = true) && !group.isAdaptive

    private fun render() {
        val context = context ?: return
        val needle = query.trim().lowercase()
        val visible = groups.filter {
            (showVariants || !isVariant(it)) && (needle.isEmpty() || it.name.lowercase().contains(needle))
        }
        val keep = visible.map { it.name }.toHashSet()
        groupPrefs.keys.filter { it !in keep }.forEach { name ->
            groupPrefs.remove(name)?.let { pref -> categories.values.forEach { it.removePreference(pref) } }
        }
        visible.forEachIndexed { index, group ->
            val category = categories[kindOf(group)] ?: return@forEachIndexed
            val pref = groupPrefs[group.name]?.takeIf { it.parent == category } ?: run {
                groupPrefs.remove(group.name)?.let { old -> categories.values.forEach { it.removePreference(old) } }
                category.add(Preference(prefContext)) {
                    key = "group:" + group.name
                    title = group.name
                    setOnPreferenceClickListener {
                        GroupFragment.open(requireContext(), group)
                        true
                    }
                }.also { groupPrefs[group.name] = it }
            }
            pref.order = index
            pref.summary = ProxyFormat.join(
                context,
                listOf(
                    proxyTypeLabel(context, group.type),
                    group.now?.takeIf { it.isNotEmpty() }?.let { getString(R.string.px_group_now, it) },
                ),
            )
        }
        for ((kind, category) in categories) category.isVisible = visible.any { kindOf(it) == kind }
        emptyPref.setTitle(if (groups.isEmpty()) R.string.px_groups_empty else R.string.px_groups_no_match)
        emptyPref.isVisible = visible.isEmpty()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        val searchItem = menu.add(Menu.NONE, MENU_SEARCH, 0, PagesR.string.menu_search).setIcon(PagesR.drawable.ic_search)
        searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        val searchView = SearchView(requireActivity().actionBar?.themedContext ?: requireContext())
        searchView.queryHint = getString(R.string.px_groups_search_hint)
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
        searchItem.actionView = searchView
        if (query.isNotEmpty()) {
            val saved = query
            searchItem.expandActionView()
            searchView.setQuery(saved, false)
        }
        menu.add(Menu.NONE, MENU_REFRESH, 1, PagesR.string.menu_refresh)
            .setIcon(R.drawable.ic_refresh)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != MENU_REFRESH) return false
        refresh()
        return true
    }

    companion object {
        private const val KIND_OTHER = "other"
        private const val STATE_VARIANTS = "variants"
        private const val STATE_QUERY = "query"
        private const val MENU_SEARCH = 1
        private const val MENU_REFRESH = 2
    }
}
