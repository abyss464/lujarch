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

import android.content.Context
import android.graphics.Typeface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.core.otp.Otp
import org.lujarch.vault.core.otp.OtpParams
import org.lujarch.vault.core.otp.OtpType
import org.lujarch.vault.data.Vault

/**
 * One 2FA code: the code as title (large, monospace), issuer and account as
 * summary, a countdown ring with the seconds left as widget. [tick] recomputes
 * code and countdown; tapping copies the code.
 */
class CodePreference(context: Context, val params: OtpParams?, private val label: String) : Preference(context) {
    private var remainingMs = 0L
    private var code: String? = null

    init {
        widgetLayoutResource = R.layout.widget_countdown
        isPersistent = false
        isIconSpaceReserved = false
    }

    /** A long press (the account's actions); null for codes that cannot be changed here. */
    var onLongClick: ((CodePreference) -> Unit)? = null

    /** The current code without grouping spaces, null when it cannot be computed. */
    val currentCode: String?
        get() = code

    fun tick(now: Long) {
        val p = params
        if (p == null) {
            title = context.getString(R.string.code_unsupported)
            summary = label
            return
        }
        val newCode = try {
            Otp.code(p, now)
        } catch (e: Exception) {
            Log.w(TAG, "code of an entry cannot be computed: ${e.javaClass.simpleName}")
            null
        }
        remainingMs = if (p.type == OtpType.HOTP) 0 else Otp.millisLeft(p, now)
        if (newCode != code || title == null) {
            code = newCode
            title = newCode?.let { styled(Format.code(it)) } ?: context.getString(R.string.code_unsupported)
        }
        summary = if (p.type == OtpType.HOTP) context.getString(R.string.code_hotp_summary, label, p.counter) else label
        notifyChanged()
    }

    private fun styled(text: String): CharSequence = SpannableString(text).apply {
        setSpan(TypefaceSpan("monospace"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(1.5f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val longClick = onLongClick
        if (longClick != null) {
            holder.itemView.setOnLongClickListener {
                longClick(this)
                true
            }
        } else {
            // Views are recycled between rows: a password entry's row must not keep an account row's action.
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
        }
        val ring = holder.findViewById(R.id.countdown_ring) as? ProgressBar
        val seconds = holder.findViewById(R.id.countdown_seconds) as? TextView
        val p = params
        val show = p != null && p.type != OtpType.HOTP && code != null
        ring?.visibility = if (show) View.VISIBLE else View.GONE
        seconds?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            ring?.max = p!!.period * 1000
            ring?.progress = remainingMs.toInt()
            seconds?.text = ((remainingMs + 999) / 1000).toString()
        }
    }
}

/**
 * A field of an entry: tap copies it; protected fields are masked and have an
 * eye button that reveals them (after a fingerprint or screen lock check
 * when that setting is on).
 */
class FieldPreference(
    context: Context,
    private val masked: Boolean,
    private val onReveal: ((FieldPreference) -> Unit)?,
) : Preference(context) {
    var revealed = false
        private set
    /** Read only when shown, so a masked value is not turned into a String before it is revealed. */
    private var field: org.lujarch.vault.core.PasswordField? = null

    init {
        isPersistent = false
        isIconSpaceReserved = false
        if (masked) widgetLayoutResource = R.layout.widget_reveal
    }

    fun bindField(f: org.lujarch.vault.core.PasswordField?) {
        field = f
        render()
    }

    fun setRevealed(on: Boolean) {
        revealed = on
        render()
    }

    private fun render() {
        val f = field
        summary = when {
            f == null || f.isEmpty -> context.getString(R.string.field_empty)
            masked && !revealed -> MASK
            else -> f.value()
        }
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.summary) as? TextView)?.apply {
            maxLines = if (masked) 2 else 12
            setTextIsSelectable(false)
        }
        val eye = holder.findViewById(R.id.reveal_button) as? ImageView ?: return
        eye.setImageResource(if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        eye.contentDescription = context.getString(if (revealed) R.string.action_hide else R.string.action_reveal)
        eye.setOnClickListener { onReveal?.invoke(this) }
    }

    companion object {
        const val MASK = "••••••••"
    }
}

/**
 * Fingerprint or screen lock before revealing a password (setting, on by
 * default) or before copying one (setting, off by default).
 */
object RevealAuth {
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun run(fragment: Fragment, onSuccess: () -> Unit) =
        run(fragment, Vault.prefs.revealNeedsAuth, R.string.reveal_prompt_title, onSuccess)

    fun runForCopy(fragment: Fragment, onSuccess: () -> Unit) =
        run(fragment, Vault.prefs.copyNeedsAuth, R.string.copy_prompt_title, onSuccess)

    private fun run(fragment: Fragment, needed: Boolean, titleRes: Int, onSuccess: () -> Unit) {
        if (!needed) {
            onSuccess()
            return
        }
        val ctx = fragment.requireContext()
        val status = ctx.getSystemService(BiometricManager::class.java).canAuthenticate(AUTHENTICATORS)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "reveal: no fingerprint or screen lock available (status $status)")
            Toast.makeText(ctx, R.string.reveal_no_lock, Toast.LENGTH_LONG).show()
            return
        }
        val prompt = BiometricPrompt.Builder(ctx)
            .setTitle(ctx.getString(titleRes))
            .setSubtitle(ctx.getString(R.string.reveal_prompt_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(CancellationSignal(), ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (fragment.isAdded) onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) return
                Log.w(TAG, "reveal: authentication error $errorCode")
                if (fragment.isAdded) Toast.makeText(ctx, errString, Toast.LENGTH_SHORT).show()
            }
        })
    }
}
