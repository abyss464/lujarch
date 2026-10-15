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

package org.lujarch.sync.settings

import android.content.Context
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import org.lujarch.settings.pages.showDialog
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.Pages

/**
 * File versioning of one folder: none, trash can, simple or staggered, the
 * values each keeps, and where versions are kept (the folder's
 * .stversions, another place in the folder, or a directory of the sync
 * storage). External versioning is not offered: it runs a command.
 */
class VersioningFragment : SyncPageFragment() {
    private lateinit var folderId: String
    private val typePrefs = LinkedHashMap<String, SelectorWithWidgetPreference>()
    private lateinit var paramsCategory: PreferenceCategory
    private lateinit var keepPref: Preference
    private lateinit var cleanoutPref: Preference
    private lateinit var maxAgePref: Preference
    private lateinit var pathPref: Preference
    private var current: Versioning? = null
    private var folderLabel: String = ""

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        folderId = requireArguments().getString(ARG_FOLDER) ?: ""
        val types = screen.add(PreferenceCategory(prefContext)) { key = "types" }
        for ((type, title, summary) in listOf(
            Triple(Versioning.NONE, R.string.sync_versioning_none, R.string.sync_versioning_none_summary),
            Triple(Versioning.TRASHCAN, R.string.sync_versioning_trashcan, R.string.sync_versioning_trashcan_summary),
            Triple(Versioning.SIMPLE, R.string.sync_versioning_simple, R.string.sync_versioning_simple_summary),
            Triple(Versioning.STAGGERED, R.string.sync_versioning_staggered, R.string.sync_versioning_staggered_summary),
        )) {
            typePrefs[type] = types.add(SelectorWithWidgetPreference(prefContext)) {
                key = "type:$type"
                setTitle(title)
                setSummary(summary)
                isPersistent = false
                setOnClickListener { choose(type) }
            }
        }
        paramsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "params"
            setTitle(R.string.sync_versioning_params)
            isVisible = false
        }
        keepPref = paramsCategory.add(Preference(prefContext)) {
            key = "keep"
            setTitle(R.string.sync_versioning_keep)
            setOnPreferenceClickListener {
                editNumber(R.string.sync_versioning_keep, "keep", 5, 1)
                true
            }
        }
        cleanoutPref = paramsCategory.add(Preference(prefContext)) {
            key = "cleanout"
            setTitle(R.string.sync_versioning_cleanout)
            setOnPreferenceClickListener {
                editNumber(R.string.sync_versioning_cleanout, "cleanoutDays", 0, 0)
                true
            }
        }
        maxAgePref = paramsCategory.add(Preference(prefContext)) {
            key = "max_age"
            setTitle(R.string.sync_versioning_max_age)
            setOnPreferenceClickListener {
                editNumber(R.string.sync_versioning_max_age, "maxAge", 365, 0, days = true)
                true
            }
        }
        pathPref = paramsCategory.add(Preference(prefContext)) {
            key = "path"
            setTitle(R.string.sync_versioning_path)
            setOnPreferenceClickListener {
                choosePath()
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_versioning_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            val snap = attempt("sync", R.string.error_load_sync) { Syncthing.snapshot() } ?: return@launchUi
            val f = snap.folder(folderId) ?: return@launchUi
            current = f.versioning
            folderLabel = f.label
            render(requireContext(), f.versioning)
        }
    }

    private fun render(context: Context, v: Versioning) {
        typePrefs.forEach { (type, pref) -> pref.isChecked = type == v.type }
        paramsCategory.isVisible = v.type != Versioning.NONE
        keepPref.isVisible = v.type == Versioning.SIMPLE
        cleanoutPref.isVisible = v.type == Versioning.SIMPLE || v.type == Versioning.TRASHCAN
        maxAgePref.isVisible = v.type == Versioning.STAGGERED
        keepPref.summary = (v.params["keep"] ?: "5")
        cleanoutPref.summary = days(context, v.params["cleanoutDays"]?.toIntOrNull() ?: 0, R.string.sync_versioning_forever)
        val maxAge = v.params["maxAge"]?.toLongOrNull() ?: DEFAULT_MAX_AGE_S
        maxAgePref.summary = days(context, (maxAge / 86400).toInt(), R.string.sync_versioning_forever)
        pathPref.summary = pathLabel(context, v.path)
    }

    private fun pathLabel(context: Context, path: String): String = when {
        path.isEmpty() -> context.getString(R.string.sync_versioning_path_default)
        path.startsWith(Syncthing.FOLDERS_ROOT + "/") ->
            context.getString(R.string.sync_local_folder_value, path.removePrefix(Syncthing.FOLDERS_ROOT + "/"))
        else -> context.getString(R.string.sync_versioning_path_in_folder, path)
    }

    /** Where versions are kept: the default, a place in the folder, or a directory of the sync storage. */
    private fun choosePath() {
        val v = current ?: return
        showDialog("sync-versions-path") { d ->
            val items = arrayOf(
                getString(R.string.sync_versioning_path_default),
                getString(R.string.sync_versioning_path_storage),
                getString(R.string.sync_versioning_path_other),
            )
            androidx.appcompat.app.AlertDialog.Builder(d.context)
                .setTitle(R.string.sync_versioning_path)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> if (v.path.isNotEmpty()) set(v.copy(path = ""))
                        1 -> chooseStorageDir(v)
                        else -> editOtherPath(v)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun chooseStorageDir(v: Versioning) {
        launchUi {
            val dirs = attempt("dirs", R.string.error_load_dirs) { Syncthing.dirs() } ?: return@launchUi
            val snap = attempt("sync", R.string.error_load_sync) { Syncthing.snapshot() } ?: return@launchUi
            val used = snap.folders.mapNotNull { f -> f.dirName?.let { it to f.label } }.toMap()
            SyncDialogs.chooseDir(this@VersioningFragment, dirs, used,
                Syncthing.dirNameFor("$folderLabel versions", used.keys + dirs), null) { dir ->
                set(v.copy(path = Syncthing.FOLDERS_ROOT + "/" + dir))
            }
        }
    }

    private fun editOtherPath(v: Versioning) = showDialog("sync-versions-path-other") { d ->
        InputDialog.create(
            d.context, R.string.sync_versioning_path_other,
            message = getString(R.string.sync_versioning_path_other_message),
            initial = v.path.takeIf { !it.startsWith("/") } ?: ".stversions",
            validate = { text ->
                val parts = text.split('/')
                when {
                    text.isEmpty() -> ""
                    text.startsWith("/") || parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it } ->
                        getString(R.string.sync_versioning_path_invalid)
                    // Anything outside .stversions is synced with the folder's files.
                    parts.first() != ".stversions" -> getString(R.string.sync_versioning_path_synced)
                    else -> null
                }
            },
        ) { text -> set(v.copy(path = if (text == ".stversions") "" else text)) }
    }

    private fun days(context: Context, n: Int, @StringRes zero: Int): String =
        if (n <= 0) context.getString(zero) else context.resources.getQuantityString(R.plurals.sync_days, n, n)

    private fun choose(type: String) {
        val v = current ?: return
        if (v.type == type) return
        val params = when (type) {
            Versioning.TRASHCAN -> mapOf("cleanoutDays" to "0")
            Versioning.SIMPLE -> mapOf("keep" to "5", "cleanoutDays" to "0")
            Versioning.STAGGERED -> mapOf("maxAge" to DEFAULT_MAX_AGE_S.toString())
            else -> emptyMap()
        }
        set(Versioning(type, params, v.path))
    }

    private fun editNumber(@StringRes title: Int, param: String, fallback: Long, min: Long, days: Boolean = false) {
        val v = current ?: return
        val raw = v.params[param]?.toLongOrNull() ?: if (days) fallback * 86400 else fallback
        val shown = if (days) raw / 86400 else raw
        showDialog("sync-versions-number") { d ->
            InputDialog.create(
                d.context, title,
                message = getString(if (days) R.string.sync_versioning_days_message else R.string.sync_versioning_number_message),
                initial = shown.toString(),
                numeric = true,
                validate = { text ->
                    val n = text.toLongOrNull()
                    if (n == null || n < min || n > 100000) getString(R.string.sync_number_invalid) else null
                },
            ) { text ->
                val n = text.toLong()
                set(Versioning(v.type, v.params + (param to (if (days) n * 86400 else n).toString()), v.path))
            }
        }
    }

    private fun set(v: Versioning) {
        current = v
        render(requireContext(), v)
        change(R.string.error_sync_change, { Syncthing.setVersioning(folderId, v) })
    }

    companion object {
        const val ARG_FOLDER = "folder"
        private const val DEFAULT_MAX_AGE_S = 365L * 86400

        fun open(context: Context, id: String) {
            Pages.open(context, VersioningFragment::class.java, R.string.sync_versioning,
                arguments = Bundle().apply { putString(ARG_FOLDER, id) })
        }
    }
}
