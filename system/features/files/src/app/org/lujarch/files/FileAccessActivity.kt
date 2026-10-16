package org.lujarch.files

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.BidiFormatter
import android.util.Log
import org.lujarch.files.Problem.Kind

/**
 * The one UI of File access: a dialog when a request cannot be served, naming the problem, with
 * a way forward where there is one (Files' App info, choosing again). Closing it ends the request
 * with RESULT_CANCELED. The problem is kept in the saved state and the dialog is built again
 * after a configuration change or a recreation, so it is never lost (FILE-2).
 */
internal abstract class FileAccessActivity : Activity() {
    private var problem: Problem? = null
    private var dialog: AlertDialog? = null

    protected val showingProblem: Boolean get() = problem != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Problem.from(savedInstanceState?.getBundle(STATE_PROBLEM))?.let { show(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        problem?.let { outState.putBundle(STATE_PROBLEM, it.toBundle()) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles its configuration changes (a recreation would lose a request in
        // flight); the dialog is built again in the new language, theme and size.
        problem?.let { show(it) }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    /** Shows [p] and logs it; the request ends from the dialog. */
    protected fun showProblem(p: Problem) {
        Log.w(TAG, "${javaClass.simpleName}: cannot serve ${intent?.action} ${intent?.data ?: ""}: $p")
        show(p)
    }

    /** "Choose again" after a pick Files made could not be handed over. */
    protected open fun chooseAgain() = end()

    /** Ends the request unanswered. */
    protected fun end() {
        problem = null
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun show(p: Problem) {
        dialog?.dismiss()
        problem = p
        val name = BidiFormatter.getInstance().unicodeWrap(p.name ?: getString(R.string.files_name))
        val b = AlertDialog.Builder(this)
        when (p.kind) {
            Kind.FILES_NOT_INSTALLED -> b.setTitle(getString(R.string.files_unavailable_title, name))
                .setMessage(R.string.files_not_installed)
            Kind.FILES_NOT_SYSTEM -> b.setTitle(getString(R.string.files_unavailable_title, name))
                .setMessage(R.string.files_not_system)
            Kind.FILES_DISABLED -> b.setTitle(getString(R.string.files_unavailable_title, name))
                .setMessage(R.string.files_disabled)
            Kind.FILES_NO_PICKER -> b.setTitle(getString(R.string.files_unavailable_title, name))
                .setMessage(R.string.files_no_picker)
            Kind.FOLDER_BLOCKED -> b.setTitle(R.string.folder_blocked_title).setMessage(R.string.folder_blocked_message)
            Kind.FOLDER_NOT_LOCAL -> b.setTitle(R.string.folder_blocked_title).setMessage(R.string.folder_not_local_message)
            Kind.WRITE_FAILED -> b.setTitle(R.string.handover_failed_title).setMessage(R.string.write_failed_message)
            Kind.GRANT_FAILED -> b.setTitle(R.string.handover_failed_title).setMessage(R.string.grant_failed_message)
            Kind.NO_HOME -> b.setTitle(R.string.browse_no_home_title).setMessage(
                if (p.name != null) getString(R.string.browse_no_home_message, name)
                else getString(R.string.browse_no_home_unknown),
            )
        }
        val pkg = p.pkg
        when {
            p.kind.afterPick -> {
                b.setPositiveButton(R.string.choose_again) { _, _ -> problem = null; chooseAgain() }
                b.setNegativeButton(android.R.string.cancel) { _, _ -> end() }
            }
            p.kind == Kind.NO_HOME -> b.setPositiveButton(android.R.string.ok) { _, _ -> end() }
            pkg != null -> {
                b.setPositiveButton(R.string.app_info) { _, _ -> appInfo(pkg) }
                b.setNegativeButton(android.R.string.ok) { _, _ -> end() }
            }
            else -> b.setPositiveButton(android.R.string.ok) { _, _ -> end() }
        }
        b.setOnCancelListener { end() }
        dialog = b.create().also {
            it.setCanceledOnTouchOutside(false)
            it.show()
        }
    }

    private fun appInfo(pkg: String) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no App info page for $pkg", e)
        }
        end()
    }

    private companion object {
        const val STATE_PROBLEM = "problem"
    }
}
