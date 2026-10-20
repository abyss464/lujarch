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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.VersionedPackage
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.lujarch.installer.InstallOptions
import org.lujarch.installer.TAG

/** Who gave the installer its files, as Android records the source of an install. */
data class Origin(
    val callingUid: Int,
    val callingPackage: String?,
    /** The app the files came from: the caller, or the app the downloads or files app names. */
    val originatingUid: Int,
    /** Where the file came from, as the app that gave it says (Intent.EXTRA_ORIGINATING_URI). */
    val originatingUri: Uri?,
    val referrer: Uri?,
    /** The installer of record a caller names, kept only as Android keeps it in Package installer. */
    val installerPackageName: String?,
)

sealed interface Outcome {
    data class Installed(val packageName: String, val notes: List<String>) : Outcome

    data class Failed(val legacy: Int, val status: Int, val message: String?) : Outcome
}

/** What an install is doing now, for its progress (INS-4, INS-5). */
sealed interface Step {
    data class Copying(val done: Long, val total: Long) : Step

    data object Installing : Step

    data object Finishing : Step

    /** Uninstall and install: the installed app is being uninstalled. */
    data object Uninstalling : Step
}

/**
 * Installs one app from its parts with the owner's options (INS-2, INS-3), or removes one for
 * [user]. Status broadcasts are received on [context] itself: a receiver registered on a context
 * made for a user does not get them.
 */
class Installer(private val context: Context, user: UserHandle = context.user) {
    private val pm = context.packageManager
    private val installer = context.createContextAsUser(user, 0).packageManager.packageInstaller

    suspend fun install(
        app: AppFile,
        parts: List<Part>,
        options: InstallOptions,
        origin: Origin,
        onStep: (Step, Float) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL)
        val total = parts.sumOf { it.size }
        params.setAppPackageName(app.packageName)
        params.setAppLabel(app.label)
        app.icon?.let { params.setAppIcon(bitmap(it)) }
        params.setSize(total)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        params.setPackageSource(
            if (origin.referrer != null) PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
            else PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
        )
        if (origin.originatingUid != Process.INVALID_UID) params.setOriginatingUid(origin.originatingUid)
        origin.originatingUri?.let { params.setOriginatingUri(it) }
        origin.referrer?.let { params.setReferrerUri(it) }
        origin.installerPackageName?.let { params.setInstallerPackageName(it) }
        if (options.allowDowngrade) params.setRequestDowngrade(true)
        if (options.allUsers) params.installFlags = params.installFlags or PackageManager.INSTALL_ALL_USERS
        if (options.allowTestOnly) params.installFlags = params.installFlags or PackageManager.INSTALL_ALLOW_TEST
        if (options.allowOldSdk) {
            params.installFlags = params.installFlags or PackageManager.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK
        }
        if (options.abi.isNotEmpty()) params.abiOverride = options.abi
        if (options.askUpdates && app.installed == null) params.setRequestUpdateOwnership(true)
        options.compilerFilter?.let { params.setDexoptCompilerFilter(it) }
        if (options.permissions == InstallOptions.PERMISSIONS_GRANT) {
            params.installFlags = params.installFlags or PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS
        }

        val id =
            try {
                installer.createSession(params)
            } catch (e: Exception) {
                Log.e(TAG, "${app.packageName}: no session", e)
                return@withContext Outcome.Failed(PackageManager.INSTALL_FAILED_INTERNAL_ERROR, PackageInstaller.STATUS_FAILURE, e.message)
            }
        val session = installer.openSession(id)
        try {
            var done = 0L
            onStep(Step.Copying(0, total), 0f)
            for (part in parts) {
                val name = if (part.splitName == null) "base.apk" else "split_${part.splitName}.apk"
                session.openWrite(name, 0, part.size).use { out ->
                    part.file.inputStream().use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val f = if (total > 0) done.toFloat() / total else 1f
                            session.setStagingProgress(f)
                            onStep(Step.Copying(done, total), f * COPY_SHARE)
                        }
                    }
                    session.fsync(out)
                }
            }
            onStep(Step.Installing, COPY_SHARE)
            val result = commit(session, id, onStep)
            if (result is Outcome.Installed) {
                onStep(Step.Finishing, 1f)
                val notes = after(app, options)
                Outcome.Installed(app.packageName, notes)
            } else {
                result
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            session.abandon()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "${app.packageName}: install failed while staging", e)
            session.abandon()
            Outcome.Failed(PackageManager.INSTALL_FAILED_INTERNAL_ERROR, PackageInstaller.STATUS_FAILURE, e.message)
        } finally {
            session.close()
        }
    }

    private suspend fun commit(session: PackageInstaller.Session, id: Int, onStep: (Step, Float) -> Unit): Outcome =
        suspendCancellableCoroutine { cont ->
            val action = "org.lujarch.installer.action.RESULT.$id"
            val main = Handler(Looper.getMainLooper())
            val callback =
                object : PackageInstaller.SessionCallback() {
                    override fun onCreated(sessionId: Int) {}
                    override fun onBadgingChanged(sessionId: Int) {}
                    override fun onActiveChanged(sessionId: Int, active: Boolean) {}
                    override fun onFinished(sessionId: Int, success: Boolean) {}

                    override fun onProgressChanged(sessionId: Int, progress: Float) {
                        if (sessionId == id && progress > COPY_SHARE) onStep(Step.Installing, progress)
                    }
                }
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            Log.i(TAG, "session $id asks the owner to confirm")
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                            return
                        }
                        context.unregisterReceiver(this)
                        installer.unregisterSessionCallback(callback)
                        val outcome =
                            if (status == PackageInstaller.STATUS_SUCCESS) {
                                Outcome.Installed(intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "", emptyList())
                            } else {
                                val legacy = intent.getIntExtra(PackageInstaller.EXTRA_LEGACY_STATUS, PackageManager.INSTALL_FAILED_INTERNAL_ERROR)
                                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                                Log.e(TAG, "session $id failed: status $status, ${PackageManager.installStatusToString(legacy, message)}")
                                Outcome.Failed(legacy, status, message)
                            }
                        if (cont.isActive) cont.resume(outcome)
                    }
                }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            installer.registerSessionCallback(callback, main)
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            cont.invokeOnCancellation {
                Log.i(TAG, "session $id stopped")
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "session $id: receiver already gone", e)
                }
                installer.unregisterSessionCallback(callback)
                try {
                    installer.abandonSession(id)
                } catch (e: Exception) {
                    Log.w(TAG, "session $id not abandoned", e)
                }
            }
            session.commit(pending.intentSender)
        }

    /**
     * Lets another app's session [id], which waits for the owner, install as that app staged it,
     * and waits for its end (INS-8).
     */
    suspend fun confirm(id: Int, onStep: (Step, Float) -> Unit): Outcome =
        suspendCancellableCoroutine { cont ->
            val callback =
                object : PackageInstaller.SessionCallback() {
                    override fun onCreated(sessionId: Int) {}
                    override fun onBadgingChanged(sessionId: Int) {}
                    override fun onActiveChanged(sessionId: Int, active: Boolean) {}

                    override fun onProgressChanged(sessionId: Int, progress: Float) {
                        if (sessionId == id) onStep(Step.Installing, progress)
                    }

                    override fun onFinished(sessionId: Int, success: Boolean) {
                        if (sessionId != id) return
                        installer.unregisterSessionCallback(this)
                        if (!success) Log.e(TAG, "session $id of another app failed")
                        val outcome =
                            if (success) Outcome.Installed("", emptyList())
                            else Outcome.Failed(PackageManager.INSTALL_FAILED_INTERNAL_ERROR, PackageInstaller.STATUS_FAILURE, null)
                        if (cont.isActive) cont.resume(outcome)
                    }
                }
            installer.registerSessionCallback(callback, Handler(Looper.getMainLooper()))
            cont.invokeOnCancellation { installer.unregisterSessionCallback(callback) }
            try {
                installer.setPermissionsResult(id, true)
            } catch (e: Exception) {
                Log.e(TAG, "session $id not confirmed", e)
                installer.unregisterSessionCallback(callback)
                if (cont.isActive) {
                    cont.resume(Outcome.Failed(PackageManager.INSTALL_FAILED_INTERNAL_ERROR, PackageInstaller.STATUS_FAILURE, e.message))
                }
            }
        }

    /** Tells another app's session [id] that the owner did not install it. */
    fun decline(id: Int) {
        try {
            installer.setPermissionsResult(id, false)
        } catch (e: Exception) {
            Log.e(TAG, "session $id not declined", e)
        }
    }

    /** Expansion files and Deny all, once the app is installed; what failed of them. */
    private fun after(app: AppFile, options: InstallOptions): List<String> {
        val notes = ArrayList<String>()
        if (app.obbs.isNotEmpty()) {
            val dir = File(Environment.getExternalStorageDirectory(), "Android/obb/${app.packageName}")
            try {
                dir.mkdirs()
                for (obb in app.obbs) obb.file.copyTo(File(dir, obb.name), overwrite = true)
                Log.i(TAG, "${app.packageName}: ${app.obbs.size} expansion files copied to $dir")
            } catch (e: Exception) {
                Log.e(TAG, "${app.packageName}: expansion files not copied to $dir", e)
                notes += context.getString(R.string.ins_note_obb_failed, e.message ?: "")
            }
        }
        if (options.permissions == InstallOptions.PERMISSIONS_DENY) {
            val user = UserHandle.of(context.userId)
            val fixed = PackageManager.FLAG_PERMISSION_USER_FIXED or PackageManager.FLAG_PERMISSION_USER_SET
            for (name in runtimePermissions(app.packageName)) {
                try {
                    pm.updatePermissionFlags(name, app.packageName, fixed, fixed, user)
                } catch (e: Exception) {
                    Log.e(TAG, "${app.packageName}: $name not denied", e)
                    notes += context.getString(R.string.ins_note_deny_failed, name)
                }
            }
        }
        return notes
    }

    private fun runtimePermissions(pkg: String): List<String> {
        val info: PackageInfo =
            try {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "$pkg is not installed after its install", e)
                return emptyList()
            }
        return info.requestedPermissions.orEmpty().filterIndexed { i, name ->
            val granted = info.requestedPermissionsFlags!![i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            !granted &&
                try {
                    pm.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
        }
    }

    /** Uninstalls [packageName] with [flags] (PackageManager.DELETE_...), for INS-5 and INS-7. */
    suspend fun uninstall(packageName: String, flags: Int): Outcome =
        statusCall(packageName, "UNINSTALLED") { sender ->
            installer.uninstall(VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST), flags, sender)
        }

    /** Archives [packageName]: its data and icon stay, to be restored from its icon (INS-7). */
    suspend fun archive(packageName: String): Outcome =
        statusCall(packageName, "ARCHIVED") { sender -> installer.requestArchive(packageName, sender) }

    private suspend fun statusCall(packageName: String, what: String, start: (android.content.IntentSender) -> Unit): Outcome =
        suspendCancellableCoroutine { cont ->
            val action = "org.lujarch.installer.action.$what.${packageName.hashCode()}"
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                            return
                        }
                        context.unregisterReceiver(this)
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        val outcome =
                            if (status == PackageInstaller.STATUS_SUCCESS) {
                                Log.i(TAG, "$packageName ${what.lowercase()}")
                                Outcome.Installed(packageName, emptyList())
                            } else {
                                Log.e(TAG, "$packageName not ${what.lowercase()}: status $status, $message")
                                Outcome.Failed(intent.getIntExtra(PackageInstaller.EXTRA_LEGACY_STATUS, 0), status, message)
                            }
                        if (cont.isActive) cont.resume(outcome)
                    }
                }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            val pending =
                PendingIntent.getBroadcast(
                    context, action.hashCode(), Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            try {
                start(pending.intentSender)
            } catch (e: Exception) {
                Log.e(TAG, "$packageName: ${what.lowercase()} not started", e)
                context.unregisterReceiver(receiver)
                if (cont.isActive) {
                    cont.resume(Outcome.Failed(PackageManager.DELETE_FAILED_INTERNAL_ERROR, PackageInstaller.STATUS_FAILURE, e.message))
                }
            }
        }

    /** Deletes the files an install came from; the names of those not deleted. */
    fun deleteSources(sources: List<SourceFile>): List<String> =
        sources.mapNotNull { source ->
            val deleted =
                try {
                    when {
                        source.uri.scheme == "file" -> File(source.uri.path!!).delete()
                        DocumentsContract.isDocumentUri(context, source.uri) ->
                            DocumentsContract.deleteDocument(context.contentResolver, source.uri)
                        else -> context.contentResolver.delete(source.uri, null) > 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "${source.name} not deleted", e)
                    false
                }
            if (deleted) {
                Log.i(TAG, "${source.name} deleted after its install")
                null
            } else source.name
        }

    private fun bitmap(d: android.graphics.drawable.Drawable): Bitmap {
        val size = 192
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    private companion object {
        const val COPY_SHARE = 0.8f
    }
}
