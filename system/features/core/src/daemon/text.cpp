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

#include "lujarch/text.h"

#include <errno.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <memory>

#include <json/reader.h>
#include <json/writer.h>

namespace lujarch::text {
namespace {

bool IsAlpha(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
}

bool IsAlnum(char c) {
    return IsAlpha(c) || (c >= '0' && c <= '9');
}

// RFC 3986: scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
bool IsSchemeChar(char c) {
    return IsAlnum(c) || c == '+' || c == '-' || c == '.';
}

// A URL in manager output ends at whitespace, a quote or an angle bracket
// (YAML, JSON and prose all delimit URLs that way).
bool EndsUrl(char c) {
    const auto u = static_cast<unsigned char>(c);
    return u <= 0x20 || u == 0x7f || c == '"' || c == '\'' || c == '<' || c == '>' || c == '`';
}

}  // namespace

std::string RedactUrls(std::string_view text) {
    std::string out;
    out.reserve(text.size());
    size_t i = 0;
    while (i < text.size()) {
        const size_t sep = text.find("://", i);
        if (sep == std::string_view::npos) {
            out.append(text.substr(i));
            break;
        }
        size_t start = sep;
        while (start > i && IsSchemeChar(text[start - 1])) --start;
        while (start < sep && !IsAlpha(text[start])) ++start;
        if (start == sep) {
            // "://" with no scheme in front of it is not a URL.
            out.append(text.substr(i, sep + 3 - i));
            i = sep + 3;
            continue;
        }
        size_t end = sep + 3;
        while (end < text.size() && !EndsUrl(text[end])) ++end;
        out.append(text.substr(i, start - i));
        out.append("<url>");
        i = end;
    }
    return out;
}

std::string SanitizeUtf8(std::string_view text) {
    static constexpr std::string_view kReplacement = "\xEF\xBF\xBD";
    std::string out;
    out.reserve(text.size());
    size_t i = 0;
    while (i < text.size()) {
        const auto c = static_cast<unsigned char>(text[i]);
        if (c < 0x80) {
            out.push_back(text[i]);
            ++i;
            continue;
        }
        // Lead byte: sequence length and the range of the second byte
        // (excludes overlong forms, surrogates and code points > U+10FFFF).
        size_t len = 0;
        unsigned char lo = 0x80, hi = 0xBF;
        if (c >= 0xC2 && c <= 0xDF) {
            len = 2;
        } else if (c >= 0xE0 && c <= 0xEF) {
            len = 3;
            if (c == 0xE0) lo = 0xA0;
            if (c == 0xED) hi = 0x9F;
        } else if (c >= 0xF0 && c <= 0xF4) {
            len = 4;
            if (c == 0xF0) lo = 0x90;
            if (c == 0xF4) hi = 0x8F;
        }
        bool valid = len != 0 && i + len <= text.size();
        for (size_t k = 1; valid && k < len; ++k) {
            const auto b = static_cast<unsigned char>(text[i + k]);
            valid = k == 1 ? (b >= lo && b <= hi) : (b >= 0x80 && b <= 0xBF);
        }
        if (valid) {
            out.append(text.substr(i, len));
            i += len;
        } else {
            out.append(kReplacement);
            ++i;
        }
    }
    return out;
}

std::string ToJson(const Json::Value& value) {
    Json::StreamWriterBuilder builder;
    builder["indentation"] = "";
    builder["emitUTF8"] = true;
    return Json::writeString(builder, value);
}

bool ParseJson(std::string_view text, Json::Value* out, std::string* error) {
    // AOSP builds jsoncpp without exceptions, so its own nesting limit
    // aborts the process. Deep nesting is refused here first; requests are
    // flat and controller replies nest a few levels.
    constexpr int kMaxDepth = 64;
    int depth = 0;
    bool in_string = false;
    bool escaped = false;
    for (char c : text) {
        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                in_string = false;
            }
        } else if (c == '"') {
            in_string = true;
        } else if (c == '[' || c == '{') {
            if (++depth > kMaxDepth) {
                *error = "nested deeper than " + std::to_string(kMaxDepth) + " levels";
                return false;
            }
        } else if (c == ']' || c == '}') {
            --depth;
        }
    }
    Json::CharReaderBuilder builder;
    Json::CharReaderBuilder::strictMode(&builder.settings_);
    std::unique_ptr<Json::CharReader> reader(builder.newCharReader());
    return reader->parse(text.data(), text.data() + text.size(), out, error);
}

bool SendAll(int fd, std::string_view data) {
    while (!data.empty()) {
        const ssize_t n = TEMP_FAILURE_RETRY(send(fd, data.data(), data.size(), MSG_NOSIGNAL));
        if (n < 0) return false;
        data.remove_prefix(static_cast<size_t>(n));
    }
    return true;
}

bool IsPackageName(std::string_view name) {
    if (name.empty() || name.size() > 255 || name.front() == '.' || name.back() == '.') {
        return false;
    }
    for (char c : name) {
        if (!IsAlnum(c) && c != '_' && c != '.') return false;
    }
    return true;
}

}  // namespace lujarch::text
