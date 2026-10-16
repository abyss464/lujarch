package org.lujarch.files

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * What Files picked, in the form an app asking DocumentsUI gets.
 *
 * Files answers with its own content URI (or a provider's document URI for a folder it reaches
 * through a document tree), granted to this app. A file or folder on a mounted volume becomes the
 * ExternalStorageProvider document (DocumentsContract.buildDocumentUri), and the tree for
 * OPEN_DOCUMENT_TREE (buildTreeDocumentUri), so the app can keep the access (a persisted grant
 * on ExternalStorageProvider survives Files and this app) and walk a tree with DocumentsContract,
 * which Files' URIs cannot give. A provider's document stays as Files gave it, and becomes that
 * provider's tree for OPEN_DOCUMENT_TREE. Anything else (a network share, an archive entry) is
 * passed through with Files' grant; it cannot be a tree. The grant flags are DocumentsUI's for
 * the action (picker/ActionHandler.onPickFinished), never more than Files granted for its own
 * URIs. Blocking (provider calls): run off the main thread.
 */
internal class Handover(
    private val context: Context,
    private val request: Intent,
    /** The authorities of Files' own providers. */
    private val filesAuthorities: Set<String>,
) {
    sealed class Outcome {
        class Done(val uris: List<Uri>, val flags: Int) : Outcome()
        class Refused(val problem: Problem) : Outcome()
    }

    private sealed class Picked {
        /** A file or folder on a mounted volume, as its ExternalStorageProvider document. */
        class Local(val documentId: String, val path: String) : Picked()

        /** A document of a DocumentsProvider. */
        class Provider(val authority: String, val documentId: String, val uri: Uri) : Picked()

        /** Anything else, passed through. */
        class Other(val uri: Uri) : Picked()
    }

    private class RefusedException(val problem: Problem) : Exception()

    fun map(picked: List<Uri>): Outcome {
        val action = request.action
        val flags = grantFlags(action)
        return try {
            val uris = when (action) {
                Intent.ACTION_OPEN_DOCUMENT_TREE -> listOf(tree(classify(picked.first())))
                Intent.ACTION_CREATE_DOCUMENT -> listOf(created(classify(picked.first())))
                else -> picked.map { opened(classify(it)) }
            }
            val modes = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // Full access counts the provider's own permissions (MANAGE_DOCUMENTS for
            // ExternalStorageProvider) as well as grants (Files' own URIs)
            for (uri in uris) {
                if (context.checkContentUriPermissionFull(uri, Process.myPid(), Process.myUid(), modes) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "pick: no access to $uri to hand over (modes $modes)")
                    throw RefusedException(Problem(Problem.Kind.GRANT_FAILED))
                }
            }
            Outcome.Done(uris, flags)
        } catch (e: RefusedException) {
            Outcome.Refused(e.problem)
        }
    }

    private fun classify(uri: Uri): Picked {
        if (uri.authority in filesAuthorities) {
            val path = Documents.filesProviderPath(uri)
            val id = path?.let { Documents.documentId(context, it) }
            if (path != null && id != null) return Picked.Local(id, path)
            Log.i(TAG, "pick: $uri is not on a mounted volume (${path ?: "not a local path"}), passed through")
            return Picked.Other(uri)
        }
        val authority = uri.authority
        if (authority != null && DocumentsContract.isDocumentUri(context, uri)) {
            return Picked.Provider(authority, DocumentsContract.getDocumentId(uri), uri)
        }
        Log.i(TAG, "pick: $uri is no document, passed through")
        return Picked.Other(uri)
    }

    /** OPEN_DOCUMENT and GET_CONTENT. */
    private fun opened(p: Picked): Uri = when (p) {
        is Picked.Local -> DocumentsContract.buildDocumentUri(Documents.ESP, p.documentId)
        is Picked.Provider -> p.uri
        is Picked.Other -> p.uri
    }

    /**
     * OPEN_DOCUMENT_TREE: a folder of a DocumentsProvider that the provider does not block from
     * tree access (DocumentsUI disables "Use this folder" for those: a volume's root, Download,
     * Android; ExternalStorageProvider.shouldBlockDirectoryFromTree).
     */
    private fun tree(p: Picked): Uri {
        val (authority, id) = when (p) {
            is Picked.Local -> Documents.ESP to p.documentId
            is Picked.Provider -> p.authority to p.documentId
            is Picked.Other -> {
                Log.w(TAG, "pick: folder ${p.uri} is no document, apps cannot walk it")
                throw RefusedException(Problem(Problem.Kind.FOLDER_NOT_LOCAL))
            }
        }
        val doc = Documents.query(context, authority, id)
        if (doc == null || !doc.isDirectory) {
            Log.w(TAG, "pick: $authority $id is ${if (doc == null) "unknown to its provider" else "not a folder (${doc.mimeType})"}")
            throw RefusedException(Problem(if (doc == null) Problem.Kind.GRANT_FAILED else Problem.Kind.FOLDER_NOT_LOCAL))
        }
        if ((doc.flags and DocumentsContract.Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE) != 0) {
            Log.i(TAG, "pick: $authority $id is blocked from tree access")
            throw RefusedException(Problem(Problem.Kind.FOLDER_BLOCKED))
        }
        return DocumentsContract.buildTreeDocumentUri(authority, id)
    }

    /**
     * CREATE_DOCUMENT: the returned document exists and is writable, as DocumentsUI's save
     * returns (CreatePickedDocumentTask: DocumentsContract.createDocument in the folder). Files
     * returns the path to create; a file it returns that exists is one the owner chose to
     * replace there, which stays as it is until the app writes it.
     */
    private fun created(p: Picked): Uri {
        if (p !is Picked.Local) return opened(p)
        val existing = Documents.query(context, Documents.ESP, p.documentId)
        if (existing != null) {
            if ((existing.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) == 0) {
                Log.w(TAG, "save: ${p.documentId} exists and is not writable")
                throw RefusedException(Problem(Problem.Kind.WRITE_FAILED))
            }
            return DocumentsContract.buildDocumentUri(Documents.ESP, p.documentId)
        }
        val file = File(p.path)
        val parentId = file.parent?.let { Documents.documentId(context, it) }
        if (parentId == null) {
            Log.w(TAG, "save: no folder for ${p.path}")
            throw RefusedException(Problem(Problem.Kind.WRITE_FAILED))
        }
        val type = request.type?.takeIf { '*' !in it } ?: "application/octet-stream"
        val created = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUri(Documents.ESP, parentId),
                type,
                file.name,
            )
        } catch (e: Exception) {
            Log.e(TAG, "save: creating ${file.name} ($type) in $parentId failed", e)
            null
        } ?: throw RefusedException(Problem(Problem.Kind.WRITE_FAILED))
        Log.i(TAG, "save: created $created")
        return created
    }

    companion object {
        /** DocumentsUI's grant for the action (picker/ActionHandler.onPickFinished). */
        fun grantFlags(action: String?): Int = when (action) {
            Intent.ACTION_GET_CONTENT -> Intent.FLAG_GRANT_READ_URI_PERMISSION
            Intent.ACTION_OPEN_DOCUMENT_TREE ->
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            else ->
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
    }
}
