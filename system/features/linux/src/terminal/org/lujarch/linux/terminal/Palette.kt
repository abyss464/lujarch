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

package org.lujarch.linux.terminal

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.termux.terminal.TerminalColors
import java.io.StringReader
import java.util.Properties

/**
 * The terminal's colors (LNX-3): following the system (Android's dynamic colors, light or dark
 * as the system is), classic (light gray on black), or the owner's own in Termux's
 * colors.properties format, kept by lujarch_linux.
 */
object Palette {
    fun apply(context: Context, choice: String, own: String?) {
        val props = Properties()
        when (choice) {
            "own" -> {
                if (own.isNullOrBlank()) {
                    classic(props)
                } else {
                    try {
                        props.load(StringReader(own))
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "the own colors cannot be read: ${e.message}")
                        classic(props)
                    }
                }
            }
            "classic" -> classic(props)
            else -> system(context, props)
        }
        try {
            TerminalColors.COLOR_SCHEME.updateWith(props)
        } catch (e: RuntimeException) {
            Log.w(TAG, "colors: ${e.message}")
            TerminalColors.COLOR_SCHEME.updateWith(Properties().also { classic(it) })
        }
    }

    private fun classic(props: Properties) {
        props["foreground"] = "#d0d0d0"
        props["background"] = "#000000"
        props["cursor"] = "#d0d0d0"
    }

    private fun system(context: Context, props: Properties) {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val background = context.getColor(
            if (dark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10)
        val foreground = context.getColor(
            if (dark) android.R.color.system_neutral1_100 else android.R.color.system_neutral1_900)
        val cursor = context.getColor(
            if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        props["background"] = hex(background)
        props["foreground"] = hex(foreground)
        props["cursor"] = hex(cursor)
        if (!dark) {
            // Termux's white and bright white would vanish on a light background.
            props["color7"] = hex(context.getColor(android.R.color.system_neutral1_700))
            props["color15"] = hex(context.getColor(android.R.color.system_neutral1_900))
        }
    }

    private fun hex(color: Int) = String.format(java.util.Locale.ROOT, "#%06x", color and 0xffffff)

    fun background(): Int = TerminalColors.COLOR_SCHEME.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND]
}
