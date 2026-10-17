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

package org.lujarch.vault.data

import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.ServiceManager
import android.os.ServiceSpecificException
import android.system.ErrnoException
import android.system.Os
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import org.lujarch.sync.ISync
import org.lujarch.vault.core.Bytes

/** A failed request to the sync service; [kind] selects the message shown to the user. */
class SyncFilesException(
    val kind: Kind,
    val command: String,
    val detail: String,
    cause: Throwable? = null,
) : Exception("$command: $detail", cause) {
    enum class Kind {
        /** The service cannot be reached: not running, or this app not allowed. */
        UNAVAILABLE,
        TIMEOUT,
        IO,
        /** The answer is not what was expected. */
        PROTOCOL,
        /** The service refused or failed the request. */
        REMOTE,
        CLOSED,
    }
}

/** One file of a synced folder. */
data class FolderFile(val name: String, val size: Long, val mtime: Long)

/** A database found in a synced folder; [path] is relative to the folder. */
data class FoundDatabase(val folder: String, val path: String, val size: Long, val mtime: Long)

/**
 * A database the sync service saw in a synced folder but cannot open; [reason] is its word for why
 * (SKIP_* in [SyncFiles]).
 */
data class SkippedDatabase(val folder: String, val path: String, val reason: String, val size: Long)

/**
 * The synced folders (directory name to label), the databases in them, the ones that cannot be
 * opened, and whether the search stopped at a limit.
 */
data class FoundDatabases(
    val labels: Map<String, String>,
    val files: List<FoundDatabase>,
    val truncated: Boolean,
    val skipped: List<SkippedDatabase> = emptyList(),
)

/** A database read whole; [data] is wiped by the caller when done. */
class FileContent(val name: String, val data: ByteArray, val size: Long, val mtime: Long, val sha256: String)

/**
 * The password files of the synced folders (by name: .kdbx, .json, .1pux, .csv; the vault reads
 * their content), through the sync service (lujarch_sync), which
 * lets only this app find them and hands it the one it asks for as an open file. Nothing read here
 * is logged.
 */
object SyncFiles {
    const val MAX_DEPTH = 8
    /** Why a database cannot be opened (SkippedDatabase.reason). */
    const val SKIP_NAME = "name"
    const val SKIP_TOO_LARGE = "too_large"
    const val SKIP_TOO_DEEP = "too_deep"
    const val SKIP_SYMLINK = "symlink"
    const val SKIP_NOT_REGULAR = "not_regular"
    private const val TIMEOUT_MS = 15_000L
    private const val SERVICE = "lujarch_sync"

    private fun service(command: String): ISync =
        ISync.Stub.asInterface(ServiceManager.checkService(SERVICE))
            ?: throw SyncFilesException(SyncFilesException.Kind.UNAVAILABLE, command, "$SERVICE is not running")

    private suspend fun <T> call(command: String, block: (ISync) -> T): T =
        try {
            withTimeout(TIMEOUT_MS) { withContext(Dispatchers.IO) { block(service(command)) } }
        } catch (e: TimeoutCancellationException) {
            throw SyncFilesException(SyncFilesException.Kind.TIMEOUT, command, "no answer within ${TIMEOUT_MS / 1000} s", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncFilesException) {
            throw e
        } catch (e: ServiceSpecificException) {
            throw SyncFilesException(SyncFilesException.Kind.REMOTE, command, e.message ?: "")
        } catch (e: DeadObjectException) {
            throw SyncFilesException(SyncFilesException.Kind.UNAVAILABLE, command, e.toString(), e)
        } catch (e: RemoteException) {
            throw SyncFilesException(SyncFilesException.Kind.IO, command, e.toString(), e)
        }

    private val EXTENSIONS = listOf(".kdbx", ".json", ".1pux", ".csv")

    fun isDatabaseName(name: String): Boolean =
        EXTENSIONS.any { name.length > it.length && name.endsWith(it, ignoreCase = true) }

    /** Every password file in the synced folders, with the folders' labels. */
    suspend fun find(): FoundDatabases {
        val text = call("vaultFind") { it.vaultFind() }
        val reply = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw SyncFilesException(SyncFilesException.Kind.PROTOCOL, "vaultFind", "not JSON", e)
        }
        val labels = LinkedHashMap<String, String>()
        reply.optJSONArray("folders")?.let { a ->
            for (i in 0 until a.length()) {
                val f = a.optJSONObject(i) ?: continue
                val name = f.optString("folder").takeIf { it.isNotEmpty() } ?: continue
                labels[name] = f.optString("label").ifEmpty { name }
            }
        }
        val files = reply.optJSONArray("files") ?: throw SyncFilesException(
            SyncFilesException.Kind.PROTOCOL, "vaultFind", "reply has no \"files\" array"
        )
        val out = ArrayList<FoundDatabase>(files.length())
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val folder = f.optString("folder").takeIf { it.isNotEmpty() } ?: continue
            val name = f.optString("name").takeIf { it.isNotEmpty() } ?: continue
            out.add(FoundDatabase(folder, name, f.optLong("size", -1), f.optLong("mtime", 0)))
        }
        val skipped = ArrayList<SkippedDatabase>()
        reply.optJSONArray("skipped")?.let { a ->
            for (i in 0 until a.length()) {
                val f = a.optJSONObject(i) ?: continue
                val folder = f.optString("folder").takeIf { it.isNotEmpty() } ?: continue
                val name = f.optString("name").takeIf { it.isNotEmpty() } ?: continue
                skipped.add(SkippedDatabase(folder, name, f.optString("reason"), f.optLong("size", -1)))
            }
        }
        return FoundDatabases(labels, out, reply.optBoolean("truncated", false), skipped)
    }

    private suspend fun open(folder: String, name: String): ParcelFileDescriptor =
        call("vaultOpen") { it.vaultOpen(folder, name) }

    suspend fun stat(folder: String, name: String): FolderFile = open(folder, name).use { fd ->
        try {
            val st = Os.fstat(fd.fileDescriptor)
            FolderFile(name, st.st_size, st.st_mtime * 1000)
        } catch (e: ErrnoException) {
            throw SyncFilesException(SyncFilesException.Kind.IO, "vaultOpen", e.toString(), e)
        }
    }

    /**
     * The whole database, read from one open file: a new version the sync engine renames over it
     * meanwhile does not change what this file holds.
     */
    suspend fun read(folder: String, name: String): FileContent = open(folder, name).use { fd ->
        withContext(Dispatchers.IO) {
            try {
                val st = Os.fstat(fd.fileDescriptor)
                if (st.st_size > Sources.MAX_DATABASE_BYTES) {
                    throw SyncFilesException(
                        SyncFilesException.Kind.PROTOCOL, "vaultOpen", "file of ${st.st_size} bytes is larger than the limit"
                    )
                }
                val data = ByteArray(st.st_size.toInt())
                var filled = 0
                try {
                    val input = ParcelFileDescriptor.AutoCloseInputStream(fd.dup())
                    input.use {
                        while (filled < data.size) {
                            val n = it.read(data, filled, data.size - filled)
                            if (n < 0) break
                            filled += n
                        }
                    }
                    if (filled != data.size) {
                        throw SyncFilesException(SyncFilesException.Kind.CLOSED, "vaultOpen", "the file ended early")
                    }
                    FileContent(name.substringAfterLast('/'), data, st.st_size, st.st_mtime * 1000, Bytes.hex(Bytes.sha256(data)))
                } catch (e: Throwable) {
                    Bytes.wipe(data)
                    throw e
                }
            } catch (e: ErrnoException) {
                throw SyncFilesException(SyncFilesException.Kind.IO, "vaultOpen", e.toString(), e)
            } catch (e: IOException) {
                throw SyncFilesException(SyncFilesException.Kind.IO, "vaultOpen", e.toString(), e)
            }
        }
    }
}
