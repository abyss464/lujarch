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

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * A [RequestSlot] kept in [fragment]'s saved state under [key].
 * Made with the fragment, next to the ActivityResult launcher it belongs to:
 * [start] it right before launching, [take] it in the launcher's callback.
 * A recreated page (and a page restored in a restarted process) gets the
 * result through its own launcher and its slot restored, so it knows what
 * the result is for. The values are plain strings, never a secret.
 */
class PageRequest(fragment: Fragment, key: String) {
    private val slot = RequestSlot()
    private val stateKey = "org.lujarch.settings.pages.request:$key"

    init {
        fragment.savedStateRegistry.registerSavedStateProvider(stateKey) {
            Bundle().apply { slot.saved()?.let { putStringArray(FIELDS, it) } }
        }
        // The fragment's saved state is restored before its ON_CREATE, and
        // launcher results arrive at ON_START at the earliest.
        fragment.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event != Lifecycle.Event.ON_CREATE) return
                source.lifecycle.removeObserver(this)
                slot.restore(fragment.savedStateRegistry.consumeRestoredStateForKey(stateKey)?.getStringArray(FIELDS))
            }
        })
    }

    fun start(vararg values: String) = slot.start(*values)

    /** What the result that came back is for; null when no request of this page was under way. */
    fun take(): List<String>? = slot.take()

    private companion object {
        const val FIELDS = "fields"
    }
}
