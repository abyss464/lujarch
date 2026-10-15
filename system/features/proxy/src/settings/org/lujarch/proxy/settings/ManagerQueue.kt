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

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The mihomo-manager requests of one process, one at a time. the proxy daemon runs one
 * manager command at a time and answers every other request "busy" at once
 * (commands.cpp, CmdMihomoManager), so pages that each asked the proxy daemon again
 * every second while another page's command ran kept it answering "busy" in
 * a storm and took its turns in no order. Here they wait in line in the app
 * (a fair [Mutex]) and only the first in line asks the proxy daemon. A "busy" answer
 * then means a command this process no longer waits for (its page went away
 * and the proxy daemon finishes it) or another client's: it is asked again after a
 * pause that doubles from [firstPauseMs] up to [maxPauseMs]. The wait in
 * line and the pauses together are bounded by [boundMs]; after it the call
 * fails with [ProxyException.Kind.BUSY], which the pages show as "busy".
 * Cancelling a caller takes it out of the line.
 *
 * Pure Kotlin (no Android calls) so that tests/run.sh runs it on the host.
 */
class ManagerQueue(
    private val send: suspend (args: List<String>, input: ByteArray?) -> ManagerResult,
    private val boundMs: Long = 30_000L,
    private val firstPauseMs: Long = 250L,
    private val maxPauseMs: Long = 4_000L,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val turn = Mutex()

    suspend fun run(args: List<String>, input: ByteArray? = null): ManagerResult {
        val started = now()
        val deadline = started + boundMs
        withTimeoutOrNull(boundMs) { turn.lock() }
            ?: throw ProxyException(
                ProxyException.Kind.BUSY, describe(args),
                "waited ${boundMs / 1000} s behind this app's other proxy commands",
            )
        try {
            var pause = firstPauseMs
            var refused = 0
            while (true) {
                try {
                    val result = send(args, input)
                    if (refused > 0) log("${describe(args)}: through after $refused busy answer(s), ${now() - started} ms")
                    return result
                } catch (e: ProxyException) {
                    if (e.kind != ProxyException.Kind.BUSY) throw e
                    refused++
                    val left = deadline - now()
                    if (left <= 0) {
                        log("${describe(args)}: the proxy daemon still busy after $refused answer(s), ${now() - started} ms; giving up")
                        throw e
                    }
                    // A little jitter, so that two processes (or a restarted page and
                    // a task) do not keep asking in step.
                    val wait = minOf(left, pause + Random.nextLong(pause / 8 + 1))
                    delay(wait)
                    pause = minOf(pause * 2, maxPauseMs)
                }
            }
        } finally {
            turn.unlock()
        }
    }

    private fun describe(args: List<String>) = "mihomo.manager " + args.joinToString(" ")
}

/**
 * Identical reads share one run: a caller that asks for [key] while a run
 * for it is under way waits for that run's result (or failure) instead of
 * starting its own. A finished run is forgotten at once, so a caller never
 * gets a result older than its own request. The run belongs to its callers
 *: it runs in [scope] only so that one caller's cancellation does not
 * cancel the others', and it is cancelled when the last of them is.
 */
class SharedRuns(private val scope: CoroutineScope) {
    private class Run(val result: Deferred<Any?>) {
        var waiters = 0
    }

    private val runs = HashMap<Any, Run>()

    /** How many runs are under way (tests). */
    val active: Int
        get() = synchronized(runs) { runs.size }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: Any, block: suspend () -> T): T {
        val run = synchronized(runs) {
            val r = runs[key] ?: Run(scope.async { block() }).also { runs[key] = it }
            r.waiters++
            r
        }
        try {
            return run.result.await() as T
        } finally {
            synchronized(runs) {
                run.waiters--
                if (run.result.isCompleted || run.waiters == 0) {
                    if (runs[key] === run) runs.remove(key)
                }
                if (run.waiters == 0 && !run.result.isCompleted) run.result.cancel()
            }
        }
    }
}

/**
 * The manager commands that only read (their output depends on the files and
 * the core, and they change nothing): identical ones share a run
 * ([SharedRuns]). Commands that write, and those that take input, never do.
 */
object ManagerReads {
    fun isRead(args: List<String>): Boolean {
        if (args.isEmpty()) return false
        val n = args.size
        val json = args.last() == "--json"
        return when (args[0]) {
            "status" -> args.drop(1).all { it == "--offline" || it == "--json" }
            "classify" -> n == 1 || (n == 2 && json)
            "files", "backups" -> n == 2 && json
            "groups" -> n == 1
            "config" -> n == 3 && args[1] == "get" && json
            "rule" -> n >= 2 && args[1] in setOf("list", "sets") && (n == 2 || json)
            "custom-group" -> n >= 2 && args[1] == "list" && (n == 2 || (n == 3 && json))
            "provider" -> (n >= 2 && args[1] == "list" && (n == 2 || (n == 3 && json))) ||
                (n == 4 && args[1] == "nodes" && json)
            "app" -> n >= 2 && args[1] in setOf("list", "targets") && (n == 2 || (n == 3 && json))
            else -> false
        }
    }
}
