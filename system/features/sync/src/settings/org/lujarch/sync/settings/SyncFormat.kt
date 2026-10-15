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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.text.format.Formatter
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.lujarch.settings.pages.showDialog
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.InputDialog

/** The sync service's state in words. */
fun syncStateLabel(context: Context, state: String): String = when (state) {
    SyncInfo.STATE_RUNNING -> context.getString(R.string.state_running)
    SyncInfo.STATE_NO_CONFIG -> context.getString(R.string.sync_state_no_identity)
    SyncInfo.STATE_CRASH_LOOP -> context.getString(R.string.state_crash_loop)
    SyncInfo.STATE_STARTING -> context.getString(R.string.sync_state_starting)
    SyncInfo.STATE_OFF, "" -> context.getString(R.string.state_stopped)
    SyncInfo.STATE_WAITING -> context.getString(R.string.sync_state_waiting)
    else -> state
}

/** What a stopped sync waits for ("wifi", "charging"), in words. */
fun waitingLabel(context: Context, waiting: List<String>): String = when {
    "wifi" in waiting && "charging" in waiting -> context.getString(R.string.sync_waiting_wifi_charging)
    "wifi" in waiting -> context.getString(R.string.sync_waiting_wifi)
    "charging" in waiting -> context.getString(R.string.sync_waiting_charging)
    else -> context.getString(R.string.sync_state_waiting)
}

/** One-line sync summary, for the Sync tile in Settings > Connected devices. */
fun syncStateSummary(context: Context, info: SyncInfo): String =
    if (info.syncEnabled) {
        context.getString(R.string.sync_summary_on, syncStateLabel(context, info.syncState))
    } else {
        context.getString(R.string.sync_summary_off)
    }

/** Texts shared by the Sync pages. */
object SyncFormat {
    /** "192.168.1.2:22000", "tcp://[::1]:22000" -> host. */
    fun hostOf(address: String): String {
        val plain = address.substringAfter("://")
        val host = plain.substringBeforeLast(':', plain)
        return host.removePrefix("[").removeSuffix("]")
    }

    /** The folder's state in one line (without file counts). */
    fun folderState(context: Context, f: SyncFolder): String = when {
        f.paused -> context.getString(R.string.sync_paused)
        f.error.isNotEmpty() -> context.getString(R.string.sync_folder_error, f.error)
        f.state == "syncing" || f.state == "sync-preparing" -> context.getString(R.string.sync_folder_syncing, f.completion)
        f.state == "scanning" || f.state == "scan-waiting" -> context.getString(R.string.sync_folder_scanning)
        f.state == "idle" && f.needItems == 0 -> context.getString(R.string.sync_folder_up_to_date)
        f.needItems > 0 -> context.resources.getQuantityString(R.plurals.sync_folder_out_of_sync, f.needItems, f.needItems)
        f.state.isEmpty() -> context.getString(R.string.status_unknown)
        else -> context.getString(R.string.sync_folder_waiting)
    }

    /** The folder's summary row: state, problems, size. */
    fun folderSummary(context: Context, f: SyncFolder): String {
        val parts = arrayListOf(folderState(context, f))
        val res = context.resources
        if (f.failedItems > 0) parts.add(res.getQuantityString(R.plurals.sync_folder_failed, f.failedItems, f.failedItems))
        if (f.conflicts > 0) parts.add(res.getQuantityString(R.plurals.sync_folder_conflicts, f.conflicts, f.conflicts))
        if (!f.paused) {
            parts.add(
                res.getQuantityString(
                    R.plurals.sync_folder_files, f.globalFiles, f.globalFiles,
                    Formatter.formatShortFileSize(context, f.globalBytes),
                )
            )
        }
        return parts.joinToString(context.getString(R.string.separator))
    }

    /** [shownHost]: a host the row already names; the connection's host is left out when it is the same. */
    fun deviceSummary(context: Context, d: SyncDevice, shownHost: String? = null): String = when {
        d.paused -> context.getString(R.string.sync_paused)
        d.connected -> listOfNotNull(
            context.getString(R.string.sync_connected),
            d.address?.let { hostOf(it) }?.takeIf { it != shownHost },
            d.clientVersion,
        ).joinToString(context.getString(R.string.separator))
        d.lastSeen != null -> context.getString(R.string.sync_last_seen, Format.ago(context, d.lastSeen))
        else -> context.getString(R.string.sync_never_seen)
    }

    /** Where a fixed address of a device leads. */
    enum class Reach { CLOUD, LAN }

    /**
     * A tcp:// or quic:// address: CLOUD when the phone reaches it from any
     * network (a public address or a host name), LAN for private, link-local
     * and shared (CGNAT) ranges and local names; null for dynamic and relays.
     */
    fun reachOf(address: String): Reach? {
        val scheme = address.substringBefore("://", "")
        if (scheme !in DIRECT_SCHEMES) return null
        val host = hostOf(address.substringAfter("://").substringBefore('/').let { "x://$it" })
            .substringBefore('%').lowercase()
        return if (isLocalHost(host)) Reach.LAN else Reach.CLOUD
    }

    private val DIRECT_SCHEMES = setOf("tcp", "tcp4", "tcp6", "quic", "quic4", "quic6")

    private fun isLocalHost(host: String): Boolean {
        if (host.isEmpty() || host == "localhost" || '.' !in host && ':' !in host) return true
        if (listOf(".local", ".lan", ".home", ".home.arpa", ".internal").any { host.endsWith(it) }) return true
        val v4 = host.split('.')
        if (v4.size == 4 && v4.all { it.toIntOrNull() in 0..255 }) {
            val a = v4[0].toInt()
            val b = v4[1].toInt()
            return a == 10 || a == 127 || a == 0 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (':' in host) {
            return host == "::1" || host == "::" || host.startsWith("fc") || host.startsWith("fd") ||
                listOf("fe8", "fe9", "fea", "feb").any { host.startsWith(it) }
        }
        return false
    }

    /**
     * A device's row on the Sync page: a device with a fixed address shows
     * where it is reached ("Cloud · tcp://203.0.113.7:20023 · Connected"),
     * the cloud address first; others show the connection alone.
     */
    fun deviceRowSummary(context: Context, d: SyncDevice): String {
        val fixed = d.addresses.filter { reachOf(it) != null }
        val cloud = fixed.filter { reachOf(it) == Reach.CLOUD }
        val shown = cloud.firstOrNull() ?: fixed.firstOrNull() ?: return deviceSummary(context, d)
        val role = context.getString(if (cloud.isNotEmpty()) R.string.sync_reach_cloud else R.string.sync_reach_lan)
        val address = if (fixed.size > 1) context.getString(R.string.sync_address_more, shown, fixed.size - 1) else shown
        return listOf(role, address, deviceSummary(context, d, hostOf(shown)))
            .joinToString(context.getString(R.string.separator))
    }

    /** A device's addresses, one per line, "dynamic" in words and fixed ones with where they lead. */
    fun addressesLabel(context: Context, addresses: List<String>): String =
        addresses.ifEmpty { listOf(Syncthing.DYNAMIC) }.joinToString("\n") { a ->
            when (reachOf(a)) {
                Reach.CLOUD -> context.getString(R.string.sync_address_with_reach, a, context.getString(R.string.sync_reach_cloud))
                Reach.LAN -> context.getString(R.string.sync_address_with_reach, a, context.getString(R.string.sync_reach_lan))
                null -> if (a == Syncthing.DYNAMIC) context.getString(R.string.sync_address_dynamic) else a
            }
        }

    fun typeLabel(context: Context, type: String): String = when (type) {
        Syncthing.TYPE_SEND_RECEIVE -> context.getString(R.string.sync_type_send_receive)
        Syncthing.TYPE_SEND_ONLY -> context.getString(R.string.sync_type_send_only)
        Syncthing.TYPE_RECEIVE_ONLY -> context.getString(R.string.sync_type_receive_only)
        Syncthing.TYPE_RECEIVE_ENCRYPTED -> context.getString(R.string.sync_type_receive_encrypted)
        else -> type
    }

    val TYPES = listOf(Syncthing.TYPE_SEND_RECEIVE, Syncthing.TYPE_SEND_ONLY, Syncthing.TYPE_RECEIVE_ONLY)

    fun typeSummary(context: Context, type: String): String = when (type) {
        Syncthing.TYPE_SEND_RECEIVE -> context.getString(R.string.sync_type_send_receive_summary)
        Syncthing.TYPE_SEND_ONLY -> context.getString(R.string.sync_type_send_only_summary)
        Syncthing.TYPE_RECEIVE_ONLY -> context.getString(R.string.sync_type_receive_only_summary)
        Syncthing.TYPE_RECEIVE_ENCRYPTED -> context.getString(R.string.sync_type_receive_encrypted_summary)
        else -> ""
    }

    fun versioningLabel(context: Context, v: Versioning): String = when (v.type) {
        Versioning.NONE -> context.getString(R.string.sync_versioning_none)
        Versioning.TRASHCAN -> context.getString(R.string.sync_versioning_trashcan)
        Versioning.SIMPLE -> context.getString(R.string.sync_versioning_simple)
        Versioning.STAGGERED -> context.getString(R.string.sync_versioning_staggered)
        else -> v.type
    }

    /** The folder's place in the folders root, as the owner sees it. */
    fun localFolder(context: Context, f: SyncFolder): String =
        f.dirName?.let { context.getString(R.string.sync_local_folder_value, it) } ?: f.path

    /** Rescan intervals offered (seconds; 0: no periodic scan). */
    val RESCAN_INTERVALS = listOf(0, 60, 600, 3600, 6 * 3600, 24 * 3600)

    fun intervalLabel(context: Context, seconds: Int): String = when {
        seconds <= 0 -> context.getString(R.string.sync_rescan_never)
        seconds % 86400 == 0 -> context.resources.getQuantityString(R.plurals.sync_every_days, seconds / 86400, seconds / 86400)
        seconds % 3600 == 0 -> context.resources.getQuantityString(R.plurals.sync_every_hours, seconds / 3600, seconds / 3600)
        seconds % 60 == 0 -> context.resources.getQuantityString(R.plurals.sync_every_minutes, seconds / 60, seconds / 60)
        else -> context.resources.getQuantityString(R.plurals.sync_every_seconds, seconds, seconds)
    }

    fun copy(context: Context, label: CharSequence, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.sync_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /** The device ID as a QR code (how other Syncthing apps read it) with a copy button, a page dialog of [page]. */
    fun showDeviceId(page: Fragment, id: String) = page.showDialog("sync-device-id-qr") { d ->
        val context = d.context
        val size = InputDialog.dp(context, 240)
        val image = ImageView(context).apply {
            val pad = InputDialog.dp(context, 16)
            setPadding(pad, pad, pad, 0)
            qr(id, size)?.let { setImageBitmap(it) }
            contentDescription = id
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.sync_device_id)
            .setMessage(twoLines(id))
            .setView(image)
            .setPositiveButton(R.string.sync_copy) { _, _ -> copy(requireContext(), getString(R.string.sync_device_id), id) }
            .setNegativeButton(R.string.action_close, null)
            .create()
    }

    /**
     * A device ID for display, in two lines of four groups: left to wrap, a
     * row or dialog broke it inside a group ("ORAD" / "EF5"), which made it
     * hard to read and compare. Copies and QR codes keep the ID itself.
     */
    fun twoLines(id: String): String {
        val groups = id.split('-')
        if (groups.size != 8) return id
        return groups.take(4).joinToString("-") + "-\n" + groups.drop(4).joinToString("-")
    }

    private fun qr(text: String, size: Int): Bitmap? = try {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }
}
