// Copyright (C) 2026 The Lujarch Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// The cameras' frames for Linux (LNX-7, Camera; org/lujarch/linux/terminal/Nv12.kt): an
// ImageReader's YUV_420_888 image to NV12 (the Y plane, then U and V interleaved), turned clockwise
// by 0, 90, 180 or 270 degrees so that the picture is upright on the display. nativeConvert keeps
// the size (an upright landscape frame); nativeFit fills a frame of another size with the turned
// picture, centred and cropped (a portrait picture in Linux's landscape frame). The planes come as
// direct ByteBuffers with their row and pixel strides; the output goes into the Java byte[] at an
// offset, behind the stream's header. Camera2's YUV is full-range BT.601 and stays so: only
// positions change.
#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <algorithm>
#include <vector>

namespace {

// Where pixel (0, row) of the source lands in the destination, and the step from one source column to
// the next, in elements of the destination plane (a byte for Y, a U/V pair for the chroma).
struct Walk {
    ptrdiff_t base;
    ptrdiff_t step;
};

// [w] x [h] source turned by [rotation] into a destination [dw] elements wide.
Walk WalkFor(int rotation, int row, int w, int h, int dw) {
    switch (rotation) {
        case 90:  // source (c, r) -> destination (h - 1 - r, c)
            return {static_cast<ptrdiff_t>(h - 1 - row), dw};
        case 180:  // -> (w - 1 - c, h - 1 - r)
            return {static_cast<ptrdiff_t>(h - 1 - row) * w + (w - 1), -1};
        case 270:  // -> (r, w - 1 - c)
            return {static_cast<ptrdiff_t>(w - 1) * dw + row, -static_cast<ptrdiff_t>(dw)};
        default:
            return {static_cast<ptrdiff_t>(row) * w, 1};
    }
}

bool Fits(jlong capacity, int rows, int row_stride, int last_offset) {
    if (rows <= 0) return false;
    const int64_t need = static_cast<int64_t>(rows - 1) * row_stride + last_offset + 1;
    return capacity >= need;
}

// The planes of one image, checked against their buffers' sizes.
struct Planes {
    const uint8_t* y = nullptr;
    const uint8_t* u = nullptr;
    const uint8_t* v = nullptr;
};

bool GetPlanes(JNIEnv* env, jobject y_buf, jint y_row, jobject u_buf, jobject v_buf, jint uv_row, jint uv_pixel,
               jint width, jint height, Planes* out) {
    if (width <= 0 || height <= 0 || (width & 1) || (height & 1)) return false;
    if (uv_pixel < 1 || y_row < width || uv_row < 1) return false;
    out->y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(y_buf));
    out->u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(u_buf));
    out->v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(v_buf));
    if (out->y == nullptr || out->u == nullptr || out->v == nullptr) return false;
    const int w2 = width / 2;
    const int h2 = height / 2;
    return Fits(env->GetDirectBufferCapacity(y_buf), height, y_row, width - 1) &&
           Fits(env->GetDirectBufferCapacity(u_buf), h2, uv_row, (w2 - 1) * uv_pixel) &&
           Fits(env->GetDirectBufferCapacity(v_buf), h2, uv_row, (w2 - 1) * uv_pixel);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL Java_org_lujarch_linux_terminal_Nv12_nativeConvert(
        JNIEnv* env, jclass, jobject y_buf, jint y_row, jobject u_buf, jobject v_buf, jint uv_row, jint uv_pixel,
        jint width, jint height, jint rotation, jbyteArray out, jint offset) {
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) return JNI_FALSE;
    if (offset < 0) return JNI_FALSE;
    Planes planes;
    if (!GetPlanes(env, y_buf, y_row, u_buf, v_buf, uv_row, uv_pixel, width, height, &planes)) return JNI_FALSE;
    const uint8_t* y = planes.y;
    const uint8_t* u = planes.u;
    const uint8_t* v = planes.v;
    const int w2 = width / 2;
    const int h2 = height / 2;
    const size_t frame = static_cast<size_t>(width) * height * 3 / 2;
    if (static_cast<size_t>(env->GetArrayLength(out)) < frame + static_cast<size_t>(offset)) return JNI_FALSE;

    const bool turned = rotation == 90 || rotation == 270;
    const int dw = turned ? height : width;  // destination width in pixels
    const int dh = turned ? width : height;
    const int dw2 = dw / 2;

    auto* base = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(out, nullptr));
    if (base == nullptr) return JNI_FALSE;
    uint8_t* dst = base + offset;
    uint8_t* oy = dst;
    uint8_t* ouv = dst + static_cast<size_t>(dw) * dh;

    // Y.
    for (int r = 0; r < height; ++r) {
        const uint8_t* s = y + static_cast<ptrdiff_t>(r) * y_row;
        if (rotation == 0) {
            memcpy(oy + static_cast<ptrdiff_t>(r) * width, s, width);
            continue;
        }
        const Walk walk = WalkFor(rotation, r, width, height, dw);
        uint8_t* d = oy + walk.base;
        for (int c = 0; c < width; ++c) d[c * walk.step] = s[c];
    }

    // U and V. Upright with interleaved planes in NV12 order (U first, V one byte after it): row copies.
    if (rotation == 0 && uv_pixel == 2 && v == u + 1) {
        for (int r = 0; r < h2; ++r) memcpy(ouv + static_cast<ptrdiff_t>(r) * width, u + static_cast<ptrdiff_t>(r) * uv_row, width);
    } else {
        for (int r = 0; r < h2; ++r) {
            const uint8_t* su = u + static_cast<ptrdiff_t>(r) * uv_row;
            const uint8_t* sv = v + static_cast<ptrdiff_t>(r) * uv_row;
            const Walk walk = WalkFor(rotation, r, w2, h2, dw2);
            uint8_t* d = ouv + walk.base * 2;
            const ptrdiff_t step = walk.step * 2;
            for (int c = 0; c < w2; ++c) {
                d[c * step] = su[c * uv_pixel];
                d[c * step + 1] = sv[c * uv_pixel];
            }
        }
    }

    env->ReleasePrimitiveArrayCritical(out, base, 0);
    return JNI_TRUE;
}

// [width] x [height] turned by [rotation], scaled so that it covers [out_w] x [out_h], centred, the overhang
// cropped; nearest sample. Every output position maps to a source offset that is the sum of a term of its
// column and a term of its row, whatever the turn, so two small tables per plane drive the loops.
extern "C" JNIEXPORT jboolean JNICALL Java_org_lujarch_linux_terminal_Nv12_nativeFit(
        JNIEnv* env, jclass, jobject y_buf, jint y_row, jobject u_buf, jobject v_buf, jint uv_row, jint uv_pixel,
        jint width, jint height, jint rotation, jint out_w, jint out_h, jbyteArray out, jint offset) {
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) return JNI_FALSE;
    if (out_w <= 0 || out_h <= 0 || (out_w & 1) || (out_h & 1) || offset < 0) return JNI_FALSE;
    Planes planes;
    if (!GetPlanes(env, y_buf, y_row, u_buf, v_buf, uv_row, uv_pixel, width, height, &planes)) return JNI_FALSE;
    const size_t frame = static_cast<size_t>(out_w) * out_h * 3 / 2;
    if (static_cast<size_t>(env->GetArrayLength(out)) < frame + static_cast<size_t>(offset)) return JNI_FALSE;

    const bool turned = rotation == 90 || rotation == 270;
    const int uw = turned ? height : width;  // the upright picture
    const int uh = turned ? width : height;
    const double scale = std::max(static_cast<double>(out_w) / uw, static_cast<double>(out_h) / uh);
    const double ox = (uw - out_w / scale) / 2;
    const double oy = (uh - out_h / scale) / 2;
    std::vector<int> ux(out_w), uy(out_h);
    for (int x = 0; x < out_w; ++x) ux[x] = std::clamp(static_cast<int>(ox + (x + 0.5) / scale), 0, uw - 1);
    for (int y = 0; y < out_h; ++y) uy[y] = std::clamp(static_cast<int>(oy + (y + 0.5) / scale), 0, uh - 1);

    // Upright (ux, uy) is source column c, row r:
    //   0: (ux, uy)   90: (uy, h-1-ux)   180: (w-1-ux, h-1-uy)   270: (w-1-uy, ux)
    // Luma offset r * y_row + c; chroma (r / 2) * uv_row + (c / 2) * uv_pixel.
    const int w = width, h = height;
    std::vector<ptrdiff_t> lx(out_w), ly(out_h), cx(out_w / 2), cy(out_h / 2);
    for (int x = 0; x < out_w; ++x) {
        const int a = ux[x];
        switch (rotation) {
            case 90: lx[x] = static_cast<ptrdiff_t>(h - 1 - a) * y_row; break;
            case 180: lx[x] = w - 1 - a; break;
            case 270: lx[x] = static_cast<ptrdiff_t>(a) * y_row; break;
            default: lx[x] = a; break;
        }
    }
    for (int y = 0; y < out_h; ++y) {
        const int b = uy[y];
        switch (rotation) {
            case 90: ly[y] = b; break;
            case 180: ly[y] = static_cast<ptrdiff_t>(h - 1 - b) * y_row; break;
            case 270: ly[y] = w - 1 - b; break;
            default: ly[y] = static_cast<ptrdiff_t>(b) * y_row; break;
        }
    }
    for (int x = 0; x < out_w / 2; ++x) {
        const int a = ux[2 * x];
        switch (rotation) {
            case 90: cx[x] = static_cast<ptrdiff_t>((h - 1 - a) / 2) * uv_row; break;
            case 180: cx[x] = static_cast<ptrdiff_t>((w - 1 - a) / 2) * uv_pixel; break;
            case 270: cx[x] = static_cast<ptrdiff_t>(a / 2) * uv_row; break;
            default: cx[x] = static_cast<ptrdiff_t>(a / 2) * uv_pixel; break;
        }
    }
    for (int y = 0; y < out_h / 2; ++y) {
        const int b = uy[2 * y];
        switch (rotation) {
            case 90: cy[y] = static_cast<ptrdiff_t>(b / 2) * uv_pixel; break;
            case 180: cy[y] = static_cast<ptrdiff_t>((h - 1 - b) / 2) * uv_row; break;
            case 270: cy[y] = static_cast<ptrdiff_t>((w - 1 - b) / 2) * uv_pixel; break;
            default: cy[y] = static_cast<ptrdiff_t>(b / 2) * uv_row; break;
        }
    }

    auto* base = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(out, nullptr));
    if (base == nullptr) return JNI_FALSE;
    uint8_t* dst = base + offset;
    uint8_t* oyp = dst;
    uint8_t* ouv = dst + static_cast<size_t>(out_w) * out_h;
    for (int y = 0; y < out_h; ++y) {
        const uint8_t* s = planes.y + ly[y];
        uint8_t* d = oyp + static_cast<ptrdiff_t>(y) * out_w;
        for (int x = 0; x < out_w; ++x) d[x] = s[lx[x]];
    }
    for (int y = 0; y < out_h / 2; ++y) {
        const uint8_t* su = planes.u + cy[y];
        const uint8_t* sv = planes.v + cy[y];
        uint8_t* d = ouv + static_cast<ptrdiff_t>(y) * out_w;
        for (int x = 0; x < out_w / 2; ++x) {
            d[2 * x] = su[cx[x]];
            d[2 * x + 1] = sv[cx[x]];
        }
    }
    env->ReleasePrimitiveArrayCritical(out, base, 0);
    return JNI_TRUE;
}
