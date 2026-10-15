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

#include <android-base/logging.h>
#include <android-base/strings.h>
#include <android/binder_ibinder.h>
#include <lujarch/daemon.h>
#include <sys/socket.h>

#include <thread>

#include "controller.h"
#include "manager.h"
#include "supervisor.h"
#include "text.h"

namespace lujarch::proxy {

namespace {

using aidl::org::lujarch::proxy::IProxy;
using aidl::org::lujarch::proxy::IProxyListener;
using aidl::org::lujarch::proxy::Reply;
using android::base::unique_fd;
using namespace std::chrono_literals;

constexpr char kSettingsPackage[] = "org.lujarch.settings";
constexpr char kSystemUiPackage[] = "com.android.systemui";
constexpr auto kControllerTimeout = 20s;
constexpr auto kManagerTimeout = 300s;
// A binder reply holds at most 1 MiB with what else it carries.
constexpr size_t kMaxReply = 960u << 10;

ndk::ScopedAStatus Refused(const std::string& why) {
    LOG(WARNING) << "uid " << AIBinder_getCallingUid() << ": refused: " << why;
    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(IProxy::ERROR_REFUSED,
                                                                   why.c_str());
}

ndk::ScopedAStatus Failed(const std::string& why) {
    LOG(ERROR) << why;
    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(IProxy::ERROR_FAILED,
                                                                   why.c_str());
}

bool FromSettings() {
    return CallerIs(kSettingsPackage);
}

std::string LastLine(std::string_view text) {
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.remove_suffix(1);
    size_t newline = text.rfind('\n');
    return std::string(newline == std::string_view::npos ? text : text.substr(newline + 1));
}

}  // namespace

ndk::ScopedAStatus Service::getStatus(aidl::org::lujarch::proxy::Status* status) {
    if (!FromSettings() && !CallerIs(kSystemUiPackage)) return Refused("not an allowed caller");
    *status = TheSupervisor().Status();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::watch(const std::shared_ptr<IProxyListener>& listener) {
    if (!FromSettings() && !CallerIs(kSystemUiPackage)) return Refused("not an allowed caller");
    if (listener == nullptr) return Refused("no listener");
    TheSupervisor().Watch(listener);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::controller(const std::string& method, const std::string& path,
                                       const std::optional<std::string>& body, Reply* reply) {
    if (!FromSettings()) return Refused("not an allowed caller");
    Json::Value json;
    if (body && !android::base::Trim(*body).empty()) {
        std::string error;
        if (!ParseJson(*body, &json, &error)) return Refused("the body is not JSON: " + error);
    }
    if (std::string why = controller::CheckApi(method, path, json); !why.empty()) {
        return Refused(why + ": " + method + " " + SanitizeUtf8(path.substr(0, 256)));
    }
    auto answer = controller::Call(method, path, json.isNull() ? "" : ToJson(json),
                                   controller::TimeoutFor(method, path, kControllerTimeout));
    if (!answer.ok()) {
        LOG(WARNING) << method << " " << path << ": " << answer.error().message();
        return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                IProxy::ERROR_FAILED, answer.error().message().c_str());
    }
    if (answer->status >= 400) {
        LOG(WARNING) << method << " " << path << ": the controller replied " << answer->status;
    }
    if (answer->body.size() > kMaxReply) {
        return Failed(method + " " + path + ": the controller's reply is larger than " +
                      std::to_string(kMaxReply >> 10) + " KiB");
    }
    reply->status = answer->status;
    reply->body = SanitizeUtf8(answer->body);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::stream(const std::string& path, ndk::ScopedFileDescriptor* socket) {
    if (!FromSettings()) return Refused("not an allowed caller");
    if (std::string why = controller::CheckStream(path); !why.empty()) return Refused(why);
    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, fds) != 0) {
        return Failed(std::string("socketpair: ") + strerror(errno));
    }
    unique_fd ours(fds[0]);
    socket->set(fds[1]);
    std::thread([path, fd = std::move(ours)] {
        auto end = controller::Stream(path, fd.get(), [&fd](std::string_view line) {
            Json::Value data;
            std::string error;
            std::string out;
            if (!ParseJson(line, &data, &error)) {
                out = ToJson(Json::Value(SanitizeUtf8(line)));
            } else {
                // Log lines of failed subscription updates name the address.
                if (data.isObject() && std::as_const(data)["payload"].isString()) {
                    data["payload"] = RedactUrls(std::as_const(data)["payload"].asString());
                }
                out = ToJson(data);
            }
            return SendAll(fd.get(), out + "\n");
        });
        if (!end.ok()) LOG(WARNING) << "stream " << path << ": " << end.error().message();
    }).detach();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::manager(const std::vector<std::string>& args,
                                    const ndk::ScopedFileDescriptor& input, Reply* reply) {
    if (!FromSettings()) return Refused("not an allowed caller");
    if (std::string why = manager::CheckArgs(args); !why.empty()) return Refused(why);
    bool has_input = input.get() >= 0;
    if (manager::TakesInput(args) != has_input) {
        return Refused(has_input ? "this command of the manager takes no input"
                                 : "this command of the manager needs input");
    }
    std::unique_lock lock(manager_mutex_, std::try_to_lock);
    if (!lock.owns_lock()) {
        return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(IProxy::ERROR_BUSY, "busy");
    }
    // Arguments carry no secret: addresses and node links come on stdin.
    LOG(INFO) << "uid " << AIBinder_getCallingUid() << ": manager "
              << android::base::Join(args, ' ');
    auto output = manager::Run(args, kManagerTimeout, input.get());
    if (!output.ok()) return Failed(output.error().message());
    std::string text = RedactUrls(SanitizeUtf8(output->text));
    if (output->code != 0) {
        LOG(WARNING) << "manager " << args[0] << " exited with " << output->code << ": "
                     << LastLine(text);
    }
    reply->status = output->code;
    reply->body = std::move(text);
    reply->truncated = output->truncated;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::exportTo(bool secrets, const ndk::ScopedFileDescriptor& out,
                                     int32_t* code) {
    if (!FromSettings()) return Refused("not an allowed caller");
    if (out.get() < 0) return Refused("no file to export to");
    std::unique_lock lock(manager_mutex_, std::try_to_lock);
    if (!lock.owns_lock()) {
        return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(IProxy::ERROR_BUSY, "busy");
    }
    std::vector<std::string> args = {"export"};
    if (secrets) args.push_back("--secrets");
    auto output = manager::Run(args, kManagerTimeout, -1, out.get());
    if (!output.ok()) return Failed(output.error().message());
    // Only its messages are here; the export itself went to out.
    std::string messages = RedactUrls(SanitizeUtf8(output->text));
    if (output->code == 0) {
        LOG(INFO) << "uid " << AIBinder_getCallingUid() << ": proxy data exported ("
                  << (secrets ? "with" : "without")
                  << " subscription addresses and node passwords)";
    } else {
        LOG(WARNING) << "export exited with " << output->code << ": " << LastLine(messages);
    }
    *code = output->code;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::restart() {
    if (!FromSettings()) return Refused("not an allowed caller");
    LOG(INFO) << "uid " << AIBinder_getCallingUid() << ": restart";
    TheSupervisor().RequestRestart();
    return ndk::ScopedAStatus::ok();
}

}  // namespace lujarch::proxy
