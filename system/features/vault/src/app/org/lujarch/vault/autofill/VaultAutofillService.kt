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

package org.lujarch.vault.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.vault.TAG
import org.lujarch.vault.core.match.EntryMatcher
import org.lujarch.vault.data.KeysForgottenException
import org.lujarch.vault.data.KeystoreLockedException
import org.lujarch.vault.data.Vault

/**
 * Autofill for apps and browsers from the KeePass database.
 * Each request opens the database for itself and wipes it once the response
 * is built; while the phone is locked the open fails at the Keystore and
 * nothing is offered. Offers nothing when KeePass is not set up or turned
 * off, for apps and sites excluded in Settings, never fills our own
 * packages, and asks for no saves (phase 1). An open that takes
 * longer than 2.5 s (the KDF after a save on the PC) is answered with one
 * item that opens [AutofillActivity], since the framework drops answers
 * that take longer than a few seconds; the open goes on and caches the new
 * transformed key, so the activity's own open is quick.
 */
class VaultAutofillService : AutofillService() {
    private val scope = MainScope()

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        val form = FormParser.parse(structure)
        if (isOwnPackage(form.packageName) || form.isEmpty || !Vault.keePassActive() || isExcluded(form)) {
            callback.onSuccess(null)
            return
        }
        val inline = request.inlineSuggestionsRequest
        val wanted = AtomicBoolean(true)
        // Not a child of the request's job: it always runs to the end and wipes what it opened.
        val built = scope.async {
            try {
                Built(
                    Vault.withKeePass { data ->
                        // Built only while someone waits for it; the data is wiped right after.
                        if (wanted.get()) {
                            FillBuilder.response(this@VaultAutofillService, form, data, inline, withPicker = Vault.prefs.autofillSearchItem)
                        } else {
                            null
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: KeystoreLockedException) {
                // The phone is locked: the Keystore refused, nothing to offer.
                Built(null)
            } catch (e: KeysForgottenException) {
                // The forget policy deleted the keys: nothing to offer until set up again.
                Built(null)
            } catch (e: Exception) {
                Log.e(TAG, "autofill request failed: ${e.javaClass.simpleName}: ${e.message}")
                Built(null)
            }
        }
        val job = scope.launch {
            val answer = withTimeoutOrNull(WAIT_MS) { built.await() }
            if (answer == null) {
                // Still opening: answer with the item that waits for it.
                wanted.set(false)
                callback.onSuccess(FillBuilder.unlockResponse(this@VaultAutofillService, form, inline))
            } else {
                callback.onSuccess(answer.response)
            }
            built.join()
            Log.i(TAG, "autofill request done; decrypted objects left: ${Vault.liveCount}")
        }
        cancellationSignal.setOnCancelListener {
            wanted.set(false)
            job.cancel()
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // No SaveInfo is ever sent, so this is not called in phase 1.
        callback.onFailure(null)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private class Built(val response: FillResponse?)

    companion object {
        /** A warm open takes well under a second; short of the framework's timeout. */
        private const val WAIT_MS = 2_500L

        /** Our own apps are never filled: the vault's UI and Lujarch Settings. */
        fun isOwnPackage(packageName: String): Boolean = packageName.startsWith("org.lujarch.")

        /**
         * Apps and sites the user excluded in Settings; checked before the
         * database is opened. Apps go by package name, a site by the scope it
         * was given (this host or the whole site) or by the scope chosen for
         * the list.
         */
        fun isExcluded(form: ParsedForm): Boolean {
            val prefs = Vault.prefs
            if (form.packageName in prefs.autofillExcludedPackages) return true
            val domain = form.webDomain ?: return false
            return EntryMatcher.isExcluded(domain, prefs.autofillExcludedSites, prefs.autofillExclusionScope)
        }
    }
}
