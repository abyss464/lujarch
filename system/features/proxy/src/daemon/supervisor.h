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

// The core's life: it runs while Use proxy is on (PRX-1), a failure leaves the phone online
// (PRX-3), its path is repaired after a network change (PRX-4), and IPv6 is blocked while it runs
// (PRX-5).
#pragma once

#include <aidl/org/lujarch/proxy/IProxyListener.h>
#include <aidl/org/lujarch/proxy/Status.h>
#include <android-base/unique_fd.h>
#include <sys/types.h>

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace lujarch::proxy {

inline constexpr char kFeature[] = "proxy";
inline constexpr char kDir[] = "/data/lujarch/proxy";
inline constexpr char kConfig[] = "/data/lujarch/proxy/config.yaml";
inline constexpr char kLastGood[] = "/data/lujarch/proxy/config.last-good.yaml";
inline constexpr char kCoreLog[] = "/data/lujarch/proxy/core.log";
// The manager asks for a restart with this file in kDir.
inline constexpr char kRestartRequest[] = "restart.request";
inline constexpr char kCoreBinary[] = "/system_ext/bin/lujarch_proxy_core";
inline constexpr char kStateProperty[] = "lujarch.proxy.state";

class Supervisor {
  public:
    using Listener = aidl::org::lujarch::proxy::IProxyListener;

    // Starts the threads that run the core and follow the switches and the network stack.
    void Start();
    aidl::org::lujarch::proxy::Status Status();
    void Watch(const std::shared_ptr<Listener>& listener);
    // A restart asked for, not counted as a failure; it also ends a stop after repeated failures.
    void RequestRestart();

  private:
    enum class End {
        kSwitchedOff,
        kRequested,
        kRepair,
        kUnhealthy,
        kInterfaceLost,
        kExited,
        kNetworkStack
    };

    struct Run;
    void Loop();
    void WatchProperty(const std::string& name);
    void Wake();
    // Waits for a wake-up or a file event, at most timeout_ms (-1: no limit).
    void WaitEvent(int timeout_ms);
    void DrainEvents();
    bool Valid(const std::string& config, std::string* error);
    std::shared_ptr<Run> StartCore(const std::string& config, bool repair);
    End WaitCore(Run* run);
    void StopCore(Run* run);
    void ReadCoreOutput(Run* run, android::base::unique_fd output);
    void Watchdog(Run* run);
    void SetIpv6Block(bool on);
    void SetState(const std::string& state, const std::string& check_error = "");
    void SetTun(bool tun);
    // The configuration a run started with works: it becomes the last working one.
    void KeepAsLastGood(Run* run);
    void NoteRestart(const std::string& reason);
    bool ShouldStop(Run* run, std::chrono::seconds wait);

    std::mutex mutex_;
    aidl::org::lujarch::proxy::Status status_;
    std::vector<std::shared_ptr<Listener>> listeners_;
    android::base::unique_fd wake_;
    android::base::unique_fd inotify_;
    std::atomic<bool> restart_requested_ = false;
    std::atomic<bool> netd_restarted_ = false;
    bool ipv6_blocked_ = false;
};

Supervisor& TheSupervisor();

}  // namespace lujarch::proxy
