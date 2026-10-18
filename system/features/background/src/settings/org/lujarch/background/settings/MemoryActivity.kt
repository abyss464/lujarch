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
import android.util.Log
import androidx.fragment.app.Fragment
import com.android.internal.app.LujarchBackground
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity

/**
 * Settings > System > Memory (BG-5) and an app's memory page (BG-6), opened from Settings, App
 * info, Developer options and Recents' memory row. Opened from Recents it is a task of its own,
 * never on top of another page of Lujarch Settings.
 */
class MemoryActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.memory_title)

    override fun createFragment(): Fragment? {
        if (intent.action != LujarchBackground.ACTION_APP_MEMORY) return MemoryFragment()
        val pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        if (pkg == null || !PACKAGE.matches(pkg)) {
            Log.w(TAG, "${LujarchBackground.ACTION_APP_MEMORY} without a valid ${Intent.EXTRA_PACKAGE_NAME}")
            return null
        }
        val label = try {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "$pkg is not installed", e)
            return null
        }
        return AppMemoryFragment().apply { arguments = AppMemoryFragment.args(pkg, label) }
    }

    private companion object {
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

/** The memory page in Settings search (BG-5). */
class MemoryContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> =
        listOf(
            SearchEntry(
                key = "lujarch_memory",
                title = context.getString(R.string.memory_title),
                summary = context.getString(R.string.memory_summary),
                keywords = context.getString(R.string.memory_keywords),
                screenTitle = context.getString(R.string.memory_system),
                action = LujarchBackground.ACTION_MEMORY,
            ),
            SearchEntry(
                key = MemoryFragment.KEY_SHOW_SYSTEM,
                title = context.getString(R.string.memory_show_system),
                summary = "",
                keywords = context.getString(R.string.memory_show_system_keywords),
                screenTitle = context.getString(R.string.memory_title),
                action = LujarchBackground.ACTION_MEMORY,
            ),
        )
}
