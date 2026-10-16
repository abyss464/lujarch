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

/**
 * One search result: opening it starts [action], which shows the page with [key] in view; in
 * [targetPackage], or in Lujarch Settings when it is empty.
 */
data class SearchEntry(
    val key: String,
    val title: String,
    val summary: String,
    val keywords: String,
    val screenTitle: String,
    val action: String,
    val targetPackage: String = "",
)

/**
 * A string of another app by its resource name, for entries of rows that app shows (its titles stay
 * in the app that holds them); null, logged, when the app or the string is missing.
 */
fun appString(context: Context, packageName: String, name: String): String? =
    try {
        val res = context.packageManager.getResourcesForApplication(packageName)
        val id = res.getIdentifier(name, "string", packageName)
        if (id == 0) android.util.Log.e("lujarch_core", "$packageName has no string $name")
        if (id != 0) res.getString(id) else null
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        android.util.Log.e("lujarch_core", "$packageName is not installed", e)
        null
    }
