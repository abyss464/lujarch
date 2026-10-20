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
import android.os.Build
import android.provider.Settings
import android.util.Log

const val TAG = "lujarch_installer"

/** The options of one install (INS-3); [read] gives the defaults the owner chose. */
data class InstallOptions(
    val allowDowngrade: Boolean = false,
    val allUsers: Boolean = false,
    val permissions: Int = PERMISSIONS_ASK,
    val allowTestOnly: Boolean = false,
    val allowOldSdk: Boolean = false,
    val abi: String = "",
    val askUpdates: Boolean = false,
    val optimization: Int = OPTIMIZE_AUTO,
    val after: Int = AFTER_DONE,
    val deleteFile: Boolean = false,
) {
    companion object {
        const val PREFIX = "lujarch_installer_"

        const val PERMISSIONS_ASK = 0
        const val PERMISSIONS_GRANT = 1
        const val PERMISSIONS_DENY = 2

        const val OPTIMIZE_AUTO = 0
        const val OPTIMIZE_SPEED_PROFILE = 1
        const val OPTIMIZE_SPEED = 2
        const val OPTIMIZE_VERIFY = 3

        const val AFTER_DONE = 0
        const val AFTER_OPEN = 1
        const val AFTER_INFO = 2

        fun read(context: Context): InstallOptions {
            val cr = context.contentResolver
            fun int(key: String, def: Int) =
                Settings.Secure.getIntForUser(cr, PREFIX + key, def, context.userId)
            fun bool(key: String) = int(key, 0) != 0
            return InstallOptions(
                allowDowngrade = bool("allow_downgrade"),
                allUsers = int("install_for", 0) == 1,
                permissions = int("permissions", PERMISSIONS_ASK),
                allowTestOnly = bool("allow_test_only"),
                allowOldSdk = bool("allow_old_sdk"),
                abi =
                    (Settings.Secure.getStringForUser(cr, PREFIX + "abi", context.userId) ?: "")
                        .takeIf { it in Build.SUPPORTED_ABIS } ?: "",
                askUpdates = bool("ask_updates"),
                optimization = int("optimization", OPTIMIZE_AUTO),
                after = int("after", AFTER_DONE),
                deleteFile = bool("delete_file"),
            )
        }

        /** Writes the default [key] (without the prefix); false when it was not written. */
        fun write(context: Context, key: String, value: String): Boolean =
            try {
                Settings.Secure.putStringForUser(
                    context.contentResolver, PREFIX + key, value, context.userId)
            } catch (e: SecurityException) {
                Log.e(TAG, "$key not written", e)
                false
            }
    }

    /** The dex compiler filter Android is asked for, or null for its own choice. */
    val compilerFilter: String?
        get() =
            when (optimization) {
                OPTIMIZE_SPEED_PROFILE -> "speed-profile"
                OPTIMIZE_SPEED -> "speed"
                OPTIMIZE_VERIFY -> "verify"
                else -> null
            }

    /** The controls of these options, in the order the installer and Settings show them. */
    fun controls(context: Context): List<OptionControl> {
        fun s(id: Int) = context.getString(id)
        return listOf(
            OptionControl.Pick(
                "install_for",
                s(R.string.ins_install_for),
                if (allUsers) 1 else 0,
                listOf(0 to s(R.string.ins_this_user), 1 to s(R.string.ins_all_users)),
            ),
            OptionControl.Pick(
                "permissions",
                s(R.string.ins_permissions),
                permissions,
                listOf(
                    PERMISSIONS_ASK to s(R.string.ins_permissions_ask),
                    PERMISSIONS_GRANT to s(R.string.ins_permissions_grant),
                    PERMISSIONS_DENY to s(R.string.ins_permissions_deny),
                ),
            ),
            OptionControl.Pick(
                "optimization",
                s(R.string.ins_optimization),
                optimization,
                listOf(
                    OPTIMIZE_AUTO to s(R.string.ins_optimize_auto),
                    OPTIMIZE_SPEED_PROFILE to s(R.string.ins_optimize_speed_profile),
                    OPTIMIZE_SPEED to s(R.string.ins_optimize_speed),
                    OPTIMIZE_VERIFY to s(R.string.ins_optimize_verify),
                ),
            ),
            OptionControl.Pick(
                "after",
                s(R.string.ins_after),
                after,
                listOf(
                    AFTER_DONE to s(R.string.ins_after_done),
                    AFTER_OPEN to s(R.string.ins_after_open),
                    AFTER_INFO to s(R.string.ins_after_info),
                ),
            ),
            OptionControl.Abi(
                s(R.string.ins_abi),
                abi,
                listOf("" to s(R.string.ins_abi_auto)) + Build.SUPPORTED_ABIS.map { it to it },
            ),
            OptionControl.Flag(
                "allow_downgrade",
                s(R.string.ins_allow_downgrade),
                s(R.string.ins_allow_downgrade_summary),
                allowDowngrade,
            ),
            OptionControl.Flag("allow_test_only", s(R.string.ins_allow_test_only), null, allowTestOnly),
            OptionControl.Flag("allow_old_sdk", s(R.string.ins_allow_old_sdk), null, allowOldSdk),
            OptionControl.Flag(
                "ask_updates",
                s(R.string.ins_ask_updates),
                s(R.string.ins_ask_updates_summary),
                askUpdates,
            ),
            OptionControl.Flag("delete_file", s(R.string.ins_delete_file), null, deleteFile),
        )
    }

    /** These options with the control [key] set to [value]. */
    fun with(key: String, value: String): InstallOptions =
        when (key) {
            "install_for" -> copy(allUsers = value == "1")
            "permissions" -> copy(permissions = value.toIntOrNull() ?: permissions)
            "optimization" -> copy(optimization = value.toIntOrNull() ?: optimization)
            "after" -> copy(after = value.toIntOrNull() ?: after)
            "abi" -> copy(abi = value)
            "allow_downgrade" -> copy(allowDowngrade = value == "1")
            "allow_test_only" -> copy(allowTestOnly = value == "1")
            "allow_old_sdk" -> copy(allowOldSdk = value == "1")
            "ask_updates" -> copy(askUpdates = value == "1")
            "delete_file" -> copy(deleteFile = value == "1")
            else -> this
        }

    /** What these options change from Android's own install, joined; empty when nothing. */
    fun summary(context: Context): List<String> {
        val out = ArrayList<String>()
        if (allowDowngrade) out += context.getString(R.string.ins_allow_downgrade)
        if (allUsers) out += context.getString(R.string.ins_all_users)
        if (permissions == PERMISSIONS_GRANT) out += context.getString(R.string.ins_permissions_grant_all)
        if (permissions == PERMISSIONS_DENY) out += context.getString(R.string.ins_permissions_deny_all)
        if (allowTestOnly) out += context.getString(R.string.ins_allow_test_only)
        if (allowOldSdk) out += context.getString(R.string.ins_allow_old_sdk)
        if (abi.isNotEmpty()) out += abi
        if (askUpdates) out += context.getString(R.string.ins_ask_updates)
        if (optimization != OPTIMIZE_AUTO) {
            out += controls(context).filterIsInstance<OptionControl.Pick>()
                .first { it.key == "optimization" }.choices.first { it.first == optimization }.second
        }
        if (deleteFile) out += context.getString(R.string.ins_delete_file)
        return out
    }
}

/** One control of [InstallOptions]; its value is written as a string under its key. */
sealed interface OptionControl {
    val key: String
    val title: String

    data class Flag(
        override val key: String,
        override val title: String,
        val summary: String?,
        val checked: Boolean,
    ) : OptionControl

    data class Pick(
        override val key: String,
        override val title: String,
        val value: Int,
        val choices: List<Pair<Int, String>>,
    ) : OptionControl

    data class Abi(
        override val title: String,
        val value: String,
        val choices: List<Pair<String, String>>,
    ) : OptionControl {
        override val key = "abi"
    }
}
