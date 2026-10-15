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

package org.lujarch.settings.pages

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.BannerMessagePreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Base of every page: builds the preference screen in code, shows failures in
 * an error banner at the top (one slot per data source, so one success does
 * not hide another failure) and logs every failure. Its dialogs are page
 * dialogs (`showDialog`), which a recreation brings back.
 */
abstract class BasePreferenceFragment : SettingsBasePreferenceFragment(), PageDialogHost {
    override val dialogLogTag: String
        get() = TAG

    private var banner: BannerMessagePreference? = null
    private val errors = LinkedHashMap<String, Pair<Int, Throwable>>()
    private val lastLogged = HashMap<String, String>()

    protected val prefContext: Context
        get() = preferenceManager.context

    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(prefContext)
        banner = BannerMessagePreference(prefContext).apply {
            key = KEY_BANNER
            isVisible = false
            setAttentionLevel(BannerMessagePreference.AttentionLevel.HIGH)
            setPositiveButtonText(R.string.action_retry)
            setPositiveButtonOnClickListener { refresh() }
            // The system's own text of the failure shown (never in the sentence).
            setNegativeButtonText(R.string.error_details)
            setNegativeButtonVisible(false)
        }
        screen.addPreference(banner!!)
        preferenceScreen = screen
        buildScreen(screen, savedInstanceState)
        if (savedInstanceState == null) {
            arguments?.getString(BaseActivity.EXTRA_HIGHLIGHT)?.let { scrollToPreference(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (this is MenuProvider) {
            requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }
    }

    /** Adds the page content below the error banner. */
    protected abstract fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?)

    /** Reloads the page data; also bound to the banner's retry button. */
    open fun refresh() {}

    // ---- failures ----

    /** Shows [t] in the banner under [slot] and logs it (repeats of the same failure once). */
    protected fun setError(slot: String, @StringRes action: Int, t: Throwable) {
        logFailure(slot, action, t)
        val context = context ?: return
        errors.remove(slot)
        errors[slot] = action to t
        renderBanner(context)
    }

    protected fun clearError(slot: String) {
        lastLogged.remove(slot)
        if (errors.remove(slot) != null) context?.let { renderBanner(it) }
    }

    /**
     * Logs [t] and tells the owner: for failed user actions, a dialog with the reason in the
     * owner's language and the system's own text behind "Details".
     */
    protected fun toastError(@StringRes action: Int, t: Throwable) {
        Log.e(TAG, "${javaClass.simpleName}: ${resourceName(action)} failed: ${t.message}", t)
        if (context == null) return
        showError(action, t)
    }

    /** The failure of [action]: its sentence in a page dialog, the system's text behind "Details". */
    private fun showError(@StringRes action: Int, t: Throwable) = showDialog("error") { d ->
        Errors.dialog(d.context, getString(action), t) { showErrorDetails(action, t) }
    }

    private fun showErrorDetails(@StringRes title: Int, t: Throwable) = showDialog("error-details") { d ->
        Errors.detailsDialog(d.context, getString(title), t)
    }

    private fun logFailure(slot: String, @StringRes action: Int, t: Throwable) {
        val line = "${javaClass.simpleName}: ${resourceName(action)} failed: ${t.message}"
        if (lastLogged[slot] == line) return
        lastLogged[slot] = line
        if (Errors.isUnavailable(t)) Log.w(TAG, line) else Log.e(TAG, line, t)
    }

    private fun resourceName(@StringRes res: Int): String = try {
        context?.resources?.getResourceEntryName(res) ?: res.toString()
    } catch (e: android.content.res.Resources.NotFoundException) {
        res.toString()
    }

    private fun renderBanner(context: Context) {
        val banner = banner ?: return
        // An unreachable service explains every other failure: it is shown first.
        val failures = errors.values
        val entry = failures.firstOrNull { Errors.isUnavailable(it.second) } ?: failures.lastOrNull()
        if (entry == null) {
            banner.isVisible = false
            return
        }
        val (action, t) = entry
        banner.setAttentionLevel(BannerMessagePreference.AttentionLevel.HIGH)
        banner.setPositiveButtonVisible(true)
        banner.title = if (Errors.isUnavailable(t)) {
            getString(R.string.error_service_title)
        } else {
            getString(action)
        }
        banner.summary = Errors.describe(context, t)
        val title = if (Errors.isUnavailable(t)) R.string.error_service_title else action
        banner.setNegativeButtonVisible(Errors.details(t) != null)
        banner.setNegativeButtonOnClickListener { showErrorDetails(title, t) }
        banner.isVisible = true
    }

    // ---- coroutines ----
    //
    // A recreated activity (display density, dark mode, language) cancels the old
    // view's scope, and a coroutine whose reply was already posted to the main
    // thread resumes after this fragment was detached: with the cancellation, or
    // with the reply's failure (kotlinx hands a failure over in preference to the
    // cancellation). Page code that carries on from there throws in getString()
    // or requireContext(). attempt, act and orNull log such a failure and end the
    // coroutine in both cases (setError and toastError only log without a
    // context); code that runs anyway (a finally) goes through ifPageAlive.
    // tools/lint/lifecycle.py checks the pages for both.

    /** Launches in the view lifecycle: cancelled when the page view goes away. */
    protected fun launchUi(block: suspend CoroutineScope.() -> Unit): Job =
        viewLifecycleOwner.lifecycleScope.launch(block = block)

    /** Whether the page's view is there and not destroyed (the fragment attached). */
    protected val pageAlive: Boolean
        get() = isAdded && view != null && viewLifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED

    /** Runs [block] only while [pageAlive]: for a finally or a callback that may outlive the page. */
    protected fun ifPageAlive(block: () -> Unit) {
        if (pageAlive) block()
    }

    /** Runs [block]; a failure goes to the banner under [slot] and returns null. */
    protected suspend fun <T> attempt(slot: String, @StringRes action: Int, block: suspend () -> T): T? =
        try {
            block().also { clearError(slot) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(slot, action, e)
            currentCoroutineContext().ensureActive()
            null
        }

    /** Runs a user action; a failure is logged and shown in a toast. Returns true on success. */
    protected suspend fun act(@StringRes action: Int, block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toastError(action, e)
            currentCoroutineContext().ensureActive()
            false
        }

    /**
     * Runs [block] for a value the page can do without: a failure is logged
     * (once while it repeats) and gives null. Unlike runCatching it never
     * catches the page's cancellation, so a cancelled page does not go on.
     */
    protected suspend fun <T> orNull(what: String, block: suspend () -> T): T? =
        try {
            block().also { lastLogged.remove(ORNULL + what) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val line = "${javaClass.simpleName}: $what failed: ${e.message}"
            if (lastLogged.put(ORNULL + what, line) != line) Log.w(TAG, line)
            currentCoroutineContext().ensureActive()
            null
        }

    // ---- helpers ----

    /**
     * A question with Cancel and [positive], a page dialog: [message] and
     * [onConfirm] run with the page that is there (again after a recreation,
     * in its language).
     */
    protected fun <P : BasePreferenceFragment> P.confirm(
        key: String,
        @StringRes title: Int,
        @RebuiltWithPage message: P.() -> CharSequence,
        @StringRes positive: Int,
        @RebuiltWithPage onConfirm: P.() -> Unit,
    ) {
        showDialog(key) { d ->
            AlertDialog.Builder(d.context)
                .setTitle(title)
                .setMessage(message())
                .setPositiveButton(positive) { _, _ -> onConfirm() }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    protected fun <T : Preference> PreferenceGroup.add(pref: T, block: T.() -> Unit = {}): T {
        pref.block()
        addPreference(pref)
        return pref
    }

    companion object {
        private const val KEY_BANNER = "error_banner"
        /** orNull's entries in lastLogged, apart from the banner's slots. */
        private const val ORNULL = "orNull:"
    }
}
