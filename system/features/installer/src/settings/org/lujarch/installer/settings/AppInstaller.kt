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

package org.lujarch.installer.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lujarch.installer.InstallFailures
import org.lujarch.installer.InstallHistory
import org.lujarch.installer.InstallOptions
import org.lujarch.installer.OptionControl
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.showDialog

private const val ACTION = "org.lujarch.installer.action.APP_INSTALLER"
private const val INSTALL_FROM_FILES = "org.lujarch.installer.action.INSTALL_FROM_FILES"
private const val PACKAGE_INSTALLER = "com.android.packageinstaller"

/** Settings > Apps > App installer (INS-3, INS-4, INS-6). */
class AppInstallerActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.ins_title)

    override fun createFragment(): Fragment = AppInstallerFragment()
}

/** Install from files, Default options, and the install history, the latest first. */
class AppInstallerFragment : BasePreferenceFragment() {
    private lateinit var history: HistoryCategory

    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                ifPageAlive { refresh() }
            }
        }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        screen.add(Preference(prefContext)) {
            key = "from_files"
            setTitle(R.string.ins_from_files)
            setSummary(R.string.ins_from_files_summary)
            setIcon(R.drawable.ins_ic_install)
            setOnPreferenceClickListener {
                try {
                    startActivity(Intent(INSTALL_FROM_FILES).setPackage(PACKAGE_INSTALLER))
                } catch (e: Exception) {
                    toastError(R.string.ins_error_from_files, e)
                }
                true
            }
        }
        screen.add(Preference(prefContext)) {
            key = "defaults"
            setTitle(R.string.ins_defaults)
            setSummary(R.string.ins_defaults_summary)
            setIcon(R.drawable.ins_ic_options)
            setOnPreferenceClickListener {
                Pages.open(prefContext, DefaultOptionsFragment::class.java, R.string.ins_defaults)
                true
            }
        }
        history = screen.add(HistoryCategory(prefContext)) {
            key = "history"
            setTitle(R.string.ins_history)
            onClear = ::askClear
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            requireContext().contentResolver.registerContentObserver(InstallHistory.INSTALLS, false, observer)
        } catch (e: SecurityException) {
            setError("history", R.string.ins_error_history, e)
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        requireContext().contentResolver.unregisterContentObserver(observer)
    }

    override fun refresh() {
        launchUi {
            val context = prefContext
            val rows = attempt("history", R.string.ins_error_history) { withContext(Dispatchers.IO) { load(context) } } ?: return@launchUi
            ifPageAlive { show(rows) }
        }
    }

    private class Row(
        val time: Long,
        val packageName: String,
        val label: String,
        val version: String,
        val installer: String,
        val kind: Int,
        val result: Int,
        val message: String?,
        val icon: Drawable?,
    )

    private fun load(context: Context): List<Row> {
        val pm = context.packageManager
        val labels = HashMap<String, String>()
        fun installerLabel(pkg: String?): String =
            if (pkg.isNullOrEmpty()) {
                context.getString(R.string.ins_installer_unknown)
            } else {
                labels.getOrPut(pkg) {
                    try {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        pkg
                    }
                }
            }
        val cursor =
            context.contentResolver.query(InstallHistory.INSTALLS, null, null, null, null)
                ?: throw IllegalStateException("${InstallHistory.INSTALLS} gave no cursor")
        return cursor.use { c ->
            val time = c.getColumnIndexOrThrow(InstallHistory.TIME)
            val pkg = c.getColumnIndexOrThrow(InstallHistory.PACKAGE)
            val label = c.getColumnIndexOrThrow(InstallHistory.LABEL)
            val name = c.getColumnIndexOrThrow(InstallHistory.VERSION_NAME)
            val code = c.getColumnIndexOrThrow(InstallHistory.VERSION_CODE)
            val installer = c.getColumnIndexOrThrow(InstallHistory.INSTALLER)
            val kind = c.getColumnIndexOrThrow(InstallHistory.KIND)
            val result = c.getColumnIndexOrThrow(InstallHistory.RESULT)
            val message = c.getColumnIndexOrThrow(InstallHistory.MESSAGE)
            val icons = HashMap<String, Drawable?>()
            buildList {
                while (c.moveToNext()) {
                    val p = c.getString(pkg)
                    add(
                        Row(
                            time = c.getLong(time),
                            packageName = p,
                            label = c.getString(label)?.takeIf { it.isNotBlank() } ?: p,
                            version = c.getString(name) ?: c.getLong(code).toString(),
                            installer = installerLabel(c.getString(installer)),
                            kind = c.getInt(kind),
                            result = c.getInt(result),
                            message = c.getString(message),
                            icon =
                                icons.getOrPut(p) {
                                    try {
                                        pm.getApplicationIcon(p)
                                    } catch (e: PackageManager.NameNotFoundException) {
                                        pm.defaultActivityIcon
                                    }
                                },
                        )
                    )
                }
            }
        }
    }

    private fun show(rows: List<Row>) {
        history.removeAll()
        history.canClear = rows.isNotEmpty()
        if (rows.isEmpty()) {
            history.add(Preference(prefContext)) {
                key = "empty"
                setTitle(R.string.ins_history_empty)
                isSelectable = false
            }
            return
        }
        rows.forEachIndexed { i, r ->
            val failed = r.result != 0
            history.add(HistoryRowPreference(prefContext, failed)) {
                key = "install_$i"
                title = getString(R.string.ins_history_line_title, r.label, r.version)
                summary =
                    getString(
                        R.string.ins_history_line,
                        Format.at(prefContext, r.time),
                        r.installer,
                        if (failed) InstallFailures.shortReason(prefContext, r.result) else InstallHistory.kindLabel(prefContext, r.kind),
                    )
                icon = r.icon
                if (failed) {
                    setOnPreferenceClickListener {
                        showFailure(r.label, r.result, r.message)
                        true
                    }
                } else {
                    isSelectable = false
                }
            }
        }
    }

    /** A failed install's reason in words, Android's code and the system's message (INS-5). */
    private fun showFailure(label: String, legacy: Int, message: String?) =
        showDialog("failure") { d ->
            val text = buildString {
                append(InstallFailures.reason(d.context, legacy))
                append("\n\n").append(getString(R.string.ins_failure_code, InstallFailures.code(legacy)))
                if (!message.isNullOrBlank()) append("\n\n").append(getString(R.string.ins_failure_message, message))
            }
            AlertDialog.Builder(d.context)
                .setTitle(label)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        }

    private fun askClear() =
        confirm("clear", R.string.ins_clear_history, { getString(R.string.ins_clear_history_message) }, R.string.ins_clear) {
            clear()
        }

    private fun clear() {
        launchUi {
            val resolver = prefContext.contentResolver
            if (act(R.string.ins_error_clear) { withContext(Dispatchers.IO) { resolver.delete(InstallHistory.INSTALLS, null, null) } }) {
                ifPageAlive { refresh() }
            }
        }
    }
}

/** The history's heading with Clear history at its end. */
class HistoryCategory(context: Context) : PreferenceCategory(context) {
    var onClear: () -> Unit = {}
    var canClear = false
        set(value) {
            if (field != value) {
                field = value
                notifyChanged()
            }
        }

    init {
        layoutResource = R.layout.ins_history_header
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.ins_clear)?.apply {
            visibility = if (canClear) View.VISIBLE else View.GONE
            setOnClickListener { onClear() }
        }
    }
}

/** One install of the history, with its result as a tag. */
class HistoryRowPreference(context: Context, private val failed: Boolean) : Preference(context) {
    init {
        widgetLayoutResource = R.layout.ins_result_tag
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.ins_result) as? TextView)?.apply {
            setText(if (failed) R.string.ins_result_failed else R.string.ins_result_ok)
            setTextColor(context.getColor(if (failed) R.color.ins_on_tag_bad else R.color.ins_on_tag_ok))
            background.mutate().setTint(context.getColor(if (failed) R.color.ins_tag_bad else R.color.ins_tag_ok))
        }
    }
}

/**
 * Settings > Apps > App installer > Default options: what the installer's options start from
 * (INS-3).
 */
class DefaultOptionsFragment : BasePreferenceFragment() {
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                ifPageAlive { bind() }
            }
        }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        for (c in InstallOptions.read(prefContext).controls(prefContext)) {
            when (c) {
                is OptionControl.Pick ->
                    screen.add(ListPreference(prefContext)) {
                        key = c.key
                        title = c.title
                        dialogTitle = c.title
                        isPersistent = false
                        entries = c.choices.map { it.second }.toTypedArray()
                        entryValues = c.choices.map { it.first.toString() }.toTypedArray()
                        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                        setOnPreferenceChangeListener { _, v -> write(c.key, v as String) }
                    }
                is OptionControl.Abi ->
                    screen.add(ListPreference(prefContext)) {
                        key = c.key
                        title = c.title
                        dialogTitle = c.title
                        isPersistent = false
                        entries = c.choices.map { it.second }.toTypedArray()
                        entryValues = c.choices.map { it.first }.toTypedArray()
                        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                        setOnPreferenceChangeListener { _, v -> write(c.key, v as String) }
                    }
                is OptionControl.Flag ->
                    screen.add(SwitchPreferenceCompat(prefContext)) {
                        key = c.key
                        title = c.title
                        summary = c.summary
                        isPersistent = false
                        setOnPreferenceChangeListener { _, v -> write(c.key, if (v as Boolean) "1" else "0") }
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().contentResolver.registerContentObserver(Settings.Secure.CONTENT_URI, true, observer)
        bind()
    }

    override fun onPause() {
        super.onPause()
        requireContext().contentResolver.unregisterContentObserver(observer)
    }

    private fun bind() {
        for (c in InstallOptions.read(prefContext).controls(prefContext)) {
            when (c) {
                is OptionControl.Pick -> findPreference<ListPreference>(c.key)?.value = c.value.toString()
                is OptionControl.Abi -> findPreference<ListPreference>(c.key)?.value = c.value
                is OptionControl.Flag -> findPreference<SwitchPreferenceCompat>(c.key)?.isChecked = c.checked
            }
        }
    }

    private fun write(key: String, value: String): Boolean {
        if (InstallOptions.write(prefContext, key, value)) return true
        toastError(R.string.ins_error_default, IllegalStateException("${InstallOptions.PREFIX}$key not written"))
        return false
    }
}

/** App installer's entries in Settings search. */
class AppInstallerContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = context.getString(R.string.ins_title)
        val keywords = context.getString(R.string.ins_keywords)
        fun entry(key: String, title: String, summary: String) =
            SearchEntry(key = key, title = title, summary = summary, keywords = keywords, screenTitle = screen, action = ACTION)
        return listOf(
            SearchEntry(
                key = "lujarch_installer",
                title = screen,
                summary = context.getString(R.string.ins_summary),
                keywords = keywords,
                screenTitle = context.getString(R.string.ins_apps),
                action = ACTION,
            ),
            entry("from_files", context.getString(R.string.ins_from_files), context.getString(R.string.ins_from_files_summary)),
            entry("defaults", context.getString(R.string.ins_defaults), context.getString(R.string.ins_defaults_summary)),
            entry("history", context.getString(R.string.ins_history), context.getString(R.string.ins_history_search)),
        )
    }
}
