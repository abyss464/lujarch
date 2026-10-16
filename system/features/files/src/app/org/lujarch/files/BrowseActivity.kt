package org.lujarch.files

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * Where the system says "browse" (Settings > Storage, a USB drive's Explore, the downloads
 * notification's app, an MTP device), the app made for the content opens it (FILE-3): images and
 * videos the gallery, audio Music, files and folders Files at the matching folder (its
 * ABSOLUTE_PATH extra). Files has no by-type collections, so a category without a folder goes
 * to the app that has one, and to Files when that app is missing. A location Files cannot open
 * (an MTP device, another app's document provider) has no app on the phone since DocumentsUI's
 * browser left: the dialog says so.
 */
internal class BrowseActivity : FileAccessActivity() {
    private sealed class Place {
        class Folder(val dir: File) : Place()
        object Gallery : Place()
        object Music : Place()
        class Elsewhere(val authority: String?) : Place()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            if (!showingProblem) finish()
            return
        }
        val place = try {
            place(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "browse: cannot read $intent", e)
            Place.Elsewhere(intent.data?.authority)
        }
        when (place) {
            is Place.Folder -> inFiles(place.dir)
            Place.Gallery ->
                start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)) ||
                    inFiles(Environment.getExternalStorageDirectory())
            Place.Music -> music() ||
                inFiles(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
            is Place.Elsewhere -> showProblem(Problem(Problem.Kind.NO_HOME, name = owner(place.authority)))
        }
    }

    private fun place(i: Intent): Place {
        if (i.action == DownloadManager.ACTION_VIEW_DOWNLOADS) {
            return Place.Folder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        }
        val uri = i.data
        if (i.action != Intent.ACTION_VIEW || uri == null) return Place.Elsewhere(null)
        val authority = uri.authority
        var root: String? = null
        var doc: String? = null
        if (DocumentsContract.isRootUri(this, uri)) {
            root = DocumentsContract.getRootId(uri)
        } else if (DocumentsContract.isDocumentUri(this, uri)) {
            doc = DocumentsContract.getDocumentId(uri)
        }
        if (authority == MEDIA && root != null) {
            return when (root) {
                "images_root", "videos_root" -> Place.Gallery
                "audio_root" -> Place.Music
                "documents_root" ->
                    Place.Folder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
                else -> Place.Folder(Environment.getExternalStorageDirectory())
            }
        }
        if (authority == Documents.ESP) {
            val id = root ?: doc ?: return Place.Elsewhere(authority)
            val colon = id.indexOf(':')
            val volume = if (colon >= 0) id.substring(0, colon) else id
            val rel = if (colon >= 0) id.substring(colon + 1) else ""
            val base = Documents.path(this, "$volume:")
                ?: if (volume == DocumentsContract.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID) {
                    Environment.getExternalStorageDirectory()
                } else {
                    File("/storage", volume)
                }
            return Place.Folder(if (rel.isEmpty()) base else File(base, rel))
        }
        if (authority == DOWNLOADS) {
            return Place.Folder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        }
        return Place.Elsewhere(authority)
    }

    /** Files at [dir]; the Files dialog when Files cannot take it. */
    private fun inFiles(dir: File): Boolean {
        val files = Files.find(this)
        val component = files.component
        if (component == null) {
            showProblem(files.problem)
            return false
        }
        if (start(Intent(Intent.ACTION_VIEW).setComponent(component).putExtra(EXTRA_ABSOLUTE_PATH, dir.absolutePath))) {
            return true
        }
        showProblem(Problem(Problem.Kind.FILES_NO_PICKER, files.app.pkg, files.app.name))
        return false
    }

    /** Music, the system's app in place of the music app (APP-2). */
    private fun music(): Boolean {
        val app = SystemApps.find(this, SystemApps.MUSIC)
        val pkg = app.pkg
        val launch = if (app.ok && pkg != null) packageManager.getLaunchIntentForPackage(pkg) else null
        if (launch == null) Log.w(TAG, "browse: no music app (${app.state}), Files instead")
        return launch != null && start(launch)
    }

    private fun start(target: Intent): Boolean = try {
        startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(TAG, "browse: ${intent.action} ${intent.data ?: ""} -> ${target.component ?: target.selector ?: target}")
        finish()
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "browse: nothing started for $target", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "browse: nothing started for $target", e)
        false
    }

    /** The app owning a provider's authority, by its label, as the dialog names it. */
    private fun owner(authority: String?): String? {
        authority ?: return null
        val pi = packageManager.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0)) ?: return null
        return pi.applicationInfo?.loadLabel(packageManager)?.toString()
    }

    private companion object {
        const val MEDIA = "com.android.providers.media.documents"
        const val DOWNLOADS = "com.android.providers.downloads.documents"
    }
}
