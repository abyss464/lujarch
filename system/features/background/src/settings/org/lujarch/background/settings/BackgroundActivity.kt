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

package org.lujarch.background.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.util.Log
import androidx.fragment.app.Fragment
import com.android.internal.app.LujarchBackground
import org.lujarch.settings.AppInfoRow
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity

/**
 * Settings > System > Background (BG-2) and an app's Background page (BG-3), opened from App
 * info, the Background notification, its alerts and Recents.
 */
class BackgroundActivity : BaseActivity() {
    private val appPackage: String?
        get() = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
            ?.takeIf { intent.action == LujarchBackground.ACTION_APP }

    override fun pageTitle(): CharSequence = getText(R.string.background_title)

    override fun createFragment(): Fragment? {
        if (intent.action != LujarchBackground.ACTION_APP) return BackgroundFragment()
        val pkg = appPackage
        if (pkg == null || !PACKAGE.matches(pkg)) {
            Log.w(TAG, "${LujarchBackground.ACTION_APP} without a valid ${Intent.EXTRA_PACKAGE_NAME}")
            return null
        }
        val label = try {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "$pkg is not installed", e)
            return null
        }
        return AppBackgroundFragment().apply { arguments = AppBackgroundFragment.args(pkg, label) }
    }

    private companion object {
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

/** Background in App info, with the app's depth (BG-3), and Background's page in search (BG-2). */
class BackgroundContribution : Contribution {
    override fun appInfoRows(context: Context, packageName: String, userId: Int): List<AppInfoRow> {
        val summary = try {
            BackgroundClient.callNow { it.getApp(packageName, userId) }
                ?.let { LujarchBackground.depthName(context, it.depth) }
        } catch (e: Exception) {
            Log.w(TAG, "App info: the background of $packageName is not known: ${e.message}")
            null
        } ?: context.getString(R.string.background_unknown)
        return listOf(
            AppInfoRow(
                key = "lujarch_background",
                title = context.getString(R.string.background_title),
                summary = summary,
                action = LujarchBackground.ACTION_APP,
            )
        )
    }

    override fun searchEntries(context: Context): List<SearchEntry> =
        listOf(
            SearchEntry(
                key = "lujarch_background",
                title = context.getString(R.string.background_title),
                summary = context.getString(R.string.background_summary),
                keywords = context.getString(R.string.background_keywords),
                screenTitle = context.getString(R.string.background_system),
                action = LujarchBackground.ACTION_BACKGROUND,
            ),
            SearchEntry(
                key = BackgroundFragment.KEY_SHOW_SYSTEM,
                title = context.getString(R.string.background_show_system),
                summary = "",
                keywords = context.getString(R.string.background_show_system_keywords),
                screenTitle = context.getString(R.string.background_title),
                action = LujarchBackground.ACTION_BACKGROUND,
            ),
        )
}
