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

// Allowlisted runs of the proxy's manager.
#pragma once

#include <chrono>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include <android-base/result.h>

namespace lujarch::proxy::manager {

inline constexpr char kBinary[] = "/system_ext/bin/lujarch_proxy_manager";

// Checks the arguments against the allowlisted subcommands: what Lujarch's pages run. Returns why
// they are refused, or "" when allowed.
std::string CheckArgs(const std::vector<std::string>& args);

// Allowed shapes that read stdin (a subscription URL, an imported file):
// the request must carry "input", and only these may.
bool TakesInput(const std::vector<std::string>& args);

// Largest output kept for a run of args: 1 MiB less what a binder reply needs besides.
inline constexpr size_t kMaxOutput = 960u << 10;

struct Output {
    int code = -1;     // exit code, 128 + signal when killed
    std::string text;  // stdout and stderr, interleaved, not redacted
    bool truncated = false;
};

// Runs the manager with args (already checked) and a clean environment, killing its process group
// after timeout. stdin is input when it is a valid fd, else /dev/null. stdout goes to out when it
// is a valid fd, else into Output.text with stderr; at most kMaxOutput bytes are kept.
android::base::Result<Output> Run(const std::vector<std::string>& args,
                                  std::chrono::milliseconds timeout, int input = -1, int out = -1);

}  // namespace lujarch::proxy::manager
