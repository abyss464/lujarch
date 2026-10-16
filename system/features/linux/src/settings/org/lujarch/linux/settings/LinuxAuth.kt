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

package org.lujarch.linux.settings

import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog

/**
 * The owner's identity before Root sessions goes on (LNX-4), as Root access asks it (ROOT-1): the
 * fingerprint or the screen lock. Without a screen lock the page says so and offers to set one or
 * to go on; turning Root sessions off asks for nothing.
 */
object LinuxAuth {
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun <P : Fragment> authenticate(
        fragment: P,
        title: String,
        @RebuiltWithPage onCancelled: P.() -> Unit,
        @RebuiltWithPage onAuthenticated: P.() -> Unit,
    ) {
        val context = fragment.context ?: return
        val manager = context.getSystemService(BiometricManager::class.java)
        val result = manager?.canAuthenticate(AUTHENTICATORS)
        if (result != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "root sessions: canAuthenticate returned $result")
            val reason = if (result == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                R.string.lnx_auth_no_lock
            } else {
                R.string.lnx_auth_unavailable
            }
            fragment.showDialog("lnx-auth-unavailable") { d ->
                d.onCancel { onCancelled() }
                AlertDialog.Builder(d.context)
                    .setTitle(R.string.lnx_auth_unavailable_title)
                    .setMessage(reason)
                    .setPositiveButton(R.string.lnx_auth_set_lock) { _, _ ->
                        onCancelled()
                        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }
                    .setNeutralButton(R.string.lnx_auth_continue) { _, _ -> onAuthenticated() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> onCancelled() }
                    .create()
            }
            return
        }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(true)
            .build()
        prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                if (fragment.isAdded) fragment.onAuthenticated()
            }

            override fun onAuthenticationError(code: Int, message: CharSequence?) {
                Log.i(TAG, "root sessions: authentication error $code")
                if (fragment.isAdded) fragment.onCancelled()
            }
        })
    }
}
