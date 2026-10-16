package org.lujarch.settings

import android.database.Cursor
import android.database.MatrixCursor
import android.provider.SearchIndexablesContract.INDEXABLES_RAW_COLUMNS
import android.provider.SearchIndexablesContract.INDEXABLES_XML_RES_COLUMNS
import android.provider.SearchIndexablesContract.NON_INDEXABLES_KEYS_COLUMNS
import android.provider.SearchIndexablesContract.RawData
import android.provider.SearchIndexablesProvider
import android.util.Log

/** Settings search's entries for every feature's pages and switches. */
class SearchProvider : SearchIndexablesProvider() {
    override fun onCreate() = true

    override fun queryXmlResources(projection: Array<out String>?): Cursor =
        MatrixCursor(INDEXABLES_XML_RES_COLUMNS)

    override fun queryRawData(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(INDEXABLES_RAW_COLUMNS)
        val context = context ?: return cursor
        for (contribution in Contributions.all(context)) {
            try {
                for (entry in contribution.searchEntries(context)) {
                    cursor.newRow()
                        .add(RawData.COLUMN_KEY, entry.key)
                        .add(RawData.COLUMN_TITLE, entry.title)
                        .add(RawData.COLUMN_SUMMARY_ON, entry.summary)
                        .add(RawData.COLUMN_KEYWORDS, entry.keywords)
                        .add(RawData.COLUMN_SCREEN_TITLE, entry.screenTitle)
                        .add(RawData.COLUMN_INTENT_ACTION, entry.action)
                        .add(RawData.COLUMN_INTENT_TARGET_PACKAGE, entry.targetPackage.ifEmpty { context.packageName })
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "${contribution.javaClass.name} failed to list search entries", e)
            }
        }
        return cursor
    }

    override fun queryNonIndexableKeys(projection: Array<out String>?): Cursor =
        MatrixCursor(NON_INDEXABLES_KEYS_COLUMNS)

    private companion object {
        const val TAG = "lujarch_core"
    }
}
