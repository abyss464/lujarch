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
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import org.lujarch.settings.pages.showDialog
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.Pages
import org.json.JSONArray
import org.json.JSONObject

/**
 * One other device: whether this device syncs with it, its connection, its
 * name, addresses, the folders shared with it, introducer, automatic
 * acceptance of its folders, compression; replacing it by another device
 * (a new cloud server) and removing it.
 */
class DeviceFragment : SyncPageFragment() {
    private lateinit var deviceId: String
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var statePref: Preference
    private lateinit var namePref: Preference
    private lateinit var addressesPref: Preference
    private lateinit var foldersPref: Preference
    private lateinit var introducerPref: SwitchPreferenceCompat
    private lateinit var autoAcceptPref: SwitchPreferenceCompat
    private lateinit var untrustedPref: SwitchPreferenceCompat
    private lateinit var compressionPref: Preference

    private var snapshot: SyncSnapshot? = null
    private var device: SyncDevice? = null
    private var busy = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        deviceId = requireArguments().getString(ARG_DEVICE) ?: ""
        mainSwitch = screen.add(MainSwitchPreference(prefContext)) {
            key = "sync"
            setTitle(R.string.sync_device_switch)
            isPersistent = false
            isEnabled = false
            setOnPreferenceChangeListener { _, value ->
                setPaused(!(value as Boolean))
                false
            }
        }
        val status = screen.add(PreferenceCategory(prefContext)) {
            key = "status"
            setTitle(R.string.sync_status_category)
        }
        statePref = status.add(Preference(prefContext)) {
            key = "state"
            setTitle(R.string.sync_connection)
            setSummary(R.string.loading)
            isSelectable = false
        }
        status.add(Preference(prefContext)) {
            key = "id"
            setTitle(R.string.sync_device_id)
            summary = SyncFormat.twoLines(deviceId)
            setOnPreferenceClickListener {
                SyncFormat.showDeviceId(this@DeviceFragment, deviceId)
                true
            }
        }
        val settings = screen.add(PreferenceCategory(prefContext)) {
            key = "settings"
            setTitle(R.string.sync_settings_category)
        }
        namePref = settings.add(Preference(prefContext)) {
            key = "name"
            setTitle(R.string.sync_device_name)
            setOnPreferenceClickListener {
                device?.let { editName(it) }
                true
            }
        }
        addressesPref = settings.add(Preference(prefContext)) {
            key = "addresses"
            setTitle(R.string.sync_addresses)
            setOnPreferenceClickListener {
                editAddresses()
                true
            }
        }
        foldersPref = settings.add(Preference(prefContext)) {
            key = "folders"
            setTitle(R.string.sync_device_folders)
            setOnPreferenceClickListener {
                chooseFolders()
                true
            }
        }
        introducerPref = settings.add(SwitchPreferenceCompat(prefContext)) {
            key = "introducer"
            setTitle(R.string.sync_introducer)
            setSummary(R.string.sync_introducer_summary)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                patch(JSONObject().put("introducer", value as Boolean))
                false
            }
        }
        autoAcceptPref = settings.add(SwitchPreferenceCompat(prefContext)) {
            key = "auto_accept"
            setTitle(R.string.sync_auto_accept)
            setSummary(R.string.sync_auto_accept_summary)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                val on = value as Boolean
                change(R.string.error_sync_change, {
                    // Automatically accepted folders go to the folders root.
                    if (on) Syncthing.setDefaultFolderPath()
                    Syncthing.patchDevice(deviceId, JSONObject().put("autoAcceptFolders", on))
                })
                false
            }
        }
        untrustedPref = settings.add(SwitchPreferenceCompat(prefContext)) {
            key = "untrusted"
            setTitle(R.string.sync_untrusted)
            setSummary(R.string.sync_untrusted_summary)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                setUntrusted(value as Boolean)
                false
            }
        }
        compressionPref = settings.add(Preference(prefContext)) {
            key = "compression"
            setTitle(R.string.sync_compression)
            setOnPreferenceClickListener {
                chooseCompression()
                true
            }
        }
        val removeCategory = screen.add(PreferenceCategory(prefContext)) { key = "remove_category" }
        removeCategory.add(Preference(prefContext)) {
            key = "replace"
            setTitle(R.string.sync_replace_device)
            setSummary(R.string.sync_replace_device_summary)
            setIcon(R.drawable.ic_sync)
            setOnPreferenceClickListener {
                replace()
                true
            }
        }
        removeCategory.add(Preference(prefContext)) {
            key = "remove"
            setTitle(R.string.sync_remove_device)
            setIcon(R.drawable.ic_delete)
            setOnPreferenceClickListener {
                device?.let { askRemove(it) }
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_device_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            val snap = attempt("sync", R.string.error_load_sync) { Syncthing.snapshot() } ?: return@launchUi
            snapshot = snap
            val d = snap.device(deviceId)
            if (d == null) {
                activity?.finish()
                return@launchUi
            }
            device = d
            render(requireContext(), d, snap)
        }
    }

    private fun render(context: Context, d: SyncDevice, snap: SyncSnapshot) {
        activity?.title = d.name
        mainSwitch.isChecked = !d.paused
        mainSwitch.isEnabled = !busy
        statePref.summary = SyncFormat.deviceSummary(context, d)
        namePref.summary = d.name
        addressesPref.summary = SyncFormat.addressesLabel(context, d.addresses)
        val shared = snap.folders.filter { deviceId in it.devices }
        foldersPref.summary = if (shared.isEmpty()) getString(R.string.sync_device_folders_none)
        else shared.joinToString(getString(R.string.list_separator)) { it.label }
        introducerPref.isChecked = d.introducer
        autoAcceptPref.isChecked = d.autoAcceptFolders
        untrustedPref.isChecked = d.untrusted
        compressionPref.summary = compressionLabel(d.compression)
    }

    private fun compressionLabel(value: String): String = when (value) {
        "always" -> getString(R.string.sync_compression_always)
        "never" -> getString(R.string.sync_compression_never)
        else -> getString(R.string.sync_compression_metadata)
    }

    // ---- actions ----

    private fun patch(changes: JSONObject) = change(R.string.error_sync_change, { Syncthing.patchDevice(deviceId, changes) })

    private fun setPaused(paused: Boolean) {
        if (busy) return
        busy = true
        mainSwitch.isEnabled = false
        change(if (paused) R.string.error_sync_pause else R.string.error_sync_resume, { Syncthing.setDevicePaused(deviceId, paused) }) {
            busy = false
        }
    }

    private fun editName(d: SyncDevice) = SyncDialogs.editText(
        this, R.string.sync_device_name, d.name,
        validate = { if (it.isEmpty()) getString(R.string.sync_name_required) else null },
    ) { name -> patch(JSONObject().put("name", name)) }

    private fun askRemove(d: SyncDevice) = confirm(
        "sync-remove-device", R.string.sync_remove_device_title, { getString(R.string.sync_remove_device_message, d.name) }, R.string.sync_remove,
    ) {
        change(R.string.error_sync_change, { Syncthing.removeDevice(deviceId) }) { ok ->
            if (ok) activity?.finish()
        }
    }

    private fun editAddresses() {
        val d = device ?: return
        SyncDialogs.editAddresses(this, R.string.sync_addresses, d.addresses) { list ->
            patch(JSONObject().put("addresses", JSONArray(list)))
        }
    }

    // ---- replacing this device ----

    /**
     * Replaces this device by another one, typically a new cloud server:
     * the new device gets this one's settings and its folders the way this
     * one has them (encrypted with the same password where it has them
     * encrypted; the system service copies the password, which the app
     * never holds), then this one is removed, paused or kept. A device
     * already added keeps its name and gets the rest.
     */
    private fun replace() {
        val old = device ?: return
        SyncDialogs.askDeviceId(
            this, R.string.sync_replace_device,
            { scan -> getString(if (scan) R.string.sync_replace_id_message_scan else R.string.sync_replace_id_message, old.name) },
            SyncDialogs.PURPOSE_REPLACE,
        )
    }

    /**
     * The new device's ID, typed in or scanned. A scan's result can reach a
     * page recreated meanwhile, which may not have loaded its device yet:
     * then it reads the device and the snapshot first.
     */
    override fun onDeviceId(purpose: String, id: String) {
        if (purpose != SyncDialogs.PURPOSE_REPLACE) return super.onDeviceId(purpose, id)
        val old = device
        val snap = snapshot
        if (old != null && snap != null) {
            replaceWith(old, snap, id)
            return
        }
        launchUi {
            val s = attempt("sync", R.string.error_load_sync) { Syncthing.snapshot() } ?: return@launchUi
            val d = s.device(deviceId)
            if (d == null) {
                Log.w(TAG, "DeviceFragment: the device to replace is gone; the scanned ID is not used")
                return@launchUi
            }
            replaceWith(d, s, id)
        }
    }

    private fun replaceWith(old: SyncDevice, snap: SyncSnapshot, id: String) {
        val existing = snap.device(id)
        when {
            id == old.id || id == snap.myId -> showDialog("sync-replace-same") { d ->
                AlertDialog.Builder(d.context)
                    .setTitle(R.string.sync_replace_device)
                    .setMessage(R.string.sync_replace_same_device)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            }
            existing != null -> SyncDialogs.editAddresses(this, R.string.sync_replace_device, existing.addresses, R.string.sync_next) {
                confirmReplace(old, id, existing.name, it, existing = true)
            }
            else -> askReplacementName(old, id)
        }
    }

    private fun askReplacementName(old: SyncDevice, id: String) = showDialog("sync-replace-name") { d ->
        InputDialog.create(
            d.context, R.string.sync_replace_device,
            message = getString(R.string.sync_add_device_name_message, id),
            initial = old.name,
            hint = getString(R.string.sync_device_name),
            positive = R.string.sync_next,
            validate = { if (it.isEmpty()) getString(R.string.sync_name_required) else null },
        ) { name ->
            // The old addresses as a start: a new server often keeps the port.
            SyncDialogs.editAddresses(this, R.string.sync_replace_device, old.addresses, R.string.sync_next) {
                confirmReplace(old, id, name, it, existing = false)
            }
        }
    }

    private fun confirmReplace(old: SyncDevice, id: String, name: String, addresses: List<String>, existing: Boolean) {
        val snap = snapshot ?: return
        val shared = snap.folders.filter { old.id in it.devices }
        // The owner's choice is the dialog's: a recreated window keeps it.
        var after = AFTER_REMOVE
        showDialog("sync-replace") { d ->
            val context = d.context
            val message = if (shared.isEmpty()) getString(R.string.sync_replace_message_none, name, old.name)
            else getString(R.string.sync_replace_message, name, old.name, Format.names(context, shared.map { it.label }))
            val pad = InputDialog.dp(context, 24)
            val group = RadioGroup(context)
            val choices = listOf(
                R.string.sync_replace_then_remove to AFTER_REMOVE,
                R.string.sync_replace_then_pause to AFTER_PAUSE,
                R.string.sync_replace_then_keep to AFTER_KEEP,
            ).map { (text, value) ->
                val button = RadioButton(context).apply {
                    this.id = View.generateViewId()
                    this.text = getString(text, old.name)
                }
                group.addView(button)
                button.id to value
            }
            group.check(choices.first { it.second == after }.first)
            group.setOnCheckedChangeListener { _, checkedId -> choices.firstOrNull { it.first == checkedId }?.let { after = it.second } }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, InputDialog.dp(context, 8), pad, 0)
                addView(TextView(context).apply {
                    text = message
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                    setPadding(0, 0, 0, InputDialog.dp(context, 8))
                })
                addView(group)
            }
            AlertDialog.Builder(context)
                .setTitle(getString(R.string.sync_replace_title, old.name))
                .setView(ScrollView(context).apply { addView(column) })
                .setPositiveButton(R.string.sync_replace_confirm) { _, _ -> replace(old, id, name, addresses, existing, shared, after) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun replace(
        old: SyncDevice,
        id: String,
        name: String,
        addresses: List<String>,
        existing: Boolean,
        shared: List<SyncFolder>,
        after: Int,
    ) {
        change(R.string.error_sync_replace, {
            if (existing) {
                Syncthing.patchDevice(
                    id, JSONObject().put("addresses", JSONArray(addresses)).put("introducer", old.introducer)
                        .put("autoAcceptFolders", old.autoAcceptFolders).put("untrusted", old.untrusted)
                        .put("compression", old.compression),
                )
            } else {
                Syncthing.addDevice(id, name, addresses, like = old)
            }
            // Folders it accepts on its own go to the folders root, as for this one.
            if (old.autoAcceptFolders) Syncthing.setDefaultFolderPath()
            shared.forEach { Syncthing.shareLike(it, id, old.id) }
            when (after) {
                AFTER_REMOVE -> Syncthing.removeDevice(old.id)
                AFTER_PAUSE -> Syncthing.setDevicePaused(old.id, true)
            }
        }) { ok ->
            if (ok) {
                open(requireContext(), id, name)
                activity?.finish()
            }
        }
    }

    /** Shares or unshares folders with this device (each folder's device list). */
    private fun chooseFolders() {
        val snap = snapshot ?: return
        val folders = snap.folders
        if (folders.isEmpty()) return
        // The dialog's own state: a recreated window shows the owner's checks.
        val checked = BooleanArray(folders.size) { deviceId in folders[it].devices }
        showDialog("sync-device-folders") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(R.string.sync_device_folders)
                .setMultiChoiceItems(folders.map { it.label }.toTypedArray(), checked.copyOf()) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    change(R.string.error_sync_change, {
                        folders.forEachIndexed { i, f ->
                            val has = deviceId in f.devices
                            if (checked[i] != has) {
                                Syncthing.setFolderDevices(f.id, if (checked[i]) f.devices + deviceId else f.devices - deviceId)
                            }
                        }
                    })
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    /**
     * Untrusted: every folder shared with the device must be encrypted with a
     * password; Syncthing stops sharing the ones without a password with it.
     */
    private fun setUntrusted(on: Boolean) {
        val d = device ?: return
        val snap = snapshot ?: return
        if (!on) {
            patch(JSONObject().put("untrusted", false))
            return
        }
        val plain = snap.folders.filter { d.id in it.devices && d.id !in it.encryptedFor }
        confirm(
            "sync-untrusted", R.string.sync_untrusted_title,
            {
                if (plain.isEmpty()) getString(R.string.sync_untrusted_message, d.name)
                else getString(R.string.sync_untrusted_message_folders, d.name, plain.joinToString(getString(R.string.list_separator)) { it.label })
            },
            R.string.sync_untrusted_confirm,
        ) {
            patch(JSONObject().put("untrusted", true))
        }
    }

    private fun chooseCompression() {
        val compression = device?.compression ?: return
        val values = listOf("metadata", "always", "never")
        showDialog("sync-compression") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(R.string.sync_compression)
                .setSingleChoiceItems(values.map { compressionLabel(it) }.toTypedArray(), values.indexOf(compression)) { dialog, which ->
                    dialog.dismiss()
                    if (values[which] != compression) patch(JSONObject().put("compression", values[which]))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    companion object {
        const val ARG_DEVICE = "device"

        private const val AFTER_REMOVE = 0
        private const val AFTER_PAUSE = 1
        private const val AFTER_KEEP = 2

        fun open(context: Context, id: String, name: String) {
            Pages.open(context, DeviceFragment::class.java, R.string.sync_devices_category, title = name,
                arguments = Bundle().apply { putString(ARG_DEVICE, id) })
        }
    }
}
