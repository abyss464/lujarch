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

import android.app.Activity
import android.content.Intent
import android.util.Log
import org.lujarch.installer.TAG

/** Package installer's own entries, handed to the sheets with the app that opened them. */
object Entry {
    /** Opens [activity]'s app file in the installer's sheet; false for a session's confirmation. */
    @JvmStatic
    fun install(activity: Activity): Boolean {
        val intent = activity.intent
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_INSTALL_PACKAGE) return false
        val scheme = intent.data?.scheme
        if (scheme != "content" && scheme != "file") return false
        return forward(activity, InstallActivity::class.java)
    }

    /** Opens [activity]'s uninstall in the uninstaller's sheet. */
    @JvmStatic
    fun uninstall(activity: Activity): Boolean {
        if (activity.intent.data?.scheme != "package") return false
        return forward(activity, UninstallActivity::class.java)
    }

    private fun forward(activity: Activity, sheet: Class<out Activity>): Boolean {
        val intent = activity.intent
        val next =
            Intent(intent)
                .setClass(activity, sheet)
                .putExtra(InstallActivity.EXTRA_CALLING_UID, activity.launchedFromUid)
                .putExtra(InstallActivity.EXTRA_CALLING_PACKAGE, activity.launchedFromPackage)
        next.flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT or (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            activity.startActivity(next)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "${sheet.simpleName} not opened for ${intent.action}", e)
            false
        }
    }
}
