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

package org.lujarch.motion.settings

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.TopIntroPreference
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity
import org.lujarch.settings.pages.BasePreferenceFragment

/** The moments that have a switch, each with its setting (settings.toml) and its row's words. */
internal enum class Moment(
    val key: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
) {
    LOCK_SCREEN(
        "lujarch_motion_lock_screen",
        R.string.motion_lock_screen,
        R.string.motion_lock_screen_summary,
    ),
    CHARGING("lujarch_motion_charging", R.string.motion_charging, R.string.motion_charging_summary),
    BACK_GESTURE(
        "lujarch_motion_back_gesture",
        R.string.motion_back_gesture,
        R.string.motion_back_gesture_summary,
    ),
    VOLUME("lujarch_motion_volume", R.string.motion_volume, R.string.motion_volume_summary);

    fun isOn(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, key, 1) != 0

    fun set(context: Context, on: Boolean): Boolean =
        try {
            Settings.Secure.putInt(context.contentResolver, key, if (on) 1 else 0)
        } catch (e: SecurityException) {
            Log.e(TAG, "$key not written", e)
            false
        }

    companion object {
        const val TAG = "lujarch_motion"
    }
}

/** Lujarch motion (MOT-2, MOT-4, MOT-6, MOT-7), from its row in Color and motion. */
class MotionActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.motion_title)

    override fun createFragment(): Fragment = MotionFragment()

    companion object {
        const val ACTION_MOTION = "org.lujarch.motion.action.MOTION"
    }
}

/**
 * One switch for each of Lujarch's moments that has one: on, the moment plays in its place;
 * off, Android's own animation does.
 */
class MotionFragment : BasePreferenceFragment() {
    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.motion_intro)
        }
        for (m in Moment.entries) {
            screen.add(SwitchPreferenceCompat(prefContext)) {
                key = m.key
                isPersistent = false
                setTitle(m.title)
                setSummary(m.summary)
                isChecked = m.isOn(prefContext)
                setOnPreferenceChangeListener { _, value -> m.set(prefContext, value as Boolean) }
            }
        }
    }
}

/** The page and each of its switches, found by Settings search. */
class MotionContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = context.getString(R.string.motion_title)
        val page =
            SearchEntry(
                key = "lujarch_motion",
                title = screen,
                summary = context.getString(R.string.motion_summary),
                keywords = context.getString(R.string.motion_keywords),
                screenTitle = context.getString(R.string.motion_color_and_motion),
                action = MotionActivity.ACTION_MOTION,
            )
        return listOf(page) +
            Moment.entries.map {
                SearchEntry(
                    key = it.key,
                    title = context.getString(it.title),
                    summary = context.getString(it.summary),
                    keywords = context.getString(R.string.motion_keywords),
                    screenTitle = screen,
                    action = MotionActivity.ACTION_MOTION,
                )
            }
    }
}
