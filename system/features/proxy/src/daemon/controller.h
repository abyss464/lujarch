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

// Requests to the core's controller (127.0.0.1:9090), signed here with the control key so that no
// app sees it.
#pragma once

#include <chrono>
#include <functional>
#include <string>
#include <string_view>

#include <android-base/result.h>
#include <json/value.h>

#include "http.h"

namespace lujarch::proxy::controller {

// Checks a request against the allowlist: what Lujarch's pages ask of the controller. Returns why
// it is refused, or "" when allowed. body is null when the request has none.
std::string CheckApi(std::string_view method, std::string_view target, const Json::Value& body);

// How long a request may take: normal, except the geodata update (POST /configs/geo downloads tens
// of MiB before it answers) and provider updates.
std::chrono::milliseconds TimeoutFor(std::string_view method, std::string_view target,
                                     std::chrono::milliseconds normal);

// Checks a stream path: /traffic or /logs?level=<level>.
std::string CheckStream(std::string_view target);

using HttpReply = http::Reply;

// One request; the reply is read until the controller closes (HTTP/1.0).
android::base::Result<HttpReply> Call(std::string_view method, std::string_view target,
                                      std::string_view body, std::chrono::milliseconds timeout);

enum class StreamEnd { kClientClosed, kControllerClosed };

// GET target and hand every body line to on_line until the client hangs up
// (client_fd), on_line returns false, or the controller ends the stream.
android::base::Result<StreamEnd> Stream(std::string_view target, int client_fd,
                                        const std::function<bool(std::string_view)>& on_line);

}  // namespace lujarch::proxy::controller
