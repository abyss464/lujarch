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

package org.lujarch.proxy.settings

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.ZeroStatePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format

/** Live core log (/logs stream) with a level filter; newest line first. */
class LogsFragment : BasePreferenceFragment(), MenuProvider {
    private lateinit var levelPref: ListPreference
    private lateinit var logCategory: PreferenceCategory
    private lateinit var waitingPref: ZeroStatePreference

    /** Level to stream; a new generation restarts the stream with the same level. */
    private data class StreamKey(val level: String, val generation: Int)

    private val streamKey = MutableStateFlow(StreamKey(DEFAULT_LEVEL, 0))
    private val pending = ArrayList<LogLine>()
    private val shown = ArrayDeque<Preference>()
    private var sequence = 0
    private var paused = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        paused = savedInstanceState?.getBoolean(STATE_PAUSED) ?: false
        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        levelPref = header.add(ListPreference(prefContext)) {
            key = KEY_LEVEL
            setTitle(R.string.logs_level)
            setDialogTitle(R.string.logs_level)
            entries = resources.getStringArray(R.array.log_level_entries)
            entryValues = Mihomo.LOG_LEVELS.toTypedArray()
            setDefaultValue(DEFAULT_LEVEL)
            setOnPreferenceChangeListener { _, value ->
                // The lines shown so far may be below the new level.
                clearLines()
                streamKey.value = streamKey.value.copy(level = value as String)
                showLevel(value)
                true
            }
        }
        logCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "log"
            setTitle(R.string.logs_category)
            isOrderingAsAdded = false
            isVisible = false
        }
        waitingPref = screen.add(ZeroStatePreference(prefContext)) {
            key = "waiting"
            setIcon(R.drawable.ic_logs)
            setTitle(R.string.logs_waiting)
        }
        // The stored level is known once the preference is attached.
        streamKey.value = StreamKey(levelPref.value?.takeIf { it in Mihomo.LOG_LEVELS } ?: DEFAULT_LEVEL, 0)
        showLevel(streamKey.value.level)
    }

    /** The core's own log level (Core settings): the stream never carries anything below it. */
    private var coreLevel: String? = null

    /**
     * The chosen level, and when the core records less than it (its level is
     * higher, or silent) what the core records: without it an empty page looked
     * like a fault.
     */
    private fun showLevel(level: String) {
        val chosen = levelLabel(level)
        val core = coreLevel
        val coreIndex = core?.let { Mihomo.CORE_LOG_LEVELS.indexOf(it) } ?: -1
        levelPref.summary = when {
            core == "silent" -> getString(R.string.px_logs_core_silent, chosen)
            coreIndex > Mihomo.LOG_LEVELS.indexOf(level) && core != null ->
                getString(R.string.px_logs_level_limited, chosen, levelLabel(core))
            else -> chosen
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PAUSED, paused)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    coreLevel = orNull("core log level") { Mihomo.configs().logLevel?.lowercase() }
                    showLevel(streamKey.value.level)
                }
                launch { flushLoop() }
                streamKey.collectLatest { key -> streamLoop(key.level) }
            }
        }
    }

    private suspend fun streamLoop(level: String) {
        while (true) {
            try {
                Mihomo.logs(level).collect { line ->
                    clearError("logs")
                    if (!paused) pending.add(line)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError("logs", R.string.error_logs, e)
                currentCoroutineContext().ensureActive()
            }
            delay(RETRY_MS)
        }
    }

    /** Moves received lines into the list in batches, so bursts do not rebind per line. */
    private suspend fun flushLoop() {
        while (true) {
            delay(FLUSH_MS)
            if (pending.isEmpty()) continue
            val context = context ?: return
            for (line in pending) {
                val pref = Preference(prefContext).apply {
                    key = "line:" + sequence
                    order = -sequence
                    sequence++
                    title = getString(
                        R.string.logs_line_title,
                        Format.time(context, line.receivedAt),
                        levelLabel(line.level),
                    )
                    summary = line.payload
                    isSelectable = false
                    isCopyingEnabled = true
                }
                logCategory.addPreference(pref)
                shown.addLast(pref)
            }
            pending.clear()
            while (shown.size > MAX_LINES) logCategory.removePreference(shown.removeFirst())
            logCategory.isVisible = true
            waitingPref.isVisible = false
        }
    }

    private fun levelLabel(value: String): String {
        val index = Mihomo.LOG_LEVELS.indexOf(value.lowercase())
        return if (index >= 0) resources.getStringArray(R.array.log_level_entries)[index] else value
    }

    override fun refresh() {
        streamKey.value = streamKey.value.let { it.copy(generation = it.generation + 1) }
    }

    private fun clearLines() {
        shown.forEach { logCategory.removePreference(it) }
        shown.clear()
        pending.clear()
        logCategory.isVisible = false
        waitingPref.isVisible = true
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menu.add(Menu.NONE, MENU_PAUSE, 0, if (paused) R.string.logs_resume else R.string.logs_pause)
            .setIcon(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, MENU_CLEAR, 1, R.string.logs_clear)
            .setIcon(R.drawable.ic_clear)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
        MENU_PAUSE -> {
            paused = !paused
            requireActivity().invalidateMenu()
            true
        }
        MENU_CLEAR -> {
            clearLines()
            true
        }
        else -> false
    }

    companion object {
        private const val KEY_LEVEL = "log_level"
        private const val DEFAULT_LEVEL = "info"
        private const val STATE_PAUSED = "paused"
        private const val MAX_LINES = 300
        private const val FLUSH_MS = 500L
        private const val RETRY_MS = 3000L
        private const val MENU_PAUSE = 1
        private const val MENU_CLEAR = 2
    }
}
