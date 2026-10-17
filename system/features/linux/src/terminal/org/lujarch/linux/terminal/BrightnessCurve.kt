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

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Android's brightness slider position (0..[GAMMA_MAX], a Hybrid Log Gamma curve) and the linear
 * brightness the display service takes: the same constants and steps as SettingsLib's
 * BrightnessUtils, so the desktop's slider and Android's stand at the same place for the same
 * brightness.
 */
object BrightnessCurve {
    const val GAMMA_MAX = 65535

    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    /** The linear brightness between [min] and [max] for slider position [gamma]. */
    fun toLinear(gamma: Int, min: Float, max: Float): Float {
        val n = gamma.coerceIn(0, GAMMA_MAX) / GAMMA_MAX.toFloat()
        val hlg = if (n <= R) {
            val x = n / R
            x * x
        } else {
            exp((n - C) / A) + B
        }
        // HLG is normalized to [0, 12]; the setting is in [0, 1].
        return min + (max - min) * (hlg.coerceIn(0f, 12f) / 12f)
    }

    /** The slider position for linear brightness [linear] between [min] and [max]. */
    fun toGamma(linear: Float, min: Float, max: Float): Int {
        val n = if (max > min) ((linear - min) / (max - min)).coerceIn(0f, 1f) * 12f else 0f
        val hlg = if (n <= 1f) sqrt(n) * R else A * ln(n - B) + C
        return (hlg * GAMMA_MAX).roundToInt().coerceIn(0, GAMMA_MAX)
    }
}
