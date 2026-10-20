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

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.compose.theme.PlatformTheme
import java.io.File
import org.lujarch.installer.TAG

/**
 * The installer's sheet for files opened or shared from any app, or chosen with Install from files
 * (INS-1 to INS-5).
 */
class InstallActivity : ComponentActivity() {
    private var work by mutableStateOf<Work?>(null)
    private var shown by mutableStateOf(true)
    private var trusted = false

    private val pick =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) {
                finish()
                return@registerForActivityResult
            }
            trusted = true
            start(sources(uris), Origin(Process.myUid(), packageName, Process.INVALID_UID, null, null, null), false)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addSystemFlags(WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val self = launchedFromUid == Process.myUid()
        val again = Work.find(savedInstanceState?.getString(EXTRA_WORK) ?: intent.getStringExtra(EXTRA_WORK).takeIf { self })
        when {
            again != null -> {
                again.inBackground = false
                trusted = savedInstanceState?.getBoolean(STATE_TRUSTED) ?: true
                work = again
            }
            savedInstanceState != null -> {
                Log.i(TAG, "the install this sheet showed is gone with its process")
                finish()
                return
            }
            intent.action == ACTION_INSTALL_FROM_FILES -> pick.launch(MIME_TYPES)
            intent.action == PackageInstaller.ACTION_CONFIRM_INSTALL -> fromSession()
            else -> fromIntent(self)
        }
        setContent {
            PlatformTheme {
                val w = work
                if (w != null) {
                    InstallSheet(
                        work = w,
                        visible = shown,
                        onClose = { shown = false },
                        onGone = ::done,
                        onUnknownSettings = { pkg ->
                            try {
                                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", pkg, null)))
                            } catch (e: Exception) {
                                Log.e(TAG, "Install unknown apps of $pkg not opened", e)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The owner may have allowed Install unknown apps in Settings meanwhile.
        work?.let { w ->
            if (w.gate is Gate.UnknownSource) {
                w.gate = gate(this, w.origin, trusted)
                if (w.gate == null && w.reading == null) w.read()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        work?.let { outState.putString(EXTRA_WORK, it.id) }
        outState.putBoolean(STATE_TRUSTED, trusted)
    }

    private fun fromIntent(self: Boolean) {
        val uris =
            when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> listOfNotNull(intent.data)
            }.filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isEmpty()) {
            Log.e(TAG, "nothing to install in ${intent.action}")
            finish()
            return
        }
        val callingUid = if (self) intent.getIntExtra(EXTRA_CALLING_UID, Process.INVALID_UID) else launchedFromUid
        val callingPackage = if (self) intent.getStringExtra(EXTRA_CALLING_PACKAGE) else launchedFromPackage
        if (callingUid == Process.INVALID_UID) {
            Log.e(TAG, "the app that opened the installer is not known")
            finish()
            return
        }
        val documents = checkPermission(Manifest.permission.MANAGE_DOCUMENTS, -1, callingUid) == PackageManager.PERMISSION_GRANTED
        val downloads =
            packageManager.resolveContentProvider("downloads", PackageManager.ComponentInfoFlags.of(0))
                ?.applicationInfo?.let { it.isSystemApp && it.uid == callingUid } == true
        val originatingUid =
            if (documents || downloads) intent.getIntExtra(Intent.EXTRA_ORIGINATING_UID, Process.INVALID_UID) else callingUid
        val caller = callingPackage?.let {
            try {
                packageManager.getApplicationInfo(it, PackageManager.ApplicationInfoFlags.of(0))
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        trusted = caller?.isPrivilegedApp == true && intent.getBooleanExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)
        // Package installer keeps an installer of record a caller names only for itself, or from
        // an app allowed to install packages.
        val named =
            intent.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME)?.takeIf {
                it == callingPackage ||
                    (callingPackage != null &&
                        packageManager.checkPermission(Manifest.permission.INSTALL_PACKAGES, callingPackage) == PackageManager.PERMISSION_GRANTED)
            }
        val origin =
            Origin(
                callingUid = callingUid,
                callingPackage = callingPackage,
                originatingUid = originatingUid,
                originatingUri = intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI, Uri::class.java),
                referrer = intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java),
                installerPackageName = named,
            )
        start(sources(uris), origin, intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
    }

    /** Shows the files another app staged in its session, as that app staged them. */
    private fun fromSession() {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val info = packageManager.packageInstaller.getSessionInfo(id)
        val base = info?.resolvedBaseApkPath
        if (info == null || !info.isSealed || base == null) {
            Log.e(TAG, "session $id does not wait for the owner")
            finish()
            return
        }
        val dir = File(base).parentFile
        val files = dir?.listFiles { f -> f.name.endsWith(".apk") }?.sortedBy { it.name }.orEmpty().ifEmpty { listOf(File(base)) }
        val origin = Origin(info.installerUid, info.installerPackageName, info.installerUid, info.originatingUri, info.referrerUri, null)
        start(files.map { SourceFile(Uri.fromFile(it), it.name, it.length()) }, origin, false, id)
    }

    private fun start(sources: List<SourceFile>, origin: Origin, returnResult: Boolean, session: Int? = null) {
        val w = Work.create(this, sources, origin, returnResult, session)
        w.gate = gate(this, origin, trusted)
        if (w.gate == null) w.read()
        work = w
    }

    private fun sources(uris: List<Uri>): List<SourceFile> =
        uris.map { uri ->
            var name: String? = null
            var size = -1L
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$uri has no name or size", e)
            }
            SourceFile(uri, name ?: uri.lastPathSegment ?: uri.toString(), size)
        }

    private fun done() {
        val w = work
        if (w != null && w.returnResult) {
            val ok = w.legacyResult == PackageManager.INSTALL_SUCCEEDED
            setResult(if (ok) RESULT_OK else RESULT_FIRST_USER, Intent().putExtra(Intent.EXTRA_INSTALL_RESULT, w.legacyResult))
        }
        w?.close()
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    companion object {
        const val EXTRA_WORK = "org.lujarch.installer.extra.WORK"
        const val EXTRA_CALLING_UID = "org.lujarch.installer.extra.CALLING_UID"
        const val EXTRA_CALLING_PACKAGE = "org.lujarch.installer.extra.CALLING_PACKAGE"
        const val ACTION_INSTALL_FROM_FILES = "org.lujarch.installer.action.INSTALL_FROM_FILES"
        private const val STATE_TRUSTED = "trusted"
        private val MIME_TYPES =
            arrayOf(
                "application/vnd.android.package-archive", "application/x-apks", "application/xapk-package-archive",
                "application/vnd.apkm", "application/zip", "application/x-zip-compressed", "application/octet-stream",
            )

        /** Whether the files may be installed from where they came from, as Android asks (PERM-12). */
        fun gate(context: Context, origin: Origin, trusted: Boolean): Gate? {
            val um = context.getSystemService(UserManager::class.java)
            if (um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)) return Gate.Blocked
            if (trusted) return null
            if (um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
                um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
            ) {
                return Gate.Blocked
            }
            val uid = origin.originatingUid
            if (uid == Process.INVALID_UID) return null
            val pm = context.packageManager
            val pkgs = pm.getPackagesForUid(uid)
            if (pkgs.isNullOrEmpty()) {
                Log.e(TAG, "uid $uid that gave the files has no app")
                return Gate.Blocked
            }
            val infos =
                pkgs.mapNotNull {
                    try {
                        pm.getPackageInfo(it, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                }
            val first: PackageInfo = infos.firstOrNull() ?: return Gate.Blocked
            val label = first.applicationInfo?.loadLabel(pm)?.toString() ?: first.packageName
            val target = infos.maxOf { it.applicationInfo?.targetSdkVersion ?: 0 }
            val requests = infos.any { Manifest.permission.REQUEST_INSTALL_PACKAGES in it.requestedPermissions.orEmpty() }
            if (target >= Build.VERSION_CODES.O && !requests) {
                Log.e(TAG, "${first.packageName} does not request ${Manifest.permission.REQUEST_INSTALL_PACKAGES}")
                return Gate.NoRequest(label)
            }
            val ops = context.getSystemService(AppOpsManager::class.java)
            val op = AppOpsManager.permissionToOp(Manifest.permission.REQUEST_INSTALL_PACKAGES)!!
            return when (val mode = ops.noteOpNoThrow(op, uid, first.packageName, null, "installing from files")) {
                AppOpsManager.MODE_ALLOWED -> null
                else -> {
                    if (mode == AppOpsManager.MODE_DEFAULT) ops.setMode(op, uid, first.packageName, AppOpsManager.MODE_ERRORED)
                    Log.i(TAG, "${first.packageName} may not install unknown apps (mode $mode)")
                    Gate.UnknownSource(label, first.packageName)
                }
            }
        }
    }
}
