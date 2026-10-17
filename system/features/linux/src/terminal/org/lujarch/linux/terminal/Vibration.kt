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
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import org.lujarch.linux.client.Linux
import org.lujarch.linux.client.TAG

/**
 * Linux's vibration (LNX-7, Vibration): "<amplitude 0-255>:<ms>,..." or "<ms>", which
 * lujarch_linux checked. One short step plays Android's click, longer patterns a waveform. Every
 * vibration has the touch usage, so Android's touch feedback switch and intensity apply to it.
 * Not logged: a keyboard sends one for each key.
 */
class Vibration(context: Context) {
    private val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val touch = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    private var warned = false

    fun play(pattern: String) {
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            if (!warned) Log.w(TAG, "vibration: the phone has no vibrator")
            warned = true
            return
        }
        val steps = pattern.split(',').mapNotNull { step ->
            val colon = step.indexOf(':')
            val amplitude = if (colon < 0) VibrationEffect.DEFAULT_AMPLITUDE else step.substring(0, colon).toIntOrNull()
            val ms = (if (colon < 0) step else step.substring(colon + 1)).toLongOrNull()
            if (amplitude == null || ms == null) null else amplitude to ms
        }
        if (steps.none { it.first != 0 && it.second > 0 }) return
        val effect = if (steps.size == 1) {
            val (amplitude, ms) = steps[0]
            if (ms <= TICK_MS) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            else VibrationEffect.createOneShot(ms, amplitude)
        } else {
            VibrationEffect.createWaveform(
                LongArray(steps.size) { steps[it].second },
                IntArray(steps.size) { steps[it].first },
                -1,
            )
        }
        v.vibrate(effect, touch)
    }

    private companion object {
        /** A key press: Android's click effect, one crisp tick. */
        const val TICK_MS = 20L
    }
}
