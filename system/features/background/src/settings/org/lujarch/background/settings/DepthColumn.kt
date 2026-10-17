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

package org.lujarch.background.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.internal.app.LujarchBackground

/**
 * An app's three choices drawn on the column of depths where each acts (BG-3): Background usage
 * brackets the depths it may stay at, Keep is a line it does not sink below, Autostart a gate
 * under Reclaimed; a dot marks where it is now.
 */
class DepthColumnPreference(context: Context) : Preference(context) {
    var depth = LujarchBackground.DEPTH_RUNNING
    var usage = LujarchBackground.USAGE_OPTIMIZED
    var usageEnabled = true
    var kept = false
    var keepEnabled = true
    var autostart: CharSequence = ""
    var autostartEnabled = true
    var onUsage: () -> Unit = {}
    var onKeep: (Boolean) -> Unit = {}
    var onAutostart: () -> Unit = {}

    /** Shows the values set. */
    fun update() = notifyChanged()

    init {
        layoutResource = R.layout.background_column
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val column = holder.findViewById(R.id.background_column) as DepthColumnView
        column.bind(this)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
    }
}

class DepthColumnView(context: Context, attrs: android.util.AttributeSet? = null) : ViewGroup(context, attrs) {
    private val rungs = DEPTHS.map { rung(it) }
    private val usageCard = card()
    private val keepCard = card(switch = true)
    private val autostartCard = card()
    private var pref: DepthColumnPreference? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(context, 2f).toFloat()
    }
    private val dash = Paint(line).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(context, 5f).toFloat(), dp(context, 4f).toFloat()), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        rungs.forEach { addView(it) }
        addView(usageCard)
        addView(keepCard)
        addView(autostartCard)
    }

    fun bind(p: DepthColumnPreference) {
        pref = p
        fill(usageCard, R.string.background_usage, LujarchBackground.usageName(context, p.usage),
            context.getString(R.string.background_usage_note), p.usageEnabled)
        usageCard.setOnClickListener { p.onUsage() }
        fill(keepCard, R.string.background_keep,
            context.getString(if (p.kept) R.string.background_keep_on else R.string.background_keep_off),
            context.getString(R.string.background_keep_note), p.keepEnabled)
        val sw = keepCard.findViewWithTag<Switch>(SWITCH)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = p.kept
        sw.isEnabled = p.keepEnabled
        sw.setOnCheckedChangeListener { _, on -> p.onKeep(on) }
        keepCard.setOnClickListener { if (p.keepEnabled) sw.toggle() }
        fill(autostartCard, R.string.background_autostart, p.autostart,
            context.getString(R.string.background_autostart_note), p.autostartEnabled)
        autostartCard.setOnClickListener { p.onAutostart() }
        DEPTHS.forEachIndexed { i, d -> paintRung(rungs[i], d, d == current()) }
        requestLayout()
        invalidate()
    }

    private fun current(): Int = pref?.depth?.let {
        if (it == LujarchBackground.DEPTH_ON_SCREEN) LujarchBackground.DEPTH_RUNNING else it
    } ?: LujarchBackground.DEPTH_RUNNING

    private fun rung(depth: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val h = dp(context, 12f)
        setPadding(h, 0, h, 0)
        addView(TextView(context).apply {
            text = LujarchBackground.depthName(context, depth)
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            paint.isFakeBoldText = true
        })
        addView(TextView(context).apply {
            text = context.getString(RUNG_NOTES[DEPTHS.indexOf(depth)])
            textSize = 11f
            alpha = 0.85f
        })
    }

    private fun paintRung(v: LinearLayout, depth: Int, here: Boolean) {
        v.background = GradientDrawable().apply {
            cornerRadius = dp(context, 16f).toFloat()
            setColor(LujarchBackground.depthColor(context, depth, false))
            if (depth == LujarchBackground.DEPTH_STOPPED) {
                setStroke(dp(context, 1.5f), LujarchBackground.depthOutline(context, depth),
                    dp(context, 4f).toFloat(), dp(context, 3f).toFloat())
            }
        }
        val on = LujarchBackground.onDepthColor(context, depth)
        for (i in 0 until v.childCount) (v.getChildAt(i) as TextView).setTextColor(on)
        v.contentDescription = if (here) context.getString(R.string.background_column_here,
            LujarchBackground.depthName(context, depth)) else null
    }

    private fun card(switch: Boolean = false): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.background_card)
        val p = dp(context, 12f)
        setPadding(p, dp(context, 8f), p, dp(context, 8f))
        isClickable = true
        isFocusable = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = TEXTS
            addView(TextView(context).apply {
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                alpha = 0.75f
            })
            addView(TextView(context).apply {
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
            })
            addView(TextView(context).apply {
                textSize = 11.5f
                alpha = 0.75f
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (switch) {
            addView(Switch(context).apply {
                tag = SWITCH
                contentDescription = context.getString(R.string.background_keep)
            })
        }
    }

    private fun fill(card: LinearLayout, title: Int, value: CharSequence, note: CharSequence, enabled: Boolean) {
        val texts = card.findViewWithTag<LinearLayout>(TEXTS)
        (texts.getChildAt(0) as TextView).setText(title)
        (texts.getChildAt(1) as TextView).text = if (card == keepCard) value
        else context.getString(R.string.background_card_value, value)
        (texts.getChildAt(2) as TextView).text = note
        card.isEnabled = enabled
        card.alpha = if (enabled) 1f else 0.5f
    }

    private val rungHeight get() = dp(context, 60f)
    private val rungGap get() = dp(context, 8f)
    private val columnWidth get() = dp(context, 124f)
    private val lane get() = dp(context, 36f)

    private fun rungTop(i: Int) = i * (rungHeight + rungGap)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        rungs.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rungHeight, MeasureSpec.EXACTLY))
        }
        val cardWidth = (width - columnWidth - lane).coerceAtLeast(dp(context, 120f))
        listOf(usageCard, keepCard, autostartCard).forEach {
            it.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        val cards = placeCards()
        val bottom = maxOf(rungTop(DEPTHS.size), cards.last() + autostartCard.measuredHeight)
        setMeasuredDimension(width, bottom)
    }

    /** The cards' tops: each centered where it acts, pushed down below the one above. */
    private fun placeCards(): IntArray {
        val (from, to) = bracket()
        val usageCenter = (rungTop(from) + rungTop(to) + rungHeight) / 2
        val keepCenter = boundary(LujarchBackground.DEPTH_PAUSED)
        val autostartCenter = rungTop(index(LujarchBackground.DEPTH_RECLAIMED)) + rungHeight * 3 / 4
        val gap = dp(context, 8f)
        var top = (usageCenter - usageCard.measuredHeight / 2).coerceAtLeast(0)
        val usageTop = top
        top = maxOf(keepCenter - keepCard.measuredHeight / 2, usageTop + usageCard.measuredHeight + gap)
        val keepTop = top
        top = maxOf(autostartCenter - autostartCard.measuredHeight / 2, keepTop + keepCard.measuredHeight + gap)
        return intArrayOf(usageTop, keepTop, top)
    }

    /** The rungs Background usage lets the app stay at: from its highest to its deepest. */
    private fun bracket(): Pair<Int, Int> {
        val p = pref
        val highest = when (p?.usage) {
            LujarchBackground.USAGE_UNRESTRICTED -> LujarchBackground.DEPTH_RUNNING
            LujarchBackground.USAGE_MESSAGES -> LujarchBackground.DEPTH_MESSAGES
            LujarchBackground.USAGE_RESTRICTED -> LujarchBackground.DEPTH_RECLAIMED
            else -> LujarchBackground.DEPTH_PAUSED
        }
        val deepest = if (p?.kept == true) LujarchBackground.DEPTH_PAUSED else LujarchBackground.DEPTH_RECLAIMED
        val a = index(highest)
        val b = index(deepest)
        return minOf(a, b) to maxOf(a, b)
    }

    private fun index(depth: Int) = DEPTHS.indexOf(depth)

    /** The y between a rung and the one below it. */
    private fun boundary(depth: Int) = rungTop(index(depth)) + rungHeight + rungGap / 2

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        rungs.forEachIndexed { i, v -> v.layout(0, rungTop(i), columnWidth, rungTop(i) + rungHeight) }
        val tops = placeCards()
        val x = columnWidth + lane
        listOf(usageCard, keepCard, autostartCard).forEachIndexed { i, c ->
            c.layout(x, tops[i], x + c.measuredWidth, tops[i] + c.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val p = pref ?: return
        val accent = context.getColor(android.R.color.system_accent1_300)
        val system = LujarchBackground.whoColor(context, LujarchBackground.WHO_SYSTEM)
        line.color = accent
        dash.color = system
        val x0 = columnWidth.toFloat()
        val bx = x0 + dp(context, 12f)
        val cardX = (columnWidth + lane).toFloat()
        // Background usage: a bracket over the depths it may stay at.
        val (from, to) = bracket()
        val top = rungTop(from).toFloat() + dp(context, 4f)
        val bottom = (rungTop(to) + rungHeight).toFloat() - dp(context, 4f)
        canvas.drawLine(x0, top, bx, top, line)
        canvas.drawLine(bx, top, bx, bottom, line)
        canvas.drawLine(x0, bottom, bx, bottom, line)
        canvas.drawLine(bx, usageCard.top + usageCard.height / 2f, cardX, usageCard.top + usageCard.height / 2f, line)
        // Keep: a line under Paused it does not sink below.
        val ky = boundary(LujarchBackground.DEPTH_PAUSED).toFloat()
        val keepY = keepCard.top + keepCard.height / 2f
        if (p.kept) {
            canvas.drawLine(0f, ky, bx, ky, line)
            canvas.drawLine(bx, ky, bx, keepY, line)
            canvas.drawLine(bx, keepY, cardX, keepY, line)
        } else {
            val faint = Paint(dash).apply { color = accent; alpha = 90 }
            canvas.drawLine(x0, ky, bx, ky, faint)
            canvas.drawLine(bx, ky, bx, keepY, faint)
            canvas.drawLine(bx, keepY, cardX, keepY, faint)
        }
        // Autostart: a gate on Reclaimed, who may wake it from there.
        val ri = index(LujarchBackground.DEPTH_RECLAIMED)
        val ay = rungTop(ri) + rungHeight * 3 / 4f
        val autoY = autostartCard.top + autostartCard.height / 2f
        canvas.drawLine(x0 - dp(context, 30f), ay, bx, ay, dash)
        canvas.drawLine(bx, ay, bx, autoY, dash)
        canvas.drawLine(bx, autoY, cardX, autoY, dash)
        // Where it is now.
        val ci = index(current())
        val cy = rungTop(ci) + rungHeight / 2f
        val cx = x0 - dp(context, 14f)
        dot.color = context.getColor(android.R.color.white)
        dot.alpha = 70
        canvas.drawCircle(cx, cy, dp(context, 10f).toFloat(), dot)
        dot.alpha = 255
        canvas.drawCircle(cx, cy, dp(context, 6f).toFloat(), dot)
    }

    companion object {
        private const val TEXTS = "texts"
        private const val SWITCH = "switch"
        val DEPTHS = intArrayOf(
            LujarchBackground.DEPTH_RUNNING,
            LujarchBackground.DEPTH_MESSAGES,
            LujarchBackground.DEPTH_PAUSED,
            LujarchBackground.DEPTH_RECLAIMED,
            LujarchBackground.DEPTH_STOPPED,
        ).toList()
        private val RUNG_NOTES = intArrayOf(
            R.string.background_rung_running,
            R.string.background_rung_messages,
            R.string.background_rung_paused,
            R.string.background_rung_reclaimed,
            R.string.background_rung_stopped,
        )
    }
}
