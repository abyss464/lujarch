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

#include "remote.h"

#include <aidl/android/hardware/health/IHealth.h>
#include <aidl/org/lujarch/linux/ILinux.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <android/binder_manager.h>
#include <cutils/uevent.h>
#include <fcntl.h>
#include <lujarch/aid.h>
#include <lujarch/daemon.h>
#include <lujarch/text.h>
#include <poll.h>
#include <private/android_filesystem_config.h>
#include <signal.h>
#include <sys/eventfd.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cstring>
#include <map>
#include <thread>

#include "files.h"
#include "paths.h"

namespace lujarch::linux {

namespace {

using aidl::android::hardware::health::HealthInfo;
using aidl::android::hardware::health::IHealth;
using aidl::org::lujarch::linux::ILinux;
using aidl::org::lujarch::linux::RemoteConfig;
using aidl::org::lujarch::linux::RemoteStatus;
using android::base::Error;

constexpr auto kSettle = std::chrono::seconds(10);
constexpr auto kStopTimeout = std::chrono::seconds(5);
constexpr auto kMaxBackoff = std::chrono::seconds(60);
// A program that ends within this time after its start counts as failing.
constexpr auto kQuick = std::chrono::seconds(30);
constexpr int kFailed = 5;
constexpr size_t kMaxKeys = 16;
constexpr size_t kMaxLine = 1024;

std::string LinuxPath(const char* name) {
    return std::string(kRemoteDir) + "/" + name;
}

bool IsHost(const std::string& s) {
    if (s.empty() || s.size() > 253) return false;
    for (char c : s) {
        if (!(isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '-' || c == ':' ||
              c == '[' || c == ']')) {
            return false;
        }
    }
    return true;
}

bool IsSecret(const std::string& s) {
    if (s.empty() || s.size() > 256) return false;
    for (char c : s) {
        if (c < 0x21 || c > 0x7e) return false;
    }
    return true;
}

bool IsEntryName(const std::string& s) {
    if (s.empty() || s.size() > 64) return false;
    for (char c : s) {
        if (!(isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '_' || c == '-')) {
            return false;
        }
    }
    return true;
}

// One OpenSSH public key line: type, base64 body, an optional comment.
bool IsPublicKey(const std::string& s) {
    if (s.empty() || s.size() > 8192) return false;
    for (char c : s) {
        if (static_cast<unsigned char>(c) < 0x20 || c == 0x7f) return false;
    }
    std::vector<std::string> f = android::base::Tokenize(s, " ");
    if (f.size() < 2) return false;
    static const char* kTypes[] = {"ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256",
                                   "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
                                   "sk-ssh-ed25519@openssh.com",
                                   "sk-ecdsa-sha2-nistp256@openssh.com"};
    if (std::none_of(std::begin(kTypes), std::end(kTypes), [&](const char* t) { return f[0] == t; })) {
        return false;
    }
    return std::all_of(f[1].begin(), f[1].end(), [](char c) {
        return isalnum(static_cast<unsigned char>(c)) || c == '+' || c == '/' || c == '=';
    });
}

std::string Quoted(const std::string& value) {
    std::string out = "\"";
    for (char c : value) {
        if (c == '"' || c == '\\') out.push_back('\\');
        out.push_back(c);
    }
    return out + "\"";
}

}  // namespace

Remote::Remote(System& system, std::function<void()> changed)
    : system_(system), changed_(std::move(changed)) {
    auto values = files::ReadKeyValues(kRemoteFile);
    config_.server = values["server"];
    int port;
    if (android::base::ParseInt(values["port"], &port, 1, 65535)) config_.port = port;
    if (!values["name"].empty()) config_.name = values["name"];
    config_.token = values["token"];
    config_.secret = values["secret"];
    if (auto keys = files::ReadFile(kRemoteKeysFile, 1 << 20); keys.ok()) {
        for (const std::string& line : android::base::Split(*keys, "\n")) {
            if (IsPublicKey(line)) config_.keys.push_back(line);
        }
    }
}

void Remote::Start() {
    wake_.reset(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    uevents_.reset(uevent_open_socket(64 * 1024, true));
    if (uevents_ < 0) {
        PLOG(WARNING) << "remote: no power-supply events; the charger is read every minute";
    } else {
        fcntl(uevents_.get(), F_SETFL, O_NONBLOCK);
    }
    std::thread(&Remote::Loop, this).detach();
}

void Remote::Wake() {
    uint64_t one = 1;
    TEMP_FAILURE_RETRY(write(wake_.get(), &one, sizeof(one)));
}

std::string Remote::Mode() {
    const std::string mode = Setting(kFeature, "remote");
    if (mode == ILinux::REMOTE_CHARGING || mode == ILinux::REMOTE_ALWAYS) return mode;
    return ILinux::REMOTE_OFF;
}

void Remote::ReadCharger() {
    static std::shared_ptr<IHealth> health;
    if (!health) {
        const std::string name = std::string(IHealth::descriptor) + "/default";
        ndk::SpAIBinder binder(AServiceManager_checkService(name.c_str()));
        if (binder.get() != nullptr) health = IHealth::fromBinder(binder);
    }
    std::optional<bool> charging;
    if (health) {
        HealthInfo info;
        if (health->getHealthInfo(&info).isOk()) {
            charging = info.chargerAcOnline || info.chargerUsbOnline ||
                       info.chargerWirelessOnline || info.chargerDockOnline;
        } else {
            health.reset();
        }
    }
    if (charging != charging_) {
        if (charging) {
            LOG(INFO) << "remote: charger " << (*charging ? "online" : "offline");
        } else {
            LOG(WARNING) << "remote: the charger cannot be read; it counts as online";
        }
    }
    charging_ = charging;
}

std::string Remote::Wanted(bool* run) {
    *run = false;
    const std::string mode = Mode();
    if (mode == ILinux::REMOTE_OFF) return ILinux::RELAY_OFF;
    if (!config_.configured()) return ILinux::RELAY_NOT_SET_UP;
    if (!linux_) return ILinux::RELAY_NO_LINUX;
    auto root = files::OpenRoot(kRoot);
    if (!root.ok() || !files::ExistsIn(root->get(), kSshd)) return ILinux::RELAY_NO_OPENSSH;
    if (mode == ILinux::REMOTE_CHARGING && charging_ == false) return ILinux::RELAY_WAITING;
    *run = true;
    return ILinux::RELAY_RUNNING;
}

Result<void> Remote::WriteLinuxFiles() {
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return root.error();
    if (auto made = files::MakeDirsIn(root->get(), kRemoteDir, 0755); !made.ok()) {
        return made.error();
    }
    const std::string config =
            "# Remote access of the Linux development environment, written by Lujarch from its\n"
            "# page in Settings: only the page's public keys, only the owner's user.\n"
            "ListenAddress 127.0.0.1:" + std::to_string(kSshPort) + "\n" +
            "HostKey " + LinuxPath("ssh_host_ed25519_key") + "\n" +
            "AuthorizedKeysFile " + LinuxPath("authorized_keys") + "\n" +
            "AuthenticationMethods publickey\n"
            "PasswordAuthentication no\n"
            "KbdInteractiveAuthentication no\n"
            "PermitRootLogin no\n"
            "AllowUsers " + user_ + "\n" +
            "UsePAM yes\n"
            "PidFile none\n"
            "Subsystem sftp internal-sftp\n";
    if (auto w = files::WriteIn(root->get(), LinuxPath("sshd_config"), config, 0644); !w.ok()) {
        return w.error();
    }
    std::string keys;
    for (const std::string& key : config_.keys) keys += key + "\n";
    if (auto w = files::WriteIn(root->get(), LinuxPath("authorized_keys"), keys, 0644); !w.ok()) {
        return w.error();
    }
    if (!files::ExistsIn(root->get(), LinuxPath("ssh_host_ed25519_key"))) {
        Spawn keygen;
        keygen.as = Spawn::As::kRoot;
        keygen.argv = {kSshKeygen, "-q", "-t", "ed25519", "-N", "", "-C", "lujarch-remote",
                       "-f", LinuxPath("ssh_host_ed25519_key")};
        auto p = system_.Start(keygen);
        if (!p.ok()) return p.error();
        pollfd fd{p->pidfd.get(), POLLIN, 0};
        if (TEMP_FAILURE_RETRY(poll(&fd, 1, 30000)) <= 0) kill(p->pid, SIGKILL);
        const int status = ExitCode(Wait(*p));
        if (status != 0) return Error() << "ssh-keygen ended with " << status;
        LOG(INFO) << "remote: host key made";
    }
    return {};
}

Result<void> Remote::WriteRelayConfig() {
    std::string config = "serverAddr = " + Quoted(config_.server) + "\n" +
                         "serverPort = " + std::to_string(config_.port) + "\n" +
                         "loginFailExit = false\n"
                         "log.to = \"console\"\n"
                         "log.level = \"info\"\n";
    if (!config_.token.empty()) {
        config += "auth.method = \"token\"\nauth.token = " + Quoted(config_.token) + "\n";
    }
    config += "\n[[proxies]]\n"
              "name = " + Quoted(config_.name) + "\n" +
              "type = \"stcp\"\n"
              "secretKey = " + Quoted(config_.secret) + "\n" +
              "localIP = \"127.0.0.1\"\n"
              "localPort = " + std::to_string(kSshPort) + "\n";
    return files::WriteFile(kRelayConfig, config, 0640, 0, aid::kLinuxRelay);
}

bool Remote::StartSshd() {
    if (auto written = WriteLinuxFiles(); !written.ok()) {
        LOG(ERROR) << "remote: files of the SSH server: " << written.error();
        reason_ = written.error().message();
        return false;
    }
    int out[2];
    if (pipe2(out, O_CLOEXEC) != 0) return false;
    unique_fd read_end(out[0]), write_end(out[1]);
    fcntl(read_end.get(), F_SETFL, O_NONBLOCK);
    Spawn spawn;
    spawn.as = Spawn::As::kRoot;
    spawn.argv = {kSshd, "-D", "-e", "-f", LinuxPath("sshd_config")};
    spawn.out = write_end.get();
    spawn.err = write_end.get();
    auto p = system_.Start(spawn);
    if (!p.ok()) {
        LOG(ERROR) << "remote: the SSH server did not start: " << p.error();
        reason_ = p.error().message();
        return false;
    }
    sshd_.process = std::move(*p);
    sshd_.output = std::move(read_end);
    sshd_.started = Clock::now();
    LOG(INFO) << "remote: the SSH server runs, pid " << sshd_.process.pid;
    return true;
}

bool Remote::StartRelay() {
    if (auto written = WriteRelayConfig(); !written.ok()) {
        LOG(ERROR) << "remote: the relay's configuration: " << written.error();
        reason_ = written.error().message();
        return false;
    }
    int out[2];
    if (pipe2(out, O_CLOEXEC) != 0) return false;
    unique_fd read_end(out[0]), write_end(out[1]);
    fcntl(read_end.get(), F_SETFL, O_NONBLOCK);
    auto p = StartAndroid(kRelay, {"lujarch_linux_frpc", "-c", kRelayConfig}, aid::kLinuxRelay,
                          {AID_INET}, write_end.get());
    if (!p.ok()) {
        LOG(ERROR) << "remote: the relay did not start: " << p.error();
        reason_ = p.error().message();
        return false;
    }
    relay_.process = std::move(*p);
    relay_.output = std::move(read_end);
    relay_.started = Clock::now();
    LOG(INFO) << "remote: the relay runs, pid " << relay_.process.pid;
    return true;
}

void Remote::Stop(Program& p) {
    p.restart_at = {};
    p.backoff = std::chrono::seconds(2);
    p.quick_exits = 0;
    if (p.process.pid <= 0) return;
    kill(-p.process.pid, SIGTERM);
    pollfd fd{p.process.pidfd.get(), POLLIN, 0};
    const int ms = static_cast<int>(
            std::chrono::duration_cast<std::chrono::milliseconds>(kStopTimeout).count());
    if (TEMP_FAILURE_RETRY(poll(&fd, 1, ms)) <= 0) kill(-p.process.pid, SIGKILL);
    Wait(p.process);
    p.output.reset();
    p.partial.clear();
    LOG(INFO) << "remote: the " << p.what << " stopped";
}

void Remote::ReadOutput(Program& p) {
    char buf[4096];
    ssize_t n;
    while (p.output >= 0 && (n = read(p.output.get(), buf, sizeof(buf))) > 0) {
        p.partial.append(buf, static_cast<size_t>(n));
    }
    size_t nl;
    while ((nl = p.partial.find('\n')) != std::string::npos || p.partial.size() > kMaxLine) {
        const std::string line =
                text::SanitizeUtf8(p.partial.substr(0, nl == std::string::npos ? kMaxLine : nl));
        p.partial.erase(0, nl == std::string::npos ? kMaxLine : nl + 1);
        LOG(INFO) << "remote: " << p.what << ": " << line;
        // frp marks errors and warnings [E] and [W]; the last one tells why the relay fails.
        if (&p == &relay_ && (line.find("[E]") != std::string::npos ||
                              line.find("[W]") != std::string::npos)) {
            reason_ = line;
        } else if (&p == &relay_ && line.find("start proxy success") != std::string::npos) {
            reason_.clear();
        }
    }
}

void Remote::Apply() {
    bool run;
    std::string state = Wanted(&run);
    if (!run) {
        Stop(sshd_);
        Stop(relay_);
        reason_.clear();
    } else {
        const auto now = Clock::now();
        for (Program* p : {&sshd_, &relay_}) {
            if (p->process.pid > 0 || p->quick_exits >= kFailed) continue;
            if (p->restart_at != Clock::time_point() && now < p->restart_at) continue;
            p->restart_at = {};
            const bool started = p == &sshd_ ? StartSshd() : StartRelay();
            if (!started) {
                p->restart_at = now + p->backoff;
                p->backoff = std::min(p->backoff * 2, kMaxBackoff);
            }
        }
        if (sshd_.quick_exits >= kFailed || relay_.quick_exits >= kFailed) {
            state = ILinux::RELAY_FAILED;
        }
    }
    if (state != state_) {
        LOG(INFO) << "remote: " << state;
        state_ = state;
    }
}

void Remote::Loop() {
    {
        std::lock_guard lock(mutex_);
        ReadCharger();
        Apply();
    }
    changed_();
    for (;;) {
        std::vector<pollfd> fds = {{wake_.get(), POLLIN, 0}};
        if (uevents_ >= 0) fds.push_back({uevents_.get(), POLLIN, 0});
        int timeout = uevents_ >= 0 ? -1 : 60000;
        {
            std::lock_guard lock(mutex_);
            for (Program* p : {&sshd_, &relay_}) {
                if (p->process.pid > 0) {
                    fds.push_back({p->process.pidfd.get(), POLLIN, 0});
                    if (p->output >= 0) fds.push_back({p->output.get(), POLLIN, 0});
                }
            }
            const auto now = Clock::now();
            for (Clock::time_point at : {charger_at_, sshd_.restart_at, relay_.restart_at}) {
                if (at == Clock::time_point()) continue;
                const int left = static_cast<int>(std::max<int64_t>(
                        0, std::chrono::duration_cast<std::chrono::milliseconds>(at - now).count()));
                timeout = timeout < 0 ? left : std::min(timeout, left);
            }
        }
        TEMP_FAILURE_RETRY(poll(fds.data(), fds.size(), timeout));
        std::string before;
        std::string after;
        {
            std::lock_guard lock(mutex_);
            before = state_ + reason_;
            uint64_t count;
            while (read(wake_.get(), &count, sizeof(count)) > 0) {
            }
            if (uevents_ >= 0) {
                char buf[4096];
                ssize_t n;
                while ((n = uevent_kernel_multicast_recv(uevents_.get(), buf, sizeof(buf) - 1)) > 0) {
                    buf[n] = '\0';
                    for (char* p = buf; p < buf + n; p += strlen(p) + 1) {
                        if (strcmp(p, "SUBSYSTEM=power_supply") == 0) {
                            if (charger_at_ == Clock::time_point()) {
                                charger_at_ = Clock::now() + kSettle;
                            }
                            break;
                        }
                    }
                }
            } else {
                ReadCharger();
            }
            for (Program* p : {&sshd_, &relay_}) {
                ReadOutput(*p);
                if (p->process.pid <= 0) continue;
                int status = 0;
                if (waitpid(p->process.pid, &status, WNOHANG) != p->process.pid) continue;
                p->process.pid = -1;
                p->process.pidfd.reset();
                ReadOutput(*p);
                p->output.reset();
                LOG(WARNING) << "remote: the " << p->what << " ended with " << ExitCode(status);
                if (Clock::now() - p->started < kQuick) {
                    ++p->quick_exits;
                } else {
                    p->quick_exits = 0;
                    p->backoff = std::chrono::seconds(2);
                }
                if (p->quick_exits >= kFailed) {
                    reason_ = "the " + p->what + " ended with " + std::to_string(ExitCode(status));
                    LOG(ERROR) << "remote: " << reason_ << " " << kFailed
                               << " times in a row; it stays stopped until Remote access changes";
                }
                p->restart_at = Clock::now() + p->backoff;
                p->backoff = std::min(p->backoff * 2, kMaxBackoff);
            }
            if (charger_at_ != Clock::time_point() && Clock::now() >= charger_at_) {
                charger_at_ = {};
                ReadCharger();
            }
            Apply();
            after = state_ + reason_;
        }
        if (before != after) changed_();
    }
}

void Remote::SetLinux(bool running, const std::string& user) {
    {
        std::lock_guard lock(mutex_);
        linux_ = running;
        user_ = user;
        sshd_.quick_exits = 0;
        relay_.quick_exits = 0;
        if (!running) {
            Stop(sshd_);
            Stop(relay_);
        }
    }
    Wake();
}

void Remote::Recheck() {
    {
        std::lock_guard lock(mutex_);
        sshd_.quick_exits = 0;
        relay_.quick_exits = 0;
        ReadCharger();
    }
    Wake();
}

RemoteStatus Remote::Status() {
    std::lock_guard lock(mutex_);
    RemoteStatus s;
    s.mode = Mode();
    s.state = state_.empty() ? std::string(ILinux::RELAY_OFF) : state_;
    if (state_ == ILinux::RELAY_FAILED || state_ == ILinux::RELAY_RUNNING) {
        if (!reason_.empty()) s.reason = reason_;
    }
    s.charging = charging_.value_or(false);
    s.server = config_.server;
    s.port = config_.port;
    s.name = config_.name;
    s.tokenSet = !config_.token.empty();
    s.secretSet = !config_.secret.empty();
    s.keys = config_.keys;
    return s;
}

Result<void> Remote::Set(const RemoteConfig& in) {
    Config next;
    next.server = android::base::Trim(in.server);
    if (!next.server.empty()) {
        if (!IsHost(next.server)) return Error(EINVAL) << "not a server address";
        if (in.port < 1 || in.port > 65535) return Error(EINVAL) << "not a port";
        next.port = in.port;
        next.name = android::base::Trim(in.name);
        if (!IsEntryName(next.name)) return Error(EINVAL) << "not an entry name";
        {
            std::lock_guard lock(mutex_);
            next.token = in.token ? *in.token : config_.token;
            next.secret = in.secret ? *in.secret : config_.secret;
        }
        if (!next.token.empty() && !IsSecret(next.token)) return Error(EINVAL) << "not a token";
        if (!next.secret.empty() && !IsSecret(next.secret)) return Error(EINVAL) << "not a secret";
        if (in.keys.size() > kMaxKeys) return Error(EINVAL) << "more than " << kMaxKeys << " keys";
        for (const std::string& key : in.keys) {
            const std::string k = android::base::Trim(key);
            if (!IsPublicKey(k)) return Error(EINVAL) << "not an OpenSSH public key";
            next.keys.push_back(k);
        }
    }
    std::map<std::string, std::string> values;
    if (!next.server.empty()) {
        values = {{"server", next.server}, {"port", std::to_string(next.port)},
                  {"name", next.name}, {"token", next.token}, {"secret", next.secret}};
    }
    if (auto w = files::WriteKeyValues(kRemoteFile, values, 0600); !w.ok()) return w.error();
    std::string keys;
    for (const std::string& key : next.keys) keys += key + "\n";
    if (auto w = files::WriteFile(kRemoteKeysFile, keys, 0600); !w.ok()) return w.error();
    {
        std::lock_guard lock(mutex_);
        config_ = std::move(next);
        LOG(INFO) << "remote: connection " << (config_.server.empty() ? "removed" : "set")
                  << ", " << config_.keys.size() << " public keys";
        // The new connection and keys take effect at once.
        Stop(relay_);
        Stop(sshd_);
        reason_.clear();
        Apply();
    }
    Wake();
    changed_();
    return {};
}

}  // namespace lujarch::linux
