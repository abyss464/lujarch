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

package org.lujarch.icons.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import com.android.internal.lujarch.icons.ILujarchIconService
import com.android.internal.lujarch.icons.LujarchIcons

/** Fitted icons as Settings sees them (ICON-1): the system's switch and each app's choice. */
internal object Icons {
    const val TAG = "lujarch_icons"

    /** The choices an app's Icon offers, in the order they are shown. */
    val MODES = listOf(LujarchIcons.MODE_ORIGINAL, LujarchIcons.MODE_FIT, LujarchIcons.MODE_REDRAW)

    /** Fit app icons, Wallpaper & style's switch. */
    fun isOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, LujarchIcons.SETTING_FIT, 1) != 0

    fun service(): ILujarchIconService =
        LujarchIcons.service() ?: throw IllegalStateException("the icon service is not running")

    fun appInfo(context: Context, packageName: String, userId: Int): ApplicationInfo? =
        try {
            context.packageManager.getApplicationInfoAsUser(packageName, 0, userId)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    /** The choice in effect: an app without one of its own is redrawn. */
    fun mode(packageName: String): Int =
        service().getMode(packageName).let { if (it == LujarchIcons.MODE_DEFAULT) LujarchIcons.MODE_REDRAW else it }

    /** Stores [mode]; Redrawn, the default, is stored as no choice of the app's own. */
    fun setMode(packageName: String, mode: Int) =
        service().setMode(packageName, if (mode == LujarchIcons.MODE_REDRAW) LujarchIcons.MODE_DEFAULT else mode)

    fun label(mode: Int): Int = when (mode) {
        LujarchIcons.MODE_ORIGINAL -> R.string.icons_original
        LujarchIcons.MODE_FIT -> R.string.icons_fitted
        else -> R.string.icons_redrawn
    }
}
