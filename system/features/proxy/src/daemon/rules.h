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

// The IPv6 block while the proxy runs (PRX-5): policy rules set over rtnetlink.
#pragma once

#include <netinet/in.h>

#include <vector>

namespace lujarch::proxy::rules {

// Global unicast IPv6 (2000::/3) is refused by a rule just before Android's own, except the
// core's own connections (root) to the given addresses. Replaces the rules a previous call set.
void BlockIpv6(const std::vector<in6_addr>& exceptions);

// Removes the block and its exceptions.
void UnblockIpv6();

}  // namespace lujarch::proxy::rules
