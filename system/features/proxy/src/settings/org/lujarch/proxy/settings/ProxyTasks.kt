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

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lujarch.settings.pages.Failure
import org.lujarch.settings.pages.OutlivesPage

/**
 * Proxy changes started from the pages (a subscription added, a file
 * imported, a rule set turned off, the configuration applied, ...). Each runs
 * in the process scope, so leaving the page does not abort it, one at a
 * time; pages observe [tasks] by key. A task's steps say what it is doing;
 * its result keeps the parsed outcome and the raw manager output, shown only
 * behind "Detailed output".
 */
object ProxyTasks {
    enum class State { RUNNING, DONE, FAILED }

    data class Task(
        /** What the task acts on: "config", "provider:NAME", "files", "rules", ... */
        val key: String,
        /** Localized name of the change, for status rows. */
        val title: String,
        val state: State,
        /** Localized current step. */
        val step: String? = null,
        val startedAt: Long,
        val finishedAt: Long = 0,
        /** Localized outcome. */
        val summary: String? = null,
        val error: Throwable? = null,
        /** Raw manager output of the task's commands, in order (detailed output). */
        val output: String? = null,
        /** Parsed outcome: [ConfigResult], [ImportReport], ... */
        val data: Any? = null,
        /** Outcomes of steps before the last ([ProbeResult], [LocateFailed]). */
        val notes: List<Any> = emptyList(),
        /** The configuration was rebuilt and the core uses it. */
        val applied: Boolean = false,
    ) {
        val running: Boolean
            get() = state == State.RUNNING
    }

    /** What a task's work reports while it runs. */
    class Scope internal constructor(private val key: String, val context: Context) {
        fun step(text: String) = update(key) { it.copy(step = text) }
        fun summary(text: String) = update(key) { it.copy(summary = text) }
        /** Adds a command's raw output to the task's detailed output. */
        fun output(text: String) = update(key) { it.copy(output = joinOutput(it.output, text)) }
        fun data(value: Any?) = update(key) { it.copy(data = value) }
        fun note(value: Any) = update(key) { it.copy(notes = it.notes + value) }

        /**
         * Rebuilds the configuration and reloads the core (validate, install,
         * reload, health check, rollback on failure). A failure throws
         * [ConfigFailedException] with the result.
         */
        suspend fun applyConfig() {
            step(context.getString(R.string.px_step_applying))
            val (result, output) = ProxyManager.build()
            output(output)
            data(result)
            if (!result.ok) throw ConfigFailedException(result)
            update(key) { it.copy(applied = true) }
        }
    }

    /** `build` failed; the previous configuration stays in use. */
    class ConfigFailedException(val result: ConfigResult) :
        Exception(result.error ?: "build failed"), Failure {
        override fun describe(context: Context): String = ProxyErrors.describe(context, this)

        override val details: String?
            get() = ProxyErrors.rawText(this)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow<Map<String, Task>>(emptyMap())

    /** Latest task per key. */
    val tasks: StateFlow<Map<String, Task>> = state

    fun running(): Task? = state.value.values.firstOrNull { it.running }

    operator fun get(key: String): Task? = state.value[key]

    private fun update(key: String, change: (Task) -> Task) {
        state.update { map -> map[key]?.let { map + (key to change(it)) } ?: map }
    }

    /**
     * Starts [work] under [key] unless a task is running; returns whether it
     * started. The work's exceptions end the task as failed (logged).
     */
    fun start(context: Context, key: String, title: String, @OutlivesPage work: suspend Scope.() -> Unit): Boolean {
        if (running() != null) return false
        val app = context.applicationContext
        val startedAt = System.currentTimeMillis()
        state.update { it + (key to Task(key, title, State.RUNNING, startedAt = startedAt)) }
        scope.launch {
            val s = Scope(key, app)
            try {
                s.work()
                update(key) { it.copy(state = State.DONE, step = null, finishedAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "proxy task $key ($title) failed: ${e.message}", e)
                update(key) {
                    // The failing command's own text (English) stays behind "Detailed output".
                    val raw = ProxyErrors.rawText(e)?.takeIf { r -> it.output?.contains(r) != true }
                    it.copy(
                        state = State.FAILED,
                        error = e,
                        output = if (raw != null) joinOutput(it.output, raw) else it.output,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
        return true
    }

    private fun joinOutput(before: String?, text: String): String =
        if (before.isNullOrBlank()) text else before.trimEnd() + "\n\n" + text

    /** Forgets a finished task (its status row goes away). */
    fun clear(key: String) {
        state.update { map -> if (map[key]?.running == true) map else map - key }
    }
}
