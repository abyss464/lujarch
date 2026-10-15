#include "lujarch/daemon.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <sys/system_properties.h>

#include <string>

namespace lujarch {

namespace {

constexpr char kPackagesList[] = "/data/system/packages.list";

std::string MirrorName(std::string_view feature, std::string_view key) {
    return "lujarch.setting." + std::string(feature) + "." + std::string(key);
}

}  // namespace

void Serve(const std::string& name, const ndk::SpAIBinder& binder, int threads) {
    ABinderProcess_setThreadPoolMaxThreadCount(threads);
    binder_exception_t status = AServiceManager_addService(binder.get(), name.c_str());
    if (status != EX_NONE) {
        LOG(ERROR) << "cannot register " << name << ": exception " << status;
        return;
    }
    ABinderProcess_startThreadPool();
    ABinderProcess_joinThreadPool();
}

std::vector<std::string> CallerPackages() {
    uid_t uid = AIBinder_getCallingUid();
    std::string list;
    if (!android::base::ReadFileToString(kPackagesList, &list)) {
        PLOG(ERROR) << "cannot read " << kPackagesList;
        return {};
    }
    std::vector<std::string> packages;
    for (const std::string& line : android::base::Split(list, "\n")) {
        std::vector<std::string> fields = android::base::Split(line, " ");
        if (fields.size() > 1 && fields[1] == std::to_string(uid)) {
            packages.push_back(fields[0]);
        }
    }
    return packages;
}

bool CallerIs(std::string_view package) {
    if (AIBinder_getCallingUid() == 0) {
        return true;
    }
    for (const std::string& name : CallerPackages()) {
        if (name == package) {
            return true;
        }
    }
    return false;
}

std::string Setting(std::string_view feature, std::string_view key) {
    return android::base::GetProperty(MirrorName(feature, key), "");
}

bool SettingIsOn(std::string_view feature, std::string_view key) {
    return Setting(feature, key) == "1";
}

std::string WaitForSettingChange(std::string_view feature, std::string_view key,
                                 const std::string& value) {
    std::string name = MirrorName(feature, key);
    for (;;) {
        const prop_info* info = __system_property_find(name.c_str());
        if (info == nullptr) {
            android::base::WaitForPropertyCreation(name);
            continue;
        }
        // The serial is read before the value, so a change in between ends the wait at once.
        uint32_t serial = __system_property_serial(info);
        std::string current = android::base::GetProperty(name, "");
        if (current != value) {
            return current;
        }
        uint32_t changed;
        __system_property_wait(info, serial, &changed, nullptr);
    }
}

}  // namespace lujarch
