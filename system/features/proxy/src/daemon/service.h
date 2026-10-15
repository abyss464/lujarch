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

#include <aidl/org/lujarch/proxy/BnProxy.h>

#include <mutex>

namespace lujarch::proxy {

// IProxy for Lujarch Settings, and its state for the status bar.
class Service : public aidl::org::lujarch::proxy::BnProxy {
  public:
    ndk::ScopedAStatus getStatus(aidl::org::lujarch::proxy::Status* status) override;
    ndk::ScopedAStatus watch(
            const std::shared_ptr<aidl::org::lujarch::proxy::IProxyListener>& listener) override;
    ndk::ScopedAStatus controller(const std::string& method, const std::string& path,
                                  const std::optional<std::string>& body,
                                  aidl::org::lujarch::proxy::Reply* reply) override;
    ndk::ScopedAStatus stream(const std::string& path, ndk::ScopedFileDescriptor* socket) override;
    ndk::ScopedAStatus manager(const std::vector<std::string>& args,
                               const std::optional<ndk::ScopedFileDescriptor>& input,
                               aidl::org::lujarch::proxy::Reply* reply) override;
    ndk::ScopedAStatus exportTo(bool secrets, const ndk::ScopedFileDescriptor& out,
                                int32_t* code) override;
    ndk::ScopedAStatus restart() override;

  private:
    // One manager run at a time: they share the manager's data and the core.
    std::mutex manager_mutex_;
};

}  // namespace lujarch::proxy
