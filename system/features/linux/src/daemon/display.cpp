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

#include "display.h"

#include <android-base/logging.h>
#include <fcntl.h>
#include <linux/sync_file.h>
#include <lujarch/aid.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <thread>

#include "files.h"
#include "lujarch_display.h"
#include "paths.h"
#include "system.h"

namespace lujarch::linux {

namespace {

using android::base::ErrnoError;
using android::base::Error;

constexpr size_t kMaxQueuedBytes = 1 << 20;
constexpr size_t kMaxQueuedMessages = 2048;
constexpr size_t kMaxRequest = 4096;
constexpr auto kGreetingTime = std::chrono::seconds(5);
constexpr char kAccepted[] = "{\"ok\":true}\n";
constexpr char kRefused[] = "{\"ok\":false,\"error\":\"bad-request\"}\n";

// The types each side may send, and how many fds each may carry.
bool Allowed(bool producer, uint16_t type, uint16_t nfds) {
    if (producer) {
        switch (type) {
            case LJD_PRESENT:
                return nfds <= 1;
            case LJD_REQUEST_FRAME:
                return nfds == 0;
        }
        return false;
    }
    switch (type) {
        case LJD_POOL:
            return nfds <= LJD_MAX_BUFFERS;
        case LJD_RELEASE:
            return nfds <= 1;
        case LJD_CONFIGURE:
        case LJD_FRAME:
        case LJD_PRESENTED:
        case LJD_TOUCH:
        case LJD_KEY:
        case LJD_POINTER:
            return nfds == 0;
    }
    return false;
}

// A sync_file of this process for the same fence; invalid when fd is no sync_file.
unique_fd OwnFence(int fd) {
    sync_merge_data data{};
    strncpy(data.name, "lujarch_display", sizeof(data.name) - 1);
    data.fd2 = fd;
    if (ioctl(fd, SYNC_IOC_MERGE, &data) != 0) return unique_fd();
    return unique_fd(data.fence);
}

const char* Side(bool producer) {
    return producer ? "KWin" : "the Terminal app";
}

}  // namespace

void Display::Start() {
    wake_.reset(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    if (wake_ < 0) PLOG(FATAL) << "display: eventfd";
    cgroup::SetDesktopFrozen(true);
    std::thread(&Display::Loop, this).detach();
}

void Display::Wake() {
    uint64_t one = 1;
    TEMP_FAILURE_RETRY(write(wake_.get(), &one, sizeof(one)));
}

void Display::SetLinux(bool running) {
    {
        std::lock_guard lock(mutex_);
        linux_wanted_ = running;
    }
    Wake();
}

Result<unique_fd> Display::Attach() {
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair) != 0) {
        return ErrnoError() << "socketpair";
    }
    unique_fd ours(pair[0]), theirs(pair[1]);
    fcntl(ours.get(), F_SETFL, O_NONBLOCK);
    {
        std::lock_guard lock(mutex_);
        if (!linux_wanted_) return Error(ESRCH) << "Linux does not run";
        attached_.push_back(std::move(ours));
    }
    Wake();
    return theirs;
}

Result<unique_fd> Display::OpenSound() {
    {
        std::lock_guard lock(mutex_);
        if (!linux_wanted_) return Error(ESRCH) << "Linux does not run";
    }
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return root.error();
    auto fd = files::OpenIn(root->get(), kSoundFifo, O_RDONLY | O_NONBLOCK);
    if (!fd.ok()) return Error(ENOENT) << kSoundFifo << ": " << fd.error().message();
    struct stat st;
    if (fstat(fd->get(), &st) != 0 || !S_ISFIFO(st.st_mode)) {
        return Error(ENOENT) << kSoundFifo << " is not a FIFO";
    }
    // What the desktop played while nobody listened.
    char buf[16384];
    while (read(fd->get(), buf, sizeof(buf)) > 0) {
    }
    return std::move(*fd);
}

void Display::Loop() {
    for (;;) {
        std::vector<pollfd> fds = {{wake_.get(), POLLIN, 0}};
        // What each pollfd after the first is: the listener (nullptr) or a connection.
        std::vector<Conn*> owners = {nullptr};
        if (listener_ >= 0) {
            fds.push_back({listener_.get(), POLLIN, 0});
            owners.push_back(nullptr);
        }
        int timeout = -1;
        const auto now = Clock::now();
        for (auto& c : greeting_) {
            fds.push_back({c->fd.get(), POLLIN, 0});
            owners.push_back(c.get());
            const int left = static_cast<int>(std::max<int64_t>(
                    0, std::chrono::duration_cast<std::chrono::milliseconds>(c->deadline - now).count()));
            timeout = timeout < 0 ? left : std::min(timeout, left);
        }
        for (Conn* c : {producer_.get(), app_.get()}) {
            if (c == nullptr) continue;
            fds.push_back({c->fd.get(), static_cast<short>(POLLIN | (c->out.empty() ? 0 : POLLOUT)), 0});
            owners.push_back(c);
        }
        TEMP_FAILURE_RETRY(poll(fds.data(), fds.size(), timeout));

        if (fds[0].revents) {
            uint64_t count;
            while (read(wake_.get(), &count, sizeof(count)) > 0) {
            }
            bool wanted;
            std::vector<unique_fd> attached;
            {
                std::lock_guard lock(mutex_);
                wanted = linux_wanted_;
                attached.swap(attached_);
            }
            if (wanted != linux_) Arrange(wanted);
            for (unique_fd& fd : attached) {
                if (linux_) AttachApp(std::move(fd));
            }
        }
        auto alive = [this](Conn* c) {
            return c == producer_.get() || c == app_.get() ||
                   std::any_of(greeting_.begin(), greeting_.end(),
                               [c](const auto& g) { return g.get() == c; });
        };
        for (size_t i = 1; i < fds.size(); ++i) {
            if (!fds[i].revents) continue;
            Conn* c = owners[i];
            if (c == nullptr) {
                if (listener_.get() == fds[i].fd) Accept();
                continue;
            }
            if (!alive(c) || c->fd.get() != fds[i].fd) continue;
            if (c->greeting) {
                Greet(*c);
                continue;
            }
            if ((fds[i].revents & POLLOUT) && !Flush(*c)) continue;
            if ((fds[i].revents & (POLLIN | POLLHUP | POLLERR)) && alive(c)) Readable(*c);
        }
        const auto later = Clock::now();
        for (size_t i = 0; i < greeting_.size();) {
            if (later >= greeting_[i]->deadline) {
                LOG(WARNING) << "display: a producer sent no request in time";
                greeting_.erase(greeting_.begin() + static_cast<std::ptrdiff_t>(i));
            } else {
                ++i;
            }
        }
    }
}

void Display::Arrange(bool running) {
    linux_ = running;
    if (running) {
        Listen();
        if (auto root = files::OpenRoot(kRoot); root.ok()) {
            if (auto r = files::MakeFifoIn(root->get(), kSoundFifo, 0660, aid::kLinux); !r.ok()) {
                LOG(ERROR) << "display: the sound's FIFO: " << r.error();
            }
        }
    } else {
        greeting_.clear();
        if (producer_) Close(*producer_, "Linux stopped");
        if (app_) Close(*app_, "Linux stopped");
        listener_.reset();
    }
    ApplyFrozen();
}

void Display::Listen() {
    listener_.reset();
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) {
        LOG(ERROR) << "display: " << root.error();
        return;
    }
    if (auto made = files::MakeDirsIn(root->get(), kBridgeDir, 01775, 0, aid::kLinux); !made.ok()) {
        LOG(ERROR) << "display: " << made.error();
        return;
    }
    std::string name;
    auto dir = files::OpenParentIn(root->get(), kDisplaySocket, &name);
    if (!dir.ok()) {
        LOG(ERROR) << "display: " << dir.error();
        return;
    }
    unlinkat(dir->get(), name.c_str(), 0);
    unique_fd fd(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0));
    sockaddr_un addr = {};
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "/proc/self/fd/%d/%s", dir->get(), name.c_str());
    if (fd < 0 || bind(fd.get(), reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0 ||
        fchownat(dir->get(), name.c_str(), 0, aid::kLinux, AT_SYMLINK_NOFOLLOW) != 0 ||
        fchmodat(dir->get(), name.c_str(), 0660, 0) != 0 || listen(fd.get(), 4) != 0) {
        PLOG(ERROR) << "display: " << kDisplaySocket;
        return;
    }
    listener_ = std::move(fd);
    LOG(INFO) << "display: waiting for KWin at " << kDisplaySocket;
}

void Display::Accept() {
    for (;;) {
        unique_fd fd(accept4(listener_.get(), nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK));
        if (fd < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                PLOG(WARNING) << "display: accept";
            }
            return;
        }
        ucred cred = {};
        socklen_t length = sizeof(cred);
        if (getsockopt(fd.get(), SOL_SOCKET, SO_PEERCRED, &cred, &length) != 0 ||
            (cred.uid != 0 && cred.uid != aid::kLinux)) {
            LOG(WARNING) << "display: refused a producer of uid " << cred.uid;
            continue;
        }
        auto c = std::make_unique<Conn>();
        c->fd = std::move(fd);
        c->producer = true;
        c->greeting = true;
        c->deadline = Clock::now() + kGreetingTime;
        greeting_.push_back(std::move(c));
    }
}

void Display::Greet(Conn& c) {
    auto drop = [&](const char* why) {
        LOG(WARNING) << "display: a producer " << why;
        std::erase_if(greeting_, [&](const auto& g) { return g.get() == &c; });
    };
    char buf[512];
    const ssize_t n = recv(c.fd.get(), buf, sizeof(buf), MSG_DONTWAIT);
    if (n == 0) return drop("left before its request");
    if (n < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) drop(strerror(errno));
        return;
    }
    c.in.append(buf, static_cast<size_t>(n));
    const size_t nl = c.in.find('\n');
    if (nl == std::string::npos) {
        if (c.in.size() > kMaxRequest) drop("sent a request line that does not end");
        return;
    }
    if (c.in.substr(0, nl + 1) != LJD_REQUEST_PRODUCER) {
        send(c.fd.get(), kRefused, sizeof(kRefused) - 1, MSG_NOSIGNAL | MSG_DONTWAIT);
        return drop("sent a request this daemon does not serve");
    }
    if (send(c.fd.get(), kAccepted, sizeof(kAccepted) - 1, MSG_NOSIGNAL | MSG_DONTWAIT) !=
        static_cast<ssize_t>(sizeof(kAccepted) - 1)) {
        return drop("did not take the answer");
    }
    c.in.erase(0, nl + 1);
    c.greeting = false;
    auto it = std::find_if(greeting_.begin(), greeting_.end(),
                           [&](const auto& g) { return g.get() == &c; });
    std::unique_ptr<Conn> owned = std::move(*it);
    greeting_.erase(it);
    Produce(std::move(owned));
}

void Display::Produce(std::unique_ptr<Conn> c) {
    if (producer_) Close(*producer_, "a newer KWin replaces it");
    producer_ = std::move(c);
    LOG(INFO) << "display: KWin connected";
    if (app_) {
        Peer(*app_, true);
        if (producer_ && app_) Peer(*producer_, true);
    }
    if (producer_ && !producer_->in.empty()) Parse(*producer_);
}

void Display::AttachApp(unique_fd fd) {
    if (app_) Close(*app_, "a newer connection replaces it");
    app_ = std::make_unique<Conn>();
    app_->fd = std::move(fd);
    app_visible_ = false;
    LOG(INFO) << "display: the Terminal app attached";
    if (producer_) {
        Peer(*producer_, true);
        if (producer_ && app_) Peer(*app_, true);
    }
}

bool Display::Readable(Conn& c) {
    for (int round = 0; round < 16; ++round) {
        char buf[16384];
        alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int) * 16)];
        iovec iov{buf, sizeof(buf)};
        msghdr msg = {};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control;
        msg.msg_controllen = sizeof(control);
        const ssize_t n = recvmsg(c.fd.get(), &msg, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) return true;
            Close(c, strerror(errno));
            return false;
        }
        for (cmsghdr* cm = CMSG_FIRSTHDR(&msg); cm != nullptr; cm = CMSG_NXTHDR(&msg, cm)) {
            if (cm->cmsg_level != SOL_SOCKET || cm->cmsg_type != SCM_RIGHTS) continue;
            const size_t count = (cm->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            const int* received = reinterpret_cast<const int*>(CMSG_DATA(cm));
            for (size_t i = 0; i < count; ++i) c.in_fds.emplace_back(received[i]);
        }
        if (msg.msg_flags & MSG_CTRUNC) {
            // The kernel drops an fd SELinux does not let this daemon use.
            LOG(ERROR) << "display: fds from " << Side(c.producer) << " were refused";
            Close(c, "fds refused");
            return false;
        }
        if (n == 0) {
            Close(c, "end of stream");
            return false;
        }
        c.in.append(buf, static_cast<size_t>(n));
        if (!Parse(c)) return false;
    }
    return true;
}

bool Display::Parse(Conn& c) {
    auto alive = [&] { return &c == producer_.get() || &c == app_.get(); };
    size_t pos = 0;
    while (c.in.size() - pos >= sizeof(ljd_header)) {
        ljd_header h;
        memcpy(&h, c.in.data() + pos, sizeof(h));
        if (h.size > LJD_MAX_PAYLOAD || h.nfds > LJD_MAX_FDS || !Allowed(c.producer, h.type, h.nfds)) {
            LOG(WARNING) << "display: " << Side(c.producer) << " sent type " << h.type << ", size "
                         << h.size << ", " << h.nfds << " fds";
            Close(c, "protocol error");
            return false;
        }
        if (c.in.size() - pos < sizeof(h) + h.size) break;
        if (c.in_fds.size() < h.nfds) {
            LOG(WARNING) << "display: " << Side(c.producer) << " sent type " << h.type
                         << " without its " << h.nfds << " fds";
            Close(c, "protocol error");
            return false;
        }
        std::string bytes = c.in.substr(pos, sizeof(h) + h.size);
        pos += sizeof(h) + h.size;
        std::vector<unique_fd> fds;
        for (uint16_t i = 0; i < h.nfds; ++i) {
            fds.push_back(std::move(c.in_fds.front()));
            c.in_fds.pop_front();
        }
        if ((h.type == LJD_PRESENT || h.type == LJD_RELEASE) && fds.size() == 1) {
            unique_fd own = OwnFence(fds[0].get());
            if (own < 0) {
                PLOG(WARNING) << "display: " << Side(c.producer) << " sent a fence that is no sync_file";
                Close(c, "protocol error");
                return false;
            }
            fds[0] = std::move(own);
        }
        if (!c.producer && h.type == LJD_CONFIGURE && h.size >= sizeof(ljd_configure)) {
            ljd_configure configure;
            memcpy(&configure, bytes.data() + sizeof(h), sizeof(configure));
            // Thawed before a configure that shows the desktop reaches KWin.
            app_visible_ = configure.visible != 0;
            ApplyFrozen();
        }
        Conn* to = c.producer ? app_.get() : producer_.get();
        if (to == nullptr) continue;
        Send(*to, std::move(bytes), std::move(fds));
        if (!alive()) return false;
    }
    c.in.erase(0, pos);
    return true;
}

bool Display::Send(Conn& c, std::string bytes, std::vector<unique_fd> fds) {
    if (c.out.size() >= kMaxQueuedMessages || c.out_bytes + bytes.size() > kMaxQueuedBytes) {
        LOG(WARNING) << "display: " << Side(c.producer) << " does not keep up";
        Close(c, "queue full");
        return false;
    }
    c.out_bytes += bytes.size();
    c.out.push_back(Out{std::move(bytes), std::move(fds), 0});
    return Flush(c);
}

bool Display::Flush(Conn& c) {
    while (!c.out.empty()) {
        Out& m = c.out.front();
        iovec iov{m.bytes.data() + m.sent, m.bytes.size() - m.sent};
        msghdr msg = {};
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int) * LJD_MAX_FDS)];
        if (!m.fds.empty()) {
            msg.msg_control = control;
            msg.msg_controllen = CMSG_SPACE(sizeof(int) * m.fds.size());
            cmsghdr* cm = CMSG_FIRSTHDR(&msg);
            cm->cmsg_level = SOL_SOCKET;
            cm->cmsg_type = SCM_RIGHTS;
            cm->cmsg_len = CMSG_LEN(sizeof(int) * m.fds.size());
            int* out = reinterpret_cast<int*>(CMSG_DATA(cm));
            for (size_t i = 0; i < m.fds.size(); ++i) out[i] = m.fds[i].get();
        }
        const ssize_t n = sendmsg(c.fd.get(), &msg, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) return true;
            Close(c, strerror(errno));
            return false;
        }
        // The fds went with the first byte.
        m.fds.clear();
        m.sent += static_cast<size_t>(n);
        if (m.sent < m.bytes.size()) continue;
        c.out_bytes -= m.bytes.size();
        c.out.pop_front();
    }
    return true;
}

void Display::Peer(Conn& c, bool connected) {
    std::string bytes(sizeof(ljd_header) + sizeof(ljd_peer), '\0');
    const ljd_header h{LJD_PEER, 0, sizeof(ljd_peer)};
    const ljd_peer p{connected ? 1u : 0u, 0};
    memcpy(bytes.data(), &h, sizeof(h));
    memcpy(bytes.data() + sizeof(h), &p, sizeof(p));
    Send(c, std::move(bytes), {});
}

void Display::Close(Conn& c, const std::string& why) {
    LOG(INFO) << "display: " << Side(c.producer) << " left: " << why;
    if (&c == producer_.get()) {
        producer_.reset();
        if (app_) Peer(*app_, false);
    } else if (&c == app_.get()) {
        app_.reset();
        app_visible_ = false;
        ApplyFrozen();
        if (producer_) Peer(*producer_, false);
    } else {
        std::erase_if(greeting_, [&](const auto& g) { return g.get() == &c; });
    }
}

void Display::ApplyFrozen() {
    cgroup::SetDesktopFrozen(!(linux_ && app_ && app_visible_));
}

}  // namespace lujarch::linux
