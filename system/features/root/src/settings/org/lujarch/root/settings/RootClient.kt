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

package org.lujarch.root.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.Process
import android.os.ServiceManager
import android.os.ServiceSpecificException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.lujarch.root.IRoot
import org.lujarch.settings.pages.Failure
import org.lujarch.settings.pages.R as PagesR

/** Log tag of Root's pages. */
const val TAG = "lujarch_root"

/**
 * A failed request to the root service; [code] (IRoot's ERROR_ constants, 0 for none) selects
 * the sentence shown to the owner, [detail] (the service's own text) is shown only behind Details.
 */
class RootException(
    val kind: Kind,
    val command: String,
    val detail: String,
    val code: Int = 0,
    cause: Throwable? = null,
) : Exception("$command: $detail", cause), Failure {
    enum class Kind { UNAVAILABLE, TIMEOUT, REMOTE }

    override val unavailable: Boolean
        get() = kind == Kind.UNAVAILABLE

    override fun describe(context: Context): String =
        when (kind) {
            Kind.UNAVAILABLE -> context.getString(PagesR.string.error_unavailable_sentence)
            Kind.TIMEOUT -> context.getString(PagesR.string.error_timeout)
            Kind.REMOTE ->
                context.getString(
                    when (code) {
                        IRoot.ERROR_UNAVAILABLE -> R.string.root_ksu_unavailable
                        IRoot.ERROR_NOT_INSTALLED -> R.string.root_error_not_installed
                        IRoot.ERROR_NEVER_GRANTED -> R.string.root_error_never_granted
                        IRoot.ERROR_NO_ROOT -> R.string.root_error_no_root
                        IRoot.ERROR_INVALID -> R.string.root_error_invalid
                        IRoot.ERROR_REFUSED -> PagesR.string.error_refused
                        else -> PagesR.string.error_failed
                    }
                )
        }

    override val details: String
        get() = if (detail.isBlank()) command else "$command\n\n$detail"
}

/** KernelSU in the running kernel. */
data class RootStatus(val available: Boolean, val version: String?, val reason: String?)

/** A profile KernelSU holds, under the package it was written for. */
data class SuApp(val packageName: String, val uid: Int, val allowSu: Boolean, val custom: Boolean)

/** An app's root profile (ROOT-2). */
data class RootProfile(
    /** Values of its own instead of KernelSU's default root profile. */
    val custom: Boolean,
    val uid: Int,
    val gid: Int,
    val groups: List<Int>,
    val capabilities: Long,
    val selinuxDomain: String,
    /** One of IRoot's NAMESPACE_ constants. */
    val namespace: Int,
    val noNewPrivileges: Boolean,
) {
    val allCapabilities: Boolean
        get() = capabilities == ALL_CAPABILITIES

    companion object {
        /** The 41 capabilities of the kernel (CAP_LAST_CAP is CAP_CHECKPOINT_RESTORE). */
        const val ALL_CAPABILITIES = (1L shl 41) - 1
    }
}

/** An app's root as its page shows it. */
data class AppRootState(
    val packageName: String,
    val uid: Int,
    val allowSu: Boolean,
    /** Present while allowed. */
    val root: RootProfile?,
    /** One of the UID_ constants. */
    val uidClass: String,
    /** KernelSU may give root to this app: its user ID is one KernelSU grants, and it is not Lujarch's. */
    val rootAllowed: Boolean,
    /** Other packages with the same user ID: a grant applies to all of them. */
    val sharedWith: List<String>,
) {
    companion object {
        const val UID_APP = "app"
        const val UID_SYSTEM = "system"
        const val UID_SHELL = "shell"
        const val UID_OTHER = "other"
        const val UID_FORBIDDEN = "forbidden"
    }
}

/** The root service (lujarch_root). Every call runs off the main thread. */
object RootClient {
    private const val SERVICE = "lujarch_root"
    private const val TIMEOUT_MS = 10_000L
    private const val OWN_PREFIX = "org.lujarch."

    private fun service(command: String): IRoot =
        IRoot.Stub.asInterface(ServiceManager.checkService(SERVICE))
            ?: throw RootException(RootException.Kind.UNAVAILABLE, command, "$SERVICE is not running")

    private suspend fun <T> call(command: String, block: (IRoot) -> T): T =
        try {
            withTimeout(TIMEOUT_MS) { withContext(Dispatchers.IO) { block(service(command)) } }
        } catch (e: TimeoutCancellationException) {
            throw RootException(RootException.Kind.TIMEOUT, command, "no answer within ${TIMEOUT_MS / 1000} s", cause = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RootException) {
            throw e
        } catch (e: ServiceSpecificException) {
            throw RootException(RootException.Kind.REMOTE, command, e.message ?: "", e.errorCode, e)
        } catch (e: DeadObjectException) {
            throw RootException(RootException.Kind.UNAVAILABLE, command, "$SERVICE stopped", cause = e)
        }

    suspend fun status(): RootStatus = call("getInfo") {
        val info = it.info
        RootStatus(info.available, info.version, info.reason)
    }

    suspend fun list(): List<SuApp> = call("list") { root ->
        root.list().map { SuApp(it.packageName, it.uid, it.allowed, it.custom) }
    }

    suspend fun state(context: Context, packageName: String): AppRootState {
        val profile = call("getProfile") { it.getProfile(packageName) }
        val pm = context.packageManager
        val uid = try {
            pm.getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0))
        } catch (e: PackageManager.NameNotFoundException) {
            -1
        }
        val uidClass = uidClass(uid)
        return AppRootState(
            packageName = packageName,
            uid = uid,
            allowSu = profile.allowed,
            root = if (profile.allowed) {
                RootProfile(
                    custom = profile.custom,
                    uid = profile.uid,
                    gid = profile.gid,
                    groups = profile.groups.toList(),
                    capabilities = profile.capabilities,
                    selinuxDomain = profile.domain,
                    namespace = profile.mountNamespace,
                    noNewPrivileges = profile.noNewPrivs,
                )
            } else {
                null
            },
            uidClass = uidClass,
            rootAllowed = uidClass != AppRootState.UID_FORBIDDEN && !packageName.startsWith(OWN_PREFIX),
            sharedWith = if (uid < 0) emptyList() else pm.getPackagesForUid(uid).orEmpty().filter { it != packageName }.sorted(),
        )
    }

    suspend fun setAllowed(packageName: String, allowed: Boolean) =
        call("setAllowed") { it.setAllowed(packageName, allowed) }

    suspend fun setProfile(packageName: String, profile: RootProfile) =
        call("setProfile") {
            it.setProfile(
                packageName,
                org.lujarch.root.RootProfile().apply {
                    allowed = true
                    custom = profile.custom
                    uid = profile.uid
                    gid = profile.gid
                    groups = profile.groups.toIntArray()
                    capabilities = profile.capabilities
                    domain = profile.selinuxDomain
                    mountNamespace = profile.namespace
                    noNewPrivs = profile.noNewPrivileges
                },
            )
        }

    suspend fun resetProfile(packageName: String) = call("resetProfile") { it.resetProfile(packageName) }

    /** KernelSU grants every user ID from 2000 (shell) up, and 1000 (system). */
    fun uidClass(uid: Int): String =
        when {
            uid >= Process.FIRST_APPLICATION_UID && uid <= Process.LAST_APPLICATION_UID -> AppRootState.UID_APP
            uid == Process.SYSTEM_UID -> AppRootState.UID_SYSTEM
            uid == Process.SHELL_UID -> AppRootState.UID_SHELL
            uid >= Process.SHELL_UID -> AppRootState.UID_OTHER
            else -> AppRootState.UID_FORBIDDEN
        }
}
