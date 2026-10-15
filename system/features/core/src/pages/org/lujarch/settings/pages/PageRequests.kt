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

package org.lujarch.settings.pages

/**
 * What a request to another activity (a document picker, a QR scanner, a
 * permission) is for while that activity is open: a few plain
 * strings (which row, which purpose, a name), never a secret. It is set
 * when the request starts, kept in the page's saved state (so the page that
 * gets the result, recreated or in a restarted process, knows what it is
 * for) and handed to the one result that comes back. No Android types: the
 * host tests run it; [PageRequest] keeps it in a fragment's saved state.
 */
class RequestSlot {
    private var fields: List<String>? = null

    /** A request is under way (set and not taken). */
    val pending: Boolean
        get() = fields != null

    /** The request starts: what its result is for (replaces an earlier one that never came back). */
    fun start(vararg values: String) {
        fields = values.toList()
    }

    /** For the saved state; null while no request is under way. */
    fun saved(): Array<String>? = fields?.toTypedArray()

    /** From the saved state of the page before (a recreation or a restarted process). */
    fun restore(saved: Array<String>?) {
        if (saved != null) fields = saved.toList()
    }

    /** The result came back: what it is for, once; null when no request was under way. */
    fun take(): List<String>? {
        val f = fields
        fields = null
        return f
    }
}

/**
 * Secret bytes a result brought (a picked file of node links, a pasted
 * link) while a page dialog asks what to do with them: the answer that uses
 * them [take]s them and wipes them when done; the dialog's end [wipe]s
 * whatever was not taken. Kept in memory only (the dialog's registry), never
 * in the saved state. Whichever comes first wins, once.
 */
class HeldBytes(bytes: ByteArray) {
    private var bytes: ByteArray? = bytes

    /** The size held (0 once taken or wiped). */
    @get:Synchronized
    val size: Int
        get() = bytes?.size ?: 0

    /** Hands the bytes over (the caller wipes them after use); null when taken or wiped already. */
    @Synchronized
    fun take(): ByteArray? {
        val b = bytes
        bytes = null
        return b
    }

    /** Overwrites the bytes unless they were taken; returns how many were wiped. */
    @Synchronized
    fun wipe(): Int {
        val b = bytes ?: return 0
        b.fill(0)
        bytes = null
        return b.size
    }
}
