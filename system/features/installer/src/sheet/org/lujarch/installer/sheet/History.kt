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

package org.lujarch.installer.sheet

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import org.lujarch.installer.InstallHistory
import org.lujarch.installer.TAG

/**
 * The install history of this user, in device-protected storage so that installs before the first
 * unlock are recorded too; the installed version of every app, to tell updates from downgrades.
 */
class HistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.createDeviceProtectedStorageContext(), "install_history.db", null, 1) {
    private val pm = context.packageManager

    /** The app whose addition opens the history first: it is recorded, not taken as there before. */
    @Volatile private var adding: String? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE installs (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "${InstallHistory.TIME} INTEGER NOT NULL, ${InstallHistory.PACKAGE} TEXT NOT NULL, " +
                "${InstallHistory.LABEL} TEXT, ${InstallHistory.VERSION_NAME} TEXT, " +
                "${InstallHistory.VERSION_CODE} INTEGER NOT NULL, ${InstallHistory.INSTALLER} TEXT, " +
                "${InstallHistory.KIND} INTEGER NOT NULL, ${InstallHistory.RESULT} INTEGER NOT NULL, " +
                "${InstallHistory.MESSAGE} TEXT)"
        )
        db.execSQL("CREATE INDEX installs_time ON installs (${InstallHistory.TIME} DESC)")
        db.execSQL("CREATE TABLE versions (package TEXT PRIMARY KEY, code INTEGER NOT NULL, updated INTEGER NOT NULL)")
        // The apps there are when the history begins, so that their first update is told apart.
        for (info in pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))) {
            if (info.packageName == adding) continue
            db.insertWithOnConflict(
                "versions", null,
                ContentValues().apply {
                    put("package", info.packageName)
                    put("code", info.longVersionCode)
                    put("updated", info.lastUpdateTime)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Records that [packageName] is installed now, once per install; false when it already was. */
    fun recordInstalled(packageName: String, replacing: Boolean): Boolean {
        val info =
            try {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "$packageName was added but is not installed", e)
                return false
            }
        adding = packageName
        val db = writableDatabase
        adding = null
        db.beginTransaction()
        try {
            val known =
                db.rawQuery("SELECT code, updated FROM versions WHERE package = ?", arrayOf(packageName)).use {
                    if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
                }
            if (known != null && known.second == info.lastUpdateTime) return false
            val kind =
                when {
                    !replacing -> InstallHistory.KIND_NEW
                    known == null || info.longVersionCode > known.first -> InstallHistory.KIND_UPDATE
                    info.longVersionCode < known.first -> InstallHistory.KIND_DOWNGRADE
                    else -> InstallHistory.KIND_REINSTALL
                }
            val installer =
                try {
                    pm.getInstallSourceInfo(packageName).installingPackageName
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "$packageName has no install source", e)
                    null
                }
            db.insertOrThrow(
                "installs", null,
                ContentValues().apply {
                    put(InstallHistory.TIME, info.lastUpdateTime)
                    put(InstallHistory.PACKAGE, packageName)
                    put(InstallHistory.LABEL, info.applicationInfo?.loadLabel(pm)?.toString())
                    put(InstallHistory.VERSION_NAME, info.versionName)
                    put(InstallHistory.VERSION_CODE, info.longVersionCode)
                    put(InstallHistory.INSTALLER, installer)
                    put(InstallHistory.KIND, kind)
                    put(InstallHistory.RESULT, 0)
                },
            )
            db.insertWithOnConflict(
                "versions", null,
                ContentValues().apply {
                    put("package", packageName)
                    put("code", info.longVersionCode)
                    put("updated", info.lastUpdateTime)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "history: $packageName ${info.versionName} installed by $installer")
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Records an install through the installer that failed (INS-5, INS-6). */
    fun recordFailure(app: AppFile, installer: String, legacy: Int, message: String?) {
        val kind =
            when (app.kind) {
                InstallKind.NEW -> InstallHistory.KIND_NEW
                InstallKind.UPDATE -> InstallHistory.KIND_UPDATE
                InstallKind.DOWNGRADE -> InstallHistory.KIND_DOWNGRADE
                InstallKind.SAME -> InstallHistory.KIND_REINSTALL
            }
        try {
            writableDatabase.insertOrThrow(
                "installs", null,
                ContentValues().apply {
                    put(InstallHistory.TIME, System.currentTimeMillis())
                    put(InstallHistory.PACKAGE, app.packageName)
                    put(InstallHistory.LABEL, app.label)
                    put(InstallHistory.VERSION_NAME, app.versionName)
                    put(InstallHistory.VERSION_CODE, app.versionCode)
                    put(InstallHistory.INSTALLER, installer)
                    put(InstallHistory.KIND, kind)
                    put(InstallHistory.RESULT, if (legacy == 0) PackageManager.INSTALL_FAILED_INTERNAL_ERROR else legacy)
                    put(InstallHistory.MESSAGE, message)
                },
            )
        } catch (e: SQLiteException) {
            Log.e(TAG, "history: the failure of ${app.packageName} not recorded", e)
        }
    }

    fun query(): Cursor =
        readableDatabase.query(
            "installs",
            arrayOf(
                InstallHistory.TIME, InstallHistory.PACKAGE, InstallHistory.LABEL,
                InstallHistory.VERSION_NAME, InstallHistory.VERSION_CODE, InstallHistory.INSTALLER,
                InstallHistory.KIND, InstallHistory.RESULT, InstallHistory.MESSAGE,
            ),
            null, null, null, null, "${InstallHistory.TIME} DESC, _id DESC",
        )

    fun clear(): Int = writableDatabase.delete("installs", null, null)

    companion object {
        @Volatile private var store: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            store ?: synchronized(this) { store ?: HistoryStore(context.applicationContext).also { store = it } }
    }
}

/** The history to Lujarch Settings: read with [InstallHistory.INSTALLS], cleared by deleting it. */
class HistoryProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        if (uri != InstallHistory.INSTALLS) return null
        return HistoryStore.get(context!!).query().apply { setNotificationUri(context!!.contentResolver, uri) }
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        if (uri != InstallHistory.INSTALLS) return 0
        val n = HistoryStore.get(context!!).clear()
        Log.i(TAG, "history cleared: $n installs")
        context!!.contentResolver.notifyChange(uri, null)
        return n
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** Every app installed or updated on the phone, into the history (INS-6). */
class HistoryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_ARCHIVAL, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val result = goAsync()
        Thread {
            try {
                if (HistoryStore.get(context).recordInstalled(pkg, intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))) {
                    context.contentResolver.notifyChange(InstallHistory.INSTALLS, null)
                }
            } catch (e: SQLiteException) {
                Log.e(TAG, "history: $pkg not recorded", e)
            } finally {
                result.finish()
            }
        }.start()
    }
}
