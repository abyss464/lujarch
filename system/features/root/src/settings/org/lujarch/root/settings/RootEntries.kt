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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.root.IRoot
import org.lujarch.settings.AppInfoRow
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity
import org.lujarch.settings.pages.R as PagesR

/** Root access in Special app access, and one app's root from its App info row. */
class RootActivity : BaseActivity() {
    private val appPackage: String?
        get() = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)?.takeIf { intent.action == ACTION_APP_ROOT }

    override fun pageTitle(): CharSequence {
        val pkg = appPackage ?: return getText(R.string.root_title)
        return AppRootFragment.appLabel(this, pkg) ?: pkg
    }

    override fun createFragment(): Fragment? {
        if (intent.action != ACTION_APP_ROOT) return RootFragment()
        val pkg = appPackage
        if (pkg == null || !isPackageName(pkg)) {
            Log.w(TAG, "APP_ROOT without a valid ${Intent.EXTRA_PACKAGE_NAME}")
            return null
        }
        return AppRootFragment().apply {
            arguments = AppRootFragment.args(pkg, AppRootFragment.appLabel(this@RootActivity, pkg))
        }
    }

    companion object {
        const val ACTION_ROOT = "org.lujarch.root.action.ROOT"
        const val ACTION_APP_ROOT = "org.lujarch.root.action.APP_ROOT"
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun isPackageName(name: String): Boolean = name.length <= 255 && PACKAGE.matches(name)
    }
}

/** Root in App info (each app's state) and in Settings search. */
class RootContribution : Contribution {
    override fun appInfoRows(context: Context, packageName: String, userId: Int): List<AppInfoRow> {
        // Root concerns apps KernelSU can grant; Lujarch's own and system user IDs get no row.
        val uid = try {
            context.packageManager.getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0))
        } catch (e: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        if (packageName.startsWith("org.lujarch.") ||
            RootClient.uidClass(uid) == AppRootState.UID_FORBIDDEN) {
            return emptyList()
        }
        // Without KernelSU in the kernel no app has root.
        var noKernelSu = false
        val state = runBlocking {
            withTimeoutOrNull(ROW_TIMEOUT_MS) {
                try {
                    RootClient.state(context, packageName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    noKernelSu = (e as? RootException)?.code == IRoot.ERROR_UNAVAILABLE
                    if (!noKernelSu) Log.w(TAG, "App info: root of $packageName unavailable: ${e.message}")
                    null
                }
            }
        }
        val summary = when {
            noKernelSu -> context.getString(R.string.root_state_not_allowed)
            state == null -> context.getString(R.string.status_unknown)
            state.allowSu && state.root?.custom == true -> context.getString(R.string.root_state_allowed_custom)
            state.allowSu -> context.getString(R.string.root_state_allowed)
            else -> context.getString(R.string.root_state_not_allowed)
        }
        return listOf(
            AppInfoRow(
                key = "lujarch_root",
                title = context.getString(R.string.root_title),
                summary = summary,
                action = RootActivity.ACTION_APP_ROOT,
            )
        )
    }

    override fun searchEntries(context: Context): List<SearchEntry> =
        listOf(
            SearchEntry(
                key = "lujarch_root",
                title = context.getString(R.string.root_title),
                summary = context.getString(R.string.root_summary),
                keywords = context.getString(R.string.search_root_keywords),
                screenTitle = context.getString(R.string.root_special_access),
                action = RootActivity.ACTION_ROOT,
            )
        )

    private companion object {
        const val ROW_TIMEOUT_MS = 3000L
    }
}
