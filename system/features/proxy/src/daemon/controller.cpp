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

#include "controller.h"

#include <errno.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <vector>

#include <android-base/file.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>

#include <lujarch/text.h>

namespace lujarch::proxy::controller {

using android::base::ErrnoError;
using android::base::Error;
using android::base::Result;
using android::base::unique_fd;
using namespace std::chrono_literals;

namespace {

constexpr char kSecretFile[] = "/data/lujarch/proxy/secret";
constexpr uint16_t kPort = 9090;
constexpr auto kConnectTimeout = 2s;
constexpr auto kStreamSendTimeout = 10s;  // connect and send the request of a stream
constexpr size_t kMaxReply = 16u << 20;   // /proxies and /connections stay far below
constexpr size_t kMaxStreamLine = 1u << 20;
constexpr size_t kMaxTarget = 2048;
constexpr char kWhat[] = "the controller";

// ---- allowlist ----

enum class BodyRule {
    kNone,         // no body
    kSelectProxy,  // {"name": <proxy>}
    kConfigPatch,  // {"mode": "rule" | "global" | "direct"} or {"log-level": <level>}
};

struct Route {
    std::string_view method;
    std::string_view path;  // "*" matches one segment
    BodyRule body;
    bool query;  // a query string is allowed
};

// What Lujarch's pages use of the controller API, and nothing else: no PUT /configs (it loads a
// configuration from a path), no POST but the geodata update (not restart, upgrade, cache
// flushes), no /debug, /storage, /memory, /rules/disable.
constexpr Route kRoutes[] = {
        {"GET", "/version", BodyRule::kNone, false},
        {"GET", "/configs", BodyRule::kNone, false},
        {"PATCH", "/configs", BodyRule::kConfigPatch, false},
        // Downloads GeoIP/GeoSite from the config's geox-url and reloads them.
        {"POST", "/configs/geo", BodyRule::kNone, false},
        {"GET", "/proxies", BodyRule::kNone, false},
        {"GET", "/proxies/*", BodyRule::kNone, false},
        {"PUT", "/proxies/*", BodyRule::kSelectProxy, false},
        {"DELETE", "/proxies/*", BodyRule::kNone, false},
        {"GET", "/proxies/*/delay", BodyRule::kNone, true},
        {"GET", "/group", BodyRule::kNone, false},
        {"GET", "/group/*", BodyRule::kNone, false},
        {"GET", "/group/*/delay", BodyRule::kNone, true},
        {"GET", "/providers/proxies", BodyRule::kNone, false},
        {"GET", "/providers/proxies/*", BodyRule::kNone, false},
        {"PUT", "/providers/proxies/*", BodyRule::kNone, false},
        {"GET", "/providers/proxies/*/healthcheck", BodyRule::kNone, false},
        {"GET", "/providers/proxies/*/*", BodyRule::kNone, false},
        {"GET", "/providers/proxies/*/*/healthcheck", BodyRule::kNone, true},
        {"GET", "/providers/rules", BodyRule::kNone, false},
        {"PUT", "/providers/rules/*", BodyRule::kNone, false},
        {"GET", "/connections", BodyRule::kNone, false},
        {"DELETE", "/connections", BodyRule::kNone, false},
        {"DELETE", "/connections/*", BodyRule::kNone, false},
        {"GET", "/rules", BodyRule::kNone, false},
        {"GET", "/dns/query", BodyRule::kNone, true},
};

bool HasOnlyMember(const Json::Value& body, const char* name) {
    return body.isObject() && body.size() == 1 && body.isMember(name);
}

// ---- HTTP ----

Result<std::string> ReadSecret() {
    std::string secret;
    if (!android::base::ReadFileToString(kSecretFile, &secret)) {
        return ErrnoError() << "cannot read the controller secret";
    }
    secret = android::base::Trim(secret);
    if (secret.empty()) return Error() << "the controller secret is empty";
    for (char c : secret) {
        // It goes into a header line.
        if (static_cast<unsigned char>(c) <= ' ' || c == 0x7f) {
            return Error() << "the controller secret contains control characters";
        }
    }
    return secret;
}

// One HTTP/1.0 request with the secret: the controller then replies
// without chunked encoding and closes the connection after the reply.
Result<std::string> BuildRequest(std::string_view method, std::string_view target,
                                 std::string_view body) {
    auto secret = ReadSecret();
    if (!secret.ok()) return secret.error();
    return http::BuildRequest(method, target, "127.0.0.1:9090",
                              {"Authorization: Bearer " + *secret}, body);
}

Result<unique_fd> Connect() {
    return http::ConnectLoopback(kPort, kConnectTimeout, "controller 127.0.0.1:9090");
}

// Connects and sends a request whose reply the caller reads.
Result<unique_fd> Send(std::string_view method, std::string_view target, std::string_view body,
                       std::chrono::milliseconds timeout) {
    auto request = BuildRequest(method, target, body);
    if (!request.ok()) return request.error();
    auto fd = Connect();
    if (!fd.ok()) return fd.error();
    http::SetTimeouts(fd->get(), timeout, timeout);
    if (!text::SendAll(fd->get(), *request)) return ErrnoError() << "sending to the controller";
    return std::move(*fd);
}

}  // namespace

std::string CheckApi(std::string_view method, std::string_view target, const Json::Value& body) {
    if (method != "GET" && method != "PUT" && method != "PATCH" && method != "DELETE" &&
        method != "POST") {
        return "method not allowed (GET, PUT, PATCH, DELETE, POST)";
    }
    if (std::string why = http::CheckTarget(target, kMaxTarget); !why.empty()) return why;
    const size_t query = target.find('?');
    std::vector<std::string_view> segments;
    if (!http::SplitPath(target.substr(0, query), &segments)) return "path not allowed";

    const Route* route = nullptr;
    bool known_path = false;
    for (const Route& r : kRoutes) {
        if (!http::Matches(r.path, segments)) continue;
        known_path = true;
        if (r.method == method) {
            route = &r;
            break;
        }
    }
    if (route == nullptr)
        return known_path ? "method not allowed for this path" : "path not allowed";
    if (query != std::string_view::npos && !route->query) return "no query allowed for this path";

    switch (route->body) {
        case BodyRule::kNone:
            if (!body.isNull()) return "this request takes no body";
            break;
        case BodyRule::kSelectProxy: {
            if (!HasOnlyMember(body, "name")) return "body must be {\"name\": <proxy>}";
            const Json::Value& name = body["name"];
            if (!name.isString() || name.asString().empty() || name.asString().size() > 1024) {
                return "body must be {\"name\": <proxy>}";
            }
            break;
        }
        case BodyRule::kConfigPatch: {
            constexpr const char* kShape =
                    "body must be {\"mode\": \"rule\"|\"global\"|\"direct\"} or "
                    "{\"log-level\": \"debug\"|\"info\"|\"warning\"|\"error\"|\"silent\"}";
            if (HasOnlyMember(body, "mode")) {
                const Json::Value& mode = body["mode"];
                if (!mode.isString() || (mode.asString() != "rule" && mode.asString() != "global" &&
                                         mode.asString() != "direct")) {
                    return kShape;
                }
            } else if (HasOnlyMember(body, "log-level")) {
                const Json::Value& level = body["log-level"];
                if (!level.isString()) return kShape;
                const std::string l = level.asString();
                if (l != "debug" && l != "info" && l != "warning" && l != "error" && l != "silent")
                    return kShape;
            } else {
                return kShape;
            }
            break;
        }
    }
    return "";
}

std::chrono::milliseconds TimeoutFor(std::string_view method, std::string_view target,
                                     std::chrono::milliseconds normal) {
    if (method == "POST" && target == "/configs/geo") return 110s;
    // A provider update downloads its list first.
    constexpr std::string_view kRuleProviders = "/providers/rules/";
    constexpr std::string_view kProxyProviders = "/providers/proxies/";
    if (method == "PUT" && (target.substr(0, kRuleProviders.size()) == kRuleProviders ||
                            target.substr(0, kProxyProviders.size()) == kProxyProviders)) {
        return 55s;
    }
    return normal;
}

std::string CheckStream(std::string_view target) {
    if (target == "/traffic" || target == "/logs") return "";
    constexpr std::string_view kLogsPrefix = "/logs?level=";
    if (target.size() > kLogsPrefix.size() && target.substr(0, kLogsPrefix.size()) == kLogsPrefix) {
        const std::string_view level = target.substr(kLogsPrefix.size());
        for (std::string_view known : {"debug", "info", "warning", "error", "silent"}) {
            if (level == known) return "";
        }
    }
    return "stream path not allowed (/traffic, /logs?level=debug|info|warning|error|silent)";
}

Result<HttpReply> Call(std::string_view method, std::string_view target, std::string_view body,
                       std::chrono::milliseconds timeout) {
    auto request = BuildRequest(method, target, body);
    if (!request.ok()) return request.error();
    auto fd = Connect();
    if (!fd.ok()) return fd.error();
    return http::Exchange(fd->get(), *request, timeout, kMaxReply, kWhat);
}

Result<StreamEnd> Stream(std::string_view target, int client_fd,
                         const std::function<bool(std::string_view)>& on_line) {
    auto fd = Send("GET", target, "", kStreamSendTimeout);
    if (!fd.ok()) return fd.error();
    // No receive timeout: the controller sends its headers only with the
    // first line, and a log stream may stay quiet for hours.
    http::SetTimeouts(fd->get(), 0ms, kStreamSendTimeout);

    std::string head_data;
    http::Head head;
    bool streaming = false;
    http::ChunkedDecoder decoder;
    std::string pending;
    // Body bytes to lines; false: stop, the client is gone.
    auto deliver = [&](std::string_view raw) -> Result<bool> {
        std::string decoded;
        if (head.chunked) {
            if (!decoder.Feed(raw, &decoded))
                return Error() << "malformed chunked stream from the controller";
            raw = decoded;
        }
        pending.append(raw);
        size_t start = 0;
        size_t nl;
        while ((nl = pending.find('\n', start)) != std::string::npos) {
            std::string_view line(pending.data() + start, nl - start);
            start = nl + 1;
            if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
            if (!line.empty() && !on_line(line)) return false;
        }
        pending.erase(0, start);
        if (pending.size() > kMaxStreamLine) return Error() << "stream line longer than 1 MiB";
        return true;
    };

    char buf[16384];
    pollfd fds[2] = {{fd->get(), POLLIN, 0}, {client_fd, POLLIN | POLLRDHUP, 0}};
    while (true) {
        if (TEMP_FAILURE_RETRY(poll(fds, 2, -1)) < 0) return ErrnoError() << "poll";
        if (fds[1].revents != 0) {
            if (fds[1].revents & (POLLHUP | POLLRDHUP | POLLERR | POLLNVAL))
                return StreamEnd::kClientClosed;
            // The protocol has nothing after the request line: EOF or stray bytes.
            const ssize_t n = TEMP_FAILURE_RETRY(recv(client_fd, buf, sizeof(buf), MSG_DONTWAIT));
            if (n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK))
                return StreamEnd::kClientClosed;
        }
        if (fds[0].revents == 0) continue;
        const ssize_t n = TEMP_FAILURE_RETRY(recv(fd->get(), buf, sizeof(buf), 0));
        if (n < 0) return ErrnoError() << "reading the controller stream";
        if (n == 0) {
            if (!streaming)
                return Error() << "the controller closed the connection without a reply";
            return StreamEnd::kControllerClosed;
        }
        std::string_view body(buf, static_cast<size_t>(n));
        if (!streaming) {
            head_data.append(body);
            const int r = http::ParseHead(head_data, &head);
            if (r < 0) return Error() << "malformed reply from the controller";
            if (r == 0) continue;
            if (head.status != 200) return Error() << "the controller replied " << head.status;
            streaming = true;
            body = std::string_view(head_data).substr(head.length);
        }
        auto more = deliver(body);
        if (!more.ok()) return more.error();
        if (!*more) return StreamEnd::kClientClosed;
    }
}

}  // namespace lujarch::proxy::controller
