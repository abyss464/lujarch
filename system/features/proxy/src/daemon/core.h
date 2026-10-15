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

// What the supervisor reads from the core's configuration and from the system, and the probes of
// its watchdog.
#pragma once

#include <netinet/in.h>

#include <string>
#include <vector>

namespace lujarch::proxy::core {

// The interface the core makes; the manager leaves the configuration's tun.device out.
inline constexpr char kTunDevice[] = "Meta";

// A top-level scalar of a configuration, without quotes; "" when absent.
std::string Top(const std::string& config, const std::string& key);

// The configuration turns its virtual network interface on (`tun:` with `enable: true`, block or
// flow style; the core's own default is off). An unreadable configuration counts as on, so that
// the interface stays watched.
bool TunEnabled(const std::string& config);

// The IPv6 addresses of the configuration's nodes (`server:` values).
std::vector<in6_addr> NodeIpv6(const std::string& config);

// The interface is up (/proc/net/dev lists it).
bool TunPresent();

// A local TCP socket listens on port (/proc/net/tcp and tcp6).
bool Listening(int port);

// The controller at host:port answers an HTTP request at all.
bool ControllerAnswers(const std::string& host, int port);

// A request to the phone's connectivity check address through the phone's own network path (its
// DNS and the virtual interface) gets the answer 204.
bool Probe();

enum class CoreProbe {
    kWorks,     // 204: the proxy itself reaches the address
    kNoReach,   // another answer: a node or the network is down
    kNoAnswer,  // no answer, or no port to ask
};
// The same request through the core's own HTTP port on loopback.
CoreProbe ProbeThroughCore(int port);

}  // namespace lujarch::proxy::core
