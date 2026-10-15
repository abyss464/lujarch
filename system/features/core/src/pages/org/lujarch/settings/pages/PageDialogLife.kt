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

import java.util.UUID

/**
 * The page dialogs of this process that have not ended, by token.
 * An entry keeps, in memory only, what a dialog needs across its page's
 * recreation: the builder that makes its window again with the recreated
 * page, and what runs once when it ends for good. Nothing of it goes into
 * the saved state, so a restarted process has none of them and drops the
 * dialogs the system restores. No Android types: the host tests run it.
 */
class PageDialogRegistry {
    class Entry internal constructor(
        val key: String,
        /** The page class that showed it; only a page of that class builds it again. */
        val page: String,
        /** The page's builder, `P.(DialogScope) -> Dialog?` (typed by PageDialog). */
        val build: Any,
        /** False: the dialog shows work of the page's view (a progress) and ends with it. */
        val restorable: Boolean,
        internal val onEnd: (() -> Unit)?,
    )

    /** Tokens of an earlier process never match an entry of this one. */
    private val session = UUID.randomUUID().toString().substring(0, 8)
    private var next = 0L
    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun add(key: String, page: String, build: Any, restorable: Boolean, onEnd: (() -> Unit)?): String {
        val token = "$session-${++next}"
        entries[token] = Entry(key, page, build, restorable, onEnd)
        return token
    }

    @Synchronized
    fun find(token: String): Entry? = entries[token]

    /**
     * Ends a dialog: removes its entry and runs its onEnd, once. False when
     * it had ended already or belongs to an earlier process.
     */
    fun end(token: String): Boolean {
        val e = synchronized(this) { entries.remove(token) } ?: return false
        e.onEnd?.invoke()
        return true
    }

    @get:Synchronized
    val size: Int
        get() = entries.size
}

/**
 * What one PageDialog fragment decides at its callbacks, apart from the
 * fragment so the host tests can drive every path. A dialog ends for good
 * once: closed (a button, Back, a touch outside, code), its page gone for
 * good (the activity finishing, the page removed), or not restorable when
 * the page is recreated. A recreation of the page keeps it for the next
 * fragment, which builds it again with the recreated page. A fragment the
 * system restores in a restarted process finds no entry: [lost].
 */
class PageDialogLife(private val registry: PageDialogRegistry, val token: String) {
    enum class End {
        /** A button, Back, a touch outside, or the page's code closed it. */
        CLOSED,
        /** Its page went away for good without an answer. */
        PAGE_GONE,
        /** It showed work of the page's view, which a recreation ends. */
        NOT_RESTORABLE,
        /** Nothing to build it from: a restarted process, another page, or the builder had nothing to show. */
        LOST,
    }

    /** How it ended; null while it lasts. */
    var end: End? = null
        private set

    /** The entry to build the window from for a page of class [page]; null (and ended as LOST) when there is none. */
    fun entry(page: String): PageDialogRegistry.Entry? {
        if (end != null) return null
        val e = registry.find(token)
        if (e != null && e.page == page) return e
        finish(End.LOST)
        return null
    }

    /** The builder had nothing to show (the page decided the dialog no longer applies). */
    fun declined(): Boolean = finish(End.LOST)

    /** The window was closed while its view stays (not a view going with its page). */
    fun closed(): Boolean = finish(End.CLOSED)

    /** The fragment is destroyed; [recreating]: the activity is recreated and a new page follows. */
    fun destroyed(recreating: Boolean): Boolean = when {
        end != null -> false
        !recreating -> finish(End.PAGE_GONE)
        registry.find(token)?.restorable == false -> finish(End.NOT_RESTORABLE)
        else -> false
    }

    private fun finish(why: End): Boolean {
        if (end != null) return false
        end = why
        registry.end(token)
        return true
    }
}
