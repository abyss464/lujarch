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

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A stream sent nothing for [waitedMs]: before its first value when [first],
 * else since its last one. The stream was closed.
 */
class StreamStalledException(val first: Boolean, val waitedMs: Long) :
    Exception(if (first) "no first value within $waitedMs ms" else "no value for $waitedMs ms")

/**
 * Collects this flow like `collect`, but a stream that stays silent is not
 * waited for without end: no first value within [firstMs], or no next value
 * within [gapMs] of the last, closes it (cancels the upstream, which closes
 * the the proxy daemon connection) and throws [StreamStalledException]. A stream that
 * ends returns; its failure is thrown.
 *
 * Pure Kotlin (no Android calls) so that tests/run.sh runs it on the host.
 */
suspend fun <T> Flow<T>.collectWithin(firstMs: Long, gapMs: Long, action: suspend (T) -> Unit) {
    coroutineScope {
        val values = this@collectWithin.produceIn(this)
        try {
            var first = true
            while (true) {
                val bound = if (first) firstMs else gapMs
                val next = withTimeoutOrNull(bound) { values.receiveCatching() }
                    ?: throw StreamStalledException(first, bound)
                if (next.isClosed) {
                    next.exceptionOrNull()?.let { throw it }
                    return@coroutineScope
                }
                action(next.getOrThrow())
                first = false
            }
        } finally {
            values.cancel()
        }
    }
}
