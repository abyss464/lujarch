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

package org.lujarch.vault.core

/**
 * A picked file of 2FA accounts while its password is asked for: the
 * password dialog's answer [take]s the bytes for the setup, which wipes
 * them when it is done; the dialog's end ([end], its onEnd) wipes whatever
 * was not taken: a cancel, a dismissal, and a page gone without an answer.
 * A recreation of the page keeps the dialog and so the bytes; a restarted
 * process has neither. Whichever comes first wins, once.
 */
class PendingImport(bytes: ByteArray) {
    private var bytes: ByteArray? = bytes

    /** The size held (0 once taken or wiped). */
    @get:Synchronized
    val size: Int
        get() = bytes?.size ?: 0

    /** Hands the bytes to the setup (which wipes them); null when taken or wiped already. */
    @Synchronized
    fun take(): ByteArray? {
        val b = bytes
        bytes = null
        return b
    }

    /** The dialog ended: overwrites the bytes unless the setup took them. Returns the log line. */
    @Synchronized
    fun end(): String {
        val b = bytes ?: return "2FA import: the password dialog ended; the picked file went to the setup"
        Bytes.wipe(b)
        bytes = null
        return "2FA import: the password dialog ended unanswered; the picked file's ${b.size} bytes wiped"
    }
}
