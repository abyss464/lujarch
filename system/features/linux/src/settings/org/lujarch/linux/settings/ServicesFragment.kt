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

package org.lujarch.linux.settings

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.ActionButtonsPreference
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.ZeroStatePreference
import org.lujarch.linux.ILinux
import org.lujarch.linux.LinuxService
import org.lujarch.settings.pages.OutputPreference
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/** Services (LNX-8): Linux's services with their state; each opens its own page. */
class ServicesFragment : LinuxPage() {
    private lateinit var category: PreferenceCategory
    private lateinit var empty: ZeroStatePreference
    private val prefs = HashMap<String, Preference>()

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        category = screen.add(PreferenceCategory(prefContext)) {
            key = "services"
            isOrderingAsAdded = false
        }
        empty = screen.add(ZeroStatePreference(prefContext)) {
            key = "empty"
            setIcon(R.drawable.ic_lnx_services)
            setTitle(R.string.lnx_services_none)
            isVisible = false
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.lnx_services_footer)
        }
    }

    override suspend fun load() {
        val list = attempt("services", R.string.lnx_error_load_services) { LinuxClient.call("listServices") { it.listServices() } }
        val context = context ?: return
        if (list == null) return
        val names = list.map { it.name }.toSet()
        prefs.keys.filter { it !in names }.forEach { name -> prefs.remove(name)?.let { category.removePreference(it) } }
        list.forEachIndexed { index, service ->
            val pref = prefs.getOrPut(service.name) {
                category.add(Preference(prefContext)) {
                    key = "service_${service.name}"
                    title = service.name
                    setOnPreferenceClickListener {
                        Pages.open(requireContext(), ServiceFragment::class.java, 0, service.name,
                            Bundle().apply { putString(ServiceFragment.ARG_NAME, service.name) })
                        true
                    }
                }
            }
            pref.order = index
            pref.summary = summary(context, service)
        }
        empty.isVisible = list.isEmpty()
    }

    companion object {
        fun summary(context: Context, s: LinuxService): String {
            val state = when {
                s.running -> context.getString(R.string.lnx_service_running, LinuxPage.date(context, s.since))
                s.wanted -> context.getString(R.string.lnx_service_restarting)
                s.lastExit >= 0 -> context.getString(R.string.lnx_service_ended, s.lastExit)
                else -> context.getString(R.string.lnx_service_stopped)
            }
            val start = context.getString(if (s.startsWithLinux) R.string.lnx_service_starts else R.string.lnx_service_not_starts)
            return state + context.getString(R.string.lnx_separator) + start
        }
    }
}

/** One of Linux's services (LNX-8): run or stop it, restart it, whether it starts with Linux, its log and its files. */
class ServiceFragment : LinuxPage() {
    private lateinit var name: String
    private lateinit var runPref: MainSwitchPreference
    private lateinit var statePref: Preference
    private lateinit var startsPref: SwitchPreferenceCompat
    private lateinit var buttons: ActionButtonsPreference
    private lateinit var logPref: OutputPreference
    private lateinit var filesCategory: PreferenceCategory
    private val filePrefs = HashMap<String, Preference>()
    private var service: LinuxService? = null
    private var busy = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        name = requireArguments().getString(ARG_NAME).orEmpty()
        runPref = screen.add(MainSwitchPreference(prefContext)) {
            key = "run"
            setTitle(R.string.lnx_service_run)
            isPersistent = false
            isEnabled = false
            setOnPreferenceChangeListener { _, value ->
                control(if (value as Boolean) ILinux.SERVICE_START else ILinux.SERVICE_STOP)
                false
            }
        }
        statePref = screen.add(Preference(prefContext)) {
            key = "state"
            setTitle(R.string.lnx_state_title)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        startsPref = screen.add(SwitchPreferenceCompat(prefContext)) {
            key = "starts"
            setTitle(R.string.lnx_service_starts_title)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                setStarts(value as Boolean)
                false
            }
        }
        buttons = screen.add(ActionButtonsPreference(prefContext)) {
            key = "buttons"
            setButton1Text(R.string.lnx_service_restart)
            setButton1Icon(R.drawable.ic_lnx_restart)
            setButton1OnClickListener { control(ILinux.SERVICE_RESTART) }
        }
        val log = screen.add(PreferenceCategory(prefContext)) {
            key = "log_category"
            setTitle(R.string.lnx_service_log)
        }
        logPref = log.add(OutputPreference(prefContext)) { key = "log" }
        filesCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "files"
            setTitle(R.string.lnx_service_files)
            isOrderingAsAdded = false
        }
    }

    override suspend fun load() {
        val list = attempt("service", R.string.lnx_error_load_services) { LinuxClient.call("listServices") { it.listServices() } }
        val context = context ?: return
        service = list?.firstOrNull { it.name == name }
        val s = service
        runPref.isEnabled = s != null && !busy
        runPref.isChecked = s?.running == true || s?.wanted == true
        statePref.summary = s?.let { ServicesFragment.summary(context, it) } ?: getString(R.string.lnx_error_no_service)
        startsPref.isEnabled = s != null && !busy
        startsPref.isChecked = s?.startsWithLinux == true
        buttons.setButton1Enabled(s != null && !busy)
        val log = attempt("log", R.string.lnx_error_load_services) { LinuxClient.call("readServiceLog") { it.readServiceLog(name) } }
        logPref.text = if (log.isNullOrEmpty()) getString(R.string.lnx_service_log_empty) else log
        val files = orNull("files") { LinuxClient.call("listServiceFiles") { it.listServiceFiles(name) } }.orEmpty()
        filePrefs.keys.filter { it !in files }.forEach { f -> filePrefs.remove(f)?.let { filesCategory.removePreference(it) } }
        files.forEachIndexed { index, file ->
            val pref = filePrefs.getOrPut(file) {
                filesCategory.add(Preference(prefContext)) {
                    key = "file_$file"
                    title = file
                    setIcon(R.drawable.ic_lnx_file)
                    setOnPreferenceClickListener {
                        showFile(file)
                        true
                    }
                }
            }
            pref.order = index
        }
        filesCategory.isVisible = files.isNotEmpty()
    }

    private fun control(action: String) {
        if (busy) return
        busy = true
        launchUi {
            act(R.string.lnx_error_service) { LinuxClient.call("controlService") { it.controlService(name, action) } }
            busy = false
            refresh()
        }
    }

    private fun setStarts(starts: Boolean) {
        if (busy) return
        busy = true
        launchUi {
            act(R.string.lnx_error_service) { LinuxClient.call("setServiceStartsWithLinux") { it.setServiceStartsWithLinux(name, starts) } }
            busy = false
            refresh()
        }
    }

    private fun showFile(file: String) {
        launchUi {
            val text = attempt("file", R.string.lnx_error_load_services) {
                LinuxClient.call("readServiceFile") { it.readServiceFile(name, file) }
            } ?: return@launchUi
            showDialog("lnx-file") { d -> textDialog(d.context, file, text) }
        }
    }

    companion object {
        const val ARG_NAME = "name"
    }
}
