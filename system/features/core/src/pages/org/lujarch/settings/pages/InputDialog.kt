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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog

/** A text field in a dialog, validated as the owner types. */
object InputDialog {
    /**
     * A dialog with one text field, for a page dialog (built, not shown).
     * [validate] returns an error to show under the field, or null when the
     * text is acceptable; the positive button is enabled only then. The
     * field's text comes back when the page is recreated, except a
     * [password]: a masked field, out of autofill, keyboard learning and the
     * saved state (it comes back empty), passed on untrimmed.
     */
    fun create(
        context: Context,
        @StringRes title: Int,
        message: CharSequence? = null,
        initial: String = "",
        hint: CharSequence? = null,
        @StringRes positive: Int = android.R.string.ok,
        singleLine: Boolean = true,
        numeric: Boolean = false,
        password: Boolean = false,
        @StringRes neutral: Int? = null,
        onNeutral: (() -> Unit)? = null,
        validate: (String) -> String? = { null },
        onDone: (String) -> Unit,
    ): AlertDialog {
        val pad = dp(context, 24)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(context, 8), pad, 0)
        }
        if (message != null) {
            column.addView(TextView(context).apply {
                text = message
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                setPadding(0, 0, 0, dp(context, 8))
            })
        }
        val field = EditText(context).apply {
            // An id: the window's saved state keeps the typed text across a recreation.
            id = android.R.id.edit
            setText(initial)
            setSelection(initial.length)
            this.hint = hint
            if (password) {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            } else if (numeric) {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_NUMBER
            } else if (singleLine) {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        column.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val error = TextView(context).apply {
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            setTextColor(context.getColor(android.R.color.holo_red_dark))
            visibility = TextView.GONE
        }
        column.addView(error)
        val frame = FrameLayout(context).apply { addView(column) }
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(positive) { _, _ ->
                val text = field.text.toString()
                onDone(if (password) text else text.trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (neutral != null && onNeutral != null) builder.setNeutralButton(neutral) { _, _ -> onNeutral() }
        val dialog = builder.create()
        fun check() {
            val problem = validate(if (password) field.text.toString() else field.text.toString().trim())
            error.text = problem ?: ""
            error.visibility = if (problem.isNullOrEmpty()) TextView.GONE else TextView.VISIBLE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = problem == null
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = check()
        })
        dialog.setOnShowListener { check() }
        field.requestFocus()
        return dialog
    }

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
}
