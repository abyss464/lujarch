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

import android.content.Context
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The keys row above the keyboard (LNX-3): the keys a phone's keyboard lacks for a shell. CTRL
 * and ALT hold for the next key.
 */
class KeysRow(context: Context, private val onKey: (Key) -> Unit) : HorizontalScrollView(context) {
    enum class Key(val label: Int, val keyCode: Int = 0, val text: String? = null) {
        ESC(R.string.key_esc, text = "\u001b"),
        TAB(R.string.key_tab, text = "\t"),
        CTRL(R.string.key_ctrl),
        ALT(R.string.key_alt),
        LEFT(R.string.key_left, KeyEvent.KEYCODE_DPAD_LEFT),
        DOWN(R.string.key_down, KeyEvent.KEYCODE_DPAD_DOWN),
        UP(R.string.key_up, KeyEvent.KEYCODE_DPAD_UP),
        RIGHT(R.string.key_right, KeyEvent.KEYCODE_DPAD_RIGHT),
        HOME(R.string.key_home, KeyEvent.KEYCODE_MOVE_HOME),
        END(R.string.key_end, KeyEvent.KEYCODE_MOVE_END),
        PAGE_UP(R.string.key_page_up, KeyEvent.KEYCODE_PAGE_UP),
        PAGE_DOWN(R.string.key_page_down, KeyEvent.KEYCODE_PAGE_DOWN),
        DASH(R.string.key_dash, text = "-"),
        SLASH(R.string.key_slash, text = "/"),
        PIPE(R.string.key_pipe, text = "|"),
        TILDE(R.string.key_tilde, text = "~"),
    }

    private val ctrlView: TextView
    private val altView: TextView

    init {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val views = Key.values().associateWith { key ->
            TextView(context).apply {
                setText(key.label)
                minWidth = dp(44)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                isClickable = true
                setOnClickListener { onKey(key) }
                row.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        ctrlView = views.getValue(Key.CTRL)
        altView = views.getValue(Key.ALT)
        addView(row)
    }

    fun setSticky(ctrl: Boolean, alt: Boolean) {
        ctrlView.isActivated = ctrl
        altView.isActivated = alt
        ctrlView.alpha = if (ctrl) 1f else 0.7f
        altView.alpha = if (alt) 1f else 0.7f
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
