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

package org.lujarch.recorder.settings

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.lujarch.recorder.settings.R as SettingsR

/** Children in rows, each row as wide as the layout, wrapping to the next. */
class FlowLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ViewGroup(context, attrs) {
    private val gap = (8 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var row = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > width) {
                x = 0
                y += row + gap
                row = 0
            }
            x += child.measuredWidth + gap
            row = maxOf(row, child.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            y + row + paddingTop + paddingBottom + gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var x = 0
        var y = paddingTop
        var row = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > width) {
                x = 0
                y += row + gap
                row = 0
            }
            val left = if (rtl) r - l - paddingRight - x - child.measuredWidth else paddingLeft + x
            child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
            row = maxOf(row, child.measuredHeight)
        }
    }
}

/** The spring of a fast spatial motion: 350 ms, settling after a small overshoot. */
private object FastSpatial : TimeInterpolator {
    private const val DAMPING = 0.6f
    private const val OMEGA = 18f
    private val damped = OMEGA * sqrt(1 - DAMPING * DAMPING)

    override fun getInterpolation(t: Float): Float {
        val s = t * 0.35f
        val decay = exp(-DAMPING * OMEGA * s)
        return 1 - decay * (cos(damped * s) + DAMPING * OMEGA / damped * sin(damped * s))
    }
}

/**
 * A profile to choose: square-cornered and outlined; rounded and filled with the mark when in
 * use; rounded with a dashed outline when a setting has changed since it was chosen.
 */
class ProfileChip(context: Context) : TextView(context) {
    enum class Look { PLAIN, ON, CHANGED }

    private val dp = resources.displayMetrics.density
    private val shape = GradientDrawable()
    private val check = context.getDrawable(SettingsR.drawable.rec_check)!!.mutate()
    private val line = context.getColor(SettingsR.color.rec_chip_line)
    private val ink = context.getColor(SettingsR.color.rec_on_surface_variant)
    private val on = context.getColor(SettingsR.color.rec_chip_on)
    private val onInk = context.getColor(SettingsR.color.rec_on_chip_on)
    private val changedLine = context.getColor(SettingsR.color.rec_chip_changed)
    private var look: Look? = null
    private var from = 0f
    private var to = 0f
    private var animator: ValueAnimator? = null

    init {
        background = shape
        gravity = Gravity.CENTER_VERTICAL
        minHeight = (36 * dp).toInt()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSingleLine = true
    }

    fun show(label: String, look: Look) {
        text = label
        isSelected = look == Look.ON
        val first = this.look == null
        val was = this.look
        this.look = look
        val target = if (look == Look.PLAIN) 0f else 1f
        animator?.cancel()
        if (first || !isAttachedToWindow) {
            from = target
            to = target
            draw(target, look)
            return
        }
        // From PLAIN the shape moves; between ON and CHANGED only the fill and the outline.
        from = if (was == Look.PLAIN) 0f else 1f
        to = target
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = FastSpatial
            addUpdateListener { draw(from + (to - from) * it.animatedValue as Float, look) }
            start()
        }
    }

    /** [p] is 0 for a plain chip and 1 for a marked one; it overshoots with the spring. */
    private fun draw(p: Float, look: Look) {
        val c = p.coerceIn(0f, 1f)
        shape.cornerRadius = (10 + 8 * p) * dp
        val fill = if (look == Look.CHANGED) 0 else ColorUtils.setAlphaComponent(on, (255 * c).toInt())
        shape.setColor(fill)
        val stroke =
            when (look) {
                Look.CHANGED -> ColorUtils.blendARGB(line, changedLine, c)
                else -> ColorUtils.blendARGB(line, on, c)
            }
        if (look == Look.CHANGED) shape.setStroke(dp.toInt(), stroke, 4 * dp, 3 * dp)
        else shape.setStroke(dp.toInt(), stroke)
        val text = if (look == Look.CHANGED) ink else ColorUtils.blendARGB(ink, onInk, c)
        setTextColor(text)
        check.setTint(text)
        val w = (18 * dp * p).toInt().coerceAtLeast(0)
        check.setBounds(0, 0, w, (18 * dp).toInt())
        check.alpha = (255 * c).toInt()
        setCompoundDrawablesRelative(if (w > 0) check else null, null, null, null)
        compoundDrawablePadding = (6 * dp * c).toInt()
        setPaddingRelative(((14 - 4 * c) * dp).toInt(), 0, (14 * dp).toInt(), 0)
    }
}
