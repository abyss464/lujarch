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

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lujarch.vault.TAG
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.WipeableBuffer

/** Where the passwords' file comes from (VLT-2). */
sealed class DbSource {
    /** The file name shown to the user and in logs. */
    abstract val fileName: String

    /** A database in a synced folder, read through the Lujarch system service. */
    data class Synced(val folder: String, val path: String) : DbSource() {
        override val fileName: String
            get() = path.substringAfterLast('/')
    }

    /**
     * A document chosen with the system file picker (ACTION_OPEN_DOCUMENT),
     * read with a persisted read grant, again on every open, so that a file
     * another app updated is picked up.
     */
    data class Document(val uri: Uri, val name: String) : DbSource() {
        override val fileName: String
            get() = name.ifEmpty { uri.lastPathSegment ?: "?" }
    }
}

/** The chosen file cannot be read: the grant was revoked, the file is gone, or it is too large. */
class SourceUnavailableException(val reason: Reason, message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Reason { PERMISSION, MISSING, TOO_LARGE }
}

/** Name, size and modification time of a document; -1 and 0 when the provider does not say. */
data class DocumentInfo(val name: String, val size: Long, val mtime: Long)

/** Size and modification time of a source's file, for change checks. */
data class FileStat(val size: Long, val mtime: Long)

/** Reads and inspects database sources and picked documents. */
object Sources {
    /**
     * Largest password file from either source: the decompressed payload
     * limit of the KDBX reader (a larger file cannot be opened); the daemon
     * serves synced databases up to the same size (vault::kMaxDatabaseSize).
     */
    const val MAX_DATABASE_BYTES = 64 * 1024 * 1024

    suspend fun read(context: Context, source: DbSource): FileContent = when (source) {
        is DbSource.Synced -> synced { SyncFiles.read(source.folder, source.path) }
        is DbSource.Document -> withContext(Dispatchers.IO) {
            val data = readDocument(context, source.uri, MAX_DATABASE_BYTES)
            val info = try {
                documentInfo(context, source.uri)
            } catch (e: Exception) {
                DocumentInfo(source.name, data.size.toLong(), 0)
            }
            FileContent(source.fileName, data, data.size.toLong(), info.mtime, Bytes.hex(Bytes.sha256(data)))
        }
    }

    suspend fun stat(context: Context, source: DbSource): FileStat = when (source) {
        is DbSource.Synced -> synced { SyncFiles.stat(source.folder, source.path) }.let { FileStat(it.size, it.mtime) }
        is DbSource.Document -> withContext(Dispatchers.IO) {
            documentInfo(context, source.uri).let { FileStat(it.size, it.mtime) }
        }
    }

    /** A database gone from its synced folder is reported like a gone document: the pages offer to choose again. */
    private suspend fun <T> synced(block: suspend () -> T): T = try {
        block()
    } catch (e: SyncFilesException) {
        if (e.kind == SyncFilesException.Kind.REMOTE && e.detail.startsWith("no such file")) {
            throw SourceUnavailableException(SourceUnavailableException.Reason.MISSING, "the database is no longer in the synced folder", e)
        }
        throw e
    }

    /**
     * Reads a picked document into a byte array (the caller wipes it). The
     * intermediate buffer is wiped as well.
     */
    fun readDocument(context: Context, uri: Uri, maxBytes: Int): ByteArray {
        val buffer = WipeableBuffer(64 * 1024)
        try {
            val input = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                throw SourceUnavailableException(SourceUnavailableException.Reason.PERMISSION, "no read access to the document", e)
            } catch (e: FileNotFoundException) {
                throw SourceUnavailableException(SourceUnavailableException.Reason.MISSING, "the document does not exist", e)
            } catch (e: IllegalArgumentException) {
                // Document providers report unknown documents this way too.
                throw SourceUnavailableException(SourceUnavailableException.Reason.MISSING, "the document does not exist", e)
            } ?: throw SourceUnavailableException(SourceUnavailableException.Reason.MISSING, "the provider returned no stream")
            input.use {
                val chunk = ByteArray(64 * 1024)
                try {
                    while (true) {
                        val n = it.read(chunk)
                        if (n < 0) break
                        if (buffer.size() + n > maxBytes) {
                            throw SourceUnavailableException(
                                SourceUnavailableException.Reason.TOO_LARGE, "the document is larger than ${maxBytes shr 20} MiB",
                            )
                        }
                        buffer.write(chunk, 0, n)
                    }
                } finally {
                    Bytes.wipe(chunk)
                }
            }
            return buffer.toByteArray()
        } finally {
            buffer.wipe()
        }
    }

    /** Display name, size and modification time of a document; throws like [readDocument] when it is not readable. */
    fun documentInfo(context: Context, uri: Uri): DocumentInfo {
        val cursor: Cursor? = try {
            context.contentResolver.query(uri, null, null, null, null)
        } catch (e: SecurityException) {
            throw SourceUnavailableException(SourceUnavailableException.Reason.PERMISSION, "no read access to the document", e)
        } catch (e: IllegalArgumentException) {
            throw SourceUnavailableException(SourceUnavailableException.Reason.MISSING, "the document does not exist", e)
        }
        cursor?.use { c ->
            if (!c.moveToFirst()) {
                throw SourceUnavailableException(SourceUnavailableException.Reason.MISSING, "the document does not exist")
            }
            fun long(column: String): Long {
                val i = c.getColumnIndex(column)
                return if (i >= 0 && !c.isNull(i)) c.getLong(i) else -1
            }
            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val name = if (nameIndex >= 0 && !c.isNull(nameIndex)) c.getString(nameIndex) else ""
            return DocumentInfo(name, long(OpenableColumns.SIZE), long(DocumentsContract.Document.COLUMN_LAST_MODIFIED).coerceAtLeast(0))
        }
        throw SourceUnavailableException(SourceUnavailableException.Reason.MISSING, "the provider returned no metadata")
    }

    /** Whether the vault still holds a persisted read grant for [uri]. */
    fun hasGrant(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /** Keeps the read grant of a picked database across restarts; false when the provider offers none. */
    fun takeGrant(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "the document offers no persistable read grant: ${e.message}")
        false
    }

    /** Releases every persisted grant except the one of [keep]: databases that are no longer the source. */
    fun releaseGrantsExcept(context: Context, keep: Uri?) {
        for (p in context.contentResolver.persistedUriPermissions) {
            if (p.uri == keep) continue
            try {
                context.contentResolver.releasePersistableUriPermission(
                    p.uri,
                    (if (p.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (p.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0),
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "releasing a document grant failed: ${e.message}")
            }
        }
    }

    /** The document provider's app label ("Files", a cloud app), or null when it is unknown. */
    fun providerLabel(context: Context, uri: Uri): String? {
        val authority = uri.authority ?: return null
        val pm = context.packageManager
        return try {
            pm.resolveContentProvider(authority, 0)?.applicationInfo?.loadLabel(pm)?.toString()
        } catch (e: RuntimeException) {
            null
        }
    }
}
