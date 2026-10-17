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

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lujarch.linux.ILinux
import org.lujarch.linux.RemoteStatus
import org.lujarch.linux.Status
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.PageRequest
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * System > Linux development environment: the switch and the state (LNX-1), the sessions, the
 * root file system and its install (LNX-2), the terminal's choices (LNX-3), Root sessions (LNX-4),
 * Memory and Swap (LNX-5), the desktop (LNX-6), the parts of the phone Linux is lent (LNX-7), and
 * the rows of Services (LNX-8) and Remote access (LNX-9).
 */
class LinuxFragment : LinuxPage() {
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var statePref: Preference
    private lateinit var sessionsCategory: PreferenceCategory
    private lateinit var installPref: Preference
    private lateinit var actionPref: Preference
    private lateinit var setupPref: Preference
    private lateinit var openKindPref: Preference
    private lateinit var colorsPref: Preference
    private lateinit var keysRowPref: SwitchPreferenceCompat
    private lateinit var rootPref: SwitchPreferenceCompat
    private lateinit var memoryPref: Preference
    private lateinit var swapPref: SwitchPreferenceCompat
    private lateinit var desktopPref: Preference
    private lateinit var desktopInstallPref: Preference
    private lateinit var servicesPref: Preference
    private lateinit var remotePref: Preference
    private val lendPrefs = LinkedHashMap<String, SwitchPreferenceCompat>()
    private val sessionPrefs = HashMap<String, Preference>()

    private var status: Status? = null
    private var remote: RemoteStatus? = null
    private var busy = false

    private val archiveFor = PageRequest(this, "lnx-archive")
    private val pickArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (archiveFor.take() == null || uri == null) return@registerForActivityResult
        askUser(uri.toString())
    }
    private val colorsFor = PageRequest(this, "lnx-colors")
    private val pickColors = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (colorsFor.take() == null || uri == null) return@registerForActivityResult
        setOwnColors(uri)
    }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        mainSwitch = screen.add(MainSwitchPreference(prefContext)) {
            key = KEY_ENABLED
            setTitle(R.string.lnx_use)
            isPersistent = false
            isEnabled = false
            setOnPreferenceChangeListener { _, value ->
                setSwitch("enabled", value as Boolean, R.string.lnx_error_switch)
                false
            }
        }
        statePref = screen.add(Preference(prefContext)) {
            key = "state"
            setTitle(R.string.lnx_state_title)
            setSummary(PagesR.string.loading)
            setOnPreferenceClickListener {
                status?.reason?.let { reason -> showText(R.string.lnx_state_failed, reason) }
                true
            }
        }

        sessionsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "sessions"
            setTitle(R.string.lnx_sessions)
            isOrderingAsAdded = false
        }
        sessionsCategory.add(Preference(prefContext)) {
            key = "open_terminal"
            order = ORDER_LAST
            setTitle(R.string.lnx_open_terminal)
            setIcon(R.drawable.ic_lnx_terminal)
            setOnPreferenceClickListener {
                openTerminal(null)
                true
            }
        }

        val rootfs = screen.add(PreferenceCategory(prefContext)) {
            key = "rootfs"
            setTitle(R.string.lnx_rootfs)
        }
        installPref = rootfs.add(Preference(prefContext)) {
            key = KEY_INSTALL
            setTitle(R.string.lnx_install)
            setOnPreferenceClickListener {
                if (status?.actionRunning != true) {
                    archiveFor.start("archive")
                    try {
                        pickArchive.launch(arrayOf("*/*"))
                    } catch (e: ActivityNotFoundException) {
                        archiveFor.take()
                        toastError(R.string.lnx_error_install, e)
                    }
                }
                true
            }
        }
        actionPref = rootfs.add(Preference(prefContext)) {
            key = "action"
            setTitle(R.string.lnx_action)
            isVisible = false
            setOnPreferenceClickListener {
                val s = status
                if (s != null && !s.actionRunning && !s.actionOk && !s.actionMessage.isNullOrEmpty()) {
                    showText(R.string.lnx_action, s.actionMessage)
                }
                true
            }
        }
        setupPref = rootfs.add(Preference(prefContext)) {
            key = KEY_SETUP
            setTitle(R.string.lnx_setup_again)
            setSummary(R.string.lnx_setup_again_summary)
            setOnPreferenceClickListener {
                confirm("lnx-setup", R.string.lnx_setup_again, { getString(R.string.lnx_setup_again_message) },
                    R.string.lnx_setup_run) { runSetup() }
                true
            }
        }

        val terminal = screen.add(PreferenceCategory(prefContext)) {
            key = "terminal"
            setTitle(R.string.lnx_terminal)
        }
        openKindPref = terminal.add(Preference(prefContext)) {
            key = KEY_OPEN_KIND
            setTitle(R.string.lnx_open_kind)
            setOnPreferenceClickListener {
                val current = KINDS.indexOf(LinuxClient.secure(requireContext(), "open_kind") ?: ILinux.KIND_ARCH)
                choose("lnx-open-kind", R.string.lnx_open_kind, KINDS.map { kindLabel(it) }.toIntArray(), current) { i ->
                    setSecure("open_kind", KINDS[i])
                }
                true
            }
        }
        colorsPref = terminal.add(Preference(prefContext)) {
            key = KEY_COLORS
            setTitle(R.string.lnx_colors)
            setOnPreferenceClickListener {
                val current = COLORS.indexOf(LinuxClient.secure(requireContext(), "colors") ?: "system")
                choose("lnx-colors", R.string.lnx_colors, COLOR_LABELS, current) { i ->
                    if (COLORS[i] == "own") {
                        colorsFor.start("colors")
                        try {
                            pickColors.launch(arrayOf("*/*"))
                        } catch (e: ActivityNotFoundException) {
                            colorsFor.take()
                            toastError(R.string.lnx_error_colors, e)
                        }
                    } else {
                        setSecure("colors", COLORS[i])
                    }
                }
                true
            }
        }
        keysRowPref = terminal.add(SwitchPreferenceCompat(prefContext)) {
            key = KEY_KEYS_ROW
            setTitle(R.string.lnx_keys_row)
            setSummary(R.string.lnx_keys_row_summary)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                setSecure("keys_row", if (value as Boolean) "1" else "0")
                true
            }
        }
        rootPref = terminal.add(SwitchPreferenceCompat(prefContext)) {
            key = KEY_ROOT_SESSIONS
            setTitle(R.string.lnx_root_sessions)
            setSummary(R.string.lnx_root_sessions_summary)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                if (value as Boolean) {
                    LinuxAuth.authenticate(this@LinuxFragment, getString(R.string.lnx_root_sessions_auth),
                        onCancelled = { render() }) {
                        setSwitch("root_sessions", true, R.string.lnx_error_switch)
                    }
                } else {
                    setSwitch("root_sessions", false, R.string.lnx_error_switch)
                }
                false
            }
        }

        val memory = screen.add(PreferenceCategory(prefContext)) {
            key = "memory_category"
            setTitle(R.string.lnx_memory_category)
        }
        memoryPref = memory.add(Preference(prefContext)) {
            key = KEY_MEMORY
            setTitle(R.string.lnx_memory)
            setOnPreferenceClickListener {
                val current = MEMORY.indexOf(LinuxClient.global(requireContext(), "memory") ?: "balanced")
                choose("lnx-memory", R.string.lnx_memory, MEMORY_LABELS, current) { i ->
                    setGlobal("memory", MEMORY[i])
                }
                true
            }
        }
        swapPref = memory.add(SwitchPreferenceCompat(prefContext)) {
            key = KEY_SWAP
            setTitle(R.string.lnx_swap)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                setSwitch("swap", value as Boolean, R.string.lnx_error_switch)
                false
            }
        }

        val desktop = screen.add(PreferenceCategory(prefContext)) {
            key = "desktop"
            setTitle(R.string.lnx_desktop)
        }
        desktopPref = desktop.add(Preference(prefContext)) {
            key = KEY_DESKTOP
            setTitle(R.string.lnx_open_desktop)
            setSummary(R.string.lnx_open_desktop_summary)
            setIcon(R.drawable.ic_lnx_desktop)
            setOnPreferenceClickListener {
                try {
                    startActivity(Intent(LinuxClient.ACTION_DESKTOP))
                } catch (e: ActivityNotFoundException) {
                    toastError(R.string.lnx_open_desktop, e)
                }
                true
            }
        }
        desktopInstallPref = desktop.add(Preference(prefContext)) {
            key = KEY_DESKTOP_INSTALL
            setTitle(R.string.lnx_install_desktop)
            setOnPreferenceClickListener {
                confirm("lnx-desktop", R.string.lnx_install_desktop, { getString(R.string.lnx_install_desktop_message) },
                    R.string.lnx_install_desktop_run) { installDesktop() }
                true
            }
        }

        val lend = screen.add(PreferenceCategory(prefContext)) {
            key = "lend"
            setTitle(R.string.lnx_lend)
        }
        for ((part, texts) in LEND) {
            lendPrefs[part] = lend.add(SwitchPreferenceCompat(prefContext)) {
                key = KEY_LEND_PREFIX + part
                setTitle(texts.first)
                setSummary(texts.second)
                isPersistent = false
                setOnPreferenceChangeListener { _, value ->
                    lendPart(part, value as Boolean)
                    false
                }
            }
        }

        val more = screen.add(PreferenceCategory(prefContext)) { key = "more" }
        servicesPref = more.add(Preference(prefContext)) {
            key = KEY_SERVICES
            setTitle(R.string.lnx_services)
            setIcon(R.drawable.ic_lnx_services)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), ServicesFragment::class.java, R.string.lnx_services)
                true
            }
        }
        remotePref = more.add(Preference(prefContext)) {
            key = KEY_REMOTE
            setTitle(R.string.lnx_remote)
            setIcon(R.drawable.ic_lnx_remote)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), RemoteFragment::class.java, R.string.lnx_remote)
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.lnx_footer)
        }
    }

    override suspend fun load() {
        status = attempt("status", R.string.lnx_error_load) { LinuxClient.call("getStatus") { it.status } }
        remote = orNull("remote") { LinuxClient.call("getRemote") { it.remote } }
        render()
    }

    private fun render() {
        val context = context ?: return
        val s = status
        mainSwitch.isEnabled = s != null && !busy
        mainSwitch.isChecked = LinuxClient.on(context, "enabled")
        statePref.summary = when {
            s == null -> getString(R.string.lnx_status_unknown)
            s.state == ILinux.STATE_FAILED -> getString(R.string.lnx_state_failed_summary)
            s.state == ILinux.STATE_RUNNING && s.user != null -> getString(R.string.lnx_state_running_user, s.user)
            else -> stateLabel(context, s)
        }
        statePref.isSelectable = s?.state == ILinux.STATE_FAILED

        // Sessions.
        val sessions = s?.sessions.orEmpty().toList()
        val ids = sessions.map { it.id }.toSet()
        sessionPrefs.keys.filter { it !in ids }.forEach { id ->
            sessionPrefs.remove(id)?.let { sessionsCategory.removePreference(it) }
        }
        sessions.forEachIndexed { index, info ->
            val pref = sessionPrefs.getOrPut(info.id) {
                sessionsCategory.add(Preference(prefContext)) {
                    key = "session_${info.id}"
                    setOnPreferenceClickListener {
                        openTerminal(info.id)
                        true
                    }
                }
            }
            pref.order = index
            pref.title = info.name.ifEmpty { getString(kindLabel(info.kind)) }
            pref.summary = listOfNotNull(
                getString(kindLabel(info.kind)),
                if (info.busy) getString(R.string.lnx_session_busy, info.job ?: "") else null,
                if (info.awake) getString(R.string.lnx_session_awake) else null,
            ).joinToString(getString(R.string.lnx_separator))
        }
        sessionsCategory.isVisible = s?.state == ILinux.STATE_RUNNING

        // The root file system.
        installPref.isEnabled = s != null && !s.actionRunning
        installPref.summary = when {
            s == null -> null
            !s.installed -> getString(R.string.lnx_install_none)
            else -> buildString {
                append(getString(R.string.lnx_install_done, date(context, s.installedAt), s.user ?: ""))
                if (s.kept.isNotEmpty()) {
                    append("\n")
                    append(resources.getQuantityString(R.plurals.lnx_install_kept, s.kept.size, s.kept.size))
                }
            }
        }
        actionPref.isVisible = s?.action != null
        if (s?.action != null) {
            val what = getString(
                when (s.action) {
                    ILinux.ACTION_INSTALL -> R.string.lnx_action_install
                    ILinux.ACTION_DESKTOP -> R.string.lnx_action_desktop
                    else -> R.string.lnx_action_setup
                },
            )
            actionPref.summary = when {
                s.actionRunning && s.action == ILinux.ACTION_INSTALL && s.actionTotal > 0 ->
                    getString(R.string.lnx_action_running_progress, what, size(context, s.actionDone), size(context, s.actionTotal))
                s.actionRunning -> getString(R.string.lnx_action_running, what)
                s.actionOk -> getString(R.string.lnx_action_ok, what, date(context, s.actionTime))
                s.actionMessage.isNullOrEmpty() -> getString(R.string.lnx_action_interrupted, what, date(context, s.actionTime))
                else -> getString(R.string.lnx_action_failed, what, date(context, s.actionTime))
            }
            actionPref.isSelectable = !s.actionRunning && !s.actionOk && !s.actionMessage.isNullOrEmpty()
        }
        setupPref.isEnabled = s?.state == ILinux.STATE_RUNNING && !s.actionRunning

        // The terminal.
        openKindPref.summary = getString(kindLabel(LinuxClient.secure(context, "open_kind")))
        val colors = LinuxClient.secure(context, "colors") ?: "system"
        colorsPref.summary = getString(COLOR_LABELS[COLORS.indexOf(colors).coerceAtLeast(0)])
        keysRowPref.isChecked = LinuxClient.secure(context, "keys_row") != "0"
        rootPref.isChecked = LinuxClient.on(context, "root_sessions")
        rootPref.isEnabled = !busy

        // Memory and swap.
        val memory = LinuxClient.global(context, "memory") ?: "balanced"
        val choice = getString(MEMORY_LABELS[MEMORY.indexOf(memory).coerceAtLeast(0)])
        memoryPref.summary = when {
            s == null -> choice
            s.memoryLimit == 0L && s.memoryUsed > 0 -> getString(R.string.lnx_memory_used_unlimited, choice, size(context, s.memoryUsed))
            s.memoryLimit == 0L -> choice
            s.memoryUsed > 0 -> getString(R.string.lnx_memory_used, choice, size(context, s.memoryUsed), size(context, s.memoryLimit))
            else -> getString(R.string.lnx_memory_limit, choice, size(context, s.memoryLimit))
        }
        swapPref.isEnabled = !busy
        swapPref.isChecked = LinuxClient.on(context, "swap")
        swapPref.summary = when {
            s != null && s.swapOn -> getString(R.string.lnx_swap_on, size(context, s.swapSize))
            else -> getString(R.string.lnx_swap_summary)
        }
        desktopPref.isEnabled = s?.state == ILinux.STATE_RUNNING
        desktopInstallPref.isEnabled = s?.state == ILinux.STATE_RUNNING && !s.actionRunning
        desktopInstallPref.summary = getString(
            if (s?.desktopInstalled == true) R.string.lnx_install_desktop_done else R.string.lnx_install_desktop_none,
        )

        for ((part, pref) in lendPrefs) {
            pref.isChecked = LinuxClient.on(context, "lend_$part")
            pref.isEnabled = !busy
        }

        servicesPref.isEnabled = s?.installed == true
        remotePref.summary = remote?.let { RemoteFragment.summary(context, it) }
    }

    // ---- actions ----

    private fun setSwitch(name: String, on: Boolean, error: Int) {
        if (busy) return
        busy = true
        render()
        launchUi {
            act(error) { LinuxClient.setOn(requireContext(), name, on) }
            busy = false
            render()
            refresh()
        }
    }

    private fun setGlobal(name: String, value: String) {
        launchUi {
            act(R.string.lnx_error_switch) { LinuxClient.setGlobal(requireContext(), name, value) }
            render()
            refresh()
        }
    }

    private fun setSecure(name: String, value: String) {
        launchUi {
            act(R.string.lnx_error_switch) { LinuxClient.setSecure(requireContext(), name, value) }
            render()
        }
    }

    private fun lendPart(part: String, on: Boolean) {
        if (busy) return
        busy = true
        render()
        launchUi {
            act(R.string.lnx_error_switch) {
                if (part == "notifications") {
                    // The Terminal app's listener reads Android's notifications only while they are lent.
                    withContext(Dispatchers.IO) {
                        requireContext().getSystemService(NotificationManager::class.java)
                            ?.setNotificationListenerAccessGranted(LinuxClient.NOTIFICATIONS_LISTENER, on, true)
                    }
                }
                LinuxClient.setOn(requireContext(), "lend_$part", on)
            }
            busy = false
            render()
        }
    }

    private fun openTerminal(session: String?) {
        val intent = Intent(LinuxClient.ACTION_TERMINAL)
        if (session != null) intent.putExtra(LinuxClient.EXTRA_SESSION, session)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toastError(R.string.lnx_open_terminal, e)
        }
    }

    private fun runSetup() {
        launchUi {
            act(R.string.lnx_error_setup) { LinuxClient.call("runSetup") { it.runSetup() } }
            refresh()
        }
    }

    private fun installDesktop() {
        launchUi {
            act(R.string.lnx_error_desktop) { LinuxClient.call("installDesktop") { it.installDesktop() } }
            refresh()
        }
    }

    /** The owner's user in the system the archive installs. */
    private fun askUser(uri: String) {
        val initial = status?.user.orEmpty()
        showDialog("lnx-install-user") { d ->
            InputDialog.create(
                d.context, R.string.lnx_install_user,
                message = d.context.getString(R.string.lnx_install_user_message),
                initial = initial,
                validate = { name ->
                    if (USER.matches(name) && name != "root") null else d.context.getString(R.string.lnx_install_user_invalid)
                },
            ) { name -> confirmInstall(uri, name) }
        }
    }

    private fun confirmInstall(uri: String, user: String) {
        val installed = status?.installed == true
        if (!installed) {
            install(uri, user)
            return
        }
        confirm("lnx-install-replace", R.string.lnx_install, { getString(R.string.lnx_install_replace_message) },
            R.string.lnx_install_replace) { install(uri, user) }
    }

    private fun install(uri: String, user: String) {
        launchUi {
            act(R.string.lnx_error_install) {
                val pfd = withContext(Dispatchers.IO) { requireContext().contentResolver.openFileDescriptor(Uri.parse(uri), "r") }
                    ?: throw LinuxException(LinuxException.Kind.REMOTE, "open", uri, ILinux.ERROR_INVALID)
                pfd.use { fd -> LinuxClient.call("install") { it.install(fd, user) } }
                Log.i(TAG, "install started for $user")
            }
            refresh()
        }
    }

    private fun setOwnColors(uri: Uri) {
        launchUi {
            act(R.string.lnx_error_colors) {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytes(MAX_COLORS + 1)
                        if (bytes.size > MAX_COLORS) {
                            throw LinuxException(LinuxException.Kind.REMOTE, "colors", "larger than 64 KiB", ILinux.ERROR_INVALID)
                        }
                        String(bytes, Charsets.UTF_8)
                    }
                } ?: throw LinuxException(LinuxException.Kind.REMOTE, "colors", uri.toString(), ILinux.ERROR_INVALID)
                LinuxClient.call("setColors") { it.setColors(text) }
                LinuxClient.setSecure(requireContext(), "colors", "own")
            }
            render()
        }
    }

    private fun showText(title: Int, text: String) = showDialog("lnx-text") { d ->
        textDialog(d.context, d.context.getString(title), text)
    }

    companion object {
        // The rows' keys are also their search entries' keys, which Settings search names to
        // bring the row into view: unique among every feature's.
        const val KEY_ENABLED = "lujarch_linux_enabled"
        const val KEY_INSTALL = "lujarch_linux_install"
        const val KEY_SETUP = "lujarch_linux_setup"
        const val KEY_OPEN_KIND = "lujarch_linux_open_kind"
        const val KEY_COLORS = "lujarch_linux_colors"
        const val KEY_KEYS_ROW = "lujarch_linux_keys_row"
        const val KEY_ROOT_SESSIONS = "lujarch_linux_root_sessions"
        const val KEY_MEMORY = "lujarch_linux_memory"
        const val KEY_SWAP = "lujarch_linux_swap"
        const val KEY_DESKTOP = "lujarch_linux_desktop"
        const val KEY_DESKTOP_INSTALL = "lujarch_linux_desktop_install"
        const val KEY_SERVICES = "lujarch_linux_services"
        const val KEY_REMOTE = "lujarch_linux_remote"
        const val KEY_LEND_PREFIX = "lujarch_linux_lend_"
        private const val ORDER_LAST = 10_000
        private const val MAX_COLORS = 64 * 1024
        private val USER = Regex("^[a-z_][a-z0-9_-]{0,31}$")

        val KINDS = listOf(ILinux.KIND_ARCH, ILinux.KIND_ARCH_ROOT, ILinux.KIND_ANDROID_ROOT)
        val COLORS = listOf("system", "classic", "own")
        val COLOR_LABELS = intArrayOf(R.string.lnx_colors_system, R.string.lnx_colors_classic, R.string.lnx_colors_own)
        val MEMORY = listOf("android", "balanced", "linux", "unlimited")
        val MEMORY_LABELS = intArrayOf(
            R.string.lnx_memory_android, R.string.lnx_memory_balanced, R.string.lnx_memory_linux,
            R.string.lnx_memory_unlimited,
        )

        /** The lent parts: their switch's title and summary. */
        val LEND = linkedMapOf(
            "clipboard" to (R.string.lnx_lend_clipboard to R.string.lnx_lend_clipboard_summary),
            "notifications" to (R.string.lnx_lend_notifications to R.string.lnx_lend_notifications_summary),
            "sensors" to (R.string.lnx_lend_sensors to R.string.lnx_lend_sensors_summary),
            "usb" to (R.string.lnx_lend_usb to R.string.lnx_lend_usb_summary),
            "fingerprint" to (R.string.lnx_lend_fingerprint to R.string.lnx_lend_fingerprint_summary),
            "ir" to (R.string.lnx_lend_ir to R.string.lnx_lend_ir_summary),
            "nfc" to (R.string.lnx_lend_nfc to R.string.lnx_lend_nfc_summary),
            "microphone" to (R.string.lnx_lend_microphone to R.string.lnx_lend_microphone_summary),
            "camera" to (R.string.lnx_lend_camera to R.string.lnx_lend_camera_summary),
            "location" to (R.string.lnx_lend_location to R.string.lnx_lend_location_summary),
            "vibration" to (R.string.lnx_lend_vibration to R.string.lnx_lend_vibration_summary),
            "quick_settings" to (R.string.lnx_lend_quick_settings to R.string.lnx_lend_quick_settings_summary),
        )
    }
}
