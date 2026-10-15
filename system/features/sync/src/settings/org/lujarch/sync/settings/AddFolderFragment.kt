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
import com.android.settingslib.widget.TopIntroPreference
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * Adds a folder, new or offered by another device: its name, ID, local
 * folder (a directory the owner picks or names under the folders root),
 * type, the devices it is shared with and the encryption password of each
 * device that gets it encrypted. A folder offered encrypted is kept
 * receive-encrypted: this device stores data it cannot read.
 */
class AddFolderFragment : SyncPageFragment() {
    private var accept = false
    private lateinit var folderId: String
    private lateinit var label: String
    private var dir: String? = null
    private var type = Syncthing.TYPE_SEND_RECEIVE
    private var devices: List<String> = emptyList()
    private var dirs: List<String> = emptyList()
    private var snapshot: SyncSnapshot? = null
    private var adding = false
    /** Offered encrypted: this device keeps it receive-encrypted. */
    private var receiveEncrypted = false
    /** Devices that keep the folder encrypted themselves and need a password. */
    private var remoteEncrypted: Set<String> = emptySet()
    /** Device -> encryption password; in memory only, never saved with the page state. */
    private val passwords = HashMap<String, String>()

    private lateinit var labelPref: Preference
    private lateinit var idPref: Preference
    private lateinit var dirPref: Preference
    private lateinit var typePref: Preference
    private lateinit var devicesPref: Preference
    private lateinit var encryptionPref: Preference
    private lateinit var addPref: Preference

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val args = arguments ?: Bundle()
        accept = args.getBoolean(ARG_ACCEPT)
        folderId = savedInstanceState?.getString(ARG_FOLDER) ?: args.getString(ARG_FOLDER) ?: Syncthing.newFolderId()
        label = savedInstanceState?.getString(ARG_LABEL) ?: args.getString(ARG_LABEL) ?: ""
        dir = savedInstanceState?.getString(STATE_DIR)
        type = savedInstanceState?.getString(STATE_TYPE) ?: Syncthing.TYPE_SEND_RECEIVE
        devices = savedInstanceState?.getStringArrayList(ARG_DEVICES) ?: args.getStringArrayList(ARG_DEVICES) ?: emptyList()
        receiveEncrypted = args.getBoolean(ARG_RECEIVE_ENCRYPTED)
        remoteEncrypted = args.getStringArrayList(ARG_REMOTE_ENCRYPTED)?.toSet() ?: emptySet()
        if (receiveEncrypted) type = Syncthing.TYPE_RECEIVE_ENCRYPTED

        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(if (accept) R.string.sync_accept_folder_intro else R.string.sync_add_folder_intro)
        }
        val folder = screen.add(PreferenceCategory(prefContext)) { key = "folder" }
        labelPref = folder.add(Preference(prefContext)) {
            key = "label"
            setTitle(R.string.sync_folder_label)
            setOnPreferenceClickListener {
                editLabel()
                true
            }
        }
        idPref = folder.add(Preference(prefContext)) {
            key = "id"
            setTitle(R.string.sync_folder_id)
            isEnabled = !accept
            setOnPreferenceClickListener {
                editId()
                true
            }
        }
        dirPref = folder.add(Preference(prefContext)) {
            key = "dir"
            setTitle(R.string.sync_local_folder)
            setOnPreferenceClickListener {
                chooseDir()
                true
            }
        }
        typePref = folder.add(Preference(prefContext)) {
            key = "type"
            setTitle(R.string.sync_folder_type)
            setOnPreferenceClickListener {
                if (!receiveEncrypted) chooseType()
                true
            }
        }
        devicesPref = folder.add(Preference(prefContext)) {
            key = "devices"
            setTitle(R.string.sync_shared_with)
            setOnPreferenceClickListener {
                chooseDevices()
                true
            }
        }
        encryptionPref = folder.add(Preference(prefContext)) {
            key = "encryption"
            setTitle(R.string.sync_encryption)
            setIcon(R.drawable.ic_key)
            setOnPreferenceClickListener {
                chooseEncryption()
                true
            }
        }
        addPref = screen.add(PreferenceCategory(prefContext)) { key = "actions" }.add(Preference(prefContext)) {
            key = "add"
            setTitle(if (accept) R.string.sync_accept_folder else R.string.sync_add_folder)
            setIcon(R.drawable.ic_check)
            setOnPreferenceClickListener {
                add()
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_local_folder_footer)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ARG_FOLDER, folderId)
        outState.putString(ARG_LABEL, label)
        outState.putString(STATE_DIR, dir)
        outState.putString(STATE_TYPE, type)
        outState.putStringArrayList(ARG_DEVICES, ArrayList(devices))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            snapshot = attempt("sync", R.string.error_load_sync) { Syncthing.snapshot() } ?: snapshot
            dirs = attempt("dirs", R.string.error_load_dirs) { Syncthing.dirs() } ?: dirs
            if (dir == null && label.isNotEmpty()) dir = Syncthing.dirNameFor(label, usedDirs().keys + dirs)
            render()
        }
    }

    /** Directories other folders use: name -> folder label. */
    private fun usedDirs(): Map<String, String> =
        snapshot?.folders?.mapNotNull { f -> f.dirName?.let { it to f.label } }?.toMap() ?: emptyMap()

    private fun render() {
        val context = context ?: return
        labelPref.summary = label.ifEmpty { getString(R.string.sync_value_required) }
        idPref.summary = folderId
        dirPref.summary = dir?.let { getString(R.string.sync_local_folder_value, it) } ?: getString(R.string.sync_value_required)
        typePref.summary = SyncFormat.typeLabel(context, type) + "\n" + SyncFormat.typeSummary(context, type)
        devicesPref.summary = if (devices.isEmpty()) getString(R.string.sync_shared_with_none)
        else devices.joinToString(getString(PagesR.string.list_separator)) { snapshot?.deviceName(it) ?: it.substringBefore('-') }
        typePref.isSelectable = !receiveEncrypted
        encryptionPref.isVisible = !receiveEncrypted && devices.isNotEmpty()
        val missing = missingPasswords()
        encryptionPref.summary = when {
            missing.isNotEmpty() -> getString(R.string.sync_encryption_required,
                Format.names(requireContext(), missing.map { snapshot?.deviceName(it) ?: it.substringBefore('-') }))
            passwords.keys.any { it in devices } -> getString(R.string.sync_encryption_for,
                Format.names(requireContext(), passwords.keys.filter { it in devices }.map { snapshot?.deviceName(it) ?: it }))
            else -> getString(R.string.sync_encryption_none)
        }
        addPref.isEnabled = !adding && snapshot != null && label.isNotEmpty() && dir != null && folderId.isNotEmpty() &&
            missing.isEmpty()
    }

    private fun editLabel() = SyncDialogs.editText(
        this, R.string.sync_folder_label, label,
        validate = { if (it.isEmpty()) getString(R.string.sync_name_required) else null },
    ) {
        label = it
        render()
    }

    private fun editId() = SyncDialogs.editText(
        this, R.string.sync_folder_id, folderId, R.string.sync_folder_id_message,
        validate = { id ->
            when {
                id.isEmpty() -> ""
                id.length > 256 || id.any { it.code < 0x20 } -> getString(R.string.sync_folder_id_invalid)
                snapshot?.folder(id) != null -> getString(R.string.sync_folder_id_used)
                else -> null
            }
        },
    ) {
        folderId = it
        render()
    }

    private fun chooseType() = SyncDialogs.chooseType(this, type) {
        type = it
        render()
    }

    private fun chooseDevices() {
        val snap = snapshot ?: return
        SyncDialogs.chooseDevices(this, snap.devices, devices) {
            devices = it
            render()
        }
    }

    private fun chooseDir() {
        val used = usedDirs()
        SyncDialogs.chooseDir(this, dirs, used, Syncthing.dirNameFor(label, used.keys + dirs), dir) {
            dir = it
            render()
        }
    }

    /** Devices that must get the folder encrypted but have no password yet. */
    private fun missingPasswords(): List<String> {
        if (receiveEncrypted) return emptyList()
        val untrusted = snapshot?.devices.orEmpty().filter { it.untrusted }.map { it.id }.toSet()
        return devices.filter { (it in remoteEncrypted || it in untrusted) && passwords[it].isNullOrEmpty() }
    }

    private fun chooseEncryption() {
        val snap = snapshot ?: return
        val chosen = devices
        showDialog("sync-encryption") { d ->
            val items = chosen.map { id ->
                val state = when {
                    !passwords[id].isNullOrEmpty() -> getString(R.string.sync_encryption_on)
                    id in remoteEncrypted || snap.device(id)?.untrusted == true -> getString(R.string.sync_encryption_needed)
                    else -> getString(R.string.sync_encryption_off)
                }
                getString(R.string.sync_encryption_item, snap.deviceName(id), state)
            }
            androidx.appcompat.app.AlertDialog.Builder(d.context)
                .setTitle(R.string.sync_encryption)
                .setItems(items.toTypedArray()) { _, which -> editPassword(chosen[which], snap.deviceName(chosen[which])) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    /**
     * A device's password, kept by this page until the folder is added (never
     * in the saved state). The field is secret: a recreated dialog asks for
     * it again from an empty field.
     */
    private fun editPassword(id: String, name: String) = showDialog("sync-encryption-password") { d ->
        org.lujarch.settings.pages.InputDialog.create(
            d.context, R.string.sync_encryption_password,
            message = getString(R.string.sync_encryption_password_message, name, label.ifEmpty { folderId }),
            password = true,
            neutral = if (passwords.containsKey(id)) R.string.sync_encryption_remove else null,
            onNeutral = if (passwords.containsKey(id)) ({
                passwords.remove(id)
                render()
            }) else null,
            validate = { if (it.isEmpty()) "" else null },
        ) { password ->
            passwords[id] = password
            render()
        }
    }

    private fun add() {
        val chosen = dir ?: return
        if (adding) return
        adding = true
        render()
        val chosenPasswords = passwords.filterKeys { it in devices }
        change(if (accept) R.string.error_sync_accept_folder else R.string.error_sync_add_folder, {
            Syncthing.addFolder(folderId, label, chosen, type, devices, chosenPasswords)
        }) { ok ->
            adding = false
            if (ok) activity?.finish() else render()
        }
    }

    companion object {
        const val ARG_ACCEPT = "accept"
        const val ARG_FOLDER = "folder"
        const val ARG_LABEL = "label"
        const val ARG_DEVICES = "devices"
        const val ARG_RECEIVE_ENCRYPTED = "receive_encrypted"
        const val ARG_REMOTE_ENCRYPTED = "remote_encrypted"
        private const val STATE_DIR = "dir"
        private const val STATE_TYPE = "type"

        /**
         * Accepts a folder [devices] offer; [receiveEncrypted]: offered
         * encrypted (kept receive-encrypted here); [remoteEncrypted]: devices
         * that keep it encrypted and need a password from this device.
         */
        fun openAccept(
            context: Context,
            id: String,
            label: String,
            devices: List<String>,
            receiveEncrypted: Boolean = false,
            remoteEncrypted: Collection<String> = emptyList(),
        ) {
            Pages.open(context, AddFolderFragment::class.java, R.string.sync_accept_folder,
                arguments = Bundle().apply {
                    putBoolean(ARG_ACCEPT, true)
                    putString(ARG_FOLDER, id)
                    putString(ARG_LABEL, label)
                    putStringArrayList(ARG_DEVICES, ArrayList(devices))
                    putBoolean(ARG_RECEIVE_ENCRYPTED, receiveEncrypted)
                    putStringArrayList(ARG_REMOTE_ENCRYPTED, ArrayList(remoteEncrypted))
                })
        }
    }
}
