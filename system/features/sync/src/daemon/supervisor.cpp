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

#include "supervisor.h"

#include <aidl/org/lujarch/sync/ISync.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <fcntl.h>
#include <grp.h>
#include <lujarch/aid.h>
#include <lujarch/daemon.h>
#include <poll.h>
#include <private/android_filesystem_config.h>
#include <signal.h>
#include <sys/eventfd.h>
#include <sys/pidfd.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/wait.h>
#include <unistd.h>

#include <chrono>
#include <deque>
#include <thread>

#include "conditions.h"
#include "identity.h"
#include "rest.h"

namespace lujarch::sync {

namespace {

using aidl::org::lujarch::sync::ISync;
using android::base::unique_fd;

constexpr int kMaxExits = 5;
constexpr int kExitWindow = 300;
constexpr int kMaxBackoff = 60;
// The engine keeps its log and one older file, each at most this size.
constexpr int kMaxEngineLog = 4 << 20;

int64_t Monotonic() {
    return std::chrono::duration_cast<std::chrono::seconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

// The home and the sync storage: the engine's, 0750, so that the daemon reaches the
// configuration, the socket and the folders through the group.
bool StorageOk() {
    for (const char* dir : {rest::kHome, rest::kFoldersDir}) {
        struct stat st;
        if (stat(dir, &st) != 0 || !S_ISDIR(st.st_mode) || (st.st_mode & 07777) != 0750 ||
            st.st_uid != aid::kSync || st.st_gid != aid::kSync) {
            return false;
        }
    }
    return true;
}

}  // namespace

Supervisor& TheSupervisor() {
    static Supervisor supervisor;
    return supervisor;
}

void Supervisor::Start() {
    wake_.reset(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    conditions::Start([this] { Wake(); });
    for (const char* name : {"lujarch.setting.sync.enabled", "lujarch.setting.sync.wifi_only",
                             "lujarch.setting.sync.charging_only", "sys.boot_completed"}) {
        std::thread(&Supervisor::WatchProperty, this, std::string(name)).detach();
    }
    std::thread(&Supervisor::Loop, this).detach();
}

void Supervisor::WatchProperty(const std::string& name) {
    std::string previous = android::base::GetProperty(name, "");
    for (;;) {
        const prop_info* info = __system_property_find(name.c_str());
        if (info == nullptr) {
            android::base::WaitForPropertyCreation(name);
            continue;
        }
        uint32_t serial = __system_property_serial(info);
        std::string value = android::base::GetProperty(name, "");
        if (value != previous) {
            previous = value;
            // A run condition chosen or dropped is decided at once; turning sync on again ends
            // a stop after repeated failures.
            if (name != "sys.boot_completed" && name != "lujarch.setting.sync.enabled") {
                conditions::Recheck();
            }
            if (name == "lujarch.setting.sync.enabled") reset_ = true;
            Wake();
        }
        uint32_t new_serial;
        __system_property_wait(info, serial, &new_serial, nullptr);
    }
}

void Supervisor::Wake() {
    uint64_t one = 1;
    if (TEMP_FAILURE_RETRY(write(wake_.get(), &one, sizeof(one))) < 0) PLOG(ERROR) << "wake";
}

void Supervisor::DrainEvents() {
    uint64_t count;
    while (read(wake_.get(), &count, sizeof(count)) > 0) {
    }
}

void Supervisor::WaitEvent(int timeout_ms) {
    pollfd p{wake_.get(), POLLIN, 0};
    TEMP_FAILURE_RETRY(poll(&p, 1, timeout_ms));
}

void Supervisor::SetState(const std::string& state, const std::string& waiting) {
    {
        std::lock_guard lock(mutex_);
        if (status_.state == state && status_.waiting == waiting) return;
        status_.state = state;
        status_.waiting = waiting;
        if (state != ISync::STATE_RUNNING) status_.startedAt = 0;
    }
    LOG(INFO) << "state: " << state << (waiting.empty() ? "" : " (waiting for " + waiting + ")");
    if (!android::base::SetProperty(kStateProperty, state)) {
        LOG(ERROR) << "cannot set " << kStateProperty;
    }
}

aidl::org::lujarch::sync::Status Supervisor::Status() {
    aidl::org::lujarch::sync::Status status;
    {
        std::lock_guard lock(mutex_);
        status = status_;
    }
    status.identity = identity::Present();
    status.storageOk = StorageOk();
    return status;
}

std::string Supervisor::Blocked() {
    if (!SettingIsOn(kFeature, "enabled")) return ISync::STATE_OFF;
    if (android::base::GetProperty("sys.boot_completed", "") != "1") return ISync::STATE_STARTING;
    {
        std::lock_guard lock(mutex_);
        if (held_) return ISync::STATE_STARTING;
    }
    if (!conditions::Waiting().empty()) return ISync::STATE_WAITING;
    return "";
}

void Supervisor::Loop() {
    // When the engine exited in the last kExitWindow seconds.
    std::deque<int64_t> exits;
    int backoff = 2;
    bool given_up = false;
    for (;;) {
        DrainEvents();
        if (reset_.exchange(false)) {
            exits.clear();
            backoff = 2;
            given_up = false;
        }
        if (std::string blocked = Blocked(); !blocked.empty()) {
            SetState(blocked, blocked == ISync::STATE_WAITING ? conditions::Waiting() : "");
            WaitEvent(-1);
            continue;
        }
        if (given_up) {
            SetState(ISync::STATE_CRASH_LOOP);
            WaitEvent(-1);
            continue;
        }
        if (!identity::Present()) {
            LOG(WARNING) << "no device identity: import or create one in the Sync settings";
            SetState(ISync::STATE_NO_CONFIG);
            WaitEvent(-1);
            continue;
        }
        // An identity change claims the engine between the check above and here.
        {
            std::lock_guard lock(mutex_);
            if (held_) continue;
            engine_ = 0;
        }
        if (!StartEngine()) {
            {
                std::lock_guard lock(mutex_);
                engine_ = -1;
            }
            engine_cv_.notify_all();
            given_up = true;
            continue;
        }
        SetState(ISync::STATE_RUNNING);
        const int64_t started = Monotonic();
        End end = WaitEngine();
        if (end == End::kStopped) continue;
        const int64_t now = Monotonic();
        // An engine that ran through a whole window has recovered: its next pause is short again.
        if (now - started >= kExitWindow) backoff = 2;
        exits.push_back(now);
        while (now - exits.front() > kExitWindow) exits.pop_front();
        {
            std::lock_guard lock(mutex_);
            status_.exits = static_cast<int>(exits.size());
        }
        if (static_cast<int>(exits.size()) >= kMaxExits) {
            LOG(ERROR) << "the engine exited " << exits.size() << " times within " << kExitWindow
                       << " s; it stays stopped until sync is turned off and on";
            given_up = true;
            continue;
        }
        SetState(ISync::STATE_STARTING);
        const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(backoff);
        for (;;) {
            auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                    until - std::chrono::steady_clock::now());
            if (left.count() <= 0) break;
            WaitEvent(left.count());
            DrainEvents();
            if (!Blocked().empty() || reset_) break;
        }
        backoff = std::min(backoff * 2, kMaxBackoff);
    }
}

bool Supervisor::StartEngine() {
    // The engine writes and rotates its own log in its home, which only its user may write.
    unique_fd null_in(open("/dev/null", O_RDONLY | O_CLOEXEC));
    unique_fd null_out(open("/dev/null", O_WRONLY | O_CLOEXEC));
    std::vector<std::string> argv = {kEngineBinary,
                                     "serve",
                                     std::string("--home=") + rest::kHome,
                                     "--no-browser",
                                     "--no-upgrade",
                                     std::string("--log-file=") + kEngineLog,
                                     "--log-max-old-files=1",
                                     "--log-max-size=" + std::to_string(kMaxEngineLog)};
    std::vector<char*> args;
    for (std::string& arg : argv) args.push_back(arg.data());
    args.push_back(nullptr);
    std::vector<std::string> env = {
            "PATH=/system_ext/bin:/system/bin", std::string("HOME=") + rest::kHome,
            std::string("STGUIADDRESS=unix://") + rest::kSocket, "STNODEFAULTFOLDER=1"};
    std::vector<char*> envp;
    for (std::string& e : env) envp.push_back(e.data());
    envp.push_back(nullptr);
    pid_t pid = fork();
    if (pid < 0) {
        PLOG(ERROR) << "cannot start the engine: fork";
        return false;
    }
    if (pid == 0) {
        gid_t groups[] = {aid::kSync, AID_INET};
        if (setgroups(2, groups) != 0 || setresgid(aid::kSync, aid::kSync, aid::kSync) != 0 ||
            setresuid(aid::kSync, aid::kSync, aid::kSync) != 0) {
            _exit(126);
        }
        umask(027);
        if (dup2(null_in.get(), STDIN_FILENO) < 0 || dup2(null_out.get(), STDOUT_FILENO) < 0 ||
            dup2(null_out.get(), STDERR_FILENO) < 0) {
            _exit(126);
        }
        struct sigaction dfl = {};
        dfl.sa_handler = SIG_DFL;
        sigaction(SIGPIPE, &dfl, nullptr);
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, nullptr);
        execve(args[0], args.data(), envp.data());
        _exit(127);
    }
    unique_fd pidfd(pidfd_open(pid, 0));
    if (pidfd < 0) {
        PLOG(ERROR) << "pidfd_open of the engine";
        kill(pid, SIGKILL);
        TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
        return false;
    }
    {
        std::lock_guard lock(mutex_);
        engine_ = pid;
        engine_fd_ = std::move(pidfd);
        status_.startedAt = time(nullptr);
    }
    rest::SetRunning(true);
    LOG(INFO) << "engine started";
    return true;
}

Supervisor::End Supervisor::WaitEngine() {
    End end = End::kExited;
    for (;;) {
        pollfd fds[2] = {{engine_fd_.get(), POLLIN, 0}, {wake_.get(), POLLIN, 0}};
        TEMP_FAILURE_RETRY(poll(fds, 2, -1));
        if (fds[0].revents == 0) {
            DrainEvents();
            if (Blocked().empty() && !reset_) continue;
            end = End::kStopped;
            StopEngine();
        }
        int status = 0;
        TEMP_FAILURE_RETRY(waitpid(engine_, &status, 0));
        if (end == End::kExited) {
            if (WIFEXITED(status)) {
                const int code = WEXITSTATUS(status);
                LOG(WARNING) << "engine exited with " << code
                             << (code == 126   ? " (cannot become the engine's user)"
                                 : code == 127 ? " (cannot execute the engine)"
                                               : "; its log is " + std::string(kEngineLog));
            } else if (WIFSIGNALED(status)) {
                LOG(WARNING) << "engine killed by signal " << WTERMSIG(status);
            }
        }
        rest::SetRunning(false);
        {
            std::lock_guard lock(mutex_);
            engine_ = -1;
            engine_fd_.reset();
        }
        engine_cv_.notify_all();
        return end;
    }
}

void Supervisor::StopEngine() {
    pidfd_send_signal(engine_fd_.get(), SIGTERM, nullptr, 0);
    pollfd p{engine_fd_.get(), POLLIN, 0};
    if (TEMP_FAILURE_RETRY(poll(&p, 1, 10000)) > 0) return;
    LOG(WARNING) << "engine did not exit on SIGTERM; killing it";
    pidfd_send_signal(engine_fd_.get(), SIGKILL, nullptr, 0);
    TEMP_FAILURE_RETRY(poll(&p, 1, 5000));
}

android::base::Result<bool> Supervisor::WithEngineStopped(
        const std::function<android::base::Result<void>()>& install) {
    {
        std::unique_lock lock(mutex_);
        if (held_) return android::base::Error() << "another identity change is in progress";
        held_ = true;
    }
    Wake();
    {
        std::unique_lock lock(mutex_);
        engine_cv_.wait(lock, [this] { return engine_ == -1; });
    }
    auto result = install();
    {
        std::lock_guard lock(mutex_);
        held_ = false;
    }
    reset_ = true;
    Wake();
    if (!result.ok()) return result.error();
    return SettingIsOn(kFeature, "enabled") && conditions::Waiting().empty();
}

}  // namespace lujarch::sync
