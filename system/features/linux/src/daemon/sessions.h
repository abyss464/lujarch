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

// The terminal sessions (LNX-3): a shell on a terminal the daemon holds, so that a session goes
// on while the Terminal app is closed. The daemon keeps what each session wrote lately; an
// attach gets that first, then the output as it comes, over one end of a socket pair whose other
// end the app holds. A session ends when its shell exits, when it is ended (SIGHUP, SIGKILL
// three seconds later) and when Linux stops. While a session keeps the phone awake and a command
// runs in it, a wake lock named after it holds the phone awake.
#pragma once

#include <aidl/org/lujarch/linux/LinuxSession.h>
#include <android-base/result.h>
#include <android-base/unique_fd.h>

#include <atomic>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "system.h"

namespace lujarch::linux {

class Sessions {
  public:
    using Changed = std::function<void()>;
    using Exited = std::function<void(const std::string& id, int status)>;

    Sessions(System& system, Changed changed, Exited exited);

    // Starts the thread that carries the sessions' input and output.
    void Start();

    // Opens a session of a KIND_ constant's kind, its program started as spawn says (its
    // terminal is the session's).
    Result<std::string> Create(const std::string& kind, const std::string& name, int columns,
                               int rows, Spawn spawn);
    Result<unique_fd> Attach(const std::string& id, int columns, int rows);
    Result<void> Resize(const std::string& id, int columns, int rows);
    Result<void> Rename(const std::string& id, const std::string& name);
    Result<void> SetAwake(const std::string& id, bool awake);
    Result<void> End(const std::string& id);

    // Ends the sessions of the kinds (root sessions when Root sessions goes off).
    void EndKinds(const std::vector<std::string>& kinds);

    // Ends every session at once: Linux stops.
    void KillAll();

    std::vector<aidl::org::lujarch::linux::LinuxSession> List();
    size_t Count();

    struct Session;

  private:
    void Loop();
    void Wake();

    System& system_;
    Changed changed_;
    Exited exited_;
    std::mutex mutex_;
    std::map<uint64_t, std::unique_ptr<Session>> sessions_;
    uint64_t next_serial_ = 1;
    int next_id_ = 1;
    unique_fd epoll_;
    unique_fd wake_;
};

}  // namespace lujarch::linux
