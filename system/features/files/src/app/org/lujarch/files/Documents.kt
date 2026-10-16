package org.lujarch.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * Paths and ExternalStorageProvider documents. A document id there is the volume's root id, a
 * colon and the path below the volume ("primary:Download/a.pdf"): "primary" for the user's
 * emulated volume, the file system UUID for an SD card or a USB drive
 * (ExternalStorageProvider.updateVolumesLocked, getDocIdForFileMaybeCreate; the same root ids
 * as StorageVolume.createOpenDocumentTreeIntent). Everything here is lexical or a provider call:
 * this app has no storage permission and never touches the files.
 */
internal object Documents {
    val ESP: String = DocumentsContract.EXTERNAL_STORAGE_PROVIDER_AUTHORITY
    private const val DOWNLOADS = "com.android.providers.downloads.documents"
    private val ALIASES = listOf("/sdcard", "/storage/self/primary")

    /**
     * The local path a URI of Files' own provider names, or null for anything else (a network
     * share, an archive entry). Files writes `content://<authority>/` + `Uri.encode` of the
     * path's `file:` URI, which Uri.Builder encodes once more (FileProvider.kt,
     * `Path.fileProviderUri`); this undoes it as Files does (`Uri.fileProviderPath`).
     */
    fun filesProviderPath(uri: Uri): String? {
        val encoded = uri.path ?: return null
        val inner = Uri.decode(encoded).removePrefix("/")
        val parsed = try {
            URI.create(inner)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (parsed.scheme != "file") return null
        return parsed.path?.takeIf { it.startsWith("/") }
    }

    /**
     * The ExternalStorageProvider document id of [path], or null when it is on no mounted volume
     * of this user. Lexical: the path is normalized and /sdcard is the primary volume.
     */
    fun documentId(context: Context, path: String): String? {
        var p = try {
            Paths.get(path).normalize().toString()
        } catch (e: InvalidPathException) {
            return null
        }
        val primary = Environment.getExternalStorageDirectory().absolutePath
        for (alias in ALIASES) {
            if (p == alias || p.startsWith("$alias/")) p = primary + p.substring(alias.length)
        }
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        for (vol in sm.storageVolumes) {
            val state = vol.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            val dir = vol.directory?.absolutePath ?: continue
            val rel = when {
                p == dir -> ""
                p.startsWith("$dir/") -> p.substring(dir.length + 1)
                else -> continue
            }
            val root = if (vol.isEmulated) {
                DocumentsContract.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID
            } else {
                vol.uuid ?: continue
            }
            return "$root:$rel"
        }
        return null
    }

    /** The path of an ExternalStorageProvider document or root id, null off the mounted volumes. */
    fun path(context: Context, documentId: String): File? {
        val colon = documentId.indexOf(':')
        val root = if (colon >= 0) documentId.substring(0, colon) else documentId
        val rel = if (colon >= 0) documentId.substring(colon + 1) else ""
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        val vol = sm.storageVolumes.firstOrNull {
            if (it.isEmulated) root == DocumentsContract.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID
            else root.equals(it.uuid, ignoreCase = true)
        } ?: return null
        val state = vol.state
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return null
        val dir = vol.directory ?: return null
        return if (rel.isEmpty()) dir else File(dir, rel)
    }

    /** A document's MIME type and flags, as its provider reports them. */
    class Doc(val mimeType: String?, val flags: Int) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    /** Asks the provider of [authority] about a document; null when it has no such document. */
    fun query(context: Context, authority: String, documentId: String): Doc? {
        val uri = DocumentsContract.buildDocumentUri(authority, documentId)
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) Doc(c.getString(0), c.getInt(1)) else null }
        } catch (e: Exception) {
            Log.w(TAG, "documents: cannot query $uri", e)
            null
        }
    }

    /**
     * The folder Files should start in for a request's EXTRA_INITIAL_URI, or null. Files would
     * read a content URI there as a path of its own content file system, so the extra is
     * translated: an ExternalStorageProvider root, tree or document becomes its folder (a file's
     * parent, as DocumentsUI starts in a file's folder), a Downloads document the Download
     * folder; other providers have no folder Files can open.
     */
    fun initialFolder(context: Context, uri: Uri): String? {
        try {
            when (uri.authority) {
                ESP -> {
                    val id = when {
                        DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                        DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                        DocumentsContract.isRootUri(context, uri) -> DocumentsContract.getRootId(uri) + ":"
                        else -> return null
                    }
                    val file = path(context, id) ?: return null
                    return if (query(context, ESP, id)?.isDirectory == true) file.path else file.parent
                }
                DOWNLOADS ->
                    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                else -> return null
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "documents: initial location $uri not understood", e)
            return null
        }
    }
}
