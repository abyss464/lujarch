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

#include "http.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>

#include <android-base/strings.h>

#include "text.h"

namespace lujarch::proxy::http {

using android::base::ErrnoError;
using android::base::Error;
using android::base::Result;
using android::base::unique_fd;

namespace {

bool IsHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

bool ContainsIgnoreCase(std::string_view text, std::string_view word) {
    std::string lower(text);
    std::transform(lower.begin(), lower.end(), lower.begin(), [](char c) {
        return static_cast<char>(tolower(static_cast<unsigned char>(c)));
    });
    return lower.find(word) != std::string::npos;
}

// Finishes a non-blocking connect within timeout and makes fd blocking.
Result<void> FinishConnect(int fd, int connect_result, std::chrono::milliseconds timeout,
                           std::string_view what) {
    if (connect_result != 0) {
        if (errno != EINPROGRESS && errno != EAGAIN) return ErrnoError() << what << " unreachable";
        pollfd p{fd, POLLOUT, 0};
        const int r = TEMP_FAILURE_RETRY(poll(&p, 1, static_cast<int>(timeout.count())));
        if (r < 0) return ErrnoError() << "poll";
        if (r == 0) return Error() << what << " did not accept the connection";
        int err = 0;
        socklen_t len = sizeof(err);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len) != 0)
            return ErrnoError() << "SO_ERROR";
        if (err != 0) {
            errno = err;
            return ErrnoError() << what << " unreachable";
        }
    }
    const int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags & ~O_NONBLOCK) != 0) return ErrnoError() << "fcntl";
    return {};
}

}  // namespace

bool IsTargetChar(char c) {
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return true;
    return std::string_view("-._~!$&'()*+,;=:@/?%").find(c) != std::string_view::npos;
}

std::string CheckTarget(std::string_view target, size_t max_size) {
    if (target.empty() || target[0] != '/') return "path must start with /";
    if (target.size() > max_size) return "path too long";
    for (size_t i = 0; i < target.size(); ++i) {
        const char c = target[i];
        if (!IsTargetChar(c)) return "path contains a character that must be percent-encoded";
        if (c == '%' &&
            (i + 2 >= target.size() || !IsHex(target[i + 1]) || !IsHex(target[i + 2]))) {
            return "path contains a malformed percent-encoding";
        }
    }
    return "";
}

bool SplitPath(std::string_view path, std::vector<std::string_view>* segments) {
    segments->clear();
    if (path.empty() || path[0] != '/') return false;
    size_t pos = 1;
    while (true) {
        size_t end = path.find('/', pos);
        if (end == std::string_view::npos) end = path.size();
        const std::string_view segment = path.substr(pos, end - pos);
        if (segment.empty() || segment == "." || segment == "..") return false;
        segments->push_back(segment);
        if (end == path.size()) return true;
        pos = end + 1;
    }
}

bool Matches(std::string_view pattern, const std::vector<std::string_view>& segments) {
    std::vector<std::string_view> parts;
    if (!SplitPath(pattern, &parts) || parts.size() != segments.size()) return false;
    for (size_t i = 0; i < parts.size(); ++i) {
        if (parts[i] != "*" && parts[i] != segments[i]) return false;
    }
    return true;
}

int ParseHead(std::string_view data, Head* head) {
    constexpr size_t kMaxHead = 64u << 10;
    const size_t end = data.find("\r\n\r\n");
    if (end == std::string_view::npos) return data.size() > kMaxHead ? -1 : 0;
    const std::string_view text = data.substr(0, end);
    const size_t eol = text.find("\r\n");
    const std::string_view status_line = text.substr(0, eol);
    // "HTTP/1.x NNN reason"
    if (status_line.size() < 12 || status_line.substr(0, 7) != "HTTP/1." || status_line[8] != ' ') {
        return -1;
    }
    int status = 0;
    for (size_t i = 9; i < 12; ++i) {
        if (status_line[i] < '0' || status_line[i] > '9') return -1;
        status = status * 10 + (status_line[i] - '0');
    }
    head->status = status;
    head->chunked = false;
    head->length = end + 4;
    std::string_view rest =
            eol == std::string_view::npos ? std::string_view() : text.substr(eol + 2);
    while (!rest.empty()) {
        size_t line_end = rest.find("\r\n");
        if (line_end == std::string_view::npos) line_end = rest.size();
        const std::string_view line = rest.substr(0, line_end);
        rest = line_end == rest.size() ? std::string_view() : rest.substr(line_end + 2);
        const size_t colon = line.find(':');
        if (colon == std::string_view::npos) continue;
        const std::string name = android::base::Trim(std::string(line.substr(0, colon)));
        if (android::base::EqualsIgnoreCase(name, "Transfer-Encoding") &&
            ContainsIgnoreCase(line.substr(colon + 1), "chunked")) {
            head->chunked = true;
        }
    }
    return 1;
}

bool ChunkedDecoder::Feed(std::string_view input, std::string* out) {
    size_t i = 0;
    while (i < input.size()) {
        switch (state_) {
            case State::kSize: {
                const char c = input[i++];
                if (c != '\n') {
                    line_.push_back(c);
                    if (line_.size() > 64) return false;
                    break;
                }
                std::string_view size_text = line_;
                if (!size_text.empty() && size_text.back() == '\r') size_text.remove_suffix(1);
                size_text = size_text.substr(0, size_text.find(';'));  // chunk extensions
                while (!size_text.empty() && size_text.back() == ' ') size_text.remove_suffix(1);
                if (size_text.empty() || size_text.size() > 8) return false;
                size_t size = 0;
                for (char h : size_text) {
                    if (!IsHex(h)) return false;
                    size = size * 16 +
                           static_cast<size_t>(h <= '9' ? h - '0' : (h | 0x20) - 'a' + 10);
                }
                line_.clear();
                if (size == 0) {
                    state_ = State::kDone;
                } else {
                    remaining_ = size;
                    state_ = State::kData;
                }
                break;
            }
            case State::kData: {
                const size_t n = std::min(remaining_, input.size() - i);
                out->append(input.substr(i, n));
                i += n;
                remaining_ -= n;
                if (remaining_ == 0) state_ = State::kDataEnd;
                break;
            }
            case State::kDataEnd: {
                const char c = input[i++];
                if (c == '\r') break;
                if (c != '\n') return false;
                state_ = State::kSize;
                break;
            }
            case State::kDone:
                return true;  // trailers are ignored
        }
    }
    return true;
}

Result<unique_fd> ConnectLoopback(uint16_t port, std::chrono::milliseconds timeout,
                                  std::string_view what) {
    unique_fd fd(socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0));
    if (fd < 0) return ErrnoError() << "socket";
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    const int r = connect(fd.get(), reinterpret_cast<const sockaddr*>(&addr), sizeof(addr));
    if (auto done = FinishConnect(fd.get(), r, timeout, what); !done.ok()) return done.error();
    return fd;
}

Result<unique_fd> Connect(const sockaddr* addr, socklen_t len, std::chrono::milliseconds timeout,
                          std::string_view what) {
    unique_fd fd(socket(addr->sa_family, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0));
    if (fd < 0) return ErrnoError() << "socket";
    const int r = connect(fd.get(), addr, len);
    if (auto done = FinishConnect(fd.get(), r, timeout, what); !done.ok()) return done.error();
    return fd;
}

void SetTimeouts(int fd, std::chrono::milliseconds recv_timeout,
                 std::chrono::milliseconds send_timeout) {
    auto to_timeval = [](std::chrono::milliseconds ms) {
        timeval tv{};
        tv.tv_sec = static_cast<time_t>(ms.count() / 1000);
        tv.tv_usec = static_cast<suseconds_t>((ms.count() % 1000) * 1000);
        return tv;
    };
    const timeval rcv = to_timeval(recv_timeout);
    const timeval snd = to_timeval(send_timeout);
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &rcv, sizeof(rcv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &snd, sizeof(snd));
}

std::string BuildRequest(std::string_view method, std::string_view target, std::string_view host,
                         const std::vector<std::string>& headers, std::string_view body) {
    std::string request;
    request.append(method).append(" ").append(target).append(" HTTP/1.0\r\n");
    request.append("Host: ").append(host).append("\r\n");
    for (const std::string& header : headers) request.append(header).append("\r\n");
    if (!body.empty()) {
        request.append("Content-Type: application/json\r\n");
        request.append("Content-Length: ").append(std::to_string(body.size())).append("\r\n");
    } else if (method != "GET") {
        request.append("Content-Length: 0\r\n");
    }
    request.append("\r\n").append(body);
    return request;
}

Result<Reply> Exchange(int fd, std::string_view request, std::chrono::milliseconds timeout,
                       size_t max_reply, std::string_view what) {
    SetTimeouts(fd, timeout, timeout);
    if (!SendAll(fd, request)) return ErrnoError() << "sending to " << what;
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    std::string data;
    char buf[16384];
    while (true) {
        const ssize_t n = TEMP_FAILURE_RETRY(recv(fd, buf, sizeof(buf), 0));
        if (n == 0) break;
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                return Error() << what << " did not reply within " << timeout.count() / 1000
                               << " s";
            }
            return ErrnoError() << "reading from " << what;
        }
        data.append(buf, static_cast<size_t>(n));
        if (data.size() > max_reply)
            return Error() << what << " reply larger than " << (max_reply >> 20) << " MiB";
        if (std::chrono::steady_clock::now() > deadline) {
            return Error() << what << " did not finish its reply within " << timeout.count() / 1000
                           << " s";
        }
    }
    Head head;
    if (ParseHead(data, &head) != 1) return Error() << "malformed reply from " << what;
    Reply reply;
    reply.status = head.status;
    if (head.chunked) {
        ChunkedDecoder decoder;
        if (!decoder.Feed(std::string_view(data).substr(head.length), &reply.body)) {
            return Error() << "malformed chunked reply from " << what;
        }
    } else {
        reply.body = data.substr(head.length);
    }
    return reply;
}

}  // namespace lujarch::proxy::http
