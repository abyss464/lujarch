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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.lujarch.installer.sheet

import android.Manifest
import android.app.usage.StorageStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.os.storage.StorageManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.theme.PlatformTheme
import kotlinx.coroutines.launch
import org.lujarch.installer.TAG

/** Uninstalling an app with the owner's options (INS-7). */
class UninstallActivity : ComponentActivity() {
    private class Target(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val version: String?,
        val data: Long,
        val systemUpdate: Boolean,
        val archivable: Boolean,
        val user: UserHandle,
    )

    private var shown by mutableStateOf(true)
    private var keepData by mutableStateOf(false)
    private var allUsers by mutableStateOf(false)
    private var archive by mutableStateOf(false)
    private var working by mutableStateOf(false)
    private var failure by mutableStateOf<Outcome.Failed?>(null)
    private var succeeded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addSystemFlags(WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val target = target() ?: run {
            finish()
            return
        }
        allUsers = intent.getBooleanExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, false)
        setContent {
            PlatformTheme {
                val scope = rememberCoroutineScope()
                BackHandler(enabled = shown && !working) { shown = false }
                SheetFrame(visible = shown, dismissable = !working, onDismiss = { shown = false }, onGone = ::done) {
                    Sheet(target) {
                        working = true
                        failure = null
                        scope.launch {
                            val installer = Installer(applicationContext, target.user)
                            val flags =
                                (if (keepData) PackageManager.DELETE_KEEP_DATA else 0) or
                                    (if (allUsers) PackageManager.DELETE_ALL_USERS else 0)
                            val out = if (archive) installer.archive(target.packageName) else installer.uninstall(target.packageName, flags)
                            working = false
                            when (out) {
                                is Outcome.Installed -> {
                                    succeeded = true
                                    shown = false
                                }
                                is Outcome.Failed -> failure = out
                            }
                        }
                    }
                }
            }
        }
    }

    /** The app to uninstall, if the caller may ask for it (Android asks REQUEST_DELETE_PACKAGES). */
    private fun target(): Target? {
        val pkg = intent.data?.schemeSpecificPart
        if (pkg.isNullOrEmpty()) {
            Log.e(TAG, "uninstall: no package in $intent")
            return null
        }
        val self = launchedFromUid == Process.myUid()
        val caller = if (self) intent.getIntExtra(InstallActivity.EXTRA_CALLING_UID, Process.INVALID_UID) else launchedFromUid
        // As Package installer asks: apps made for Android 9 or later need one of the permissions.
        val legacy =
            packageManager.getPackagesForUid(caller).orEmpty().any {
                try {
                    packageManager.getApplicationInfo(it, PackageManager.ApplicationInfoFlags.of(0)).targetSdkVersion < Build.VERSION_CODES.P
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            }
        val may =
            caller == Process.myUid() || legacy ||
                checkPermission(Manifest.permission.REQUEST_DELETE_PACKAGES, -1, caller) == PackageManager.PERMISSION_GRANTED ||
                checkPermission(Manifest.permission.DELETE_PACKAGES, -1, caller) == PackageManager.PERMISSION_GRANTED
        if (caller == Process.INVALID_UID || !may) {
            Log.e(TAG, "uninstall of $pkg refused: uid $caller holds neither REQUEST_DELETE_PACKAGES nor DELETE_PACKAGES")
            return null
        }
        val user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java) ?: Process.myUserHandle()
        if (user != Process.myUserHandle() && getSystemService(UserManager::class.java).userProfiles.none { it == user }) {
            Log.e(TAG, "uninstall of $pkg refused: user $user is not this user's")
            return null
        }
        val pm = createContextAsUser(user, 0).packageManager
        val info: ApplicationInfo =
            try {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES))
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "uninstall: $pkg is not installed", e)
                return null
            }
        val version =
            try {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        val data =
            try {
                getSystemService(StorageStatsManager::class.java).queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user).dataBytes
            } catch (e: Exception) {
                Log.w(TAG, "uninstall: data size of $pkg not known", e)
                -1L
            }
        val archivable =
            try {
                pm.isAppArchivable(pkg)
            } catch (e: Exception) {
                false
            }
        return Target(
            pkg, info.loadLabel(pm).toString(), info.loadIcon(pm), version, data,
            info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0, archivable, user,
        )
    }

    @Composable
    private fun Sheet(t: Target, onGo: () -> Unit) {
        val context = this
        val title =
            when {
                working -> stringResource(if (archive) R.string.ins_archiving else R.string.ins_uninstalling)
                failure != null -> stringResource(if (archive) R.string.ins_not_archived else R.string.ins_not_uninstalled)
                t.systemUpdate -> stringResource(R.string.ins_uninstall_update)
                else -> stringResource(R.string.ins_uninstall)
            }
        val name = listOfNotNull(t.label, t.version).joinToString(" ")
        val sub = if (t.data >= 0) stringResource(R.string.ins_uninstall_sub, name, Texts.size(context, t.data)) else name
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 16.dp),
        ) {
            AppBadge(t.icon, if (failure != null) BadgeLook.FAILED else BadgeLook.APP)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(title, style = InsText.title, color = MaterialTheme.colorScheme.onSurface)
                Text(sub, style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val f = failure
        when {
            working ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            f != null ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    if (!f.message.isNullOrBlank()) {
                        Text(f.message, style = InsText.t1.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (f.legacy != 0) {
                        Row {
                            Tag(PackageManager.deleteStatusToString(f.legacy), TagLook.BAD, mono = true)
                        }
                    }
                }
            else -> {
                if (t.systemUpdate) SheetRow(stringResource(R.string.ins_system_update), null)
                SwitchRow(stringResource(R.string.ins_keep_data), stringResource(R.string.ins_keep_data_summary), keepData || archive, enabled = !archive) { keepData = it }
                SwitchRow(
                    stringResource(R.string.ins_uninstall_all_users),
                    stringResource(R.string.ins_uninstall_all_users_summary),
                    allUsers && !archive,
                    enabled = !archive,
                ) { allUsers = it }
                SwitchRow(
                    stringResource(R.string.ins_archive_instead),
                    stringResource(R.string.ins_archive_instead_summary),
                    archive,
                    enabled = t.archivable && !t.systemUpdate,
                ) { archive = it }
            }
        }
        SheetSpacer()
        SheetActions {
            if (failure != null) {
                SheetButton(stringResource(R.string.ins_close), Look.OUTLINE, grow = true) { shown = false }
            } else if (!working) {
                SheetButton(stringResource(R.string.ins_cancel), Look.OUTLINE) { shown = false }
                SheetButton(
                    stringResource(
                        when {
                            archive -> R.string.ins_archive
                            t.systemUpdate -> R.string.ins_uninstall_update
                            else -> R.string.ins_uninstall
                        }
                    ),
                    Look.FILL,
                    onClick = onGo,
                )
            }
        }
    }

    private fun done() {
        if (intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
            val legacy = if (succeeded) PackageManager.DELETE_SUCCEEDED else failure?.legacy ?: PackageManager.DELETE_FAILED_ABORTED
            setResult(if (succeeded) RESULT_OK else RESULT_FIRST_USER, Intent().putExtra(Intent.EXTRA_INSTALL_RESULT, legacy))
        }
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}
