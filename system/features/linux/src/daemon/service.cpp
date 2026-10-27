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

#include "service.h"

#include <aidl/org/lujarch/root/IRoot.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <dirent.h>
#include <fcntl.h>
#include <lujarch/aid.h>
#include <lujarch/daemon.h>
#include <lujarch/text.h>
#include <poll.h>
#include <signal.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <condition_variable>
#include <ctime>
#include <map>
#include <memory>
#include <thread>

#include "archive.h"
#include "files.h"
#include "paths.h"

namespace lujarch::linux {

namespace {

using aidl::org::lujarch::linux::AndroidState;
using aidl::org::lujarch::linux::ILinux;
using aidl::org::lujarch::linux::ILinuxListener;
using aidl::org::lujarch::linux::LinuxLocation;
using aidl::org::lujarch::linux::LinuxNotification;
using aidl::org::lujarch::linux::LinuxService;
using aidl::org::lujarch::linux::RemoteConfig;
using aidl::org::lujarch::linux::RemoteStatus;
using aidl::org::lujarch::linux::Status;
using android::base::unique_fd;

constexpr size_t kMaxColors = 64 << 10;
constexpr size_t kMaxNotifications = 200;
constexpr size_t kMaxNotificationText = 1024;
// The switches the daemon follows, as lujarch.setting.linux.<key>.
constexpr const char* kSwitches[] = {"enabled",          "root_sessions",   "memory",
                                     "swap",             "lend_clipboard",  "lend_notifications",
                                     "lend_sensors",     "lend_usb",        "lend_fingerprint",
                                     "lend_ir",          "lend_nfc",        "lend_microphone",
                                     "lend_camera",      "lend_location",   "lend_vibration",
                                     "lend_quick_settings", "remote"};

enum class Caller { kSettings, kTerminal, kEither };

ndk::ScopedAStatus Fail(int code, const std::string& why,
                        android::base::LogSeverity severity = android::base::WARNING) {
    LOG(severity) << "uid " << AIBinder_getCallingUid() << ": " << why;
    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(code, why.c_str());
}

// Linux mode's side: the home screen, which shows the terminal and the desktop, and the Terminal
// app, which serves the parts Linux is lent.
bool TerminalSide() { return CallerIs(kTerminalPackage) || CallerIs(kHomePackage); }

bool Allowed(Caller caller, ndk::ScopedAStatus* refusal) {
    const bool settings = CallerIs(kSettingsPackage);
    const bool terminal = TerminalSide();
    if ((caller == Caller::kSettings && settings) || (caller == Caller::kTerminal && terminal) ||
        (caller == Caller::kEither && (settings || terminal))) {
        return true;
    }
    *refusal = Fail(ILinux::ERROR_REFUSED, "not a caller of this method");
    return false;
}

#define CHECK_CALLER(caller)                                   \
    do {                                                       \
        ndk::ScopedAStatus refusal_;                           \
        if (!Allowed(caller, &refusal_)) return refusal_;      \
    } while (0)

bool IsUserName(const std::string& name) {
    if (name.empty() || name.size() > 32 || name == "root") return false;
    if (!(name[0] == '_' || (name[0] >= 'a' && name[0] <= 'z'))) return false;
    return std::all_of(name.begin(), name.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    });
}

std::string KeptName(int64_t installed_at) {
    const time_t t = static_cast<time_t>(installed_at > 0 ? installed_at : time(nullptr));
    tm utc;
    gmtime_r(&t, &utc);
    char buf[32];
    strftime(buf, sizeof(buf), "%Y%m%d-%H%M%S", &utc);
    return std::string(kKeptPrefix) + buf;
}

std::optional<int64_t> ParseKept(const std::string& name) {
    if (!android::base::StartsWith(name, kKeptPrefix)) return std::nullopt;
    tm utc = {};
    int suffix = 0;
    if (sscanf(name.c_str() + strlen(kKeptPrefix), "%4d%2d%2d-%2d%2d%2d-%d", &utc.tm_year,
               &utc.tm_mon, &utc.tm_mday, &utc.tm_hour, &utc.tm_min, &utc.tm_sec, &suffix) < 6) {
        return std::nullopt;
    }
    utc.tm_year -= 1900;
    utc.tm_mon -= 1;
    return static_cast<int64_t>(timegm(&utc));
}

bool Exists(const char* path) {
    struct stat st;
    return lstat(path, &st) == 0;
}

}  // namespace

Service::Service()
    : sessions_(
              system_, [this] { Changed(); },
              [this](const std::string& id, int status) { Exited(id, status); }),
      services_(system_, [this] { Changed(); }),
      remote_(system_, [this] { Changed(); }),
      bridge_(system_,
              Bridge::Asks{
                      [this] {
                          std::lock_guard lock(listeners_mutex_);
                          return std::any_of(listeners_.begin(), listeners_.end(),
                                             [](const Listener& l) { return l.terminal; });
                      },
                      [this](int id, const std::string& text) {
                          ForTerminal([&](ILinuxListener& l) { l.onAuthRequest(id, text); });
                      },
                      [this](int id, const std::vector<uint8_t>& command) {
                          ForTerminal([&](ILinuxListener& l) { l.onNfcRequest(id, command); });
                      },
                      [this](const std::string& copied) {
                          ForTerminal([&](ILinuxListener& l) { l.onClipboard(copied); });
                      },
                      [this](const std::string& key, const std::string& value) {
                          ForTerminal([&](ILinuxListener& l) { l.onAndroidSet(key, value); });
                      },
                      [this](const std::string& pattern) {
                          ForTerminal([&](ILinuxListener& l) { l.onVibrate(pattern); });
                      },
                      [this](const std::string& stream, bool wanted) {
                          ForTerminal([&](ILinuxListener& l) { l.onStream(stream, wanted); });
                      },
                      [this] { ForTerminal([](ILinuxListener& l) { l.onShowTerminal(); }); }}),
      death_(AIBinder_DeathRecipient_new(&Service::OnListenerDied)) {
    // The cookie is the service, which outlives every link: nothing to release when one ends.
    AIBinder_DeathRecipient_setOnUnlinked(death_.get(), [](void*) {});
    auto values = files::ReadKeyValues(kStateFile);
    android::base::ParseInt(values["installed"], &persisted_.installed_at);
    persisted_.user = values["user"];
    persisted_.setup_done = values["setup"] == "done";
    persisted_.action = values["action"];
    persisted_.action_ok = values["action_ok"] == "1";
    persisted_.action_message = values["action_message"];
    android::base::ParseInt(values["action_time"], &persisted_.action_time);
}

void Service::SaveState() {
    std::map<std::string, std::string> values = {
            {"installed", std::to_string(persisted_.installed_at)},
            {"user", persisted_.user},
            {"setup", persisted_.setup_done ? "done" : "needed"},
            {"action", persisted_.action},
            {"action_ok", persisted_.action_ok ? "1" : "0"},
            {"action_message", persisted_.action_message},
            {"action_time", std::to_string(persisted_.action_time)},
    };
    for (auto& [key, value] : values) std::replace(value.begin(), value.end(), '\n', ' ');
    if (auto w = files::WriteKeyValues(kStateFile, values, 0600); !w.ok()) {
        LOG(ERROR) << "state: " << w.error();
    }
}

void Service::Start() {
    wake_.reset(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    if (wake_ < 0) PLOG(FATAL) << "eventfd";
    sessions_.Start();
    services_.Start();
    remote_.Start();
    bridge_.Start();
    display_.Start();
    for (const char* key : kSwitches) {
        std::thread(&Service::WatchProperty, this,
                    std::string("lujarch.setting.") + kFeature + "." + key)
                .detach();
    }
    // Linux's time zone follows Android's.
    std::thread(&Service::WatchProperty, this, std::string(kTimezoneProperty)).detach();
    std::thread(&Service::KernelSuRootLoop, this).detach();
    std::thread(&Service::Loop, this).detach();
}

void Service::Wake() {
    uint64_t one = 1;
    TEMP_FAILURE_RETRY(write(wake_.get(), &one, sizeof(one)));
}

void Service::WatchProperty(const std::string& name) {
    std::string previous = android::base::GetProperty(name, "");
    for (;;) {
        const prop_info* info = __system_property_find(name.c_str());
        if (info == nullptr) {
            android::base::WaitForPropertyCreation(name);
            Wake();
            continue;
        }
        uint32_t serial = __system_property_serial(info);
        const std::string value = android::base::GetProperty(name, "");
        if (value != previous) {
            previous = value;
            Wake();
        }
        uint32_t changed;
        __system_property_wait(info, serial, &changed, nullptr);
    }
}

void Service::SetState(const std::string& state, const std::string& reason) {
    {
        std::lock_guard lock(mutex_);
        if (state_ == state && reason_ == reason) return;
        state_ = state;
        reason_ = reason;
    }
    LOG(INFO) << "state: " << state << (reason.empty() ? "" : " (" + reason + ")");
    Changed();
}

void Service::Loop() {
    // Programs and swap of an earlier run of the daemon.
    cgroup::KillAll();
    if (auto off = swap::Off(); !off.ok()) LOG(ERROR) << off.error();
    if (Exists(kStaging)) {
        unique_fd systems(open(kSystems, O_RDONLY | O_DIRECTORY | O_CLOEXEC));
        if (auto r = files::RemoveTree(systems.get(), "root.new"); !r.ok()) {
            LOG(ERROR) << "an interrupted install: " << r.error();
        }
    }
    for (;;) {
        Reconcile();
        std::vector<pollfd> fds = {{wake_.get(), POLLIN, 0}};
        if (job_.pid > 0) {
            fds.push_back({job_.pidfd.get(), POLLIN, 0});
            if (job_output_ >= 0) fds.push_back({job_output_.get(), POLLIN, 0});
        }
        TEMP_FAILURE_RETRY(poll(fds.data(), fds.size(), -1));
        uint64_t count;
        while (read(wake_.get(), &count, sizeof(count)) > 0) {
        }
        if (job_output_ >= 0) {
            char buf[4096];
            ssize_t n;
            while ((n = read(job_output_.get(), buf, sizeof(buf))) > 0) {
                if (job_log_ >= 0) TEMP_FAILURE_RETRY(write(job_log_.get(), buf, static_cast<size_t>(n)));
                job_partial_.append(buf, static_cast<size_t>(n));
            }
            if (n == 0) job_output_.reset();
            size_t nl;
            while ((nl = job_partial_.find('\n')) != std::string::npos) {
                const std::string line = android::base::Trim(job_partial_.substr(0, nl));
                job_partial_.erase(0, nl + 1);
                if (!line.empty()) job_last_line_ = text::SanitizeUtf8(line);
            }
            if (job_partial_.size() > 4096) job_partial_.clear();
        }
        if (job_.pid > 0) {
            int status = 0;
            if (waitpid(job_.pid, &status, WNOHANG) == job_.pid) {
                job_.pid = -1;
                job_.pidfd.reset();
                JobEnded(ExitCode(status));
            }
        }
    }
}

void Service::Reconcile() {
    bool replace;
    bool setup_requested;
    bool desktop_requested;
    {
        std::lock_guard lock(mutex_);
        replace = replace_pending_;
        setup_requested = setup_requested_;
        setup_requested_ = false;
        desktop_requested = desktop_requested_;
        desktop_requested_ = false;
    }
    if (replace) {
        if (system_.Created()) StopLinux();
        Replace();
    }
    const bool installed = Exists(kRoot);
    const bool enabled = FeatureEnabled(kFeature);
    if (!enabled) held_ = false;
    const bool want = enabled && installed && !held_;
    if (want && !system_.Created()) {
        StartLinux();
    } else if (!want && system_.Created()) {
        StopLinux();
    }
    if (!system_.Created() && !held_) {
        SetState(installed ? ILinux::STATE_STOPPED : ILinux::STATE_NOT_INSTALLED);
    }
    if (setup_requested && system_.Created() && job_.pid <= 0) StartJob(ILinux::ACTION_SETUP);
    if (desktop_requested) {
        if (system_.Created() && linux_ready_ && job_.pid <= 0) {
            StartJob(ILinux::ACTION_DESKTOP);
        } else {
            {
                std::lock_guard lock(mutex_);
                action_running_ = false;
                persisted_.action = ILinux::ACTION_DESKTOP;
                persisted_.action_ok = false;
                persisted_.action_message = "Linux does not run";
                persisted_.action_time = time(nullptr);
                SaveState();
            }
            Changed();
        }
    }

    // The switches that apply while Linux runs.
    std::string memory = Setting(kFeature, "memory");
    if (memory.empty()) memory = "balanced";
    if (system_.Created() && memory != memory_) {
        if (auto r = cgroup::SetMemory(memory); !r.ok()) LOG(ERROR) << "memory: " << r.error();
        memory_ = memory;
    }
    const bool swap = SettingIsOn(kFeature, "swap");
    if (system_.Created() && swap && !swap::Active()) {
        if (auto r = swap::On(); !r.ok()) LOG(ERROR) << "swap: " << r.error();
    } else if (!swap && swap::Size() > 0) {
        if (auto r = swap::Remove(); !r.ok()) LOG(ERROR) << "swap: " << r.error();
    }
    if (swap != swap_) {
        swap_ = swap;
        Changed();
    }
    const bool root_sessions = SettingIsOn(kFeature, "root_sessions");
    if (!root_sessions_known_ || root_sessions != root_sessions_) {
        if (root_sessions_known_ && !root_sessions) {
            sessions_.EndKinds({ILinux::KIND_ARCH_ROOT, ILinux::KIND_ANDROID_ROOT});
        }
        SetKernelSuRoot(root_sessions);
        root_sessions_ = root_sessions;
        root_sessions_known_ = true;
        LOG(INFO) << "root sessions " << (root_sessions ? "allowed" : "not allowed");
    }
    remote_.Recheck();
    bridge_.Recheck();
}

void Service::StartLinux() {
    SetState(ILinux::STATE_STARTING);
    if (auto root = files::OpenRoot(kRoot); root.ok()) {
        // Arch Linux keeps these in memory: they start empty.
        for (const char* dir : {"/run", "/tmp", "/dev/shm"}) {
            auto fd = files::OpenIn(root->get(), dir, O_RDONLY | O_DIRECTORY);
            if (!fd.ok()) continue;
            if (auto emptied = files::EmptyDir(fd->get()); !emptied.ok()) {
                LOG(WARNING) << "emptying " << dir << ": " << emptied.error();
            }
        }
    }
    cgroup::KillAll();
    std::string memory = Setting(kFeature, "memory");
    if (memory.empty()) memory = "balanced";
    if (auto r = cgroup::SetMemory(memory); !r.ok()) LOG(ERROR) << "memory: " << r.error();
    memory_ = memory;
    if (auto created = system_.Create(SettingIsOn(kFeature, "lend_usb")); !created.ok()) {
        LOG(ERROR) << "Linux cannot start: " << created.error();
        held_ = true;
        SetState(ILinux::STATE_FAILED, created.error().message());
        return;
    }
    if (SettingIsOn(kFeature, "swap")) {
        if (auto r = swap::On(); !r.ok()) LOG(ERROR) << "swap: " << r.error();
    }
    LOG(INFO) << "Linux started";
    bool setup_done;
    {
        std::lock_guard lock(mutex_);
        setup_done = persisted_.setup_done;
    }
    linux_ready_ = false;
    if (!setup_done) {
        StartJob(ILinux::ACTION_SETUP);
        return;
    }
    std::string user;
    {
        std::lock_guard lock(mutex_);
        user = persisted_.user;
    }
    linux_ready_ = true;
    display_.SetLinux(true);
    services_.LinuxStarted();
    remote_.SetLinux(true, user);
    bridge_.SetLinux(true, user);
    SetState(ILinux::STATE_RUNNING);
}

void Service::StopLinux() {
    LOG(INFO) << "Linux stops";
    if (job_.pid > 0) {
        kill(-job_.pid, SIGKILL);
        Wait(job_);
        job_output_.reset();
        job_log_.reset();
        std::lock_guard lock(mutex_);
        action_running_ = false;
        persisted_.action = job_kind_;
        persisted_.action_ok = false;
        persisted_.action_message = "Linux stopped";
        persisted_.action_time = time(nullptr);
        SaveState();
    }
    linux_ready_ = false;
    display_.SetLinux(false);
    remote_.SetLinux(false, "");
    bridge_.SetLinux(false, "");
    services_.StopAll();
    sessions_.KillAll();
    cgroup::KillAll();
    system_.Drop();
    if (auto off = swap::Off(); !off.ok()) LOG(ERROR) << "swap: " << off.error();
    LOG(INFO) << "Linux stopped";
}

void Service::StartJob(const std::string& kind) {
    const bool setup = kind == ILinux::ACTION_SETUP;
    std::string user;
    {
        std::lock_guard lock(mutex_);
        user = persisted_.user;
        action_running_ = true;
        persisted_.action = kind;
        persisted_.action_ok = false;
        persisted_.action_message.clear();
        persisted_.action_time = time(nullptr);
        SaveState();
    }
    job_kind_ = kind;
    if (setup) SetState(ILinux::STATE_SETUP);
    Changed();
    job_last_line_.clear();
    job_partial_.clear();
    job_log_.reset(TEMP_FAILURE_RETRY(open(setup ? kSetupLog : kDesktopLog,
                                           O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC,
                                           0600)));
    int out[2];
    if (pipe2(out, O_CLOEXEC) != 0) {
        PLOG(ERROR) << kind << ": pipe";
        JobEnded(127);
        return;
    }
    unique_fd read_end(out[0]), write_end(out[1]);
    fcntl(read_end.get(), F_SETFL, O_NONBLOCK);
    Spawn spawn;
    spawn.as = Spawn::As::kRoot;
    spawn.argv = {kShell, setup ? kLinuxSetup : kLinuxDesktopInstall, user,
                  std::to_string(aid::kLinux)};
    spawn.cwd = "/";
    spawn.out = write_end.get();
    spawn.err = write_end.get();
    auto p = system_.Start(spawn);
    if (!p.ok()) {
        LOG(ERROR) << kind << " did not start: " << p.error();
        job_last_line_ = p.error().message();
        JobEnded(127);
        return;
    }
    job_ = std::move(*p);
    job_output_ = std::move(read_end);
    LOG(INFO) << (setup ? "initial setup" : "the desktop's install") << " runs for " << user;
}

void Service::JobEnded(int status) {
    job_output_.reset();
    job_log_.reset();
    const bool ok = status == 0;
    const bool setup = job_kind_ == ILinux::ACTION_SETUP;
    std::string user;
    {
        std::lock_guard lock(mutex_);
        action_running_ = false;
        persisted_.action = job_kind_;
        persisted_.action_ok = ok;
        persisted_.action_message = ok ? "" : job_last_line_;
        persisted_.action_time = time(nullptr);
        if (ok && setup) persisted_.setup_done = true;
        SaveState();
        user = persisted_.user;
    }
    if (ok) {
        LOG(INFO) << job_kind_ << " done";
    } else {
        LOG(ERROR) << job_kind_ << " ended with " << status << ": " << job_last_line_;
    }
    if (!setup) {
        // The desktop starts again with what was installed.
        if (ok && system_.Created()) {
            if (auto r = services_.Control(kDesktopService, ILinux::SERVICE_RESTART); !r.ok()) {
                LOG(ERROR) << "the desktop's service: " << r.error();
            }
        }
        Changed();
        return;
    }
    if (system_.Created() && !linux_ready_) {
        linux_ready_ = true;
        display_.SetLinux(true);
        services_.LinuxStarted();
        remote_.SetLinux(true, user);
        bridge_.SetLinux(true, user);
    } else if (system_.Created()) {
        // A setup run again: the user may be new to Remote access and the lent parts.
        remote_.SetLinux(true, user);
        bridge_.SetLinux(true, user);
    }
    SetState(system_.Created() ? ILinux::STATE_RUNNING : ILinux::STATE_STOPPED);
}

void Service::Replace() {
    std::string user;
    int64_t installed_at;
    {
        std::lock_guard lock(mutex_);
        user = replace_user_;
        installed_at = persisted_.installed_at;
    }
    std::string failure;
    if (Exists(kRoot)) {
        std::string kept = std::string(kSystems) + "/" + KeptName(installed_at);
        for (int n = 2; Exists(kept.c_str()); ++n) {
            kept = std::string(kSystems) + "/" + KeptName(installed_at) + "-" + std::to_string(n);
        }
        if (rename(kRoot, kept.c_str()) != 0) {
            failure = std::string("keeping the installed system: ") + strerror(errno);
        } else {
            LOG(INFO) << "the installed system is kept as " << kept;
        }
    }
    if (failure.empty() && rename(kStaging, kRoot) != 0) {
        failure = std::string("putting the new system in place: ") + strerror(errno);
    }
    {
        std::lock_guard lock(mutex_);
        replace_pending_ = false;
        action_running_ = false;
        persisted_.action = ILinux::ACTION_INSTALL;
        persisted_.action_time = time(nullptr);
        persisted_.action_ok = failure.empty();
        persisted_.action_message = failure;
        if (failure.empty()) {
            persisted_.installed_at = time(nullptr);
            persisted_.user = user;
            persisted_.setup_done = false;
        }
        SaveState();
    }
    held_ = false;
    if (failure.empty()) {
        LOG(INFO) << "installed a new system for " << user;
    } else {
        LOG(ERROR) << "install: " << failure;
    }
    Changed();
}

void Service::InstallThread(unique_fd archive, std::string user) {
    std::string failure;
    unique_fd systems(open(kSystems, O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (systems < 0) {
        failure = std::string(kSystems) + ": " + strerror(errno);
    } else if (auto removed = files::RemoveTree(systems.get(), "root.new"); !removed.ok()) {
        failure = removed.error().message();
    } else if (mkdirat(systems.get(), "root.new", 0755) != 0) {
        failure = std::string("root.new: ") + strerror(errno);
    } else {
        unique_fd staging(openat(systems.get(), "root.new", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        if (staging < 0) {
            failure = std::string("root.new: ") + strerror(errno);
        } else if (auto unpacked = archive::Unpack(archive.get(), staging.get(), &action_done_);
                   !unpacked.ok()) {
            failure = unpacked.error().message();
        } else if (auto checked = archive::Check(staging.get()); !checked.ok()) {
            failure = checked.error().message();
        }
    }
    if (!failure.empty()) {
        LOG(ERROR) << "install: " << failure;
        if (systems >= 0) files::RemoveTree(systems.get(), "root.new");
        {
            std::lock_guard lock(mutex_);
            action_running_ = false;
            persisted_.action = ILinux::ACTION_INSTALL;
            persisted_.action_ok = false;
            persisted_.action_message = failure;
            persisted_.action_time = time(nullptr);
            SaveState();
        }
        Changed();
        return;
    }
    LOG(INFO) << "install: the archive is checked and unpacked";
    {
        std::lock_guard lock(mutex_);
        replace_pending_ = true;
        replace_user_ = user;
    }
    Wake();
}

void Service::SetKernelSuRoot(bool allowed) {
    {
        std::lock_guard lock(root_mutex_);
        root_pending_ = allowed;
    }
    root_cv_.notify_one();
}

void Service::KernelSuRootLoop() {
    using aidl::org::lujarch::root::IRoot;
    for (;;) {
        bool allowed;
        {
            std::unique_lock lock(root_mutex_);
            root_cv_.wait(lock, [this] { return root_pending_.has_value(); });
            allowed = *root_pending_;
            root_pending_.reset();
        }
        // lujarch_root starts with the phone like this daemon.
        ndk::SpAIBinder binder(AServiceManager_waitForService("lujarch_root"));
        auto root = IRoot::fromBinder(binder);
        if (!root) {
            LOG(ERROR) << "lujarch_root is not there: root sessions in Android need it";
            continue;
        }
        if (auto status = root->setLinuxRootSessions(allowed); !status.isOk()) {
            LOG(WARNING) << "root sessions in Android: lujarch_root: " << status.getDescription();
        }
    }
}

void Service::Changed() {
    std::vector<std::shared_ptr<ILinuxListener>> targets;
    {
        std::lock_guard lock(listeners_mutex_);
        for (const Listener& l : listeners_) targets.push_back(l.listener);
    }
    for (auto& l : targets) l->onChanged();
}

void Service::Exited(const std::string& id, int status) {
    std::vector<std::shared_ptr<ILinuxListener>> targets;
    {
        std::lock_guard lock(listeners_mutex_);
        for (const Listener& l : listeners_) targets.push_back(l.listener);
    }
    for (auto& l : targets) l->onSessionExited(id, status);
}

void Service::ForTerminal(const std::function<void(ILinuxListener&)>& call) {
    std::vector<std::shared_ptr<ILinuxListener>> targets;
    {
        std::lock_guard lock(listeners_mutex_);
        for (const Listener& l : listeners_) {
            if (l.terminal) targets.push_back(l.listener);
        }
    }
    for (auto& l : targets) call(*l);
}

void Service::PruneListeners() {
    std::lock_guard lock(listeners_mutex_);
    std::erase_if(listeners_, [](const Listener& l) {
        return !AIBinder_isAlive(l.listener->asBinder().get());
    });
}

// ---- ILinux ----

ndk::ScopedAStatus Service::getStatus(Status* out) {
    CHECK_CALLER(Caller::kEither);
    {
        std::lock_guard lock(mutex_);
        out->state = state_.empty() ? std::string(ILinux::STATE_STARTING) : state_;
        if (!reason_.empty()) out->reason = reason_;
        out->installed = Exists(kRoot);
        out->installedAt = persisted_.installed_at;
        if (!persisted_.user.empty()) out->user = persisted_.user;
        out->setupDone = persisted_.setup_done;
        if (!persisted_.action.empty()) out->action = persisted_.action;
        out->actionRunning = action_running_;
        out->actionOk = persisted_.action_ok;
        if (!persisted_.action_message.empty()) out->actionMessage = persisted_.action_message;
        out->actionTime = persisted_.action_time;
        out->actionDone = action_done_;
        out->actionTotal = action_total_;
    }
    out->sessions = sessions_.List();
    if (auto root = files::OpenRoot(kRoot); root.ok()) {
        out->desktopInstalled =
                files::ExistsIn(root->get(), std::string(kServicesDir) + "/" + kDesktopService + "/run");
    }
    const bool running = system_.Created();
    out->memoryUsed = running ? cgroup::MemoryUsed() : 0;
    out->memoryTotal = MemoryTotal();
    std::string memory = Setting(kFeature, "memory");
    out->memoryLimit = memory == "android"  ? out->memoryTotal / 4
                       : memory == "linux"  ? out->memoryTotal / 4 * 3
                       : memory == "unlimited" ? 0
                                               : out->memoryTotal / 2;
    out->swapOn = swap::Active();
    out->swapSize = swap::Size();
    if (std::unique_ptr<DIR, int (*)(DIR*)> d(opendir(kSystems), closedir); d) {
        while (dirent* e = readdir(d.get())) {
            if (auto t = ParseKept(e->d_name); t) out->kept.push_back(*t);
        }
    }
    std::sort(out->kept.begin(), out->kept.end());
    return ndk::ScopedAStatus::ok();
}

void Service::OnListenerDied(void* cookie) {
    static_cast<Service*>(cookie)->PruneListeners();
}

ndk::ScopedAStatus Service::registerListener(const std::shared_ptr<ILinuxListener>& listener) {
    CHECK_CALLER(Caller::kEither);
    if (listener == nullptr) return Fail(ILinux::ERROR_INVALID, "no listener");
    const bool terminal = TerminalSide() && AIBinder_getCallingUid() != 0;
    std::lock_guard lock(listeners_mutex_);
    for (const Listener& l : listeners_) {
        if (l.listener->asBinder() == listener->asBinder()) return ndk::ScopedAStatus::ok();
    }
    AIBinder_linkToDeath(listener->asBinder().get(), death_.get(), this);
    listeners_.push_back({listener, static_cast<uid_t>(AIBinder_getCallingUid()), terminal});
    if (terminal) {
        // The streams programs read already; the bridge's thread tells of later ones.
        std::thread([this, listener] {
            for (const std::string& stream : bridge_.Wanted()) listener->onStream(stream, true);
        }).detach();
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::unregisterListener(const std::shared_ptr<ILinuxListener>& listener) {
    CHECK_CALLER(Caller::kEither);
    std::lock_guard lock(listeners_mutex_);
    std::erase_if(listeners_, [&](const Listener& l) {
        return listener != nullptr && l.listener->asBinder() == listener->asBinder();
    });
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::createSession(const std::string& kind, const std::string& name,
                                          int32_t columns, int32_t rows, std::string* id) {
    CHECK_CALLER(Caller::kTerminal);
    Spawn spawn;
    if (kind == ILinux::KIND_ARCH) {
        spawn.as = Spawn::As::kUser;
    } else if (kind == ILinux::KIND_ARCH_ROOT) {
        spawn.as = Spawn::As::kRoot;
    } else if (kind == ILinux::KIND_ANDROID_ROOT) {
        spawn.as = Spawn::As::kAndroidRoot;
    } else {
        return Fail(ILinux::ERROR_INVALID, "no kind " + kind);
    }
    if (!system_.Created()) return Fail(ILinux::ERROR_NOT_RUNNING, "Linux does not run");
    if (kind != ILinux::KIND_ARCH && !SettingIsOn(kFeature, "root_sessions")) {
        return Fail(ILinux::ERROR_ROOT_SESSIONS_OFF, "Root sessions is off");
    }
    if (sessions_.Count() >= static_cast<size_t>(ILinux::MAX_SESSIONS)) {
        return Fail(ILinux::ERROR_TOO_MANY_SESSIONS, "the sessions are at their maximum");
    }
    auto created = sessions_.Create(kind, name, columns, rows, std::move(spawn));
    if (!created.ok()) {
        return Fail(ILinux::ERROR_FAILED, created.error().message(), android::base::ERROR);
    }
    *id = *created;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::attachSession(const std::string& id, int32_t columns, int32_t rows,
                                          ndk::ScopedFileDescriptor* stream) {
    CHECK_CALLER(Caller::kTerminal);
    auto fd = sessions_.Attach(id, columns, rows);
    if (!fd.ok()) return Fail(ILinux::ERROR_NO_SESSION, fd.error().message());
    *stream = ndk::ScopedFileDescriptor(fd->release());
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::resizeSession(const std::string& id, int32_t columns, int32_t rows) {
    CHECK_CALLER(Caller::kTerminal);
    if (auto r = sessions_.Resize(id, columns, rows); !r.ok()) {
        return Fail(ILinux::ERROR_NO_SESSION, r.error().message());
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::renameSession(const std::string& id, const std::string& name) {
    CHECK_CALLER(Caller::kTerminal);
    if (auto r = sessions_.Rename(id, name); !r.ok()) {
        return Fail(ILinux::ERROR_NO_SESSION, r.error().message());
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setSessionAwake(const std::string& id, bool awake) {
    CHECK_CALLER(Caller::kTerminal);
    if (auto r = sessions_.SetAwake(id, awake); !r.ok()) {
        return Fail(ILinux::ERROR_NO_SESSION, r.error().message());
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::endSession(const std::string& id) {
    CHECK_CALLER(Caller::kTerminal);
    if (auto r = sessions_.End(id); !r.ok()) {
        return Fail(ILinux::ERROR_NO_SESSION, r.error().message());
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::install(const ndk::ScopedFileDescriptor& archive,
                                    const std::string& user) {
    CHECK_CALLER(Caller::kSettings);
    if (!IsUserName(user)) return Fail(ILinux::ERROR_INVALID, "not a user name");
    unique_fd fd(fcntl(archive.get(), F_DUPFD_CLOEXEC, 0));
    if (fd < 0) return Fail(ILinux::ERROR_INVALID, "no archive");
    struct stat st;
    const int64_t size = fstat(fd.get(), &st) == 0 && S_ISREG(st.st_mode) ? st.st_size : 0;
    {
        std::lock_guard lock(mutex_);
        if (action_running_ || replace_pending_) {
            return Fail(ILinux::ERROR_BUSY, "an install, the initial setup or the desktop's install runs");
        }
        action_running_ = true;
        action_done_ = 0;
        action_total_ = size;
        persisted_.action = ILinux::ACTION_INSTALL;
        persisted_.action_ok = false;
        persisted_.action_message.clear();
        persisted_.action_time = time(nullptr);
        SaveState();
    }
    LOG(INFO) << "install for " << user << " from an archive of " << size << " bytes";
    std::thread(&Service::InstallThread, this, std::move(fd), user).detach();
    Changed();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::runSetup() {
    CHECK_CALLER(Caller::kSettings);
    if (!system_.Created()) return Fail(ILinux::ERROR_NOT_RUNNING, "Linux does not run");
    {
        std::lock_guard lock(mutex_);
        if (action_running_ || replace_pending_) {
            return Fail(ILinux::ERROR_BUSY, "an install, the initial setup or the desktop's install runs");
        }
        setup_requested_ = true;
    }
    Wake();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::installDesktop() {
    CHECK_CALLER(Caller::kSettings);
    if (!system_.Created()) return Fail(ILinux::ERROR_NOT_RUNNING, "Linux does not run");
    {
        std::lock_guard lock(mutex_);
        if (action_running_ || replace_pending_) {
            return Fail(ILinux::ERROR_BUSY, "an install, the initial setup or the desktop's install runs");
        }
        action_running_ = true;
        desktop_requested_ = true;
    }
    Wake();
    Changed();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::listServices(std::vector<LinuxService>* out) {
    CHECK_CALLER(Caller::kSettings);
    *out = services_.List();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::controlService(const std::string& name, const std::string& action) {
    CHECK_CALLER(Caller::kSettings);
    if (!system_.Created()) return Fail(ILinux::ERROR_NOT_RUNNING, "Linux does not run");
    if (auto r = services_.Control(name, action); !r.ok()) {
        const int code = r.error().code() == ENOENT ? ILinux::ERROR_NO_SERVICE
                         : r.error().code() == EINVAL ? ILinux::ERROR_INVALID
                         : r.error().code() == ESRCH  ? ILinux::ERROR_NOT_RUNNING
                                                      : ILinux::ERROR_FAILED;
        return Fail(code, r.error().message());
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setServiceStartsWithLinux(const std::string& name, bool starts) {
    CHECK_CALLER(Caller::kSettings);
    if (auto r = services_.SetStartsWithLinux(name, starts); !r.ok()) {
        const int code = r.error().code() == ENOENT ? ILinux::ERROR_NO_SERVICE : ILinux::ERROR_FAILED;
        return Fail(code, r.error().message());
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::readServiceLog(const std::string& name, std::string* log) {
    CHECK_CALLER(Caller::kSettings);
    auto r = services_.Log(name);
    if (!r.ok()) return Fail(ILinux::ERROR_FAILED, r.error().message());
    *log = *r;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::listServiceFiles(const std::string& name,
                                             std::vector<std::string>* out) {
    CHECK_CALLER(Caller::kSettings);
    auto r = services_.Files(name);
    if (!r.ok()) {
        return Fail(r.error().code() == ENOENT ? ILinux::ERROR_NO_SERVICE : ILinux::ERROR_FAILED,
                    r.error().message());
    }
    *out = *r;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::readServiceFile(const std::string& name, const std::string& file,
                                            std::string* out) {
    CHECK_CALLER(Caller::kSettings);
    auto r = services_.File(name, file);
    if (!r.ok()) return Fail(ILinux::ERROR_FAILED, r.error().message());
    *out = *r;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::getRemote(RemoteStatus* out) {
    // Lujarch Settings' page, and the Terminal app's tile.
    CHECK_CALLER(Caller::kEither);
    *out = remote_.Status();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setRemote(const RemoteConfig& config) {
    CHECK_CALLER(Caller::kSettings);
    if (auto r = remote_.Set(config); !r.ok()) {
        return Fail(r.error().code() == EINVAL ? ILinux::ERROR_INVALID : ILinux::ERROR_FAILED,
                    r.error().message());
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::getColors(std::string* colors) {
    CHECK_CALLER(Caller::kEither);
    auto text = files::ReadFile(kColorsFile, kMaxColors);
    *colors = text.ok() ? *text : "";
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setColors(const std::string& colors) {
    CHECK_CALLER(Caller::kSettings);
    if (colors.size() > kMaxColors) return Fail(ILinux::ERROR_INVALID, "more than 64 KiB of colors");
    if (colors.empty()) {
        if (unlink(kColorsFile) != 0 && errno != ENOENT) {
            return Fail(ILinux::ERROR_FAILED, std::string("colors: ") + strerror(errno));
        }
    } else if (auto w = files::WriteFile(kColorsFile, text::SanitizeUtf8(colors), 0600); !w.ok()) {
        return Fail(ILinux::ERROR_FAILED, w.error().message(), android::base::ERROR);
    }
    Changed();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::attachDisplay(ndk::ScopedFileDescriptor* connection) {
    CHECK_CALLER(Caller::kTerminal);
    if (!system_.Created()) return Fail(ILinux::ERROR_NOT_RUNNING, "Linux does not run");
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return Fail(ILinux::ERROR_FAILED, root.error().message());
    if (!files::ExistsIn(root->get(), std::string(kServicesDir) + "/" + kDesktopService + "/run")) {
        return Fail(ILinux::ERROR_NO_DESKTOP, "Linux has no service desktop");
    }
    auto fd = display_.Attach();
    if (!fd.ok()) {
        return Fail(fd.error().code() == ESRCH ? ILinux::ERROR_NOT_RUNNING : ILinux::ERROR_FAILED,
                    fd.error().message());
    }
    *connection = ndk::ScopedFileDescriptor(fd->release());
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::openSound(ndk::ScopedFileDescriptor* sound) {
    CHECK_CALLER(Caller::kTerminal);
    auto fd = display_.OpenSound();
    if (!fd.ok()) {
        return Fail(fd.error().code() == ESRCH ? ILinux::ERROR_NOT_RUNNING : ILinux::ERROR_NO_SOUND,
                    fd.error().message());
    }
    *sound = ndk::ScopedFileDescriptor(fd->release());
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setClipboard(const std::string& text) {
    CHECK_CALLER(Caller::kTerminal);
    if (!bridge_.SetClipboard(text)) {
        return Fail(ILinux::ERROR_REFUSED, "the clipboard is not lent");
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setNotifications(const std::vector<LinuxNotification>& list) {
    CHECK_CALLER(Caller::kTerminal);
    if (!SettingIsOn(kFeature, "lend_notifications")) {
        return Fail(ILinux::ERROR_REFUSED, "Android notifications are not lent");
    }
    std::vector<LinuxNotification> clean;
    for (const LinuxNotification& n : list) {
        if (clean.size() >= kMaxNotifications) break;
        LinuxNotification c = n;
        c.title = c.title.substr(0, kMaxNotificationText);
        c.text = c.text.substr(0, kMaxNotificationText);
        c.app = c.app.substr(0, kMaxNotificationText);
        clean.push_back(std::move(c));
    }
    bridge_.SetNotifications(clean);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::answerAuth(int32_t id, bool allowed) {
    CHECK_CALLER(Caller::kTerminal);
    bridge_.AnswerAuth(id, allowed);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setNfcCard(const std::optional<std::string>& card) {
    CHECK_CALLER(Caller::kTerminal);
    bridge_.SetNfcCard(card);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::answerNfc(int32_t id, const std::vector<uint8_t>& response,
                                      const std::optional<std::string>& error) {
    CHECK_CALLER(Caller::kTerminal);
    bridge_.AnswerNfc(id, response, error);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setAndroidState(const AndroidState& state) {
    CHECK_CALLER(Caller::kTerminal);
    if (!bridge_.SetAndroidState(state)) {
        return Fail(ILinux::ERROR_REFUSED, "Android's quick settings are not lent");
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::openStream(const std::string& stream, ndk::ScopedFileDescriptor* fd) {
    CHECK_CALLER(Caller::kTerminal);
    auto out = bridge_.OpenStream(stream);
    if (!out.ok()) {
        return Fail(out.error().code() == EINVAL ? ILinux::ERROR_INVALID : ILinux::ERROR_NOT_WANTED,
                    out.error().message());
    }
    *fd = ndk::ScopedFileDescriptor(out->release());
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setLocation(const LinuxLocation& location) {
    CHECK_CALLER(Caller::kTerminal);
    bridge_.SetLocation(location);
    return ndk::ScopedAStatus::ok();
}

}  // namespace lujarch::linux
