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

#include "ksu.h"

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <errno.h>
#include <linux/capability.h>
#include <linux/ioctl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <mutex>
#include <set>
#include <thread>

namespace lujarch::root::ksu {

using android::base::ErrnoError;
using android::base::Error;
using android::base::Result;
using namespace std::chrono_literals;

namespace {

struct GetInfoCmd {
    uint32_t version;
    uint32_t flags;
    uint32_t features;
    uint32_t uapi_version;
};

// struct ksu_new_get_allow_list_cmd without its trailing uids[].
struct ListHeader {
    uint16_t count;        // in: room for uids; out: uids copied
    uint16_t total_count;  // out: uids in the list
};
static_assert(sizeof(ListHeader) == 4);

struct VersionTagCmd {
    char tag[32];
};

constexpr uint32_t kIoctlGetInfo = _IOR('K', 2, GetInfoCmd);
constexpr uint32_t kIoctlNewGetAllowList = _IOWR('K', 6, ListHeader);
constexpr uint32_t kIoctlNewGetDenyList = _IOWR('K', 7, ListHeader);
constexpr uint32_t kIoctlGetAppProfile = _IOC(_IOC_READ | _IOC_WRITE, 'K', 11, 0);
constexpr uint32_t kIoctlSetAppProfile = _IOC(_IOC_WRITE, 'K', 12, 0);
constexpr uint32_t kIoctlGetVersionTag = _IOC(_IOC_READ, 'K', 99, 0);
constexpr unsigned long kInstallMagic1 = 0xDEADBEEF;
constexpr unsigned long kInstallMagic2 = 0xCAFEBABE;

constexpr char kAllowlistDir[] = "/data/adb/ksu";
constexpr char kAllowlistFile[] = "/data/adb/ksu/.allowlist";
constexpr off_t kAllowlistHeader = 8;  // magic and format version
constexpr char kFlushProperty[] = "lujarch.root.flush";
constexpr auto kFlushTimeout = 5s;

struct Driver {
    int fd = -1;
    Info info;
    std::string error;
};

std::string CString(const char* s, size_t max) {
    return std::string(s, strnlen(s, max));
}

Driver OpenDriver() {
    Driver driver;
    int fd = -1;
    // KernelSU hooks reboot(2): called with its magic numbers, it installs a driver fd (O_CLOEXEC)
    // in the caller and stores its number through the fourth argument. The syscall itself then
    // fails on the missing CAP_SYS_BOOT: the first magic is not LINUX_REBOOT_MAGIC1.
    syscall(__NR_reboot, kInstallMagic1, kInstallMagic2, 0, &fd);
    if (fd < 0) {
        driver.error = "this kernel has no KernelSU";
        LOG(WARNING) << driver.error;
        return driver;
    }
    GetInfoCmd info{};
    if (ioctl(fd, kIoctlGetInfo, &info) != 0) {
        driver.error = std::string("KernelSU GET_INFO failed: ") + strerror(errno);
        LOG(ERROR) << driver.error;
        close(fd);
        return driver;
    }
    if (info.uapi_version != kUapiVersion) {
        driver.error = "KernelSU uapi " + std::to_string(info.uapi_version) +
                       " is not the one lujarch_root speaks (" + std::to_string(kUapiVersion) + ")";
        LOG(ERROR) << driver.error;
        close(fd);
        return driver;
    }
    VersionTagCmd tag{};
    if (ioctl(fd, kIoctlGetVersionTag, &tag) == 0) {
        driver.info.tag = CString(tag.tag, sizeof(tag.tag));
    } else {
        PLOG(WARNING) << "KernelSU GET_VERSION_TAG failed";
    }
    driver.fd = fd;
    driver.info.version = info.version;
    LOG(INFO) << "KernelSU " << driver.info.tag << " (" << driver.info.version << "), uapi "
              << info.uapi_version;
    return driver;
}

const Driver& TheDriver() {
    // Opened once; the fd stays open for the daemon's lifetime.
    static const Driver driver = OpenDriver();
    return driver;
}

Result<int> DriverFd() {
    const Driver& driver = TheDriver();
    if (driver.fd < 0) return Error() << driver.error;
    return driver.fd;
}

Result<std::vector<int32_t>> Uids(int fd, bool allow) {
    const uint32_t request = allow ? kIoctlNewGetAllowList : kIoctlNewGetDenyList;
    size_t capacity = 64;
    for (int attempt = 0; attempt < 4; ++attempt) {
        // The header in the first word, the uids after it.
        std::vector<uint32_t> buffer(1 + capacity);
        ListHeader header{static_cast<uint16_t>(capacity), 0};
        memcpy(buffer.data(), &header, sizeof(header));
        if (ioctl(fd, request, buffer.data()) != 0) {
            return ErrnoError() << "KernelSU " << (allow ? "NEW_GET_ALLOW_LIST" : "NEW_GET_DENY_LIST");
        }
        memcpy(&header, buffer.data(), sizeof(header));
        if (header.total_count <= header.count) {
            return std::vector<int32_t>(buffer.begin() + 1, buffer.begin() + 1 + header.count);
        }
        capacity = std::min<size_t>(header.total_count + 16u, UINT16_MAX);
    }
    return Error() << "the KernelSU allowlist kept changing while it was read";
}

void CopyKey(char (&dst)[256], std::string_view key) {
    const size_t n = std::min(key.size(), sizeof(dst) - 1);
    memcpy(dst, key.data(), n);
    dst[n] = '\0';
}

AppProfile Blank(std::string_view key, int32_t uid, bool allow) {
    AppProfile p;
    memset(&p, 0, sizeof(p));
    p.version = kProfileVersion;
    CopyKey(p.key, key);
    p.curr_uid = uid;
    p.allow_su = allow;
    return p;
}

// root with KernelSU's default root profile (use_default); the explicit values written beside it
// are the same defaults.
AppProfile GrantProfile(std::string_view key, int32_t uid) {
    AppProfile p = Blank(key, uid, true);
    p.rp_config.use_default = true;
    RootProfile& r = p.rp_config.profile;
    r.groups_count = 1;
    r.capabilities.effective = FullCapabilities();
    r.capabilities.permitted = FullCapabilities();
    strlcpy(r.selinux_domain, kDefaultDomain, sizeof(r.selinux_domain));
    return p;
}

// No root, with KernelSU's default non-root profile.
AppProfile RevokeProfile(std::string_view key, int32_t uid) {
    AppProfile p = Blank(key, uid, false);
    p.nrp_config.use_default = true;
    p.nrp_config.profile.umount_modules = true;
    return p;
}

AppProfile CustomProfile(std::string_view key, int32_t uid, const Fields& f) {
    AppProfile p = Blank(key, uid, true);
    p.rp_config.use_default = false;
    RootProfile& r = p.rp_config.profile;
    r.uid = f.uid;
    r.gid = f.gid;
    r.groups_count = static_cast<uint32_t>(std::min(f.groups.size(), kMaxGroups));
    for (uint32_t i = 0; i < r.groups_count; ++i) r.groups[i] = f.groups[i];
    // escape_with_root_profile copies the effective mask into every set; permitted is kept equal
    // for readers of the profile.
    r.capabilities.effective = f.capabilities;
    r.capabilities.permitted = f.capabilities;
    strlcpy(r.selinux_domain, f.domain.c_str(), sizeof(r.selinux_domain));
    r.namespaces = static_cast<int32_t>(f.ns);
    r.flags = f.no_new_privs ? kFlagNoNewPrivs : 0;
    return p;
}

bool SameFields(const Fields& a, const Fields& b) {
    return a.uid == b.uid && a.gid == b.gid && a.groups == b.groups &&
           a.capabilities == b.capabilities && a.domain == b.domain && a.ns == b.ns &&
           a.no_new_privs == b.no_new_privs;
}

// Two profiles that make the kernel behave the same.
bool SameEffect(const AppProfile& a, const AppProfile& b) {
    if (a.allow_su != b.allow_su) return false;
    if (!a.allow_su) return true;
    return a.rp_config.use_default == b.rp_config.use_default &&
           SameFields(FieldsOf(a), FieldsOf(b));
}

Result<void> EnsureAllowlistDir() {
    struct stat st;
    if (stat(kAllowlistDir, &st) == 0) {
        if (S_ISDIR(st.st_mode)) return {};
        return Error() << kAllowlistDir << " is not a directory";
    }
    if (errno != ENOENT) return ErrnoError() << "stat " << kAllowlistDir;
    // The kernel writes its allowlist there but does not create the directory.
    if (mkdir(kAllowlistDir, 0700) != 0 && errno != EEXIST) {
        return ErrnoError() << "mkdir " << kAllowlistDir;
    }
    LOG(INFO) << "created " << kAllowlistDir;
    return {};
}

struct Stamp {
    bool exists = false;
    timespec mtime{};
};

Stamp StampOf(const char* path) {
    struct stat st;
    if (stat(path, &st) != 0) return {};
    return {true, st.st_mtim};
}

Result<void> WaitForAllowlistWrite(const Stamp& before) {
    // SET_APP_PROFILE queues the file write as task_work on init, which runs only when init next
    // returns to user space; a property write wakes init's main loop.
    timespec now{};
    clock_gettime(CLOCK_BOOTTIME, &now);
    const std::string value = std::to_string(now.tv_sec) + "." + std::to_string(now.tv_nsec);
    if (!android::base::SetProperty(kFlushProperty, value)) {
        return Error() << "cannot set " << kFlushProperty << " to wake init";
    }
    const auto deadline = std::chrono::steady_clock::now() + kFlushTimeout;
    for (;;) {
        struct stat st;
        const bool present = stat(kAllowlistFile, &st) == 0;
        const int stat_errno = errno;
        if (present && st.st_size >= kAllowlistHeader &&
            (!before.exists || st.st_mtim.tv_sec != before.mtime.tv_sec ||
             st.st_mtim.tv_nsec != before.mtime.tv_nsec)) {
            return {};
        }
        if (std::chrono::steady_clock::now() >= deadline) {
            return Error() << "KernelSU did not rewrite " << kAllowlistFile << " within "
                           << kFlushTimeout.count() << " s ("
                           << (present ? "unchanged" : strerror(stat_errno))
                           << "); the change is lost at the next start";
        }
        std::this_thread::sleep_for(50ms);
    }
}

// One change at a time: each waits for its own allowlist write.
std::mutex change_lock;

// Writes wanted, checks that the kernel kept it and waits for the allowlist file.
Result<void> Write(int fd, const std::string& package, int32_t uid, const AppProfile& wanted) {
    if (auto dir = EnsureAllowlistDir(); !dir.ok()) return dir.error();
    const Stamp before = StampOf(kAllowlistFile);
    AppProfile copy = wanted;
    if (ioctl(fd, kIoctlSetAppProfile, &copy) != 0) {
        return ErrnoError() << "KernelSU SET_APP_PROFILE for " << package;
    }
    auto now = Get(uid);
    if (!now.ok()) return now.error();
    if (!now->has_value() || !SameEffect(**now, wanted)) {
        return Error() << "KernelSU did not keep the profile written for " << package;
    }
    return WaitForAllowlistWrite(before);
}

}  // namespace

Result<Info> GetInfo() {
    auto fd = DriverFd();
    if (!fd.ok()) return fd.error();
    return TheDriver().info;
}

Result<std::optional<AppProfile>> Get(int32_t uid) {
    auto fd = DriverFd();
    if (!fd.ok()) return fd.error();
    AppProfile profile;
    memset(&profile, 0, sizeof(profile));
    profile.curr_uid = uid;
    if (ioctl(*fd, kIoctlGetAppProfile, &profile) == 0) return std::optional<AppProfile>(profile);
    if (errno == ENOENT) return std::optional<AppProfile>();
    return ErrnoError() << "KernelSU GET_APP_PROFILE for uid " << uid;
}

Result<std::vector<Entry>> List() {
    auto fd = DriverFd();
    if (!fd.ok()) return fd.error();
    std::set<int32_t> uids;
    for (bool allow : {true, false}) {
        auto list = Uids(*fd, allow);
        if (!list.ok()) return list.error();
        uids.insert(list->begin(), list->end());
    }
    std::vector<Entry> entries;
    for (int32_t uid : uids) {
        auto profile = Get(uid);
        if (!profile.ok()) return profile.error();
        if (!profile->has_value()) continue;  // removed while listing
        const AppProfile& p = **profile;
        entries.push_back({CString(p.key, sizeof(p.key)), p.curr_uid, p.allow_su,
                           p.allow_su && !p.rp_config.use_default});
    }
    return entries;
}

bool KernelAllowsRoot(int32_t uid) {
    return uid >= 2000 || uid == 1000;  // SHELL_UID and SYSTEM_UID in allowlist.c
}

uint64_t FullCapabilities() {
    return (uint64_t{1} << (CAP_LAST_CAP + 1)) - 1;
}

Fields DefaultFields() {
    Fields f;
    f.groups = {0};
    f.capabilities = FullCapabilities();
    f.domain = kDefaultDomain;
    return f;
}

Fields FieldsOf(const AppProfile& p) {
    if (p.rp_config.use_default) return DefaultFields();
    const RootProfile& r = p.rp_config.profile;
    Fields f;
    f.uid = r.uid;
    f.gid = r.gid;
    const uint32_t n = std::min<uint32_t>(r.groups_count, kMaxGroups);
    f.groups.assign(r.groups, r.groups + n);
    f.capabilities = r.capabilities.effective;
    f.domain = CString(r.selinux_domain, sizeof(r.selinux_domain));
    f.ns = static_cast<Namespace>(r.namespaces);
    f.no_new_privs = (r.flags & kFlagNoNewPrivs) != 0;
    return f;
}

std::string CheckFields(const Fields& f) {
    if (f.uid < 0 || f.uid > kMaxId) return "the user ID is out of range";
    if (f.gid < 0 || f.gid > kMaxId) return "the group ID is out of range";
    if (f.groups.size() > kMaxGroups) return "at most 32 supplementary groups";
    std::set<int32_t> seen;
    for (int32_t g : f.groups) {
        if (g < 0 || g > kMaxId) return "a group ID is out of range";
        if (!seen.insert(g).second) return "a group is listed twice";
    }
    if ((f.capabilities & ~FullCapabilities()) != 0) return "unknown capability bits";
    // KernelSU's manager's rule: user:role:type[:level], lower case, digits and '_'.
    const std::string& d = f.domain;
    if (d.empty() || d.size() > kMaxDomain) return "the SELinux domain must be 1 to 63 characters";
    int colons = 0;
    bool part_empty = true;
    for (char c : d) {
        if (c == ':') {
            if (part_empty) return "the SELinux domain has an empty part";
            ++colons;
            part_empty = true;
            continue;
        }
        const bool ok = (c >= 'a' && c <= 'z') || c == '_' || (colons > 0 && c >= '0' && c <= '9');
        if (!ok) return "the SELinux domain must be user:role:type[:level] (a-z, 0-9, _)";
        part_empty = false;
    }
    if (part_empty || colons < 2 || colons > 3) {
        return "the SELinux domain must be user:role:type[:level]";
    }
    switch (f.ns) {
        case Namespace::kInherited:
        case Namespace::kGlobal:
        case Namespace::kIndividual:
            return "";
    }
    return "unknown mount namespace";
}

Result<bool> SetAllowSu(const std::string& package, int32_t uid, bool allow) {
    std::lock_guard<std::mutex> guard(change_lock);
    auto fd = DriverFd();
    if (!fd.ok()) return fd.error();
    auto current = Get(uid);
    if (!current.ok()) return current.error();
    const bool granted = current->has_value() && (*current)->allow_su;
    if (granted == allow) return false;
    const AppProfile wanted = allow ? GrantProfile(package, uid) : RevokeProfile(package, uid);
    if (auto written = Write(*fd, package, uid, wanted); !written.ok()) return written.error();
    return true;
}

Result<bool> SetFields(const std::string& package, int32_t uid, const Fields& fields) {
    std::lock_guard<std::mutex> guard(change_lock);
    auto fd = DriverFd();
    if (!fd.ok()) return fd.error();
    auto current = Get(uid);
    if (!current.ok()) return current.error();
    if (!current->has_value() || !(*current)->allow_su) {
        return Error() << package << " has no root";
    }
    const AppProfile wanted = SameFields(fields, DefaultFields())
                                      ? GrantProfile(package, uid)
                                      : CustomProfile(package, uid, fields);
    if (SameEffect(**current, wanted)) return false;
    if (auto written = Write(*fd, package, uid, wanted); !written.ok()) return written.error();
    return true;
}

}  // namespace lujarch::root::ksu
