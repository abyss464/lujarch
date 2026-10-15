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

// The HTTP client of the controller and the probes. One HTTP/1.0 request per connection: the
// server replies without chunked encoding and closes the connection after the reply.
#pragma once

#include <chrono>
#include <string>
#include <string_view>
#include <vector>

#include <android-base/result.h>
#include <android-base/unique_fd.h>
#include <sys/socket.h>

namespace lujarch::proxy::http {

// RFC 3986 unreserved, sub-delims, ":", "@", "/", "?" and "%": no spaces,
// controls, quotes or non-ASCII bytes, which keeps the request line intact.
// Names with such characters come percent-encoded.
bool IsTargetChar(char c);

// Checks the characters and percent-encodings of a request target
// ("/path?query"). Returns why it is refused, or "" when well-formed.
std::string CheckTarget(std::string_view target, size_t max_size);

// "/a/b" -> {"a", "b"}; false for empty, "." or ".." segments.
bool SplitPath(std::string_view path, std::vector<std::string_view>* segments);

// pattern "/a/*/c": "*" matches one segment.
bool Matches(std::string_view pattern, const std::vector<std::string_view>& segments);

struct Head {
    int status = 0;
    bool chunked = false;
    size_t length = 0;  // bytes up to and including the blank line
};

// 1: complete, 0: more data needed, -1: malformed.
int ParseHead(std::string_view data, Head* head);

// Transfer-Encoding: chunked, decoded incrementally. Servers have no reason
// to use it for HTTP/1.0 requests; this keeps replies readable if one does.
class ChunkedDecoder {
  public:
    // Appends the decoded bytes of input to out; false on malformed input.
    bool Feed(std::string_view input, std::string* out);
    bool finished() const { return state_ == State::kDone; }

  private:
    enum class State { kSize, kData, kDataEnd, kDone };
    State state_ = State::kSize;
    std::string line_;
    size_t remaining_ = 0;
};

struct Reply {
    int status = 0;
    std::string body;
};

// Connections with a connect timeout; `what` names the server in errors.
android::base::Result<android::base::unique_fd> ConnectLoopback(uint16_t port,
                                                                std::chrono::milliseconds timeout,
                                                                std::string_view what);
// A connection to addr (IPv4 or IPv6).
android::base::Result<android::base::unique_fd> Connect(const sockaddr* addr, socklen_t len,
                                                        std::chrono::milliseconds timeout,
                                                        std::string_view what);

// Receive and send timeouts of a connected socket.
void SetTimeouts(int fd, std::chrono::milliseconds recv_timeout,
                 std::chrono::milliseconds send_timeout);

// "METHOD target HTTP/1.0" with Host, the extra header lines ("Name: value",
// no CRLF) and, for a body, Content-Type application/json.
std::string BuildRequest(std::string_view method, std::string_view target, std::string_view host,
                         const std::vector<std::string>& headers, std::string_view body);

// Sends request on fd and reads the reply until the server closes the
// connection, the reply exceeds max_reply bytes or timeout passes.
android::base::Result<Reply> Exchange(int fd, std::string_view request,
                                      std::chrono::milliseconds timeout, size_t max_reply,
                                      std::string_view what);

}  // namespace lujarch::proxy::http
