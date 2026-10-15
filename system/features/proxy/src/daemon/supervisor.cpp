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

#include <aidl/org/lujarch/proxy/IProxy.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <fcntl.h>
#include <lujarch/daemon.h>
#include <poll.h>
#include <signal.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <sys/pidfd.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/wait.h>
#include <unistd.h>

#include <chrono>

#include "core.h"
#include "rules.h"

namespace lujarch::proxy {

namespace {

using aidl::org::lujarch::proxy::IProxy;
using android::base::unique_fd;
using namespace std::chrono_literals;

// The watchdog's timing, in seconds.
constexpr int kSettle = 10;       // no network change for this long: the network has settled
constexpr int kRetry = 5;         // between two path probes when the core has no proxy port
constexpr int kRecheck = 30;      // probe again while the proxy itself cannot reach the address...
constexpr int kRechecks = 10;     // ...at most this many times after a network change
constexpr int kHealth = 10;       // between liveness checks of the controller and the interface
constexpr int kHealthRetry = 5;   // after a failed liveness check
constexpr int kHealthMisses = 3;  // failed liveness checks in a row that stop the core
// Exits of the core within kExitWindow that stop it for good.
constexpr int kMaxExits = 5;
constexpr int kExitWindow = 300;
constexpr int kMaxBackoff = 60;
constexpr off_t kMaxCoreLog = 4 << 20;

int64_t Monotonic() {
    return std::chrono::duration_cast<std::chrono::seconds>(
                   std::chrono::steady_clock::now().time_since_epoch())
            .count();
}

int64_t Now() {
    return time(nullptr);
}

int64_t ModifiedAt(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0 ? st.st_mtime : 0;
}

int Port(const std::string& text) {
    if (text.empty() || text.size() > 5 ||
        text.find_first_not_of("0123456789") != std::string::npos) {
        return 0;
    }
    int port = std::stoi(text);
    return port <= 65535 ? port : 0;
}

// Runs argv with its output captured, at most timeout; returns the exit code, -1 when it could not
// run or was killed.
int RunCapture(std::vector<std::string> argv, std::chrono::seconds timeout, std::string* output) {
    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) return -1;
    unique_fd read_end(fds[0]), write_end(fds[1]);
    unique_fd null_fd(open("/dev/null", O_RDONLY | O_CLOEXEC));
    std::vector<char*> args;
    for (std::string& arg : argv) args.push_back(arg.data());
    args.push_back(nullptr);
    static char kPath[] = "PATH=/system_ext/bin:/system/bin";
    char* envp[] = {kPath, nullptr};
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        if (dup2(null_fd.get(), STDIN_FILENO) < 0 || dup2(write_end.get(), STDOUT_FILENO) < 0 ||
            dup2(write_end.get(), STDERR_FILENO) < 0) {
            _exit(126);
        }
        execve(args[0], args.data(), envp);
        _exit(127);
    }
    write_end.reset();
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    bool timed_out = false;
    char buf[4096];
    for (;;) {
        auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now());
        pollfd p{read_end.get(), POLLIN, 0};
        if (left.count() <= 0 || TEMP_FAILURE_RETRY(poll(&p, 1, left.count())) <= 0) {
            timed_out = true;
            break;
        }
        ssize_t n = TEMP_FAILURE_RETRY(read(read_end.get(), buf, sizeof(buf)));
        if (n <= 0) break;
        if (output->size() < (64u << 10)) output->append(buf, n);
    }
    if (timed_out) kill(pid, SIGKILL);
    int status = 0;
    TEMP_FAILURE_RETRY(waitpid(pid, &status, 0));
    if (timed_out || !WIFEXITED(status)) return -1;
    return WEXITSTATUS(status);
}

std::string LastLines(const std::string& text, size_t count) {
    std::vector<std::string> lines = android::base::Split(android::base::Trim(text), "\n");
    if (lines.size() > count) lines.erase(lines.begin(), lines.end() - count);
    return android::base::Join(lines, "\n");
}

}  // namespace

struct Supervisor::Run {
    pid_t pid = -1;
    unique_fd pidfd;
    std::string config;
    bool repair = false;
    std::atomic<bool> done = false;
    // How the run ends when the supervisor or the watchdog stops the core.
    std::atomic<End> end = End::kExited;
    std::atomic<bool> ending = false;
    // Default-interface changes in the core's log, and when the last one came.
    std::mutex mutex;
    std::condition_variable changed;
    int changes = 0;
    int64_t last_change = 0;
};

Supervisor& TheSupervisor() {
    static Supervisor supervisor;
    return supervisor;
}

void Supervisor::Start() {
    wake_.reset(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    inotify_.reset(inotify_init1(IN_CLOEXEC | IN_NONBLOCK));
    if (inotify_add_watch(inotify_.get(), kDir, IN_CLOSE_WRITE | IN_MOVED_TO) < 0) {
        PLOG(ERROR) << "cannot watch " << kDir << "; restart requests of the manager are not seen";
    }
    // Rules a previous run of the daemon left.
    rules::UnblockIpv6();
    for (const char* name :
         {"lujarch.setting.proxy.enabled", "sys.boot_completed", "init.svc.netd"}) {
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
            // netd flushes every policy rule when it starts again: the core's and the IPv6 block.
            if (name == "init.svc.netd" && value == "running" && !previous.empty()) {
                netd_restarted_ = true;
            }
            previous = value;
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

void Supervisor::RequestRestart() {
    restart_requested_ = true;
    // Whoever asked sees the restart begin before the loop gets to it.
    if (SettingIsOn(kFeature, "enabled")) SetState(IProxy::STATE_STARTING);
    Wake();
}

void Supervisor::DrainEvents() {
    uint64_t count;
    while (read(wake_.get(), &count, sizeof(count)) > 0) {
    }
    alignas(inotify_event) char buf[4096];
    ssize_t n;
    while ((n = read(inotify_.get(), buf, sizeof(buf))) > 0) {
        for (char* p = buf; p < buf + n;) {
            auto* event = reinterpret_cast<inotify_event*>(p);
            if (event->len > 0 && strcmp(event->name, kRestartRequest) == 0) {
                std::string path = std::string(kDir) + "/" + kRestartRequest;
                if (unlink(path.c_str()) == 0) {
                    LOG(INFO) << "the manager asks for a restart of the core";
                    restart_requested_ = true;
                }
            }
            p += sizeof(inotify_event) + event->len;
        }
    }
}

void Supervisor::WaitEvent(int timeout_ms) {
    pollfd fds[2] = {{wake_.get(), POLLIN, 0}, {inotify_.get(), POLLIN, 0}};
    TEMP_FAILURE_RETRY(poll(fds, 2, timeout_ms));
}

bool Supervisor::Valid(const std::string& config, std::string* error) {
    struct stat st;
    if (stat(config.c_str(), &st) != 0 || st.st_size == 0) {
        *error = "no configuration";
        return false;
    }
    std::string output;
    int code = RunCapture({kCoreBinary, "-t", "-d", kDir, "-f", config}, 120s, &output);
    if (code == 0) return true;
    *error = code < 0 ? "the check did not finish" : LastLines(output, 3);
    return false;
}

void Supervisor::SetState(const std::string& state, const std::string& check_error) {
    std::vector<std::shared_ptr<Listener>> listeners;
    {
        std::lock_guard lock(mutex_);
        status_.checkError = check_error;
        if (status_.state == state) return;
        status_.state = state;
        if (state != IProxy::STATE_RUNNING && state != IProxy::STATE_FALLBACK) {
            status_.startedAt = 0;
            status_.tun = false;
        }
        listeners = listeners_;
    }
    LOG(INFO) << "state: " << state;
    if (!android::base::SetProperty(kStateProperty, state)) {
        LOG(ERROR) << "cannot set " << kStateProperty;
    }
    for (const auto& listener : listeners) {
        if (!listener->onState(state).isOk()) {
            std::lock_guard lock(mutex_);
            std::erase(listeners_, listener);
        }
    }
}

void Supervisor::Watch(const std::shared_ptr<Listener>& listener) {
    std::string state;
    {
        std::lock_guard lock(mutex_);
        listeners_.push_back(listener);
        state = status_.state;
    }
    listener->onState(state);
}

aidl::org::lujarch::proxy::Status Supervisor::Status() {
    aidl::org::lujarch::proxy::Status status;
    {
        std::lock_guard lock(mutex_);
        status = status_;
        status.ipv6Blocked = ipv6_blocked_;
    }
    status.lastGoodAt = ModifiedAt(kLastGood);
    return status;
}

void Supervisor::SetTun(bool tun) {
    std::lock_guard lock(mutex_);
    status_.tun = tun;
}

void Supervisor::NoteRestart(const std::string& reason) {
    std::lock_guard lock(mutex_);
    status_.lastRestart = reason;
    status_.lastRestartAt = Now();
}

void Supervisor::SetIpv6Block(bool on, const std::string& config) {
    std::lock_guard lock(mutex_);
    if (on) {
        rules::BlockIpv6(core::NodeIpv6(config));
        if (!ipv6_blocked_) LOG(INFO) << "IPv6 blocked while the proxy runs";
    } else if (ipv6_blocked_) {
        rules::UnblockIpv6();
        LOG(INFO) << "IPv6 block lifted";
    }
    ipv6_blocked_ = on;
}

void Supervisor::Loop() {
    int exits = 0;
    int64_t window_start = Monotonic();
    int backoff = 2;
    bool repair = false;
    bool given_up = false;
    for (;;) {
        DrainEvents();
        if (!SettingIsOn(kFeature, "enabled")) {
            exits = 0;
            backoff = 2;
            given_up = false;
            repair = false;
            restart_requested_ = false;
            SetIpv6Block(false, "");
            SetState(IProxy::STATE_OFF);
            WaitEvent(-1);
            continue;
        }
        if (android::base::GetProperty("sys.boot_completed", "") != "1" ||
            android::base::GetProperty("init.svc.netd", "") != "running") {
            SetState(IProxy::STATE_STARTING);
            WaitEvent(-1);
            continue;
        }
        if (restart_requested_.exchange(false)) {
            exits = 0;
            window_start = Monotonic();
            backoff = 2;
            given_up = false;
            repair = false;
        }
        if (given_up) {
            SetIpv6Block(false, "");
            SetState(IProxy::STATE_CRASH_LOOP);
            WaitEvent(-1);
            continue;
        }
        std::string error, use = kConfig;
        if (!Valid(kConfig, &error)) {
            std::string ignored;
            if (!Valid(kLastGood, &ignored)) {
                LOG(ERROR) << "no configuration passes the check; the phone connects directly: "
                           << error;
                SetIpv6Block(false, "");
                SetState(IProxy::STATE_NO_CONFIG, error);
                // A configuration the manager installs is tried at once.
                WaitEvent(-1);
                continue;
            }
            LOG(WARNING) << "the configuration does not pass the check; the last working one runs: "
                         << error;
            use = kLastGood;
        }
        SetIpv6Block(SettingIsOn(kFeature, "ipv6_block") && core::TunEnabled(kConfig) &&
                             core::TunEnabled(use),
                     use);
        netd_restarted_ = false;
        std::shared_ptr<Run> run = StartCore(use, repair);
        End end = End::kExited;
        if (run) {
            SetState(use == kConfig ? IProxy::STATE_RUNNING : IProxy::STATE_FALLBACK,
                     use == kConfig ? "" : error);
            end = WaitCore(run.get());
        }
        repair = false;
        switch (end) {
            case End::kSwitchedOff:
                continue;
            case End::kRequested:
                LOG(INFO) << "core restarted on request";
                continue;
            case End::kRepair:
                LOG(INFO) << "core restarted to repair the proxy path after a network change";
                NoteRestart(IProxy::RESTART_REPAIR);
                repair = true;
                continue;
            case End::kNetworkStack:
                LOG(INFO) << "core restarted after the network stack started again";
                NoteRestart(IProxy::RESTART_NETWORK_STACK);
                continue;
            case End::kUnhealthy:
            case End::kExited:
                break;
        }
        if (Monotonic() - window_start > kExitWindow) {
            exits = 0;
            window_start = Monotonic();
            backoff = 2;
        }
        ++exits;
        NoteRestart(end == End::kUnhealthy ? IProxy::RESTART_UNHEALTHY : IProxy::RESTART_EXITED);
        {
            std::lock_guard lock(mutex_);
            status_.exits = exits;
        }
        if (exits >= kMaxExits) {
            LOG(ERROR) << "the core exited " << exits << " times within " << kExitWindow
                       << " s; it stays stopped and the phone connects directly";
            given_up = true;
            continue;
        }
        SetIpv6Block(false, "");
        SetState(IProxy::STATE_STARTING);
        // The pause ends early only for the switch or a restart asked for.
        const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(backoff);
        for (;;) {
            auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                    until - std::chrono::steady_clock::now());
            if (left.count() <= 0) break;
            WaitEvent(left.count());
            DrainEvents();
            if (!SettingIsOn(kFeature, "enabled") || restart_requested_) break;
        }
        backoff = std::min(backoff * 2, kMaxBackoff);
    }
}

std::shared_ptr<Supervisor::Run> Supervisor::StartCore(const std::string& config, bool repair) {
    // The output of this run and the one before.
    std::string previous = std::string(kCoreLog) + ".1";
    if (rename(kCoreLog, previous.c_str()) != 0 && errno != ENOENT) {
        PLOG(WARNING) << "cannot keep the previous core log";
    }
    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) {
        PLOG(ERROR) << "cannot start the core: pipe2";
        return nullptr;
    }
    unique_fd read_end(fds[0]), write_end(fds[1]);
    unique_fd null_fd(open("/dev/null", O_RDONLY | O_CLOEXEC));
    std::vector<std::string> argv = {kCoreBinary, "-d", kDir, "-f", config};
    std::vector<char*> args;
    for (std::string& arg : argv) args.push_back(arg.data());
    args.push_back(nullptr);
    static char kPath[] = "PATH=/system_ext/bin:/system/bin";
    char* envp[] = {kPath, nullptr};
    pid_t pid = fork();
    if (pid < 0) {
        PLOG(ERROR) << "cannot start the core: fork";
        return nullptr;
    }
    if (pid == 0) {
        umask(077);
        if (dup2(null_fd.get(), STDIN_FILENO) < 0 || dup2(write_end.get(), STDOUT_FILENO) < 0 ||
            dup2(write_end.get(), STDERR_FILENO) < 0) {
            _exit(126);
        }
        struct sigaction dfl = {};
        dfl.sa_handler = SIG_DFL;
        sigaction(SIGPIPE, &dfl, nullptr);
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, nullptr);
        execve(args[0], args.data(), envp);
        static constexpr char kMessage[] = "lujarch_proxy: cannot execute the core\n";
        (void)!write(STDERR_FILENO, kMessage, sizeof(kMessage) - 1);
        _exit(127);
    }
    write_end.reset();
    auto run = std::make_shared<Run>();
    run->pid = pid;
    run->pidfd.reset(pidfd_open(pid, 0));
    run->config = config;
    run->repair = repair;
    if (run->pidfd < 0) {
        PLOG(ERROR) << "pidfd_open of the core";
        kill(pid, SIGKILL);
        TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
        return nullptr;
    }
    {
        std::lock_guard lock(mutex_);
        status_.startedAt = Now();
        status_.configAt = ModifiedAt(config);
    }
    LOG(INFO) << "core started with " << config << (repair ? " to repair the proxy path" : "");
    std::thread([this, run, output = std::move(read_end)]() mutable {
        ReadCoreOutput(run.get(), std::move(output));
    }).detach();
    std::thread([this, run] { Watchdog(run.get()); }).detach();
    return run;
}

void Supervisor::ReadCoreOutput(Run* run, unique_fd output) {
    unique_fd log(open(kCoreLog, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600));
    if (log < 0) PLOG(ERROR) << "cannot write " << kCoreLog;
    std::string partial;
    char buf[8192];
    off_t written = 0;
    for (;;) {
        ssize_t n = TEMP_FAILURE_RETRY(read(output.get(), buf, sizeof(buf)));
        if (n <= 0) return;
        if (log >= 0 && written < kMaxCoreLog) {
            written += n;
            if (!android::base::WriteFully(log.get(), buf, n)) log.reset();
        }
        partial.append(buf, n);
        size_t start = 0, newline;
        while ((newline = partial.find('\n', start)) != std::string::npos) {
            std::string_view line(partial.data() + start, newline - start);
            start = newline + 1;
            // sing-tun logs every default-interface change; the first one of a run is the start.
            if (line.find("default interface changed by monitor") != std::string_view::npos ||
                line.find("default interface lost by monitor") != std::string_view::npos) {
                std::lock_guard lock(run->mutex);
                ++run->changes;
                run->last_change = Monotonic();
                run->changed.notify_all();
            }
        }
        partial.erase(0, start);
        if (partial.size() > (1u << 20)) partial.clear();
    }
}

Supervisor::End Supervisor::WaitCore(Run* run) {
    for (;;) {
        pollfd fds[3] = {{run->pidfd.get(), POLLIN, 0},
                         {wake_.get(), POLLIN, 0},
                         {inotify_.get(), POLLIN, 0}};
        TEMP_FAILURE_RETRY(poll(fds, 3, -1));
        if (fds[0].revents == 0) {
            DrainEvents();
            End end;
            if (!SettingIsOn(kFeature, "enabled")) {
                end = End::kSwitchedOff;
            } else if (restart_requested_) {
                end = End::kRequested;
            } else if (netd_restarted_.exchange(false)) {
                end = End::kNetworkStack;
            } else {
                continue;
            }
            if (!run->ending.exchange(true)) run->end = end;
            StopCore(run);
        }
        int status = 0;
        TEMP_FAILURE_RETRY(waitpid(run->pid, &status, 0));
        {
            std::lock_guard lock(run->mutex);
            run->done = true;
            run->changed.notify_all();
        }
        if (!run->ending) {
            if (WIFEXITED(status)) {
                LOG(WARNING) << "core exited with " << WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                LOG(WARNING) << "core killed by signal " << WTERMSIG(status);
            }
        }
        return run->end;
    }
}

void Supervisor::StopCore(Run* run) {
    // SIGCONT after SIGTERM: a stopped core holds SIGTERM until it runs again, and then ends as
    // on any stop, its interface and routes removed by itself.
    pidfd_send_signal(run->pidfd.get(), SIGTERM, nullptr, 0);
    pidfd_send_signal(run->pidfd.get(), SIGCONT, nullptr, 0);
    pollfd p{run->pidfd.get(), POLLIN, 0};
    if (TEMP_FAILURE_RETRY(poll(&p, 1, 5000)) > 0) return;
    LOG(WARNING) << "core did not exit on SIGTERM; killing it";
    pidfd_send_signal(run->pidfd.get(), SIGKILL, nullptr, 0);
    TEMP_FAILURE_RETRY(poll(&p, 1, 5000));
}

bool Supervisor::ShouldStop(Run* run, std::chrono::seconds wait) {
    std::unique_lock lock(run->mutex);
    return run->changed.wait_for(lock, wait, [run] { return run->done.load(); });
}

namespace {

// The watchdog of one run (Supervisor::Watchdog). The core stays up through network changes,
// since its interface follows the default network itself; it is stopped only when it is
// unhealthy (CheckHealth), or when its path stays broken after a network change while the proxy
// itself works. The path is judged only while the liveness checks pass.
struct WatchdogState {
    // Liveness.
    std::string ctl_host;
    int ctl_port = 0;
    int proxy_port = 0;
    bool ctl_due = false;
    bool tun = false;
    bool tun_seen = false;
    int misses = 0;
    int64_t next_health = 0;
    // The path.
    bool started = false;
    int seen_changes = 0;
    int64_t last_change = 0;
    bool pending = false;
    bool suppressed = false;
    bool told = false;
    int rechecks = 0;
    int64_t next_check = 0;
};

}  // namespace

void Supervisor::Watchdog(Run* run) {
    WatchdogState w;
    const std::string& use = run->config;
    std::string ctl = core::Top(use, "external-controller");
    size_t colon = ctl.rfind(':');
    if (colon != std::string::npos) {
        w.ctl_host = ctl.substr(0, colon);
        w.ctl_port = Port(ctl.substr(colon + 1));
    }
    if (w.ctl_host.empty() || w.ctl_host == "0.0.0.0" || w.ctl_host == "*" || w.ctl_host == "::" ||
        w.ctl_host == "[::]") {
        w.ctl_host = "127.0.0.1";
    }
    if (w.ctl_host.size() > 2 && w.ctl_host.front() == '[' && w.ctl_host.back() == ']') {
        w.ctl_host = w.ctl_host.substr(1, w.ctl_host.size() - 2);
    }
    w.proxy_port = Port(core::Top(use, "mixed-port"));
    if (w.proxy_port == 0) w.proxy_port = Port(core::Top(use, "port"));
    auto tun_wanted = [&] {
        return core::TunEnabled(kConfig) && (use == kConfig || core::TunEnabled(use));
    };
    w.tun = tun_wanted();
    w.pending = w.suppressed = run->repair;
    // At log level error the start-up detection (a warning) is not logged, only losses are; at
    // silent nothing is.
    std::string level = core::Top(use, "log-level");
    if (level == "error" || level == "silent") w.started = true;
    w.next_health = Monotonic() + kHealthRetry;

    auto stop = [&](End end) {
        if (run->done || run->ending.exchange(true)) return;
        run->end = end;
        StopCore(run);
    };
    // New default-interface changes since the last look.
    auto read_changes = [&] {
        std::lock_guard lock(run->mutex);
        int fresh = run->changes - w.seen_changes;
        w.seen_changes = run->changes;
        if (fresh == 0) return;
        w.last_change = run->last_change;
        if (!w.started) {
            w.started = true;
            --fresh;
        }
        if (fresh > 0) {
            w.pending = true;
            w.rechecks = 0;
            w.next_check = 0;
            w.told = false;
        }
    };
    // One liveness check: the controller answers and the interface is there. After kHealthMisses
    // failed checks in a row the core is stopped like an exit. The controller is judged once it
    // should answer: it answered in this run, the interface is up (the core starts its controller
    // before its interface), or its port listens; before that the core is still reading its
    // configuration.
    auto check_health = [&]() -> bool {
        int64_t t = Monotonic();
        std::string lost_ctl, lost_tun;
        if (core::TunPresent()) {
            if (!w.tun) {
                LOG(INFO) << "the interface " << core::kTunDevice
                          << " is up; the watchdog checks it and probes the path";
                w.tun = true;
                if (!run->done && SettingIsOn(kFeature, "ipv6_block")) SetIpv6Block(true, use);
            }
            w.tun_seen = true;
        } else if (!tun_wanted()) {
            if (w.tun) {
                LOG(INFO) << "the interface " << core::kTunDevice
                          << " is off by the configuration; not a failure";
                w.tun = false;
                if (!run->done) SetIpv6Block(false, "");
            }
            w.tun_seen = false;
        } else if (w.tun_seen) {
            lost_tun = std::string("the interface ") + core::kTunDevice + " is gone";
        }
        if (!run->done) SetTun(w.tun_seen);
        if (w.ctl_port != 0) {
            // Due or not is settled before the controller is asked: a port that opens while it
            // is asked is a start, not a miss.
            if (!w.ctl_due && (w.tun_seen || core::Listening(w.ctl_port))) w.ctl_due = true;
            if (core::ControllerAnswers(w.ctl_host, w.ctl_port)) {
                w.ctl_due = true;
            } else if (w.ctl_due) {
                lost_ctl = "the controller does not answer";
            }
        }
        std::string lost = lost_ctl;
        if (!lost_tun.empty()) lost += (lost.empty() ? "" : ", ") + lost_tun;
        if (lost.empty()) {
            w.misses = 0;
            w.next_health = t + kHealth;
            return true;
        }
        ++w.misses;
        w.next_health = t + kHealthRetry;
        if (w.misses < kHealthMisses) return true;
        LOG(WARNING) << "core unhealthy: " << lost << " (" << w.misses
                     << " checks in a row); stopping it";
        stop(End::kUnhealthy);
        return false;
    };

    while (!ShouldStop(run, 2s)) {
        read_changes();
        if (Monotonic() >= w.next_health && !check_health()) return;
        if (w.misses != 0 || !w.tun || !w.pending) continue;
        int64_t now = Monotonic();
        if (now - w.last_change < kSettle || now < w.next_check) continue;
        const int64_t mark = w.last_change;
        auto settled = [&] {
            read_changes();
            return w.last_change == mark && !run->done;
        };
        enum { kUp, kNode, kBroken } verdict = kUp;
        core::CoreProbe own = core::CoreProbe::kNoAnswer;
        if (!core::Probe()) {
            if (!settled()) continue;
            // The core first: a probe through a core that does not answer says nothing about the
            // path or the network.
            if (!check_health()) return;
            if (w.misses != 0) continue;
            own = core::ProbeThroughCore(w.proxy_port);
            if (w.proxy_port == 0 && ShouldStop(run, std::chrono::seconds(kRetry))) return;
            if (!settled()) continue;
            if (own == core::CoreProbe::kNoReach) {
                verdict = kNode;
            } else if (core::Probe()) {
                // The first failure was passing, not a broken path.
                verdict = kUp;
            } else {
                verdict = kBroken;
            }
            if (!settled()) continue;
            // A core that hung during these probes failed them too.
            if (verdict != kUp) {
                if (!check_health()) return;
                if (w.misses != 0) continue;
            }
        }
        switch (verdict) {
            case kUp:
                if (w.told || w.suppressed) LOG(INFO) << "proxy path works";
                w.pending = w.suppressed = w.told = false;
                w.rechecks = 0;
                break;
            case kNode:
                if (++w.rechecks >= kRechecks) {
                    LOG(WARNING) << "proxy path still broken after " << w.rechecks
                                 << " checks, the proxy's own request too; not restarting";
                    w.pending = false;
                    w.rechecks = 0;
                } else {
                    if (!w.told) {
                        LOG(WARNING) << "proxy path broken after a network change, the proxy's "
                                        "own request too (a node or the network); not restarting";
                    }
                    w.told = true;
                    w.next_check = Monotonic() + kRecheck;
                }
                break;
            case kBroken: {
                std::string why = own == core::CoreProbe::kWorks ? "the proxy's own request works"
                                  : w.proxy_port != 0 ? "the proxy's own request got no answer"
                                                      : "no proxy port to compare with";
                if (w.suppressed) {
                    LOG(WARNING) << "proxy path broken (" << why
                                 << "); the restart before did not repair it";
                    w.pending = false;
                } else if (!SettingIsOn(kFeature, "repair")) {
                    LOG(WARNING) << "proxy path broken (" << why
                                 << "); Repair after network changes is off";
                    w.pending = false;
                } else {
                    LOG(WARNING) << "proxy path broken after the network settled (" << why
                                 << "); restarting the core";
                    stop(End::kRepair);
                    return;
                }
                break;
            }
        }
    }
}

}  // namespace lujarch::proxy
