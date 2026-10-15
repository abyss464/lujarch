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

// The sync engine's life: it runs while Use sync is on (SYN-1) and its run conditions are met
// (SYN-2), and stays stopped after repeated failures (SYN-3).
#pragma once

#include <aidl/org/lujarch/sync/Status.h>
#include <android-base/result.h>
#include <android-base/unique_fd.h>

#include <atomic>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <string>

namespace lujarch::sync {

inline constexpr char kFeature[] = "sync";
inline constexpr char kEngineBinary[] = "/system_ext/bin/lujarch_sync_engine";
inline constexpr char kStateProperty[] = "lujarch.sync.state";
inline constexpr char kEngineLog[] = "/data/lujarch/sync/engine.log";

class Supervisor {
  public:
    // Starts the threads that run the engine and follow the switches and the run conditions.
    void Start();
    aidl::org::lujarch::sync::Status Status();
    // Runs install while the engine is stopped and starts it again when sync is on and may run;
    // returns whether it runs again.
    android::base::Result<bool> WithEngineStopped(
            const std::function<android::base::Result<void>()>& install);
    void Wake();

  private:
    enum class End { kStopped, kExited };

    void Loop();
    void WatchProperty(const std::string& name);
    void WaitEvent(int timeout_ms);
    void DrainEvents();
    bool StartEngine();
    End WaitEngine();
    void StopEngine();
    void SetState(const std::string& state, const std::string& waiting = "");
    // Why the engine may not run now, as a state; "" when it may.
    std::string Blocked();

    std::mutex mutex_;
    std::condition_variable engine_cv_;
    aidl::org::lujarch::sync::Status status_;
    android::base::unique_fd wake_;
    // The engine's pid; 0 while it starts, -1 while none runs.
    pid_t engine_ = -1;
    android::base::unique_fd engine_fd_;
    bool held_ = false;
    std::atomic<bool> reset_ = false;
};

Supervisor& TheSupervisor();

}  // namespace lujarch::sync
