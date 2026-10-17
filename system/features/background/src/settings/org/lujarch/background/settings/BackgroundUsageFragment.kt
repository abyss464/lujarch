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
import android.os.Bundle
import android.os.UserHandle
import androidx.preference.PreferenceScreen
import com.android.internal.app.LujarchBackground
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import org.lujarch.settings.appString
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Pages

/**
 * Settings > Apps > (app) > Background > Background usage (BG-4): Android's Unrestricted,
 * Optimized and Restricted, with their own words, and Messages only.
 */
class BackgroundUsageFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private val choices = LinkedHashMap<Int, SelectorWithWidgetPreference>()
    private lateinit var footer: FooterPreference

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        packageName = requireArguments().getString(AppBackgroundFragment.ARG_PACKAGE).orEmpty()
        val context = prefContext
        for ((usage, title, summary) in rows(context)) {
            choices[usage] = screen.add(SelectorWithWidgetPreference(context)) {
                key = KEYS[usage]
                this.title = title
                this.summary = summary
                isPersistent = false
                isEnabled = false
                setOnClickListener { choose(usage) }
            }
        }
        footer = screen.add(FooterPreference(context)) {
            key = "usage_footer"
            title = appString(context, SETTINGS, "manager_battery_usage_footer")
        }
    }

    /** Android's three choices in its words, and Messages only after Unrestricted. */
    private fun rows(context: Context): List<Triple<Int, String, String>> = listOf(
        Triple(LujarchBackground.USAGE_UNRESTRICTED,
            LujarchBackground.usageName(context, LujarchBackground.USAGE_UNRESTRICTED),
            appString(context, SETTINGS, "manager_battery_usage_unrestricted_summary").orEmpty()),
        Triple(LujarchBackground.USAGE_MESSAGES,
            LujarchBackground.usageName(context, LujarchBackground.USAGE_MESSAGES),
            context.getString(R.string.background_usage_messages_summary)),
        Triple(LujarchBackground.USAGE_OPTIMIZED,
            LujarchBackground.usageName(context, LujarchBackground.USAGE_OPTIMIZED),
            appString(context, SETTINGS, "manager_battery_usage_optimized_summary").orEmpty()),
        Triple(LujarchBackground.USAGE_RESTRICTED,
            LujarchBackground.usageName(context, LujarchBackground.USAGE_RESTRICTED),
            appString(context, SETTINGS, "manager_battery_usage_restricted_summary").orEmpty()),
    )

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            val a = attempt("app", R.string.background_error_load) {
                BackgroundClient.call { it.getApp(packageName, UserHandle.myUserId()) }
            } ?: return@launchUi
            if (!pageAlive) return@launchUi
            for ((usage, pref) in choices) {
                pref.isChecked = usage == a.usage
                pref.isEnabled = !a.usageFixed
            }
            if (a.usageFixed) footer.setTitle(R.string.background_usage_fixed)
        }
    }

    private fun choose(usage: Int) {
        for ((u, pref) in choices) pref.isChecked = u == usage
        launchUi {
            act(R.string.background_error_usage) {
                BackgroundClient.call { it.setBackgroundUsage(packageName, UserHandle.myUserId(), usage) }
            }
            refresh()
        }
    }

    companion object {
        private const val SETTINGS = "com.android.settings"
        private val KEYS = arrayOf("unrestricted", "messages_only", "optimized", "restricted")

        fun open(context: Context, packageName: String, label: String?) {
            Pages.open(context, BackgroundUsageFragment::class.java, R.string.background_usage,
                arguments = AppBackgroundFragment.args(packageName, label))
        }
    }
}
