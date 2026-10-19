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

package org.lujarch.storage.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.UserHandle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import com.android.internal.app.LujarchStorage
import com.android.internal.app.LujarchStorageLimit
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.showDialog

/**
 * Settings > Apps > (app) > Storage & cache > Storage limit (STO-1): Use storage limit, how much
 * the app may keep and what happens when it is full; above them its User data and Cache against
 * the limit, whether it is full and when its cache was last cleared for it.
 */
class StorageLimitFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var usePref: StorageUsePreference
    private lateinit var storagePref: Preference
    private lateinit var whenPref: ListPreference
    /** The limit as last read; the rows change a copy of it. */
    private var limit: LujarchStorageLimit? = null
    private var loop: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        val context = prefContext
        mainSwitch = screen.add(MainSwitchPreference(context)) {
            key = "use"
            setTitle(R.string.storage_limit_use)
            isPersistent = false
            isEnabled = false
            setOnPreferenceChangeListener { _, value ->
                change { it.on = value as Boolean }
                true
            }
        }
        usePref = screen.add(StorageUsePreference(context)) { key = "now" }
        storagePref = screen.add(Preference(context)) {
            key = "storage"
            setTitle(R.string.storage_limit_storage)
            setOnPreferenceClickListener {
                askStorage()
                true
            }
        }
        whenPref = screen.add(ListPreference(context)) {
            key = "when"
            setTitle(R.string.storage_limit_when)
            setDialogTitle(R.string.storage_limit_when)
            isPersistent = false
            entries = arrayOf(getString(R.string.storage_limit_clear_cache), getString(R.string.storage_limit_stop))
            entryValues = arrayOf(LujarchStorage.FULL_CLEAR_CACHE.toString(), LujarchStorage.FULL_STOP.toString())
            setOnPreferenceChangeListener { _, value ->
                change { it.action = (value as String).toInt() }
                true
            }
        }
        screen.add(FooterPreference(context)) {
            key = "footer"
            setTitle(R.string.storage_limit_footer)
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
        val l = attempt("limit", R.string.storage_limit_error_load) {
            StorageClient.call { it.getLimit(packageName, user) }
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

    private fun render(l: LujarchStorageLimit) {
        val context = prefContext
        mainSwitch.isEnabled = true
        mainSwitch.isChecked = l.on
        usePref.limit = l
        storagePref.summary = Format.bytes(context, l.storageBytes)
        whenPref.value = l.action.toString()
        whenPref.setSummary(if (l.action == LujarchStorage.FULL_STOP) R.string.storage_limit_stop_summary
            else R.string.storage_limit_clear_cache_summary)
        setRowsEnabled(l.on)
    }

    private fun setRowsEnabled(on: Boolean) {
        storagePref.isEnabled = on
        whenPref.isEnabled = on
    }

    /** Changes the limit as the owner set it and shows it at once; read again after. */
    private fun change(edit: (LujarchStorageLimit) -> Unit) {
        val now = limit ?: return
        val next = copy(now).also(edit)
        limit = next
        render(next)
        launchUi {
            act(R.string.storage_limit_error_set) {
                StorageClient.call { it.setLimit(packageName, UserHandle.myUserId(), next) }
            }
            load()
        }
    }

    private fun askStorage() {
        showDialog("storage") { d ->
            val l = limit ?: return@showDialog null
            val max = l.maxBytes
            InputDialog.create(d.context, R.string.storage_limit_storage,
                message = d.context.getString(R.string.storage_limit_storage_message,
                    Format.bytes(d.context, held(l)), Format.bytes(d.context, LujarchStorage.MIN_BYTES),
                    Format.bytes(d.context, max)),
                initial = (l.storageBytes / MB).toString(),
                hint = d.context.getString(R.string.storage_limit_mb),
                numeric = true,
                validate = { text -> rangeError(d.context, text, LujarchStorage.MIN_BYTES, max) },
                onDone = { text -> change { it.storageBytes = text.toLong() * MB } })
        }
    }

    /** An error for a size in MB outside [min, max]; "" for an empty field (no error shown). */
    private fun rangeError(context: Context, text: String, min: Long, max: Long): String? {
        val mb = text.toLongOrNull() ?: return ""
        return if (mb < 0 || mb > Long.MAX_VALUE / MB || mb * MB < min || mb * MB > max) {
            context.getString(R.string.storage_limit_range, Format.bytes(context, min), Format.bytes(context, max))
        } else {
            null
        }
    }

    companion object {
        private const val ARG_PACKAGE = "package"
        private const val REFRESH_MS = 2_000L
        private const val MB = LujarchStorage.MB

        fun args(packageName: String) = Bundle().apply { putString(ARG_PACKAGE, packageName) }

        /** What the app holds against its limit: its User data and Cache together. */
        fun held(l: LujarchStorageLimit): Long = maxOf(0, l.dataBytes) + maxOf(0, l.cacheBytes)

        fun copy(l: LujarchStorageLimit) = LujarchStorageLimit().apply {
            on = l.on
            storageBytes = l.storageBytes
            action = l.action
            dataBytes = l.dataBytes
            cacheBytes = l.cacheBytes
            state = l.state
            sinceMillis = l.sinceMillis
            lastClearMillis = l.lastClearMillis
            maxBytes = l.maxBytes
        }
    }
}

/** The app's User data and Cache against its limit, whether it is full, its last cache clear. */
class StorageUsePreference(context: Context) : Preference(context) {
    var limit: LujarchStorageLimit? = null
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        layoutResource = R.layout.storage_limit_use
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val l = limit
        val state = holder.findViewById(R.id.storage_state) as TextView
        val lastClear = holder.findViewById(R.id.storage_last_clear) as TextView
        val total = holder.findViewById(R.id.storage_total_value) as TextView
        val bar = holder.findViewById(R.id.storage_bar) as StorageBarView
        val data = holder.findViewById(R.id.storage_data_value) as TextView
        val cache = holder.findViewById(R.id.storage_cache_value) as TextView
        (holder.findViewById(R.id.storage_data_key) as View).background = StorageBarView.dot(context, StorageBarView.DATA)
        (holder.findViewById(R.id.storage_cache_key) as View).background = StorageBarView.dot(context, StorageBarView.CACHE)
        if (l == null) {
            state.text = context.getString(org.lujarch.settings.pages.R.string.loading)
            state.setCompoundDrawablesRelative(null, null, null, null)
            lastClear.visibility = View.GONE
            total.text = ""
            data.text = ""
            cache.text = ""
            bar.set(0, 0, 0, false)
            return
        }
        val full = l.state == LujarchStorage.STATE_FULL
        state.text = when {
            l.state == LujarchStorage.STATE_OFF -> context.getString(R.string.storage_limit_state_off)
            full && l.sinceMillis > 0 ->
                context.getString(R.string.storage_limit_state_full_since, Format.at(context, l.sinceMillis))
            full -> context.getString(R.string.storage_limit_state_full)
            else -> context.getString(R.string.storage_limit_state_within)
        }
        state.setCompoundDrawablesRelative(StorageBarView.dot(context,
            if (full) StorageBarView.FULL else if (l.on) StorageBarView.DATA else StorageBarView.NONE), null, null, null)
        lastClear.visibility = if (l.lastClearMillis > 0) View.VISIBLE else View.GONE
        if (l.lastClearMillis > 0) {
            lastClear.text = context.getString(R.string.storage_limit_last_clear, Format.at(context, l.lastClearMillis))
        }
        val held = StorageLimitFragment.held(l)
        total.text = if (l.on) context.getString(R.string.storage_limit_of, Format.bytes(context, held),
            Format.bytes(context, l.storageBytes)) else Format.bytes(context, held)
        bar.visibility = if (l.on) View.VISIBLE else View.GONE
        bar.set(maxOf(0, l.dataBytes), maxOf(0, l.cacheBytes), l.storageBytes, full)
        data.text = if (l.dataBytes >= 0) Format.bytes(context, l.dataBytes) else ""
        cache.text = if (l.cacheBytes >= 0) Format.bytes(context, l.cacheBytes) else ""
    }
}

/** User data and Cache side by side against the limit: the track is the limit. */
class StorageBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var data = 0L
    private var cache = 0L
    private var limit = 0L
    private var full = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(data: Long, cache: Long, limit: Long, full: Boolean) {
        this.data = data
        this.cache = cache
        this.limit = limit
        this.full = full
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        val w = width.toFloat()
        paint.color = color(context, NONE)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, paint)
        if (limit <= 0 || data + cache <= 0) return
        val all = (w * ((data + cache).toFloat() / limit).coerceIn(0f, 1f)).coerceAtLeast(h)
        val split = if (data + cache > 0) all * data / (data + cache) else 0f
        canvas.save()
        rect.set(0f, 0f, all, h)
        val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        paint.color = color(context, if (full) FULL else DATA)
        canvas.drawRect(0f, 0f, split, h, paint)
        paint.color = color(context, CACHE)
        canvas.drawRect(split, 0f, all, h, paint)
        canvas.restore()
    }

    companion object {
        const val NONE = 0
        const val DATA = 1
        const val CACHE = 2
        const val FULL = 3

        /** The accent for User data, a lighter tone of it for Cache, the error color when full. */
        fun color(context: Context, part: Int): Int {
            val accent = attr(context, android.R.attr.colorAccent, 0xFF1A73E8.toInt())
            return when (part) {
                DATA -> accent
                CACHE -> (accent and 0x00FFFFFF) or (0x80 shl 24)
                FULL -> attr(context, android.R.attr.colorError, 0xFFB3261E.toInt())
                else -> (accent and 0x00FFFFFF) or (0x26 shl 24)
            }
        }

        fun dot(context: Context, part: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(context, part))
            val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f,
                context.resources.displayMetrics).toInt()
            setSize(size, size)
            setBounds(0, 0, size, size)
        }

        private fun attr(context: Context, attr: Int, fallback: Int): Int {
            val a = context.obtainStyledAttributes(intArrayOf(attr))
            try {
                return a.getColor(0, fallback)
            } finally {
                a.recycle()
            }
        }
    }
}
