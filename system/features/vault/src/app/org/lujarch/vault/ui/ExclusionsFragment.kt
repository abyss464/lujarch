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

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.TopIntroPreference
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog
import org.lujarch.vault.R
import org.lujarch.vault.autofill.VaultAutofillService
import org.lujarch.vault.core.match.EntryMatcher
import org.lujarch.vault.core.match.ExclusionScope
import org.lujarch.vault.core.match.SiteExclusion
import org.lujarch.vault.data.Vault

/**
 * Apps and sites the autofill service never fills (Settings > Apps and
 * sites not filled); the service checks the list before it opens the
 * database. Apps go by package name. How far a site reaches is the user's
 * choice: one scope for the list and, on a site's own row, that scope or one
 * of its own.
 */
class ExclusionsFragment : BasePreferenceFragment() {
    private lateinit var apps: PreferenceCategory
    private lateinit var sites: PreferenceCategory

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.excluded_intro)
        }
        apps = screen.add(PreferenceCategory(prefContext)) {
            key = "apps"
            setTitle(R.string.excluded_apps)
            isOrderingAsAdded = true
        }
        sites = screen.add(PreferenceCategory(prefContext)) {
            key = "sites"
            setTitle(R.string.excluded_sites)
            isOrderingAsAdded = true
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val collator = Collator.getInstance()
        apps.removeAll()
        apps.add(Preference(prefContext)) {
            key = "add_app"
            setTitle(R.string.excluded_add_app)
            setIcon(R.drawable.ic_add)
            setOnPreferenceClickListener {
                showAppChooser()
                true
            }
        }
        val packages = Vault.prefs.autofillExcludedPackages
            .map { pkg -> pkg to appLabel(pm, pkg) }
            .sortedWith { a, b -> collator.compare(a.second ?: a.first, b.second ?: b.first) }
        for ((pkg, label) in packages) {
            apps.add(Preference(prefContext)) {
                key = "app:$pkg"
                title = label ?: pkg
                summary = if (label != null) pkg else getString(R.string.excluded_not_installed)
                icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
                isIconSpaceReserved = true
                setOnPreferenceClickListener {
                    confirmRemoveApp(pkg, label)
                    true
                }
            }
        }

        sites.removeAll()
        val default = Vault.prefs.autofillExclusionScope
        sites.add(ListPreference(prefContext)) {
            key = "site_scope"
            setTitle(R.string.excluded_scope)
            setDialogTitle(R.string.excluded_scope)
            isPersistent = false
            isIconSpaceReserved = true
            entries = arrayOf(getString(R.string.excluded_scope_host), getString(R.string.excluded_scope_site))
            entryValues = arrayOf(ExclusionScope.HOST.key, ExclusionScope.SITE.key)
            value = default.key
            setSummary(
                if (default == ExclusionScope.HOST) {
                    R.string.excluded_scope_host_summary
                } else {
                    R.string.excluded_scope_site_summary
                },
            )
            setOnPreferenceChangeListener { _, v ->
                Vault.prefs.autofillExclusionScope = ExclusionScope.fromKey(v as String)
                // Every site that follows the scope now covers something else.
                render()
                true
            }
        }
        sites.add(Preference(prefContext)) {
            key = "add_site"
            setTitle(R.string.excluded_add_site)
            setIcon(R.drawable.ic_add)
            setOnPreferenceClickListener {
                showSiteDialog()
                true
            }
        }
        for (site in Vault.prefs.autofillExcludedSites.sortedWith { a, b -> collator.compare(a.host, b.host) }) {
            sites.add(Preference(prefContext)) {
                key = "site:${site.host}"
                title = site.host
                summary = getString(R.string.excluded_site_summary, scopeLabel(site.scope, default), covers(site, default))
                setIcon(R.drawable.ic_web)
                setOnPreferenceClickListener {
                    showSiteScopeDialog(site)
                    true
                }
            }
        }
    }

    /** The name of a site's scope; the one it follows names the list's scope. */
    private fun scopeLabel(scope: ExclusionScope, default: ExclusionScope): String = when (scope) {
        ExclusionScope.HOST -> getString(R.string.excluded_scope_host)
        ExclusionScope.SITE -> getString(R.string.excluded_scope_site)
        ExclusionScope.DEFAULT ->
            getString(R.string.excluded_scope_follow, scopeLabel(default.resolve(ExclusionScope.SITE), default))
    }

    /** What a site's exclusion covers, in the scope that applies to it. */
    private fun covers(site: SiteExclusion, default: ExclusionScope): String =
        if (site.scope.resolve(default) == ExclusionScope.HOST) {
            getString(R.string.excluded_covers_host, site.domain)
        } else {
            getString(R.string.excluded_covers_site, site.domain)
        }

    /** A site's row: the scope it follows or one of its own, and removing it. */
    private fun showSiteScopeDialog(site: SiteExclusion) = showDialog("site-scope") { d ->
        val default = Vault.prefs.autofillExclusionScope
        val choices = listOf(ExclusionScope.DEFAULT, ExclusionScope.HOST, ExclusionScope.SITE)
        val labels = choices.map { scopeLabel(it, default) }.toTypedArray<CharSequence>()
        AlertDialog.Builder(d.context)
            .setTitle(site.host)
            .setSingleChoiceItems(labels, choices.indexOf(site.scope)) { dialog, which ->
                dialog.dismiss()
                setScope(site, choices[which])
            }
            .setNeutralButton(R.string.excluded_remove) { _, _ ->
                confirmRemove(site.host) { removeSite(site) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun setScope(site: SiteExclusion, scope: ExclusionScope) {
        if (scope == site.scope) return
        Vault.prefs.autofillExcludedSites =
            Vault.prefs.autofillExcludedSites.filter { it.host != site.host } + SiteExclusion(site.host, scope)
        render()
    }

    private fun removeSite(site: SiteExclusion) {
        Vault.prefs.autofillExcludedSites = Vault.prefs.autofillExcludedSites.filter { it.host != site.host }
    }

    private fun appLabel(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun confirmRemoveApp(pkg: String, label: String?) = confirmRemove(label ?: pkg) {
        Vault.prefs.autofillExcludedPackages = Vault.prefs.autofillExcludedPackages - pkg
    }

    private fun confirmRemove(name: String, @RebuiltWithPage remove: ExclusionsFragment.() -> Unit) = showDialog("remove") { d ->
        AlertDialog.Builder(d.context)
            .setTitle(getString(R.string.excluded_remove_title, name))
            .setMessage(R.string.excluded_remove_message)
            .setPositiveButton(R.string.excluded_remove) { _, _ ->
                remove()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    /** Apps with a launcher entry (the vault may see those), minus our own and the ones already listed. */
    private fun showAppChooser() {
        launchUi {
            val ctx = requireContext()
            val pm = ctx.packageManager
            val excluded = Vault.prefs.autofillExcludedPackages
            val choices = withContext(Dispatchers.Default) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val collator = Collator.getInstance()
                pm.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }
                    .distinct()
                    .filter { it !in excluded && !VaultAutofillService.isOwnPackage(it) }
                    .map { it to (appLabel(pm, it) ?: it) }
                    .sortedWith { a, b -> collator.compare(a.second, b.second) }
            }
            if (!isAdded) return@launchUi
            // The list is the dialog's: a recreated page shows the same apps.
            showDialog("app-choice") { d ->
                AlertDialog.Builder(d.context)
                    .setTitle(R.string.excluded_choose_app)
                    .setItems(choices.map { it.second }.toTypedArray<CharSequence>()) { _, which ->
                        Vault.prefs.autofillExcludedPackages = Vault.prefs.autofillExcludedPackages + choices[which].first
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
            }
        }
    }

    /** The typed site comes back with a recreated page (the field has an id). */
    private fun showSiteDialog() = showDialog("add-site") { d ->
        val view = LayoutInflater.from(d.context).inflate(R.layout.dialog_text, null)
        val edit = view.findViewById<EditText>(R.id.text)
        edit.setHint(R.string.excluded_site_hint)
        AlertDialog.Builder(d.context)
            .setTitle(R.string.excluded_add_site)
            .setView(view)
            .setPositiveButton(R.string.excluded_add) { _, _ ->
                val host = EntryMatcher.normalizeTypedHost(edit.text.toString())
                if (host == null || !host.contains('.')) {
                    Toast.makeText(requireContext(), R.string.excluded_site_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    // A site that is already listed keeps the scope it has.
                    val listed = Vault.prefs.autofillExcludedSites
                    if (listed.none { it.host == host }) {
                        Vault.prefs.autofillExcludedSites = listed + SiteExclusion(host)
                    }
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
