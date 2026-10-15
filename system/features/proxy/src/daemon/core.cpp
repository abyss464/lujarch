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

#include "core.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>

#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <optional>
#include <regex>
#include <thread>

#include <lujarch/http.h>
#include <lujarch/text.h>

namespace lujarch::proxy::core {

namespace {

using namespace std::chrono_literals;
using android::base::unique_fd;

// Android's own connectivity check (Settings.Global captive_portal_http_url's default).
constexpr char kCheckHost[] = "connectivitycheck.gstatic.com";
constexpr char kCheckPath[] = "/generate_204";

std::vector<std::string> Lines(const std::string& path, bool* readable = nullptr) {
    std::string text;
    bool ok = android::base::ReadFileToString(path, &text);
    if (readable != nullptr) *readable = ok;
    return ok ? android::base::Split(text, "\n") : std::vector<std::string>{};
}

bool IsTrue(std::string_view value) {
    return value == "true" || value == "True" || value == "TRUE" || value == "yes" || value == "on";
}

struct Address {
    sockaddr_storage storage = {};
    socklen_t length = 0;
    const sockaddr* get() const { return reinterpret_cast<const sockaddr*>(&storage); }
};

std::optional<Address> Numeric(const std::string& host, int port) {
    Address address;
    auto* v4 = reinterpret_cast<sockaddr_in*>(&address.storage);
    auto* v6 = reinterpret_cast<sockaddr_in6*>(&address.storage);
    if (inet_pton(AF_INET, host.c_str(), &v4->sin_addr) == 1) {
        v4->sin_family = AF_INET;
        v4->sin_port = htons(port);
        address.length = sizeof(*v4);
    } else if (inet_pton(AF_INET6, host.c_str(), &v6->sin6_addr) == 1) {
        v6->sin6_family = AF_INET6;
        v6->sin6_port = htons(port);
        address.length = sizeof(*v6);
    } else {
        return std::nullopt;
    }
    return address;
}

// A name lookup through the phone's resolver within timeout; a lookup that takes longer finishes
// on its own thread and is not waited for.
std::optional<Address> Resolve(const std::string& host, int port,
                               std::chrono::milliseconds timeout) {
    struct Shared {
        std::mutex mutex;
        std::condition_variable done_cv;
        bool done = false;
        std::optional<Address> address;
    };
    auto shared = std::make_shared<Shared>();
    std::thread([shared, host, port] {
        addrinfo hints = {};
        hints.ai_socktype = SOCK_STREAM;
        addrinfo* result = nullptr;
        std::optional<Address> found;
        if (getaddrinfo(host.c_str(), std::to_string(port).c_str(), &hints, &result) == 0) {
            if (result != nullptr && result->ai_addrlen <= sizeof(sockaddr_storage)) {
                Address address;
                memcpy(&address.storage, result->ai_addr, result->ai_addrlen);
                address.length = result->ai_addrlen;
                found = address;
            }
            freeaddrinfo(result);
        }
        std::lock_guard lock(shared->mutex);
        shared->address = found;
        shared->done = true;
        shared->done_cv.notify_all();
    }).detach();
    std::unique_lock lock(shared->mutex);
    shared->done_cv.wait_for(lock, timeout, [&] { return shared->done; });
    return shared->done ? shared->address : std::nullopt;
}

// The first line of the answer to request, "" when none comes within timeout.
std::string Ask(const Address& address, const std::string& request,
                std::chrono::milliseconds timeout) {
    auto fd = http::Connect(address.get(), address.length, 6s, "the probe's server");
    if (!fd.ok()) return "";
    http::SetTimeouts(fd->get(), timeout, timeout);
    if (!text::SendAll(fd->get(), request)) return "";
    std::string data;
    char buf[512];
    while (data.find('\n') == std::string::npos && data.size() < 4096) {
        ssize_t n = TEMP_FAILURE_RETRY(recv(fd->get(), buf, sizeof(buf), 0));
        if (n <= 0) break;
        data.append(buf, n);
    }
    return data.substr(0, data.find('\n'));
}

std::string CheckRequest(bool absolute) {
    std::string target = absolute ? std::string("http://") + kCheckHost + kCheckPath : kCheckPath;
    return "GET " + target + " HTTP/1.1\r\nHost: " + kCheckHost + "\r\nConnection: close\r\n\r\n";
}

bool Is204(const std::string& status_line) {
    return android::base::StartsWith(status_line, "HTTP/") &&
           status_line.find(" 204") != std::string::npos;
}

}  // namespace

std::string Top(const std::string& config, const std::string& key) {
    for (const std::string& line : Lines(config)) {
        if (!android::base::StartsWith(line, key + ":")) continue;
        std::string value;
        for (char c : line.substr(key.size() + 1)) {
            if (c != '"' && c != '\'' && c != ' ' && c != '\t' && c != '\r') value.push_back(c);
        }
        return value;
    }
    return "";
}

bool TunEnabled(const std::string& config) {
    bool readable = false;
    std::vector<std::string> lines = Lines(config, &readable);
    if (!readable) return true;
    static const std::regex kEnable(R"(^[^#]*[\s{,]enable:\s*([A-Za-z]*))");
    for (size_t i = 0; i < lines.size(); ++i) {
        if (!android::base::StartsWith(lines[i], "tun:")) continue;
        for (size_t j = i; j < lines.size(); ++j) {
            const std::string& line = lines[j];
            if (j > i && !line.empty() && line[0] != ' ' && line[0] != '\t' && line[0] != '#') {
                break;
            }
            std::smatch match;
            if (std::regex_search(line, match, kEnable)) return IsTrue(match[1].str());
        }
        return false;
    }
    return false;
}

std::vector<in6_addr> NodeIpv6(const std::string& config) {
    static const std::regex kServer(R"(^ *server: *['"]?([0-9A-Fa-f]*:[0-9A-Fa-f:.]*)['"]? *\r?$)");
    std::vector<in6_addr> addresses;
    for (const std::string& line : Lines(config)) {
        std::smatch match;
        if (!std::regex_match(line, match, kServer)) continue;
        in6_addr address;
        if (inet_pton(AF_INET6, match[1].str().c_str(), &address) != 1) continue;
        bool seen = false;
        for (const in6_addr& known : addresses)
            seen |= memcmp(&known, &address, sizeof(address)) == 0;
        if (!seen) addresses.push_back(address);
    }
    return addresses;
}

bool TunPresent() {
    for (const std::string& line : Lines("/proc/net/dev")) {
        if (android::base::StartsWith(android::base::Trim(line), std::string(kTunDevice) + ":")) {
            return true;
        }
    }
    return false;
}

bool Listening(int port) {
    char hex[8];
    snprintf(hex, sizeof(hex), ":%04X", port);
    for (const char* file : {"/proc/net/tcp", "/proc/net/tcp6"}) {
        for (const std::string& line : Lines(file)) {
            std::vector<std::string> fields = android::base::Tokenize(line, " ");
            if (fields.size() > 3 && android::base::EndsWith(fields[1], hex) && fields[3] == "0A") {
                return true;
            }
        }
    }
    return false;
}

bool ControllerAnswers(const std::string& host, int port) {
    std::optional<Address> address = Numeric(host, port);
    if (!address) return false;
    return android::base::StartsWith(Ask(*address, "GET / HTTP/1.0\r\n\r\n", 3s), "HTTP/");
}

bool Probe() {
    std::optional<Address> address = Resolve(kCheckHost, 80, 6s);
    return address && Is204(Ask(*address, CheckRequest(false), 6s));
}

CoreProbe ProbeThroughCore(int port) {
    if (port <= 0) return CoreProbe::kNoAnswer;
    // The core answers 502 when it cannot reach the address (its dial timeout is 5 s).
    std::string status = Ask(*Numeric("127.0.0.1", port), CheckRequest(true), 10s);
    if (Is204(status)) return CoreProbe::kWorks;
    if (android::base::StartsWith(status, "HTTP/")) return CoreProbe::kNoReach;
    return CoreProbe::kNoAnswer;
}

}  // namespace lujarch::proxy::core
