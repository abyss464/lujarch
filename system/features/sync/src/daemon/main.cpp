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

#include <android-base/logging.h>
#include <android/binder_interface_utils.h>
#include <lujarch/daemon.h>
#include <signal.h>

#include "identity.h"
#include "service.h"
#include "supervisor.h"

int main(int, char** argv) {
    android::base::InitLogging(argv, android::base::LogdLogger(android::base::SYSTEM));
    // Writes to the engine's closed socket fail with EPIPE instead of ending the daemon.
    signal(SIGPIPE, SIG_IGN);
    auto& supervisor = lujarch::sync::TheSupervisor();
    lujarch::sync::identity::SetEngineStop(
            [&supervisor](const std::function<android::base::Result<void>()>& install) {
                return supervisor.WithEngineStopped(install);
            });
    supervisor.Start();
    auto service = ndk::SharedRefBase::make<lujarch::sync::Service>();
    lujarch::Serve("lujarch_sync", service->asBinder(), 4);
    return 1;
}
