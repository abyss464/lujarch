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

package org.lujarch.vault.core.match

/**
 * How far an exclusion of a site reaches (VLT-8). The
 * choice is the user's: a site is one host or the whole site, and a site
 * without a scope of its own follows the one chosen for the list.
 */
enum class ExclusionScope(val key: String) {
    /** No scope of its own: the one chosen for the list applies. */
    DEFAULT("default"),

    /** That host alone, with or without a leading "www."; its subdomains are still filled. */
    HOST("host"),

    /** The host's site: its domain and every subdomain of it. */
    SITE("site"),
    ;

    /** This scope where the exclusion has one of its own, else [fallback]. */
    fun resolve(fallback: ExclusionScope): ExclusionScope = when {
        this != DEFAULT -> this
        fallback != DEFAULT -> fallback
        else -> SITE
    }

    companion object {
        fun fromKey(key: String?): ExclusionScope = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * A site the autofill service never fills, with the scope the user gave it.
 *
 * Stored as `<scope key>:<host>`, or as the bare host while the site follows
 * the scope of the list, which is what earlier versions stored: a host holds
 * no ":" (a port is cut off when it is read), so the two forms never collide
 * and an exclusion from before this choice keeps the reach it had.
 */
data class SiteExclusion(val host: String, val scope: ExclusionScope = ExclusionScope.DEFAULT) {

    fun stored(): String = if (scope == ExclusionScope.DEFAULT) host else "${scope.key}:$host"

    /**
     * The domain the exclusion is about, which both scopes state the same way
     * (the host without a leading "www."): with [ExclusionScope.HOST] that
     * domain and its "www." form, with [ExclusionScope.SITE] its subdomains too.
     */
    val domain: String
        get() = EntryMatcher.siteHost(host)

    companion object {
        /** An exclusion from its stored form; null when it holds no usable host. */
        fun parse(stored: String): SiteExclusion? {
            val mark = stored.indexOf(':')
            val key = if (mark > 0) stored.substring(0, mark) else ""
            val prefix = ExclusionScope.entries.firstOrNull { it.key == key }
            val text = if (prefix != null) stored.substring(mark + 1) else stored
            val host = EntryMatcher.normalizeTypedHost(text)?.takeIf { it.contains('.') } ?: return null
            return SiteExclusion(host, prefix ?: ExclusionScope.DEFAULT)
        }
    }
}
