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

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment

/** The process's page dialogs (PageDialogLife.kt). */
object PageDialogs {
    val registry = PageDialogRegistry()
}

/** Implemented by the pages' base classes: the log tag, and what a page does about a dialog it lost. */
interface PageDialogHost {
    val dialogLogTag: String

    /**
     * A dialog of this page came back from the saved state with nothing to
     * build it from (the process was restarted, or it showed work that ended
     * with the page's view): it is not shown again. Called on the main thread
     * once the page is created.
     */
    fun onDialogLost(key: String) {}
}

/**
 * What a page's dialog builder is given: the dialog's context and the
 * window's settings that the fragment owns (it replaces the dialog's own
 * cancelable flag and its cancel and dismiss listeners).
 */
class DialogScope internal constructor(
    /** Build with this context (the page's activity at this build), never with one captured before. */
    val context: Context,
    /** True when this window replaces one the page's recreation took away. */
    val restored: Boolean,
) {
    /** Whether Back and a touch outside cancel the dialog (instead of AlertDialog.Builder.setCancelable). */
    var cancelable = true

    internal var cancelHook: (() -> Unit)? = null
    internal var dismissHook: (() -> Unit)? = null
    internal val secrets = ArrayList<TextView>()

    /** Runs when the owner cancels this window: Back, a touch outside (instead of setOnCancelListener). */
    fun onCancel(block: () -> Unit) {
        cancelHook = block
    }

    /**
     * Runs when this window is closed, by a button, a cancel or code; not
     * when it goes with a recreation of its page (instead of setOnDismissListener).
     */
    fun onDismiss(block: () -> Unit) {
        dismissHook = block
    }

    /**
     * Fields holding a secret: their text never goes into the saved state
     * (a recreated dialog shows them empty) and is overwritten when the
     * window goes. Password fields are secret without this.
     */
    fun secret(vararg fields: TextView) {
        secrets += fields
    }
}

/** Closes a shown page dialog from the page's code. */
class PageDialogHandle internal constructor(private val fragment: PageDialog) {
    /** Closes it as a button would (its onEnd runs); nothing when it has ended. */
    fun dismiss() = fragment.close()
}

/**
 * Shows a dialog of this page that survives the page's recreation (dark
 * mode, language, display density; ). [build] makes the dialog
 * (`AlertDialog.Builder(d.context)...create()`, not shown) and runs again
 * with the recreated page as its receiver, so its buttons act on the page
 * that is there; the dialog's view state (texts, choices) comes back, secret
 * fields empty. [onEnd] runs once when the dialog ends for good: answered,
 * cancelled, dismissed, or its page gone without an answer; it must not use
 * the page. [restorable] false: the dialog shows work of the page's view (a
 * progress), which a recreation ends; it ends then too, and the recreated
 * page hears of it through [PageDialogHost.onDialogLost]. A dialog restored
 * in a restarted process has nothing to build it from and is dropped the
 * same way. Returns null (after running [onEnd]) when the page is gone.
 */
fun <P : Fragment> P.showDialog(
    key: String,
    restorable: Boolean = true,
    @OutlivesPage onEnd: (() -> Unit)? = null,
    @RebuiltWithPage build: P.(DialogScope) -> Dialog?,
): PageDialogHandle? {
    val tag = (this as? PageDialogHost)?.dialogLogTag ?: PageDialog.TAG
    if (!isAdded || isRemoving || childFragmentManager.isDestroyed) {
        Log.w(tag, "${javaClass.simpleName}: dialog $key not shown: the page is gone")
        onEnd?.invoke()
        return null
    }
    val token = PageDialogs.registry.add(key, javaClass.name, build, restorable, onEnd)
    val dialog = PageDialog()
    dialog.arguments = Bundle().apply {
        putString(PageDialog.ARG_TOKEN, token)
        putString(PageDialog.ARG_KEY, key)
    }
    val fm = childFragmentManager
    val tx = fm.beginTransaction().setReorderingAllowed(true).add(dialog, PageDialog.FRAGMENT_TAG + token)
    // A page that is stopped shows it when it starts again (as a dialog window would).
    if (fm.isStateSaved) tx.commitAllowingStateLoss() else tx.commit()
    return PageDialogHandle(dialog)
}

/**
 * The fragment of every page dialog: a child of its page, so it goes with
 * the page and comes back with it. Its decisions are PageDialogLife's.
 */
class PageDialog : DialogFragment() {
    private lateinit var life: PageDialogLife
    private var scope: DialogScope? = null
    private var viewGoing = false
    private var cancelled = false
    private var closeRequested = false

    private val key: String
        get() = arguments?.getString(ARG_KEY).orEmpty()

    private val token: String
        get() = arguments?.getString(ARG_TOKEN).orEmpty()

    private val logTag: String
        get() = (parentFragment as? PageDialogHost)?.dialogLogTag ?: TAG

    private val pageName: String
        get() = parentFragment?.javaClass?.simpleName ?: "?"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        life = PageDialogLife(PageDialogs.registry, token)
        val page = parentFragment
        when {
            closeRequested -> {
                life.closed()
                drop(lost = false, why = "closed before it was shown")
            }
            page == null -> {
                life.declined()
                drop(lost = false, why = "no page")
            }
            // Restored by the system with nothing in this process to build it from.
            life.entry(page.javaClass.name) == null ->
                drop(lost = true, why = "nothing to build it from (a restarted process, or work that ended with the page)")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val page = parentFragment
        val entry = page?.let { life.entry(it.javaClass.name) }
        if (page == null || entry == null) {
            life.declined()
            drop(lost = false, why = "nothing to build it from")
            return Dialog(requireContext())
        }
        val scope = DialogScope(requireContext(), restored = savedInstanceState != null)
        @Suppress("UNCHECKED_CAST")
        val dialog = (entry.build as Fragment.(DialogScope) -> Dialog?)(page, scope)
        if (dialog == null) {
            life.declined()
            drop(lost = false, why = "its page had nothing to show")
            return Dialog(requireContext())
        }
        this.scope = scope
        isCancelable = scope.cancelable
        // The content exists from here on: the secret fields are known before any save.
        dialog.create()
        markSecrets(dialog)
        if (scope.restored) Log.i(logTag, "$pageName: dialog $key restored after its page was recreated")
        return dialog
    }

    override fun onCancel(dialog: DialogInterface) {
        cancelled = true
        scope?.cancelHook?.let { hook -> if (parentFragment?.isAdded == true) hook() }
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface) {
        wipeSecrets()
        if (!viewGoing) {
            scope?.dismissHook?.let { hook -> if (parentFragment?.isAdded == true) hook() }
            if (life.closed()) Log.d(logTag, "$pageName: dialog $key ${if (cancelled) "cancelled" else "closed"}")
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        // The window goes with the view (a recreation, or the page going):
        // DialogFragment dismisses it and calls onDismiss, which is no answer.
        viewGoing = true
        wipeSecrets()
        try {
            super.onDestroyView()
        } finally {
            viewGoing = false
        }
    }

    override fun onDestroy() {
        val recreating = activity?.isChangingConfigurations == true
        if (life.destroyed(recreating)) {
            Log.i(logTag, "$pageName: dialog $key ended: " +
                if (life.end == PageDialogLife.End.NOT_RESTORABLE) "its work ended with the recreated page" else "its page went away unanswered")
        }
        super.onDestroy()
    }

    /** From [PageDialogHandle]: closed as a button would, before or after it is shown. */
    internal fun close() {
        if (isAdded) {
            if (life.closed()) Log.d(logTag, "$pageName: dialog $key closed")
            dismissAllowingStateLoss()
        } else {
            // Not added yet: ended now, removed when it comes (onCreate).
            closeRequested = true
            PageDialogs.registry.end(token)
        }
    }

    /** Not shown (it has ended): removed at once; [lost]: the page is told after its creation. */
    private fun drop(lost: Boolean, why: String) {
        showsDialog = false
        Log.i(logTag, "$pageName: dialog $key not shown: $why")
        val page = parentFragment
        Handler(Looper.getMainLooper()).post {
            if (isAdded) dismissAllowingStateLoss()
            if (lost && page != null && page.isAdded) (page as? PageDialogHost)?.onDialogLost(key)
        }
    }

    private fun markSecrets(dialog: Dialog) {
        val s = scope ?: return
        dialog.window?.decorView?.let { collectPasswords(it, s.secrets) }
        for (f in s.secrets) f.isSaveEnabled = false
    }

    private fun collectPasswords(v: View, out: MutableList<TextView>) {
        if (v is EditText && isPassword(v.inputType) && v !in out) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) collectPasswords(v.getChildAt(i), out)
    }

    /** Overwrites and clears the secret fields' text (the password buffers go with the window). */
    private fun wipeSecrets() {
        val s = scope ?: return
        for (f in s.secrets) {
            val text = f.editableText ?: continue
            val n = text.length
            if (n == 0) continue
            text.replace(0, n, " ".repeat(n))
            text.clear()
        }
    }

    companion object {
        const val TAG = "PageDialog"
        internal const val ARG_TOKEN = "page_dialog_token"
        internal const val ARG_KEY = "page_dialog_key"
        internal const val FRAGMENT_TAG = "page-dialog:"

        private fun isPassword(inputType: Int): Boolean {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }
    }
}
