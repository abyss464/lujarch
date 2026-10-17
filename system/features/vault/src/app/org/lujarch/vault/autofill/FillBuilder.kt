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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.drawable.Icon
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.util.Log
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import java.text.Collator
import java.util.concurrent.atomic.AtomicInteger
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.core.PasswordEntry
import org.lujarch.vault.core.match.EntryMatcher
import org.lujarch.vault.core.otp.Otp
import org.lujarch.vault.data.PasswordData
import org.lujarch.vault.data.Vault
import org.lujarch.vault.ui.VaultActivity

/**
 * Builds autofill responses: one dataset per matching password entry, plus a
 * "search" dataset that opens the entry picker; each with a dropdown
 * (RemoteViews) and, when the keyboard asks for them, an inline presentation.
 */
object FillBuilder {
    private val requestCodes = AtomicInteger(1)

    /** Entries for [form]: web pages by domain only, apps by package only. */
    fun matches(form: ParsedForm, data: PasswordData): List<PasswordEntry> {
        val entries = data.db.entries
        val found = form.webDomain?.let { EntryMatcher.matchWeb(entries, it, Vault.prefs.autofillSubdomains) }
            ?: EntryMatcher.matchApp(entries, form.packageName)
        val collator = Collator.getInstance()
        return found.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    fun response(
        context: Context,
        form: ParsedForm,
        data: PasswordData,
        inline: InlineSuggestionsRequest?,
        withPicker: Boolean,
    ): FillResponse? {
        val builder = FillResponse.Builder()
        var count = 0
        val max = inline?.maxSuggestionCount ?: Int.MAX_VALUE
        for (entry in matches(form, data)) {
            // Leave room for the pinned search suggestion in the keyboard strip.
            val inlineSpec = if (count < max - (if (withPicker) 1 else 0)) spec(inline, count) else null
            entryDataset(context, form, entry, inlineSpec)?.let {
                builder.addDataset(it)
                count++
            }
        }
        if (withPicker) {
            builder.addDataset(pickerDataset(context, form, spec(inline, count)))
            count++
        }
        return if (count == 0) null else builder.build()
    }

    /** A dataset filling [entry] into [form]; null when the entry has nothing for its fields. */
    fun entryDataset(context: Context, form: ParsedForm, entry: PasswordEntry, inlineSpec: InlinePresentationSpec?): Dataset? {
        val builder = Dataset.Builder(
            presentations(context, entry.title.ifEmpty { context.getString(R.string.entry_untitled) }, entry.username, inlineSpec, pinned = false),
        )
        builder.setId(entry.uuid)
        var filled = 0
        val username = entry.username
        if (username.isNotEmpty()) {
            form.username.forEach {
                builder.setField(it, Field.Builder().setValue(AutofillValue.forText(username)).build())
                filled++
            }
        }
        val password = entry.password
        if (password != null && !password.isEmpty) {
            form.password.forEach {
                builder.setField(it, Field.Builder().setValue(AutofillValue.forText(password.value())).build())
                filled++
            }
        }
        if (form.otp.isNotEmpty() && Otp.hasOtp(entry)) {
            val code = try {
                Otp.fromEntry(entry)?.let { p -> Otp.code(p, System.currentTimeMillis()).also { p.wipe() } }
            } catch (e: Exception) {
                Log.w(TAG, "OTP code of an entry cannot be computed: ${e.javaClass.simpleName}")
                null
            }
            if (code != null) {
                form.otp.forEach {
                    builder.setField(it, Field.Builder().setValue(AutofillValue.forText(code)).build())
                    filled++
                }
            }
        }
        return if (filled == 0) null else builder.build()
    }

    /** The "search" dataset: opens the picker, which returns the chosen entry's dataset. */
    private fun pickerDataset(context: Context, form: ParsedForm, inlineSpec: InlinePresentationSpec?): Dataset {
        val builder = Dataset.Builder(
            presentations(
                context, context.getString(R.string.autofill_search), context.getString(R.string.app_name),
                inlineSpec, pinned = true,
            ),
        )
        for (id in form.allIds) builder.setField(id, Field.Builder().build())
        builder.setAuthentication(authIntent(context, AutofillActivity.MODE_PICK))
        return builder.build()
    }

    /** Response while the vault is still loading: one item that waits for it in [AutofillActivity]. */
    fun unlockResponse(context: Context, form: ParsedForm, inline: InlineSuggestionsRequest?): FillResponse =
        FillResponse.Builder()
            .setAuthentication(
                form.allIds.toTypedArray(),
                authIntent(context, AutofillActivity.MODE_RESPONSE),
                presentations(
                    context, context.getString(R.string.autofill_unlock), context.getString(R.string.app_name),
                    spec(inline, 0), pinned = false,
                ),
            )
            .build()

    private fun authIntent(context: Context, mode: String): IntentSender {
        val intent = Intent(context, AutofillActivity::class.java).putExtra(AutofillActivity.EXTRA_MODE, mode)
        // Mutable: the system adds the assist structure and inline request to the intent.
        return PendingIntent.getActivity(
            context, requestCodes.getAndIncrement(), intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender
    }

    private fun spec(inline: InlineSuggestionsRequest?, index: Int): InlinePresentationSpec? {
        val specs = inline?.inlinePresentationSpecs ?: return null
        if (specs.isEmpty()) return null
        return specs[minOf(index, specs.size - 1)]
    }

    private fun presentations(
        context: Context,
        title: String,
        subtitle: String,
        inlineSpec: InlinePresentationSpec?,
        pinned: Boolean,
    ): Presentations {
        val menu = RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle)
        }
        val builder = Presentations.Builder().setMenuPresentation(menu)
        inlinePresentation(context, title, subtitle, inlineSpec, pinned)?.let { builder.setInlinePresentation(it) }
        return builder.build()
    }

    private fun inlinePresentation(
        context: Context,
        title: String,
        subtitle: String,
        spec: InlinePresentationSpec?,
        pinned: Boolean,
    ): InlinePresentation? {
        if (spec == null) return null
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        // Long-pressing a suggestion opens the vault (the attribution intent the keyboard requires).
        val attribution = PendingIntent.getActivity(
            context, 0, Intent(context, VaultActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val content = InlineSuggestionUi.newContentBuilder(attribution)
            .setContentDescription(title)
            .setStartIcon(Icon.createWithResource(context, R.drawable.ic_vault))
        content.setTitle(title)
        if (!pinned && subtitle.isNotEmpty()) content.setSubtitle(subtitle)
        return InlinePresentation(content.build().slice, spec, pinned)
    }
}
