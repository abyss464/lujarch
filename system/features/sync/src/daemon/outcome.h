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

// The result of a command of the daemon's interface: a reply, or a refusal or failure with its
// code, which the service turns into its answer.
#pragma once

#include <sys/types.h>

#include <string>
#include <utility>

#include <android-base/logging.h>
#include <json/value.h>

namespace lujarch::sync {

struct Outcome {
    enum class Kind { kOk, kRefused, kFailed };

    Kind kind = Kind::kOk;
    Json::Value body{Json::objectValue};  // kOk: the reply without "ok"
    std::string error;                    // kRefused, kFailed
    // kRefused, kFailed: a stable name of the condition (rest.h's kCode
    // constants), which the pages turn into a sentence in the owner's
    // language; empty when the kind says enough.
    std::string code;
    android::base::LogSeverity severity = android::base::DEBUG;

    static Outcome Ok(Json::Value body = Json::Value(Json::objectValue)) {
        Outcome o;
        o.body = std::move(body);
        return o;
    }
    // The request is not allowed or malformed.
    static Outcome Refused(std::string error, std::string code = {}) {
        Outcome o;
        o.kind = Kind::kRefused;
        o.error = std::move(error);
        o.code = std::move(code);
        o.severity = android::base::WARNING;
        return o;
    }
    // The request was valid and could not be carried out.
    static Outcome Failed(std::string error,
                          android::base::LogSeverity severity = android::base::ERROR,
                          std::string code = {}) {
        Outcome o;
        o.kind = Kind::kFailed;
        o.error = std::move(error);
        o.code = std::move(code);
        o.severity = severity;
        return o;
    }
    static Outcome Failed(std::string error, std::string code) {
        return Failed(std::move(error), android::base::ERROR, std::move(code));
    }
};

}  // namespace lujarch::sync
