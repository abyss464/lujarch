package org.lujarch.settings

import android.content.Context

/**
 * What a feature adds to Settings beyond its own pages. A feature registers its implementation
 * in its manifest as application meta-data named `org.lujarch.settings.contribution.<feature>`
 * whose value is the class name.
 */
interface Contribution {
    /** The rows App info shows for one app. */
    fun appInfoRows(context: Context, packageName: String, userId: Int): List<AppInfoRow> =
        emptyList()

    /** The pages and switches Settings search finds. */
    fun searchEntries(context: Context): List<SearchEntry> = emptyList()
}

/** One row in an app's App info: opening it starts [action] with the app's package name. */
data class AppInfoRow(val key: String, val title: String, val summary: String, val action: String)

/** One search result: opening it starts [action], which shows the page with [key] in view. */
data class SearchEntry(
    val key: String,
    val title: String,
    val summary: String,
    val keywords: String,
    val screenTitle: String,
    val action: String,
)
