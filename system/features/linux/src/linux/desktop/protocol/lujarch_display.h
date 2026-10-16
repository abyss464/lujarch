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

/*
 * lujarch_display.h: the display protocol of the Linux development environment's desktop (LNX-6).
 * Version 1. "ljd" is Lujarch display.
 *
 * Three parties:
 *   app       the Terminal app's DesktopActivity (org.lujarch.linux.terminal): allocates the
 *             buffers (AHardwareBuffers), presents them with SurfaceControl, sends configure, frame
 *             timing and input.
 *   producer  KWin's Lujarch backend in Linux (kwin/lujarch): imports the buffers (EGL dma-buf
 *             import with the modifier the app read from each buffer), renders, returns them with
 *             a sync_file.
 *   host      lujarch_linux: relays the messages and their fds between one app and one producer.
 *
 * Connection.
 *   producer  connects to the stream socket LJD_SOCKET_PATH in Linux and sends one line,
 *             LJD_REQUEST_PRODUCER. The host answers one line, {"ok":true}\n or
 *             {"ok":false,"error":"<code>"}\n, and from then on the connection carries binary
 *             messages in both directions. Only the owner's user and root in Linux may produce.
 *   app       gets its end of a socket pair from ILinux.attachDisplay(); it carries binary
 *             messages from the start.
 * A newer connection of the same role replaces the older one (the host closes it).
 *
 * Message: struct ljd_header, then `size` payload bytes. A message and its fds are sent with ONE
 * sendmsg(); the fds travel as SCM_RIGHTS on it, `nfds` of them. A receiver keeps the fds it gets
 * in a FIFO queue and takes `nfds` from the front for each complete message (on a stream socket a
 * recvmsg may return fds together with the end of the previous message). Integers are in the
 * machine's byte order (everything runs on the same phone). Unknown types are skipped by size by
 * the endpoints (their fds closed); the host drops a connection that sends a type that is not
 * known to it or not allowed in that direction, a size over LJD_MAX_PAYLOAD or nfds over
 * LJD_MAX_FDS.
 *
 * Peers. The host sends LJD_PEER {connected=1} to each side when the other side is there (also
 * right after the connection starts when it already is) and {connected=0} when it goes. On
 * connected=1 the app sends LJD_CONFIGURE and LJD_POOL. On connected=0 the producer drops its pool
 * (closes every imported buffer) and the app hides its layer and takes every buffer back.
 *
 * Buffers. The app allocates a pool of LJD_POOL_SIZE buffers at the view's size in pixels,
 * RGBA8888 (DRM_FORMAT_ABGR8888), usage GPU_FRAMEBUFFER | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY, on
 * every size change (resize, rotation). LJD_POOL carries the layout read from each buffer's native
 * handle: one dma-buf fd per buffer (single plane), its stride in bytes, offset, size, and the DRM
 * modifier (DRM_FORMAT_MOD_LINEAR or DRM_FORMAT_MOD_QCOM_COMPRESSED (UBWC)). Import it with
 * exactly that modifier and plane layout. A new pool replaces the previous one: the producer drops
 * every buffer of the old pool (no LJD_RELEASE comes for them) and every buffer of the new one is
 * free.
 *
 * Frame cycle.
 *   1. The producer renders into a free buffer and sends LJD_PRESENT {pool, index, seq, damage}
 *      with its acquire fence (a sync_file that signals when the rendering is done; LJD_F_FENCE and
 *      nfds 1), or with no fence after waiting for the rendering itself. The buffer now belongs to
 *      the app.
 *   2. The host passes every fence on as a sync_file of its own (SYNC_IOC_MERGE of the fence with
 *      itself: same fence, new file), so each side receives fences only the host made.
 *   3. At each vsync (AChoreographer) the app puts the newest LJD_PRESENT into one SurfaceControl
 *      transaction (setBuffer with the fence, the damage). A present that a newer one replaced
 *      before its vsync is not shown: LJD_PRESENTED with LJD_F_DISCARDED, LJD_RELEASE with no
 *      fence.
 *   4. When SurfaceFlinger releases a buffer the app sends LJD_RELEASE {pool, index} with the
 *      release fence (LJD_F_FENCE, nfds 1) or none; the producer may write the buffer once that
 *      fence has signalled.
 *   5. Pacing: the app sends LJD_FRAME at the vsync in which it applied a present, and at the next
 *      vsync after an LJD_REQUEST_FRAME: "render the next frame now" (the Wayland frame callback).
 *      A producer that renders continuously renders one frame per LJD_FRAME. An idle producer that
 *      gets new content may also present at once without waiting for a FRAME.
 *   6. LJD_PRESENTED {seq, present time} follows when the frame was on screen (presentation
 *      feedback).
 * A present that names an old pool, a bad index or a buffer the app holds is released at once
 * (LJD_RELEASE without a fence) and logged.
 *
 * Configure. LJD_CONFIGURE {size, rotation, density, refresh, visible} right after the app's
 * connection starts, on LJD_PEER {connected=1} and on every change. The host reads `visible` from
 * every configure and passes the configure on while a producer is there. The buffers are always
 * upright for the current rotation (the app allocates them at the rotated size), so `rotation` is
 * information, not a transform to apply. `visible` 0: the view is not on screen; render nothing
 * until it is 1 again. While no app is attached or the app's last configure says 0, the host
 * freezes the desktop's cgroup; it thaws it before it passes on a configure that says 1.
 *
 * Input (app -> producer). Touch: one LJD_TOUCH per Android MotionEvent, i.e. per touch frame
 * (wl_touch.frame), with every pointer that changed; coordinates in buffer pixels, ids stable
 * while the finger is down. Keys: LJD_KEY with Linux evdev key codes (KEY_*), from hardware
 * keyboards (Android's scan code); text input comes from Linux's on-screen keyboard, not from
 * Android's. Mouse: one LJD_POINTER per Android mouse event (hover, move, buttons, wheel) instead
 * of touch, with the position in buffer pixels, the buttons held and the wheel of this event;
 * LJD_POINTER_LEAVE when the pointer left the view or the mouse went away. The producer adds a
 * pointer device at the first LJD_POINTER and removes it after a leave, so tablet mode holds while
 * no mouse is used.
 */
#ifndef LUJARCH_DISPLAY_H
#define LUJARCH_DISPLAY_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LJD_VERSION 1
#define LJD_SOCKET_PATH "/run/lujarch/display"
#define LJD_REQUEST_PRODUCER "{\"cmd\":\"display.produce\",\"version\":1}\n"

#define LJD_MAX_PAYLOAD 4096
#define LJD_MAX_FDS 4
#define LJD_POOL_SIZE 3
#define LJD_MAX_BUFFERS 4
#define LJD_MAX_DAMAGE 16
#define LJD_MAX_TOUCH 16

/* DRM fourcc and modifiers (drm_fourcc.h values, repeated so that no libdrm header is needed). */
#define LJD_FORMAT_ABGR8888 0x34324241u /* 'AB24': R, G, B, A bytes in memory (AHB RGBA_8888) */
#define LJD_MOD_LINEAR 0ull
#define LJD_MOD_QCOM_COMPRESSED 0x0500000000000001ull /* fourcc_mod_code(QCOM, 1): UBWC */

/* Flags (ljd_present.flags, ljd_release.flags, ljd_presented.flags). */
#define LJD_F_FENCE 0x1u     /* one sync_file fd travels with the message */
#define LJD_F_DISCARDED 0x2u /* ljd_presented: replaced before it was shown */

/* Flags of ljd_pointer.flags. */
#define LJD_POINTER_LEAVE 0x1u /* the pointer left (or the mouse went away) */

enum ljd_type {
    /* host -> app, host -> producer */
    LJD_PEER = 0x01,
    /* app -> producer */
    LJD_CONFIGURE = 0x10,
    LJD_POOL = 0x11,      /* nfds = count: the dma-buf of each buffer, in index order */
    LJD_RELEASE = 0x12,   /* nfds 0 or 1 (LJD_F_FENCE): the release fence */
    LJD_FRAME = 0x13,
    LJD_PRESENTED = 0x14,
    LJD_TOUCH = 0x20,
    LJD_KEY = 0x21,
    LJD_POINTER = 0x22,   /* a mouse event */
    /* producer -> app */
    LJD_PRESENT = 0x40,   /* nfds 0 or 1 (LJD_F_FENCE): the acquire fence */
    LJD_REQUEST_FRAME = 0x41,
};

struct ljd_header {
    uint16_t type;  /* enum ljd_type */
    uint16_t nfds;  /* fds sent with this message */
    uint32_t size;  /* payload bytes after the header */
};

struct ljd_peer {
    uint32_t connected; /* 1: the other side is there; 0: it went away */
    uint32_t reserved;
};

struct ljd_configure {
    uint32_t width, height;   /* the view, in pixels: the size of the pool's buffers */
    uint32_t rotation;        /* display rotation, 0..3 = 0, 90, 180, 270 degrees */
    uint32_t density_dpi;     /* Android's density; scale = density_dpi / 160 */
    uint32_t refresh_mhz;     /* display refresh rate in millihertz (120000 at 120 Hz) */
    uint32_t visible;         /* 1: on screen */
};

struct ljd_buffer {
    uint32_t stride;          /* bytes per row */
    uint32_t offset;          /* of the plane in the dma-buf */
    uint64_t size;            /* of the dma-buf in bytes */
};

struct ljd_pool {
    uint32_t pool_id;         /* increases with every pool of this connection */
    uint32_t count;           /* buffers, <= LJD_MAX_BUFFERS; fds follow in index order */
    uint32_t width, height;
    uint32_t format;          /* LJD_FORMAT_ABGR8888 */
    uint32_t reserved;
    uint64_t modifier;        /* LJD_MOD_LINEAR or LJD_MOD_QCOM_COMPRESSED */
    struct ljd_buffer buffers[LJD_MAX_BUFFERS]; /* the first `count` are valid */
};

struct ljd_rect {
    int32_t x, y, width, height;
};

struct ljd_present {
    uint32_t pool_id;
    uint32_t index;
    uint32_t seq;             /* the producer's frame number, echoed in LJD_FRAME/LJD_PRESENTED */
    uint32_t flags;           /* LJD_F_FENCE */
    uint32_t damage_count;    /* 0: the whole buffer changed */
    uint32_t reserved;
    struct ljd_rect damage[]; /* damage_count rects, buffer pixels, <= LJD_MAX_DAMAGE */
};

struct ljd_release {
    uint32_t pool_id;
    uint32_t index;
    uint32_t flags;           /* LJD_F_FENCE */
    uint32_t reserved;
};

struct ljd_frame {
    uint32_t seq;             /* the last present the app applied (0: none yet) */
    uint32_t reserved;
    int64_t vsync_ns;         /* this vsync, CLOCK_MONOTONIC */
    int64_t deadline_ns;      /* when a present must reach the app to be shown at the next vsync */
    int64_t refresh_ns;       /* vsync period */
};

struct ljd_presented {
    uint32_t seq;
    uint32_t flags;           /* LJD_F_DISCARDED */
    int64_t present_ns;       /* when it reached the screen (CLOCK_MONOTONIC); 0: unknown */
};

enum ljd_touch_type {
    LJD_TOUCH_DOWN = 0,
    LJD_TOUCH_MOTION = 1,
    LJD_TOUCH_UP = 2,
    LJD_TOUCH_CANCEL = 3,     /* every point of the touch sequence is cancelled */
};

struct ljd_touch_point {
    uint32_t type;            /* enum ljd_touch_type */
    int32_t id;               /* pointer id, stable from down to up */
    float x, y;               /* buffer pixels */
};

struct ljd_touch {
    int64_t time_ns;          /* event time, CLOCK_MONOTONIC */
    uint32_t count;           /* <= LJD_MAX_TOUCH */
    uint32_t reserved;
    struct ljd_touch_point points[]; /* one frame (wl_touch.frame) */
};

struct ljd_key {
    int64_t time_ns;
    uint32_t keycode;         /* Linux evdev KEY_* */
    uint32_t pressed;         /* 1 down (repeats come as more downs), 0 up */
};

struct ljd_pointer {
    int64_t time_ns;      /* event time, CLOCK_MONOTONIC */
    float x, y;           /* buffer pixels */
    uint32_t buttons;     /* held: 1 left, 2 right, 4 middle, 8 back, 16 forward */
    int32_t scroll_v120;  /* vertical wheel this event, 120 per detent, positive down */
    int32_t scroll_h120;  /* horizontal wheel, positive right */
    uint32_t flags;       /* LJD_POINTER_LEAVE 0x1: the pointer left (or the mouse went away) */
};

#ifdef __cplusplus
}
#endif

#endif /* LUJARCH_DISPLAY_H */
