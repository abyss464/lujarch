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

import java.net.IDN
import java.util.Locale
import org.lujarch.vault.core.PasswordEntry

/**
 * Which password entries belong to an app or a web page.
 *
 * Apps: `androidapp://<package>` in the URL field or in KeePass2Android's
 * `KP2A_URL*` fields, or the package in KeePassDX's `AndroidApp*` fields.
 * Web pages: the host of the URL field and of web URLs in `KP2A_URL*` fields;
 * the page's domain must be that host or a subdomain of it (or, with
 * subdomains off, that host only; a leading "www." is ignored). The two are kept
 * apart: a page in a browser is only matched by its web domain, never by the
 * browser's package, so an app entry is never offered to a web page.
 */
object EntryMatcher {
    private const val APP_SCHEME = "androidapp://"

    fun appPackages(entry: PasswordEntry): Set<String> {
        val out = LinkedHashSet<String>()
        for (url in urls(entry)) {
            if (url.startsWith(APP_SCHEME, ignoreCase = true)) {
                packageName(url.substring(APP_SCHEME.length))?.let { out.add(it) }
            }
        }
        for (f in entry.fields) {
            if (f.name.startsWith("AndroidApp", ignoreCase = true)) {
                val v = f.value().trim()
                val pkg = if (v.startsWith(APP_SCHEME, ignoreCase = true)) v.substring(APP_SCHEME.length) else v
                packageName(pkg)?.let { out.add(it) }
            }
        }
        return out
    }

    fun webHosts(entry: PasswordEntry): Set<String> {
        val out = LinkedHashSet<String>()
        for (url in urls(entry)) {
            if (url.startsWith(APP_SCHEME, ignoreCase = true)) continue
            hostOf(url)?.let { out.add(it) }
        }
        return out
    }

    fun matchApp(entries: List<PasswordEntry>, packageName: String): List<PasswordEntry> =
        entries.filter { packageName in appPackages(it) }

    fun matchWeb(entries: List<PasswordEntry>, webDomain: String, subdomains: Boolean = true): List<PasswordEntry> {
        val domain = normalizeHost(webDomain) ?: return emptyList()
        return entries.filter { e ->
            webHosts(e).any { if (subdomains) domainMatches(domain, it) else sameHost(domain, it) }
        }
    }

    /** [domain] and [host] are the same host, a leading "www." on either side aside. */
    fun sameHost(domain: String, host: String): Boolean {
        if (host.isEmpty() || !host.contains('.') && host != "localhost") return false
        return withoutWww(domain) == withoutWww(host)
    }

    private fun withoutWww(host: String): String =
        if (host.startsWith("www.") && host.length > 4 && host.substring(4).contains('.')) host.substring(4) else host

    /**
     * Whether [domain] is covered by one of [sites] (the sites the user never
     * wants filled), each with the scope it was given or with [default] where
     * it has none: with [ExclusionScope.HOST] the host alone (its "www." form
     * included, as "Same host only" matching treats the two as one host), with
     * [ExclusionScope.SITE] its [siteHost] and every subdomain of it.
     */
    fun isExcluded(domain: String, sites: Collection<SiteExclusion>, default: ExclusionScope): Boolean {
        val d = normalizeHost(domain) ?: return false
        return sites.any { site ->
            when (site.scope.resolve(default)) {
                ExclusionScope.HOST -> sameHost(d, site.host)
                else -> domainMatches(d, siteHost(site.host))
            }
        }
    }

    /**
     * The site a host belongs to: the host without a leading "www.", the same
     * equivalence matching uses (www.example.com and example.com are one site
     * by convention). The phone carries no public suffix list, so a host is
     * never reduced further; the whole site of a registrable domain is
     * excluded by entering that domain.
     */
    fun siteHost(host: String): String = withoutWww(normalizeHost(host) ?: host.trim().lowercase(Locale.ROOT))

    /** A host as the exclusion list stores it (lower case, IDN in ASCII), from a typed host or URL; null if none. */
    fun normalizeTypedHost(text: String): String? {
        val t = text.trim()
        return if (t.contains("://")) hostOf(t) else hostOf("https://$t")
    }

    /**
     * [domain] equals [host] or is a subdomain of it. An entry host starting
     * with "www." also matches the bare domain (www.example.com and
     * example.com are the same site by convention, and entries saved from a
     * browser often carry the prefix), but not the bare domain's other
     * subdomains.
     */
    fun domainMatches(domain: String, host: String): Boolean {
        if (host.isEmpty() || !host.contains('.') && host != "localhost") return false
        if (domain == host || domain.endsWith(".$host")) return true
        return host.startsWith("www.") && host.length > 4 && domain == host.substring(4)
    }

    /** Host of a URL with or without scheme, lower case, without port and trailing dot; null if none. */
    fun hostOf(url: String): String? {
        var s = url.trim()
        if (s.isEmpty()) return null
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            val name = s.substring(0, scheme).lowercase(Locale.ROOT)
            // Only web schemes carry a host we can compare with a page's domain.
            if (name != "http" && name != "https") return null
            s = s.substring(scheme + 3)
        } else if (s.contains(':') && !s.substringBefore(':').contains('.') &&
            s.substringAfter(':').firstOrNull()?.isDigit() != true) {
            // Another scheme without "//" (mailto:, cmd://-like values): no host.
            return null
        }
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) s = s.substring(0, end)
        s = s.substringAfterLast('@')
        if (s.startsWith("[")) return null
        s = s.substringBefore(':')
        return normalizeHost(s)
    }

    private fun normalizeHost(host: String): String? {
        val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (h.isEmpty() || h.any { it.isWhitespace() || it == '*' }) return null
        return try {
            IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun urls(entry: PasswordEntry): List<String> {
        val out = ArrayList<String>()
        entry.url.takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }
        for (f in entry.fields) {
            if (f.name.startsWith("KP2A_URL", ignoreCase = true)) {
                f.value().takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }
            }
        }
        return out
    }

    /** A plausible Android package name (letters, digits, underscores, at least one dot). */
    fun packageName(value: String): String? {
        val v = value.trim().trimEnd('/')
        if (!v.contains('.')) return null
        val ok = v.split('.').all { part ->
            part.isNotEmpty() && (part[0].isLetter() || part[0] == '_') && part.all { it.isLetterOrDigit() || it == '_' }
        }
        return if (ok) v else null
    }
}
