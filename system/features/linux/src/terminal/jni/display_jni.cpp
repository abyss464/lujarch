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

// The desktop's presenter in DesktopActivity (LNX-6): the app side of the display protocol
// (lujarch_display.h). One native thread with its own ALooper owns everything: the connection
// lujarch_linux hands the app (ILinux.attachDisplay), the buffer pool (AHardwareBuffers), the
// SurfaceControl child of the SurfaceView's surface, and the Choreographer vsync callbacks that
// pace the transactions. The UI thread and SurfaceFlinger's callbacks hand it work through a
// locked queue and an eventfd.
#include <android/choreographer.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/looper.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/sync_file.h>
#include <poll.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <deque>
#include <functional>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "lujarch_display.h"

#define TAG "lujarch_linux"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// native_handle_t of <cutils/native_handle.h>; AHardwareBuffer_getNativeHandle is a system API of
// libnativewindow (not in the NDK stubs), looked up at run time.
struct NativeHandle {
    int version, numFds, numInts;
    int data[0];
};
using GetNativeHandleFn = const NativeHandle* (*)(const AHardwareBuffer*);

GetNativeHandleFn GetNativeHandle() {
    static GetNativeHandleFn fn = [] {
        void* lib = dlopen("libnativewindow.so", RTLD_NOW);
        return lib ? reinterpret_cast<GetNativeHandleFn>(dlsym(lib, "AHardwareBuffer_getNativeHandle")) : nullptr;
    }();
    return fn;
}

constexpr uint32_t kGmsm = ('g' << 24) | ('m' << 16) | ('s' << 8) | 'm';
constexpr uint32_t kQtiUbwc = 0x08000000;

int64_t NowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// A sync_file of this process for the same fence (the receivers of what we pass on, SurfaceFlinger
// and lujarch_linux, may use only files of certain creators).
int OwnFence(int fd) {
    if (fd < 0) return -1;
    sync_merge_data data{};
    strncpy(data.name, "lujarch_display", sizeof(data.name) - 1);
    data.fd2 = fd;
    if (ioctl(fd, SYNC_IOC_MERGE, &data) != 0) {
        LOGW("SYNC_IOC_MERGE: %s", strerror(errno));
        return dup(fd);
    }
    return data.fence;
}

enum class BufState { kProducer, kPending, kScreen };

struct Buffer {
    AHardwareBuffer* ahb = nullptr;
    BufState state = BufState::kProducer;
};

struct Presenter;

// SurfaceFlinger's callbacks can arrive after DisplayActivity destroyed the presenter: they reach
// only presenters that still live.
std::mutex g_live_mu;
std::set<Presenter*> g_live;

struct ReleaseCtx {
    Presenter* p;
    uint32_t pool_id, index;
};

struct Presenter {
    ASurfaceControl* sc = nullptr;
    ALooper* looper = nullptr;
    int wake_fd = -1;
    std::thread thread;
    bool quit = false;

    std::mutex mu;
    std::deque<std::function<void()>> work;  // runs on the presenter's thread

    // Read by the UI thread.
    std::atomic<bool> connected{false};
    std::atomic<bool> peer_seen{false};

    // The presenter's thread only.
    int sock = -1;
    bool peer = false;
    std::string in;
    std::deque<int> in_fds;
    std::deque<std::pair<std::string, std::vector<int>>> out;
    size_t out_off = 0;
    uint32_t width = 0, height = 0, rotation = 0, dpi = 0, refresh_mhz = 120000;
    bool visible = false;
    bool linear = true;  // CPU_READ_RARELY keeps gralloc on a linear layout
    uint32_t pool_id = 0;
    uint64_t modifier = 0;
    std::vector<Buffer> pool;
    bool shown = false;  // the layer is visible
    // The newest present not yet in a transaction.
    bool has_pending = false;
    uint32_t pend_index = 0, pend_seq = 0;
    int pend_fence = -1;
    std::vector<ARect> pend_damage;
    bool frame_requested = false;
    bool vsync_posted = false;
    uint32_t last_seq = 0;
    int64_t last_vsync = 0;      // frame time of the latest Choreographer callback
    int64_t last_deadline = 0;
    int64_t applied_vsync = -1;  // the vsync period in which the last transaction was applied
    int64_t last_present_rx = 0; // when the latest LJD_PRESENT arrived
    int64_t refresh_ns = 8333333;
    uint64_t frames = 0;

    void Post(std::function<void()> fn) {
        {
            std::lock_guard<std::mutex> l(mu);
            work.push_back(std::move(fn));
        }
        uint64_t one = 1;
        (void)!write(wake_fd, &one, sizeof(one));
    }

    void Run();
    void Wake();
    void Attach(int fd);
    void Disconnect(const char* why);
    void Readable();
    void Flush();
    void Send(uint16_t type, const void* payload, size_t size, std::vector<int> fds = {});
    void Handle(const ljd_header& h, const char* payload, std::vector<int>& fds);
    void OnPresent(const ljd_present& pr, const ljd_rect* rects, int fence);
    void SendConfigure();
    void NewPool();
    void FreePool();
    void Release(uint32_t pid, uint32_t index, int fence);
    void Hide();
    void RequestVsync();
    void OnVsync(const AChoreographerFrameCallbackData* data);
    void Apply();
};

int OnLooperFd(int fd, int events, void* data);

void Presenter::Run() {
    looper = ALooper_prepare(0);
    ALooper_acquire(looper);
    ALooper_addFd(looper, wake_fd, 1, ALOOPER_EVENT_INPUT, OnLooperFd, this);
    LOGI("presenter thread up");
    while (!quit) {
        ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
    }
    Disconnect("stopping");
    FreePool();
    if (sc != nullptr) {
        ASurfaceTransaction* t = ASurfaceTransaction_create();
        ASurfaceTransaction_reparent(t, sc, nullptr);
        ASurfaceTransaction_apply(t);
        ASurfaceTransaction_delete(t);
        ASurfaceControl_release(sc);
        sc = nullptr;
    }
    ALooper_removeFd(looper, wake_fd);
    ALooper_release(looper);
    LOGI("presenter thread down");
}

int OnLooperFd(int fd, int events, void* data) {
    auto* p = static_cast<Presenter*>(data);
    if (fd == p->wake_fd) {
        p->Wake();
        return 1;
    }
    if (fd != p->sock) return 0;  // a closed connection: unregister
    if (events & (ALOOPER_EVENT_INPUT | ALOOPER_EVENT_HANGUP | ALOOPER_EVENT_ERROR)) p->Readable();
    if (p->sock == fd && (events & ALOOPER_EVENT_OUTPUT)) p->Flush();
    return 1;
}

void Presenter::Wake() {
    uint64_t v;
    (void)!read(wake_fd, &v, sizeof(v));
    std::deque<std::function<void()>> todo;
    {
        std::lock_guard<std::mutex> l(mu);
        todo.swap(work);
    }
    for (auto& fn : todo) fn();
}

// A connection from lujarch_linux: binary messages from the start. The configure goes first, so
// that lujarch_linux knows at once whether the desktop is on screen.
void Presenter::Attach(int fd) {
    Disconnect("replaced");
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    sock = fd;
    in.clear();
    connected = true;
    ALooper_addFd(looper, sock, 2, ALOOPER_EVENT_INPUT, OnLooperFd, this);
    LOGI("display: attached");
    if (width > 0) SendConfigure();
}

void Presenter::Disconnect(const char* why) {
    if (sock < 0) return;
    LOGI("display: detached (%s)", why);
    ALooper_removeFd(looper, sock);
    close(sock);
    sock = -1;
    connected = false;
    for (int fd : in_fds) close(fd);
    in_fds.clear();
    for (auto& m : out)
        for (int fd : m.second) close(fd);
    out.clear();
    out_off = 0;
    peer = false;
    Hide();
}

void Presenter::Readable() {
    for (int round = 0; round < 8 && sock >= 0; ++round) {
        char buf[16384];
        alignas(cmsghdr) char ctl[CMSG_SPACE(sizeof(int) * 8)];
        iovec iov{buf, sizeof(buf)};
        msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = ctl;
        msg.msg_controllen = sizeof(ctl);
        ssize_t n = recvmsg(sock, &msg, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (n == 0) return Disconnect("eof");
        if (n < 0) {
            if (errno == EAGAIN || errno == EINTR) return;
            return Disconnect(strerror(errno));
        }
        for (cmsghdr* cm = CMSG_FIRSTHDR(&msg); cm; cm = CMSG_NXTHDR(&msg, cm)) {
            if (cm->cmsg_level != SOL_SOCKET || cm->cmsg_type != SCM_RIGHTS) continue;
            size_t count = (cm->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            const int* fds = reinterpret_cast<const int*>(CMSG_DATA(cm));
            for (size_t i = 0; i < count; ++i) in_fds.push_back(fds[i]);
        }
        if (msg.msg_flags & MSG_CTRUNC) LOGE("display: fds from lujarch_linux were refused");
        in.append(buf, n);
        size_t pos = 0;
        while (in.size() - pos >= sizeof(ljd_header)) {
            ljd_header h;
            memcpy(&h, in.data() + pos, sizeof(h));
            if (h.size > LJD_MAX_PAYLOAD || h.nfds > LJD_MAX_FDS) return Disconnect("bad message");
            if (in.size() - pos < sizeof(h) + h.size) break;
            std::vector<int> fds;
            for (int i = 0; i < h.nfds && !in_fds.empty(); ++i) {
                fds.push_back(in_fds.front());
                in_fds.pop_front();
            }
            std::string payload = in.substr(pos + sizeof(h), h.size);
            pos += sizeof(h) + h.size;
            Handle(h, payload.data(), fds);
            for (int fd : fds) close(fd);
            if (sock < 0) return;
        }
        in.erase(0, pos);
    }
}

void Presenter::Send(uint16_t type, const void* payload, size_t size, std::vector<int> fds) {
    if (sock < 0) {
        for (int fd : fds) close(fd);
        return;
    }
    ljd_header h{type, static_cast<uint16_t>(fds.size()), static_cast<uint32_t>(size)};
    std::string bytes(sizeof(h) + size, '\0');
    memcpy(bytes.data(), &h, sizeof(h));
    if (size) memcpy(bytes.data() + sizeof(h), payload, size);
    out.emplace_back(std::move(bytes), std::move(fds));
    Flush();
}

void Presenter::Flush() {
    while (sock >= 0 && !out.empty()) {
        auto& m = out.front();
        iovec iov{m.first.data() + out_off, m.first.size() - out_off};
        msghdr msg{};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        alignas(cmsghdr) char ctl[CMSG_SPACE(sizeof(int) * LJD_MAX_FDS)];
        if (!m.second.empty()) {
            msg.msg_control = ctl;
            msg.msg_controllen = CMSG_SPACE(sizeof(int) * m.second.size());
            cmsghdr* cm = CMSG_FIRSTHDR(&msg);
            cm->cmsg_level = SOL_SOCKET;
            cm->cmsg_type = SCM_RIGHTS;
            cm->cmsg_len = CMSG_LEN(sizeof(int) * m.second.size());
            memcpy(CMSG_DATA(cm), m.second.data(), sizeof(int) * m.second.size());
        }
        ssize_t n = sendmsg(sock, &msg, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN) {
                ALooper_addFd(looper, sock, 2, ALOOPER_EVENT_INPUT | ALOOPER_EVENT_OUTPUT, OnLooperFd, this);
                return;
            }
            return Disconnect(strerror(errno));
        }
        // Every fd queued here is ours: dups of the buffers' dma-bufs, fences.
        for (int fd : m.second) if (fd >= 0) close(fd);
        m.second.clear();
        out_off += n;
        if (out_off < m.first.size()) continue;
        out.pop_front();
        out_off = 0;
    }
    if (sock >= 0) ALooper_addFd(looper, sock, 2, ALOOPER_EVENT_INPUT, OnLooperFd, this);
}

void Presenter::Handle(const ljd_header& h, const char* payload, std::vector<int>& fds) {
    switch (h.type) {
        case LJD_PEER: {
            if (h.size < sizeof(ljd_peer)) return;
            ljd_peer p;
            memcpy(&p, payload, sizeof(p));
            peer = p.connected != 0;
            if (peer) peer_seen = true;
            LOGI("display: KWin %s", peer ? "connected" : "gone");
            if (peer) {
                SendConfigure();
                NewPool();
            } else {
                Hide();
            }
            return;
        }
        case LJD_PRESENT: {
            if (h.size < sizeof(ljd_present)) return;
            ljd_present pr;
            memcpy(&pr, payload, sizeof(pr));
            uint32_t n = pr.damage_count;
            if (n > LJD_MAX_DAMAGE || sizeof(pr) + n * sizeof(ljd_rect) > h.size) n = 0;
            std::vector<ljd_rect> rects(n);
            if (n) memcpy(rects.data(), payload + sizeof(pr), n * sizeof(ljd_rect));
            int fence = -1;
            if ((pr.flags & LJD_F_FENCE) && !fds.empty()) {
                fence = fds[0];
                fds[0] = -1;
                fds.erase(fds.begin());
            }
            OnPresent(pr, rects.data(), fence);
            return;
        }
        case LJD_REQUEST_FRAME:
            frame_requested = true;
            RequestVsync();
            return;
        default:
            return;  // unknown: skipped
    }
}

void Presenter::SendConfigure() {
    ljd_configure c{width, height, rotation, dpi, refresh_mhz, visible ? 1u : 0u};
    Send(LJD_CONFIGURE, &c, sizeof(c));
}

void Presenter::FreePool() {
    if (has_pending) {
        if (pend_fence >= 0) close(pend_fence);
        pend_fence = -1;
        has_pending = false;
    }
    for (Buffer& b : pool) {
        if (b.ahb) AHardwareBuffer_release(b.ahb);  // SurfaceFlinger keeps its own reference
    }
    pool.clear();
}

void Presenter::NewPool() {
    FreePool();
    if (width == 0 || height == 0) return;
    ++pool_id;
    ljd_pool msg{};
    msg.pool_id = pool_id;
    msg.width = width;
    msg.height = height;
    msg.format = LJD_FORMAT_ABGR8888;
    std::vector<int> fds;
    GetNativeHandleFn handle_of = GetNativeHandle();
    if (handle_of == nullptr) {
        LOGE("AHardwareBuffer_getNativeHandle is missing");
        return;
    }
    for (uint32_t i = 0; i < LJD_POOL_SIZE; ++i) {
        AHardwareBuffer_Desc d{};
        d.width = width;
        d.height = height;
        d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                  AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        if (linear) d.usage |= AHARDWAREBUFFER_USAGE_CPU_READ_RARELY;
        Buffer b;
        if (AHardwareBuffer_allocate(&d, &b.ahb) != 0) {
            LOGE("AHardwareBuffer_allocate %ux%u failed", width, height);
            FreePool();
            return;
        }
        AHardwareBuffer_describe(b.ahb, &d);
        const NativeHandle* nh = handle_of(b.ahb);
        if (nh == nullptr || nh->numFds < 1) {
            LOGE("no native handle");
            AHardwareBuffer_release(b.ahb);
            FreePool();
            return;
        }
        int fd = nh->data[0];
        uint64_t mod = LJD_MOD_LINEAR;
        if (nh->numInts >= 2 && static_cast<uint32_t>(nh->data[nh->numFds]) == kGmsm &&
            (static_cast<uint32_t>(nh->data[nh->numFds + 1]) & kQtiUbwc)) {
            mod = LJD_MOD_QCOM_COMPRESSED;
        }
        off_t size = lseek(fd, 0, SEEK_END);
        lseek(fd, 0, SEEK_SET);
        msg.buffers[i].stride = d.stride * 4;
        msg.buffers[i].offset = 0;
        msg.buffers[i].size = size > 0 ? static_cast<uint64_t>(size) : 0;
        modifier = mod;
        fds.push_back(dup(fd));  // the copy that goes out (Flush closes it once sent)
        pool.push_back(b);
        LOGI("pool %u buffer %u: %ux%u stride %u bytes, size %lld, modifier 0x%llx, handle fds %d ints %d",
             pool_id, i, width, height, d.stride * 4, static_cast<long long>(size),
             static_cast<unsigned long long>(mod), nh->numFds, nh->numInts);
    }
    msg.count = static_cast<uint32_t>(pool.size());
    msg.modifier = modifier;
    Send(LJD_POOL, &msg, sizeof(msg), std::move(fds));
}

void Presenter::Release(uint32_t pid, uint32_t index, int fence) {
    ljd_release r{pid, index, fence >= 0 ? LJD_F_FENCE : 0u, 0};
    std::vector<int> fds;
    if (fence >= 0) fds.push_back(fence);
    Send(LJD_RELEASE, &r, sizeof(r), std::move(fds));
}

void Presenter::Hide() {
    if (has_pending) {
        if (pend_fence >= 0) close(pend_fence);
        pend_fence = -1;
        has_pending = false;
    }
    for (Buffer& b : pool) b.state = BufState::kProducer;
    if (sc == nullptr || !shown) return;
    ASurfaceTransaction* t = ASurfaceTransaction_create();
    ASurfaceTransaction_setVisibility(t, sc, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
    ASurfaceTransaction_apply(t);
    ASurfaceTransaction_delete(t);
    shown = false;
}

void Presenter::OnPresent(const ljd_present& pr, const ljd_rect* rects, int fence) {
    if (pr.pool_id != pool_id || pr.index >= pool.size() || pool[pr.index].state != BufState::kProducer) {
        LOGW("present of pool %u buffer %u refused (pool %u)", pr.pool_id, pr.index, pool_id);
        if (fence >= 0) close(fence);
        Release(pr.pool_id, pr.index, -1);
        return;
    }
    if (has_pending) {
        // Replaced before its vsync: not shown.
        ljd_presented d{pend_seq, LJD_F_DISCARDED, 0};
        Send(LJD_PRESENTED, &d, sizeof(d));
        pool[pend_index].state = BufState::kProducer;
        Release(pool_id, pend_index, pend_fence);  // the producer's own fence back
        pend_fence = -1;
    }
    has_pending = true;
    pend_index = pr.index;
    pend_seq = pr.seq;
    pend_fence = fence;
    pend_damage.clear();
    for (uint32_t i = 0; i < pr.damage_count && i < LJD_MAX_DAMAGE; ++i) {
        pend_damage.push_back(ARect{rects[i].x, rects[i].y, rects[i].x + rects[i].width, rects[i].y + rects[i].height});
    }
    pool[pr.index].state = BufState::kPending;
    last_present_rx = NowNs();
    // Pacing is one transaction per vsync period. A present that missed this period's callback but
    // arrives in its first three quarters is applied now; SurfaceFlinger may still latch it for the
    // next vsync, which a wait for the next callback would miss.
    if (applied_vsync != last_vsync && last_vsync > 0 && last_present_rx - last_vsync < refresh_ns * 3 / 4) {
        Apply();
    }
    RequestVsync();
}

void VsyncCallback(const AChoreographerFrameCallbackData* data, void* ctx) {
    static_cast<Presenter*>(ctx)->OnVsync(data);
}

void Presenter::RequestVsync() {
    if (vsync_posted) return;
    AChoreographer* ch = AChoreographer_getInstance();
    if (ch == nullptr) return;
    vsync_posted = true;
    AChoreographer_postVsyncCallback(ch, VsyncCallback, this);
}

void OnBufferRelease(void* ctx, int fence) {
    auto* rc = static_cast<ReleaseCtx*>(ctx);
    Presenter* p = rc->p;
    const uint32_t pid = rc->pool_id, index = rc->index;
    delete rc;
    std::lock_guard<std::mutex> l(g_live_mu);
    if (!g_live.count(p)) {
        if (fence >= 0) close(fence);
        return;
    }
    p->Post([p, pid, index, fence] {
        int own = OwnFence(fence);
        if (fence >= 0) close(fence);
        if (pid == p->pool_id && index < p->pool.size()) p->pool[index].state = BufState::kProducer;
        p->Release(pid, index, own);
    });
}

struct CompleteCtx {
    Presenter* p;
    uint32_t seq;
};

void OnComplete(void* ctx, ASurfaceTransactionStats* stats) {
    auto* cc = static_cast<CompleteCtx*>(ctx);
    Presenter* p = cc->p;
    const uint32_t seq = cc->seq;
    delete cc;
    const int64_t latch = ASurfaceTransactionStats_getLatchTime(stats);
    std::lock_guard<std::mutex> l(g_live_mu);
    if (!g_live.count(p)) return;
    p->Post([p, seq, latch] {
        // The frame reaches the screen at the vsync after the latch.
        ljd_presented d{seq, 0, latch > 0 ? latch + p->refresh_ns : 0};
        p->Send(LJD_PRESENTED, &d, sizeof(d));
    });
}

void Presenter::OnVsync(const AChoreographerFrameCallbackData* data) {
    vsync_posted = false;
    last_vsync = AChoreographerFrameCallbackData_getFrameTimeNanos(data);
    const size_t pref = AChoreographerFrameCallbackData_getPreferredFrameTimelineIndex(data);
    last_deadline = AChoreographerFrameCallbackData_getFrameTimelineDeadlineNanos(data, pref);
    if (has_pending) {
        Apply();
    } else if (frame_requested) {
        frame_requested = false;
        ljd_frame f{last_seq, 0, last_vsync, last_deadline, refresh_ns};
        Send(LJD_FRAME, &f, sizeof(f));
    }
    // Callbacks continue while frames arrive, so that a late present knows the current period.
    if (sock >= 0 && peer && NowNs() - last_present_rx < 1000000000LL) RequestVsync();
}

// The pending present as one transaction; then LJD_FRAME: render the next one now.
void Presenter::Apply() {
    if (!has_pending || sc == nullptr || sock < 0) return;
    has_pending = false;
    Buffer& b = pool[pend_index];
    ASurfaceTransaction* t = ASurfaceTransaction_create();
    const int fence = OwnFence(pend_fence);
    if (pend_fence >= 0) close(pend_fence);
    pend_fence = -1;
    ASurfaceTransaction_setBufferWithRelease(t, sc, b.ahb, fence, new ReleaseCtx{this, pool_id, pend_index},
                                             OnBufferRelease);
    if (!pend_damage.empty()) {
        ASurfaceTransaction_setDamageRegion(t, sc, pend_damage.data(), static_cast<uint32_t>(pend_damage.size()));
    }
    if (!shown) {
        ASurfaceTransaction_setVisibility(t, sc, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        ASurfaceTransaction_setBufferTransparency(t, sc, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        ASurfaceTransaction_setFrameRate(t, sc, refresh_mhz / 1000.0f, ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT);
        shown = true;
    }
    // No frame timeline: with the preferred timeline SurfaceFlinger holds the buffer for a later
    // vsync, which keeps all three buffers in flight and shows two frames of every three (80 Hz).
    ASurfaceTransaction_setOnComplete(t, new CompleteCtx{this, pend_seq}, OnComplete);
    ASurfaceTransaction_apply(t);
    ASurfaceTransaction_delete(t);
    b.state = BufState::kScreen;
    last_seq = pend_seq;
    applied_vsync = last_vsync;
    if (++frames % 600 == 0) LOGI("%llu frames presented", static_cast<unsigned long long>(frames));
    frame_requested = false;
    ljd_frame f{last_seq, 0, last_vsync, last_deadline, refresh_ns};
    Send(LJD_FRAME, &f, sizeof(f));
}

Presenter* FromHandle(jlong h) { return reinterpret_cast<Presenter*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_org_lujarch_linux_terminal_NativeDisplay_nativeCreate(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (win == nullptr) return 0;
    ASurfaceControl* sc = ASurfaceControl_createFromWindow(win, "lujarch_linux_desktop");
    ANativeWindow_release(win);
    if (sc == nullptr) return 0;
    auto* p = new Presenter();
    p->sc = sc;
    p->wake_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    {
        std::lock_guard<std::mutex> l(g_live_mu);
        g_live.insert(p);
    }
    p->thread = std::thread([p] { p->Run(); });
    return reinterpret_cast<jlong>(p);
}

JNIEXPORT void JNICALL Java_org_lujarch_linux_terminal_NativeDisplay_nativeDestroy(JNIEnv*, jclass, jlong h) {
    Presenter* p = FromHandle(h);
    if (p == nullptr) return;
    p->Post([p] { p->quit = true; });
    p->thread.join();
    {
        std::lock_guard<std::mutex> l(g_live_mu);
        g_live.erase(p);
    }
    close(p->wake_fd);
    delete p;
}

JNIEXPORT void JNICALL Java_org_lujarch_linux_terminal_NativeDisplay_nativeConfigure(
        JNIEnv*, jclass, jlong h, jint width, jint height, jint rotation, jint dpi, jint refresh_mhz, jboolean visible) {
    Presenter* p = FromHandle(h);
    if (p == nullptr) return;
    p->Post([p, width, height, rotation, dpi, refresh_mhz, visible] {
        const bool resized = p->width != static_cast<uint32_t>(width) || p->height != static_cast<uint32_t>(height);
        p->width = width;
        p->height = height;
        p->rotation = rotation;
        p->dpi = dpi;
        p->refresh_mhz = refresh_mhz > 0 ? refresh_mhz : 60000;
        p->refresh_ns = 1000000000000LL / p->refresh_mhz;
        p->visible = visible;
        // lujarch_linux reads every configure; KWin gets a pool only while it is there.
        if (p->sock < 0) return;
        p->SendConfigure();
        if (p->peer && resized) p->NewPool();
    });
}

JNIEXPORT void JNICALL Java_org_lujarch_linux_terminal_NativeDisplay_nativeAttach(JNIEnv*, jclass, jlong h, jint fd) {
    Presenter* p = FromHandle(h);
    if (p == nullptr) {
        close(fd);
        return;
    }
    p->Post([p, fd] { p->Attach(fd); });
}

JNIEXPORT jboolean JNICALL Java_org_lujarch_linux_terminal_NativeDisplay_nativeAttached(JNIEnv*, jclass, jlong h) {
    Presenter* p = FromHandle(h);
    return p != nullptr && p->connected;
}

JNIEXPORT jboolean JNICALL Java_org_lujarch_linux_terminal_NativeDisplay_nativeDrawn(JNIEnv*, jclass, jlong h) {
    Presenter* p = FromHandle(h);
    return p != nullptr && p->peer_seen;
}

JNIEXPORT void JNICALL Java_org_lujarch_linux_terminal_NativeDisplay_nativeTouch(
        JNIEnv* env, jclass, jlong h, jlong time_ns, jint count, jintArray types, jintArray ids, jfloatArray xs, jfloatArray ys) {
    Presenter* p = FromHandle(h);
    if (p == nullptr || count <= 0) return;
    if (count > LJD_MAX_TOUCH) count = LJD_MAX_TOUCH;
    std::string msg(sizeof(ljd_touch) + count * sizeof(ljd_touch_point), '\0');
    ljd_touch t{time_ns, static_cast<uint32_t>(count), 0};
    memcpy(msg.data(), &t, sizeof(t));
    std::vector<jint> ty(count), id(count);
    std::vector<jfloat> x(count), y(count);
    env->GetIntArrayRegion(types, 0, count, ty.data());
    env->GetIntArrayRegion(ids, 0, count, id.data());
    env->GetFloatArrayRegion(xs, 0, count, x.data());
    env->GetFloatArrayRegion(ys, 0, count, y.data());
    for (int i = 0; i < count; ++i) {
        ljd_touch_point pt{static_cast<uint32_t>(ty[i]), id[i], x[i], y[i]};
        memcpy(msg.data() + sizeof(t) + i * sizeof(pt), &pt, sizeof(pt));
    }
    p->Post([p, msg] {
        if (p->peer) p->Send(LJD_TOUCH, msg.data(), msg.size());
    });
}

JNIEXPORT void JNICALL Java_org_lujarch_linux_terminal_NativeDisplay_nativeKey(
        JNIEnv*, jclass, jlong h, jlong time_ns, jint keycode, jboolean pressed) {
    Presenter* p = FromHandle(h);
    if (p == nullptr) return;
    ljd_key k{time_ns, static_cast<uint32_t>(keycode), pressed ? 1u : 0u};
    p->Post([p, k] {
        if (p->peer) p->Send(LJD_KEY, &k, sizeof(k));
    });
}

JNIEXPORT void JNICALL Java_org_lujarch_linux_terminal_NativeDisplay_nativePointer(
        JNIEnv*, jclass, jlong h, jlong time_ns, jfloat x, jfloat y, jint buttons, jint scroll_v120, jint scroll_h120,
        jint flags) {
    Presenter* p = FromHandle(h);
    if (p == nullptr) return;
    ljd_pointer pt{};
    pt.time_ns = time_ns;
    pt.x = x;
    pt.y = y;
    pt.buttons = static_cast<uint32_t>(buttons);
    pt.scroll_v120 = scroll_v120;
    pt.scroll_h120 = scroll_h120;
    pt.flags = static_cast<uint32_t>(flags);
    p->Post([p, pt] {
        if (p->peer) p->Send(LJD_POINTER, &pt, sizeof(pt));
    });
}

}  // extern "C"
