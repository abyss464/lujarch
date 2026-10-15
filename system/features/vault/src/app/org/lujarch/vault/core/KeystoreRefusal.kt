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
 * What a page's open does after the Keystore refused the vault's wrapping key
 * (VLT-1). The key works only while the phone is unlocked, so a
 * refusal on a locked phone is the locked state, not a failure: the source
 * waits and opens once the phone is unlocked. A page can be visible then: on a
 * display other than the phone's screen (the UI tests' virtual display), or
 * started by the unlock itself before the Keystore has heard of it
 * (TrustManagerService tells the Keystore from its own handler after the
 * keyguard state changed). A refusal on an unlocked phone is tried once more a
 * moment later (the same unlock, seen as unlocked a little before the Keystore
 * is told); a second one is a failure, shown as such.
 */
object KeystoreRefusal {
    enum class Next {
        /** The phone is locked: the source waits for the unlock, and pages say "Locked". */
        WAIT_FOR_UNLOCK,

        /** Unlocked, first refusal: opened again after [RETRY_MS]. */
        RETRY,

        /** Unlocked, refused again: the open failed. */
        FAIL,
    }

    /** How often a waiting source checks for the unlock, and the delay of the one retry. */
    const val RETRY_MS = 1_000L

    /** [retried]: the source's previous open was refused on an unlocked phone and this is its retry. */
    fun next(deviceLocked: Boolean, retried: Boolean): Next = when {
        deviceLocked -> Next.WAIT_FOR_UNLOCK
        !retried -> Next.RETRY
        else -> Next.FAIL
    }
}
