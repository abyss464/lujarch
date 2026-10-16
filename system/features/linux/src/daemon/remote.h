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

// Remote access (LNX-9): the owner's computers log in to Linux by SSH through the owner's relay
// server. Two programs carry it while it runs: Linux's OpenSSH server on 127.0.0.1:kSshPort,
// which takes only the public keys of the page and only the owner's user, and frp's client, an
// stcp entry of the owner's relay server to it, as aid::kLinuxRelay in a domain of its own.
// Nothing listens on the phone's networks. When it runs is the mode: always, while a charger is
// online (the Health HAL; a charger state it cannot give counts as charging; a change is acted on
// ten seconds after the last power-supply event), or off. The token and the secret stay in the
// daemon's files and the relay's configuration; they are never logged and never answered.
#pragma once

#include <aidl/org/lujarch/linux/RemoteConfig.h>
#include <aidl/org/lujarch/linux/RemoteStatus.h>
#include <android-base/result.h>
#include <android-base/unique_fd.h>

#include <chrono>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "system.h"

namespace lujarch::linux {

class Remote {
  public:
    Remote(System& system, std::function<void()> changed);

    void Start();

    // Linux runs, with the owner's user, or does not.
    void SetLinux(bool running, const std::string& user);

    // The mode's mirror changed.
    void Recheck();

    aidl::org::lujarch::linux::RemoteStatus Status();
    Result<void> Set(const aidl::org::lujarch::linux::RemoteConfig& config);

  private:
    using Clock = std::chrono::steady_clock;

    struct Config {
        std::string server;
        int port = 7000;
        std::string name = "lujarch-ssh";
        std::string token;
        std::string secret;
        std::vector<std::string> keys;
        bool configured() const { return !server.empty() && !secret.empty(); }
    };

    struct Program {
        std::string what;
        Process process;
        unique_fd output;
        std::string partial;
        Clock::time_point started;
        Clock::time_point restart_at;
        std::chrono::seconds backoff{2};
        int quick_exits = 0;
    };

    void Loop();
    void Wake();
    std::string Mode();
    // What should be, from the mode, the configuration, Linux and the charger.
    std::string Wanted(bool* run);
    void Apply();
    Result<void> WriteLinuxFiles();
    Result<void> WriteRelayConfig();
    bool StartSshd();
    bool StartRelay();
    void Stop(Program& p);
    void ReadOutput(Program& p);
    void ReadCharger();

    System& system_;
    std::function<void()> changed_;
    std::mutex mutex_;
    Config config_;
    bool linux_ = false;
    std::string user_;
    std::optional<bool> charging_;
    Clock::time_point charger_at_;
    std::string state_;
    std::string reason_;
    Program sshd_{"sshd"};
    Program relay_{"relay"};
    unique_fd wake_;
    unique_fd uevents_;
};

}  // namespace lujarch::linux
