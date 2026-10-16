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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Every mihomo-manager request of Lujarch Settings goes through here: one at a
 * time per process ([ManagerQueue]), and identical reads ([ManagerReads])
 * share one run ([SharedRuns]): the node locations (`classify --json`) that
 * the locations page, a subscription's page and their recreations ask for
 * at once run once. Pages that call [run] from their view scope stop waiting
 * with it; the shared run stops when no page waits for it any more.
 */
object ManagerCalls {
    private val queue = ManagerQueue(
        send = { args, input -> ProxyClient.manager(args, input = input) },
        log = { Log.i(TAG, it) },
    )

    // Runs on Default: the shared run is not page code, and the JSON of a large
    // output is read off the main thread.
    private val shared = SharedRuns(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    suspend fun run(args: List<String>, input: ByteArray? = null): ManagerResult =
        if (input == null && ManagerReads.isRead(args)) {
            shared.get(args) { queue.run(args) }
        } else {
            queue.run(args, input)
        }

    /** A request of the manager that is not a plain command (an export), in the same line. */
    suspend fun <T> inLine(what: String, call: suspend () -> T): T = queue.inLine(what, call)
}
