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

package org.lujarch.permissions.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import org.lujarch.settings.pages.BaseActivity
import org.lujarch.settings.pages.BasePreferenceFragment

/** Settings > Apps > Defaults for new apps: the choices an app the user installs starts with. */
class NewAppDefaultsActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.lujarch_new_app_defaults)

    override fun createFragment(): Fragment = NewAppDefaultsFragment()
}

class NewAppDefaultsFragment : BasePreferenceFragment() {
    /** One row: its settings key, its title, the choices it offers and its value while unset. */
    private data class Row(
        val key: String,
        val title: CharSequence,
        val choices: List<Int>,
        val default: Int = AppOpsManager.MODE_DEFAULT,
    )

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val ctx = prefContext
        for (row in rows(ctx)) {
            val labels =
                row.choices.map {
                    getString(
                        if (row.key == "phone_info" && it == AppOpsManager.MODE_ALLOWED)
                            R.string.lujarch_basic_phone_info_only
                        else label(it)
                    )
                }
            screen.addPreference(
                ListPreference(ctx).apply {
                    key = PREFIX + row.key
                    title = row.title
                    isPersistent = false
                    entries = labels.toTypedArray()
                    entryValues = row.choices.map { it.toString() }.toTypedArray()
                    value = read(ctx, row.key, row.default).toString()
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    setOnPreferenceChangeListener { _, v -> write(ctx, row.key, (v as String).toInt()) }
                }
            )
        }
        screen.addPreference(
            MultiSelectListPreference(ctx).apply {
                key = PREFIX + NETWORK
                setTitle(R.string.lujarch_new_app_network)
                isPersistent = false
                entries = NETWORK_LABELS.map { getString(it) }.toTypedArray()
                entryValues = NETWORK_BITS.map { it.toString() }.toTypedArray()
                val bits = read(ctx, NETWORK, 0)
                values = NETWORK_BITS.filter { bits and it != 0 }.map { it.toString() }.toSet()
                setSummaryFromValues(this, values)
                setOnPreferenceChangeListener { p, v ->
                    @Suppress("UNCHECKED_CAST")
                    val chosen = v as Set<String>
                    setSummaryFromValues(p as MultiSelectListPreference, chosen)
                    write(ctx, NETWORK, chosen.sumOf { it.toInt() })
                }
            }
        )
        screen.addPreference(
            FooterPreference(ctx).apply {
                key = "lujarch_new_app_defaults_footer"
                setTitle(R.string.lujarch_new_app_defaults_footer)
            }
        )
    }

    private fun setSummaryFromValues(p: MultiSelectListPreference, values: Set<String>) {
        p.summary =
            if (values.isEmpty()) getString(R.string.lujarch_not_changed)
            else
                NETWORK_BITS.indices
                    .filter { NETWORK_BITS[it].toString() in values }
                    .joinToString(getString(R.string.lujarch_list_separator)) { getString(NETWORK_LABELS[it]) }
    }

    private fun rows(ctx: Context): List<Row> {
        val all = listOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_FOREGROUND, AppOpsManager.MODE_DEFAULT,
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED)
        val noForeground = all - AppOpsManager.MODE_FOREGROUND
        return listOf(
            Row("sensors", group(ctx, "LUJARCH_SENSORS"), all),
            Row("clipboard", group(ctx, "LUJARCH_CLIPBOARD"), noForeground - AppOpsManager.MODE_ERRORED),
            Row("installed_apps", group(ctx, "LUJARCH_INSTALLED_APPS"), noForeground),
            Row("device_ids", group(ctx, "LUJARCH_DEVICE_IDS"), noForeground),
            Row("device_info", group(ctx, "LUJARCH_DEVICE_INFO"), noForeground - AppOpsManager.MODE_ERRORED),
            Row("network_info", group(ctx, "LUJARCH_NETWORK_INFO"), noForeground),
            Row("phone_info", getString(R.string.lujarch_basic_phone_info), listOf(AppOpsManager.MODE_ALLOWED,
                AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_ERRORED)),
            Row("install", getString(R.string.lujarch_install_unknown_apps), listOf(AppOpsManager.MODE_ALLOWED,
                AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_ERRORED), NOT_CHANGED),
            Row("autostart", getString(R.string.lujarch_autostart), listOf(AppOpsManager.MODE_ALLOWED,
                AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_ERRORED)),
        ).map { it.copy(choices = listOf(NOT_CHANGED) + it.choices) }
    }

    private fun group(ctx: Context, name: String): CharSequence =
        try {
            ctx.packageManager.getPermissionGroupInfo("android.permission-group.$name", 0)
                .loadLabel(ctx.packageManager)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "$name is not defined", e)
            name
        }

    private fun label(mode: Int): Int =
        when (mode) {
            NOT_CHANGED -> R.string.lujarch_not_changed
            AppOpsManager.MODE_FOREGROUND -> R.string.lujarch_choice_foreground
            AppOpsManager.MODE_DEFAULT -> R.string.lujarch_choice_ask
            AppOpsManager.MODE_IGNORED -> R.string.lujarch_choice_empty
            AppOpsManager.MODE_ERRORED -> R.string.lujarch_choice_deny
            else -> R.string.lujarch_choice_allow
        }

    private fun read(ctx: Context, key: String, default: Int): Int =
        Settings.Secure.getInt(ctx.contentResolver, PREFIX + key, default)

    private fun write(ctx: Context, key: String, value: Int): Boolean =
        try {
            Settings.Secure.putInt(ctx.contentResolver, PREFIX + key, value)
        } catch (e: SecurityException) {
            Log.e(TAG, "${PREFIX + key} not written", e)
            false
        }

    companion object {
        private const val TAG = "lujarch_permissions"
        private const val PREFIX = "lujarch_permissions_new_app_"
        private const val NETWORK = "network"
        private const val NOT_CHANGED = -1

        // NetworkPolicyManager's POLICY_REJECT_WIFI, _CELLULAR, _VPN and _ALL.
        private val NETWORK_BITS = listOf(0x40000, 0x8000, 0x10000, 0x20000)
        private val NETWORK_LABELS =
            listOf(
                R.string.lujarch_new_app_no_network,
                R.string.lujarch_new_app_no_wifi,
                R.string.lujarch_new_app_no_mobile,
                R.string.lujarch_new_app_no_vpn,
            )
    }
}
