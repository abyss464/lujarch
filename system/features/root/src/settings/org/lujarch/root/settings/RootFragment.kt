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

package org.lujarch.root.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.AppPreference
import com.android.settingslib.widget.TopIntroPreference
import org.lujarch.settings.pages.AppListFragment
import org.lujarch.settings.pages.R as PagesR

/**
 * Root access (ROOT-1): KernelSU's version, and the installed apps with their state (allowed,
 * allowed with a custom profile, not allowed; a package that shares its user ID with an allowed
 * one is allowed through it), the allowed ones first; an app's page holds the switch and its root
 * profile. Nothing can be changed while the kernel has no KernelSU.
 */
class RootFragment : AppListFragment<AppPreference>() {
    private lateinit var ksuPref: Preference
    private var profiles: Map<String, SuApp> = emptyMap()
    private var byUid: Map<Int, SuApp> = emptyMap()
    private var available = false
    private var suKnown = false

    override val appsCategoryRes = R.string.root_apps_category
    override val footerRes = R.string.root_footer

    override fun buildHeader(screen: PreferenceScreen, savedInstanceState: Bundle?): PreferenceCategory {
        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.root_intro)
        }
        val status = screen.add(PreferenceCategory(prefContext)) { key = "status" }
        ksuPref = status.add(Preference(prefContext)) {
            key = "ksu"
            setTitle(R.string.root_ksu_title)
            setIcon(R.drawable.ic_root)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        return status
    }

    override suspend fun loadState() {
        val status = attempt("status", R.string.error_load_root) { RootClient.status() }
        available = status?.available == true
        ksuPref.summary = when {
            status == null -> getString(R.string.status_unknown)
            status.available -> getString(R.string.root_ksu_version, status.version ?: "")
            else -> getString(R.string.root_ksu_unavailable)
        }
        val su = if (available) attempt("list", R.string.error_load_root) { RootClient.list() } else null
        suKnown = su != null
        profiles = su.orEmpty().associateBy { it.packageName }
        // KernelSU keeps one profile per user ID, under one package name.
        byUid = su.orEmpty().associateBy { it.uid }
        appsCategory.isEnabled = available
    }

    private val allowedCount: Int
        get() = profiles.values.count { it.allowSu }

    override fun isPinned(app: AppEntry): Boolean =
        profiles[app.packageName]?.allowSu == true || app.info?.uid?.let { byUid[it]?.allowSu } == true

    override fun categoryTitle(): CharSequence = if (suKnown) {
        resources.getQuantityString(R.plurals.root_apps_category_count, allowedCount, allowedCount)
    } else {
        getString(R.string.root_apps_category)
    }

    override fun createAppPref(app: AppEntry): AppPreference =
        AppPreference(prefContext).apply {
            key = "app:" + app.packageName
            isPersistent = false
            title = app.label
            setOnPreferenceClickListener {
                AppRootFragment.open(requireContext(), app.packageName, app.label)
                true
            }
        }

    override fun bindAppPref(pref: AppPreference, app: AppEntry) {
        val own = profiles[app.packageName]
        val shared = if (own == null) app.info?.uid?.let { byUid[it] } else null
        val profile = own ?: shared
        val state = when {
            !suKnown -> getString(R.string.status_unknown)
            profile?.allowSu == true && shared != null -> getString(R.string.root_state_allowed_shared)
            profile?.allowSu == true && profile.custom -> getString(R.string.root_state_allowed_custom)
            profile?.allowSu == true -> getString(R.string.root_state_allowed)
            else -> getString(R.string.root_state_not_allowed)
        }
        pref.summary = if (app.system) getString(R.string.root_app_system_state, state) else state
        if (app.icon != null) pref.icon = app.icon
    }
}
