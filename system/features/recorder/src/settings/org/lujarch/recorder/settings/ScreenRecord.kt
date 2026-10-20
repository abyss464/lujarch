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

package org.lujarch.recorder.settings

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import org.lujarch.recorder.Choice
import org.lujarch.recorder.Count
import org.lujarch.recorder.Entry
import org.lujarch.recorder.Group
import org.lujarch.recorder.Level
import org.lujarch.recorder.R
import org.lujarch.recorder.RecorderConfig
import org.lujarch.recorder.RecorderPage
import org.lujarch.recorder.RecorderProfiles
import org.lujarch.recorder.RecorderState
import org.lujarch.recorder.Setting
import org.lujarch.recorder.Toggle
import org.lujarch.recorder.settings.R as SettingsR
import org.lujarch.recorder.profileNameProblem
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.showDialog

private const val TAG = RecorderConfig.TAG
private const val ACTION = "org.lujarch.recorder.action.SCREEN_RECORD"
private const val ARG_PAGE = "page"

/** Settings > System > Screen record (REC-1 to REC-6). */
class ScreenRecordActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.rec_title)

    override fun createFragment(): Fragment = ScreenRecordFragment()
}

/** A page of screen record's settings, shown again whenever one of them changes. */
abstract class RecorderFragment : BasePreferenceFragment() {
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (uri?.lastPathSegment?.startsWith(RecorderConfig.PREFIX) != false) {
                    ifPageAlive { bind() }
                }
            }
        }

    protected abstract fun bind()

    override fun onResume() {
        super.onResume()
        requireContext().contentResolver.registerContentObserver(
            Settings.Secure.CONTENT_URI, true, observer)
        bind()
    }

    override fun onPause() {
        super.onPause()
        requireContext().contentResolver.unregisterContentObserver(observer)
    }

    protected fun refuse(problem: String) {
        Toast.makeText(prefContext, problem, Toast.LENGTH_LONG).show()
        Log.i(TAG, "refused: $problem")
    }

    /** A text dialog titled [title]; [find] finds what it edits on the page that is there. */
    protected fun <P : RecorderFragment> P.ask(
        key: String,
        title: String,
        initial: String,
        hint: String?,
        numeric: Boolean,
        neutral: Pair<Int, P.() -> Unit>? = null,
        validate: P.(String) -> String?,
        write: P.(String) -> Boolean,
    ) {
        showDialog("entry_$key") { d ->
            InputDialog.create(
                d.context,
                R.string.rec_title,
                initial = initial,
                hint = hint,
                numeric = numeric,
                neutral = neutral?.first,
                onNeutral = neutral?.let { n -> { n.second(this) } },
                validate = { validate(it) },
                onDone = { if (!write(it)) Log.e(TAG, "$key not written") },
            ).apply { setTitle(title) }
        }
    }
}

/**
 * Settings > System > Screen record: what a recording takes and gets with the profiles, then its
 * parts, each a page.
 */
class ScreenRecordFragment : RecorderFragment() {
    private lateinit var summary: RecorderSummaryPreference
    private val parts = HashMap<String, Preference>()

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        summary = screen.add(RecorderSummaryPreference(prefContext)) { key = "summary" }
        val group = screen.add(PreferenceCategory(prefContext)) {
            key = "parts"
            setTitle(R.string.rec_settings)
        }
        for (page in RecorderPage.entries) {
            parts[page.name] = group.add(Preference(prefContext)) {
                key = page.name.lowercase()
                setTitle(page.title)
                setIcon(page.icon)
                setOnPreferenceClickListener {
                    Pages.open(prefContext, PageFragment::class.java, page.title,
                        arguments = Bundle().apply { putString(ARG_PAGE, page.name) })
                    true
                }
            }
        }
        parts["profiles"] = group.add(Preference(prefContext)) {
            key = "profiles"
            setTitle(R.string.rec_profiles)
            setIcon(R.drawable.rec_ic_profiles)
            setOnPreferenceClickListener {
                Pages.open(prefContext, ProfilesFragment::class.java, R.string.rec_profiles)
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.rec_footer)
        }
    }

    override fun bind() {
        val s = RecorderState(prefContext)
        summary.show(s) { name ->
            if (!s.useProfile(name)) refuse(getString(R.string.rec_error_profile))
        }
        for (page in RecorderPage.entries) {
            val (first, second) = s.summary(page)
            parts[page.name]?.summary = "$first · $second"
        }
        parts["profiles"]?.summary =
            s.profiles().joinToString(getString(R.string.rec_list_separator)) { it.second }
    }
}

/** The size a minute takes, the line of what a recording gets, and the profiles to choose. */
class RecorderSummaryPreference(context: Context) : Preference(context) {
    private var state: RecorderState? = null
    private var pick: (String) -> Unit = {}
    private var bound: PreferenceViewHolder? = null

    init {
        layoutResource = SettingsR.layout.rec_summary
        isSelectable = false
    }

    fun show(s: RecorderState, onPick: (String) -> Unit) {
        state = s
        pick = onPick
        // The card shown is updated in place, so that its chips move from what they were.
        val holder = bound
        if (holder != null && holder.itemView.isAttachedToWindow) render(holder) else notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        bound = holder
        render(holder)
    }

    private fun render(holder: PreferenceViewHolder) {
        val s = state ?: return
        val chosen = s.chosenProfile
        val profile =
            chosen?.let { name ->
                val label = RecorderProfiles.label(context, name)
                if (s.profileChanged) context.getString(R.string.rec_profile_changed, label) else label
            } ?: context.getString(R.string.rec_custom)
        (holder.findViewById(SettingsR.id.rec_size) as TextView).text = s.sizePerMinute()
        (holder.findViewById(SettingsR.id.rec_size_note) as TextView).text =
            context.getString(R.string.rec_per_minute) + " · " + profile
        (holder.findViewById(SettingsR.id.rec_spec) as TextView).apply {
            text = s.spec()
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val chips = holder.findViewById(SettingsR.id.rec_profiles) as ViewGroup
        val profiles = s.profiles()
        val old = (0 until chips.childCount).map { chips.getChildAt(it) as ProfileChip }
            .associateBy { it.tag as String }
        old.filterKeys { name -> profiles.none { it.first == name } }.values.forEach(chips::removeView)
        for ((i, pair) in profiles.withIndex()) {
            val (name, label) = pair
            val chip = old[name] ?: ProfileChip(context).also {
                it.tag = name
                chips.addView(it, i)
            }
            if (chips.indexOfChild(chip) != i) {
                chips.removeView(chip)
                chips.addView(chip, i)
            }
            val look =
                when {
                    name != chosen -> ProfileChip.Look.PLAIN
                    s.profileChanged -> ProfileChip.Look.CHANGED
                    else -> ProfileChip.Look.ON
                }
            chip.show(label, look)
            chip.contentDescription =
                if (look == ProfileChip.Look.CHANGED) context.getString(R.string.rec_profile_changed, label)
                else label
            chip.setOnClickListener { pick(name) }
        }
    }
}

/** Settings > System > Screen record > Video, Audio, File or Recording (REC-2 to REC-4, REC-6). */
class PageFragment : RecorderFragment() {
    private val page: RecorderPage
        get() = RecorderPage.valueOf(requireArguments().getString(ARG_PAGE)!!)
    private lateinit var screen: PreferenceScreen
    private val shown = HashMap<String, Preference>()

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        this.screen = screen
    }

    private fun find(key: String): Setting? = page.settings(prefContext).firstOrNull { it.key == key }

    override fun bind() {
        val settings = page.settings(prefContext)
        val keys = settings.map { it.key }.toSet()
        shown.filterKeys { it !in keys }.values.forEach { it.isVisible = false }
        var group: PreferenceGroup = screen
        for ((i, setting) in settings.withIndex()) {
            val pref = shown.getOrPut(setting.key) { create(setting) }
            pref.order = i
            pref.isVisible = true
            if (setting is Group) group = screen
            if (pref.parent == null) group.addPreference(pref)
            if (setting is Group) group = pref as PreferenceCategory
            update(pref, setting)
        }
    }

    private fun create(setting: Setting): Preference =
        when (setting) {
            is Choice -> ListPreference(prefContext).apply {
                isPersistent = false
                setOnPreferenceChangeListener { _, value ->
                    pick(setting.key, value as String)
                    false
                }
            }
            is Toggle -> SwitchPreferenceCompat(prefContext).apply {
                isPersistent = false
                setOnPreferenceChangeListener { _, value ->
                    (find(setting.key) as? Toggle)?.write?.invoke(value as Boolean) ?: false
                }
            }
            is Level -> SeekBarPreference(prefContext).apply {
                isPersistent = false
                showSeekBarValue = false
                updatesContinuously = false
            }
            is Count, is Entry -> Preference(prefContext).apply {
                setOnPreferenceClickListener {
                    edit(setting.key)
                    true
                }
            }
            is Group -> PreferenceCategory(prefContext)
        }.apply { key = RecorderConfig.PREFIX + setting.key }

    private fun update(pref: Preference, setting: Setting) {
        pref.title = setting.title
        when (setting) {
            is Choice -> (pref as ListPreference).apply {
                dialogTitle = setting.title
                entries = setting.options.map { o ->
                    listOfNotNull(o.label, o.tag).joinToString(" · ")
                }.toTypedArray()
                entryValues = setting.options.map { it.value }.toTypedArray()
                value = setting.selected?.value
                summary = setting.summary
            }
            is Toggle -> (pref as SwitchPreferenceCompat).apply {
                isEnabled = setting.available
                isChecked = setting.checked
                summary = setting.summary
            }
            is Level -> (pref as SeekBarPreference).apply {
                // With Automatic, the bar's lowest step is Automatic.
                val auto = setting.automatic != null
                min = if (auto) setting.min - setting.step else setting.min
                max = setting.max
                seekBarIncrement = setting.step
                value = if (auto && setting.value == 0) min else setting.value
                summary =
                    if (auto && setting.value == 0) setting.automatic else setting.label(setting.value)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
                    val n = v as Int
                    val l = find(setting.key) as? Level ?: return@OnPreferenceChangeListener false
                    l.write(if (auto && n < setting.min) 0 else n)
                }
            }
            is Count -> pref.summary =
                if (setting.noLimit != null && setting.value == 0) setting.noLimit
                else setting.label(setting.value)
            is Entry -> pref.summary = setting.summary
            is Group -> {}
        }
    }

    private fun pick(key: String, value: String) {
        val choice = find(key) as? Choice ?: return
        val option = choice.options.firstOrNull { it.value == value } ?: return
        when {
            option.problem != null -> refuse(option.problem!!)
            option.entry != null -> edit(key, option.entry!!.key)
            !choice.write(value) -> Log.e(TAG, "$key not written")
        }
    }

    /** Asks for the value of the setting [key], or of the entry [entry] of its choice. */
    private fun edit(key: String, entry: String? = null) {
        fun target(p: PageFragment): Setting? {
            val s = p.find(key)
            return if (entry == null) s
            else (s as? Choice)?.options?.firstNotNullOfOrNull { it.entry?.takeIf { e -> e.key == entry } }
        }
        when (val s = target(this)) {
            is Entry -> ask(entry ?: key, s.title, s.initial, s.hint, s.numeric,
                validate = { (target(this) as? Entry)?.validate?.invoke(it) },
                write = { (target(this) as? Entry)?.write?.invoke(it) ?: false })
            is Count -> ask(key, s.title, s.value.takeIf { it > 0 || s.noLimit == null }?.toString() ?: "",
                null, numeric = true,
                neutral = s.noLimit?.let {
                    R.string.rec_no_limit to { (target(this) as? Count)?.write?.invoke(0); Unit }
                },
                validate = { t -> (target(this) as? Count)?.problem(prefContext, t.toIntOrNull()) },
                write = { t -> (target(this) as? Count)?.write?.invoke(t.toInt()) ?: false })
            else -> {}
        }
    }
}

/** Settings > System > Screen record > Profiles (REC-5). */
class ProfilesFragment : RecorderFragment() {
    private lateinit var list: PreferenceCategory

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        list = screen.add(PreferenceCategory(prefContext)) {
            key = "profiles"
            setTitle(R.string.rec_profiles)
        }
        screen.add(Preference(prefContext)) {
            key = "save"
            setTitle(R.string.rec_profile_save)
            setOnPreferenceClickListener {
                ask("save", getString(R.string.rec_profile_save), "",
                    getString(R.string.rec_profile_name), numeric = false,
                    validate = { profileNameProblem(prefContext, it) },
                    write = { RecorderProfiles.saveCurrent(prefContext, it) })
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.rec_profiles_footer)
        }
    }

    override fun bind() {
        list.removeAll()
        val s = RecorderState(prefContext)
        for ((i, name) in RecorderProfiles.names(prefContext).withIndex()) {
            list.add(Preference(prefContext)) {
                key = "profile_$i"
                order = i
                title = RecorderProfiles.label(prefContext, name)
                summary =
                    when (name) {
                        RecorderProfiles.HIGH_QUALITY -> getString(R.string.rec_profile_high_summary)
                        RecorderProfiles.SMALL_FILE -> getString(R.string.rec_profile_small_summary)
                        else -> getString(R.string.rec_profile_saved_summary)
                    } + if (name == s.chosenProfile && !s.profileChanged) {
                        " · " + getString(R.string.rec_in_use)
                    } else ""
                setOnPreferenceClickListener {
                    actions(name)
                    true
                }
            }
        }
    }

    private fun actions(name: String) {
        showDialog("profile") { d ->
            val context = d.context
            val items =
                if (RecorderProfiles.isBuiltIn(name)) arrayOf(context.getString(R.string.rec_profile_use))
                else arrayOf(
                    context.getString(R.string.rec_profile_use),
                    context.getString(R.string.rec_profile_rename),
                    context.getString(R.string.rec_profile_delete),
                )
            AlertDialog.Builder(context)
                .setTitle(RecorderProfiles.label(context, name))
                .setItems(items) { _, which ->
                    when (which) {
                        0 ->
                            if (RecorderState(context).useProfile(name)) {
                                Toast.makeText(context, context.getString(R.string.rec_profile_used,
                                    RecorderProfiles.label(context, name)), Toast.LENGTH_SHORT).show()
                            } else {
                                refuse(context.getString(R.string.rec_error_profile))
                            }
                        1 ->
                            ask("rename", getString(R.string.rec_profile_rename), name,
                                getString(R.string.rec_profile_name), numeric = false,
                                validate = { profileNameProblem(prefContext, it) },
                                write = { RecorderProfiles.rename(prefContext, name, it) })
                        2 -> RecorderProfiles.delete(context, name)
                    }
                }
                .create()
        }
    }
}

/** The pages in Settings search (CORE-5). */
class ScreenRecordContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = context.getString(R.string.rec_title)
        val keywords = context.getString(R.string.rec_keywords)
        fun entry(key: String, title: Int, summary: Int) =
            SearchEntry(
                key = key,
                title = context.getString(title),
                summary = context.getString(summary),
                keywords = keywords,
                screenTitle = screen,
                action = ACTION,
            )
        return listOf(
            SearchEntry(
                key = "lujarch_recorder",
                title = screen,
                summary = context.getString(R.string.rec_summary),
                keywords = keywords,
                screenTitle = context.getString(R.string.rec_system),
                action = ACTION,
            ),
            entry("video", R.string.rec_video, R.string.rec_video_search),
            entry("audio", R.string.rec_audio, R.string.rec_audio_search),
            entry("file", R.string.rec_file, R.string.rec_file_search),
            entry("profiles", R.string.rec_profiles, R.string.rec_profiles_search),
            entry("recording", R.string.rec_recording, R.string.rec_recording_search),
        )
    }
}
