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

import android.view.KeyEvent

/** X keysyms (X11/keysymdef.h) of Android's keys and characters, as RFB sends keys. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val DELETE = 0xffff
    const val RETURN = 0xff0d

    private val KEYS = mapOf(
        KeyEvent.KEYCODE_DEL to BACKSPACE,
        KeyEvent.KEYCODE_FORWARD_DEL to DELETE,
        KeyEvent.KEYCODE_TAB to 0xff09,
        KeyEvent.KEYCODE_ENTER to RETURN,
        KeyEvent.KEYCODE_NUMPAD_ENTER to 0xff8d,
        KeyEvent.KEYCODE_ESCAPE to 0xff1b,
        KeyEvent.KEYCODE_MOVE_HOME to 0xff50,
        KeyEvent.KEYCODE_DPAD_LEFT to 0xff51,
        KeyEvent.KEYCODE_DPAD_UP to 0xff52,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0xff53,
        KeyEvent.KEYCODE_DPAD_DOWN to 0xff54,
        KeyEvent.KEYCODE_PAGE_UP to 0xff55,
        KeyEvent.KEYCODE_PAGE_DOWN to 0xff56,
        KeyEvent.KEYCODE_MOVE_END to 0xff57,
        KeyEvent.KEYCODE_INSERT to 0xff63,
        KeyEvent.KEYCODE_SHIFT_LEFT to 0xffe1,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe2,
        KeyEvent.KEYCODE_CTRL_LEFT to 0xffe3,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe4,
        KeyEvent.KEYCODE_CAPS_LOCK to 0xffe5,
        KeyEvent.KEYCODE_META_LEFT to 0xffeb,
        KeyEvent.KEYCODE_META_RIGHT to 0xffec,
        KeyEvent.KEYCODE_ALT_LEFT to 0xffe9,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xffea,
        KeyEvent.KEYCODE_SPACE to 0x20,
        KeyEvent.KEYCODE_F1 to 0xffbe,
        KeyEvent.KEYCODE_F2 to 0xffbf,
        KeyEvent.KEYCODE_F3 to 0xffc0,
        KeyEvent.KEYCODE_F4 to 0xffc1,
        KeyEvent.KEYCODE_F5 to 0xffc2,
        KeyEvent.KEYCODE_F6 to 0xffc3,
        KeyEvent.KEYCODE_F7 to 0xffc4,
        KeyEvent.KEYCODE_F8 to 0xffc5,
        KeyEvent.KEYCODE_F9 to 0xffc6,
        KeyEvent.KEYCODE_F10 to 0xffc7,
        KeyEvent.KEYCODE_F11 to 0xffc8,
        KeyEvent.KEYCODE_F12 to 0xffc9,
    )

    /** The keysym of a key that is no character, 0 for none. */
    fun ofKey(keyCode: Int): Int = KEYS[keyCode] ?: 0

    /** The keysym of a character: Latin-1 as it is, the rest of Unicode as 0x01000000 + code point. */
    fun ofCodePoint(codePoint: Int): Int =
        when {
            codePoint == '\n'.code -> RETURN
            codePoint == '\t'.code -> 0xff09
            codePoint in 0x20..0x7e || codePoint in 0xa0..0xff -> codePoint
            else -> 0x01000000 or codePoint
        }
}
