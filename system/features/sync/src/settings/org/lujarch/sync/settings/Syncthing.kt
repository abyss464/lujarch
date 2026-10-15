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

import android.net.Uri
import android.util.Log
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** A remote device, from the configuration, connections and statistics. */
data class SyncDevice(
    val id: String,
    val name: String,
    val paused: Boolean,
    val connected: Boolean,
    /** Remote address of the current connection. */
    val address: String?,
    /** e.g. "v2.1.5". */
    val clientVersion: String?,
    /** Last time the device was connected, ms since the epoch; null when never. */
    val lastSeen: Long?,
    /** Configured addresses: "dynamic" or scheme://host:port. */
    val addresses: List<String>,
    val introducer: Boolean,
    val autoAcceptFolders: Boolean,
    /** metadata, always or never. */
    val compression: String,
    /** Every folder shared with it must be encrypted with a password (Syncthing's "untrusted"). */
    val untrusted: Boolean = false,
) {
    val shortId: String
        get() = id.substringBefore('-')
}

/**
 * File versioning of a folder (Syncthing's type and parameters). [path]:
 * where versions are kept; "" is the folder's own .stversions directory, a
 * relative path is inside the folder, an absolute one is under the sync
 * storage.
 */
data class Versioning(val type: String, val params: Map<String, String>, val path: String = "") {
    companion object {
        const val NONE = ""
        const val TRASHCAN = "trashcan"
        const val SIMPLE = "simple"
        const val STAGGERED = "staggered"
    }
}

/** A folder and its current state. */
data class SyncFolder(
    val id: String,
    val label: String,
    val path: String,
    /** sendreceive, sendonly, receiveonly or receiveencrypted. */
    val type: String,
    /** IDs of the devices the folder is shared with (this device excluded). */
    val devices: List<String>,
    /** Of [devices], those that get the folder encrypted (a password is set for them). */
    val encryptedFor: Set<String> = emptySet(),
    val paused: Boolean,
    val rescanIntervalS: Int,
    val fsWatcherEnabled: Boolean,
    val versioning: Versioning,
    /** Syncthing's folder state: idle, scanning, syncing, sync-waiting, error, ... */
    val state: String,
    /** Folder-level error (path missing, marker missing), or "". */
    val error: String,
    /** Files that failed to sync (pull errors). */
    val failedItems: Int,
    val needItems: Int,
    val needBytes: Long,
    val globalBytes: Long,
    val globalFiles: Int,
    /**
     * Items the other devices have that this phone lacks, plus deletions it has
     * not taken: what an override deletes on every other device (as the system
     * service counts them); -1 when Syncthing did not report the counts.
     */
    val overrideDeletes: Int = -1,
    val inSyncBytes: Long,
    /** Top-level Syncthing conflict copies (name.sync-conflict-...). */
    val conflicts: Int,
    /** Last completed scan, ms since the epoch; null when unknown. */
    val lastScan: Long?,
    /** Receive only: items changed here that the other devices do not have (revert undoes them). */
    val localChanges: Int = 0,
) {
    val completion: Int
        get() = if (globalBytes <= 0) 100 else ((inSyncBytes * 100) / globalBytes).toInt().coerceIn(0, 100)

    /** The directory name under the folders root, or null for a path elsewhere. */
    val dirName: String?
        get() = path.removeSuffix("/").takeIf { it.startsWith(Syncthing.FOLDERS_ROOT + "/") }
            ?.substring(Syncthing.FOLDERS_ROOT.length + 1)?.takeIf { '/' !in it }
}

/** A device asking to connect that is not configured. */
data class PendingDevice(val id: String, val name: String, val address: String, val time: Long?)

/** A folder another device offers that is not shared with it yet. */
data class PendingFolder(
    val id: String,
    val label: String,
    /** Offering device ID -> label it gave. */
    val offeredBy: Map<String, String>,
    /** The offering device shares it encrypted: this device keeps it receive-encrypted. */
    val receiveEncrypted: Boolean,
    /** Offering devices that keep it encrypted themselves: they need a password from this device. */
    val remoteEncrypted: Set<String> = emptySet(),
)

/** Sync's run conditions (sync.conditions). */
data class SyncConditions(
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    /** null: not known (the system service could not read it). */
    val wifi: Boolean?,
    val charging: Boolean?,
    /** What a stopped sync waits for: "wifi", "charging". */
    val waiting: List<String>,
)

/** A folder moved to another directory (sync.folder.move). */
data class FolderMoved(val path: String, val previous: String?, val paused: Boolean, val ignoresCopied: Int)

/** A device Syncthing ignores (it never asks again). */
data class IgnoredDevice(val id: String, val name: String, val address: String)

/** Discovery, relays, NAT, where this device listens, and rate limits. */
data class SyncOptions(
    val globalAnnounce: Boolean,
    val localAnnounce: Boolean,
    val relays: Boolean,
    val nat: Boolean,
    val maxSendKbps: Int,
    val maxRecvKbps: Int,
    /**
     * Where this device listens, and the relays it joins: "default", tcp:// and
     * quic:// addresses, relay:// servers and dynamic+https:// relay pools.
     */
    val listenAddresses: List<String> = listOf(Syncthing.DEFAULT),
    /** Global discovery servers: "default", "default-v4", "default-v6" or https:// URLs. */
    val globalAnnounceServers: List<String> = listOf(Syncthing.DEFAULT),
    /** How often a device that is not connected is dialled again at each address, in seconds. */
    val reconnectionIntervalS: Int = 60,
)

/** Everything the Sync page shows, read in one poll. */
data class SyncSnapshot(
    val myId: String,
    val myName: String,
    val version: String?,
    val devices: List<SyncDevice>,
    val folders: List<SyncFolder>,
    val pendingDevices: List<PendingDevice>,
    val pendingFolders: List<PendingFolder>,
) {
    fun device(id: String): SyncDevice? = devices.firstOrNull { it.id == id }

    fun folder(id: String): SyncFolder? = folders.firstOrNull { it.id == id }

    fun deviceName(id: String): String = device(id)?.name ?: id.substringBefore('-')
}

/** A failed item of a folder. */
data class FolderError(val path: String, val error: String)

/** Syncthing's REST API through the Lujarch system service (sync.api and sync.*). */
object Syncthing {
    /** The only place synced folders live on this device (extra/syncthing). */
    const val FOLDERS_ROOT = "/data/lujarch/sync/folders"

    const val TYPE_SEND_RECEIVE = "sendreceive"
    const val TYPE_SEND_ONLY = "sendonly"
    const val TYPE_RECEIVE_ONLY = "receiveonly"
    const val TYPE_RECEIVE_ENCRYPTED = "receiveencrypted"

    /** Syncthing's own defaults for listen addresses and discovery servers. */
    const val DEFAULT = "default"

    /** A device address found through discovery. */
    const val DYNAMIC = "dynamic"

    /** What "default" listens on directly (Syncthing's DefaultListenAddresses without the pool). */
    val DEFAULT_DIRECT = listOf("tcp://0.0.0.0:22000", "quic://0.0.0.0:22000")

    /** The public relay pool "default" joins. */
    const val DEFAULT_RELAY_POOL = "dynamic+https://relays.syncthing.net/endpoint"

    /** Never connected: Syncthing reports the epoch (or year 1). */
    private const val NEVER_BEFORE_MS = 86_400_000L

    private fun segment(id: String): String = Uri.encode(id)

    // ---- reading ----

    /** This device's ID, in one request (the snapshot reads every folder and device). */
    suspend fun myId(): String =
        obj("GET", "/rest/system/status").stringOrNull("myID") ?: throw SyncException(
            SyncException.Kind.PROTOCOL, "sync.api GET /rest/system/status", "reply has no myID"
        )

    suspend fun snapshot(): SyncSnapshot {
        val myId = myId()
        val version = (SyncClient.syncApi("GET", "/rest/system/version") as? JSONObject)?.stringOrNull("version")
        val connections = obj("GET", "/rest/system/connections").optJSONObject("connections") ?: JSONObject()
        val deviceStats = obj("GET", "/rest/stats/device")
        val folderStats = obj("GET", "/rest/stats/folder")

        var myName = ""
        val devices = ArrayList<SyncDevice>()
        for (d in objects(SyncClient.syncApi("GET", "/rest/config/devices"))) {
            val id = d.stringOrNull("deviceID") ?: continue
            if (id == myId) {
                myName = d.stringOrNull("name") ?: ""
                continue
            }
            val conn = connections.optJSONObject(id)
            devices.add(device(d, id, conn, deviceStats.optJSONObject(id)))
        }
        devices.sortWith(compareBy({ !it.connected }, { it.name.lowercase() }))

        val folders = ArrayList<SyncFolder>()
        for (f in objects(SyncClient.syncApi("GET", "/rest/config/folders"))) {
            val id = f.stringOrNull("id") ?: continue
            folders.add(folder(id, f, folderStats.optJSONObject(id), myId))
        }
        folders.sortBy { it.label.lowercase() }
        return SyncSnapshot(
            myId = myId,
            myName = myName,
            version = version,
            devices = devices,
            folders = folders,
            pendingDevices = optional("pending devices") { pendingDevices() },
            pendingFolders = optional("pending folders") { pendingFolders() },
        )
    }

    /**
     * Requests are secondary to the page: a failed lookup (an older system
     * service refuses the path) leaves them empty and is logged, instead of
     * leaving the page without its folders and devices.
     */
    private suspend fun <T> optional(what: String, block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The page polls every few seconds: each distinct failure is logged once.
        val line = "sync: $what not loaded: ${e.message}"
        if (loggedFailures.add(line)) Log.w(TAG, line)
        // A failure that reaches a cancelled page ends it (kotlinx hands it over before the cancellation).
        currentCoroutineContext().ensureActive()
        emptyList()
    }

    private val loggedFailures: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun device(d: JSONObject, id: String, conn: JSONObject?, stats: JSONObject?): SyncDevice =
        SyncDevice(
            id = id,
            name = d.stringOrNull("name") ?: id.substringBefore('-'),
            paused = d.looseBoolean("paused"),
            connected = conn?.looseBoolean("connected") ?: false,
            address = conn?.stringOrNull("address"),
            clientVersion = conn?.stringOrNull("clientVersion"),
            lastSeen = time(stats?.stringOrNull("lastSeen")),
            addresses = strings(d.optJSONArray("addresses")),
            introducer = d.looseBoolean("introducer"),
            autoAcceptFolders = d.looseBoolean("autoAcceptFolders"),
            compression = d.stringOrNull("compression") ?: "metadata",
            untrusted = d.looseBoolean("untrusted"),
        )

    private suspend fun folder(id: String, config: JSONObject, stats: JSONObject?, myId: String): SyncFolder {
        val q = "folder=${segment(id)}"
        val status = obj("GET", "/rest/db/status?$q")
        // Pull errors and the file list exist only for a running folder;
        // a paused one answers these with an HTTP error.
        val paused = config.looseBoolean("paused")
        val errors = if (paused) 0 else (SyncClient.syncApi("GET", "/rest/folder/errors?$q") as? JSONObject)
            ?.optJSONArray("errors")?.length() ?: 0
        var conflicts = 0
        if (!paused) {
            for (entry in objects(SyncClient.syncApi("GET", "/rest/db/browse?$q&levels=0"))) {
                if (entry.stringOrNull("name")?.contains(CONFLICT_MARK) == true) conflicts++
            }
        }
        val versioning = config.optJSONObject("versioning")
        val params = HashMap<String, String>()
        versioning?.optJSONObject("params")?.let { p -> p.keys().forEach { k -> params[k] = p.optString(k) } }
        val deviceEntries = objectsOf(config.optJSONArray("devices")).filter { it.stringOrNull("deviceID") != myId }
        return SyncFolder(
            id = id,
            label = config.stringOrNull("label") ?: id,
            path = config.stringOrNull("path") ?: "",
            type = config.stringOrNull("type") ?: TYPE_SEND_RECEIVE,
            devices = deviceEntries.mapNotNull { it.stringOrNull("deviceID") },
            // The system service never passes passwords on, only whether one is set.
            encryptedFor = deviceEntries.filter { it.looseBoolean("encryptionPasswordSet") }
                .mapNotNull { it.stringOrNull("deviceID") }.toSet(),
            paused = paused,
            rescanIntervalS = config.optInt("rescanIntervalS", 3600),
            fsWatcherEnabled = config.optBoolean("fsWatcherEnabled", true),
            versioning = Versioning(
                versioning?.stringOrNull("type") ?: Versioning.NONE, params, versioning?.stringOrNull("fsPath") ?: "",
            ),
            state = status.stringOrNull("state") ?: "",
            error = listOfNotNull(status.stringOrNull("error"), status.stringOrNull("invalid"))
                .firstOrNull { it.isNotBlank() } ?: "",
            failedItems = maxOf(status.optInt("pullErrors", 0), errors),
            needItems = status.optInt("needTotalItems", 0),
            needBytes = status.optLong("needBytes", 0),
            globalBytes = status.optLong("globalBytes", 0),
            globalFiles = status.optInt("globalFiles", 0),
            overrideDeletes = overrideDeletes(status),
            inSyncBytes = status.optLong("inSyncBytes", 0),
            conflicts = conflicts,
            lastScan = time(stats?.stringOrNull("lastScan")),
            localChanges = status.optInt("receiveOnlyTotalItems", 0),
        )
    }

    suspend fun pendingDevices(): List<PendingDevice> {
        val body = SyncClient.syncApi("GET", "/rest/cluster/pending/devices") as? JSONObject ?: return emptyList()
        return body.keys().asSequence().mapNotNull { id ->
            val d = body.optJSONObject(id) ?: return@mapNotNull null
            PendingDevice(id, d.stringOrNull("name") ?: "", d.stringOrNull("address") ?: "", time(d.stringOrNull("time")))
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun pendingFolders(): List<PendingFolder> {
        val body = SyncClient.syncApi("GET", "/rest/cluster/pending/folders") as? JSONObject ?: return emptyList()
        return body.keys().asSequence().mapNotNull { id ->
            val offered = body.optJSONObject(id)?.optJSONObject("offeredBy") ?: return@mapNotNull null
            val by = LinkedHashMap<String, String>()
            var receiveEncrypted = false
            val remoteEncrypted = HashSet<String>()
            offered.keys().forEach { device ->
                val o = offered.optJSONObject(device) ?: return@forEach
                by[device] = o.stringOrNull("label") ?: ""
                // As Syncthing's own GUI reads them: receiveEncrypted, this device
                // keeps it encrypted; remoteEncrypted, the other device does.
                receiveEncrypted = receiveEncrypted || o.looseBoolean("receiveEncrypted")
                if (o.looseBoolean("remoteEncrypted")) remoteEncrypted.add(device)
            }
            if (by.isEmpty()) return@mapNotNull null
            PendingFolder(id, by.values.firstOrNull { it.isNotEmpty() } ?: id, by, receiveEncrypted, remoteEncrypted)
        }.sortedBy { it.label.lowercase() }.toList()
    }

    /** Failed items of a running folder. */
    suspend fun folderErrors(id: String): List<FolderError> {
        val body = SyncClient.syncApi("GET", "/rest/folder/errors?folder=${segment(id)}&page=1&perpage=200") as? JSONObject
        return objectsOf(body?.optJSONArray("errors")).map {
            FolderError(it.stringOrNull("path") ?: "", it.stringOrNull("error") ?: "")
        }
    }

    /** Relative paths of the conflict copies in a running folder, up to [levels] directories deep. */
    suspend fun conflicts(id: String, levels: Int = 8): List<String> {
        val found = ArrayList<String>()
        fun walk(entries: JSONArray?, prefix: String) {
            for (e in objectsOf(entries)) {
                val name = e.stringOrNull("name") ?: continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (CONFLICT_MARK in name) found.add(path)
                walk(e.optJSONArray("children"), path)
            }
        }
        walk(SyncClient.syncApi("GET", "/rest/db/browse?folder=${segment(id)}&levels=$levels") as? JSONArray, "")
        return found.sorted()
    }

    suspend fun options(): SyncOptions {
        val o = obj("GET", "/rest/config/options")
        return SyncOptions(
            globalAnnounce = o.optBoolean("globalAnnounceEnabled", true),
            localAnnounce = o.optBoolean("localAnnounceEnabled", true),
            relays = o.optBoolean("relaysEnabled", true),
            nat = o.optBoolean("natEnabled", true),
            maxSendKbps = o.optInt("maxSendKbps", 0),
            maxRecvKbps = o.optInt("maxRecvKbps", 0),
            listenAddresses = if (o.has("listenAddresses")) strings(o.optJSONArray("listenAddresses")) else listOf(DEFAULT),
            globalAnnounceServers =
                if (o.has("globalAnnounceServers")) strings(o.optJSONArray("globalAnnounceServers")) else listOf(DEFAULT),
            reconnectionIntervalS = o.optInt("reconnectionIntervalS", 60),
        )
    }

    /** Syncthing's canonical form of a device ID, or null with Syncthing's reason in [error]. */
    suspend fun normalizeDeviceId(text: String, error: StringBuilder? = null): String? {
        val body = SyncClient.syncApi("GET", "/rest/svc/deviceid?id=${Uri.encode(text.trim())}") as? JSONObject
        body?.stringOrNull("id")?.let { return it }
        error?.append(body?.stringOrNull("error") ?: "")
        return null
    }

    // ---- changes ----

    suspend fun setDevicePaused(id: String, paused: Boolean) = patchDevice(id, JSONObject().put("paused", paused))

    suspend fun setFolderPaused(id: String, paused: Boolean) = patchFolder(id, JSONObject().put("paused", paused))

    suspend fun patchDevice(id: String, changes: JSONObject) {
        SyncClient.syncApi("PATCH", "/rest/config/devices/${segment(id)}", changes)
    }

    suspend fun patchFolder(id: String, changes: JSONObject) {
        SyncClient.syncApi("PATCH", "/rest/config/folders/${segment(id)}", changes)
    }

    suspend fun patchOptions(changes: JSONObject) {
        SyncClient.syncApi("PATCH", "/rest/config/options", changes)
    }

    /** Adds a device; [like] gives it that device's introducer, acceptance, trust and compression. */
    suspend fun addDevice(id: String, name: String, addresses: List<String> = listOf(DYNAMIC), like: SyncDevice? = null) {
        val body = JSONObject().put("deviceID", id).put("name", name).put("addresses", JSONArray(addresses))
        like?.let {
            body.put("introducer", it.introducer).put("autoAcceptFolders", it.autoAcceptFolders)
                .put("untrusted", it.untrusted).put("compression", it.compression)
        }
        SyncClient.syncApi("POST", "/rest/config/devices", body)
    }

    /**
     * Shares [folder] with [device] as [like] has it: encrypted with the same
     * password or unencrypted, which only the system service knows.
     */
    suspend fun shareLike(folder: SyncFolder, device: String, like: String) {
        val list = JSONArray(folder.devices.filter { it != device }.map { JSONObject().put("deviceID", it) })
        list.put(JSONObject().put("deviceID", device).put("encryptionPasswordFrom", like))
        patchFolder(folder.id, JSONObject().put("devices", list))
    }

    suspend fun removeDevice(id: String) {
        SyncClient.syncApi("DELETE", "/rest/config/devices/${segment(id)}")
    }

    /**
     * A new folder in [dir] under the folders root, shared with [devices].
     * ignorePerms: files get 0640, which the system service reads through
     * its group (the vault opens them).
     */
    suspend fun addFolder(
        id: String,
        label: String,
        dir: String,
        type: String,
        devices: List<String>,
        passwords: Map<String, String> = emptyMap(),
    ) {
        val body = JSONObject()
            .put("id", id)
            .put("label", label)
            .put("path", "$FOLDERS_ROOT/$dir")
            .put("type", type)
            .put("ignorePerms", true)
            .put("devices", JSONArray(devices.map { d ->
                JSONObject().put("deviceID", d).also { e -> passwords[d]?.let { e.put("encryptionPassword", it) } }
            }))
        SyncClient.syncApi("POST", "/rest/config/folders", body)
    }

    suspend fun removeFolder(id: String) {
        SyncClient.syncApi("DELETE", "/rest/config/folders/${segment(id)}")
    }

    suspend fun setFolderDevices(id: String, devices: List<String>) =
        patchFolder(id, JSONObject().put("devices", JSONArray(devices.map { JSONObject().put("deviceID", it) })))

    suspend fun setVersioning(id: String, versioning: Versioning) {
        val params = JSONObject()
        versioning.params.forEach { (k, v) -> params.put(k, v) }
        patchFolder(
            id,
            JSONObject().put(
                "versioning",
                JSONObject().put("type", versioning.type).put("params", params)
                    .put("fsPath", versioning.path).put("fsType", "basic"),
            ),
        )
    }

    /**
     * Shares the folder with [device] encrypted with [password], or
     * unencrypted again when it is null. The other devices keep their
     * passwords (the system service holds them).
     */
    suspend fun setDevicePassword(folder: SyncFolder, device: String, password: String?) {
        val list = JSONArray(folder.devices.map { d ->
            JSONObject().put("deviceID", d).also { if (d == device) it.put("encryptionPassword", password ?: "") }
        })
        patchFolder(folder.id, JSONObject().put("devices", list))
    }

    /** The folder's ignore patterns (.stignore lines). */
    suspend fun ignores(id: String): List<String> {
        val lines = obj("GET", "/rest/db/ignores?folder=${segment(id)}").optJSONArray("ignore") ?: return emptyList()
        return (0 until lines.length()).map { lines.optString(it) }
    }

    suspend fun setIgnores(id: String, lines: List<String>) {
        SyncClient.syncApi(
            "POST", "/rest/db/ignores?folder=${segment(id)}", JSONObject().put("ignore", JSONArray(lines)),
        )
    }

    /** Receive only: undoes the changes made on this device. */
    suspend fun revert(id: String) {
        SyncClient.syncApi("POST", "/rest/db/revert?folder=${segment(id)}")
    }

    /** Send only: makes this device's files the version every device gets. */
    /**
     * [deleteMissing]: the owner confirmed that items missing here are deleted on
     * the other devices; without it the service refuses such an override (override_deletes).
     */
    suspend fun override(id: String, deleteMissing: Boolean) {
        SyncClient.syncApi(
            "POST", "/rest/db/override?folder=${segment(id)}",
            if (deleteMissing) JSONObject().put("deleteMissing", true) else null,
        )
    }

    /** global - local items + needDeletes, the system service's count for an override; -1 when unknown. */
    private fun overrideDeletes(status: JSONObject): Int {
        var items = 0L
        for ((name, sign) in listOf(
            "globalFiles" to 1, "globalDirectories" to 1, "globalSymlinks" to 1,
            "localFiles" to -1, "localDirectories" to -1, "localSymlinks" to -1, "needDeletes" to 1,
        )) {
            val v = status.opt(name) as? Number ?: return -1
            items += sign * v.toLong()
        }
        return items.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Moves the folder to [dir] under the folders root (sync.folder.move). */
    suspend fun moveFolder(id: String, dir: String): FolderMoved {
        val r = SyncClient.moveFolder(id, dir)
        return FolderMoved(
            path = r.stringOrNull("path") ?: "$FOLDERS_ROOT/$dir",
            previous = r.stringOrNull("previous"),
            paused = r.optBoolean("paused"),
            ignoresCopied = r.optInt("ignores_copied"),
        )
    }

    /** Run conditions; each argument that is not null is changed first. */
    suspend fun conditions(wifiOnly: Boolean? = null, chargingOnly: Boolean? = null): SyncConditions {
        val r = SyncClient.conditions(wifiOnly, chargingOnly)
        return SyncConditions(
            wifiOnly = r.optBoolean("wifi_only"),
            chargingOnly = r.optBoolean("charging_only"),
            wifi = if (r.isNull("wifi")) null else r.optBoolean("wifi"),
            charging = if (r.isNull("charging")) null else r.optBoolean("charging"),
            waiting = strings(r.optJSONArray("waiting")),
        )
    }

    /** Folders a device's "auto accept" adds go under the folders root (not Syncthing's home). */
    suspend fun setDefaultFolderPath() {
        SyncClient.syncApi(
            "PATCH", "/rest/config/defaults/folder",
            JSONObject().put("path", FOLDERS_ROOT).put("ignorePerms", true),
        )
    }

    /** Scans the folder now; returns when the scan has finished. */
    suspend fun rescan(id: String) {
        SyncClient.syncApi("POST", "/rest/db/scan?folder=${segment(id)}", timeoutMs = SyncClient.TIMEOUT_API_MS)
    }

    /** Pauses or resumes every device (Syncthing's "pause all"). */
    suspend fun setAllPaused(paused: Boolean) {
        SyncClient.syncApi("POST", if (paused) "/rest/system/pause" else "/rest/system/resume")
    }

    suspend fun dismissPendingDevice(id: String) {
        SyncClient.syncApi("DELETE", "/rest/cluster/pending/devices?device=${segment(id)}")
    }

    suspend fun dismissPendingFolder(folder: String, device: String? = null) {
        val q = "folder=${segment(folder)}" + (device?.let { "&device=${segment(it)}" } ?: "")
        SyncClient.syncApi("DELETE", "/rest/cluster/pending/folders?$q")
    }

    /** Ignores a folder offered by [device] from now on (the device's ignoredFolders). */
    suspend fun ignoreFolder(device: String, folder: String, label: String) {
        val config = obj("GET", "/rest/config/devices/${segment(device)}")
        val ignored = config.optJSONArray("ignoredFolders") ?: JSONArray()
        val list = JSONArray()
        objectsOf(ignored).filter { it.stringOrNull("id") != folder }.forEach {
            list.put(JSONObject().put("id", it.optString("id")).put("label", it.optString("label")).put("time", it.optString("time")))
        }
        list.put(JSONObject().put("id", folder).put("label", label).put("time", java.time.Instant.now().toString()))
        patchDevice(device, JSONObject().put("ignoredFolders", list))
    }

    // Ignored devices live in the whole configuration, which only the system
    // service reads (sync.ignored).

    suspend fun ignoredDevices(): List<IgnoredDevice> = ignored(JSONObject().put("op", "list"))

    suspend fun ignoreDevice(id: String, name: String, address: String) {
        ignored(JSONObject().put("op", "add").put("device", id).put("name", name).put("address", address))
    }

    suspend fun unignoreDevice(id: String) {
        ignored(JSONObject().put("op", "remove").put("device", id))
    }

    private suspend fun ignored(request: JSONObject): List<IgnoredDevice> {
        val reply = SyncClient.ignored(
            request.getString("op"),
            request.optString("device").ifEmpty { null },
            request.optString("name").ifEmpty { null },
            request.optString("address").ifEmpty { null },
        )
        return objectsOf(reply.optJSONArray("devices")).mapNotNull {
            val id = it.stringOrNull("deviceID") ?: return@mapNotNull null
            IgnoredDevice(id, it.stringOrNull("name") ?: "", it.stringOrNull("address") ?: "")
        }
    }

    /** Directory names under the folders root (sync.dirs). */
    suspend fun dirs(): List<String> {
        val reply = SyncClient.dirs()
        return strings(reply.optJSONArray("dirs"))
    }

    /** A new folder ID in Syncthing's own style (xxxxx-xxxxx). */
    fun newFolderId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        fun part() = (1..5).map { chars[random.nextInt(chars.length)] }.joinToString("")
        return part() + "-" + part()
    }

    /** A directory name the folders root accepts: [A-Za-z0-9._ -], not starting with '.'. */
    fun isDirName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 255 && name[0] != '.' &&
            name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == ' ' || it == '-' }

    /** A directory name from a label: allowed characters kept, others become '-'. */
    fun dirNameFor(label: String, taken: Collection<String>): String {
        val cleaned = buildString {
            for (c in label) {
                val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == ' ' || c == '-'
                if (ok) append(c) else if (isEmpty() || last() != '-') append('-')
            }
        }.trim('.', '-', ' ').take(64)
        val base = cleaned.ifEmpty { "folder" }
        var name = base
        var n = 2
        while (name in taken) name = "$base-${n++}"
        return name
    }

    const val CONFLICT_MARK = ".sync-conflict-"

    // ---- helpers ----

    private suspend fun obj(method: String, path: String): JSONObject =
        SyncClient.syncApi(method, path) as? JSONObject ?: throw SyncException(
            SyncException.Kind.PROTOCOL, "sync.api $method $path", "expected a JSON object"
        )

    private fun objects(body: Any?): List<JSONObject> = objectsOf(body as? JSONArray)

    private fun objectsOf(array: JSONArray?): List<JSONObject> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun strings(array: JSONArray?): List<String> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    /** RFC 3339 time as ms since the epoch; null when absent or never. */
    internal fun time(text: String?): Long? {
        if (text.isNullOrEmpty()) return null
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli().takeIf { it > NEVER_BEFORE_MS }
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
