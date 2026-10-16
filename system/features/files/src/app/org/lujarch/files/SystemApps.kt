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

package org.lujarch.files

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

internal const val TAG = "lujarch_files"

/** A system app that takes a stock app's role (APP-2), and whether the system hands it the role. */
internal class RoleApp(
    val state: State,
    val pkg: String,
    /** The app's label. */
    val name: String?,
    /** Whether PackageManager knows the package, so that its App info page exists. */
    val known: Boolean,
) {
    enum class State { OK, NOT_INSTALLED, NOT_SYSTEM, DISABLED }

    val ok: Boolean get() = state == State.OK
}

/**
 * The system's apps in the stock apps' roles (APP-2): Files in place of the file manager and its
 * picker, Music in place of the music app. A role's app is served while it is a system package,
 * installed for this user and enabled; an update keeps the system copy's signer.
 */
internal object SystemApps {
    const val FILES = "me.zhanghai.android.files"
    const val MUSIC = "org.feeluown.mobile"

    /** The role's app as the system sees it now. Blocking (PackageManager). */
    fun find(context: Context, pkg: String): RoleApp {
        val pm = context.packageManager
        val ai = try {
            pm.getApplicationInfo(
                pkg,
                PackageManager.ApplicationInfoFlags.of(
                    (PackageManager.MATCH_DISABLED_COMPONENTS or
                        PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong(),
                ),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val name = ai?.loadLabel(pm)?.toString()
        val state = when {
            ai == null || (ai.flags and ApplicationInfo.FLAG_INSTALLED) == 0 -> RoleApp.State.NOT_INSTALLED
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 -> RoleApp.State.NOT_SYSTEM
            !ai.enabled -> RoleApp.State.DISABLED
            else -> RoleApp.State.OK
        }
        if (state != RoleApp.State.OK) Log.w(TAG, "$pkg is not served: $state")
        return RoleApp(state, pkg, name, ai != null)
    }
}
