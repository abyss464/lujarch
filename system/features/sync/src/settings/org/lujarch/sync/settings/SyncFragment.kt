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

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.ZeroStatePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * Sync (Syncthing): the service switch, when it runs (Wi-Fi only, charging
 * only), this device (name, ID, identity), requests from other devices,
 * one row per folder and per device (each opens its own page), and the
 * options that apply to every device.
 */
class SyncFragment : SyncPageFragment() {
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var statePref: Preference
    private lateinit var namePref: Preference
    private lateinit var idPref: Preference
    private lateinit var identityPref: Preference
    private lateinit var storagePref: Preference
    private lateinit var conditionsCategory: PreferenceCategory
    private lateinit var wifiOnlyPref: SwitchPreferenceCompat
    private lateinit var chargingOnlyPref: SwitchPreferenceCompat
    private lateinit var pendingCategory: PreferenceCategory
    private lateinit var foldersCategory: PreferenceCategory
    private lateinit var devicesCategory: PreferenceCategory
    private lateinit var optionsCategory: PreferenceCategory
    private lateinit var pausePref: Preference
    private lateinit var ignoredPref: Preference
    private lateinit var emptyPref: ZeroStatePreference

    private var info: SyncInfo? = null
    private var identity: IdentityState? = null
    /** The sync storage's modes are as Syncthing, the vault and identity changes need them (the system service health). */
    private var storageOk: Boolean? = null
    private var snapshot: SyncSnapshot? = null
    private var conditions: SyncConditions? = null
    private var conditionsBusy = false
    private var switchBusy = false
    private var busy = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        mainSwitch = screen.add(MainSwitchPreference(prefContext)) {
            key = "enabled"
            setTitle(R.string.sync_main_switch)
            isPersistent = false
            isEnabled = false
            setOnPreferenceChangeListener { _, value ->
                toggleSync(value as Boolean)
                false
            }
        }
        val device = screen.add(PreferenceCategory(prefContext)) {
            key = "device"
            setTitle(R.string.sync_device_category)
        }
        statePref = device.add(Preference(prefContext)) {
            key = "state"
            setTitle(R.string.sync_service)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        namePref = device.add(Preference(prefContext)) {
            key = "device_name"
            setTitle(R.string.sync_device_name)
            isVisible = false
            setOnPreferenceClickListener {
                editName()
                true
            }
        }
        idPref = device.add(Preference(prefContext)) {
            key = "device_id"
            setTitle(R.string.sync_device_id)
            isVisible = false
            setOnPreferenceClickListener {
                snapshot?.myId?.let { SyncFormat.showDeviceId(this@SyncFragment, it) }
                true
            }
        }
        identityPref = device.add(Preference(prefContext)) {
            key = "identity"
            setTitle(R.string.sync_identity_title)
            setIcon(R.drawable.ic_key)
            setSummary(PagesR.string.loading)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), IdentityFragment::class.java, R.string.sync_identity_title)
                true
            }
        }
        // Shown only while the sync storage's modes are wrong (the system service's
        // health): Syncthing, the vault and identity changes depend on them.
        storagePref = device.add(Preference(prefContext)) {
            key = "storage"
            setTitle(R.string.sync_storage_title)
            setSummary(R.string.sync_storage_bad)
            setIcon(R.drawable.ic_error)
            isSelectable = false
            isVisible = false
        }
        conditionsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "conditions"
            setTitle(R.string.sync_conditions_category)
        }
        wifiOnlyPref = conditionsCategory.add(SwitchPreferenceCompat(prefContext)) {
            key = "wifi_only"
            setTitle(R.string.sync_wifi_only)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                setConditions(wifiOnly = value as Boolean)
                false
            }
        }
        chargingOnlyPref = conditionsCategory.add(SwitchPreferenceCompat(prefContext)) {
            key = "charging_only"
            setTitle(R.string.sync_charging_only)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                setConditions(chargingOnly = value as Boolean)
                false
            }
        }
        pendingCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "pending"
            setTitle(R.string.sync_pending_category)
            isOrderingAsAdded = false
            isVisible = false
        }
        foldersCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "folders"
            setTitle(R.string.sync_folders_category)
            isOrderingAsAdded = false
            isVisible = false
        }
        foldersCategory.add(Preference(prefContext)) {
            key = "add_folder"
            order = ORDER_ADD
            setTitle(R.string.sync_add_folder)
            setIcon(R.drawable.ic_add)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), AddFolderFragment::class.java, R.string.sync_add_folder)
                true
            }
        }
        devicesCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "devices"
            setTitle(R.string.sync_devices_category)
            isOrderingAsAdded = false
            isVisible = false
        }
        devicesCategory.add(Preference(prefContext)) {
            key = "add_device"
            order = ORDER_ADD
            setTitle(R.string.sync_add_device)
            setIcon(R.drawable.ic_add)
            setOnPreferenceClickListener {
                addDevice(null)
                true
            }
        }
        optionsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "options"
            setTitle(R.string.sync_options_category)
            isVisible = false
        }
        pausePref = optionsCategory.add(Preference(prefContext)) {
            key = "pause_all"
            setIcon(R.drawable.ic_pause)
            setOnPreferenceClickListener {
                snapshot?.let { setAllPaused(it.devices.any { d -> !d.paused }) }
                true
            }
        }
        optionsCategory.add(Preference(prefContext)) {
            key = "network"
            setTitle(R.string.sync_network_title)
            setSummary(R.string.sync_network_summary)
            setIcon(R.drawable.ic_tune)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), SyncOptionsFragment::class.java, R.string.sync_network_title)
                true
            }
        }
        ignoredPref = optionsCategory.add(Preference(prefContext)) {
            key = "ignored"
            setTitle(R.string.sync_ignored_title)
            setIcon(R.drawable.ic_block)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), IgnoredDevicesFragment::class.java, R.string.sync_ignored_title)
                true
            }
        }
        emptyPref = ZeroStatePreference(prefContext).apply {
            key = "empty"
            setIcon(R.drawable.ic_sync)
            setTitle(R.string.status_not_running)
            isVisible = false
        }
        screen.addPreference(emptyPref)
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_footer)
        }
    }

    // ---- polling ----

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var round = 0
                while (isActive) {
                    poll(round++ % IDENTITY_EVERY == 0)
                    withTimeoutOrNull(POLL_MS) { wake.receive() }
                }
            }
        }
    }

    override fun refresh() {
        wake.trySend(Unit)
    }

    private suspend fun poll(withIdentity: Boolean) {
        val newInfo = attempt("info", R.string.error_load_status) { SyncClient.info() }
        info = newInfo
        if (withIdentity || identity == null) {
            identity = attempt("identity", R.string.error_load_identity) { SyncClient.identity() } ?: identity
            storageOk = orNull("sync storage") { SyncClient.health().syncHomeOk } ?: storageOk
        }
        if ((withIdentity || conditions == null) && !conditionsBusy) {
            conditions = attempt("conditions", R.string.error_load_conditions) { Syncthing.conditions() } ?: conditions
        }
        // Syncthing answers whenever it runs, including a run started by hand
        // (module tests before an image), so the state property alone does
        // not decide whether to ask.
        val snap = try {
            Syncthing.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val running = newInfo?.syncState == SyncInfo.STATE_RUNNING
            if (running || !isUnreachable(e)) setError("sync", R.string.error_load_sync, e) else clearError("sync")
            currentCoroutineContext().ensureActive()
            null
        }
        if (snap != null) clearError("sync")
        snapshot = snap
        render()
    }

    /** Syncthing not listening: expected while it is off. */
    private fun isUnreachable(e: Exception): Boolean =
        e is SyncException && e.kind == SyncException.Kind.REMOTE &&
            (e.code == "sync_stopped" || e.detail.contains("Syncthing (rest.sock)") || e.detail.contains("config.xml"))

    // ---- rendering ----

    private fun render() {
        val context = context ?: return
        val info = info
        val snap = snapshot
        if (info != null && !switchBusy) {
            mainSwitch.isChecked = info.syncEnabled
            mainSwitch.isEnabled = true
        } else if (info == null) {
            mainSwitch.isEnabled = false
        }
        val waiting = conditions?.waiting.orEmpty()
        statePref.summary = when {
            snap != null -> listOfNotNull(getString(R.string.state_running), snap.version)
                .joinToString(getString(R.string.separator))
            info == null -> getString(R.string.status_unknown)
            info.syncEnabled && info.syncState == SyncInfo.STATE_WAITING -> waitingLabel(context, waiting)
            info.syncEnabled -> syncStateLabel(context, info.syncState)
            else -> getString(R.string.state_stopped)
        }
        renderConditions(context)
        namePref.isVisible = snap != null
        namePref.summary = snap?.myName?.ifEmpty { null } ?: getString(R.string.sync_device_name_unset)
        idPref.isVisible = snap != null
        idPref.summary = snap?.myId?.let { SyncFormat.twoLines(it) }
        renderIdentity(snap)
        // Only where sync is used: an unused sync storage has nothing to be wrong about.
        storagePref.isVisible = storageOk == false && (info?.syncEnabled == true || identity?.present == true)

        emptyPref.isVisible = snap == null
        emptyPref.setTitle(
            when {
                info?.syncEnabled == true && identity?.present == false -> R.string.sync_no_identity_title
                info?.syncEnabled == true && info.syncState == SyncInfo.STATE_WAITING -> R.string.sync_waiting_title
                else -> R.string.status_not_running
            }
        )
        foldersCategory.isVisible = snap != null
        devicesCategory.isVisible = snap != null
        optionsCategory.isVisible = snap != null
        if (snap == null) {
            pendingCategory.isVisible = false
            return
        }
        renderPending(snap)
        renderFolders(context, snap)
        renderDevices(context, snap)
        // Without devices there is nothing to pause or resume: the row offers Pause all, disabled.
        val anyRunning = snap.devices.isEmpty() || snap.devices.any { !it.paused }
        pausePref.setTitle(if (anyRunning) R.string.sync_pause_all else R.string.sync_resume_all)
        if (snap.devices.isEmpty()) {
            pausePref.summary = null
        } else {
            pausePref.setSummary(if (anyRunning) R.string.sync_pause_all_summary else R.string.sync_resume_all_summary)
        }
        pausePref.setIcon(if (anyRunning) R.drawable.ic_pause else R.drawable.ic_play)
        pausePref.isEnabled = !busy && snap.devices.isNotEmpty()
    }

    private fun renderConditions(context: Context) {
        val c = conditions
        wifiOnlyPref.isEnabled = c != null && !conditionsBusy
        chargingOnlyPref.isEnabled = c != null && !conditionsBusy
        if (c == null) {
            wifiOnlyPref.summary = null
            chargingOnlyPref.summary = null
            return
        }
        if (!conditionsBusy) {
            wifiOnlyPref.isChecked = c.wifiOnly
            chargingOnlyPref.isChecked = c.chargingOnly
        }
        wifiOnlyPref.summary = getString(R.string.sync_wifi_only_summary) + "\n" + when (c.wifi) {
            true -> getString(R.string.sync_wifi_now_on)
            false -> getString(R.string.sync_wifi_now_off)
            null -> getString(R.string.sync_condition_unknown)
        }
        chargingOnlyPref.summary = getString(R.string.sync_charging_only_summary) + "\n" + when (c.charging) {
            true -> getString(R.string.sync_charging_now_on)
            false -> getString(R.string.sync_charging_now_off)
            null -> getString(R.string.sync_condition_unknown)
        }
    }

    private fun setConditions(wifiOnly: Boolean? = null, chargingOnly: Boolean? = null) {
        if (conditionsBusy) return
        conditionsBusy = true
        render()
        launchUi {
            var result: SyncConditions? = null
            act(R.string.error_sync_conditions) { result = Syncthing.conditions(wifiOnly, chargingOnly) }
            result?.let { conditions = it }
            conditionsBusy = false
            refresh()
        }
    }

    private fun renderIdentity(snap: SyncSnapshot?) {
        val id = identity
        identityPref.summary = when {
            id == null -> getString(R.string.status_unknown)
            !id.present -> getString(R.string.sync_identity_missing_summary)
            snap != null -> getString(R.string.sync_identity_present_summary, snap.myId.substringBefore('-'))
            else -> getString(R.string.sync_identity_present_idle)
        }
    }

    private val pendingPrefs = HashMap<String, Preference>()
    private val folderPrefs = HashMap<String, Preference>()
    private val devicePrefs = HashMap<String, Preference>()

    private fun renderPending(snap: SyncSnapshot) {
        val wanted = LinkedHashMap<String, Pair<CharSequence, CharSequence>>()
        val actions = HashMap<String, () -> Unit>()
        snap.pendingDevices.forEach { d ->
            val key = "pending_device:" + d.id
            wanted[key] = d.name.ifEmpty { d.id.substringBefore('-') } to
                getString(R.string.sync_pending_device_summary, d.id.substringBefore('-'), d.address.ifEmpty { "-" })
            actions[key] = { pendingDevice(d) }
        }
        snap.pendingFolders.forEach { f ->
            val key = "pending_folder:" + f.id
            val by = Format.names(requireContext(), f.offeredBy.keys.map { snap.deviceName(it) })
            wanted[key] = f.label to getString(R.string.sync_pending_folder_summary, by)
            actions[key] = { pendingFolder(f) }
        }
        pendingPrefs.keys.filter { it !in wanted }.forEach { key ->
            pendingPrefs.remove(key)?.let { pendingCategory.removePreference(it) }
        }
        var order = 0
        wanted.forEach { (key, texts) ->
            val pref = pendingPrefs.getOrPut(key) {
                pendingCategory.add(Preference(prefContext)) {
                    this.key = key
                    setIcon(if (key.startsWith("pending_device")) R.drawable.ic_device else R.drawable.ic_folder)
                }
            }
            pref.order = order++
            pref.title = texts.first
            pref.summary = texts.second
            pref.setOnPreferenceClickListener {
                actions[key]?.invoke()
                true
            }
        }
        pendingCategory.isVisible = wanted.isNotEmpty()
    }

    private fun renderFolders(context: Context, snap: SyncSnapshot) {
        val keep = snap.folders.map { it.id }.toHashSet()
        folderPrefs.keys.filter { it !in keep }.forEach { id ->
            folderPrefs.remove(id)?.let { foldersCategory.removePreference(it) }
        }
        snap.folders.forEachIndexed { index, folder ->
            val pref = folderPrefs.getOrPut(folder.id) {
                foldersCategory.add(Preference(prefContext)) {
                    key = "folder:" + folder.id
                    setIcon(R.drawable.ic_folder)
                    setOnPreferenceClickListener {
                        FolderFragment.open(requireContext(), folder.id, snapshot?.folder(folder.id)?.label ?: folder.label)
                        true
                    }
                }
            }
            pref.order = index
            pref.title = folder.label
            pref.summary = SyncFormat.folderSummary(context, folder)
        }
    }

    private fun renderDevices(context: Context, snap: SyncSnapshot) {
        val keep = snap.devices.map { it.id }.toHashSet()
        devicePrefs.keys.filter { it !in keep }.forEach { id ->
            devicePrefs.remove(id)?.let { devicesCategory.removePreference(it) }
        }
        snap.devices.forEachIndexed { index, device ->
            val pref = devicePrefs.getOrPut(device.id) {
                devicesCategory.add(Preference(prefContext)) {
                    key = "device:" + device.id
                    setIcon(R.drawable.ic_device)
                    setOnPreferenceClickListener {
                        DeviceFragment.open(requireContext(), device.id, snapshot?.device(device.id)?.name ?: device.name)
                        true
                    }
                }
            }
            pref.order = index
            pref.title = device.name
            pref.summary = SyncFormat.deviceRowSummary(context, device)
        }
    }

    // ---- actions ----

    private fun toggleSync(on: Boolean) {
        if (switchBusy) return
        switchBusy = true
        mainSwitch.isEnabled = false
        launchUi {
            if (act(if (on) R.string.error_sync_on else R.string.error_sync_off) { SyncClient.syncEnable(on) }) {
                mainSwitch.isChecked = on
            }
            switchBusy = false
            mainSwitch.isEnabled = true
            refresh()
        }
    }

    private fun editName() {
        val snap = snapshot ?: return
        SyncDialogs.editText(
            this, R.string.sync_device_name, snap.myName, R.string.sync_device_name_message,
            validate = { if (it.isEmpty()) getString(R.string.sync_name_required) else null },
        ) { name ->
            launchUi {
                act(R.string.error_sync_change) { Syncthing.patchDevice(snap.myId, org.json.JSONObject().put("name", name)) }
                refresh()
            }
        }
    }

    private fun setAllPaused(paused: Boolean) {
        if (busy) return
        busy = true
        render()
        launchUi {
            act(if (paused) R.string.error_sync_pause else R.string.error_sync_resume) { Syncthing.setAllPaused(paused) }
            busy = false
            refresh()
        }
    }

    /**
     * Adds a device: the one asking to connect ([pending]), or by ID typed
     * in or scanned (onDeviceId); then its name and addresses.
     */
    private fun addDevice(pending: PendingDevice?) {
        if (pending != null) {
            SyncDialogs.askName(this, pending.id, pending.name) { refresh() }
            return
        }
        SyncDialogs.askDeviceId(
            this, R.string.sync_add_device,
            { scan -> getString(if (scan) R.string.sync_add_device_message_scan else R.string.sync_add_device_message) },
            SyncDialogs.PURPOSE_ADD,
        )
    }

    /** The ID of a device to add, typed in or scanned (a scan's result also reaches a page recreated meanwhile). */
    override fun onDeviceId(purpose: String, id: String) {
        if (purpose == SyncDialogs.PURPOSE_ADD) SyncDialogs.askName(this, id, "") { refresh() } else super.onDeviceId(purpose, id)
    }

    private fun pendingDevice(pending: PendingDevice) = showDialog("sync-pending-device") { d ->
        val name = pending.name.ifEmpty { pending.id.substringBefore('-') }
        AlertDialog.Builder(d.context)
            .setTitle(getString(R.string.sync_pending_device_title, name))
            .setMessage(getString(R.string.sync_pending_device_message, pending.id, pending.address.ifEmpty { "-" }))
            .setPositiveButton(R.string.sync_add) { _, _ -> addDevice(pending) }
            .setNeutralButton(R.string.sync_ignore) { _, _ ->
                confirm(
                    "sync-ignore-device", R.string.sync_ignore_device_title, { getString(R.string.sync_ignore_device_message, name) },
                    R.string.sync_ignore,
                ) {
                    launchUi {
                        act(R.string.error_sync_change) { Syncthing.ignoreDevice(pending.id, pending.name, pending.address) }
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.sync_later) { _, _ ->
                launchUi {
                    act(R.string.error_sync_change) { Syncthing.dismissPendingDevice(pending.id) }
                    refresh()
                }
            }
            .create()
    }

    private fun pendingFolder(f: PendingFolder) {
        val snap = snapshot ?: return
        val offeredBy = f.offeredBy.keys.map { snap.deviceName(it) }
        showDialog("sync-pending-folder") { d -> pendingFolderDialog(d.context, f, offeredBy) }
    }

    private fun pendingFolderDialog(context: Context, f: PendingFolder, offeredBy: List<String>): Dialog {
        val by = Format.names(context, offeredBy)
        return AlertDialog.Builder(context)
            .setTitle(getString(R.string.sync_pending_folder_title, f.label))
            .setMessage(
                getString(R.string.sync_pending_folder_message, by, f.id) +
                    (if (f.receiveEncrypted) "\n\n" + getString(R.string.sync_pending_folder_encrypted) else "") +
                    (if (f.remoteEncrypted.isNotEmpty()) "\n\n" + getString(R.string.sync_pending_folder_remote_encrypted) else "")
            )
            .setPositiveButton(R.string.sync_add) { _, _ ->
                AddFolderFragment.openAccept(requireContext(), f.id, f.label, f.offeredBy.keys.toList(),
                    f.receiveEncrypted, f.remoteEncrypted)
            }
            .setNeutralButton(R.string.sync_ignore) { _, _ ->
                launchUi {
                    act(R.string.error_sync_change) {
                        f.offeredBy.keys.forEach { device -> Syncthing.ignoreFolder(device, f.id, f.label) }
                    }
                    refresh()
                }
            }
            .setNegativeButton(R.string.sync_later) { _, _ ->
                launchUi {
                    act(R.string.error_sync_change) { Syncthing.dismissPendingFolder(f.id) }
                    refresh()
                }
            }
            .create()
    }

    override fun onResume() {
        super.onResume()
        launchUi {
            val ignored = attempt("ignored", R.string.error_load_sync) {
                if (snapshot != null || info?.syncState == SyncInfo.STATE_RUNNING) Syncthing.ignoredDevices() else null
            }
            ignoredPref.summary = ignored?.let {
                resources.getQuantityString(R.plurals.sync_ignored_count, it.size, it.size)
            } ?: getString(R.string.sync_ignored_summary)
        }
    }

    companion object {
        private const val POLL_MS = 3000L
        private const val IDENTITY_EVERY = 5
        private const val ORDER_ADD = 10_000
    }
}
