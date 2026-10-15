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

#include "conditions.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/fib_rules.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <poll.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <aidl/android/hardware/health/IHealth.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/result.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <android/binder_manager.h>
#include <cutils/uevent.h>
#include <lujarch/daemon.h>

#include <functional>

namespace lujarch::sync::conditions {

namespace {

using aidl::android::hardware::health::HealthInfo;
using aidl::android::hardware::health::IHealth;
using android::base::ErrnoError;
using android::base::Error;
using android::base::Result;
using android::base::unique_fd;

constexpr size_t kHdrLen = NLMSG_ALIGN(sizeof(nlmsghdr));
constexpr size_t kAttrHdrLen = RTA_ALIGN(sizeof(rtattr));
constexpr auto kDumpTimeout = std::chrono::seconds(2);
// Room for a burst of uevents while the thread evaluates.
constexpr int kUeventBuffer = 256 * 1024;

size_t Align(size_t n) {
    return (n + NLMSG_ALIGNTO - 1) & ~static_cast<size_t>(NLMSG_ALIGNTO - 1);
}

// Calls f(type, payload, payload_size) for each netlink message in data.
template <typename F>
void ForEachMessage(const void* data, size_t size, F f) {
    const auto* bytes = static_cast<const uint8_t*>(data);
    size_t off = 0;
    while (off + sizeof(nlmsghdr) <= size) {
        nlmsghdr h;
        memcpy(&h, bytes + off, sizeof(h));
        if (h.nlmsg_len < sizeof(nlmsghdr) || h.nlmsg_len > size - off) break;
        if (!f(h.nlmsg_type, bytes + off + kHdrLen,
               h.nlmsg_len >= kHdrLen ? h.nlmsg_len - kHdrLen : 0))
            break;
        off += Align(h.nlmsg_len);
    }
}

// Calls f(type, payload, payload_size) for each route attribute.
template <typename F>
void ForEachAttribute(const uint8_t* data, size_t size, F f) {
    size_t off = 0;
    while (off + sizeof(rtattr) <= size) {
        rtattr a;
        memcpy(&a, data + off, sizeof(a));
        if (a.rta_len < sizeof(rtattr) || a.rta_len > size - off) break;
        f(a.rta_type, data + off + kAttrHdrLen,
          a.rta_len >= kAttrHdrLen ? a.rta_len - kAttrHdrLen : 0);
        off += RTA_ALIGN(a.rta_len);
    }
}

// One NLM_F_DUMP request on a fresh NETLINK_ROUTE socket; the replies up
// to NLMSG_DONE.
Result<std::string> Dump(uint16_t type, const void* header, size_t header_size) {
    unique_fd fd(socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE));
    if (fd < 0) return ErrnoError() << "netlink route socket";
    timeval tv{};
    tv.tv_sec = kDumpTimeout.count();
    setsockopt(fd.get(), SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    std::string request(kHdrLen + Align(header_size), '\0');
    nlmsghdr h{};
    h.nlmsg_len = static_cast<uint32_t>(kHdrLen + header_size);
    h.nlmsg_type = type;
    h.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    h.nlmsg_seq = 1;
    memcpy(request.data(), &h, sizeof(h));
    memcpy(request.data() + kHdrLen, header, header_size);
    sockaddr_nl kernel{};
    kernel.nl_family = AF_NETLINK;
    if (sendto(fd.get(), request.data(), h.nlmsg_len, 0, reinterpret_cast<const sockaddr*>(&kernel),
               sizeof(kernel)) < 0) {
        return ErrnoError() << "netlink dump request " << type;
    }
    std::string out;
    std::vector<uint8_t> buf(64 * 1024);
    bool done = false;
    int error = 0;
    while (!done) {
        const ssize_t n = TEMP_FAILURE_RETRY(recv(fd.get(), buf.data(), buf.size(), 0));
        if (n < 0) return ErrnoError() << "netlink dump " << type;
        if (n == 0) break;
        ForEachMessage(buf.data(), static_cast<size_t>(n),
                       [&](uint16_t t, const uint8_t* p, size_t len) {
                           if (t == NLMSG_DONE) {
                               done = true;
                               return false;
                           }
                           if (t == NLMSG_ERROR && len >= sizeof(nlmsgerr)) {
                               nlmsgerr e;
                               memcpy(&e, p, sizeof(e));
                               error = -e.error;
                               done = true;
                               return false;
                           }
                           return true;
                       });
        out.append(reinterpret_cast<const char*>(buf.data()), static_cast<size_t>(n));
    }
    if (error != 0) return Error() << "netlink dump " << type << ": " << strerror(error);
    return out;
}

// nullopt with *why when the state cannot be read (Evaluate logs it once).
std::optional<bool> ReadWifi(std::string* why) {
    ifinfomsg link_header{};
    link_header.ifi_family = AF_UNSPEC;
    auto links = Dump(RTM_GETLINK, &link_header, sizeof(link_header));
    if (!links.ok()) {
        *why = links.error().message();
        return std::nullopt;
    }
    fib_rule_hdr rule_header{};
    rule_header.family = AF_INET;
    auto rules = Dump(RTM_GETRULE, &rule_header, sizeof(rule_header));
    if (!rules.ok()) {
        *why = rules.error().message();
        return std::nullopt;
    }
    return WifiConnected(ParseLinks(links->data(), links->size()),
                         ParseRuleTables(rules->data(), rules->size()));
}

// The Health HAL, connected on first use and again after it failed.
std::shared_ptr<IHealth> health;

std::optional<bool> ReadCharging(std::string* why) {
    if (!health) {
        const std::string name = std::string(IHealth::descriptor) + "/default";
        ndk::SpAIBinder binder(AServiceManager_checkService(name.c_str()));
        if (binder.get() == nullptr) {
            *why = name + " is not available";
            return std::nullopt;
        }
        health = IHealth::fromBinder(binder);
        if (!health) {
            *why = name + " is not an IHealth";
            return std::nullopt;
        }
    }
    HealthInfo info;
    const ndk::ScopedAStatus status = health->getHealthInfo(&info);
    if (!status.isOk()) {
        *why = "IHealth.getHealthInfo: " + status.getDescription();
        health.reset();
        return std::nullopt;
    }
    return info.chargerAcOnline || info.chargerUsbOnline || info.chargerWirelessOnline ||
           info.chargerDockOnline;
}

struct State {
    bool wifi_only = false;
    bool charging_only = false;
    std::optional<bool> wifi;  // the readings; nullopt: not read or unreadable
    std::optional<bool> charging;
    std::string waiting;
    bool unknown = false;  // a state a choice needs could not be read
};

// What the decisions know of one state between evaluations.
struct Memory {
    std::optional<bool> last_known;
    bool loss_logged = false;  // "cannot be read" was logged and not yet cleared
};

// Evaluations from the watcher and from the supervisor, one at a time;
// the memories are theirs.
std::mutex evaluate_lock;
Memory wifi_memory;
Memory charging_memory;

// The state one choice is decided on (Decide), with the log of an
// unreadable state once per loss. read: the state was read (a choice needs
// it, or the page asked); a state nobody reads keeps no knowledge, so a
// choice turned on later starts from its first reading.
std::optional<bool> Resolve(const char* what, bool needed, bool read, std::optional<bool> reading,
                            const std::string& why, Memory* memory, bool* unknown) {
    if (!read) {
        *memory = Memory();
        return std::nullopt;
    }
    const std::optional<bool> decided = Decide(reading, &memory->last_known);
    if (reading) {
        if (memory->loss_logged)
            LOG(INFO) << "sync conditions: the " << what << " state can be read again";
        memory->loss_logged = false;
        return decided;
    }
    if (needed) {
        *unknown = true;
        if (!memory->loss_logged) {
            LOG(WARNING) << "sync conditions: the " << what << " state cannot be read (" << why
                         << "); deciding on "
                         << (decided ? (*decided ? "the last known state (met)"
                                                 : "the last known state (not met)")
                                     : "no known state (counts as met)")
                         << "; reading again every " << kUnknownRetry.count() << " s";
            memory->loss_logged = true;
        }
    }
    return decided;
}

// What the supervisor is waiting for now, and whom to tell when it changes.
std::mutex waiting_lock;
std::string current_waiting;
std::function<void()> on_change;

// Reads the choices and the state; when apply, sets the gate. read_all
// reads the network and the charger even without a choice that needs them
// (for the page).
State Evaluate(bool apply, bool read_all) {
    std::lock_guard<std::mutex> guard(evaluate_lock);
    State s;
    s.wifi_only = SettingIsOn("sync", "wifi_only");
    s.charging_only = SettingIsOn("sync", "charging_only");
    const bool read_wifi = s.wifi_only || read_all;
    const bool read_charging = s.charging_only || read_all;
    std::string wifi_why, charging_why;
    if (read_wifi) s.wifi = ReadWifi(&wifi_why);
    if (read_charging) s.charging = ReadCharging(&charging_why);
    const std::optional<bool> wifi =
            Resolve("Wi-Fi", s.wifi_only, read_wifi, s.wifi, wifi_why, &wifi_memory, &s.unknown);
    const std::optional<bool> charging =
            Resolve("charger", s.charging_only, read_charging, s.charging, charging_why,
                    &charging_memory, &s.unknown);
    s.waiting = Waiting(s.wifi_only, s.charging_only, wifi, charging);
    if (apply) {
        bool changed = false;
        {
            std::lock_guard<std::mutex> lock(waiting_lock);
            changed = current_waiting != s.waiting;
            current_waiting = s.waiting;
        }
        if (changed) {
            LOG(INFO) << "sync conditions: "
                      << (s.waiting.empty() ? "met" : "waiting for " + s.waiting);
            if (on_change) on_change();
        }
    }
    return s;
}

unique_fd OpenRouteEvents() {
    unique_fd fd(socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_ROUTE));
    if (fd < 0) {
        PLOG(ERROR) << "sync conditions: netlink route socket";
        return {};
    }
    sockaddr_nl addr{};
    addr.nl_family = AF_NETLINK;
    addr.nl_groups = RTMGRP_LINK | RTMGRP_IPV4_RULE;
    if (bind(fd.get(), reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) != 0) {
        PLOG(ERROR) << "sync conditions: bind to link and rule events";
        return {};
    }
    return fd;
}

unique_fd OpenUevents() {
    unique_fd fd(uevent_open_socket(kUeventBuffer, true));
    if (fd < 0) {
        PLOG(ERROR) << "sync conditions: uevent socket";
        return {};
    }
    // Drained until EAGAIN after each poll.
    if (fcntl(fd.get(), F_SETFL, O_NONBLOCK) != 0) {
        PLOG(ERROR) << "sync conditions: O_NONBLOCK on the uevent socket";
        return {};
    }
    return fd;
}

void Watch() {
    unique_fd routes = OpenRouteEvents();
    unique_fd uevents = OpenUevents();
    if (routes < 0 || uevents < 0) {
        LOG(WARNING) << "sync conditions: without events; checking every minute";
    }
    using Clock = Schedule::Clock;
    const auto recheck =
            routes >= 0 && uevents >= 0
                    ? std::chrono::duration_cast<Clock::duration>(kRecheck)
                    : std::chrono::duration_cast<Clock::duration>(std::chrono::minutes(1));
    Schedule schedule(kSettle, recheck, kUnknownRetry, Clock::now());
    schedule.Evaluated(Clock::now(), Evaluate(true, false).unknown);
    std::vector<uint8_t> buf(64 * 1024);
    while (true) {
        // Negative fds (a socket that did not open) are ignored by poll.
        pollfd fds[2] = {{routes.get(), POLLIN, 0}, {uevents.get(), POLLIN, 0}};
        const auto wait = std::chrono::duration_cast<std::chrono::milliseconds>(schedule.Next() -
                                                                                Clock::now());
        const int n =
                poll(fds, 2,
                     wait.count() > 0 ? static_cast<int>(std::min<int64_t>(wait.count(), INT32_MAX))
                                      : 0);
        if (n < 0 && errno != EINTR) {
            PLOG(ERROR) << "sync conditions: poll";
            std::this_thread::sleep_for(std::chrono::seconds(5));
            continue;
        }
        // Drained until nothing is left. ENOBUFS (the socket overflowed,
        // which poll reports as POLLERR) means events were lost: a change
        // too.
        if (n > 0 && (fds[0].revents & (POLLIN | POLLERR))) {
            // Any link or rule change.
            while (true) {
                const ssize_t len = recv(routes.get(), buf.data(), buf.size(), MSG_DONTWAIT);
                if (len <= 0 && !(len < 0 && errno == ENOBUFS)) break;
                schedule.Event(Clock::now());
            }
        }
        if (n > 0 && (fds[1].revents & (POLLIN | POLLERR))) {
            while (true) {
                const ssize_t len =
                        uevent_kernel_multicast_recv(uevents.get(), buf.data(), buf.size());
                if (len > 0) {
                    if (IsPowerSupplyUevent(
                                std::string_view(reinterpret_cast<const char*>(buf.data()),
                                                 static_cast<size_t>(len)))) {
                        schedule.Event(Clock::now());
                    }
                    continue;
                }
                if (len < 0 && errno == ENOBUFS) {
                    schedule.Event(Clock::now());
                    continue;
                }
                // EAGAIN; EIO: a message not from the kernel, dropped (the
                // next poll reads on).
                break;
            }
        }
        if (schedule.Due(Clock::now())) {
            const State s = Evaluate(true, false);
            schedule.Evaluated(Clock::now(), s.unknown);
        }
    }
}

}  // namespace

std::vector<Link> ParseLinks(const void* data, size_t size) {
    std::vector<Link> links;
    ForEachMessage(data, size, [&](uint16_t type, const uint8_t* p, size_t len) {
        if (type != RTM_NEWLINK || len < sizeof(ifinfomsg)) return true;
        ifinfomsg ifi;
        memcpy(&ifi, p, sizeof(ifi));
        Link link;
        link.index = ifi.ifi_index;
        link.flags = ifi.ifi_flags;
        const size_t off = Align(sizeof(ifinfomsg));
        if (len > off) {
            ForEachAttribute(p + off, len - off, [&](uint16_t t, const uint8_t* v, size_t n) {
                if (t == IFLA_IFNAME)
                    link.name.assign(reinterpret_cast<const char*>(v),
                                     strnlen(reinterpret_cast<const char*>(v), n));
            });
        }
        links.push_back(std::move(link));
        return true;
    });
    return links;
}

std::set<uint32_t> ParseRuleTables(const void* data, size_t size) {
    std::set<uint32_t> tables;
    ForEachMessage(data, size, [&](uint16_t type, const uint8_t* p, size_t len) {
        if (type != RTM_NEWRULE || len < sizeof(fib_rule_hdr)) return true;
        fib_rule_hdr rule;
        memcpy(&rule, p, sizeof(rule));
        uint32_t table = rule.table;
        const size_t off = Align(sizeof(fib_rule_hdr));
        if (len > off) {
            ForEachAttribute(p + off, len - off, [&](uint16_t t, const uint8_t* v, size_t n) {
                if (t == FRA_TABLE && n >= sizeof(uint32_t)) memcpy(&table, v, sizeof(table));
            });
        }
        tables.insert(table);
        return true;
    });
    return tables;
}

bool WifiConnected(const std::vector<Link>& links, const std::set<uint32_t>& tables) {
    for (const Link& link : links) {
        if (!android::base::StartsWith(link.name, "wlan")) continue;
        if ((link.flags & IFF_UP) == 0 || (link.flags & IFF_RUNNING) == 0) continue;
        if (link.index > 0 &&
            tables.count(static_cast<uint32_t>(link.index) + kNetdTableOffset) != 0)
            return true;
    }
    return false;
}

bool IsPowerSupplyUevent(std::string_view message) {
    size_t pos = 0;
    while (pos < message.size()) {
        size_t end = message.find('\0', pos);
        if (end == std::string_view::npos) end = message.size();
        if (message.substr(pos, end - pos) == "SUBSYSTEM=power_supply") return true;
        pos = end + 1;
    }
    return false;
}

std::string Waiting(bool wifi_only, bool charging_only, std::optional<bool> wifi,
                    std::optional<bool> charging) {
    std::vector<std::string> waiting;
    if (wifi_only && wifi == false) waiting.push_back("wifi");
    if (charging_only && charging == false) waiting.push_back("charging");
    return android::base::Join(waiting, ',');
}

std::optional<bool> Decide(std::optional<bool> reading, std::optional<bool>* last_known) {
    if (reading) *last_known = reading;
    return *last_known;
}

void Start(std::function<void()> changed) {
    on_change = std::move(changed);
    std::thread(Watch).detach();
}

std::string Waiting() {
    std::lock_guard<std::mutex> lock(waiting_lock);
    return current_waiting;
}

void Recheck() {
    Evaluate(true, false);
}

Json::Value Describe() {
    const State s = Evaluate(true, true);
    Json::Value reply(Json::objectValue);
    reply["wifi_only"] = s.wifi_only;
    reply["charging_only"] = s.charging_only;
    reply["wifi"] = s.wifi ? Json::Value(*s.wifi) : Json::Value();
    reply["charging"] = s.charging ? Json::Value(*s.charging) : Json::Value();
    Json::Value waiting(Json::arrayValue);
    for (const std::string& w : android::base::Split(s.waiting, ",")) {
        if (!w.empty()) waiting.append(w);
    }
    reply["waiting"] = waiting;
    return reply;
}

}  // namespace lujarch::sync::conditions
