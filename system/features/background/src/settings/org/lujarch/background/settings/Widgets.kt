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
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.format.Formatter
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.internal.app.LujarchBackground
import com.android.internal.app.LujarchBackgroundMemory

/** The theme's color of secondary text. */
internal fun secondaryText(context: Context): ColorStateList? =
    themeColor(context, android.R.attr.textColorSecondary)

internal fun primaryText(context: Context): ColorStateList? =
    themeColor(context, android.R.attr.textColorPrimary)

private fun themeColor(context: Context, attr: Int): ColorStateList? {
    val a = context.obtainStyledAttributes(intArrayOf(attr))
    try {
        return a.getColorStateList(0)
    } finally {
        a.recycle()
    }
}

/** What a page is about, at its top: an app or a process, its name over what it is now. */
class HeaderPreference(context: Context) : Preference(context) {
    init {
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Preference gives an unselectable row's title the summary's color; this one is a name.
        val title = holder.findViewById(android.R.id.title) as? TextView ?: return
        primaryText(context)?.let { title.setTextColor(it) }
    }
}

internal fun dp(context: Context, value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

/** Who caused it, as a small mark: You filled, System outlined, an app dashed (BG-1). */
object Chips {
    fun bind(view: TextView, who: Int, name: CharSequence?) {
        val context = view.context
        val color = LujarchBackground.whoColor(context, who)
        view.text = name ?: LujarchBackground.whoName(context, who)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(context, 5f).toFloat()
            val stroke = dp(context, 1.5f)
            when (who) {
                LujarchBackground.WHO_YOU -> setColor(color)
                LujarchBackground.WHO_SYSTEM -> setStroke(stroke, color)
                else -> setStroke(stroke, color, dp(context, 3f).toFloat(), dp(context, 2f).toFloat())
            }
        }
        view.setTextColor(
            if (who == LujarchBackground.WHO_YOU) context.getColor(android.R.color.system_accent1_0) else color
        )
        val h = dp(context, 5f)
        view.setPadding(h, 0, h, 0)
    }
}

/** The mark of a depth: its color, dashed for Stopped. */
fun depthMark(context: Context, depth: Int, smallWindow: Boolean = false): Drawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(LujarchBackground.depthColor(context, depth, smallWindow))
        if (depth == LujarchBackground.DEPTH_STOPPED) {
            setStroke(dp(context, 1.5f), LujarchBackground.depthOutline(context, depth), dp(context, 2f).toFloat(),
                dp(context, 1.5f).toFloat())
        }
    }

/** A depth's mark as a list icon: the dot at its own size, in the icon's 24 dp. */
fun depthIcon(context: Context, depth: Int): Drawable {
    val dot = depthMark(context, depth).mutate() as GradientDrawable
    dot.setSize(dp(context, 10f), dp(context, 10f))
    val inset = dp(context, 7f)
    return InsetDrawable(dot, inset)
}

/** The memory in use as four parts, as a bar with its legend (BG-2). */
class MemoryPreference(context: Context) : Preference(context) {
    var memory: LujarchBackgroundMemory? = null
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        layoutResource = R.layout.background_memory
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val m = memory
        val bar = holder.findViewById(R.id.background_memory_bar) as MemoryBarView
        val free = holder.findViewById(R.id.background_memory_free) as TextView
        val legend = holder.findViewById(R.id.background_memory_legend) as LinearLayout
        bar.memory = m
        free.text = if (m == null) context.getString(org.lujarch.settings.pages.R.string.loading)
        else context.getString(
            R.string.background_memory_free_of,
            Formatter.formatShortFileSize(context, m.freeBytes),
            Formatter.formatShortFileSize(context, m.totalBytes),
        )
        legend.removeAllViews()
        if (m == null) return
        val parts = longArrayOf(m.systemBytes, m.appsBytes, m.keptBytes, m.freeBytes)
        for (part in 0..3) {
            legend.addView(TextView(context).apply {
                val name = LujarchBackground.memoryName(context, part)
                text = context.getString(R.string.background_memory_part, name,
                    Formatter.formatShortFileSize(context, parts[part]))
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                secondaryText(context)?.let { setTextColor(it) }
                setCompoundDrawablesRelativeWithIntrinsicBounds(square(context, part), null, null, null)
                compoundDrawablePadding = dp(context, 6f)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /**
     * A part's mark: its color as in the bar; Free, the bar's empty track, is an outline in the
     * legend's color.
     */
    private fun square(context: Context, part: Int): Drawable = GradientDrawable().apply {
        val color = LujarchBackground.memoryColor(context, part)
        if (part == LujarchBackground.MEMORY_FREE) {
            setStroke(dp(context, 1.5f), secondaryText(context)?.defaultColor ?: color)
        } else {
            setColor(color)
        }
        cornerRadius = dp(context, 2f).toFloat()
        val s = dp(context, 10f)
        setSize(s, s)
    }
}

/** A bar of the four parts of the memory. */
class MemoryBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var memory: LujarchBackgroundMemory? = null
        set(value) {
            field = value
            contentDescription = value?.let {
                context.getString(R.string.background_memory_free_of,
                    Formatter.formatShortFileSize(context, it.freeBytes),
                    Formatter.formatShortFileSize(context, it.totalBytes))
            }
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val m = memory ?: return
        val total = (m.systemBytes + m.appsBytes + m.keptBytes + m.freeBytes).coerceAtLeast(1)
        val parts = longArrayOf(m.systemBytes, m.appsBytes, m.keptBytes, m.freeBytes)
        val gap = dp(context, 2f).toFloat()
        val r = height / 2f
        var x = 0f
        val w = width.toFloat()
        for (i in parts.indices) {
            val pw = w * parts[i] / total
            if (pw <= 0f) continue
            paint.color = LujarchBackground.memoryColor(context, i)
            rect.set(x, 0f, (x + pw - gap).coerceAtLeast(x + 1f), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            x += pw
        }
    }
}

/** The header of a depth's group: its mark, name and count (BG-2). */
class DepthHeaderPreference(context: Context) : Preference(context) {
    var depth = LujarchBackground.DEPTH_RUNNING
    var count = 0
    var note: CharSequence? = null

    init {
        layoutResource = R.layout.background_depth_header
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.background_header_dot) as ImageView).setImageDrawable(depthMark(context, depth))
        (holder.findViewById(R.id.background_header_name) as TextView).text =
            LujarchBackground.depthName(context, depth)
        (holder.findViewById(R.id.background_header_count) as TextView).text = count.toString()
        (holder.findViewById(R.id.background_header_note) as TextView).text = note
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
    }
}

/** One app: icon, name, who and why, memory (BG-2). */
class AppRowPreference(context: Context) : Preference(context) {
    var depth = LujarchBackground.DEPTH_RUNNING
    var smallWindow = false
    var who = LujarchBackground.WHO_SYSTEM
    var whoName: CharSequence? = null
    var reason: CharSequence? = null
    var value: CharSequence? = null

    init {
        layoutResource = R.layout.background_app_row
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.background_row_dot) as ImageView).setImageDrawable(depthMark(context, depth, smallWindow))
        Chips.bind(holder.findViewById(R.id.background_row_who) as TextView, who, whoName)
        (holder.findViewById(R.id.background_row_reason) as TextView).text = reason
        (holder.findViewById(R.id.background_row_value) as TextView).text = value
    }
}

/** A line of a list: time or mark, who and what (BG-6, BG-7). */
class LinePreference(context: Context) : Preference(context) {
    var lead: CharSequence? = null
    var mark: Drawable? = null
    var who = LujarchBackground.WHO_SYSTEM
    var whoName: CharSequence? = null
    var showWho = true
    var text: CharSequence? = null
    var value: CharSequence? = null

    init {
        layoutResource = R.layout.background_line
        isPersistent = false
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val leadView = holder.findViewById(R.id.background_line_lead) as TextView
        leadView.text = lead
        leadView.visibility = if (lead == null) View.GONE else View.VISIBLE
        val markView = holder.findViewById(R.id.background_line_mark) as ImageView
        markView.setImageDrawable(mark)
        markView.visibility = if (mark == null) View.GONE else View.VISIBLE
        val whoView = holder.findViewById(R.id.background_line_who) as TextView
        if (showWho) {
            Chips.bind(whoView, who, whoName)
            whoView.visibility = View.VISIBLE
        } else {
            whoView.visibility = View.GONE
        }
        (holder.findViewById(R.id.background_line_text) as TextView).text = text
        val valueView = holder.findViewById(R.id.background_line_value) as TextView
        valueView.text = value
        valueView.visibility = if (value == null) View.GONE else View.VISIBLE
    }
}

/** Figures side by side: a small title over each value (BG-3, BG-7). */
class TilesPreference(context: Context) : Preference(context) {
    var tiles: List<Pair<CharSequence, CharSequence>> = emptyList()
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        layoutResource = R.layout.background_tiles
        isPersistent = false
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val grid = holder.findViewById(R.id.background_tiles) as LinearLayout
        grid.removeAllViews()
        var row: LinearLayout? = null
        tiles.forEachIndexed { i, (title, value) ->
            if (i % 3 == 0) {
                // A title over two lines does not push its figure below the others of the row.
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    gravity = Gravity.BOTTOM
                }
                grid.addView(row)
            }
            row!!.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(context, 6f), 0, dp(context, 6f))
                addView(TextView(context).apply {
                    text = title
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
                    alpha = 0.7f
                })
                addView(TextView(context).apply {
                    text = value
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val left = tiles.size % 3
        if (left != 0) repeat(3 - left) {
            row!!.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }
}

/** A process's depth now, on its depth's color (BG-7). */
class NowPreference(context: Context) : Preference(context) {
    var depth = LujarchBackground.DEPTH_RUNNING
    var state: CharSequence? = null
    var text: CharSequence? = null

    /** Shows the values set. */
    fun update() = notifyChanged()

    init {
        layoutResource = R.layout.background_now
        isPersistent = false
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val card = holder.findViewById(R.id.background_now_card)!!
        card.background = GradientDrawable().apply {
            cornerRadius = dp(context, 24f).toFloat()
            setColor(LujarchBackground.depthColor(context, depth, false))
            if (depth == LujarchBackground.DEPTH_STOPPED) {
                setStroke(dp(context, 1.5f), LujarchBackground.depthOutline(context, depth),
                    dp(context, 3f).toFloat(), dp(context, 2f).toFloat())
            }
        }
        val on = ColorStateList.valueOf(LujarchBackground.onDepthColor(context, depth))
        for (id in intArrayOf(R.id.background_now_label, R.id.background_now_state, R.id.background_now_text)) {
            (holder.findViewById(id) as TextView).setTextColor(on)
        }
        (holder.findViewById(R.id.background_now_state) as TextView).text = state
        // The reason under the depth, unless it only names the depth again.
        val reason = text?.takeIf { it.isNotEmpty() && it.toString() != state.toString() }
        val reasonView = holder.findViewById(R.id.background_now_text) as TextView
        reasonView.text = reason
        reasonView.visibility = if (reason == null) View.GONE else View.VISIBLE
    }
}
