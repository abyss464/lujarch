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

package org.lujarch.windows.settings

import android.app.INotificationManager
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.ConversationChannelWrapper
import android.util.Log
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity
import org.lujarch.settings.pages.BasePreferenceFragment

/**
 * The switches of small windows (target/windows.md), each a Settings.Secure value of the current
 * user (settings.toml). Use small windows is Android's own bubbles switch.
 */
internal enum class Switch(
    val key: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
) {
    RECENTS("lujarch_windows_recents", R.string.windows_recents, R.string.windows_recents_summary),
    NOTIFICATIONS(
        "lujarch_windows_notifications",
        R.string.windows_notifications,
        R.string.windows_notifications_summary,
    ),
    HOME("lujarch_windows_home", R.string.windows_home, R.string.windows_home_summary),
    DOCK_ON_TOUCH(
        "lujarch_windows_dock_on_touch",
        R.string.windows_dock_on_touch,
        R.string.windows_dock_on_touch_summary,
    ),
    LIVE_PREVIEWS(
        "lujarch_windows_live_previews",
        R.string.windows_live_previews,
        R.string.windows_live_previews_summary,
    );

    fun isOn(context: Context): Boolean = SmallWindows.get(context, key, 1) != 0

    fun set(context: Context, on: Boolean): Boolean = SmallWindows.put(context, key, if (on) 1 else 0)
}

internal object SmallWindows {
    const val TAG = "lujarch_windows"
    const val ACTION = "org.lujarch.windows.action.SMALL_WINDOWS"
    const val KEY_ENABLED = Settings.Secure.NOTIFICATION_BUBBLES
    const val KEY_MAX = "lujarch_windows_max"

    fun get(context: Context, key: String, default: Int): Int =
        Settings.Secure.getInt(context.contentResolver, key, default)

    fun put(context: Context, key: String, value: Int): Boolean =
        try {
            Settings.Secure.putInt(context.contentResolver, key, value)
        } catch (e: SecurityException) {
            Log.e(TAG, "$key not written", e)
            false
        }

    fun enabled(context: Context): Boolean = get(context, KEY_ENABLED, 1) != 0

    /** At most, 2 to 4; a stored value outside shows as the nearest. */
    fun max(context: Context): Int = get(context, KEY_MAX, 4).coerceIn(2, 4)
}

/** Settings > Display > Small windows (WIN-14). */
class SmallWindowsActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.windows_title)

    override fun createFragment(): Fragment = SmallWindowsFragment()
}

/**
 * Every switch of small windows: Use small windows (WIN-1), the entries (WIN-10, WIN-11, WIN-12),
 * At most (WIN-8), Move to dock when the app below is touched (WIN-3), Live previews in the dock
 * (WIN-7), and the apps with conversations, each with its choice, leading to its own page (WIN-13).
 */
class SmallWindowsFragment : BasePreferenceFragment() {
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var maxPref: ListPreference
    private lateinit var appsCategory: PreferenceCategory
    private val switches = HashMap<Switch, SwitchPreferenceCompat>()
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                ifPageAlive { bind() }
            }
        }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        mainSwitch = screen.add(MainSwitchPreference(prefContext)) {
            key = SmallWindows.KEY_ENABLED
            setTitle(R.string.windows_use)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                SmallWindows.put(prefContext, SmallWindows.KEY_ENABLED, if (value as Boolean) 1 else 0)
            }
        }
        val open = screen.add(PreferenceCategory(prefContext)) {
            key = "open"
            setTitle(R.string.windows_open_from)
        }
        for (s in listOf(Switch.RECENTS, Switch.NOTIFICATIONS, Switch.HOME)) addSwitch(open, s)
        val behavior = screen.add(PreferenceCategory(prefContext)) {
            key = "behavior"
            setTitle(R.string.windows_behavior)
        }
        maxPref = behavior.add(ListPreference(prefContext)) {
            key = SmallWindows.KEY_MAX
            setTitle(R.string.windows_max)
            isPersistent = false
            entries = arrayOf("2", "3", "4")
            entryValues = arrayOf("2", "3", "4")
            setDialogTitle(R.string.windows_max)
            setOnPreferenceChangeListener { _, value ->
                SmallWindows.put(prefContext, SmallWindows.KEY_MAX, (value as String).toInt()).also {
                    if (it) bind()
                }
            }
        }
        addSwitch(behavior, Switch.DOCK_ON_TOUCH)
        addSwitch(behavior, Switch.LIVE_PREVIEWS)
        appsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "conversations"
            setTitle(R.string.windows_conversations)
            isOrderingAsAdded = false
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.windows_footer)
        }
        bind()
    }

    private fun addSwitch(category: PreferenceCategory, s: Switch) {
        switches[s] = category.add(SwitchPreferenceCompat(prefContext)) {
            key = s.key
            isPersistent = false
            setTitle(s.title)
            setSummary(s.summary)
            setOnPreferenceChangeListener { _, value -> s.set(prefContext, value as Boolean) }
        }
    }

    override fun onResume() {
        super.onResume()
        val resolver = requireContext().contentResolver
        for (key in listOf(SmallWindows.KEY_ENABLED, SmallWindows.KEY_MAX) + Switch.entries.map { it.key }) {
            resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
        }
        bind()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        requireContext().contentResolver.unregisterContentObserver(observer)
    }

    private fun bind() {
        val context = context ?: return
        val enabled = SmallWindows.enabled(context)
        mainSwitch.isChecked = enabled
        for ((s, pref) in switches) {
            pref.isChecked = s.isOn(context)
            pref.isEnabled = enabled
        }
        val max = SmallWindows.max(context)
        maxPref.value = max.toString()
        maxPref.summary =
            resources.getQuantityString(R.plurals.windows_max_summary, max, max)
        maxPref.isEnabled = enabled
        appsCategory.isEnabled = enabled
    }

    /** The apps with conversations and their choice (WIN-13). */
    override fun refresh() {
        launchUi {
            val apps = attempt("apps", R.string.windows_error_apps) { loadApps(requireContext()) } ?: return@launchUi
            appsCategory.removeAll()
            for ((i, app) in apps.withIndex()) {
                appsCategory.add(Preference(prefContext)) {
                    key = "app_${app.packageName}_${app.uid}"
                    order = i
                    title = app.label
                    icon = app.icon
                    setSummary(choiceText(app.choice))
                    setOnPreferenceClickListener {
                        openAppPage(app)
                        true
                    }
                }
            }
            if (apps.isEmpty()) {
                appsCategory.add(Preference(prefContext)) {
                    key = "none"
                    setTitle(R.string.windows_conversations_none)
                    isSelectable = false
                }
            }
        }
    }

    private fun openAppPage(app: ConversationApp) {
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                .putExtra(EXTRA_APP_UID, app.uid)
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            toastError(R.string.windows_error_app_page, e)
        }
    }

    private class ConversationApp(
        val packageName: String,
        val uid: Int,
        val label: CharSequence,
        val icon: android.graphics.drawable.Drawable?,
        val choice: Int,
    )

    private suspend fun loadApps(context: Context): List<ConversationApp> =
        withContext(Dispatchers.IO) {
            val nm =
                INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE))
            val pm = context.packageManager
            val seen = LinkedHashMap<String, Int>()
            val conversations = nm.getConversations(false).list as List<*>
            for (c in conversations.filterIsInstance<ConversationChannelWrapper>()) {
                seen.putIfAbsent(c.pkg, c.uid)
            }
            val collator = Collator.getInstance()
            seen.mapNotNull { (pkg, uid) ->
                if (UserHandle.getUserId(uid) != UserHandle.myUserId()) return@mapNotNull null
                val info =
                    try {
                        pm.getApplicationInfo(pkg, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        return@mapNotNull null
                    }
                ConversationApp(pkg, uid, info.loadLabel(pm), info.loadIcon(pm),
                    nm.getBubblePreferenceForPackage(pkg, uid))
            }.sortedWith { a, b -> collator.compare(a.label.toString(), b.label.toString()) }
        }

    private fun choiceText(choice: Int): Int =
        when (choice) {
            NotificationManager.BUBBLE_PREFERENCE_ALL -> R.string.windows_choice_all
            NotificationManager.BUBBLE_PREFERENCE_SELECTED -> R.string.windows_choice_selected
            else -> R.string.windows_choice_none
        }

    private companion object {
        const val EXTRA_APP_UID = "app_uid"
    }
}

/** The page and each of its switches in Settings search (CORE-5). */
class SmallWindowsContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = context.getString(R.string.windows_title)
        val keywords = context.getString(R.string.windows_keywords)
        fun entry(key: String, @StringRes title: Int, @StringRes summary: Int) =
            SearchEntry(
                key = key,
                title = context.getString(title),
                summary = context.getString(summary),
                keywords = keywords,
                screenTitle = screen,
                action = SmallWindows.ACTION,
            )
        return listOf(
            SearchEntry(
                key = "lujarch_windows",
                title = screen,
                summary = context.getString(R.string.windows_summary),
                keywords = keywords,
                screenTitle = context.getString(R.string.windows_display),
                action = SmallWindows.ACTION,
            ),
            entry(SmallWindows.KEY_ENABLED, R.string.windows_use, R.string.windows_summary),
            entry(SmallWindows.KEY_MAX, R.string.windows_max, R.string.windows_max_search),
        ) + Switch.entries.map { entry(it.key, it.title, it.summary) }
    }
}

/**
 * The row in Settings > Display: its live summary (`com.android.settings.summary_uri`, method
 * `getDynamicSummary`). Only Settings (the system uid) and this app are answered.
 */
class SmallWindowsSummaryProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            Log.w(SmallWindows.TAG, "the Display row's summary refused to uid $uid")
            return null
        }
        if (method != METHOD_GET_SUMMARY) return null
        val context = context ?: return null
        val identity = Binder.clearCallingIdentity()
        return try {
            val text =
                if (SmallWindows.enabled(context)) R.string.windows_row_on else R.string.windows_row_off
            Bundle().apply { putString(META_SUMMARY, context.getString(text)) }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val METHOD_GET_SUMMARY = "getDynamicSummary"
        const val META_SUMMARY = "com.android.settings.summary"
    }
}
