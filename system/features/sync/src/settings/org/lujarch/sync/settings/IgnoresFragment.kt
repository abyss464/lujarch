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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.showDialog
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.Pages

/**
 * A folder's ignore patterns (Syncthing's .stignore in the folder): the
 * lines as they are, edited as text. Syncthing checks them when they are
 * saved; #include reads only files inside the folder.
 */
class IgnoresFragment : SyncPageFragment() {
    private lateinit var folderId: String
    private lateinit var linesCategory: PreferenceCategory
    private lateinit var editPref: Preference
    private var lines: List<String>? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        folderId = requireArguments().getString(ARG_FOLDER) ?: ""
        val actions = screen.add(PreferenceCategory(prefContext)) { key = "actions" }
        editPref = actions.add(Preference(prefContext)) {
            key = "edit"
            setTitle(R.string.sync_ignores_edit)
            setIcon(R.drawable.ic_edit)
            isEnabled = false
            setOnPreferenceClickListener {
                edit()
                true
            }
        }
        linesCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "lines"
            setTitle(R.string.sync_ignores_category)
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_ignores_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            val current = attempt("ignores", R.string.error_load_ignores) { Syncthing.ignores(folderId) }
            lines = current
            render(current)
        }
    }

    private fun render(current: List<String>?) {
        editPref.isEnabled = current != null
        linesCategory.removeAll()
        val shown = current.orEmpty().filter { it.isNotBlank() }
        if (current != null && shown.isEmpty()) {
            linesCategory.add(Preference(prefContext)) {
                key = "none"
                setSummary(R.string.sync_ignores_none)
                isSelectable = false
                isIconSpaceReserved = false
            }
        }
        shown.take(MAX_SHOWN).forEachIndexed { i, line ->
            linesCategory.add(Preference(prefContext)) {
                key = "line:$i"
                title = line
                isSelectable = false
                isCopyingEnabled = true
                isIconSpaceReserved = false
            }
        }
        if (shown.size > MAX_SHOWN) {
            linesCategory.add(Preference(prefContext)) {
                key = "more"
                summary = resources.getQuantityString(R.plurals.sync_ignores_more, shown.size - MAX_SHOWN, shown.size - MAX_SHOWN)
                isSelectable = false
                isIconSpaceReserved = false
            }
        }
    }

    private fun edit() {
        val current = lines ?: return
        showDialog("sync-ignores") { d ->
            InputDialog.create(
                d.context, R.string.sync_ignores_category,
                message = getString(R.string.sync_ignores_message),
                initial = current.joinToString("\n"),
                singleLine = false,
                validate = { text ->
                    val list = split(text)
                    when {
                        list.size > MAX_LINES -> getString(R.string.sync_ignores_too_many, MAX_LINES)
                        list.any { it.length > MAX_LINE } -> getString(R.string.sync_ignores_too_long, MAX_LINE)
                        else -> null
                    }
                },
            ) { text ->
                val list = split(text)
                if (list != current) change(R.string.error_sync_ignores, { Syncthing.setIgnores(folderId, list) })
            }
        }
    }

    /** Lines without trailing blanks at the end of the file. */
    private fun split(text: String): List<String> = text.lines().dropLastWhile { it.isBlank() }

    companion object {
        const val ARG_FOLDER = "folder"
        private const val MAX_SHOWN = 200
        private const val MAX_LINES = 4096
        private const val MAX_LINE = 1024

        fun open(context: Context, id: String) {
            Pages.open(context, IgnoresFragment::class.java, R.string.sync_ignores_title,
                arguments = Bundle().apply { putString(ARG_FOLDER, id) })
        }
    }
}
