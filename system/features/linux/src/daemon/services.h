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

// Linux's services (LNX-8). A service is a directory of the system's /etc/lujarch/services
// named after it, holding the executable `run`, which runs the service in the foreground as
// root; a file `down` beside it keeps it from starting with Linux. The daemon starts the
// services with Linux, starts one again when it ends on its own (after a second, doubling up to
// a minute while it ends within a minute), and stops one with SIGTERM to its process group and
// SIGKILL ten seconds later. What a service writes goes, with the time, to its log
// /var/log/lujarch/<name>.log, which starts over at 1 MiB beside one older file.
#pragma once

#include <aidl/org/lujarch/linux/LinuxService.h>
#include <android-base/result.h>
#include <android-base/unique_fd.h>

#include <chrono>
#include <condition_variable>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "system.h"

namespace lujarch::linux {

class Services {
  public:
    Services(System& system, std::function<void()> changed);

    void Start();

    // Linux started: the services that start with it start.
    void LinuxStarted();

    // Linux stops: every service is stopped; returns when they ended or were killed.
    void StopAll();

    std::vector<aidl::org::lujarch::linux::LinuxService> List();
    Result<void> Control(const std::string& name, const std::string& action);
    Result<void> SetStartsWithLinux(const std::string& name, bool starts);
    Result<std::string> Log(const std::string& name);
    Result<std::vector<std::string>> Files(const std::string& name);
    Result<std::string> File(const std::string& name, const std::string& file);

    struct Service;

  private:
    using Clock = std::chrono::steady_clock;

    void Loop();
    void Wake();
    // The service directories the system has now.
    std::vector<std::string> Discover();
    bool StartLocked(Service& s);
    void StopLocked(Service& s);
    void LogLine(const std::string& name, const std::string& line);
    void Finish(Service& s, int status);

    System& system_;
    std::function<void()> changed_;
    std::mutex mutex_;
    std::condition_variable idle_;
    std::map<std::string, std::unique_ptr<Service>> services_;
    bool linux_ = false;
    unique_fd wake_;
};

}  // namespace lujarch::linux
