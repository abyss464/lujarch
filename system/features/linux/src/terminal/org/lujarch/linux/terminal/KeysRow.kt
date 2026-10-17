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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The keys row on the keyboard's top edge (LNX-3): Esc, Tab, Ctrl, Alt and the four arrows. A key
 * acts as it is touched; Ctrl and Alt hold for the next key and show in the accent color while
 * they hold; the arrows repeat while held. The keys never take focus, so touching one leaves the
 * keyboard on the terminal.
 */
class KeysRow(context: Context, private val model: InputModel) : LinearLayout(context) {
    private val ctrl: TextView
    private val alt: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val normal = context.getColor(R.color.on_surface)
    private val latched = context.getColor(R.color.primary)

    init {
        orientation = HORIZONTAL
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.strip_surface))
            val r = dp(20).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        setPadding(dp(6), 0, dp(6), 0)
        isFocusable = false
        isFocusableInTouchMode = false
        addKey(R.string.key_esc) { model.key(Key.ESC) }
        addKey(R.string.key_tab) { model.key(Key.TAB) }
        ctrl = addKey(R.string.key_ctrl) { model.toggleCtrl() }
        alt = addKey(R.string.key_alt) { model.toggleAlt() }
        addKey(R.string.key_left, repeat = true) { model.key(Key.LEFT) }
        addKey(R.string.key_down, repeat = true) { model.key(Key.DOWN) }
        addKey(R.string.key_up, repeat = true) { model.key(Key.UP) }
        addKey(R.string.key_right, repeat = true) { model.key(Key.RIGHT) }
    }

    /** The latches changed. */
    fun refresh() {
        ctrl.setTextColor(if (model.ctrlLatched) latched else normal)
        alt.setTextColor(if (model.altLatched) latched else normal)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addKey(label: Int, repeat: Boolean = false, action: () -> Unit): TextView {
        val v = TextView(context).apply {
            setText(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(normal)
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription = text
        }
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        v.background = ta.getDrawable(0)
        ta.recycle()
        val repeater = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, REPEAT_MS)
            }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    action()
                    if (repeat) handler.postDelayed(repeater, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    handler.removeCallbacks(repeater)
                }
            }
            true
        }
        addView(v, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return v
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 60L
    }
}
