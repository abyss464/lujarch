package org.lujarch.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * An app asks to choose a file (GET_CONTENT, OPEN_DOCUMENT), to save one (CREATE_DOCUMENT) or to
 * choose a folder (OPEN_DOCUMENT_TREE). Media-only GET_CONTENT goes to the photo picker as
 * DocumentsUI sends it (PhotoPicker); everything else opens Files for result with the request's
 * action, type and extras, and Files' answer goes back to the app as DocumentsUI would answer
 * (Handover). Lives in the asking app's task, invisible, until the answer is handed over.
 */
internal class PickActivity : FileAccessActivity() {
    private var waiting = false
    private var worker: ExecutorService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            waiting = savedInstanceState.getBoolean(STATE_WAITING)
            // Recreated in a new process: Files' answer still comes to onActivityResult, or a
            // dialog is back. Neither means the answer was lost while it was handed over.
            if (!waiting && !showingProblem) {
                Log.w(TAG, "pick: recreated while handing over, request ended")
                end()
            }
            return
        }
        val action = intent.action
        when (action) {
            ACTION_OPEN_EXTERNAL_DIRECTORY -> {
                // StorageVolume.createAccessIntent: deprecated, ends with RESULT_CANCELED since
                // Android 10 (DocumentsUI's ScopedAccessActivity).
                Log.i(TAG, "pick: $action from $referrer, cancelled as the API says")
                end()
                return
            }
            Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_CREATE_DOCUMENT,
            Intent.ACTION_OPEN_DOCUMENT_TREE -> Unit
            else -> {
                Log.w(TAG, "pick: unknown action $action, request ended")
                end()
                return
            }
        }
        if (action == Intent.ACTION_GET_CONTENT && toPhotoPicker()) return
        openFiles()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_WAITING, waiting)
    }

    override fun onDestroy() {
        worker?.shutdownNow()
        super.onDestroy()
    }

    override fun chooseAgain() = openFiles()

    /** DocumentsUI's TrampolineActivity: true when the photo picker took the request. */
    private fun toPhotoPicker(): Boolean {
        val picker = PhotoPicker.component(packageManager, intent.type, packageName) ?: return false
        // The photo picker sends here what it does not handle and its "Browse": never back.
        if (referrer?.host == picker.packageName || !PhotoPicker.handles(intent)) return false
        val forward = Intent(intent)
            .addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
            .setComponent(picker)
        return try {
            startActivity(forward)
            Log.i(TAG, "pick: GET_CONTENT ${intent.type} from $referrer -> $picker")
            finish()
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "pick: photo picker $picker did not start, Files instead", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "pick: photo picker $picker did not start, Files instead", e)
            false
        }
    }

    private fun openFiles() {
        val files = Files.find(this)
        val component = files.component ?: return showProblem(files.problem)
        val request = Intent(intent).apply {
            // Only the request itself: no grants, no forwarding, no target of the caller's.
            flags = 0
            setComponent(component)
            setPackage(null)
            selector = null
            clipData = null
            setDataAndType(null, intent.type)
            removeExtra(DocumentsContract.EXTRA_INITIAL_URI)
            initialFolder()?.let { putExtra(EXTRA_ABSOLUTE_PATH, it) }
        }
        try {
            startActivityForResult(request, REQUEST_FILES)
            waiting = true
            Log.i(TAG, "pick: ${intent.action} ${intent.type ?: ""} from $referrer -> $component")
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "pick: $component did not start", e)
            showProblem(Problem(Problem.Kind.FILES_NO_PICKER, files.app.pkg, files.app.name))
        } catch (e: SecurityException) {
            Log.e(TAG, "pick: $component did not start", e)
            showProblem(Problem(Problem.Kind.FILES_NO_PICKER, files.app.pkg, files.app.name))
        }
    }

    private fun initialFolder(): String? {
        val uri = intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java) ?: return null
        return Documents.initialFolder(this, uri).also { Log.i(TAG, "pick: initial location $uri -> $it") }
    }

    @Deprecated("Activity result of Files")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILES) return
        waiting = false
        val picked = data?.let { d ->
            d.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
                ?: listOfNotNull(d.data)
        }.orEmpty()
        if (resultCode != RESULT_OK || picked.isEmpty()) {
            Log.i(TAG, "pick: ${intent.action} cancelled in Files")
            end()
            return
        }
        val authorities = Files.find(this).authorities
        val handover = Handover(applicationContext, Intent(intent), authorities)
        val pool = worker ?: Executors.newSingleThreadExecutor().also { worker = it }
        pool.execute {
            val outcome = try {
                handover.map(picked)
            } catch (e: RuntimeException) {
                Log.e(TAG, "pick: handing over $picked failed", e)
                Handover.Outcome.Refused(Problem(Problem.Kind.GRANT_FAILED))
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) deliver(picked, outcome) }
        }
    }

    private fun deliver(picked: List<Uri>, outcome: Handover.Outcome) {
        when (outcome) {
            is Handover.Outcome.Refused -> showProblem(outcome.problem)
            is Handover.Outcome.Done -> {
                val uris = outcome.uris
                val result = Intent()
                if (uris.size == 1) {
                    result.data = uris[0]
                } else {
                    // As DocumentsUI: the request's types as the clip's description.
                    val types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                        ?: arrayOf(intent.type ?: "*/*")
                    val clip = ClipData(null, types, ClipData.Item(uris[0]))
                    for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
                    result.clipData = clip
                }
                result.addFlags(outcome.flags)
                setResult(RESULT_OK, result)
                try {
                    finish()
                    Log.i(TAG, "pick: ${intent.action} -> ${uris.size} to $referrer: $picked as $uris")
                } catch (e: SecurityException) {
                    // The grant is checked when the result is sent; Handover's access check
                    // should have caught it.
                    Log.e(TAG, "pick: the system refused to grant $uris", e)
                    setResult(RESULT_CANCELED)
                    showProblem(Problem(Problem.Kind.GRANT_FAILED))
                }
            }
        }
    }

    private companion object {
        const val REQUEST_FILES = 1
        const val STATE_WAITING = "waiting"
        const val ACTION_OPEN_EXTERNAL_DIRECTORY = "android.os.storage.action.OPEN_EXTERNAL_DIRECTORY"
    }
}

/** The folder a file manager opens at (OpenIntents' extra, which Files reads). */
internal const val EXTRA_ABSOLUTE_PATH = "org.openintents.extra.ABSOLUTE_PATH"
