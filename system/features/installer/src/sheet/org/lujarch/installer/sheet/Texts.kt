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

import android.content.Context
import android.text.format.Formatter
import org.lujarch.installer.R as SharedR

/** The words the sheets build from what they show. */
object Texts {
    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    /** Installing, Updated, Not downgraded...: what is happening or happened to [app]. */
    fun workTitle(context: Context, app: AppFile, status: AppStatus?): String {
        val ids =
            when (app.kind) {
                InstallKind.NEW -> intArrayOf(R.string.ins_installing, R.string.ins_installed, R.string.ins_not_installed)
                InstallKind.UPDATE -> intArrayOf(R.string.ins_updating, R.string.ins_updated, R.string.ins_not_updated)
                InstallKind.DOWNGRADE ->
                    intArrayOf(R.string.ins_downgrading, R.string.ins_downgraded, R.string.ins_not_downgraded)
                InstallKind.SAME ->
                    intArrayOf(R.string.ins_reinstalling, R.string.ins_reinstalled, R.string.ins_not_reinstalled)
            }
        return context.getString(
            when (status) {
                is AppStatus.Done -> ids[1]
                is AppStatus.Failed -> ids[2]
                else -> ids[0]
            }
        )
    }

    /** The button that installs [app]. */
    fun installButton(context: Context, app: AppFile): String =
        context.getString(
            when (app.kind) {
                InstallKind.NEW -> R.string.ins_install
                InstallKind.UPDATE -> SharedR.string.ins_kind_update
                InstallKind.DOWNGRADE -> SharedR.string.ins_kind_downgrade
                InstallKind.SAME -> SharedR.string.ins_kind_reinstall
            }
        )

    fun kind(context: Context, kind: InstallKind): String =
        context.getString(
            when (kind) {
                InstallKind.NEW -> SharedR.string.ins_kind_new
                InstallKind.UPDATE -> SharedR.string.ins_kind_update
                InstallKind.DOWNGRADE -> SharedR.string.ins_kind_downgrade
                InstallKind.SAME -> SharedR.string.ins_kind_reinstall
            }
        )

    /** 2.3.1 → 2.4.0, or the version alone for a new app. */
    fun versions(context: Context, app: AppFile): String {
        val now = app.versionName ?: app.versionCode.toString()
        val before = app.installed ?: return now
        return context.getString(R.string.ins_version_change, before.versionName ?: before.versionCode.toString(), now)
    }

    fun codes(context: Context, app: AppFile): String {
        val before = app.installed ?: return context.getString(R.string.ins_code, app.versionCode)
        return context.getString(R.string.ins_code_change, before.versionCode, app.versionCode)
    }

    fun step(context: Context, step: Step): String =
        when (step) {
            is Step.Copying -> context.getString(R.string.ins_step_copying, size(context, step.done), size(context, step.total))
            Step.Installing -> context.getString(R.string.ins_step_installing)
            Step.Finishing -> context.getString(R.string.ins_step_finishing)
            Step.Uninstalling -> context.getString(R.string.ins_step_uninstalling)
        }

    /** The Android version of an API level, such as 8.0 for 26. */
    fun android(sdk: Int): String = ANDROID[sdk] ?: "API $sdk"

    private val ANDROID =
        mapOf(
            1 to "1.0", 2 to "1.1", 3 to "1.5", 4 to "1.6", 5 to "2.0", 6 to "2.0.1", 7 to "2.1",
            8 to "2.2", 9 to "2.3", 10 to "2.3.3", 11 to "3.0", 12 to "3.1", 13 to "3.2", 14 to "4.0",
            15 to "4.0.3", 16 to "4.1", 17 to "4.2", 18 to "4.3", 19 to "4.4", 20 to "4.4W", 21 to "5.0",
            22 to "5.1", 23 to "6", 24 to "7.0", 25 to "7.1", 26 to "8.0", 27 to "8.1", 28 to "9",
            29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15",
            36 to "16", 37 to "17",
        )
}
