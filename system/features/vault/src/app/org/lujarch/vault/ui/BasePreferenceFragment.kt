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

package org.lujarch.vault.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.BannerMessagePreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import java.nio.CharBuffer
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.lujarch.settings.pages.DialogScope
import org.lujarch.settings.pages.PageDialogHost
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.core.aegis.AegisException
import org.lujarch.vault.core.kdbx.KdbxException
import org.lujarch.vault.core.kdbx.KeyFile
import org.lujarch.vault.core.otp.InvalidOtp
import org.lujarch.vault.data.SyncFilesException
import org.lujarch.vault.data.KeysForgottenException
import org.lujarch.vault.data.KeystoreLockedException
import org.lujarch.vault.data.SourceUnavailableException
import org.lujarch.vault.data.Vault
import org.lujarch.vault.data.VaultState

/**
 * Base of every page: builds the preference screen in code, follows the
 * vault state while started, shows failures in a banner at the top and logs
 * them (never with values). Its dialogs are page dialogs (,
 * extra/pagedialog): `showDialog`, which a recreation brings back.
 */
abstract class BasePreferenceFragment : SettingsBasePreferenceFragment(), PageDialogHost {
    override val dialogLogTag: String
        get() = TAG

    private var banner: BannerMessagePreference? = null

    protected val prefContext: Context
        get() = preferenceManager.context

    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(prefContext)
        banner = BannerMessagePreference(prefContext).apply {
            key = KEY_BANNER
            isVisible = false
            setAttentionLevel(BannerMessagePreference.AttentionLevel.HIGH)
        }
        screen.addPreference(banner!!)
        preferenceScreen = screen
        buildScreen(screen, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (this is MenuProvider) {
            requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Vault.state.collect { if (isAdded) onVaultState(it) }
            }
        }
    }

    protected abstract fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?)

    /**
     * A stopped page shows nothing decrypted: its entries, fields and codes
     * are removed here (the session drops the data itself once no page is
     * visible) and come back from the vault when the page starts again.
     */
    override fun onStop() {
        super.onStop()
        onVaultState(Vault.emptyState())
    }

    /** Called with every vault state while the page is started. */
    protected open fun onVaultState(state: VaultState) {}

    // ---- failures ----

    /** Shows a failure at the top of the page, with an optional button that resolves it. */
    protected fun showBanner(
        title: CharSequence?,
        summary: CharSequence?,
        @StringRes actionText: Int = 0,
        action: (() -> Unit)? = null,
    ) {
        val b = banner ?: return
        if (title == null) {
            b.isVisible = false
            return
        }
        b.title = title
        b.summary = summary
        if (action != null && actionText != 0) {
            b.setPositiveButtonText(actionText)
            b.setPositiveButtonOnClickListener { action() }
            b.setPositiveButtonVisible(true)
        } else {
            b.setPositiveButtonVisible(false)
        }
        b.isVisible = true
    }

    /** Logs [t] and shows it in a toast: for failed user actions. */
    protected fun toastError(@StringRes action: Int, t: Throwable) {
        Log.e(TAG, "${javaClass.simpleName}: ${resourceName(action)} failed: ${t.javaClass.simpleName}: ${t.message}")
        val context = context ?: return
        Toast.makeText(context, getString(action) + "\n" + Errors.describe(context, t), Toast.LENGTH_LONG).show()
    }

    private fun resourceName(@StringRes res: Int): String = try {
        context?.resources?.getResourceEntryName(res) ?: res.toString()
    } catch (e: android.content.res.Resources.NotFoundException) {
        res.toString()
    }

    // A recreated activity (display density, dark mode, language) cancels the old
    // view's scope while a key derivation or a read may still run elsewhere; when
    // it ends, the coroutine resumes after this fragment was detached, with the
    // cancellation or with the work's failure (kotlinx hands a failure over in
    // preference to the cancellation). act logs such a failure (toastError only
    // logs without a context) and ends the coroutine in both cases; code that
    // runs anyway (a finally) goes through ifPageAlive.
    // tools/lint/lifecycle.py checks the pages for both.

    protected fun launchUi(block: suspend CoroutineScope.() -> Unit): Job =
        viewLifecycleOwner.lifecycleScope.launch(block = block)

    /** Whether the page's view is there and not destroyed (the fragment attached). */
    protected val pageAlive: Boolean
        get() = isAdded && view != null && viewLifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED

    /** Runs [block] only while [pageAlive]: for a finally or a callback that may outlive the page. */
    protected fun ifPageAlive(block: () -> Unit) {
        if (pageAlive) block()
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
     * A question with Cancel and [positive], a page dialog: [message] and
     * [onConfirm] run with the page that is there (again after a recreation).
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

    // ---- password dialog ----

    /**
     * The password dialog of a setup: [onEntered] gets the password as a char
     * array (the field's buffer is overwritten). The field is never saved
     * with the page and is wiped when the window goes (a password field of a
     * page dialog), so a recreated dialog asks again from an empty field.
     */
    protected fun passwordDialog(d: DialogScope, titleRes: Int, message: CharSequence?, onEntered: (CharArray) -> Unit): Dialog {
        val view = LayoutInflater.from(d.context).inflate(R.layout.dialog_password, null)
        val edit = view.findViewById<EditText>(R.id.password)
        val show = view.findViewById<CheckBox>(R.id.show_password)
        d.secret(edit)
        show.setOnCheckedChangeListener { _, on ->
            val sel = edit.selectionEnd
            edit.inputType = InputType.TYPE_CLASS_TEXT or
                if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            edit.setSelection(sel.coerceAtLeast(0))
        }
        val builder = AlertDialog.Builder(d.context)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = edit.text
                val chars = CharArray(text.length)
                text.getChars(0, text.length, chars, 0)
                // Overwrite the field's buffer before it is dropped.
                text.replace(0, text.length, " ".repeat(text.length))
                text.clear()
                onEntered(chars)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (message != null) builder.setMessage(message)
        return builder.create()
    }

    protected fun utf8(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) bb.array().fill(0)
        return out
    }

    protected fun <T : Preference> PreferenceGroup.add(pref: T, block: T.() -> Unit = {}): T {
        pref.block()
        addPreference(pref)
        return pref
    }

    companion object {
        private const val KEY_BANNER = "error_banner"
    }
}

/** Localized descriptions of failures. */
object Errors {
    fun describe(context: Context, t: Throwable): String = when (t) {
        is SyncFilesException -> when (t.kind) {
            SyncFilesException.Kind.UNAVAILABLE -> context.getString(R.string.error_daemon_unavailable, t.detail)
            SyncFilesException.Kind.TIMEOUT -> context.getString(R.string.error_daemon_timeout)
            SyncFilesException.Kind.IO -> context.getString(R.string.error_daemon_io, t.detail)
            SyncFilesException.Kind.PROTOCOL -> context.getString(R.string.error_daemon_protocol, t.detail)
            SyncFilesException.Kind.REMOTE -> context.getString(R.string.error_daemon_remote, t.detail)
            SyncFilesException.Kind.CLOSED -> context.getString(R.string.error_daemon_closed)
        }
        is KdbxException -> when (t.reason) {
            KdbxException.Reason.NOT_KDBX -> context.getString(R.string.error_kdbx_not_kdbx)
            KdbxException.Reason.KDB1 -> context.getString(R.string.error_kdbx_kdb1)
            KdbxException.Reason.UNSUPPORTED_VERSION -> context.getString(R.string.error_kdbx_version, t.message)
            KdbxException.Reason.UNSUPPORTED_CIPHER -> context.getString(R.string.error_kdbx_cipher, t.message)
            KdbxException.Reason.UNSUPPORTED_KDF -> context.getString(R.string.error_kdbx_kdf, t.message)
            KdbxException.Reason.WRONG_KEY -> context.getString(R.string.error_kdbx_wrong_key)
            KdbxException.Reason.CORRUPT -> context.getString(R.string.error_kdbx_corrupt, t.message)
        }
        is AegisException -> when (t.reason) {
            AegisException.Reason.FORMAT -> context.getString(R.string.error_aegis_format, t.message)
            AegisException.Reason.NO_PASSWORD_SLOT -> context.getString(R.string.error_aegis_no_slot)
            AegisException.Reason.WRONG_PASSWORD -> context.getString(R.string.error_aegis_wrong_password)
            AegisException.Reason.WRONG_KEY -> context.getString(R.string.error_aegis_wrong_key)
        }
        is KeystoreLockedException -> context.getString(R.string.error_keystore)
        is KeysForgottenException -> context.getString(R.string.error_keys_forgotten)
        is SourceUnavailableException -> when (t.reason) {
            SourceUnavailableException.Reason.PERMISSION -> context.getString(R.string.error_source_permission)
            SourceUnavailableException.Reason.MISSING -> context.getString(R.string.error_source_missing)
            SourceUnavailableException.Reason.TOO_LARGE -> context.getString(R.string.error_source_too_large, t.message)
        }
        is KeyFile.InvalidKeyFile -> context.getString(R.string.error_key_file, t.message)
        is InvalidOtp -> context.getString(R.string.error_otp, t.message)
        is java.io.FileNotFoundException -> context.getString(R.string.error_aegis_missing)
        else -> context.getString(R.string.error_unexpected, t.message ?: t.javaClass.simpleName)
    }
}

/** Localized size and time formatting. */
object Format {
    fun bytes(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

    fun dateTime(context: Context, millis: Long): String {
        val d = Date(millis)
        return DateFormat.getMediumDateFormat(context).format(d) + " " + DateFormat.getTimeFormat(context).format(d)
    }

    fun time(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

    /** "123 456" / "1234 5678": codes grouped for reading. */
    fun code(code: String): String = when (code.length) {
        6 -> code.substring(0, 3) + " " + code.substring(3)
        8 -> code.substring(0, 4) + " " + code.substring(4)
        else -> code
    }
}
