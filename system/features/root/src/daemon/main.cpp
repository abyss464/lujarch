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
#include <lujarch/daemon.h>

#include "ksu.h"
#include "service.h"

int main(int, char** argv) {
    android::base::InitLogging(argv, android::base::LogdLogger(android::base::SYSTEM));
    // The driver is opened once, before any caller: its state is logged at every start.
    (void)lujarch::root::ksu::GetInfo();
    auto service = ndk::SharedRefBase::make<lujarch::root::Service>();
    lujarch::Serve("lujarch_root", service->asBinder());
    return 1;
}
