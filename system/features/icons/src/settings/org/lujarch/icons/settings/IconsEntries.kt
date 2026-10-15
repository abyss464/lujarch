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

package org.lujarch.icons.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import androidx.fragment.app.Fragment
import org.lujarch.settings.AppInfoRow
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity

/** One app's icon, from its App info row (ICON-1). */
class IconsActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.icons_app_icon_title)

    override fun createFragment(): Fragment? {
        val pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        if (pkg.isNullOrEmpty()) {
            Log.w(Icons.TAG, "APP_ICON without ${Intent.EXTRA_PACKAGE_NAME}")
            return null
        }
        val user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
            ?: Process.myUserHandle()
        return AppIconFragment().apply { arguments = AppIconFragment.args(pkg, user.identifier) }
    }

    companion object {
        const val ACTION_APP_ICON = "org.lujarch.icons.action.APP_ICON"
        const val ACTION_FIT = "org.lujarch.icons.action.FIT"
    }
}

/** Settings search's way to Fit app icons: Wallpaper & style's app icon sheet. */
class FitIconsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(open())
        } catch (e: ActivityNotFoundException) {
            Log.e(Icons.TAG, "Wallpaper & style cannot open its app icons", e)
        }
        finish()
    }

    companion object {
        fun open(): Intent =
            Intent(Intent.ACTION_SET_WALLPAPER)
                .setPackage("com.android.wallpaper")
                .putExtra("destination", "icons")
    }
}

/** Icon in App info, for the apps whose icon the system fits, and Fit app icons in search. */
class IconsContribution : Contribution {
    override fun appInfoRows(context: Context, packageName: String, userId: Int): List<AppInfoRow> {
        if (!Icons.isOn(context)) return emptyList()
        val app = Icons.appInfo(context, packageName, userId) ?: return emptyList()
        if (app.icon == 0) return emptyList()
        val mode = try {
            if (!Icons.service().isFittable(packageName, app.icon, userId)) return emptyList()
            Icons.mode(packageName)
        } catch (e: RemoteException) {
            Log.w(Icons.TAG, "App info: the icon of $packageName is unknown", e)
            return emptyList()
        } catch (e: IllegalStateException) {
            Log.w(Icons.TAG, "App info: ${e.message}")
            return emptyList()
        }
        return listOf(
            AppInfoRow(
                key = "lujarch_icons",
                title = context.getString(R.string.icons_app_icon_title),
                summary = context.getString(Icons.label(mode)),
                action = IconsActivity.ACTION_APP_ICON,
            )
        )
    }

    override fun searchEntries(context: Context): List<SearchEntry> =
        listOf(
            SearchEntry(
                key = "lujarch_icons_fit",
                title = context.getString(R.string.icons_fit_title),
                summary = context.getString(R.string.icons_fit_summary),
                keywords = context.getString(R.string.icons_fit_keywords),
                screenTitle = context.getString(R.string.icons_wallpaper_and_style),
                action = IconsActivity.ACTION_FIT,
            )
        )
}
