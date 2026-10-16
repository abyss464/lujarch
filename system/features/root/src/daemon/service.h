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

#pragma once

#include <aidl/org/lujarch/root/BnRoot.h>

namespace lujarch::root {

class Service : public aidl::org::lujarch::root::BnRoot {
  public:
    ndk::ScopedAStatus getInfo(aidl::org::lujarch::root::RootInfo* info) override;
    ndk::ScopedAStatus list(std::vector<aidl::org::lujarch::root::AppRoot>* apps) override;
    ndk::ScopedAStatus getProfile(const std::string& package,
                                  aidl::org::lujarch::root::RootProfile* profile) override;
    ndk::ScopedAStatus setAllowed(const std::string& package, bool allowed) override;
    ndk::ScopedAStatus setProfile(const std::string& package,
                                  const aidl::org::lujarch::root::RootProfile& profile) override;
    ndk::ScopedAStatus resetProfile(const std::string& package) override;
    ndk::ScopedAStatus setLinuxRootSessions(bool allowed) override;
};

}  // namespace lujarch::root
