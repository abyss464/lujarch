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

package org.lujarch.background.settings

import android.app.AppOpsManager
import android.content.Context

/**
 * Autostart (PERM-15), the permission channel's choice of whether the system starts the app,
 * while it is not running, for a broadcast: Allow, Ask every time (the op's default mode) or
 * Don't allow. Return empty data refuses a start as Don't allow does.
 */
object Autostart {
    val CHOICES = intArrayOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_ERRORED)

    fun mode(context: Context, uid: Int, packageName: String): Int {
        val mode = context.getSystemService(AppOpsManager::class.java)!!
            .unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_LUJARCH_AUTOSTART, uid, packageName)
        return when (mode) {
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> AppOpsManager.MODE_ERRORED
            AppOpsManager.MODE_DEFAULT -> AppOpsManager.MODE_DEFAULT
            else -> AppOpsManager.MODE_ALLOWED
        }
    }

    fun set(context: Context, uid: Int, packageName: String, mode: Int) {
        context.getSystemService(AppOpsManager::class.java)!!
            .setMode(AppOpsManager.OP_LUJARCH_AUTOSTART, uid, packageName, mode)
    }

    fun label(context: Context, mode: Int): String = context.getString(
        when (mode) {
            AppOpsManager.MODE_DEFAULT -> R.string.background_autostart_ask
            AppOpsManager.MODE_ERRORED -> R.string.background_autostart_deny
            else -> R.string.background_autostart_allow
        }
    )

    /** The row's summary: the choice, and what Ask every time and Don't allow cost. */
    fun summary(context: Context, mode: Int): String = context.getString(
        when (mode) {
            AppOpsManager.MODE_DEFAULT -> R.string.background_autostart_summary_ask
            AppOpsManager.MODE_ERRORED -> R.string.background_autostart_summary_deny
            else -> R.string.background_autostart_allow
        }
    )
}
