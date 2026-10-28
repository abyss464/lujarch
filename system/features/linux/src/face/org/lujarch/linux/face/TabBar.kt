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

package org.lujarch.linux.face

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * The terminal's bar: the tabs, + right after them, Display and Settings at the end. The tabs
 * scroll within the room the buttons leave, so that + stays on screen however many there are.
 */
class TabBar(
    context: Context,
    private val tabs: View,
    private val add: View,
    private vararg val end: View,
) : ViewGroup(context) {
    init {
        addView(tabs)
        addView(add)
        for (v in end) addView(v)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val inner = MeasureSpec.makeMeasureSpec(height - paddingTop - paddingBottom, MeasureSpec.AT_MOST)
        var buttons = 0
        for (v in listOf(add, *end)) {
            // The buttons are of a fixed size.
            val size = v.layoutParams
            v.measure(
                MeasureSpec.makeMeasureSpec(size.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(size.height, MeasureSpec.EXACTLY),
            )
            buttons += v.measuredWidth
        }
        val room = (width - paddingLeft - paddingRight - buttons).coerceAtLeast(0)
        tabs.measure(MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), inner)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val middle = paddingTop + (b - t - paddingTop - paddingBottom) / 2
        fun place(v: View, x: Int) {
            val y = middle - v.measuredHeight / 2
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
        place(tabs, paddingLeft)
        place(add, paddingLeft + tabs.measuredWidth)
        var x = r - l - paddingRight
        for (v in end.reversed()) {
            x -= v.measuredWidth
            place(v, x)
        }
    }
}
