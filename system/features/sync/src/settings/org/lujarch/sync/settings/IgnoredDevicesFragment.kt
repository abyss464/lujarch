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

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.ZeroStatePreference

/** Devices whose requests Syncthing ignores; each can be allowed to ask again. */
class IgnoredDevicesFragment : SyncPageFragment() {
    private lateinit var list: PreferenceCategory
    private lateinit var emptyPref: ZeroStatePreference

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        list = screen.add(PreferenceCategory(prefContext)) { key = "devices" }
        emptyPref = ZeroStatePreference(prefContext).apply {
            key = "empty"
            setIcon(R.drawable.ic_block)
            setTitle(R.string.sync_ignored_none)
            isVisible = false
        }
        screen.addPreference(emptyPref)
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_ignored_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            val devices = attempt("ignored", R.string.error_load_sync) { Syncthing.ignoredDevices() } ?: return@launchUi
            list.removeAll()
            devices.forEach { d ->
                val name = d.name.ifEmpty { d.id.substringBefore('-') }
                list.add(Preference(prefContext)) {
                    key = "device:" + d.id
                    title = name
                    summary = listOf(SyncFormat.twoLines(d.id), d.address).filter { it.isNotEmpty() }.joinToString("\n")
                    setIcon(R.drawable.ic_device)
                    setOnPreferenceClickListener {
                        askUnignore(d.id, name)
                        true
                    }
                }
            }
            list.isVisible = devices.isNotEmpty()
            emptyPref.isVisible = devices.isEmpty()
        }
    }

    private fun askUnignore(id: String, name: String) = confirm(
        "sync-unignore", R.string.sync_unignore_title, { getString(R.string.sync_unignore_message, name) }, R.string.sync_unignore,
    ) {
        change(R.string.error_sync_change, { Syncthing.unignoreDevice(id) })
    }
}
