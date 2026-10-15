package org.lujarch.settings

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

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

object Contributions {
    private const val TAG = "lujarch_core"
    private const val PREFIX = "org.lujarch.settings.contribution."

    fun all(context: Context): List<Contribution> {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
        val meta = info.metaData ?: return emptyList()
        return meta.keySet().filter { it.startsWith(PREFIX) }.sorted().mapNotNull { key ->
            val name = meta.getString(key)
            try {
                Class.forName(name).getDeclaredConstructor().newInstance() as Contribution
            } catch (e: ReflectiveOperationException) {
                Log.e(TAG, "cannot load contribution $key: $name", e)
                null
            } catch (e: ClassCastException) {
                Log.e(TAG, "$name is not a Contribution", e)
                null
            }
        }
    }
}
