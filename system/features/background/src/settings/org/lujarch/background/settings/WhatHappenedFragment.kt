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
import android.text.format.DateUtils
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Pages

/** Settings > Apps > (app) > Background > What happened > All (BG-6): the last 7 days, by day. */
class WhatHappenedFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private lateinit var daysPref: PreferenceCategory
    private val groups = ArrayList<PreferenceCategory>()

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        packageName = requireArguments().getString(AppBackgroundFragment.ARG_PACKAGE).orEmpty()
        daysPref = screen.add(PreferenceCategory(prefContext)) { key = "days" }
        screen.add(FooterPreference(prefContext)) {
            key = "kept_days"
            order = Int.MAX_VALUE
            setTitle(R.string.background_all_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            val events = attempt("events", R.string.background_error_load) {
                BackgroundClient.call {
                    it.getEvents(packageName, UserHandle.myUserId(), System.currentTimeMillis() - WEEK_MS, MAX).toList()
                }
            } ?: return@launchUi
            if (!pageAlive) return@launchUi
            val context = prefContext
            val screen = preferenceScreen
            groups.forEach { screen.removePreference(it) }
            groups.clear()
            daysPref.isVisible = events.isEmpty()
            daysPref.setTitle(R.string.background_nothing_happened)
            var day: String? = null
            var group: PreferenceCategory? = null
            val time = android.text.format.DateFormat.getTimeFormat(context)
            events.forEachIndexed { i, e ->
                val d = DateUtils.formatDateTime(context, e.timeMillis,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
                if (d != day || group == null) {
                    day = d
                    group = PreferenceCategory(context).apply {
                        key = "day_$i"
                        title = d
                        order = i + 1
                    }
                    screen.addPreference(group!!)
                    groups.add(group!!)
                }
                group!!.add(LinePreference(context)) {
                    key = "event_$i"
                    lead = time.format(java.util.Date(e.timeMillis))
                    who = e.who
                    whoName = e.whoName
                    text = e.text
                }
            }
        }
    }

    companion object {
        private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX = 2000

        fun open(context: Context, packageName: String, label: String?) {
            Pages.open(context, WhatHappenedFragment::class.java, R.string.background_what_happened,
                arguments = AppBackgroundFragment.args(packageName, label))
        }
    }
}
