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

#include <aidl/org/lujarch/sync/BnSync.h>

namespace lujarch::sync {

// ISync for Lujarch Settings (the Sync pages) and Lujarch Vault (the databases).
class Service : public aidl::org::lujarch::sync::BnSync {
  public:
    ndk::ScopedAStatus getStatus(aidl::org::lujarch::sync::Status* status) override;
    ndk::ScopedAStatus api(const std::string& method, const std::string& path,
                           const std::optional<std::string>& body,
                           aidl::org::lujarch::sync::Reply* reply) override;
    ndk::ScopedAStatus dirs(std::string* json) override;
    ndk::ScopedAStatus ignored(const std::string& op, const std::optional<std::string>& device,
                               const std::optional<std::string>& name,
                               const std::optional<std::string>& address,
                               std::string* json) override;
    ndk::ScopedAStatus conditions(std::string* json) override;
    ndk::ScopedAStatus moveFolder(const std::string& folder, const std::string& dir,
                                  std::string* json) override;
    ndk::ScopedAStatus identity(std::string* json) override;
    ndk::ScopedAStatus importIdentity(const std::vector<uint8_t>& cert,
                                      const std::vector<uint8_t>& key,
                                      const std::optional<std::vector<uint8_t>>& config,
                                      bool keepConfig, std::string* json) override;
    ndk::ScopedAStatus generateIdentity(bool keepConfig, std::string* json) override;
    ndk::ScopedAStatus restoreIdentity(const std::string& backup, std::string* json) override;
    ndk::ScopedAStatus vaultFind(std::string* json) override;
    ndk::ScopedAStatus vaultOpen(const std::string& folder, const std::string& path,
                                 ndk::ScopedFileDescriptor* fd) override;
};

}  // namespace lujarch::sync
