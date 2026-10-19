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
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.UserHandle
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.app.LujarchBackground
import com.android.internal.app.LujarchBackgroundLimit
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.showDialog

/**
 * Settings > Apps > (app) > Memory > Memory limit (BG-8): Use memory limit, the most the app holds
 * in memory and compressed, what happens when it needs more, and whether the limits hold on
 * screen too; above them what the app holds now against each limit, and below when its limit
 * last ended it.
 */
class MemoryLimitFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var usePref: LimitUsePreference
    private lateinit var memoryPref: Preference
    private lateinit var compressedPref: Preference
    private lateinit var whenPref: ListPreference
    private lateinit var onScreenPref: SwitchPreferenceCompat
    private lateinit var lastEndPref: Preference
    /** The limit as last read; the rows change a copy of it. */
    private var limit: LujarchBackgroundLimit? = null
    private var deviceBytes = 0L
    private var loop: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        val context = prefContext
        mainSwitch = screen.add(MainSwitchPreference(context)) {
            key = "use"
            setTitle(R.string.memory_limit_use)
            isPersistent = false
            isEnabled = false
            setOnPreferenceChangeListener { _, value ->
                change { it.on = value as Boolean }
                true
            }
        }
        usePref = screen.add(LimitUsePreference(context)) { key = "now" }
        val limits = screen.add(PreferenceCategory(context)) {
            key = "limits"
            setTitle(R.string.memory_limit_title)
        }
        memoryPref = limits.add(Preference(context)) {
            key = "memory"
            setTitle(R.string.memory_limit_memory)
            setOnPreferenceClickListener {
                askMemory()
                true
            }
        }
        compressedPref = limits.add(Preference(context)) {
            key = "compressed"
            setTitle(R.string.memory_limit_compressed)
            setOnPreferenceClickListener {
                askCompressed()
                true
            }
        }
        whenPref = limits.add(ListPreference(context)) {
            key = "when"
            setTitle(R.string.memory_limit_when)
            setDialogTitle(R.string.memory_limit_when)
            isPersistent = false
            entries = Array<CharSequence>(ENDS.size) { actionName(context, ENDS[it]) }
            entryValues = Array<CharSequence>(ENDS.size) { ENDS[it].toString() }
            setOnPreferenceChangeListener { _, value ->
                val ms = (value as String).toLong()
                change {
                    it.action = if (ms < 0) LujarchBackground.LIMIT_HOLD else LujarchBackground.LIMIT_END
                    it.endAfterMs = ms.coerceAtLeast(0)
                }
                true
            }
        }
        onScreenPref = limits.add(SwitchPreferenceCompat(context)) {
            key = "on_screen"
            setTitle(R.string.memory_limit_on_screen)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                change { it.onScreen = value as Boolean }
                true
            }
        }
        lastEndPref = screen.add(Preference(context)) {
            key = "last_end"
            setTitle(R.string.memory_limit_last_end)
            isSelectable = false
        }
        screen.add(FooterPreference(context)) {
            key = "footer"
            setTitle(R.string.memory_limit_footer)
        }
        setRowsEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        loop = launchUi {
            while (true) {
                load()
                delay(REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        loop?.cancel()
        super.onPause()
    }

    override fun refresh() {
        launchUi { load() }
    }

    private suspend fun load() {
        val user = UserHandle.myUserId()
        val l = attempt("limit", R.string.memory_limit_error_load) {
            BackgroundClient.call { it.getLimit(packageName, user) }
        }
        if (deviceBytes == 0L) {
            deviceBytes = orNull("device memory") { BackgroundClient.call { it.memory.totalBytes } } ?: 0L
        }
        if (!pageAlive) return
        if (l == null) {
            usePref.limit = null
            setRowsEnabled(false)
            mainSwitch.isEnabled = false
            return
        }
        limit = l
        render(l)
    }

    private fun render(l: LujarchBackgroundLimit) {
        val context = prefContext
        mainSwitch.isEnabled = true
        mainSwitch.isChecked = l.on
        usePref.limit = l
        memoryPref.summary = size(context, l.memoryBytes)
        compressedPref.summary = compressedName(context, l.compressedBytes)
        val end = if (l.action == LujarchBackground.LIMIT_HOLD) -1L else l.endAfterMs
        whenPref.value = (ENDS.firstOrNull { it == end } ?: end).toString()
        whenPref.summary = actionSummary(context, end)
        onScreenPref.isChecked = l.onScreen
        onScreenPref.setSummary(if (l.onScreen) R.string.memory_limit_on_screen_on
            else R.string.memory_limit_on_screen_off)
        lastEndPref.summary = if (l.lastEndMillis > 0) AppMemoryFragment.timeOf(context, l.lastEndMillis)
            else getString(R.string.memory_limit_never_ended)
        setRowsEnabled(l.on)
    }

    private fun setRowsEnabled(on: Boolean) {
        for (p in listOf(memoryPref, compressedPref, whenPref, onScreenPref)) p.isEnabled = on
    }

    /** Changes the limit as the owner set it and shows it at once; read again after. */
    private fun change(edit: (LujarchBackgroundLimit) -> Unit) {
        val now = limit ?: return
        val next = copy(now).also(edit)
        limit = next
        render(next)
        launchUi {
            act(R.string.memory_limit_error_set) {
                BackgroundClient.call { it.setLimit(packageName, UserHandle.myUserId(), next) }
            }
            load()
        }
    }

    private fun askMemory() {
        showDialog("memory") { d ->
            val l = limit ?: return@showDialog null
            val max = maxBytes()
            InputDialog.create(d.context, R.string.memory_limit_memory,
                message = d.context.getString(R.string.memory_limit_memory_message,
                    size(d.context, l.usedBytes), size(d.context, LujarchBackground.LIMIT_MIN_BYTES),
                    size(d.context, max)),
                initial = (l.memoryBytes / MB).toString(),
                hint = d.context.getString(R.string.memory_limit_mb),
                numeric = true,
                validate = { text -> rangeError(d.context, text, LujarchBackground.LIMIT_MIN_BYTES, max) },
                onDone = { text -> change { it.memoryBytes = text.toLong() * MB } })
        }
    }

    private fun askCompressed() {
        showDialog("compressed") { d ->
            val l = limit ?: return@showDialog null
            val max = maxBytes()
            InputDialog.create(d.context, R.string.memory_limit_compressed,
                message = d.context.getString(R.string.memory_limit_compressed_message,
                    size(d.context, l.usedCompressedBytes), size(d.context, max)),
                initial = if (l.compressedBytes < 0) "" else (l.compressedBytes / MB).toString(),
                hint = d.context.getString(R.string.memory_limit_mb),
                numeric = true,
                neutral = R.string.memory_limit_no_limit,
                onNeutral = { change { it.compressedBytes = -1 } },
                validate = { text -> rangeError(d.context, text, 0, max) },
                onDone = { text -> change { it.compressedBytes = text.toLong() * MB } })
        }
    }

    /** The device's memory, the largest limit; 64 GB until it is known. */
    private fun maxBytes(): Long = if (deviceBytes > 0) deviceBytes else 64_000 * MB

    /** An error for a size in MB outside [min, max]; "" for an empty field (no error shown). */
    private fun rangeError(context: Context, text: String, min: Long, max: Long): String? {
        val mb = text.toLongOrNull() ?: return ""
        val bytes = mb * MB
        return if (mb < 0 || bytes < min || bytes > max) {
            context.getString(R.string.memory_limit_range, size(context, min), size(context, max))
        } else {
            null
        }
    }

    companion object {
        const val ARG_PACKAGE = "package"
        private const val REFRESH_MS = 2_000L
        private const val MB = LujarchBackground.LIMIT_MB
        /** When it needs more: -1 holds it back, the rest ends it after that many ms. */
        private val ENDS = longArrayOf(-1, 0, 10_000, 30_000, 60_000, 300_000)

        fun open(context: Context, packageName: String) {
            Pages.open(context, MemoryLimitFragment::class.java, R.string.memory_limit_title,
                arguments = Bundle().apply { putString(ARG_PACKAGE, packageName) })
        }

        fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

        fun compressedName(context: Context, bytes: Long): String =
            if (bytes < 0) context.getString(R.string.memory_limit_no_limit) else size(context, bytes)

        /** The limit's row on the app's memory page. */
        fun summary(context: Context, l: LujarchBackgroundLimit): String =
            if (!l.on) context.getString(R.string.memory_limit_off)
            else context.getString(R.string.memory_limit_row, size(context, l.memoryBytes),
                compressedName(context, l.compressedBytes))

        private fun duration(context: Context, ms: Long): String = when {
            ms < 60_000 -> context.getString(R.string.memory_limit_seconds, (ms / 1000).toInt())
            ms == 60_000L -> context.getString(R.string.memory_limit_minute)
            else -> context.getString(R.string.memory_limit_minutes, (ms / 60_000).toInt())
        }

        private fun actionName(context: Context, end: Long): String = when {
            end < 0 -> context.getString(R.string.memory_limit_hold)
            end == 0L -> context.getString(R.string.memory_limit_end_now)
            else -> context.getString(R.string.memory_limit_end_after, duration(context, end))
        }

        private fun actionSummary(context: Context, end: Long): String = when {
            end < 0 -> context.getString(R.string.memory_limit_hold_summary)
            end == 0L -> context.getString(R.string.memory_limit_end_now_summary)
            else -> context.getString(R.string.memory_limit_end_after_summary, duration(context, end))
        }

        fun copy(l: LujarchBackgroundLimit) = LujarchBackgroundLimit().apply {
            on = l.on
            memoryBytes = l.memoryBytes
            compressedBytes = l.compressedBytes
            action = l.action
            endAfterMs = l.endAfterMs
            onScreen = l.onScreen
            usedBytes = l.usedBytes
            usedCompressedBytes = l.usedCompressedBytes
            state = l.state
            sinceMillis = l.sinceMillis
            endsAtMillis = l.endsAtMillis
            lastEndMillis = l.lastEndMillis
        }
    }
}

/** What the app holds now, in memory and compressed, against each limit, and where it is (BG-8). */
class LimitUsePreference(context: Context) : Preference(context) {
    var limit: LujarchBackgroundLimit? = null
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        layoutResource = R.layout.memory_limit_use
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val l = limit
        val state = holder.findViewById(R.id.limit_state) as TextView
        val since = holder.findViewById(R.id.limit_since) as TextView
        val memoryValue = holder.findViewById(R.id.limit_memory_value) as TextView
        val memoryBar = holder.findViewById(R.id.limit_memory_bar) as LimitBarView
        val compressedValue = holder.findViewById(R.id.limit_compressed_value) as TextView
        val compressedBar = holder.findViewById(R.id.limit_compressed_bar) as LimitBarView
        if (l == null) {
            state.text = context.getString(org.lujarch.settings.pages.R.string.loading)
            state.setCompoundDrawablesRelative(null, null, null, null)
            since.visibility = View.GONE
            memoryValue.text = ""
            compressedValue.text = ""
            memoryBar.set(0, 0, false)
            compressedBar.set(0, 0, false)
            return
        }
        val s = when {
            l.state == LujarchBackground.LIMIT_STATE_NONE -> LujarchBackground.LIMIT_STATE_NONE
            !l.on -> STATE_OFF
            else -> l.state
        }
        state.text = when (s) {
            LujarchBackground.LIMIT_STATE_NONE -> context.getString(R.string.memory_limit_state_none)
            STATE_OFF -> context.getString(R.string.memory_limit_state_off)
            LujarchBackground.LIMIT_STATE_LIFTED -> context.getString(R.string.memory_limit_state_lifted)
            LujarchBackground.LIMIT_STATE_COMPRESSING ->
                context.getString(R.string.memory_limit_state_compressing)
            LujarchBackground.LIMIT_STATE_NEEDS_MORE ->
                if (l.endsAtMillis > 0) context.getString(R.string.memory_limit_state_needs_more_end,
                    AppMemoryFragment.timeOf(context, l.endsAtMillis))
                else context.getString(R.string.memory_limit_state_needs_more)
            else -> context.getString(R.string.memory_limit_state_within)
        }
        val needs = s == LujarchBackground.LIMIT_STATE_NEEDS_MORE
        state.setCompoundDrawablesRelative(dot(context, s), null, null, null)
        val showSince = l.on && l.sinceMillis > 0 && (s == LujarchBackground.LIMIT_STATE_COMPRESSING || needs)
        since.visibility = if (showSince) View.VISIBLE else View.GONE
        if (showSince) {
            since.text = context.getString(R.string.memory_limit_since,
                AppMemoryFragment.timeOf(context, l.sinceMillis))
        }
        val limited = l.on
        memoryValue.text = if (limited) context.getString(R.string.memory_limit_of,
            MemoryLimitFragment.size(context, l.usedBytes), MemoryLimitFragment.size(context, l.memoryBytes))
            else MemoryLimitFragment.size(context, l.usedBytes)
        memoryBar.visibility = if (limited) View.VISIBLE else View.GONE
        memoryBar.set(l.usedBytes, l.memoryBytes, needs)
        val compressedLimit = if (limited && l.compressedBytes >= 0) l.compressedBytes else 0
        compressedBar.visibility = if (compressedLimit > 0) View.VISIBLE else View.GONE
        compressedValue.text = if (limited) context.getString(R.string.memory_limit_of,
            MemoryLimitFragment.size(context, l.usedCompressedBytes),
            MemoryLimitFragment.compressedName(context, l.compressedBytes))
            else MemoryLimitFragment.size(context, l.usedCompressedBytes)
        compressedBar.set(l.usedCompressedBytes, compressedLimit, needs)
    }

    /** The state's mark: the apps' color within the limits, the error color when it needs more. */
    private fun dot(context: Context, state: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(when (state) {
            LujarchBackground.LIMIT_STATE_NEEDS_MORE -> errorColor(context)
            LujarchBackground.LIMIT_STATE_WITHIN, LujarchBackground.LIMIT_STATE_COMPRESSING ->
                LujarchBackground.memoryColor(context, LujarchBackground.MEMORY_APPS)
            else -> secondaryText(context)?.defaultColor ?: 0
        })
        val size = dp(context, 10f)
        setSize(size, size)
        setBounds(0, 0, size, size)
    }

    private companion object {
        /** Use memory limit is off: the use is shown without limits. */
        const val STATE_OFF = -1
    }
}

internal fun errorColor(context: Context): Int {
    val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorError))
    try {
        return a.getColor(0, 0xFFB3261E.toInt())
    } finally {
        a.recycle()
    }
}

/** A use against its limit: the track is the limit, the fill what is used. */
class LimitBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var used = 0L
    private var limit = 0L
    private var needs = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(used: Long, limit: Long, needs: Boolean) {
        this.used = used
        this.limit = limit
        this.needs = needs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        val w = width.toFloat()
        paint.color = LujarchBackground.memoryColor(context, LujarchBackground.MEMORY_FREE)
        rect.set(0f, 0f, w, height.toFloat())
        canvas.drawRoundRect(rect, r, r, paint)
        if (used <= 0 || limit <= 0) return
        val fraction = (used.toFloat() / limit).coerceIn(0f, 1f)
        paint.color = if (needs) errorColor(context)
            else LujarchBackground.memoryColor(context, LujarchBackground.MEMORY_APPS)
        rect.set(0f, 0f, (w * fraction).coerceAtLeast(height.toFloat()), height.toFloat())
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
