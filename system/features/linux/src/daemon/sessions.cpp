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

#include "sessions.h"

#include <aidl/org/lujarch/linux/ILinux.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/strings.h>
#include <fcntl.h>
#include <lujarch/aid.h>
#include <lujarch/text.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <wakelock/wakelock.h>

#include <algorithm>
#include <chrono>
#include <optional>
#include <thread>

namespace lujarch::linux {

namespace {

using aidl::org::lujarch::linux::ILinux;
using aidl::org::lujarch::linux::LinuxSession;
using android::base::ErrnoError;
using android::base::Error;
using Clock = std::chrono::steady_clock;

// What a session wrote lately, which an attach gets first.
constexpr size_t kKept = 1 << 20;
// An attached app that reads more slowly than this much piles up loses its stream.
constexpr size_t kMaxToClient = 4 << 20;
// Input the session does not read.
constexpr size_t kMaxToMaster = 256 << 10;
constexpr auto kKillDelay = std::chrono::seconds(3);
// How often whether a command runs is read: at most this often after output, and this often
// besides.
constexpr auto kBusyAfterOutput = std::chrono::milliseconds(200);
constexpr auto kBusyTick = std::chrono::seconds(5);
constexpr size_t kMaxName = 64;

enum Watch : uint64_t { kMaster = 0, kClient = 1, kLeader = 2 };
constexpr uint64_t kWakeKey = ~uint64_t{0};

uint64_t Key(uint64_t serial, Watch watch) {
    return serial << 2 | watch;
}

class Ring {
  public:
    Ring() : data_(kKept) {}

    void Append(const char* p, size_t n) {
        if (n >= data_.size()) {
            p += n - data_.size();
            n = data_.size();
        }
        for (size_t i = 0; i < n; ++i) {
            data_[(head_ + size_) % data_.size()] = p[i];
            if (size_ < data_.size()) {
                ++size_;
            } else {
                head_ = (head_ + 1) % data_.size();
            }
        }
    }

    std::string Contents() const {
        std::string out;
        out.reserve(size_);
        for (size_t i = 0; i < size_; ++i) out.push_back(data_[(head_ + i) % data_.size()]);
        return out;
    }

  private:
    std::vector<char> data_;
    size_t head_ = 0;
    size_t size_ = 0;
};

bool IsShell(const std::string& comm) {
    for (const char* shell : {"sh", "bash", "zsh", "fish", "dash", "mksh", "ksh", "ash", "tcsh",
                              "csh", "su"}) {
        if (comm == shell) return true;
    }
    return false;
}

std::string Comm(pid_t pid) {
    std::string comm;
    android::base::ReadFileToString("/proc/" + std::to_string(pid) + "/comm", &comm);
    return android::base::Trim(comm);
}

std::string CleanName(const std::string& name) {
    std::string clean = text::SanitizeUtf8(name);
    std::string out;
    for (char c : clean) {
        if (static_cast<unsigned char>(c) >= 0x20 && c != 0x7f) out.push_back(c);
    }
    if (out.size() > kMaxName) {
        out.resize(kMaxName);
        out = text::SanitizeUtf8(out);
    }
    return out;
}

bool SetNonBlocking(int fd) {
    const int flags = fcntl(fd, F_GETFL);
    return flags >= 0 && fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

}  // namespace

struct Sessions::Session {
    uint64_t serial = 0;
    std::string id;
    std::string kind;
    std::string name;
    int64_t created = 0;
    unique_fd master;
    Process leader;
    Ring kept;
    unique_fd client;
    std::string to_client;
    std::string to_master;
    bool master_out = false;  // waiting for the master to take input
    bool client_out = false;  // waiting for the client to take output
    bool master_gone = false;
    bool awake = false;
    bool busy = false;
    std::string job;
    // A WakeLock neither moves nor copies: it is made in place.
    std::unique_ptr<std::optional<android::wakelock::WakeLock>> lock;
    bool ending = false;
    bool killed = false;
    Clock::time_point kill_at;
    Clock::time_point checked;
    bool exited = false;
    int status = 0;
};

Sessions::Sessions(System& system, Changed changed, Exited exited)
    : system_(system), changed_(std::move(changed)), exited_(std::move(exited)) {}

void Sessions::Start() {
    epoll_.reset(epoll_create1(EPOLL_CLOEXEC));
    wake_.reset(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    epoll_event ev{EPOLLIN, {.u64 = kWakeKey}};
    if (epoll_ < 0 || wake_ < 0 || epoll_ctl(epoll_.get(), EPOLL_CTL_ADD, wake_.get(), &ev) != 0) {
        PLOG(FATAL) << "sessions: epoll";
    }
    std::thread(&Sessions::Loop, this).detach();
}

Sessions::~Sessions() = default;

void Sessions::Wake() {
    uint64_t one = 1;
    TEMP_FAILURE_RETRY(write(wake_.get(), &one, sizeof(one)));
}

namespace {

void Interest(int epoll, int fd, uint64_t key, uint32_t events, bool add) {
    epoll_event ev{events, {.u64 = key}};
    if (epoll_ctl(epoll, add ? EPOLL_CTL_ADD : EPOLL_CTL_MOD, fd, &ev) != 0) {
        PLOG(ERROR) << "sessions: epoll_ctl";
    }
}

void Forget(int epoll, int fd) {
    if (fd >= 0) epoll_ctl(epoll, EPOLL_CTL_DEL, fd, nullptr);
}

// The process groups a session's end signals: the shell's and the job's in the foreground.
void SignalSession(Sessions::Session& s, int sig);

}  // namespace

Result<std::string> Sessions::Create(const std::string& kind, const std::string& name,
                                     int columns, int rows, Spawn spawn) {
    unique_fd master(posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC));
    if (master < 0) return ErrnoError() << "posix_openpt";
    if (grantpt(master.get()) != 0 || unlockpt(master.get()) != 0) {
        return ErrnoError() << "unlockpt";
    }
    char tty[64];
    if (ptsname_r(master.get(), tty, sizeof(tty)) != 0) return ErrnoError() << "ptsname";
    winsize ws{};
    ws.ws_col = static_cast<unsigned short>(std::clamp(columns, 1, 1000));
    ws.ws_row = static_cast<unsigned short>(std::clamp(rows, 1, 1000));
    if (ioctl(master.get(), TIOCSWINSZ, &ws) != 0) return ErrnoError() << "TIOCSWINSZ";
    if (!SetNonBlocking(master.get())) return ErrnoError() << "O_NONBLOCK";
    // Opened before it becomes the user's: the daemon opens only what it may as its owner.
    unique_fd slave(open(tty, O_RDWR | O_NOCTTY | O_CLOEXEC));
    if (slave < 0) return ErrnoError() << tty;
    // Programs that open their terminal by name (sudo, ssh, gpg) need it to be their user's.
    if (spawn.as == Spawn::As::kUser &&
        (chown(tty, aid::kLinux, aid::kLinux) != 0 || chmod(tty, 0620) != 0)) {
        return ErrnoError() << "chown " << tty;
    }

    std::string id;
    {
        std::lock_guard lock(mutex_);
        if (sessions_.size() >= static_cast<size_t>(ILinux::MAX_SESSIONS)) {
            return Error() << "already " << ILinux::MAX_SESSIONS << " sessions";
        }
        id = "s" + std::to_string(next_id_++);
    }
    spawn.tty = slave.get();
    spawn.login = true;
    spawn.env.push_back("TERM=xterm-256color");
    spawn.env.push_back("COLORTERM=truecolor");
    spawn.env.push_back("LUJARCH_SESSION=" + id);
    auto process = system_.Start(spawn);
    if (!process.ok()) return process.error();

    auto s = std::make_unique<Session>();
    s->id = id;
    s->kind = kind;
    s->name = CleanName(name);
    s->created = time(nullptr);
    s->master = std::move(master);
    s->leader = std::move(*process);
    s->checked = Clock::now();
    LOG(INFO) << "session " << id << " (" << kind << ") started, pid " << s->leader.pid;
    {
        std::lock_guard lock(mutex_);
        const uint64_t serial = next_serial_++;
        s->serial = serial;
        Interest(epoll_.get(), s->master.get(), Key(serial, kMaster), EPOLLIN, true);
        Interest(epoll_.get(), s->leader.pidfd.get(), Key(serial, kLeader), EPOLLIN, true);
        sessions_[serial] = std::move(s);
    }
    changed_();
    return id;
}

namespace {

void SignalSession(Sessions::Session& s, int sig) {
    if (s.leader.pid <= 0) return;
    kill(-s.leader.pid, sig);
    const pid_t fg = tcgetpgrp(s.master.get());
    if (fg > 0 && fg != s.leader.pid) kill(-fg, sig);
}

}  // namespace

Result<unique_fd> Sessions::Attach(const std::string& id, int columns, int rows) {
    std::lock_guard lock(mutex_);
    for (auto& [serial, s] : sessions_) {
        if (s->id != id || s->exited) continue;
        int pair[2];
        if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair) != 0) {
            return ErrnoError() << "socketpair";
        }
        unique_fd ours(pair[0]), theirs(pair[1]);
        if (!SetNonBlocking(ours.get())) return ErrnoError() << "O_NONBLOCK";
        if (s->client >= 0) {
            Forget(epoll_.get(), s->client.get());
            s->client.reset();
        }
        s->client = std::move(ours);
        s->to_client = s->kept.Contents();
        s->client_out = !s->to_client.empty();
        Interest(epoll_.get(), s->client.get(), Key(serial, kClient),
                 EPOLLIN | EPOLLRDHUP | (s->client_out ? EPOLLOUT : 0), true);
        winsize ws{};
        ws.ws_col = static_cast<unsigned short>(std::clamp(columns, 1, 1000));
        ws.ws_row = static_cast<unsigned short>(std::clamp(rows, 1, 1000));
        ioctl(s->master.get(), TIOCSWINSZ, &ws);
        return theirs;
    }
    return Error(ENOENT) << "no session " << id;
}

Result<void> Sessions::Resize(const std::string& id, int columns, int rows) {
    std::lock_guard lock(mutex_);
    for (auto& [serial, s] : sessions_) {
        if (s->id != id || s->exited) continue;
        winsize ws{};
        ws.ws_col = static_cast<unsigned short>(std::clamp(columns, 1, 1000));
        ws.ws_row = static_cast<unsigned short>(std::clamp(rows, 1, 1000));
        if (ioctl(s->master.get(), TIOCSWINSZ, &ws) != 0) return ErrnoError() << "TIOCSWINSZ";
        return {};
    }
    return Error(ENOENT) << "no session " << id;
}

Result<void> Sessions::Rename(const std::string& id, const std::string& name) {
    {
        std::lock_guard lock(mutex_);
        auto it = std::find_if(sessions_.begin(), sessions_.end(),
                               [&](const auto& e) { return e.second->id == id; });
        if (it == sessions_.end()) return Error(ENOENT) << "no session " << id;
        it->second->name = CleanName(name);
    }
    changed_();
    return {};
}

Result<void> Sessions::SetAwake(const std::string& id, bool awake) {
    {
        std::lock_guard lock(mutex_);
        auto it = std::find_if(sessions_.begin(), sessions_.end(),
                               [&](const auto& e) { return e.second->id == id; });
        if (it == sessions_.end()) return Error(ENOENT) << "no session " << id;
        it->second->awake = awake;
        it->second->checked = Clock::time_point();
    }
    LOG(INFO) << "session " << id << (awake ? " keeps" : " no longer keeps")
              << " the phone awake while a command runs";
    Wake();
    changed_();
    return {};
}

Result<void> Sessions::End(const std::string& id) {
    std::lock_guard lock(mutex_);
    for (auto& [serial, s] : sessions_) {
        if (s->id != id) continue;
        if (!s->ending) {
            s->ending = true;
            s->kill_at = Clock::now() + kKillDelay;
            SignalSession(*s, SIGHUP);
            SignalSession(*s, SIGCONT);
            LOG(INFO) << "session " << id << " ended by the owner";
        }
        Wake();
        return {};
    }
    return Error(ENOENT) << "no session " << id;
}

void Sessions::EndKinds(const std::vector<std::string>& kinds) {
    std::vector<std::string> ids;
    {
        std::lock_guard lock(mutex_);
        for (auto& [serial, s] : sessions_) {
            if (std::find(kinds.begin(), kinds.end(), s->kind) != kinds.end()) {
                ids.push_back(s->id);
            }
        }
    }
    for (const std::string& id : ids) {
        if (auto ended = End(id); !ended.ok()) LOG(WARNING) << ended.error();
    }
}

void Sessions::KillAll() {
    std::lock_guard lock(mutex_);
    for (auto& [serial, s] : sessions_) {
        SignalSession(*s, SIGKILL);
        s->ending = true;
        s->killed = true;
        s->kill_at = Clock::now() + kKillDelay;
    }
    Wake();
}

std::vector<LinuxSession> Sessions::List() {
    std::lock_guard lock(mutex_);
    std::vector<LinuxSession> list;
    for (auto& [serial, s] : sessions_) {
        if (s->exited) continue;
        LinuxSession out;
        out.id = s->id;
        out.kind = s->kind;
        out.name = s->name;
        out.created = s->created;
        out.awake = s->awake;
        out.busy = s->busy;
        if (s->busy) out.job = s->job;
        list.push_back(std::move(out));
    }
    return list;
}

size_t Sessions::Count() {
    std::lock_guard lock(mutex_);
    return sessions_.size();
}

void Sessions::Loop() {
    for (;;) {
        int timeout = static_cast<int>(
                std::chrono::duration_cast<std::chrono::milliseconds>(kBusyTick).count());
        {
            std::lock_guard lock(mutex_);
            const auto now = Clock::now();
            for (auto& [serial, s] : sessions_) {
                if (s->ending && !s->exited && !s->master_gone) {
                    const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                            s->kill_at - now);
                    timeout = std::min(timeout, static_cast<int>(std::max<int64_t>(left.count(), 0)));
                }
            }
        }
        epoll_event events[32];
        const int n = TEMP_FAILURE_RETRY(epoll_wait(epoll_.get(), events, 32, timeout));
        std::vector<std::pair<std::string, int>> exits;
        bool changed = false;
        {
            std::lock_guard lock(mutex_);
            for (int i = 0; i < n; ++i) {
                const uint64_t key = events[i].data.u64;
                if (key == kWakeKey) {
                    uint64_t count;
                    while (read(wake_.get(), &count, sizeof(count)) > 0) {
                    }
                    continue;
                }
                auto it = sessions_.find(key >> 2);
                if (it == sessions_.end()) continue;
                Session& s = *it->second;
                const auto watch = static_cast<Watch>(key & 3);
                const uint32_t ev = events[i].events;
                if (watch == kMaster) {
                    if (ev & (EPOLLIN | EPOLLHUP | EPOLLERR)) {
                        char buf[65536];
                        for (int round = 0; round < 16; ++round) {
                            const ssize_t got = read(s.master.get(), buf, sizeof(buf));
                            if (got > 0) {
                                s.kept.Append(buf, static_cast<size_t>(got));
                                if (s.client >= 0) s.to_client.append(buf, static_cast<size_t>(got));
                                continue;
                            }
                            if (got < 0 && errno == EINTR) continue;
                            if (got == 0 || (got < 0 && errno != EAGAIN)) {
                                // Every program closed the terminal; the shell's end follows.
                                Forget(epoll_.get(), s.master.get());
                                s.master_gone = true;
                            }
                            break;
                        }
                        if (Clock::now() - s.checked > kBusyAfterOutput) s.checked = Clock::time_point();
                    }
                    if ((ev & EPOLLOUT) && !s.master_gone && !s.to_master.empty()) {
                        const ssize_t put = write(s.master.get(), s.to_master.data(), s.to_master.size());
                        if (put > 0) s.to_master.erase(0, static_cast<size_t>(put));
                    }
                } else if (watch == kClient && s.client >= 0) {
                    bool drop = (ev & (EPOLLERR | EPOLLHUP)) != 0;
                    if (ev & (EPOLLIN | EPOLLRDHUP)) {
                        char buf[16384];
                        const ssize_t got = read(s.client.get(), buf, sizeof(buf));
                        if (got > 0 && !s.master_gone) {
                            s.to_master.append(buf, static_cast<size_t>(got));
                            if (s.to_master.size() > kMaxToMaster) {
                                LOG(WARNING) << "session " << s.id << " does not read its input";
                                drop = true;
                            }
                        } else if (got == 0 || (got < 0 && errno != EAGAIN && errno != EINTR)) {
                            drop = true;
                        }
                    }
                    if (drop) {
                        Forget(epoll_.get(), s.client.get());
                        s.client.reset();
                        s.to_client.clear();
                        s.client_out = false;
                    }
                } else if (watch == kLeader && !s.exited) {
                    int status = 0;
                    const pid_t done = waitpid(s.leader.pid, &status, WNOHANG);
                    if (done == s.leader.pid) {
                        s.exited = true;
                        s.status = ExitCode(status);
                        s.leader.pid = -1;
                    }
                }
            }

            const auto now = Clock::now();
            for (auto it = sessions_.begin(); it != sessions_.end();) {
                Session& s = *it->second;
                if (s.ending && !s.killed && now >= s.kill_at) {
                    SignalSession(s, SIGKILL);
                    s.killed = true;
                    s.kill_at = now + kKillDelay;
                } else if (s.killed && !s.exited && !s.master_gone && now >= s.kill_at) {
                    // A shell this daemon may not signal (root in Android, KernelSU's domain):
                    // the terminal hangs up.
                    Forget(epoll_.get(), s.master.get());
                    s.master.reset();
                    s.master_gone = true;
                    LOG(WARNING) << "session " << s.id << " did not end on SIGKILL; its terminal hangs up";
                }
                // Input waiting for the session.
                if (!s.master_gone && !s.to_master.empty()) {
                    const ssize_t put = write(s.master.get(), s.to_master.data(), s.to_master.size());
                    if (put > 0) s.to_master.erase(0, static_cast<size_t>(put));
                }
                const bool want_master_out = !s.master_gone && !s.to_master.empty();
                if (want_master_out != s.master_out && !s.master_gone) {
                    Interest(epoll_.get(), s.master.get(), Key(s.serial, kMaster),
                             EPOLLIN | (want_master_out ? EPOLLOUT : 0), false);
                    s.master_out = want_master_out;
                }
                if (s.exited && !s.master_gone) {
                    // What the shell wrote last.
                    char buf[65536];
                    ssize_t got;
                    while ((got = read(s.master.get(), buf, sizeof(buf))) > 0) {
                        s.kept.Append(buf, static_cast<size_t>(got));
                        if (s.client >= 0) s.to_client.append(buf, static_cast<size_t>(got));
                    }
                }
                // Output waiting for the app.
                if (s.client >= 0 && !s.to_client.empty()) {
                    const ssize_t put = send(s.client.get(), s.to_client.data(), s.to_client.size(),
                                             MSG_NOSIGNAL | MSG_DONTWAIT);
                    if (put > 0) s.to_client.erase(0, static_cast<size_t>(put));
                    if (s.to_client.size() > kMaxToClient) {
                        LOG(WARNING) << "session " << s.id << ": the app does not keep up; its stream ends";
                        Forget(epoll_.get(), s.client.get());
                        s.client.reset();
                        s.to_client.clear();
                    }
                }
                if (s.client >= 0) {
                    const bool want_client_out = !s.to_client.empty();
                    if (want_client_out != s.client_out) {
                        Interest(epoll_.get(), s.client.get(), Key(s.serial, kClient),
                                 EPOLLIN | EPOLLRDHUP | (want_client_out ? EPOLLOUT : 0), false);
                        s.client_out = want_client_out;
                    }
                }
                // Whether a command runs, and the wake lock it may hold.
                if (!s.exited && now - s.checked >= kBusyAfterOutput &&
                    (s.checked == Clock::time_point() || now - s.checked >= kBusyTick)) {
                    s.checked = now;
                    const pid_t fg = s.master_gone ? -1 : tcgetpgrp(s.master.get());
                    std::string job = fg > 0 && fg != s.leader.pid ? Comm(fg) : "";
                    const bool busy = !job.empty() && !IsShell(job);
                    if (busy != s.busy || (busy && job != s.job)) {
                        s.busy = busy;
                        s.job = busy ? job : "";
                        changed = true;
                    }
                }
                const bool hold = s.awake && s.busy && !s.exited;
                if (hold && !s.lock) {
                    s.lock.reset(new std::optional<android::wakelock::WakeLock>(
                            android::wakelock::WakeLock::tryGet("lujarch_linux_" + s.id)));
                    if (!s.lock->has_value()) s.lock.reset();
                    if (s.lock) {
                        LOG(INFO) << "session " << s.id << " holds the phone awake for " << s.job;
                    } else {
                        LOG(ERROR) << "session " << s.id << ": no wake lock";
                    }
                } else if (!hold && s.lock) {
                    s.lock.reset();
                    LOG(INFO) << "session " << s.id << " lets the phone sleep";
                }
                if (s.exited) {
                    if (s.client >= 0 && !s.to_client.empty()) {
                        // The last output, at most a second's wait for the app.
                        int flags = fcntl(s.client.get(), F_GETFL);
                        fcntl(s.client.get(), F_SETFL, flags & ~O_NONBLOCK);
                        timeval tv{1, 0};
                        setsockopt(s.client.get(), SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
                        send(s.client.get(), s.to_client.data(), s.to_client.size(), MSG_NOSIGNAL);
                    }
                    Forget(epoll_.get(), s.client.get());
                    if (!s.master_gone) Forget(epoll_.get(), s.master.get());
                    Forget(epoll_.get(), s.leader.pidfd.get());
                    LOG(INFO) << "session " << s.id << " (" << s.kind << ") ended with " << s.status;
                    exits.emplace_back(s.id, s.status);
                    it = sessions_.erase(it);
                    changed = true;
                    continue;
                }
                ++it;
            }
        }
        for (const auto& [id, status] : exits) exited_(id, status);
        if (changed) changed_();
    }
}

}  // namespace lujarch::linux
