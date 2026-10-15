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

// KernelSU app profiles, through the kernel's own interface: reboot(2) with KernelSU's magic for a
// driver fd, then its ioctls.

#pragma once

#include <android-base/result.h>
#include <stddef.h>
#include <stdint.h>

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace lujarch::root::ksu {

// The kernel ABI of KernelSU-Next in upstream.xml (uapi/supercall.h, uapi/app_profile.h). The
// driver is used only when it reports this uapi version.
inline constexpr uint32_t kUapiVersion = 4;     // KERNEL_SU_UAPI_VERSION
inline constexpr uint32_t kProfileVersion = 4;  // KSU_APP_PROFILE_VER

struct RootProfile {
    int32_t uid;
    int32_t gid;
    uint32_t groups_count;
    int32_t groups[32];
    struct {
        uint64_t effective;
        uint64_t permitted;
        uint64_t inheritable;
    } capabilities;
    char selinux_domain[64];
    int32_t namespaces;
    uint64_t flags;
};

struct NonRootProfile {
    bool umount_modules;
};

// struct app_profile; allow_su selects the half of the union that applies.
struct AppProfile {
    uint32_t version;
    char key[256];
    int32_t curr_uid;
    bool allow_su;
    union {
        struct {
            bool use_default;
            char template_name[256];
            RootProfile profile;
        } rp_config;
        struct {
            bool use_default;
            NonRootProfile profile;
        } nrp_config;
    };
};
static_assert(sizeof(AppProfile) == 784);
static_assert(offsetof(AppProfile, rp_config) == 272);

enum class Namespace : int32_t { kInherited = 0, kGlobal = 1, kIndividual = 2 };

inline constexpr size_t kMaxGroups = 32;       // KSU_MAX_GROUPS
inline constexpr size_t kMaxDomain = 63;       // KSU_SELINUX_DOMAIN - 1
inline constexpr int32_t kMaxId = 0x7ffffffe;  // below (uid_t)-1 as int32
inline constexpr uint64_t kFlagNoNewPrivs = 1;  // FLAG_KSU_NO_NEW_PRIVS
// KernelSU's default root profile (policy/allowlist.c, init_default_profiles): uid, gid and the
// only group 0, every capability, KernelSU's domain, the inherited mount namespace, no flags.
inline constexpr char kDefaultDomain[] = "u:r:ksu:s0";

struct Info {
    uint32_t version = 0;
    std::string tag;  // e.g. "v3.4.0"
};

// KernelSU's version; an error says why the kernel has no usable KernelSU.
android::base::Result<Info> GetInfo();

struct Entry {
    std::string key;  // the package the profile was written for
    int32_t uid = 0;
    bool allow_su = false;
    bool custom = false;
};

// Every profile the kernel holds.
android::base::Result<std::vector<Entry>> List();

// The profile of uid, or nullopt when the kernel holds none.
android::base::Result<std::optional<AppProfile>> Get(int32_t uid);

// Whether KernelSU lets uid use su at all (policy/allowlist.c, forbid_system_uid): every uid from
// 2000 (shell) up, and 1000 (system).
bool KernelAllowsRoot(int32_t uid);

uint64_t FullCapabilities();

// The root profile's fields the owner chooses (ROOT-2).
struct Fields {
    int32_t uid = 0;
    int32_t gid = 0;
    std::vector<int32_t> groups;
    uint64_t capabilities = 0;
    std::string domain;
    Namespace ns = Namespace::kInherited;
    bool no_new_privs = false;
};

// KernelSU's default root profile as fields.
Fields DefaultFields();

// The fields of a profile with root: its own values, or the default ones while it uses them.
Fields FieldsOf(const AppProfile& profile);

// Why the fields cannot be a root profile, or "" (as the kernel and KernelSU's manager check).
std::string CheckFields(const Fields& fields);

// Allows or refuses su for package (uid), and waits until the kernel has written its allowlist
// file, so that the change survives a restart. Returns false when nothing had to change.
android::base::Result<bool> SetAllowSu(const std::string& package, int32_t uid, bool allow);

// Writes the root profile of package (uid), which must have root; fields equal to the default
// become the default profile again. Returns false when nothing had to change.
android::base::Result<bool> SetFields(const std::string& package, int32_t uid, const Fields& fields);

}  // namespace lujarch::root::ksu
