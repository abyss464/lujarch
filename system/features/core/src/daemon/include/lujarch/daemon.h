#pragma once

#include <android/binder_auto_utils.h>
#include <sys/types.h>

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace lujarch {

// Registers binder under name with servicemanager and serves calls on this thread and a pool of
// threads. Returns only when registration fails.
void Serve(const std::string& name, const ndk::SpAIBinder& binder, int threads = 2);

// The uid of an installed package, from packages.list; nullopt when it is not installed.
std::optional<uid_t> PackageUid(std::string_view package);

// The packages that share the calling uid, from packages.list.
std::vector<std::string> CallerPackages();

// Whether the caller is root, or an app whose uid owns package.
bool CallerIs(std::string_view package);

// The mirror of a feature's Global switch, lujarch.setting.<feature>.<key>.
std::string Setting(std::string_view feature, std::string_view key);
bool SettingIsOn(std::string_view feature, std::string_view key);

// Whether safe boot (UPD-5) turned the feature off for this boot: lujarch.safeboot.<feature>=off,
// set from the recovery menu's switches before the services start.
bool SafeBootOff(std::string_view feature);
// The feature's own switch "enabled", unless safe boot turned the feature off for this boot.
bool FeatureEnabled(std::string_view feature);

// Blocks until the mirror of a switch differs from value, and returns the new value.
std::string WaitForSettingChange(std::string_view feature, std::string_view key,
                                 const std::string& value);

}  // namespace lujarch
