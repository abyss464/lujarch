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

package org.lujarch.linux.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors

/**
 * What the Terminal app's windows share: while one is started it hears lujarch_linux and takes
 * what Linux's desktop copies onto Android's clipboard while the clipboard is lent; while one is
 * in front it answers what Linux asks of the owner (an authorization, LNX-7) and reads NFC cards
 * for Linux while NFC card reading is lent.
 */
abstract class LinuxActivity : Activity(), LinuxEvents.Sink {
    protected val scope = MainScope()
    private val events = LinuxEvents(this)
    private var nfc: NfcReader? = null
    protected var inFront = false
        private set

    override fun onStart() {
        super.onStart()
        background.execute { events.register() }
    }

    override fun onStop() {
        background.execute { events.unregister() }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        if (Linux.lent(this, "nfc")) nfc = NfcReader(this).also { it.start() }
    }

    override fun onPause() {
        inFront = false
        nfc?.stop()
        nfc = null
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onChanged() {}

    override fun onSessionExited(id: String, status: Int) {}

    override fun onAuthRequest(id: Int, text: String) {
        if (!inFront || !Linux.lent(this, "fingerprint")) {
            answerAuth(id, false)
            return
        }
        val manager = getSystemService(BiometricManager::class.java)
        if (manager?.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "authorization $id: no fingerprint or screen lock to confirm with")
            answerAuth(id, false)
            return
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.auth_title))
            .setSubtitle(text)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                answerAuth(id, true)
            }

            override fun onAuthenticationError(code: Int, message: CharSequence?) {
                answerAuth(id, false)
            }
        })
    }

    override fun onNfcRequest(id: Int, command: ByteArray) {
        val reader = nfc
        if (reader == null) {
            answerNfc(id, ByteArray(0), "no card")
        } else {
            reader.transceive(id, command)
        }
    }

    override fun onClipboard(text: String) {
        copyToClipboard(text, fromLinux = true)
    }

    private fun answerAuth(id: Int, allowed: Boolean) {
        background.execute {
            try {
                Linux.blocking("answerAuth") { it.answerAuth(id, allowed) }
            } catch (e: LinuxException) {
                Log.w(TAG, e.message ?: "answerAuth")
            }
        }
    }

    /** Sets Android's clipboard from Linux when it is lent; [fromLinux] false: the owner's own copy. */
    fun copyToClipboard(text: String, fromLinux: Boolean) {
        if (fromLinux && !Linux.lent(this, "clipboard")) {
            Log.i(TAG, "Linux set the clipboard while it is not lent; ignored")
            return
        }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    fun clipboardText(): String? =
        getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()

    fun openSettings() {
        try {
            startActivity(Intent(Linux.ACTION_SETTINGS))
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "no page of the Linux development environment in Settings")
        }
    }

    companion object {
        /** Calls to lujarch_linux that may block, one after the other. */
        val background = Executors.newSingleThreadExecutor()

        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        fun answerNfc(id: Int, response: ByteArray, error: String?) {
            background.execute {
                try {
                    Linux.blocking("answerNfc") { it.answerNfc(id, response, error) }
                } catch (e: LinuxException) {
                    Log.w(TAG, e.message ?: "answerNfc")
                }
            }
        }
    }
}
