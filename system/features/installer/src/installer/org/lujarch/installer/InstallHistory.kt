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

package org.lujarch.installer

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri

/**
 * The install history (INS-6), kept by Package installer: rows of [INSTALLS], the latest first,
 * read and cleared by holders of [PERMISSION].
 */
object InstallHistory {
    const val AUTHORITY = "org.lujarch.installer.history"
    const val PERMISSION = "org.lujarch.installer.permission.HISTORY"
    val INSTALLS: Uri = Uri.parse("content://$AUTHORITY/installs")

    const val TIME = "time"
    const val PACKAGE = "package"
    const val LABEL = "label"
    const val VERSION_NAME = "version_name"
    const val VERSION_CODE = "version_code"
    const val INSTALLER = "installer"
    const val KIND = "kind"
    /** 0, or the failure's legacy status (PackageManager.INSTALL_FAILED_...). */
    const val RESULT = "result"
    /** The system's message of a failure. */
    const val MESSAGE = "message"

    const val KIND_NEW = 0
    const val KIND_UPDATE = 1
    const val KIND_DOWNGRADE = 2
    const val KIND_REINSTALL = 3

    fun kindLabel(context: Context, kind: Int): String =
        context.getString(
            when (kind) {
                KIND_UPDATE -> R.string.ins_kind_update
                KIND_DOWNGRADE -> R.string.ins_kind_downgrade
                KIND_REINSTALL -> R.string.ins_kind_reinstall
                else -> R.string.ins_kind_new
            }
        )
}

/** Why an install failed, in words, from its legacy status and its session status. */
object InstallFailures {
    private enum class Reason(val sentence: Int, val short: Int) {
        ALREADY_EXISTS(R.string.ins_fail_already_exists, R.string.ins_fail_already_exists_short),
        INVALID(R.string.ins_fail_invalid, R.string.ins_fail_invalid_short),
        MANIFEST(R.string.ins_fail_manifest, R.string.ins_fail_manifest_short),
        STORAGE(R.string.ins_fail_storage, R.string.ins_fail_storage_short),
        DUPLICATE(R.string.ins_fail_duplicate, R.string.ins_fail_duplicate_short),
        SIGNATURE(R.string.ins_fail_signature, R.string.ins_fail_signature_short),
        UNSIGNED(R.string.ins_fail_unsigned, R.string.ins_fail_unsigned_short),
        SHARED_USER(R.string.ins_fail_shared_user, R.string.ins_fail_shared_user_short),
        LIBRARY(R.string.ins_fail_library, R.string.ins_fail_library_short),
        DEXOPT(R.string.ins_fail_dexopt, R.string.ins_fail_dexopt_short),
        OLDER_SDK(R.string.ins_fail_older_sdk, R.string.ins_fail_older_sdk_short),
        NEWER_SDK(R.string.ins_fail_newer_sdk, R.string.ins_fail_newer_sdk_short),
        OLD_TARGET(R.string.ins_fail_old_target, R.string.ins_fail_old_target_short),
        PROVIDER(R.string.ins_fail_provider, R.string.ins_fail_provider_short),
        PERMISSION(R.string.ins_fail_permission, R.string.ins_fail_permission_short),
        TEST_ONLY(R.string.ins_fail_test_only, R.string.ins_fail_test_only_short),
        ABI(R.string.ins_fail_abi, R.string.ins_fail_abi_short),
        FEATURE(R.string.ins_fail_feature, R.string.ins_fail_feature_short),
        VERIFICATION(R.string.ins_fail_verification, R.string.ins_fail_verification_short),
        DOWNGRADE(R.string.ins_fail_downgrade, R.string.ins_fail_downgrade_short),
        MISSING_SPLIT(R.string.ins_fail_missing_split, R.string.ins_fail_missing_split_short),
        RESTRICTED(R.string.ins_fail_restricted, R.string.ins_fail_restricted_short),
        ABORTED(R.string.ins_fail_aborted, R.string.ins_fail_aborted_short),
        UNKNOWN(R.string.ins_fail_unknown, R.string.ins_fail_unknown_short),
    }

    private fun of(legacy: Int, status: Int): Reason =
        when (legacy) {
            PackageManager.INSTALL_FAILED_ALREADY_EXISTS -> Reason.ALREADY_EXISTS
            PackageManager.INSTALL_FAILED_INVALID_APK,
            PackageManager.INSTALL_PARSE_FAILED_NOT_APK,
            PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
            PackageManager.INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED -> Reason.INVALID
            PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_EMPTY,
            PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
            PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID -> Reason.MANIFEST
            PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE -> Reason.STORAGE
            PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE -> Reason.DUPLICATE
            PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE,
            PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES -> Reason.SIGNATURE
            PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES,
            PackageManager.INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING,
            PackageManager.INSTALL_FAILED_BAD_SIGNATURE -> Reason.UNSIGNED
            PackageManager.INSTALL_FAILED_NO_SHARED_USER,
            PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE -> Reason.SHARED_USER
            PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY,
            PackageManager.INSTALL_FAILED_SHARED_LIBRARY_BAD_CERTIFICATE_DIGEST -> Reason.LIBRARY
            PackageManager.INSTALL_FAILED_DEXOPT,
            PackageManager.INSTALL_FAILED_BAD_DEX_METADATA -> Reason.DEXOPT
            PackageManager.INSTALL_FAILED_OLDER_SDK -> Reason.OLDER_SDK
            PackageManager.INSTALL_FAILED_NEWER_SDK -> Reason.NEWER_SDK
            PackageManager.INSTALL_FAILED_DEPRECATED_SDK_VERSION -> Reason.OLD_TARGET
            PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER -> Reason.PROVIDER
            PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION,
            PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP,
            PackageManager.INSTALL_FAILED_BAD_PERMISSION_GROUP -> Reason.PERMISSION
            PackageManager.INSTALL_FAILED_TEST_ONLY -> Reason.TEST_ONLY
            PackageManager.INSTALL_FAILED_CPU_ABI_INCOMPATIBLE,
            PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS,
            PackageManager.INSTALL_FAILED_MULTI_ARCH_NOT_MATCH_ALL_NATIVE_ABIS -> Reason.ABI
            PackageManager.INSTALL_FAILED_MISSING_FEATURE -> Reason.FEATURE
            PackageManager.INSTALL_FAILED_VERIFICATION_TIMEOUT,
            PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE -> Reason.VERIFICATION
            PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE,
            PackageManager.INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE,
            PackageManager.INSTALL_FAILED_SANDBOX_VERSION_DOWNGRADE -> Reason.DOWNGRADE
            PackageManager.INSTALL_FAILED_MISSING_SPLIT -> Reason.MISSING_SPLIT
            PackageManager.INSTALL_FAILED_USER_RESTRICTED -> Reason.RESTRICTED
            PackageManager.INSTALL_FAILED_ABORTED -> Reason.ABORTED
            else ->
                when (status) {
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> Reason.RESTRICTED
                    PackageInstaller.STATUS_FAILURE_ABORTED -> Reason.ABORTED
                    PackageInstaller.STATUS_FAILURE_INVALID -> Reason.INVALID
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> Reason.ALREADY_EXISTS
                    PackageInstaller.STATUS_FAILURE_STORAGE -> Reason.STORAGE
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Reason.FEATURE
                    PackageInstaller.STATUS_FAILURE_TIMEOUT -> Reason.VERIFICATION
                    else -> Reason.UNKNOWN
                }
        }

    /** The failure in a sentence. */
    fun reason(context: Context, legacy: Int, status: Int = PackageInstaller.STATUS_FAILURE): String =
        context.getString(of(legacy, status).sentence)

    /** The failure in a few words, for a list. */
    fun shortReason(context: Context, legacy: Int, status: Int = PackageInstaller.STATUS_FAILURE): String =
        context.getString(of(legacy, status).short)

    /** Android's name of the failure, such as INSTALL_FAILED_UPDATE_INCOMPATIBLE. */
    fun code(legacy: Int): String = PackageManager.installStatusToString(legacy)

    /** Whether uninstalling the installed app lets the install go through. */
    fun signatureDiffers(legacy: Int): Boolean =
        legacy == PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE ||
            legacy == PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES
}
