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

package org.lujarch.root.settings

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.lujarch.root.IRoot
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog

/**
 * The owner's identity before Root access is given away: giving an
 * app Root, or widening what its su sessions may do, asks for the device
 * credential or a strong biometric right before the change is sent, on top
 * of the confirmation that says what the identity reaches. Taking Root away
 * and every narrowing change ask for nothing: a lock screen that is not
 * there must never keep the owner from making the system safer.
 *
 * Without a screen lock nothing can be authenticated. The change is then not
 * carried out silently either: the page says so and offers the screen lock
 * settings, and the change goes through only when the owner asks for it
 * again in that dialog.
 */
object RootAuth {
    /** What a grant or a widening change asks for; null while it can be authenticated. */
    fun unavailableReason(context: Context): Int? {
        val manager = context.getSystemService(BiometricManager::class.java)
        if (manager == null) {
            Log.w(TAG, "root: no BiometricManager")
            return R.string.root_auth_unavailable_other
        }
        return when (val result = manager.canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            // No screen lock and no biometric enrolled: nothing to ask.
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> R.string.root_auth_unavailable_no_lock
            else -> {
                Log.w(TAG, "root: canAuthenticate returned $result")
                R.string.root_auth_unavailable_other
            }
        }
    }

    /**
     * Asks for the owner's credential and runs [onAuthenticated] when it was
     * given. Without a way to authenticate, [explain] says so and offers the
     * screen lock settings and the change itself (a page dialog: the
     * callbacks run with the page that is there, [fragment] or the page that
     * replaced it in a recreation).
     */
    fun <P : Fragment> authenticate(
        fragment: P,
        title: String,
        subtitle: String? = null,
        @RebuiltWithPage onCancelled: P.() -> Unit = {},
        @RebuiltWithPage onAuthenticated: P.() -> Unit,
    ) {
        val context = fragment.context ?: return
        val unavailable = unavailableReason(context)
        if (unavailable != null) {
            explain(fragment, unavailable, onCancelled, onAuthenticated)
            return
        }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(AUTHENTICATORS)
            // The owner confirms the biometric match itself, so an
            // unintended touch of the sensor cannot grant Root.
            .setConfirmationRequired(true)
            .build()
        prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            // The page is gone before the prompt answers: nothing is sent.
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                if (fragment.isAdded) fragment.onAuthenticated()
            }

            override fun onAuthenticationError(code: Int, message: CharSequence?) {
                // Cancelled or failed: the caller's change is dropped and the
                // page shows the unchanged state again. The prompt has said
                // why; a second message would only repeat it.
                Log.i(TAG, "root: authentication error $code")
                if (fragment.isAdded) fragment.onCancelled()
            }
        })
    }

    /**
     * No authentication is possible: the reason, the way to set a screen
     * lock, and the change itself as the second choice.
     */
    private fun <P : Fragment> explain(
        fragment: P,
        reason: Int,
        @RebuiltWithPage onCancelled: P.() -> Unit,
        @RebuiltWithPage onProceed: P.() -> Unit,
    ) {
        fragment.showDialog("root-auth-unavailable") { d ->
            val context = d.context
            d.onCancel { onCancelled() }
            AlertDialog.Builder(context)
                .setTitle(R.string.root_auth_unavailable_title)
                .setMessage(context.getString(reason) + "\n\n" + context.getString(R.string.root_auth_unavailable_message))
                .setPositiveButton(R.string.root_auth_set_lock) { _, _ ->
                    onCancelled()
                    openScreenLock(this)
                }
                .setNeutralButton(R.string.root_auth_continue) { _, _ -> onProceed() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> onCancelled() }
                .create()
        }
    }

    /** Android's own page for the screen lock. */
    fun openScreenLock(fragment: Fragment) {
        val context = fragment.context ?: return
        for (action in listOf(Settings.ACTION_BIOMETRIC_ENROLL, Settings.ACTION_SECURITY_SETTINGS)) {
            val intent = Intent(action)
            if (action == Settings.ACTION_BIOMETRIC_ENROLL) {
                intent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, AUTHENTICATORS)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                fragment.startActivity(intent)
                return
            }
        }
        Log.w(TAG, "root: no page for the screen lock")
    }

    /**
     * Whether [next] lets su sessions of the app do more than [current]:
     * root's user or group ID, more capabilities, more supplementary groups,
     * another SELinux domain (a domain can only be judged by the policy, so
     * every change of it counts), the global mount namespace (the sessions
     * then see and change every mount of the system) or no-new-privileges
     * turned off. Narrowing changes are not authenticated.
     */
    fun widens(current: RootProfile, next: RootProfile): Boolean {
        if (next.uid == 0 && current.uid != 0) return true
        if (next.gid == 0 && current.gid != 0) return true
        if ((next.capabilities and current.capabilities.inv()) != 0L) return true
        if (!current.groups.containsAll(next.groups)) return true
        if (next.selinuxDomain != current.selinuxDomain) return true
        if (next.namespace == NS_GLOBAL && current.namespace != NS_GLOBAL) return true
        if (!next.noNewPrivileges && current.noNewPrivileges) return true
        return false
    }

    const val NS_GLOBAL = IRoot.NAMESPACE_GLOBAL

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
}
