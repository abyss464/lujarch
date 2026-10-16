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

#include "services.h"

#include <aidl/org/lujarch/linux/ILinux.h>
#include <android-base/logging.h>
#include <android-base/strings.h>
#include <dirent.h>
#include <fcntl.h>
#include <lujarch/text.h>
#include <poll.h>
#include <signal.h>
#include <sys/eventfd.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <memory>
#include <thread>

#include "files.h"
#include "paths.h"

namespace lujarch::linux {

namespace {

using aidl::org::lujarch::linux::ILinux;
using aidl::org::lujarch::linux::LinuxService;
using android::base::ErrnoError;
using android::base::Error;

constexpr auto kStopTimeout = std::chrono::seconds(10);
constexpr auto kMaxBackoff = std::chrono::seconds(60);
// A service that ran this long before it ended starts again after a second.
constexpr auto kSteady = std::chrono::seconds(60);
constexpr off_t kMaxLog = 1 << 20;
constexpr size_t kMaxLine = 4096;
constexpr size_t kLogTail = 64 << 10;
constexpr size_t kMaxFile = 64 << 10;
constexpr size_t kMaxFiles = 200;

std::string Dir(const std::string& name) {
    return std::string(kServicesDir) + "/" + name;
}

std::string LogName(const std::string& name) {
    return name + ".log";
}

std::string Now() {
    char buf[32];
    time_t t = time(nullptr);
    tm local;
    localtime_r(&t, &local);
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S ", &local);
    return buf;
}

bool Executable(int root, const std::string& path) {
    auto fd = files::OpenIn(root, path, O_PATH);
    if (!fd.ok()) return false;
    struct stat st;
    return fstat(fd->get(), &st) == 0 && S_ISREG(st.st_mode) && (st.st_mode & 0111);
}

}  // namespace

struct Services::Service {
    std::string name;
    bool wanted = false;
    Process process;
    unique_fd output;
    std::string partial;
    unique_fd log;
    int64_t since = 0;
    int restarts = 0;
    int last_exit = -1;
    Clock::time_point started;
    Clock::time_point restart_at;  // epoch: none
    Clock::time_point kill_at;     // epoch: none
    std::chrono::seconds backoff{1};
    bool restart_after_stop = false;
};

Services::Services(System& system, std::function<void()> changed)
    : system_(system), changed_(std::move(changed)) {}

Services::~Services() = default;

void Services::Start() {
    wake_.reset(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    if (wake_ < 0) PLOG(FATAL) << "services: eventfd";
    std::thread(&Services::Loop, this).detach();
}

void Services::Wake() {
    uint64_t one = 1;
    TEMP_FAILURE_RETRY(write(wake_.get(), &one, sizeof(one)));
}

std::vector<std::string> Services::Discover() {
    std::vector<std::string> names;
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return names;
    auto dir = files::OpenIn(root->get(), kServicesDir, O_RDONLY | O_DIRECTORY);
    if (!dir.ok()) return names;
    std::unique_ptr<DIR, int (*)(DIR*)> d(fdopendir(dir->release()), closedir);
    if (!d) return names;
    while (dirent* e = readdir(d.get())) {
        const std::string name = e->d_name;
        if (!files::IsPlainName(name) || name[0] == '.') continue;
        if (Executable(root->get(), Dir(name) + "/run")) names.push_back(name);
    }
    std::sort(names.begin(), names.end());
    return names;
}

void Services::LogLine(const std::string& name, const std::string& line) {
    auto it = services_.find(name);
    if (it == services_.end()) return;
    Service& s = *it->second;
    if (s.log < 0) {
        auto root = files::OpenRoot(kRoot);
        if (!root.ok()) return;
        if (auto made = files::MakeDirsIn(root->get(), kServiceLogDir, 0755); !made.ok()) {
            LOG(ERROR) << "service " << name << ": log directory: " << made.error();
            return;
        }
        auto fd = files::OpenIn(root->get(), std::string(kServiceLogDir) + "/" + LogName(name),
                                O_WRONLY | O_APPEND | O_CREAT | O_NOFOLLOW, 0640);
        if (!fd.ok()) {
            LOG(ERROR) << "service " << name << ": log: " << fd.error();
            return;
        }
        s.log = std::move(*fd);
    }
    const std::string entry = Now() + text::SanitizeUtf8(line) + "\n";
    TEMP_FAILURE_RETRY(write(s.log.get(), entry.data(), entry.size()));
    struct stat st;
    if (fstat(s.log.get(), &st) == 0 && st.st_size > kMaxLog) {
        if (auto root = files::OpenRoot(kRoot); root.ok()) {
            auto dir = files::OpenIn(root->get(), kServiceLogDir, O_PATH | O_DIRECTORY);
            if (dir.ok()) {
                renameat(dir->get(), LogName(name).c_str(), dir->get(),
                         (LogName(name) + ".1").c_str());
            }
        }
        s.log.reset();
    }
}

bool Services::StartLocked(Service& s) {
    int out[2];
    if (pipe2(out, O_CLOEXEC) != 0) {
        PLOG(ERROR) << "service " << s.name << ": pipe";
        return false;
    }
    unique_fd read_end(out[0]), write_end(out[1]);
    fcntl(read_end.get(), F_SETFL, O_NONBLOCK);
    Spawn spawn;
    spawn.as = Spawn::As::kRoot;
    spawn.argv = {Dir(s.name) + "/run"};
    spawn.cwd = Dir(s.name);
    spawn.out = write_end.get();
    spawn.err = write_end.get();
    auto process = system_.Start(spawn);
    if (!process.ok()) {
        LOG(ERROR) << "service " << s.name << " did not start: " << process.error();
        LogLine(s.name, "lujarch: the service did not start: " + process.error().message());
        s.restart_at = Clock::now() + s.backoff;
        s.backoff = std::min(s.backoff * 2, kMaxBackoff);
        return false;
    }
    s.process = std::move(*process);
    s.output = std::move(read_end);
    s.started = Clock::now();
    s.since = time(nullptr);
    s.restart_at = {};
    s.kill_at = {};
    LOG(INFO) << "service " << s.name << " started, pid " << s.process.pid;
    LogLine(s.name, "lujarch: started");
    return true;
}

void Services::StopLocked(Service& s) {
    if (s.process.pid <= 0 || s.kill_at != Clock::time_point()) return;
    kill(-s.process.pid, SIGTERM);
    kill(-s.process.pid, SIGCONT);
    s.kill_at = Clock::now() + kStopTimeout;
    LOG(INFO) << "service " << s.name << " stopping";
}

void Services::Finish(Service& s, int status) {
    // What it wrote last.
    char buf[4096];
    ssize_t n;
    while (s.output >= 0 && (n = read(s.output.get(), buf, sizeof(buf))) > 0) {
        s.partial.append(buf, static_cast<size_t>(n));
    }
    if (!s.partial.empty()) LogLine(s.name, s.partial);
    s.partial.clear();
    s.output.reset();
    s.process.pid = -1;
    s.process.pidfd.reset();
    s.last_exit = ExitCode(status);
    s.since = time(nullptr);
    const bool stopped = s.kill_at != Clock::time_point();
    s.kill_at = {};
    LogLine(s.name, "lujarch: ended with " + std::to_string(s.last_exit));
    LOG(INFO) << "service " << s.name << " ended with " << s.last_exit;
    if (s.restart_after_stop) {
        s.restart_after_stop = false;
        s.backoff = std::chrono::seconds(1);
        if (linux_ && s.wanted) StartLocked(s);
        return;
    }
    if (linux_ && s.wanted && !stopped) {
        ++s.restarts;
        if (Clock::now() - s.started > kSteady) s.backoff = std::chrono::seconds(1);
        s.restart_at = Clock::now() + s.backoff;
        s.backoff = std::min(s.backoff * 2, kMaxBackoff);
    }
}

void Services::Loop() {
    for (;;) {
        std::vector<pollfd> fds = {{wake_.get(), POLLIN, 0}};
        std::vector<std::pair<Service*, bool>> owners = {{nullptr, false}};
        int timeout = -1;
        {
            std::lock_guard lock(mutex_);
            const auto now = Clock::now();
            for (auto& [name, s] : services_) {
                if (s->process.pid > 0) {
                    fds.push_back({s->process.pidfd.get(), POLLIN, 0});
                    owners.push_back({s.get(), true});
                    if (s->output >= 0) {
                        fds.push_back({s->output.get(), POLLIN, 0});
                        owners.push_back({s.get(), false});
                    }
                }
                for (Clock::time_point at : {s->restart_at, s->kill_at}) {
                    if (at == Clock::time_point()) continue;
                    const int left = static_cast<int>(std::max<int64_t>(
                            0, std::chrono::duration_cast<std::chrono::milliseconds>(at - now).count()));
                    timeout = timeout < 0 ? left : std::min(timeout, left);
                }
            }
        }
        TEMP_FAILURE_RETRY(poll(fds.data(), fds.size(), timeout));
        bool changed = false;
        {
            std::lock_guard lock(mutex_);
            if (fds[0].revents) {
                uint64_t count;
                while (read(wake_.get(), &count, sizeof(count)) > 0) {
                }
            }
            for (size_t i = 1; i < fds.size(); ++i) {
                if (!fds[i].revents) continue;
                Service* s = owners[i].first;
                // The service may have ended and started again while the lock was free.
                if (owners[i].second) {
                    if (s->process.pid <= 0 || s->process.pidfd.get() != fds[i].fd) continue;
                    int status = 0;
                    if (waitpid(s->process.pid, &status, WNOHANG) == s->process.pid) {
                        Finish(*s, status);
                        changed = true;
                    }
                } else if (s->output.get() == fds[i].fd) {
                    char buf[4096];
                    const ssize_t n = read(s->output.get(), buf, sizeof(buf));
                    if (n > 0) {
                        s->partial.append(buf, static_cast<size_t>(n));
                        size_t nl;
                        while ((nl = s->partial.find('\n')) != std::string::npos) {
                            LogLine(s->name, s->partial.substr(0, nl));
                            s->partial.erase(0, nl + 1);
                        }
                        if (s->partial.size() > kMaxLine) {
                            LogLine(s->name, s->partial);
                            s->partial.clear();
                        }
                    } else if (n == 0) {
                        s->output.reset();
                    }
                }
            }
            const auto now = Clock::now();
            for (auto& [name, s] : services_) {
                if (s->kill_at != Clock::time_point() && now >= s->kill_at && s->process.pid > 0) {
                    LOG(WARNING) << "service " << name << " did not end on SIGTERM; killing it";
                    kill(-s->process.pid, SIGKILL);
                    s->kill_at = now + std::chrono::hours(24);
                }
                if (s->restart_at != Clock::time_point() && now >= s->restart_at) {
                    s->restart_at = {};
                    if (linux_ && s->wanted && s->process.pid <= 0) {
                        StartLocked(*s);
                        changed = true;
                    }
                }
            }
            bool running = false;
            for (auto& [name, s] : services_) running |= s->process.pid > 0;
            if (!running) idle_.notify_all();
        }
        if (changed) changed_();
    }
}

void Services::LinuxStarted() {
    {
        std::lock_guard lock(mutex_);
        linux_ = true;
        auto root = files::OpenRoot(kRoot);
        for (const std::string& name : Discover()) {
            auto& s = services_[name];
            if (!s) {
                s = std::make_unique<Service>();
                s->name = name;
            }
            s->restarts = 0;
            s->backoff = std::chrono::seconds(1);
            s->wanted = root.ok() && !files::ExistsIn(root->get(), Dir(name) + "/down");
            if (s->wanted && s->process.pid <= 0) StartLocked(*s);
        }
    }
    Wake();
    changed_();
}

void Services::StopAll() {
    std::unique_lock lock(mutex_);
    linux_ = false;
    for (auto& [name, s] : services_) {
        s->wanted = false;
        s->restart_at = {};
        s->restart_after_stop = false;
        StopLocked(*s);
    }
    Wake();
    idle_.wait_for(lock, kStopTimeout + std::chrono::seconds(2), [this] {
        return std::none_of(services_.begin(), services_.end(),
                            [](const auto& e) { return e.second->process.pid > 0; });
    });
}

std::vector<LinuxService> Services::List() {
    std::lock_guard lock(mutex_);
    const std::vector<std::string> names = Discover();
    auto root = files::OpenRoot(kRoot);
    for (const std::string& name : names) {
        auto& s = services_[name];
        if (!s) {
            s = std::make_unique<Service>();
            s->name = name;
        }
    }
    std::vector<LinuxService> list;
    for (auto it = services_.begin(); it != services_.end();) {
        Service& s = *it->second;
        const bool present = std::find(names.begin(), names.end(), s.name) != names.end();
        if (!present && s.process.pid <= 0) {
            it = services_.erase(it);
            continue;
        }
        LinuxService out;
        out.name = s.name;
        out.running = s.process.pid > 0;
        out.wanted = s.wanted;
        out.startsWithLinux = root.ok() && present && !files::ExistsIn(root->get(), Dir(s.name) + "/down");
        out.since = s.since;
        out.restarts = s.restarts;
        out.lastExit = s.last_exit;
        list.push_back(std::move(out));
        ++it;
    }
    return list;
}

Result<void> Services::Control(const std::string& name, const std::string& action) {
    {
        std::lock_guard lock(mutex_);
        if (!linux_) return Error(ESRCH) << "Linux does not run";
        const std::vector<std::string> names = Discover();
        if (std::find(names.begin(), names.end(), name) == names.end()) {
            return Error(ENOENT) << "no service " << name;
        }
        auto& s = services_[name];
        if (!s) {
            s = std::make_unique<Service>();
            s->name = name;
        }
        s->backoff = std::chrono::seconds(1);
        s->restart_at = {};
        if (action == ILinux::SERVICE_START) {
            s->wanted = true;
            if (s->process.pid <= 0) StartLocked(*s);
        } else if (action == ILinux::SERVICE_STOP) {
            s->wanted = false;
            s->restart_after_stop = false;
            StopLocked(*s);
        } else if (action == ILinux::SERVICE_RESTART) {
            s->wanted = true;
            if (s->process.pid > 0) {
                s->restart_after_stop = true;
                StopLocked(*s);
            } else {
                StartLocked(*s);
            }
        } else {
            return Error(EINVAL) << "no action " << action;
        }
        LOG(INFO) << "service " << name << ": " << action << " asked";
    }
    Wake();
    changed_();
    return {};
}

Result<void> Services::SetStartsWithLinux(const std::string& name, bool starts) {
    if (!files::IsPlainName(name)) return Error(EINVAL) << "not a service name";
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return root.error();
    if (!Executable(root->get(), Dir(name) + "/run")) return Error(ENOENT) << "no service " << name;
    const std::string down = Dir(name) + "/down";
    if (starts) {
        if (auto removed = files::RemoveIn(root->get(), down); !removed.ok()) return removed.error();
    } else if (!files::ExistsIn(root->get(), down)) {
        if (auto written = files::WriteIn(root->get(), down, "", 0644); !written.ok()) {
            return written.error();
        }
    }
    LOG(INFO) << "service " << name << (starts ? " starts" : " does not start") << " with Linux";
    changed_();
    return {};
}

Result<std::string> Services::Log(const std::string& name) {
    if (!files::IsPlainName(name)) return Error(EINVAL) << "not a service name";
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return root.error();
    auto fd = files::OpenIn(root->get(), std::string(kServiceLogDir) + "/" + LogName(name),
                            O_RDONLY | O_NONBLOCK);
    if (!fd.ok()) {
        if (fd.error().code() == ENOENT) return std::string();
        return fd.error();
    }
    struct stat st;
    if (fstat(fd->get(), &st) != 0 || !S_ISREG(st.st_mode)) return Error() << "not a log file";
    const off_t start = std::max<off_t>(0, st.st_size - static_cast<off_t>(kLogTail));
    std::string tail(static_cast<size_t>(st.st_size - start), '\0');
    const ssize_t n = pread(fd->get(), tail.data(), tail.size(), start);
    if (n < 0) return ErrnoError() << "reading the log";
    tail.resize(static_cast<size_t>(n));
    if (start > 0) {
        const size_t nl = tail.find('\n');
        tail.erase(0, nl == std::string::npos ? 0 : nl + 1);
    }
    return text::SanitizeUtf8(tail);
}

Result<std::vector<std::string>> Services::Files(const std::string& name) {
    if (!files::IsPlainName(name)) return Error(EINVAL) << "not a service name";
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return root.error();
    std::vector<std::string> out;
    std::vector<std::string> pending = {""};
    while (!pending.empty() && out.size() < kMaxFiles) {
        const std::string rel = pending.back();
        pending.pop_back();
        auto dir = files::OpenIn(root->get(), Dir(name) + (rel.empty() ? "" : "/" + rel),
                                 O_RDONLY | O_DIRECTORY | O_NOFOLLOW);
        if (!dir.ok()) {
            if (rel.empty()) return Error(ENOENT) << "no service " << name;
            continue;
        }
        const int dfd = dir->get();
        std::unique_ptr<DIR, int (*)(DIR*)> d(fdopendir(dir->release()), closedir);
        if (!d) continue;
        while (dirent* e = readdir(d.get())) {
            const std::string entry = e->d_name;
            if (entry == "." || entry == ".." || !files::IsPlainName(entry)) continue;
            struct stat st;
            if (fstatat(dfd, entry.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0) continue;
            const std::string path = rel.empty() ? entry : rel + "/" + entry;
            if (S_ISDIR(st.st_mode)) {
                if (std::count(path.begin(), path.end(), '/') < 2) pending.push_back(path);
            } else if (S_ISREG(st.st_mode) || S_ISLNK(st.st_mode)) {
                out.push_back(path);
            }
        }
    }
    std::sort(out.begin(), out.end());
    return out;
}

Result<std::string> Services::File(const std::string& name, const std::string& file) {
    if (!files::IsPlainName(name)) return Error(EINVAL) << "not a service name";
    for (const std::string& part : android::base::Split(file, "/")) {
        if (!files::IsPlainName(part)) return Error(EINVAL) << "not a file of the service";
    }
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return root.error();
    auto content = files::ReadIn(root->get(), Dir(name) + "/" + file, kMaxFile);
    if (!content.ok()) return content.error();
    return text::SanitizeUtf8(*content);
}

}  // namespace lujarch::linux
