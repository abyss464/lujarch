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

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.lujarch.settings.pages.PageRequest
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.InputDialog

/** A Sync page: runs the owner's changes and reloads afterwards. */
abstract class SyncPageFragment : BasePreferenceFragment() {
    internal var pendingDeviceId: String? = null

    /**
     * What the QR scanner's device ID is for while the scanner is open (a
     * SyncDialogs purpose), in the saved state: a page recreated meanwhile,
     * or restored in a restarted process, gets the result and goes on.
     */
    private val scanFor = PageRequest(this, "sync-scan")

    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val purpose = scanFor.take()?.firstOrNull()
        val id = SyncDialogs.scanned(result.resultCode, result.data) ?: return@registerForActivityResult
        if (purpose == null) {
            Log.w(TAG, "${javaClass.simpleName}: a scanned device ID came back without its request; ignored")
        } else {
            SyncDialogs.checkId(this, id, purpose)
        }
    }

    /** Opens the QR scanner for a device ID for [purpose]. */
    internal fun scanDeviceId(intent: Intent, purpose: String) {
        scanFor.start(purpose)
        try {
            scanner.launch(intent)
        } catch (e: ActivityNotFoundException) {
            scanFor.take()
            Log.w(TAG, "${javaClass.simpleName}: the QR scanner cannot be opened: ${e.message}")
            Toast.makeText(requireContext(), R.string.error_page, Toast.LENGTH_LONG).show()
        }
    }

    /** A device ID typed in or scanned, checked by Syncthing, for [purpose] (SyncDialogs.PURPOSE_*). */
    open fun onDeviceId(purpose: String, id: String) {
        Log.w(TAG, "${javaClass.simpleName}: a device ID for $purpose, which this page does not take; ignored")
    }

    /** Runs a change; a failure is logged and shown in a toast; then the page reloads. */
    fun change(@StringRes error: Int, block: suspend () -> Unit, after: (Boolean) -> Unit = {}) {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = act(error, block)
            if (isAdded) {
                after(ok)
                refresh()
            }
        }
    }
}

/** Dialogs shared by the Sync pages. */
object SyncDialogs {
    private const val SCAN_ACTION = "com.google.zxing.client.android.SCAN"

    /** One line of text, a page dialog of [page]; [validate] and [onDone] run with the page that is there. */
    fun <P : SyncPageFragment> editText(
        page: P,
        @StringRes title: Int,
        initial: String,
        @StringRes message: Int? = null,
        @RebuiltWithPage validate: P.(String) -> String? = { null },
        @RebuiltWithPage onDone: P.(String) -> Unit,
    ) {
        page.showDialog("sync-text") { d ->
            InputDialog.create(
                d.context, title,
                message = message?.let { getString(it) },
                initial = initial,
                validate = { validate(it) },
                onDone = { onDone(it) },
            )
        }
    }

    /** A device ID to add (the page's onDeviceId goes on with its name and addresses). */
    const val PURPOSE_ADD = "add-device"

    /** A device ID that replaces the device of the page (DeviceFragment). */
    const val PURPOSE_REPLACE = "replace-device"

    /**
     * A device ID typed in or scanned, checked and put in Syncthing's form
     * by Syncthing itself, then handed to the page's onDeviceId with
     * [purpose] (a scan's result also reaches a page recreated meanwhile).
     * [message]: its argument says whether a QR scanner is installed.
     */
    fun <P : SyncPageFragment> askDeviceId(
        page: P,
        @StringRes title: Int,
        @RebuiltWithPage message: P.(scan: Boolean) -> CharSequence,
        purpose: String,
    ) {
        page.showDialog("sync-device-id") { d ->
            val context = d.context
            val scanner = Intent(SCAN_ACTION).putExtra("SCAN_MODE", "QR_CODE_MODE")
            val canScan = scanner.resolveActivity(context.packageManager) != null
            InputDialog.create(
                context, title,
                message = message(canScan),
                hint = getString(R.string.sync_device_id_hint),
                positive = R.string.sync_next,
                neutral = if (canScan) R.string.sync_scan else null,
                onNeutral = { scanDeviceId(scanner, purpose) },
                validate = { text ->
                    val plain = text.replace("-", "").replace(" ", "").uppercase()
                    when {
                        text.isEmpty() -> ""
                        plain.length != 52 && plain.length != 56 -> getString(R.string.sync_device_id_length)
                        // Syncthing itself reads 0, 1 and 8 as O, I and B (/rest/svc/deviceid).
                        plain.any { it !in 'A'..'Z' && it !in '0'..'9' } -> getString(R.string.sync_device_id_chars)
                        else -> null
                    }
                },
            ) { text -> checkId(this, text, purpose) }
        }
    }

    /** The text a scanner returned, or null (cancelled, nothing read). */
    fun scanned(resultCode: Int, data: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) data?.getStringExtra("SCAN_RESULT") else null

    internal fun checkId(page: SyncPageFragment, text: String, purpose: String) {
        val context = page.requireContext()
        page.change(R.string.error_sync_device_id, {
            val reason = StringBuilder()
            val id = Syncthing.normalizeDeviceId(text, reason)
                ?: throw IllegalArgumentException(context.getString(R.string.sync_device_id_invalid, reason.toString()))
            page.pendingDeviceId = id
        }) { ok ->
            val id = page.pendingDeviceId
            page.pendingDeviceId = null
            if (ok && id != null) page.onDeviceId(purpose, id)
        }
    }

    /**
     * The name of a device being added (typed, scanned or asking to
     * connect), then its addresses; its page opens afterwards, where
     * folders are shared with it.
     */
    fun <P : SyncPageFragment> askName(page: P, id: String, name: String, @RebuiltWithPage onAdded: P.() -> Unit) {
        page.showDialog("sync-device-name") { d ->
            InputDialog.create(
                d.context, R.string.sync_add_device,
                message = getString(R.string.sync_add_device_name_message, id),
                initial = name,
                hint = getString(R.string.sync_device_name),
                positive = R.string.sync_next,
                validate = { if (it.isEmpty()) getString(R.string.sync_name_required) else null },
            ) { chosen ->
                editAddresses(this, R.string.sync_add_device, listOf(Syncthing.DYNAMIC), R.string.sync_add) { addresses ->
                    change(R.string.error_sync_add_device, { Syncthing.addDevice(id, chosen, addresses) }) { ok ->
                        if (ok) {
                            onAdded()
                            // Folders are shared with it on its page.
                            DeviceFragment.open(requireContext(), id, chosen)
                        }
                    }
                }
            }
        }
    }

    /**
     * A device's addresses, one per line (commas and spaces also separate
     * them), with what dynamic means and what a fixed address that does not
     * answer costs: it is dialled again at the retry interval, which keeps
     * waking the mobile radio while the phone is away from that network
     * (docs/baseline.md F1). "Only dynamic" replaces the list with dynamic.
     */
    fun <P : SyncPageFragment> editAddresses(
        page: P,
        @StringRes title: Int,
        current: List<String>,
        @StringRes positive: Int = android.R.string.ok,
        @RebuiltWithPage onDone: P.(List<String>) -> Unit,
    ) {
        page.viewLifecycleOwner.lifecycleScope.launch {
            val interval = try {
                Syncthing.options().reconnectionIntervalS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sync: retry interval not read for the address editor: ${e.message}")
                null
            }
            if (!page.isAdded) return@launch
            page.showDialog("sync-addresses") { d ->
                val message = getString(R.string.sync_addresses_message) + "\n\n" +
                    (interval?.let { getString(R.string.sync_addresses_retry, it) } ?: getString(R.string.sync_addresses_retry_unknown)) +
                    " " + getString(R.string.sync_addresses_tradeoff)
                InputDialog.create(
                    d.context, title,
                    message = message,
                    initial = current.joinToString("\n"),
                    positive = positive,
                    singleLine = false,
                    neutral = R.string.sync_addresses_only_dynamic,
                    onNeutral = { onDone(listOf(Syncthing.DYNAMIC)) },
                    validate = { text ->
                        val list = parseList(text)
                        when {
                            list.size > MAX_LIST -> getString(R.string.sync_list_too_long, MAX_LIST)
                            else -> list.indexOfFirst { !DEVICE_ADDRESS.matches(it) }.takeIf { it >= 0 }
                                ?.let { getString(R.string.sync_list_invalid_entry, list[it]) }
                        }
                    },
                ) { text -> onDone(parseList(text).ifEmpty { listOf(Syncthing.DYNAMIC) }) }
            }
        }
    }

    /** Entries of a list typed one per line; commas and spaces also separate them. */
    fun parseList(text: String): List<String> =
        text.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** At most this many entries in each address list (the system service's limit). */
    const val MAX_LIST = 16

    // The shape of an address; the system service checks hosts, ports and IDs.
    private val DEVICE_ADDRESS =
        Regex("^(dynamic|(tcp|tcp4|tcp6|quic|quic4|quic6)://[^\\s/?@]+/?|relay://[^\\s/?@]+:[0-9]+/?(\\?\\S*)?)$")

    /** Choice of devices a folder is shared with. */
    fun <P : SyncPageFragment> chooseDevices(
        page: P,
        devices: List<SyncDevice>,
        selected: Collection<String>,
        @RebuiltWithPage onChosen: P.(List<String>) -> Unit,
    ) {
        if (devices.isEmpty()) {
            page.showDialog("sync-no-devices") { d ->
                AlertDialog.Builder(d.context)
                    .setTitle(R.string.sync_shared_with)
                    .setMessage(R.string.sync_no_devices_to_share)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            }
            return
        }
        // The dialog's own state: a recreated window shows the owner's checks.
        val checked = BooleanArray(devices.size) { devices[it].id in selected }
        page.showDialog("sync-devices") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(R.string.sync_shared_with)
                .setMultiChoiceItems(devices.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    onChosen(devices.filterIndexed { i, _ -> checked[i] }.map { it.id })
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    /** Single choice among the folder types, with what each does. */
    fun <P : SyncPageFragment> chooseType(page: P, current: String, @RebuiltWithPage onChosen: P.(String) -> Unit) {
        page.showDialog("sync-type") { d ->
            val context = d.context
            val labels = SyncFormat.TYPES.map { type ->
                context.getString(R.string.sync_type_choice, SyncFormat.typeLabel(context, type), SyncFormat.typeSummary(context, type))
            }
            AlertDialog.Builder(context)
                .setTitle(R.string.sync_folder_type)
                .setSingleChoiceItems(labels.toTypedArray(), SyncFormat.TYPES.indexOf(current)) { dialog, which ->
                    dialog.dismiss()
                    if (SyncFormat.TYPES[which] != current) onChosen(SyncFormat.TYPES[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    /**
     * The local folder: a directory under the folders root, the only place
     * the sync service may write. Existing directories another folder uses
     * are not offered; a new name creates the directory when the folder is
     * added.
     */
    fun <P : SyncPageFragment> chooseDir(
        page: P,
        dirs: List<String>,
        used: Map<String, String>,
        suggested: String,
        current: String?,
        @RebuiltWithPage onChosen: P.(String) -> Unit,
    ) {
        val free = dirs.filter { it !in used || it == current }
        page.showDialog("sync-dir") { d ->
            val items = ArrayList<String>()
            items.add(getString(R.string.sync_dir_new))
            free.forEach { items.add(getString(R.string.sync_local_folder_value, it)) }
            AlertDialog.Builder(d.context)
                .setTitle(R.string.sync_local_folder)
                .setItems(items.toTypedArray()) { _, which ->
                    if (which == 0) newDir(this, used, suggested, onChosen) else onChosen(free[which - 1])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun <P : SyncPageFragment> newDir(page: P, used: Map<String, String>, suggested: String, @RebuiltWithPage onChosen: P.(String) -> Unit) {
        page.showDialog("sync-dir-new") { d ->
            InputDialog.create(
                d.context, R.string.sync_dir_new,
                message = getString(R.string.sync_dir_new_message),
                initial = suggested,
                validate = { name ->
                    when {
                        name.isEmpty() -> ""
                        !Syncthing.isDirName(name) -> getString(R.string.sync_dir_invalid)
                        name in used -> getString(R.string.sync_dir_used, used[name])
                        else -> null
                    }
                },
            ) { onChosen(it) }
        }
    }
}
