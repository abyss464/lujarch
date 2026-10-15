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

#include "service.h"

#include <aidl/org/lujarch/sync/ISync.h>
#include <android-base/logging.h>
#include <android-base/strings.h>
#include <android/binder_ibinder.h>
#include <lujarch/daemon.h>
#include <lujarch/text.h>
#include <openssl/mem.h>

#include <chrono>
#include <map>

#include "conditions.h"
#include "identity.h"
#include "outcome.h"
#include "rest.h"
#include "supervisor.h"
#include "vault_files.h"

namespace lujarch::sync {

namespace {

using aidl::org::lujarch::sync::ISync;
using aidl::org::lujarch::sync::Reply;
using namespace std::chrono_literals;

constexpr char kSettingsPackage[] = "org.lujarch.settings";
constexpr char kVaultPackage[] = "org.lujarch.vault";
constexpr auto kApiTimeout = 30s;
constexpr auto kInfoTimeout = 3s;
// A binder reply holds at most 1 MiB with what else it carries.
constexpr size_t kMaxReply = 960u << 10;

int ErrorCode(const std::string& code, bool refused) {
    static const std::map<std::string, int> kCodes = {
            {rest::kCodeNotAllowed, ISync::ERROR_NOT_ALLOWED},
            {rest::kCodeStopped, ISync::ERROR_STOPPED},
            {rest::kCodeUnreachable, ISync::ERROR_UNREACHABLE},
            {rest::kCodeFolderInUse, ISync::ERROR_FOLDER_IN_USE},
            {rest::kCodeNoFolder, ISync::ERROR_NO_FOLDER},
            {rest::kCodeNotMovable, ISync::ERROR_NOT_MOVABLE},
            {rest::kCodeOverrideDeletes, ISync::ERROR_OVERRIDE_DELETES},
            {rest::kCodeVersionsInFolder, ISync::ERROR_VERSIONS_IN_FOLDER},
    };
    auto it = kCodes.find(code);
    if (it != kCodes.end()) return it->second;
    return refused ? ISync::ERROR_REFUSED : ISync::ERROR_FAILED;
}

ndk::ScopedAStatus Error(int code, const std::string& why,
                         android::base::LogSeverity severity = android::base::WARNING) {
    LOG(severity) << "uid " << AIBinder_getCallingUid() << ": " << why;
    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(code, why.c_str());
}

ndk::ScopedAStatus Refused(const std::string& why) {
    return Error(ISync::ERROR_REFUSED, why);
}

// An outcome as the answer: its body as JSON text, or the exception of its code.
ndk::ScopedAStatus Answer(const Outcome& outcome, std::string* json) {
    if (outcome.kind == Outcome::Kind::kOk) {
        *json = text::ToJson(outcome.body);
        return ndk::ScopedAStatus::ok();
    }
    return Error(ErrorCode(outcome.code, outcome.kind == Outcome::Kind::kRefused), outcome.error,
                 outcome.severity);
}

bool FromSettings() {
    return CallerIs(kSettingsPackage);
}

// A REST body as a JSON value when it is JSON, else as a string.
Json::Value BodyValue(const std::string& body) {
    if (android::base::Trim(body).empty()) return Json::Value();
    Json::Value value;
    std::string error;
    if (text::ParseJson(body, &value, &error)) return value;
    return Json::Value(text::SanitizeUtf8(body));
}

std::string Text(const std::vector<uint8_t>& bytes) {
    return std::string(bytes.begin(), bytes.end());
}

}  // namespace

ndk::ScopedAStatus Service::getStatus(aidl::org::lujarch::sync::Status* status) {
    if (!FromSettings()) return Refused("not an allowed caller");
    *status = TheSupervisor().Status();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::api(const std::string& method, const std::string& path,
                                const std::optional<std::string>& body, Reply* reply) {
    if (!FromSettings()) return Refused("not an allowed caller");
    Json::Value json;
    if (body && !android::base::Trim(*body).empty()) {
        std::string error;
        if (!text::ParseJson(*body, &json, &error))
            return Refused("the body is not JSON: " + error);
    }
    if (std::string why = rest::CheckApi(method, path, json); !why.empty()) {
        return Error(ISync::ERROR_NOT_ALLOWED,
                     why + ": " + method + " " + text::SanitizeUtf8(path.substr(0, 256)));
    }
    // Folder changes keep what the pages never see (encryption passwords).
    std::string code;
    if (std::string why = rest::Prepare(method, path, &json, &code); !why.empty()) {
        return Error(ErrorCode(code, true), why);
    }
    auto answer = rest::Call(method, path, json.isNull() ? "" : text::ToJson(json), kApiTimeout);
    if (!answer.ok()) {
        return Error(ErrorCode(rest::FailureCode(), false), answer.error().message());
    }
    if (answer->status >= 400) {
        LOG(WARNING) << method << " " << path << ": the engine replied " << answer->status;
    }
    if (method != "GET") {
        LOG(INFO) << "uid " << AIBinder_getCallingUid() << ": " << method << " " << path;
    }
    Json::Value value = BodyValue(answer->body);
    rest::Redact(&value);
    std::string out = text::ToJson(value);
    if (out.size() > kMaxReply) {
        return Error(ISync::ERROR_FAILED, method + " " + path +
                                                  ": the engine's reply is larger than " +
                                                  std::to_string(kMaxReply >> 10) + " KiB");
    }
    reply->status = answer->status;
    reply->body = std::move(out);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::dirs(std::string* json) {
    if (!FromSettings()) return Refused("not an allowed caller");
    return Answer(rest::CmdDirs(Json::Value(Json::objectValue), AIBinder_getCallingUid()), json);
}

ndk::ScopedAStatus Service::ignored(const std::string& op, const std::optional<std::string>& device,
                                    const std::optional<std::string>& name,
                                    const std::optional<std::string>& address, std::string* json) {
    if (!FromSettings()) return Refused("not an allowed caller");
    Json::Value request(Json::objectValue);
    request["op"] = op;
    if (device) request["device"] = *device;
    if (name) request["name"] = *name;
    if (address) request["address"] = *address;
    return Answer(rest::CmdIgnored(request, AIBinder_getCallingUid()), json);
}

ndk::ScopedAStatus Service::conditions(std::string* json) {
    if (!FromSettings()) return Refused("not an allowed caller");
    *json = text::ToJson(conditions::Describe());
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::moveFolder(const std::string& folder, const std::string& dir,
                                       std::string* json) {
    if (!FromSettings()) return Refused("not an allowed caller");
    Json::Value request(Json::objectValue);
    request["folder"] = folder;
    request["dir"] = dir;
    return Answer(rest::CmdFolderMove(request, AIBinder_getCallingUid()), json);
}

ndk::ScopedAStatus Service::identity(std::string* json) {
    if (!FromSettings()) return Refused("not an allowed caller");
    return Answer(identity::CmdStatus(Json::Value(Json::objectValue), AIBinder_getCallingUid()),
                  json);
}

ndk::ScopedAStatus Service::importIdentity(const std::vector<uint8_t>& cert,
                                           const std::vector<uint8_t>& key,
                                           const std::optional<std::vector<uint8_t>>& config,
                                           bool keepConfig, std::string* json) {
    if (!FromSettings()) return Refused("not an allowed caller");
    Json::Value request(Json::objectValue);
    request["cert"] = Text(cert);
    request["key"] = Text(key);
    if (config) request["config"] = Text(*config);
    request["keep_config"] = keepConfig;
    ndk::ScopedAStatus answer =
            Answer(identity::CmdImport(request, AIBinder_getCallingUid()), json);
    for (const char* field : {"key", "config"}) {
        if (request[field].isString()) {
            std::string secret = request[field].asString();
            OPENSSL_cleanse(secret.data(), secret.size());
        }
    }
    return answer;
}

ndk::ScopedAStatus Service::generateIdentity(bool keepConfig, std::string* json) {
    if (!FromSettings()) return Refused("not an allowed caller");
    Json::Value request(Json::objectValue);
    request["keep_config"] = keepConfig;
    return Answer(identity::CmdGenerate(request, AIBinder_getCallingUid()), json);
}

ndk::ScopedAStatus Service::restoreIdentity(const std::string& backup, std::string* json) {
    if (!FromSettings()) return Refused("not an allowed caller");
    Json::Value request(Json::objectValue);
    request["backup"] = backup;
    return Answer(identity::CmdRestore(request, AIBinder_getCallingUid()), json);
}

ndk::ScopedAStatus Service::vaultFind(std::string* json) {
    if (!CallerIs(kVaultPackage)) return Refused("not an allowed caller");
    auto found = vault::Find();
    if (!found.ok()) return Error(ISync::ERROR_FAILED, found.error().message());
    // Labels as the Sync page shows them; without the engine the directory names stand in.
    std::map<std::string, std::string> labels;
    auto config = rest::Call("GET", "/rest/config/folders", "", kInfoTimeout);
    if (config.ok() && config->status == 200) {
        labels = vault::FolderLabels(BodyValue(config->body));
    } else {
        LOG(INFO) << "vaultFind: no folder labels from the engine: "
                  << (config.ok() ? "HTTP " + std::to_string(config->status)
                                  : config.error().message());
    }
    Json::Value folders(Json::arrayValue);
    for (const std::string& name : found->folders) {
        Json::Value folder(Json::objectValue);
        folder["folder"] = name;
        const auto label = labels.find(name);
        folder["label"] = text::SanitizeUtf8(label == labels.end() ? name : label->second);
        folders.append(folder);
    }
    Json::Value files(Json::arrayValue);
    for (const vault::Found& f : found->files) {
        Json::Value file(Json::objectValue);
        file["folder"] = f.folder;
        file["name"] = f.path;
        file["size"] = Json::UInt64(f.size);
        file["mtime"] = Json::Int64(f.mtime_ms);
        files.append(file);
    }
    // Databases that cannot be opened are listed with the reason, so the vault shows them.
    Json::Value skipped(Json::arrayValue);
    for (const vault::Skipped& f : found->skipped) {
        Json::Value file(Json::objectValue);
        file["folder"] = f.folder;
        file["name"] = text::SanitizeUtf8(f.path);
        file["reason"] = vault::SkipReasonName(f.reason);
        file["size"] = Json::UInt64(f.size);
        skipped.append(file);
    }
    Json::Value reply(Json::objectValue);
    reply["folders"] = folders;
    reply["files"] = files;
    reply["skipped"] = skipped;
    reply["truncated"] = found->truncated;
    *json = text::ToJson(reply);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::vaultOpen(const std::string& folder, const std::string& path,
                                      ndk::ScopedFileDescriptor* fd) {
    if (!CallerIs(kVaultPackage)) return Refused("not an allowed caller");
    if (!vault::IsFileName(folder)) return Refused("invalid folder name");
    if (!vault::IsRelativePath(path) || !vault::IsDatabaseName(path)) {
        return Refused("invalid database path");
    }
    auto file = vault::Open(path, vault::FolderPath(folder));
    if (!file.ok()) return Error(ISync::ERROR_FAILED, file.error().message());
    fd->set(file->release());
    return ndk::ScopedAStatus::ok();
}

}  // namespace lujarch::sync
