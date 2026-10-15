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

package org.lujarch.settings.pages

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.SearchView
import androidx.annotation.StringRes
import androidx.core.view.MenuProvider
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.ZeroStatePreference
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Installed apps with icon, label and search: user apps by default, system
 * apps through the overflow item "Show system apps" (as in Settings' app
 * lists; the choice is kept for every list of this app). Shared by the root
 * access and per-app routing pages; a page loads its own per-app state,
 * makes one preference per app and says which apps are pinned (always
 * shown, also system apps, and sorted first).
 */
abstract class AppListFragment<P : Preference> : BasePreferenceFragment(), MenuProvider {
    class AppEntry(
        val packageName: String,
        val label: String,
        val system: Boolean,
        /** null for a package that is not installed for this user but still has state. */
        val info: ApplicationInfo?,
        var icon: Drawable? = null,
    )

    protected lateinit var appsCategory: PreferenceCategory
        private set
    private lateinit var loadingPref: Preference
    private lateinit var emptyPref: ZeroStatePreference

    protected var apps: List<AppEntry> = emptyList()
        private set
    protected var appsByName: Map<String, AppEntry> = emptyMap()
        private set
    protected val appPrefs = HashMap<String, P>()
    private var showSystem = false
    private var query = ""
    private var loadJob: Job? = null
    private var iconJob: Job? = null

    // ---- page hooks ----

    /** Adds the preferences above the list (the returned category is not used any more). */
    protected abstract fun buildHeader(screen: PreferenceScreen, savedInstanceState: Bundle?): PreferenceCategory

    /** Not shown: system apps are an overflow item now; kept for the pages that still set it. */
    @get:StringRes protected open val showSystemSummaryRes: Int = 0

    @get:StringRes protected abstract val appsCategoryRes: Int

    @get:StringRes protected abstract val footerRes: Int

    /** Loads the page's per-app state; runs while the installed apps load. Failures go to the banner. */
    protected abstract suspend fun loadState()

    /** Packages with state that are not installed for this user: listed so that the state can be removed. */
    protected open fun extraPackages(): Collection<String> = emptyList()

    protected abstract fun isPinned(app: AppEntry): Boolean

    protected abstract fun createAppPref(app: AppEntry): P

    protected abstract fun bindAppPref(pref: P, app: AppEntry)

    protected abstract fun categoryTitle(): CharSequence

    // ---- screen ----

    final override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        showSystem = showSystemApps(requireContext())
        query = savedInstanceState?.getString(STATE_QUERY) ?: ""

        buildHeader(screen, savedInstanceState)
        appsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "apps"
            setTitle(appsCategoryRes)
            isOrderingAsAdded = false
        }
        loadingPref = appsCategory.add(Preference(prefContext)) {
            key = "loading"
            setTitle(R.string.loading)
            isSelectable = false
        }
        emptyPref = ZeroStatePreference(prefContext).apply {
            key = "empty"
            setIcon(R.drawable.ic_apps)
            setTitle(R.string.apps_none)
            isVisible = false
        }
        screen.addPreference(emptyPref)
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(footerRes)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_QUERY, query)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = launchUi {
            val context = requireContext()
            val state = async { loadState() }
            val installed = attempt("apps", R.string.error_load_apps) { loadInstalled(context.packageManager) }
            state.await()
            apps = merge(installed ?: emptyList())
            appsByName = apps.associateBy { it.packageName }
            loadingPref.isVisible = false
            render()
        }
    }

    // ---- data ----

    private class Installed(val info: ApplicationInfo, val label: String)

    private suspend fun loadInstalled(pm: PackageManager): List<Installed> = withContext(Dispatchers.IO) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .map { Installed(it, it.loadLabel(pm).toString()) }
    }

    private fun merge(installed: List<Installed>): List<AppEntry> {
        val previous = appsByName
        val entries = installed.map {
            AppEntry(
                packageName = it.info.packageName,
                label = it.label,
                system = (it.info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                info = it.info,
                icon = previous[it.info.packageName]?.icon,
            )
        }.toMutableList()
        val known = entries.map { it.packageName }.toHashSet()
        extraPackages().filter { it !in known }.forEach {
            entries.add(AppEntry(it, it, system = false, info = null))
        }
        val collator = Collator.getInstance()
        return entries.sortedWith { a, b ->
            val pinnedA = isPinned(a)
            val pinnedB = isPinned(b)
            if (pinnedA != pinnedB) {
                if (pinnedA) -1 else 1
            } else {
                collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName)
            }
        }
    }

    // ---- rendering ----

    protected fun render() {
        if (!isAdded || loadingPref.isVisible) return
        val needle = query.trim().lowercase()
        val visible = apps.filter { app ->
            (showSystem || !app.system || isPinned(app)) &&
                (needle.isEmpty() || app.label.lowercase().contains(needle) ||
                    app.packageName.lowercase().contains(needle))
        }
        val keep = visible.map { it.packageName }.toHashSet()
        appPrefs.keys.filter { it !in keep }.forEach { name ->
            appPrefs.remove(name)?.let { appsCategory.removePreference(it) }
        }
        visible.forEachIndexed { index, app ->
            val pref = appPrefs[app.packageName] ?: createAppPref(app).also {
                appPrefs[app.packageName] = it
                appsCategory.addPreference(it)
            }
            pref.order = index
            bindAppPref(pref, app)
        }
        appsCategory.title = categoryTitle()
        appsCategory.isVisible = visible.isNotEmpty()
        emptyPref.isVisible = visible.isEmpty()
        loadIcons()
    }

    /** Binds the shown apps and the category title again, without filtering or sorting anew. */
    protected fun rebindApps() {
        if (!isAdded || loadingPref.isVisible) return
        appPrefs.forEach { (name, pref) -> appsByName[name]?.let { bindAppPref(pref, it) } }
        appsCategory.title = categoryTitle()
    }

    /** Loads missing icons of the shown apps in the background, in chunks. */
    private fun loadIcons() {
        if (iconJob?.isActive == true) return
        val pm = requireContext().packageManager
        iconJob = launchUi {
            while (true) {
                val missing = appPrefs.keys.mapNotNull { appsByName[it] }
                    .filter { it.icon == null && it.info != null }
                if (missing.isEmpty()) break
                for (chunk in missing.chunked(ICON_CHUNK)) {
                    val icons = withContext(Dispatchers.IO) {
                        chunk.map { app ->
                            try {
                                pm.getApplicationIcon(app.info!!)
                            } catch (e: RuntimeException) {
                                Log.w(TAG, "icon of ${app.packageName} failed to load", e)
                                pm.defaultActivityIcon
                            }
                        }
                    }
                    chunk.zip(icons).forEach { (app, icon) ->
                        app.icon = icon
                        appPrefs[app.packageName]?.icon = icon
                    }
                }
            }
        }
    }

    // ---- menu ----

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        val searchItem = menu.add(Menu.NONE, MENU_SEARCH, 0, R.string.menu_search)
            .setIcon(R.drawable.ic_search)
        searchItem.setShowAsAction(
            MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
        )
        val searchView = SearchView(requireActivity().actionBar?.themedContext ?: requireContext())
        searchView.queryHint = getString(R.string.apps_search_hint)
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
        // In the overflow menu, as in Settings' own app lists: with search, refresh
        // and the overflow as icons, a page title such as "Apps that use the
        // proxy" was cut off. The lists also reload when the page is shown.
        menu.add(Menu.NONE, MENU_REFRESH, 1, R.string.menu_refresh)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_SHOW_SYSTEM, 2, showSystemTitle())
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(MENU_SHOW_SYSTEM)?.setTitle(showSystemTitle())
    }

    private fun showSystemTitle(): Int = if (showSystem) R.string.apps_hide_system else R.string.apps_show_system

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
        MENU_REFRESH -> {
            refresh()
            true
        }
        MENU_SHOW_SYSTEM -> {
            showSystem = !showSystem
            setShowSystemApps(requireContext(), showSystem)
            menuItem.setTitle(showSystemTitle())
            render()
            true
        }
        else -> false
    }

    companion object {
        private const val STATE_QUERY = "query"
        private const val MENU_SEARCH = 1
        private const val MENU_REFRESH = 2
        private const val MENU_SHOW_SYSTEM = 3
        private const val ICON_CHUNK = 16
        private const val PREFS = "app_lists"
        private const val KEY_SHOW_SYSTEM = "show_system_apps"

        /** Whether app lists show system apps; off until the owner chooses otherwise. */
        fun showSystemApps(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_SYSTEM, false)

        fun setShowSystemApps(context: Context, show: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_SYSTEM, show).apply()
        }
    }
}
