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

import java.nio.ByteBuffer

/**
 * YUV_420_888 to NV12 in native code (jni/camera_jni.cpp): the Y plane, then U and V
 * interleaved, honouring the planes' row and pixel strides, and turned clockwise by `rotation`
 * (0, 90, 180, 270 degrees) on the way, straight into the buffer that goes to Linux at [offset].
 */
internal object Nv12 {
    init {
        System.loadLibrary("lujarch_linux_display_jni")
    }

    /**
     * [y], [u], [v]: the image's direct plane buffers; [out]: at least width * height * 3 / 2
     * bytes after [offset]. False when the layout is not one it can read.
     */
    @JvmStatic external fun nativeConvert(
        y: ByteBuffer, yRowStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int, rotation: Int, out: ByteArray, offset: Int,
    ): Boolean

    /**
     * Like [nativeConvert], into a frame of [outWidth] x [outHeight]: the turned picture scaled to
     * cover it, centred, the overhang cropped (nearest sample).
     */
    @JvmStatic external fun nativeFit(
        y: ByteBuffer, yRowStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int, rotation: Int, outWidth: Int, outHeight: Int, out: ByteArray, offset: Int,
    ): Boolean
}
