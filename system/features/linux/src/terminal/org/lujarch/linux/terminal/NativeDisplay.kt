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

import android.view.Surface

/**
 * The desktop's native presenter (jni/display_jni.cpp): the display protocol's app side, the
 * buffer pool and the SurfaceControl child of the view's surface. Every call returns at once; the
 * presenter's own thread does the work.
 */
object NativeDisplay {
    init {
        System.loadLibrary("lujarch_linux_display_jni")
    }

    // ljd_touch_type
    const val TOUCH_DOWN = 0
    const val TOUCH_MOTION = 1
    const val TOUCH_UP = 2
    const val TOUCH_CANCEL = 3

    // ljd_pointer.flags
    const val POINTER_LEAVE = 1

    /** A presenter on [surface]'s layer; 0 on failure. */
    @JvmStatic external fun nativeCreate(surface: Surface): Long

    @JvmStatic external fun nativeDestroy(handle: Long)

    /** The connection ILinux.attachDisplay gave; the presenter owns [fd] from now on. */
    @JvmStatic external fun nativeAttach(handle: Long, fd: Int)

    /** Whether the presenter has a connection to lujarch_linux. */
    @JvmStatic external fun nativeAttached(handle: Long): Boolean

    /** Whether KWin was there at least once on this presenter's connections. */
    @JvmStatic external fun nativeDrawn(handle: Long): Boolean

    /** The view's size in pixels, the display's rotation (0..3), density, refresh rate (mHz), on screen. */
    @JvmStatic external fun nativeConfigure(
        handle: Long, width: Int, height: Int, rotation: Int, dpi: Int, refreshMhz: Int, visible: Boolean,
    )

    /** One touch frame: [count] points with their type, pointer id and position in buffer pixels. */
    @JvmStatic external fun nativeTouch(
        handle: Long, timeNs: Long, count: Int, types: IntArray, ids: IntArray, xs: FloatArray, ys: FloatArray,
    )

    /** A hardware key as a Linux evdev code. */
    @JvmStatic external fun nativeKey(handle: Long, timeNs: Long, keycode: Int, pressed: Boolean)

    /**
     * One mouse event: the position in buffer pixels, the buttons held (MotionEvent's button bits,
     * which are the protocol's: 1 left, 2 right, 4 middle, 8 back, 16 forward), the wheel of this
     * event in 120ths of a detent (positive down, right).
     */
    @JvmStatic external fun nativePointer(
        handle: Long, timeNs: Long, x: Float, y: Float, buttons: Int, scrollV120: Int, scrollH120: Int, flags: Int,
    )
}
