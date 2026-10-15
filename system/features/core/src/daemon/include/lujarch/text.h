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

#pragma once

#include <json/value.h>

#include <string>
#include <string_view>

namespace lujarch::text {

// Every scheme://... in text replaced by <url>: subscription and node addresses carry access
// keys, and the text goes to an app.
std::string RedactUrls(std::string_view text);

// Invalid UTF-8 replaced by U+FFFD.
std::string SanitizeUtf8(std::string_view text);

// Compact JSON on one line.
std::string ToJson(const Json::Value& value);

// Strict JSON parsing: no comments, no trailing data, bounded nesting. Returns false with a reason
// on error.
bool ParseJson(std::string_view text, Json::Value* out, std::string* error);

// Sends all of data without SIGPIPE; false (errno set) when the peer is gone.
bool SendAll(int fd, std::string_view data);

// A package name as Android accepts it: [A-Za-z0-9_.], at most 255 bytes, no leading or trailing
// dot.
bool IsPackageName(std::string_view name);

}  // namespace lujarch::text
