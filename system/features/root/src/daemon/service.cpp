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
#include <lujarch/aid.h>
#include <lujarch/daemon.h>

#include "ksu.h"

namespace lujarch::root {

namespace {

using aidl::org::lujarch::root::AppRoot;
using aidl::org::lujarch::root::IRoot;
using aidl::org::lujarch::root::RootInfo;
using aidl::org::lujarch::root::RootProfile;

constexpr char kSettingsPackage[] = "org.lujarch.settings";
// KernelSU's allowlist names every profile; this one belongs to no app.
constexpr char kLinuxRootKey[] = "lujarch.linux.root";
// Lujarch's own apps never get root.
constexpr char kOwnPrefix[] = "org.lujarch.";

ndk::ScopedAStatus Error(int code, const std::string& why,
                         android::base::LogSeverity severity = android::base::WARNING) {
    LOG(severity) << "uid " << AIBinder_getCallingUid() << ": " << why;
    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(code, why.c_str());
}

// Only Lujarch Settings reads and changes root.
bool Allowed(ndk::ScopedAStatus* refusal) {
    if (CallerIs(kSettingsPackage)) return true;
    *refusal = Error(IRoot::ERROR_REFUSED, "only Lujarch Settings may use lujarch_root");
    return false;
}

// The installed package's uid, or the refusal.
bool UidOf(const std::string& package, int32_t* uid, ndk::ScopedAStatus* refusal) {
    auto found = PackageUid(package);
    if (!found) {
        *refusal = Error(IRoot::ERROR_NOT_INSTALLED, package + " is not installed");
        return false;
    }
    *uid = static_cast<int32_t>(*found);
    return true;
}

// Whether the package may be given root at all.
bool MayHaveRoot(const std::string& package, int32_t uid, ndk::ScopedAStatus* refusal) {
    if (android::base::StartsWith(package, kOwnPrefix)) {
        *refusal = Error(IRoot::ERROR_NEVER_GRANTED, package + " is Lujarch's own app");
        return false;
    }
    if (!ksu::KernelAllowsRoot(uid)) {
        *refusal = Error(IRoot::ERROR_NEVER_GRANTED,
                         "KernelSU never grants root to uid " + std::to_string(uid));
        return false;
    }
    return true;
}

template <typename E>
ndk::ScopedAStatus Failure(const E& error) {
    const std::string why = error.message();
    // The driver is missing or speaks another uapi: the kernel has no usable KernelSU.
    auto info = ksu::GetInfo();
    if (!info.ok()) return Error(IRoot::ERROR_UNAVAILABLE, why);
    return Error(IRoot::ERROR_FAILED, why, android::base::ERROR);
}

RootProfile ToAidl(bool allowed, bool custom, const ksu::Fields& f) {
    RootProfile p;
    p.allowed = allowed;
    p.custom = custom;
    p.uid = f.uid;
    p.gid = f.gid;
    p.groups = f.groups;
    p.capabilities = static_cast<int64_t>(f.capabilities);
    p.domain = f.domain;
    p.mountNamespace = static_cast<int32_t>(f.ns);
    p.noNewPrivs = f.no_new_privs;
    return p;
}

ksu::Fields FromAidl(const RootProfile& p) {
    ksu::Fields f;
    f.uid = p.uid;
    f.gid = p.gid;
    f.groups = p.groups;
    f.capabilities = static_cast<uint64_t>(p.capabilities);
    f.domain = p.domain;
    f.ns = static_cast<ksu::Namespace>(p.mountNamespace);
    f.no_new_privs = p.noNewPrivs;
    return f;
}

void Changed(const std::string& what, const std::string& package) {
    LOG(INFO) << "uid " << AIBinder_getCallingUid() << ": " << what << " for " << package;
}

}  // namespace

ndk::ScopedAStatus Service::getInfo(RootInfo* out) {
    ndk::ScopedAStatus refusal;
    if (!Allowed(&refusal)) return refusal;
    auto info = ksu::GetInfo();
    out->available = info.ok();
    if (info.ok()) {
        out->version = info->tag.empty() ? std::to_string(info->version) : info->tag;
    } else {
        out->reason = info.error().message();
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::list(std::vector<AppRoot>* out) {
    ndk::ScopedAStatus refusal;
    if (!Allowed(&refusal)) return refusal;
    auto entries = ksu::List();
    if (!entries.ok()) return Failure(entries.error());
    out->clear();
    for (const ksu::Entry& e : *entries) {
        if (e.uid == static_cast<int32_t>(aid::kLinuxRoot)) continue;
        AppRoot app;
        app.packageName = e.key;
        app.uid = e.uid;
        app.allowed = e.allow_su;
        app.custom = e.custom;
        out->push_back(std::move(app));
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::getProfile(const std::string& package, RootProfile* out) {
    ndk::ScopedAStatus refusal;
    int32_t uid;
    if (!Allowed(&refusal) || !UidOf(package, &uid, &refusal)) return refusal;
    auto stored = ksu::Get(uid);
    if (!stored.ok()) return Failure(stored.error());
    const bool allowed = stored->has_value() && (*stored)->allow_su;
    if (!allowed) {
        *out = ToAidl(false, false, ksu::DefaultFields());
    } else {
        const ksu::AppProfile& p = **stored;
        *out = ToAidl(true, !p.rp_config.use_default, ksu::FieldsOf(p));
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setAllowed(const std::string& package, bool allowed) {
    ndk::ScopedAStatus refusal;
    int32_t uid;
    if (!Allowed(&refusal) || !UidOf(package, &uid, &refusal)) return refusal;
    if (allowed && !MayHaveRoot(package, uid, &refusal)) return refusal;
    auto changed = ksu::SetAllowSu(package, uid, allowed);
    if (!changed.ok()) return Failure(changed.error());
    if (*changed) Changed(allowed ? "allowed root" : "refused root", package);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setProfile(const std::string& package, const RootProfile& profile) {
    ndk::ScopedAStatus refusal;
    int32_t uid;
    if (!Allowed(&refusal) || !UidOf(package, &uid, &refusal)) return refusal;
    if (!MayHaveRoot(package, uid, &refusal)) return refusal;
    const ksu::Fields fields = FromAidl(profile);
    if (std::string why = ksu::CheckFields(fields); !why.empty()) {
        return Error(IRoot::ERROR_INVALID, why);
    }
    auto stored = ksu::Get(uid);
    if (!stored.ok()) return Failure(stored.error());
    if (!stored->has_value() || !(*stored)->allow_su) {
        return Error(IRoot::ERROR_NO_ROOT, package + " has no root");
    }
    auto changed = ksu::SetFields(package, uid, fields);
    if (!changed.ok()) return Failure(changed.error());
    if (*changed) Changed("changed the root profile", package);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::resetProfile(const std::string& package) {
    ndk::ScopedAStatus refusal;
    int32_t uid;
    if (!Allowed(&refusal) || !UidOf(package, &uid, &refusal)) return refusal;
    auto stored = ksu::Get(uid);
    if (!stored.ok()) return Failure(stored.error());
    if (!stored->has_value() || !(*stored)->allow_su) return ndk::ScopedAStatus::ok();
    auto changed = ksu::SetFields(package, uid, ksu::DefaultFields());
    if (!changed.ok()) return Failure(changed.error());
    if (*changed) Changed("restored the default root profile", package);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Service::setLinuxRootSessions(bool allowed) {
    // lujarch_linux, the only root caller service_contexts lets find this service.
    if (AIBinder_getCallingUid() != 0) {
        return Error(IRoot::ERROR_REFUSED, "only lujarch_linux may change root sessions");
    }
    auto changed = ksu::SetAllowSu(kLinuxRootKey, static_cast<int32_t>(aid::kLinuxRoot), allowed);
    if (!changed.ok()) return Failure(changed.error());
    if (*changed) Changed(allowed ? "allowed root" : "refused root", kLinuxRootKey);
    return ndk::ScopedAStatus::ok();
}

}  // namespace lujarch::root
