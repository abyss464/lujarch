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
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import org.json.JSONObject
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * One folder: whether it syncs, its state, its local folder (movable to
 * another directory of the sync storage), revert (receive only) and
 * override (send only) when Syncthing needs them, the items that failed and
 * the conflict copies, and its settings (name, type, devices, encrypted
 * sharing per device, ignore patterns, versioning, change detection);
 * removing it keeps its files.
 */
class FolderFragment : SyncPageFragment() {
    private lateinit var folderId: String
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var statePref: Preference
    private lateinit var localPref: Preference
    private lateinit var filesPref: Preference
    private lateinit var rescanPref: Preference
    private lateinit var revertPref: Preference
    private lateinit var overridePref: Preference
    private lateinit var failedCategory: PreferenceCategory
    private lateinit var conflictsCategory: PreferenceCategory
    private lateinit var labelPref: Preference
    private lateinit var typePref: Preference
    private lateinit var devicesPref: Preference
    private lateinit var encryptionPref: Preference
    private lateinit var ignoresPref: Preference
    private lateinit var versioningPref: Preference
    private lateinit var watcherPref: SwitchPreferenceCompat
    private lateinit var intervalPref: Preference

    private var snapshot: SyncSnapshot? = null
    private var folder: SyncFolder? = null
    private var busy = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        folderId = requireArguments().getString(ARG_FOLDER) ?: ""
        mainSwitch = screen.add(MainSwitchPreference(prefContext)) {
            key = "sync"
            setTitle(R.string.sync_folder_switch)
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
            setTitle(R.string.sync_state)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        localPref = status.add(Preference(prefContext)) {
            key = "local"
            setTitle(R.string.sync_local_folder)
            isCopyingEnabled = true
            setOnPreferenceClickListener {
                chooseLocation()
                true
            }
        }
        filesPref = status.add(Preference(prefContext)) {
            key = "files"
            setTitle(R.string.sync_files)
            isSelectable = false
        }
        rescanPref = status.add(Preference(prefContext)) {
            key = "rescan"
            setTitle(R.string.sync_rescan)
            setIcon(R.drawable.ic_refresh)
            setOnPreferenceClickListener {
                rescan()
                true
            }
        }
        revertPref = status.add(Preference(prefContext)) {
            key = "revert"
            setTitle(R.string.sync_revert)
            setIcon(R.drawable.ic_restore)
            isVisible = false
            setOnPreferenceClickListener {
                folder?.let { askRevert(it) }
                true
            }
        }
        overridePref = status.add(Preference(prefContext)) {
            key = "override"
            setTitle(R.string.sync_override)
            setIcon(R.drawable.ic_restore)
            isVisible = false
            setOnPreferenceClickListener {
                folder?.let { askOverride(it) }
                true
            }
        }
        failedCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "failed"
            setTitle(R.string.sync_failed_category)
            isVisible = false
        }
        conflictsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "conflicts"
            setTitle(R.string.sync_conflicts_category)
            isVisible = false
        }
        val settings = screen.add(PreferenceCategory(prefContext)) {
            key = "settings"
            setTitle(R.string.sync_settings_category)
        }
        labelPref = settings.add(Preference(prefContext)) {
            key = "label"
            setTitle(R.string.sync_folder_label)
            setOnPreferenceClickListener {
                folder?.let { editLabel(it) }
                true
            }
        }
        settings.add(Preference(prefContext)) {
            key = "id"
            setTitle(R.string.sync_folder_id)
            summary = folderId
            isSelectable = false
            isCopyingEnabled = true
        }
        typePref = settings.add(Preference(prefContext)) {
            key = "type"
            setTitle(R.string.sync_folder_type)
            setOnPreferenceClickListener {
                folder?.let { chooseType(it) }
                true
            }
        }
        devicesPref = settings.add(Preference(prefContext)) {
            key = "devices"
            setTitle(R.string.sync_shared_with)
            setOnPreferenceClickListener {
                chooseDevices()
                true
            }
        }
        encryptionPref = settings.add(Preference(prefContext)) {
            key = "encryption"
            setTitle(R.string.sync_encryption)
            setIcon(R.drawable.ic_key)
            setOnPreferenceClickListener {
                chooseEncryption()
                true
            }
        }
        ignoresPref = settings.add(Preference(prefContext)) {
            key = "ignores"
            setTitle(R.string.sync_ignores_title)
            setSummary(R.string.sync_ignores_summary)
            setOnPreferenceClickListener {
                IgnoresFragment.open(requireContext(), folderId)
                true
            }
        }
        versioningPref = settings.add(Preference(prefContext)) {
            key = "versioning"
            setTitle(R.string.sync_versioning)
            setOnPreferenceClickListener {
                VersioningFragment.open(requireContext(), folderId)
                true
            }
        }
        watcherPref = settings.add(SwitchPreferenceCompat(prefContext)) {
            key = "watcher"
            setTitle(R.string.sync_watcher)
            setSummary(R.string.sync_watcher_summary)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                patch(JSONObject().put("fsWatcherEnabled", value as Boolean))
                false
            }
        }
        intervalPref = settings.add(Preference(prefContext)) {
            key = "interval"
            setTitle(R.string.sync_rescan_interval)
            setOnPreferenceClickListener {
                chooseInterval()
                true
            }
        }
        screen.add(PreferenceCategory(prefContext)) { key = "remove_category" }.add(Preference(prefContext)) {
            key = "remove"
            setTitle(R.string.sync_remove_folder)
            setIcon(R.drawable.ic_delete)
            setOnPreferenceClickListener {
                folder?.let { askRemove(it) }
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_folder_footer)
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
            val f = snap.folder(folderId)
            if (f == null) {
                activity?.finish()
                return@launchUi
            }
            folder = f
            render(requireContext(), f, snap)
            val failed = if (f.paused || f.failedItems == 0) emptyList()
            else attempt("errors", R.string.error_load_sync) { Syncthing.folderErrors(folderId) } ?: emptyList()
            val conflicts = if (f.paused) emptyList()
            else attempt("conflicts", R.string.error_load_sync) { Syncthing.conflicts(folderId) } ?: emptyList()
            renderItems(failed, conflicts)
        }
    }

    private fun render(context: Context, f: SyncFolder, snap: SyncSnapshot) {
        activity?.title = f.label
        mainSwitch.isChecked = !f.paused
        mainSwitch.isEnabled = !busy
        statePref.summary = SyncFormat.folderState(context, f)
        localPref.summary = SyncFormat.localFolder(context, f) + "\n" + f.path +
            if (f.type == Syncthing.TYPE_SEND_ONLY) "\n" + getString(R.string.error_code_folder_not_movable) else ""
        localPref.isSelectable = f.type != Syncthing.TYPE_SEND_ONLY
        filesPref.summary = resources.getQuantityString(
            R.plurals.sync_folder_files, f.globalFiles, f.globalFiles, Formatter.formatShortFileSize(context, f.globalBytes)
        )
        rescanPref.isEnabled = !busy && !f.paused
        rescanPref.summary = f.lastScan?.let { getString(R.string.sync_last_scan, Format.ago(requireContext(), it)) }
            ?: getString(R.string.sync_rescan_summary)
        revertPref.isVisible = f.type == Syncthing.TYPE_RECEIVE_ONLY && f.localChanges > 0
        revertPref.summary = resources.getQuantityString(R.plurals.sync_revert_summary, f.localChanges, f.localChanges)
        revertPref.isEnabled = !busy && !f.paused
        overridePref.isVisible = f.type == Syncthing.TYPE_SEND_ONLY && f.needItems > 0
        overridePref.summary = resources.getQuantityString(R.plurals.sync_override_summary, f.needItems, f.needItems)
        overridePref.isEnabled = !busy && !f.paused
        labelPref.summary = f.label
        typePref.summary = SyncFormat.typeLabel(context, f.type) + "\n" + SyncFormat.typeSummary(context, f.type)
        typePref.isSelectable = f.type != Syncthing.TYPE_RECEIVE_ENCRYPTED
        devicesPref.summary = if (f.devices.isEmpty()) getString(R.string.sync_shared_with_none)
        else f.devices.joinToString(getString(PagesR.string.list_separator)) { snap.deviceName(it) }
        encryptionPref.isVisible = f.type != Syncthing.TYPE_RECEIVE_ENCRYPTED && f.devices.isNotEmpty()
        encryptionPref.summary = when {
            f.encryptedFor.isEmpty() -> getString(R.string.sync_encryption_none)
            else -> getString(R.string.sync_encryption_for,
                Format.names(requireContext(), f.encryptedFor.map { snap.deviceName(it) }))
        }
        versioningPref.summary = SyncFormat.versioningLabel(context, f.versioning)
        watcherPref.isChecked = f.fsWatcherEnabled
        intervalPref.summary = SyncFormat.intervalLabel(context, f.rescanIntervalS)
    }

    private fun renderItems(failed: List<FolderError>, conflicts: List<String>) {
        failedCategory.removeAll()
        failed.take(MAX_ITEMS).forEachIndexed { i, e ->
            failedCategory.add(Preference(prefContext)) {
                key = "failed:$i"
                title = e.path
                summary = e.error
                isSelectable = false
                isCopyingEnabled = true
                isIconSpaceReserved = false
            }
        }
        failedCategory.isVisible = failed.isNotEmpty()
        conflictsCategory.removeAll()
        conflicts.take(MAX_ITEMS).forEachIndexed { i, path ->
            conflictsCategory.add(Preference(prefContext)) {
                key = "conflict:$i"
                title = path
                isSelectable = false
                isCopyingEnabled = true
                isIconSpaceReserved = false
            }
        }
        if (conflicts.isNotEmpty()) {
            conflictsCategory.add(Preference(prefContext)) {
                key = "conflict_note"
                setSummary(R.string.sync_conflicts_note)
                isSelectable = false
                isIconSpaceReserved = false
            }
        }
        conflictsCategory.isVisible = conflicts.isNotEmpty()
    }

    // ---- actions ----

    private fun patch(changes: JSONObject) = change(R.string.error_sync_change, { Syncthing.patchFolder(folderId, changes) })

    private fun askRevert(f: SyncFolder) = confirm(
        "sync-revert", R.string.sync_revert_title,
        { resources.getQuantityString(R.plurals.sync_revert_message, f.localChanges, f.localChanges, f.label) }, R.string.sync_revert_confirm,
    ) {
        change(R.string.error_sync_revert, { Syncthing.revert(folderId) })
    }

    private fun askOverride(f: SyncFolder) = confirm(
        "sync-override", R.string.sync_override_title,
        { resources.getQuantityString(R.plurals.sync_override_message, f.needItems, f.needItems, f.label) }, R.string.sync_override_confirm,
    ) {
        // The service refuses an override that would delete items on the other
        // devices (override_deletes) until it is confirmed a second time.
        var deletesRefused = false
        change(R.string.error_sync_override, {
            try {
                Syncthing.override(folderId, deleteMissing = false)
            } catch (e: SyncException) {
                if (e.code != "override_deletes") throw e
                deletesRefused = true
            }
        }) { ok -> if (ok && deletesRefused) confirmOverrideDeletes() }
    }

    private fun editLabel(f: SyncFolder) = SyncDialogs.editText(
        this, R.string.sync_folder_label, f.label,
        validate = { if (it.isEmpty()) getString(R.string.sync_name_required) else null },
    ) { label -> patch(JSONObject().put("label", label)) }

    private fun chooseType(f: SyncFolder) {
        // Receive encrypted comes from an encrypted offer and stays.
        if (f.type == Syncthing.TYPE_RECEIVE_ENCRYPTED) return
        SyncDialogs.chooseType(this, f.type) { patch(JSONObject().put("type", it)) }
    }

    private fun chooseDevices() {
        val f = folder ?: return
        val snap = snapshot ?: return
        SyncDialogs.chooseDevices(this, snap.devices, f.devices) { chosen ->
            change(R.string.error_sync_change, { Syncthing.setFolderDevices(folderId, chosen) })
        }
    }

    private fun askRemove(f: SyncFolder) = confirm(
        "sync-remove-folder", R.string.sync_remove_folder_title,
        { getString(R.string.sync_remove_folder_message, f.label, SyncFormat.localFolder(requireContext(), f)) }, R.string.sync_remove,
    ) {
        change(R.string.error_sync_change, { Syncthing.removeFolder(folderId) }) { ok ->
            if (ok) activity?.finish()
        }
    }

    /**
     * Another directory of the sync storage for this folder: the folder is
     * added again there with the same ID and settings and starts from an
     * empty index, so its files come from the other devices (or from files
     * already in that directory); the old directory keeps its files.
     */
    private fun chooseLocation() {
        val f = folder ?: return
        val snap = snapshot ?: return
        // A send-only folder takes no files from the other devices: moved to an
        // empty index it would stay empty (and Override would then delete them elsewhere).
        if (f.type == Syncthing.TYPE_SEND_ONLY) return
        launchUi {
            val dirs = attempt("dirs", R.string.error_load_dirs) { Syncthing.dirs() } ?: return@launchUi
            val used = snap.folders.filter { it.id != f.id }.mapNotNull { o -> o.dirName?.let { it to o.label } }.toMap()
            SyncDialogs.chooseDir(this@FolderFragment, dirs, used, Syncthing.dirNameFor(f.label, used.keys + dirs), f.dirName) { dir ->
                if (dir == f.dirName) return@chooseDir
                confirm(
                    "sync-move", R.string.sync_move_title,
                    {
                        getString(R.string.sync_move_message, f.label,
                            getString(R.string.sync_local_folder_value, dir), SyncFormat.localFolder(requireContext(), f))
                    },
                    R.string.sync_move_confirm,
                ) {
                    change(R.string.error_sync_move, { Syncthing.moveFolder(folderId, dir) })
                }
            }
        }
    }

    /** The second Override confirmation: how many items it deletes on every other device. */
    private fun confirmOverrideDeletes() {
        val f = folder ?: return
        val n = f.overrideDeletes
        confirm(
            "sync-override-deletes", R.string.sync_override_delete_title,
            {
                if (n > 0) {
                    resources.getQuantityString(R.plurals.sync_override_delete_message, n, n, f.label)
                } else {
                    getString(R.string.sync_override_delete_unknown)
                }
            },
            R.string.sync_override_delete_confirm,
        ) {
            change(R.string.error_sync_override, { Syncthing.override(folderId, deleteMissing = true) })
        }
    }

    /** Encrypted sharing per device: a password makes the folder unreadable to that device. */
    private fun chooseEncryption() {
        val f = folder ?: return
        val snap = snapshot ?: return
        showDialog("sync-encryption") { d ->
            val items = f.devices.map { id ->
                val device = snap.device(id)
                val state = when {
                    id in f.encryptedFor -> getString(R.string.sync_encryption_on)
                    device?.untrusted == true -> getString(R.string.sync_encryption_needed)
                    else -> getString(R.string.sync_encryption_off)
                }
                getString(R.string.sync_encryption_item, snap.deviceName(id), state)
            }
            androidx.appcompat.app.AlertDialog.Builder(d.context)
                .setTitle(R.string.sync_encryption)
                .setItems(items.toTypedArray()) { _, which -> editPassword(f, f.devices[which], snap.deviceName(f.devices[which])) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    /** The password field is secret: a recreated dialog asks for it again from an empty field. */
    private fun editPassword(f: SyncFolder, device: String, name: String) = showDialog("sync-encryption-password") { d ->
        val encrypted = device in f.encryptedFor
        InputDialog.create(
            d.context, R.string.sync_encryption_password,
            message = getString(R.string.sync_encryption_password_message, name, f.label),
            password = true,
            neutral = if (encrypted) R.string.sync_encryption_remove else null,
            onNeutral = if (encrypted) ({
                confirm(
                    "sync-encryption-remove", R.string.sync_encryption_remove_title,
                    { getString(R.string.sync_encryption_remove_message, name, f.label) }, R.string.sync_encryption_remove,
                ) {
                    change(R.string.error_sync_change, { Syncthing.setDevicePassword(f, device, null) })
                }
            }) else null,
            validate = { if (it.isEmpty()) "" else null },
        ) { password ->
            change(R.string.error_sync_change, { Syncthing.setDevicePassword(f, device, password) })
        }
    }

    private fun setPaused(paused: Boolean) {
        if (busy) return
        busy = true
        mainSwitch.isEnabled = false
        change(if (paused) R.string.error_sync_pause else R.string.error_sync_resume, { Syncthing.setFolderPaused(folderId, paused) }) {
            busy = false
        }
    }

    private fun rescan() {
        if (busy) return
        busy = true
        rescanPref.isEnabled = false
        change(R.string.error_sync_rescan, { Syncthing.rescan(folderId) }) { busy = false }
    }

    private fun chooseInterval() {
        val f = folder ?: return
        val values = (SyncFormat.RESCAN_INTERVALS + f.rescanIntervalS).distinct().sorted()
        showDialog("sync-interval") { d ->
            val labels = values.map { SyncFormat.intervalLabel(d.context, it) }
            AlertDialog.Builder(d.context)
                .setTitle(R.string.sync_rescan_interval)
                .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(f.rescanIntervalS)) { dialog, which ->
                    dialog.dismiss()
                    if (values[which] != f.rescanIntervalS) patch(JSONObject().put("rescanIntervalS", values[which]))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    companion object {
        const val ARG_FOLDER = "folder"
        private const val MAX_ITEMS = 200

        fun open(context: Context, id: String, label: String) {
            Pages.open(context, FolderFragment::class.java, R.string.sync_folders_category, title = label,
                arguments = Bundle().apply { putString(ARG_FOLDER, id) })
        }
    }
}
