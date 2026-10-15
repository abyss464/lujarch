package org.lujarch.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.UserHandle
import android.util.Log

/**
 * content://org.lujarch.settings.appinfo/<package>?user=<id>: the App info rows every feature
 * contributes for one app, in the columns Settings reads.
 */
class AppInfoRowsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val packageName = uri.lastPathSegment ?: return cursor
        val userId = uri.getQueryParameter("user")?.toIntOrNull() ?: UserHandle.myUserId()
        val context = context ?: return cursor
        for (contribution in Contributions.all(context)) {
            try {
                for (row in contribution.appInfoRows(context, packageName, userId)) {
                    cursor.addRow(arrayOf(row.key, row.title, row.summary, row.action))
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "${contribution.javaClass.name} failed for $packageName", e)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    private companion object {
        const val TAG = "lujarch_core"
        val COLUMNS = arrayOf("key", "title", "summary", "action")
    }
}
