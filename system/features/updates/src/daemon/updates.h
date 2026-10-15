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

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>

#include <aidl/org/lujarch/updates/BnUpdates.h>

namespace lujarch::updates {

// The confirmation file is libsnapshot's merge gate's (lujarch_merge_gate.h): update_engine merges
// the running build's update once it names that build.
inline constexpr char kDir[] = "/metadata/lujarch/updates";
inline constexpr char kSlotsFile[] = "/metadata/lujarch/updates/slots";
inline constexpr char kConfirmedFile[] = "/metadata/lujarch/updates/confirmed";
// Set by update_engine: waiting, merging, merged, none, cancelled, installed, failed,
// needs_reboot.
inline constexpr char kMergeProperty[] = "lujarch.updates.merge";
inline constexpr char kProgressProperty[] = "lujarch.updates.merge_progress";
inline constexpr char kFeature[] = "updates";
inline constexpr char kAutoConfirmKey[] = "auto_confirm";
inline constexpr char kUpdaterPackage[] = "org.lineageos.updater";

// The build a slot ran last.
struct SlotRecord {
    std::string build;           // ro.build.version.incremental
    std::string android;         // ro.build.version.release
    std::string security_patch;  // ro.build.version.security_patch
    std::string vendor_security_patch;  // ro.vendor.build.security_patch
    int64_t first_boot = 0;      // Unix seconds: the build's first start in this slot
    int64_t last_boot = 0;       // Unix seconds: its last start
};
using SlotRecords = std::map<std::string, SlotRecord>;  // by slot name, "a" or "b"

SlotRecords ParseSlots(std::string_view text);
std::string FormatSlots(const SlotRecords& records);
// The running build's record: its first start is kept while the slot runs the same build.
SlotRecord Refreshed(const SlotRecord* previous, SlotRecord running, int64_t now);

struct Confirmation {
    std::string build;
    std::string slot;  // "a" or "b"
    std::string by;    // "owner" or "auto"
    int64_t time = 0;
};
std::optional<Confirmation> ParseConfirmation(std::string_view text);
std::string FormatConfirmation(const Confirmation& confirmation);

// "b" for "_b".
std::string SlotName(std::string_view suffix);

// What decides whether the previous build can be restored.
struct RestoreFacts {
    std::string merge;                  // kMergeProperty
    std::string boot_merge;             // the boot control HAL's merge status
    std::optional<bool> other_bootable;
    const SlotRecord* running = nullptr;
    const SlotRecord* other = nullptr;  // the other slot's record, or null
};
// Why the previous build cannot be restored (an IUpdates RESTORE_ constant), "" when it can.
std::string RestoreBlocker(const RestoreFacts& facts);

// The auto-confirm choice from its mirror; anything but a whole number of minutes is 0, never.
int AutoConfirmMinutes(std::string_view mirror);
std::optional<int64_t> AutoConfirmTime(int minutes, const SlotRecord& running);

// Records the running build in its slot; called once at start.
void RecordRunningSlot();
// Confirms the running build once it has run for the chosen time; runs for the daemon's life.
void StartAutoConfirm();

class Updates : public aidl::org::lujarch::updates::BnUpdates {
  public:
    ndk::ScopedAStatus getStatus(aidl::org::lujarch::updates::Status* status) override;
    ndk::ScopedAStatus confirm() override;
    ndk::ScopedAStatus restorePrevious() override;
};

}  // namespace lujarch::updates
