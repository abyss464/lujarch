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

package org.lujarch.vault.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.lujarch.vault.core.match.ExclusionScope
import org.lujarch.vault.core.match.SiteExclusion
import org.json.JSONObject

/**
 * Settings, the chosen sources and the non-secret facts shown on the state
 * page (file names, sizes, times, errors). Never holds a password, key or
 * entry value.
 */
class VaultPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    // ---- sources ----

    /**
     * The KeePass database the vault reads: a file in a synced folder or a
     * document with a persisted read grant. Kept when the keys are forgotten,
     * so that setting up again needs only the password.
     */
    var keepassSource: DbSource?
        get() = when (prefs.getString(KEY_SOURCE_KIND, null)) {
            KIND_DOCUMENT -> prefs.getString(KEY_DOC_URI, null)?.let {
                DbSource.Document(Uri.parse(it), prefs.getString(KEY_DOC_NAME, null) ?: "")
            }
            KIND_SYNCED -> synced()
            // Set up before the choice of folder existed: the one folder there was.
            else -> synced()
        }
        set(v) {
            val e = prefs.edit()
            when (v) {
                null -> e.remove(KEY_SOURCE_KIND).remove(KEY_FOLDER).remove(KEY_DB).remove(KEY_DOC_URI).remove(KEY_DOC_NAME)
                is DbSource.Synced -> e.putString(KEY_SOURCE_KIND, KIND_SYNCED).putString(KEY_FOLDER, v.folder)
                    .putString(KEY_DB, v.path).remove(KEY_DOC_URI).remove(KEY_DOC_NAME)
                is DbSource.Document -> e.putString(KEY_SOURCE_KIND, KIND_DOCUMENT).putString(KEY_DOC_URI, v.uri.toString())
                    .putString(KEY_DOC_NAME, v.name).remove(KEY_FOLDER).remove(KEY_DB)
            }
            e.apply()
        }

    private fun synced(): DbSource.Synced? {
        val path = prefs.getString(KEY_DB, null) ?: return null
        val folder = prefs.getString(KEY_FOLDER, null) ?: return null
        return DbSource.Synced(folder, path)
    }

    /** Whether the stored composite key includes a key file (shown on the state page). */
    var usesKeyFile: Boolean
        get() = prefs.getBoolean(KEY_KEYFILE, false)
        set(v) = prefs.edit().putBoolean(KEY_KEYFILE, v).apply()

    /** Name of the document the Aegis vault was imported from; empty when unknown (restored from a backup). */
    var aegisSourceName: String
        get() = prefs.getString(KEY_AEGIS_NAME, null) ?: ""
        set(v) = prefs.edit().putString(KEY_AEGIS_NAME, v).apply()

    /** A source that is set up but turned off is neither opened nor used for autofill; its keys stay. */
    var keepassEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEEPASS_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_KEEPASS_ENABLED, v).apply()

    var aegisEnabled: Boolean
        get() = prefs.getBoolean(KEY_AEGIS_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_AEGIS_ENABLED, v).apply()

    // ---- security ----

    var revealNeedsAuth: Boolean
        get() = prefs.getBoolean(KEY_REVEAL_AUTH, true)
        set(v) = prefs.edit().putBoolean(KEY_REVEAL_AUTH, v).apply()

    /** Copying a password or another protected field asks for a fingerprint or the screen lock first. */
    var copyNeedsAuth: Boolean
        get() = prefs.getBoolean(KEY_COPY_AUTH, false)
        set(v) = prefs.edit().putBoolean(KEY_COPY_AUTH, v).apply()

    /** Seconds after the last copy until the clipboard is cleared; 0: never. */
    var clipboardClearSeconds: Int
        get() = prefs.getInt(KEY_CLIPBOARD_SECONDS, -1).takeIf { it >= 0 }
            // The on/off setting of earlier versions: on meant 30 s.
            ?: if (prefs.getBoolean(KEY_CLEAR_CLIPBOARD_LEGACY, true)) 30 else 0
        set(v) = prefs.edit().putInt(KEY_CLIPBOARD_SECONDS, v).apply()

    var forgetPolicy: ForgetPolicy
        get() = ForgetPolicy.fromKey(prefs.getString(KEY_FORGET_POLICY, null))
        set(v) = prefs.edit().putString(KEY_FORGET_POLICY, v.key).apply()

    /** Boot count when the keys were stored or the forget policy was chosen. */
    var keysBoot: Int
        get() = prefs.getInt(KEY_KEYS_BOOT, -1)
        set(v) = prefs.edit().putInt(KEY_KEYS_BOOT, v).apply()

    /** Wall time of the last successful open of either source (or of setup). */
    var lastUsedAt: Long
        get() = prefs.getLong(KEY_LAST_USED, 0)
        set(v) = prefs.edit().putLong(KEY_LAST_USED, v).apply()

    // ---- autofill ----

    /** The pinned "Search the vault" suggestion after the matching entries. */
    var autofillSearchItem: Boolean
        get() = prefs.getBoolean(KEY_AUTOFILL_SEARCH, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOFILL_SEARCH, v).apply()

    /** Web pages match entries of their host and of parent domains (true), or of their host only. */
    var autofillSubdomains: Boolean
        get() = prefs.getBoolean(KEY_AUTOFILL_SUBDOMAINS, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOFILL_SUBDOMAINS, v).apply()

    var autofillExcludedPackages: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, v.toSet()).apply()

    /** The sites that are never filled, each with its scope; unreadable entries are dropped. */
    var autofillExcludedSites: List<SiteExclusion>
        get() = prefs.getStringSet(KEY_EXCLUDED_DOMAINS, null).orEmpty()
            .mapNotNull { SiteExclusion.parse(it) }.sortedBy { it.host }
        set(v) = prefs.edit().putStringSet(KEY_EXCLUDED_DOMAINS, v.map { it.stored() }.toSet()).apply()

    /**
     * How far a site without a scope of its own reaches: the whole site by
     * default, which is the reach the sites of earlier versions had (they are
     * stored without a scope, so they follow this setting).
     */
    var autofillExclusionScope: ExclusionScope
        get() = ExclusionScope.fromKey(prefs.getString(KEY_EXCLUDED_SCOPE, null)).resolve(ExclusionScope.SITE)
        set(v) = prefs.edit().putString(KEY_EXCLUDED_SCOPE, v.resolve(ExclusionScope.SITE).key).apply()

    // ---- last load facts ----

    fun status(source: Source): LoadStatus = LoadStatus.fromJson(prefs.getString(source.key, null))

    fun putStatus(source: Source, status: LoadStatus) {
        prefs.edit().putString(source.key, status.toJson()).apply()
    }

    /** After "forget keys": the setup facts go, the chosen sources and the settings stay. */
    fun clearSetup() {
        prefs.edit().remove(KEY_KEYFILE).remove(Source.KEEPASS.key).remove(Source.AEGIS.key).apply()
    }

    enum class Source(val key: String) {
        KEEPASS("status_keepass"),
        AEGIS("status_aegis"),
    }

    /** When the stored keys are deleted without being asked (VLT-1). */
    enum class ForgetPolicy(val key: String, val days: Int) {
        NEVER("never", 0),
        RESTART("restart", 0),
        IDLE_1D("idle_1d", 1),
        IDLE_7D("idle_7d", 7),
        IDLE_30D("idle_30d", 30);

        companion object {
            fun fromKey(key: String?): ForgetPolicy = entries.firstOrNull { it.key == key } ?: NEVER
        }
    }

    /** Facts of the last load of one source. */
    data class LoadStatus(
        val loadedAt: Long = 0,
        val fileSize: Long = -1,
        val fileMtime: Long = 0,
        val sha256: String = "",
        val entries: Int = -1,
        val format: String = "",
        val kdfMillis: Long = -1,
        val totalMillis: Long = -1,
        val error: String = "",
        val errorAt: Long = 0,
    ) {
        fun toJson(): String = JSONObject()
            .put("loadedAt", loadedAt).put("fileSize", fileSize).put("fileMtime", fileMtime)
            .put("sha256", sha256).put("entries", entries).put("format", format)
            .put("kdfMillis", kdfMillis).put("totalMillis", totalMillis)
            .put("error", error).put("errorAt", errorAt)
            .toString()

        companion object {
            fun fromJson(s: String?): LoadStatus {
                if (s == null) return LoadStatus()
                return try {
                    val o = JSONObject(s)
                    LoadStatus(
                        o.optLong("loadedAt"), o.optLong("fileSize", -1), o.optLong("fileMtime"),
                        o.optString("sha256"), o.optInt("entries", -1), o.optString("format"),
                        o.optLong("kdfMillis", -1), o.optLong("totalMillis", -1),
                        o.optString("error"), o.optLong("errorAt"),
                    )
                } catch (e: org.json.JSONException) {
                    LoadStatus()
                }
            }
        }
    }

    companion object {
        private const val KEY_SOURCE_KIND = "keepass_source"
        private const val KIND_SYNCED = "synced"
        private const val KIND_DOCUMENT = "document"
        private const val KEY_FOLDER = "keepass_folder"
        private const val KEY_DB = "keepass_database"
        private const val KEY_DOC_URI = "keepass_document"
        private const val KEY_DOC_NAME = "keepass_document_name"
        private const val KEY_KEYFILE = "keepass_uses_key_file"
        private const val KEY_AEGIS_NAME = "aegis_source_name"
        private const val KEY_KEEPASS_ENABLED = "keepass_enabled"
        private const val KEY_AEGIS_ENABLED = "aegis_enabled"
        private const val KEY_REVEAL_AUTH = "reveal_needs_auth"
        private const val KEY_COPY_AUTH = "copy_needs_auth"
        private const val KEY_CLEAR_CLIPBOARD_LEGACY = "clear_clipboard"
        private const val KEY_CLIPBOARD_SECONDS = "clipboard_clear_seconds"
        private const val KEY_FORGET_POLICY = "forget_policy"
        private const val KEY_KEYS_BOOT = "keys_boot"
        private const val KEY_LAST_USED = "last_used_at"
        private const val KEY_AUTOFILL_SEARCH = "autofill_search_item"
        private const val KEY_AUTOFILL_SUBDOMAINS = "autofill_subdomains"
        private const val KEY_EXCLUDED_PACKAGES = "autofill_excluded_packages"
        private const val KEY_EXCLUDED_DOMAINS = "autofill_excluded_domains"
        private const val KEY_EXCLUDED_SCOPE = "autofill_excluded_scope"
    }
}
