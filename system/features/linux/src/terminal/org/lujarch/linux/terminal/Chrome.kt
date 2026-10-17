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

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.lujarch.linux.ILinux

/**
 * The terminal's chrome (LNX-3): the session tabs, a session's sheet and the new-session menu.
 * Each kind of session shows its prompt: Arch's user and root prompts in brackets, Android's bare `#`.
 */
object Chrome {
    fun glyphOf(kind: String): String =
        when (kind) {
            ILinux.KIND_ARCH_ROOT -> "[#]"
            ILinux.KIND_ANDROID_ROOT -> "#"
            else -> "[$]"
        }

    private fun glyphSp(kind: String, small: Float, large: Float) =
        if (kind == ILinux.KIND_ANDROID_ROOT) large else small

    /** A tab's name: the one the owner gave, else its kind's. */
    fun labelOf(context: Context, kind: String, name: String): String =
        name.ifEmpty {
            context.getString(
                when (kind) {
                    ILinux.KIND_ARCH_ROOT -> R.string.tab_arch_root
                    ILinux.KIND_ANDROID_ROOT -> R.string.tab_android_root
                    else -> R.string.tab_arch
                }
            )
        }

    /** A session's tab: its prompt, its name, and a sun while it holds the phone awake. */
    fun tabView(
        context: Context,
        tab: TerminalTab,
        selected: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ): View {
        val c = context
        val fg = c.getColor(if (selected) R.color.on_primary_container else R.color.on_surface_variant)
        val row = row(c)
        row.background = if (selected) round(c.getColor(R.color.primary_container), 20f, c) else null
        row.minimumHeight = dp(c, 40)
        row.setPadding(dp(c, 14), 0, dp(c, 14), 0)
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 40))
            .also { it.marginEnd = dp(c, 4) }
        row.alpha = if (tab.ended) 0.5f else 1f
        row.addView(text(c, glyphOf(tab.kind), glyphSp(tab.kind, 12f, 15f), c.getColor(R.color.primary), medium = true, mono = true))
        val label = text(c, labelOf(c, tab.kind, tab.name), 14f, fg, medium = true)
        label.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .also { it.marginStart = dp(c, 8) }
        label.maxLines = 1
        label.maxWidth = dp(c, 160)
        label.ellipsize = TextUtils.TruncateAt.END
        row.addView(label)
        if (tab.awake) {
            val sun = ImageView(c)
            sun.setImageResource(R.drawable.ic_sun)
            sun.imageTintList = ColorStateList.valueOf(c.getColor(R.color.primary))
            sun.contentDescription = c.getString(R.string.awake_description)
            sun.layoutParams = LinearLayout.LayoutParams(dp(c, 14), dp(c, 14)).also { it.marginStart = dp(c, 8) }
            row.addView(sun)
        }
        row.contentDescription = buildString {
            append(labelOf(c, tab.kind, tab.name))
            if (tab.busy) append(", ").append(c.getString(R.string.tab_busy, tab.job ?: ""))
            if (tab.awake) append(", ").append(c.getString(R.string.keep_awake))
            if (tab.ended) append(", ").append(c.getString(R.string.session_ended))
        }
        row.isClickable = true
        row.isLongClickable = true
        row.isFocusable = false
        row.setOnClickListener { onClick() }
        row.setOnLongClickListener { onLongClick(); true }
        return row
    }

    /**
     * A session's sheet, opened by a long press on its tab: when the phone sleeps (sleep with the
     * phone, keep awake), rename, end session.
     */
    fun showSessionSheet(
        context: Context,
        tab: TerminalTab,
        onAwake: (Boolean) -> Unit,
        onRename: (String) -> Unit,
        onEnd: () -> Unit,
    ) {
        val c = context
        val dialog = Dialog(c, R.style.Theme_LujarchTerminal_Sheet)
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.background = roundTop(c.getColor(R.color.menu_surface), 28f, c)
        root.setPadding(0, dp(c, 14), 0, dp(c, 36))

        val handle = View(c)
        handle.background = round(c.getColor(R.color.outline), 2f, c)
        root.addView(handle, LinearLayout.LayoutParams(dp(c, 32), dp(c, 4)).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
            it.bottomMargin = dp(c, 18)
        })

        val header = row(c)
        header.setPadding(dp(c, 24), 0, dp(c, 24), dp(c, 16))
        header.addView(circle(c, tab.kind, 36, 18f, 11f, 16f), LinearLayout.LayoutParams(dp(c, 36), dp(c, 36)))
        val titles = LinearLayout(c)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(text(c, labelOf(c, tab.kind, tab.name), 18f, c.getColor(R.color.on_surface)))
        val state = text(
            c,
            when {
                tab.ended -> c.getString(R.string.session_ended)
                tab.busy -> tab.job?.takeIf { it.isNotBlank() } ?: c.getString(R.string.sheet_busy)
                else -> c.getString(R.string.sheet_idle)
            },
            13f, c.getColor(R.color.on_surface_variant), mono = true,
        )
        state.setPadding(0, dp(c, 2), 0, 0)
        titles.addView(state)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.marginStart = dp(c, 14) })
        root.addView(header)
        root.addView(divider(c))

        if (!tab.ended) {
            val section = text(c, c.getString(R.string.sheet_sleep_title), 14f, c.getColor(R.color.primary), medium = true)
            section.setPadding(dp(c, 24), dp(c, 12), dp(c, 24), dp(c, 4))
            root.addView(section)
            for ((awake, title) in listOf(false to R.string.sheet_sleep_option, true to R.string.keep_awake)) {
                val row = row(c)
                row.minimumHeight = dp(c, 56)
                row.setPadding(dp(c, 24), dp(c, 8), dp(c, 24), dp(c, 8))
                row.addView(radio(c, tab.awake == awake), LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)))
                row.addView(text(c, c.getString(title), 16f, c.getColor(R.color.on_surface)),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(c, 16) })
                row.setOnClickListener {
                    dialog.dismiss()
                    if (tab.awake != awake) onAwake(awake)
                }
                root.addView(row)
            }
            root.addView(divider(c).also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(c, 6) })
            root.addView(action(c, R.drawable.ic_edit, R.string.rename) {
                dialog.dismiss()
                rename(c, tab.name, onRename)
            })
        }
        root.addView(action(c, R.drawable.ic_close, R.string.end_session) {
            dialog.dismiss()
            onEnd()
        })

        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.5f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // The sheet stays clear of the bars Linux hides.
            w.insetsController?.hide(WindowInsets.Type.systemBars())
        }
        dialog.show()
    }

    /** The new-session menu: the three kinds, each with its prompt and what it is. */
    fun showNewSessionMenu(context: Context, anchor: View, user: String?, linuxRuns: Boolean, rootOk: Boolean, onPick: (String) -> Unit) {
        val c = context
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.background = round(c.getColor(R.color.menu_surface), 16f, c)
        root.setPadding(0, dp(c, 8), 0, dp(c, 8))
        root.elevation = dp(c, 8).toFloat()
        val popup = PopupWindow(root, dp(c, 224), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        data class Item(val kind: String, val label: Int, val summary: String, val enabled: Boolean)
        val items = listOf(
            Item(ILinux.KIND_ARCH, R.string.kind_arch, user ?: "user", linuxRuns),
            Item(ILinux.KIND_ARCH_ROOT, R.string.kind_arch_root, "root", linuxRuns && rootOk),
            Item(ILinux.KIND_ANDROID_ROOT, R.string.kind_android_root, c.getString(R.string.kind_android_root_summary), linuxRuns && rootOk),
        )
        for (item in items) {
            val summary = when {
                // Every session, Android's root shell too, lives while Linux runs.
                !linuxRuns -> c.getString(R.string.kind_linux_off)
                !rootOk && item.kind != ILinux.KIND_ARCH -> c.getString(R.string.kind_root_disabled)
                else -> item.summary
            }
            val row = row(c)
            row.minimumHeight = dp(c, 56)
            row.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8))
            row.addView(circle(c, item.kind, 32, 16f, 10f, 15f), LinearLayout.LayoutParams(dp(c, 32), dp(c, 32)))
            val col = LinearLayout(c)
            col.orientation = LinearLayout.VERTICAL
            col.addView(text(c, c.getString(item.label), 15f, c.getColor(R.color.on_surface)))
            col.addView(text(c, summary, 12.5f, c.getColor(R.color.on_surface_variant)).also { it.setPadding(0, dp(c, 2), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(c, 14) })
            row.alpha = if (item.enabled) 1f else 0.5f
            row.isEnabled = item.enabled
            if (item.enabled) {
                val ta = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                row.foreground = ta.getDrawable(0)
                ta.recycle()
                row.setOnClickListener {
                    popup.dismiss()
                    onPick(item.kind)
                }
            }
            root.addView(row)
        }
        popup.elevation = dp(c, 8).toFloat()
        popup.setBackgroundDrawable(null)
        popup.showAsDropDown(anchor, 0, dp(c, 4))
    }

    private fun rename(c: Context, current: String, onRename: (String) -> Unit) {
        val input = EditText(c)
        input.setText(current)
        input.hint = c.getString(R.string.rename_hint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.setSingleLine()
        input.setSelectAllOnFocus(true)
        val frame = FrameLayout(c)
        frame.setPadding(dp(c, 24), dp(c, 8), dp(c, 24), 0)
        frame.addView(input)
        AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.rename_title)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ -> onRename(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun circle(c: Context, kind: String, sizeDp: Int, radiusDp: Float, small: Float, large: Float): TextView {
        val t = text(c, glyphOf(kind), glyphSp(kind, small, large), c.getColor(R.color.primary), medium = true, mono = true)
        t.gravity = Gravity.CENTER
        t.background = round(c.getColor(R.color.circle_surface), radiusDp, c)
        t.minWidth = dp(c, sizeDp)
        return t
    }

    private fun radio(c: Context, on: Boolean): FrameLayout {
        val radio = FrameLayout(c)
        radio.background = GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            it.setStroke(dp(c, 2), c.getColor(if (on) R.color.primary else R.color.on_surface_variant))
        }
        if (on) {
            val dot = View(c)
            dot.background = GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                it.setColor(c.getColor(R.color.primary))
            }
            radio.addView(dot, FrameLayout.LayoutParams(dp(c, 10), dp(c, 10), Gravity.CENTER))
        }
        return radio
    }

    private fun divider(c: Context): View {
        val v = View(c)
        v.setBackgroundColor(c.getColor(R.color.outline))
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)).also {
            it.marginStart = dp(c, 24)
            it.marginEnd = dp(c, 24)
        }
        return v
    }

    private fun action(c: Context, icon: Int, label: Int, onClick: () -> Unit): View {
        val row = row(c)
        row.minimumHeight = dp(c, 56)
        row.setPadding(dp(c, 24), 0, dp(c, 24), 0)
        val img = ImageView(c)
        img.setImageResource(icon)
        img.imageTintList = ColorStateList.valueOf(c.getColor(R.color.on_surface_variant))
        row.addView(img, LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)))
        row.addView(text(c, c.getString(label), 16f, c.getColor(R.color.on_surface)).also { it.setPadding(dp(c, 16), 0, 0, 0) })
        row.setOnClickListener { onClick() }
        return row
    }

    fun dp(c: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    fun round(color: Int, radiusDp: Float, c: Context): GradientDrawable =
        GradientDrawable().also {
            it.setColor(color)
            it.cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, c.resources.displayMetrics)
        }

    private fun roundTop(color: Int, radiusDp: Float, c: Context): GradientDrawable {
        val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, c.resources.displayMetrics)
        return GradientDrawable().also {
            it.setColor(color)
            it.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }

    private fun text(c: Context, text: CharSequence, sp: Float, color: Int, medium: Boolean = false, mono: Boolean = false): TextView =
        TextView(c).also {
            it.text = text
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            it.setTextColor(color)
            it.includeFontPadding = false
            it.typeface = when {
                mono && medium -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                mono -> Typeface.MONOSPACE
                medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.DEFAULT
            }
        }

    private fun row(c: Context): LinearLayout =
        LinearLayout(c).also {
            it.orientation = LinearLayout.HORIZONTAL
            it.gravity = Gravity.CENTER_VERTICAL
        }
}
