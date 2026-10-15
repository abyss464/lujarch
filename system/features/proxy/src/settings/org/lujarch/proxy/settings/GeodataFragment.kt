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

package org.lujarch.proxy.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.Pages

/**
 * Country, domain and network lists (geodata): automatic updates and their
 * interval, which database country rules read and how it is loaded, and
 * where each database is downloaded from. The files themselves (dates,
 * replace from a file, update now) are in Configuration files.
 */
class GeodataFragment : KeysFragment() {
    override fun buildRows(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val updates = screen.add(PreferenceCategory(prefContext)) {
            key = "updates"
            setTitle(R.string.px_geo_updates)
        }
        switchRow(updates, "geo-auto-update", R.string.px_core_geo_auto, R.string.px_core_geo_auto_summary, default = false)
        numberRow(
            updates, "geo-update-interval", R.string.px_core_geo_interval, 1, 720, R.string.px_geo_hours_hint,
            format = { resources.getQuantityString(R.plurals.px_hours, it.toInt(), it.toInt()) },
        )

        val reading = screen.add(PreferenceCategory(prefContext)) {
            key = "reading"
            setTitle(R.string.px_geo_reading)
        }
        switchRow(reading, "geodata-mode", R.string.px_geo_mode, R.string.px_geo_mode_summary, default = false)
        listRow(
            reading, "geodata-loader", R.string.px_geo_loader,
            values = listOf("memconservative", "standard"),
            labels = listOf(getString(R.string.px_geo_loader_memconservative), getString(R.string.px_geo_loader_standard)),
            defaultLabel = getString(R.string.px_geo_loader_default),
        )

        val sources = screen.add(PreferenceCategory(prefContext)) {
            key = "sources"
            setTitle(R.string.px_geo_sources)
        }
        for ((key, title) in URL_KEYS) {
            urlRow(sources, key, title, R.string.px_geo_url_message, R.string.px_geo_url_hint) {
                getString(R.string.px_geo_url_default)
            }
        }
        sources.add(Preference(prefContext)) {
            key = "files"
            setTitle(R.string.px_core_geo_files)
            setSummary(R.string.px_core_geo_files_summary)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), ConfigFilesFragment::class.java, R.string.px_files_title)
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_geo_footer)
            order = Int.MAX_VALUE
        }
    }

    companion object {
        private val URL_KEYS = listOf(
            "geox-url.mmdb" to R.string.px_geo_url_mmdb,
            "geox-url.geoip" to R.string.px_geo_url_geoip,
            "geox-url.geosite" to R.string.px_geo_url_geosite,
            "geox-url.asn" to R.string.px_geo_url_asn,
        )
    }
}
