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

// The run conditions of sync (SYN-2): it runs only on Wi-Fi, only while a charger is connected,
// or both. A watcher thread follows the network and the power supply and tells the supervisor
// when what sync waits for changes.
//
//   Wi-Fi: netd gives every connected network a routing table (its interface index + 1000) and
//     rules that refer to it; a Wi-Fi station interface (wlan*) that is up and running and whose
//     table has a rule is a connected Wi-Fi network, validated or not. Hotspot interfaces are in
//     netd's local network and have no table of their own. Events: rtnetlink link and rule
//     changes.
//   Charger: the Health HAL, as BatteryService reads it: AC, USB, wireless or dock online.
//     Events: power_supply uevents.
// A change is acted on kSettle after the first event of a burst, so a Wi-Fi roam or a brief
// disconnect does not stop and start sync. Every kRecheck the state is read again. Only a known
// "not met" makes sync wait: a state that cannot be read counts as the last known one, or as met
// when none is known, logged once, and is read again after kUnknownRetry.
#pragma once

#include <sys/types.h>

#include <chrono>
#include <functional>
#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <vector>

#include <json/value.h>

namespace lujarch::sync::conditions {

inline constexpr auto kSettle = std::chrono::seconds(3);
// Evaluated at least this often, in case an event was missed.
inline constexpr auto kRecheck = std::chrono::minutes(15);
// Read again this soon while a state a choice needs cannot be read.
inline constexpr auto kUnknownRetry = std::chrono::seconds(30);
// netd's per-interface routing tables (RouteController::ROUTE_TABLE_OFFSET_FROM_INDEX).
inline constexpr uint32_t kNetdTableOffset = 1000;

struct Link {
    int index = 0;
    std::string name;
    unsigned flags = 0;  // IFF_*
};

// RTM_NEWLINK messages of a netlink dump (or event batch).
std::vector<Link> ParseLinks(const void* data, size_t size);

// The routing tables that RTM_NEWRULE messages refer to.
std::set<uint32_t> ParseRuleTables(const void* data, size_t size);

// A Wi-Fi station interface that is up, running and has netd's table.
bool WifiConnected(const std::vector<Link>& links, const std::set<uint32_t>& tables);

// A kernel uevent ("ACTION@DEVPATH\0KEY=VALUE\0...") of a power supply.
bool IsPowerSupplyUevent(std::string_view message);

// "", "wifi", "charging" or "wifi,charging": the choices whose state is
// known not to be met; an unknown state (nullopt) counts as met.
std::string Waiting(bool wifi_only, bool charging_only, std::optional<bool> wifi,
                    std::optional<bool> charging);

// The state a decision uses: the reading when there is one (kept in
// *last_known), else the last known reading, else nullopt (met).
std::optional<bool> Decide(std::optional<bool> reading, std::optional<bool>* last_known);

// When the watcher evaluates. Deadlines: the first event of a burst sets one
// settle later, which later events do not move; the periodic recheck runs on
// its own, every recheck from its last run, whatever the events do; after an
// evaluation that could not read a state it needed, a retry.
class Schedule {
  public:
    using Clock = std::chrono::steady_clock;

    Schedule(Clock::duration settle, Clock::duration recheck, Clock::duration retry,
             Clock::time_point now)
        : settle_(settle), recheck_(recheck), retry_(retry), periodic_(now + recheck) {}

    // A link, rule or power-supply event arrived.
    void Event(Clock::time_point now) {
        if (!burst_) burst_ = now + settle_;
    }
    // The earliest deadline.
    Clock::time_point Next() const {
        Clock::time_point next = periodic_;
        if (burst_ && *burst_ < next) next = *burst_;
        if (retry_at_ && *retry_at_ < next) next = *retry_at_;
        return next;
    }
    bool Due(Clock::time_point now) const { return now >= Next(); }
    // An evaluation ran at now; unknown: a state it needed was not readable.
    void Evaluated(Clock::time_point now, bool unknown) {
        burst_.reset();
        if (periodic_ <= now) periodic_ = now + recheck_;
        if (unknown) {
            retry_at_ = now + retry_;
        } else {
            retry_at_.reset();
        }
    }

  private:
    Clock::duration settle_, recheck_, retry_;
    Clock::time_point periodic_;
    std::optional<Clock::time_point> burst_;
    std::optional<Clock::time_point> retry_at_;
};

// Starts the watcher thread; changed runs on it whenever what sync waits for changes.
void Start(std::function<void()> changed);

// What sync waits for: "", "wifi", "charging" or "wifi,charging".
std::string Waiting();

// Reads the choices and the state again now (a choice changed).
void Recheck();

// The choices, the state of the network and the charger, and what sync waits for, for the page.
Json::Value Describe();

}  // namespace lujarch::sync::conditions
