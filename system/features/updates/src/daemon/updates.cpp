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

#include "updates.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <thread>

#include <BootControlClient.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <android/binder_ibinder.h>
#include <lujarch/daemon.h>

namespace lujarch::updates {

namespace {

using aidl::org::lujarch::updates::IUpdates;
using aidl::org::lujarch::updates::Status;
using android::base::GetProperty;
using android::base::unique_fd;
using android::hal::BootControlClient;

constexpr int64_t kMinute = 60;

// Confirmations, restores and the auto-confirm thread take this lock: a confirmation lets
// update_engine overwrite the other slot, so it never crosses a restore to that slot.
std::mutex g_lock;
// Written on a confirmation, a restore and a new auto-confirm choice; wakes the auto-confirm
// thread.
std::atomic<int> g_changed_fd{-1};

void NotifyChanged() {
    const int fd = g_changed_fd.load();
    if (fd < 0) return;
    const uint64_t one = 1;
    if (TEMP_FAILURE_RETRY(write(fd, &one, sizeof(one))) != static_cast<ssize_t>(sizeof(one))) {
        PLOG(ERROR) << "cannot wake the auto-confirm thread";
    }
}

std::mutex g_hal_lock;
std::shared_ptr<BootControlClient> g_hal;

// The boot control HAL, connected once; a failed call drops it, so the next request connects
// again (the HAL may have restarted).
std::shared_ptr<BootControlClient> BootControl() {
    std::lock_guard<std::mutex> lock(g_hal_lock);
    if (g_hal == nullptr) g_hal = BootControlClient::WaitForService();
    if (g_hal == nullptr) LOG(ERROR) << "no boot control HAL";
    return g_hal;
}

void DropBootControl() {
    std::lock_guard<std::mutex> lock(g_hal_lock);
    g_hal.reset();
}

int64_t Now() {
    return static_cast<int64_t>(time(nullptr));
}

std::string OtherSlot(const std::string& slot) {
    if (slot == "a") return "b";
    if (slot == "b") return "a";
    return "";
}

std::string RunningSlot() {
    return SlotName(GetProperty("ro.boot.slot_suffix", ""));
}

SlotRecord LiveRecord() {
    SlotRecord r;
    r.build = GetProperty("ro.build.version.incremental", "");
    r.android = GetProperty("ro.build.version.release", "");
    r.security_patch = GetProperty("ro.build.version.security_patch", "");
    r.vendor_security_patch = GetProperty("ro.vendor.build.security_patch", "");
    return r;
}

// False without a log line when the file does not exist.
bool ReadText(const char* path, std::string* out) {
    if (android::base::ReadFileToString(path, out, /*follow_symlinks=*/false)) return true;
    if (errno != ENOENT) PLOG(ERROR) << "cannot read " << path;
    return false;
}

// A temporary file, fsync, rename, fsync of the directory: readers (update_engine's inotify
// watch) see the old file or the new one.
bool WriteAtomic(const std::string& path, const std::string& data) {
    const std::string tmp = path + ".tmp";
    unique_fd fd(TEMP_FAILURE_RETRY(
            open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0640)));
    if (fd < 0) {
        PLOG(ERROR) << "cannot create " << tmp;
        return false;
    }
    if (!android::base::WriteFully(fd.get(), data.data(), data.size()) || fsync(fd.get()) != 0) {
        PLOG(ERROR) << "cannot write " << tmp;
        unlink(tmp.c_str());
        return false;
    }
    fd.reset();
    if (rename(tmp.c_str(), path.c_str()) != 0) {
        PLOG(ERROR) << "cannot rename " << tmp << " to " << path;
        unlink(tmp.c_str());
        return false;
    }
    unique_fd dir(TEMP_FAILURE_RETRY(open(kDir, O_RDONLY | O_DIRECTORY | O_CLOEXEC)));
    if (dir < 0 || fsync(dir.get()) != 0) PLOG(WARNING) << "cannot sync " << kDir;
    return true;
}

SlotRecords ReadSlots() {
    std::string text;
    return ReadText(kSlotsFile, &text) ? ParseSlots(text) : SlotRecords();
}

std::optional<Confirmation> ReadConfirmation() {
    std::string text;
    if (!ReadText(kConfirmedFile, &text)) return std::nullopt;
    return ParseConfirmation(text);
}

const char* MergeStatusName(BootControlClient::MergeStatus status) {
    using MergeStatus = BootControlClient::MergeStatus;
    switch (status) {
        case MergeStatus::NONE:
            return "none";
        case MergeStatus::UNKNOWN:
            return "unknown";
        case MergeStatus::SNAPSHOTTED:
            return "snapshotted";
        case MergeStatus::MERGING:
            return "merging";
        case MergeStatus::CANCELLED:
            return "cancelled";
    }
    return "unknown";
}

// Everything the status reports and the actions decide on.
struct State {
    std::string slot;   // the running slot, "a" or "b"
    std::string other;  // the other slot
    SlotRecord running;
    std::optional<SlotRecord> other_record;
    std::string merge;
    int progress = -1;
    bool confirmed = false;
    int auto_confirm_minutes = 0;
    // From the boot control HAL; "" or nullopt when it could not be read.
    std::shared_ptr<BootControlClient> hal;
    int32_t other_index = -1;
    std::string active;
    std::optional<bool> other_bootable;
    std::string boot_merge;

    // The previous build starts at the next restart: the running build waits for confirmation and
    // the other slot is active (after an install the other slot is active too, holding the update).
    bool restoring() const { return merge == "waiting" && !active.empty() && active != slot; }

    RestoreFacts facts() const {
        RestoreFacts f;
        f.merge = merge;
        f.boot_merge = boot_merge;
        f.other_bootable = other_bootable;
        f.running = &running;
        f.other = other_record ? &*other_record : nullptr;
        return f;
    }

    int32_t state() const {
        if (restoring()) return IUpdates::STATE_RESTORING;
        if (merge == "installed") return IUpdates::STATE_INSTALLED;
        if (merge == "waiting") return confirmed ? IUpdates::STATE_MERGING : IUpdates::STATE_WAITING;
        if (merge == "merging" || merge == "needs_reboot") return IUpdates::STATE_MERGING;
        return IUpdates::STATE_NONE;
    }
};

State ReadState() {
    State s;
    s.slot = RunningSlot();
    s.other = OtherSlot(s.slot);
    s.running = LiveRecord();
    const SlotRecords records = ReadSlots();
    if (auto it = records.find(s.slot); it != records.end() && it->second.build == s.running.build) {
        s.running.first_boot = it->second.first_boot;
        s.running.last_boot = it->second.last_boot;
    }
    if (auto it = records.find(s.other); it != records.end()) s.other_record = it->second;
    s.merge = GetProperty(kMergeProperty, "");
    int progress = 0;
    if (android::base::ParseInt(GetProperty(kProgressProperty, ""), &progress, 0, 100)) {
        s.progress = progress;
    }
    const std::optional<Confirmation> confirmation = ReadConfirmation();
    s.confirmed = confirmation && !s.running.build.empty() &&
                  confirmation->build == s.running.build && confirmation->slot == s.slot;
    s.auto_confirm_minutes = AutoConfirmMinutes(Setting(kFeature, kAutoConfirmKey));

    s.hal = BootControl();
    if (s.hal == nullptr) return s;
    const int32_t slots = s.hal->GetNumSlots();
    for (int32_t i = 0; i < slots; ++i) {
        if (SlotName(s.hal->GetSuffix(i)) == s.other) s.other_index = i;
    }
    const int32_t active = s.hal->GetActiveBootSlot();
    if (active >= 0 && active < slots) s.active = SlotName(s.hal->GetSuffix(active));
    if (s.other_index >= 0) s.other_bootable = s.hal->IsSlotBootable(s.other_index);
    s.boot_merge = MergeStatusName(s.hal->getSnapshotMergeStatus());
    if (s.other_index < 0 || s.active.empty() || !s.other_bootable) {
        LOG(ERROR) << "the boot control HAL did not answer every question";
        DropBootControl();
    }
    return s;
}

ndk::ScopedAStatus Refused(const std::string& why) {
    LOG(WARNING) << "refused: " << why;
    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(IUpdates::ERROR_REFUSED,
                                                                   why.c_str());
}

ndk::ScopedAStatus Failed(const std::string& why) {
    LOG(ERROR) << why;
    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(IUpdates::ERROR_FAILED,
                                                                   why.c_str());
}

// Only System updates asks.
std::optional<ndk::ScopedAStatus> CheckCaller() {
    if (CallerIs(kUpdaterPackage)) return std::nullopt;
    return Refused("uid " + std::to_string(AIBinder_getCallingUid()) + " is not System updates");
}

// Writes the confirmation of the running build. The caller holds g_lock.
ndk::ScopedAStatus ConfirmLocked(const State& s, const char* by) {
    if (s.running.build.empty() || s.slot.empty()) {
        return Failed("the running build has no build number or slot");
    }
    if (s.restoring()) return Refused("the previous build starts at the next restart");
    if (s.confirmed) return ndk::ScopedAStatus::ok();
    Confirmation c;
    c.build = s.running.build;
    c.slot = s.slot;
    c.by = by;
    c.time = Now();
    if (!WriteAtomic(kConfirmedFile, FormatConfirmation(c))) {
        return Failed(std::string("cannot write ") + kConfirmedFile);
    }
    LOG(INFO) << c.build << " in slot " << c.slot << " confirmed (" << by
              << "); update_engine merges its update";
    NotifyChanged();
    return ndk::ScopedAStatus::ok();
}

// Waits on the change eventfd and on a CLOCK_REALTIME timerfd set to the confirmation time. Not a
// timed wait on a condition variable: bionic turns a wall-clock deadline into a CLOCK_MONOTONIC
// one, which stops while the phone sleeps. The CLOCK_REALTIME timer counts the sleep and fires at
// the first moment the phone is awake after its time; it does not wake the phone.
void AutoConfirmLoop(unique_fd timer) {
    for (;;) {
        std::optional<int64_t> wake;  // nullopt: only a change wakes the thread
        {
            std::lock_guard<std::mutex> lock(g_lock);
            const State s = ReadState();
            const std::optional<int64_t> at =
                    s.confirmed ? std::nullopt
                                : AutoConfirmTime(s.auto_confirm_minutes, s.running);
            if (at && *at <= Now() && !s.restoring()) {
                LOG(INFO) << s.running.build << " has run for " << s.auto_confirm_minutes
                          << " minutes, the time chosen for confirming it by itself";
                if (ConfirmLocked(s, "auto").isOk()) continue;
                // Tried again on the next change or at the next start.
                LOG(ERROR) << "the automatic confirmation failed";
            } else if (at && !s.restoring()) {
                wake = *at;
            }
        }
        itimerspec spec{};  // all zero: disarmed
        if (wake) spec.it_value.tv_sec = static_cast<time_t>(*wake);
        if (timerfd_settime(timer.get(), TFD_TIMER_ABSTIME, &spec, nullptr) != 0) {
            PLOG(ERROR) << "cannot set the auto-confirm timer to " << (wake ? *wake : 0);
        }
        const int changed = g_changed_fd.load();
        pollfd fds[2] = {{changed, POLLIN, 0}, {timer.get(), POLLIN, 0}};
        if (TEMP_FAILURE_RETRY(poll(fds, 2, -1)) < 0) {
            PLOG(ERROR) << "auto-confirm poll";
            std::this_thread::sleep_for(std::chrono::seconds(5));
            continue;
        }
        // Both are non-blocking: the one that did not fire answers EAGAIN.
        uint64_t count = 0;
        if (read(changed, &count, sizeof(count)) < 0 && errno != EAGAIN) {
            PLOG(ERROR) << "cannot read the change event";
        }
        if (read(timer.get(), &count, sizeof(count)) < 0 && errno != EAGAIN) {
            PLOG(ERROR) << "cannot read the auto-confirm timer";
        }
    }
}

// A new auto-confirm choice wakes the auto-confirm thread.
void WatchAutoConfirmChoice() {
    std::string value = Setting(kFeature, kAutoConfirmKey);
    for (;;) {
        value = WaitForSettingChange(kFeature, kAutoConfirmKey, value);
        LOG(INFO) << "confirm automatically after " << AutoConfirmMinutes(value) << " minutes";
        NotifyChanged();
    }
}

}  // namespace

std::string SlotName(std::string_view suffix) {
    if (suffix == "_a") return "a";
    if (suffix == "_b") return "b";
    return "";
}

SlotRecords ParseSlots(std::string_view text) {
    SlotRecords records;
    for (const std::string& raw : android::base::Split(std::string(text), "\n")) {
        const std::string line = android::base::Trim(raw);
        const size_t colon = line.find(':');
        const size_t dot = line.find('.');
        if (colon == std::string::npos || dot == std::string::npos || dot > colon) continue;
        const std::string slot = line.substr(0, dot);
        if (slot != "a" && slot != "b") continue;
        const std::string key = line.substr(dot + 1, colon - dot - 1);
        const std::string value = android::base::Trim(line.substr(colon + 1));
        SlotRecord& r = records[slot];
        int64_t number = 0;
        if (key == "build") {
            r.build = value;
        } else if (key == "android") {
            r.android = value;
        } else if (key == "security_patch") {
            r.security_patch = value;
        } else if (key == "vendor_security_patch") {
            r.vendor_security_patch = value;
        } else if (key == "first_boot" && android::base::ParseInt(value, &number, int64_t{0})) {
            r.first_boot = number;
        } else if (key == "last_boot" && android::base::ParseInt(value, &number, int64_t{0})) {
            r.last_boot = number;
        }
    }
    return records;
}

std::string FormatSlots(const SlotRecords& records) {
    std::string out;
    for (const auto& [slot, r] : records) {
        out += slot + ".build: " + r.build + "\n";
        out += slot + ".android: " + r.android + "\n";
        out += slot + ".security_patch: " + r.security_patch + "\n";
        out += slot + ".vendor_security_patch: " + r.vendor_security_patch + "\n";
        out += slot + ".first_boot: " + std::to_string(r.first_boot) + "\n";
        out += slot + ".last_boot: " + std::to_string(r.last_boot) + "\n";
    }
    return out;
}

SlotRecord Refreshed(const SlotRecord* previous, SlotRecord running, int64_t now) {
    running.first_boot = previous != nullptr && previous->build == running.build &&
                                         previous->first_boot > 0
                                 ? previous->first_boot
                                 : now;
    running.last_boot = now;
    return running;
}

std::optional<Confirmation> ParseConfirmation(std::string_view text) {
    Confirmation c;
    for (const std::string& raw : android::base::Split(std::string(text), "\n")) {
        const std::string line = android::base::Trim(raw);
        const size_t colon = line.find(':');
        if (colon == std::string::npos) continue;
        const std::string key = line.substr(0, colon);
        const std::string value = android::base::Trim(line.substr(colon + 1));
        if (key == "version") {
            c.build = value;
        } else if (key == "slot") {
            c.slot = value;
        } else if (key == "by") {
            c.by = value;
        } else if (key == "time") {
            android::base::ParseInt(value, &c.time, int64_t{0});
        }
    }
    if (c.build.empty() || c.slot.empty()) return std::nullopt;
    return c;
}

std::string FormatConfirmation(const Confirmation& c) {
    return "version: " + c.build + "\n" + "slot: " + c.slot + "\n" + "by: " + c.by + "\n" +
           "time: " + std::to_string(c.time) + "\n";
}

std::string RestoreBlocker(const RestoreFacts& f) {
    // Only a snapshotted update leaves the previous build whole; the boot control HAL holds the
    // bootloader's copy of libsnapshot's state.
    if (f.boot_merge != "snapshotted" || !f.other_bootable) return IUpdates::RESTORE_UNKNOWN;
    if (!*f.other_bootable) return IUpdates::RESTORE_NOT_BOOTABLE;
    if (f.other == nullptr || f.other->build.empty() || f.running == nullptr) {
        return IUpdates::RESTORE_UNKNOWN_BUILD;
    }
    if (f.other->android != f.running->android) return IUpdates::RESTORE_ANDROID_VERSION;
    // "YYYY-MM-DD" compares as text; a record without a patch level is older.
    if (f.other->security_patch < f.running->security_patch ||
        f.other->vendor_security_patch < f.running->vendor_security_patch) {
        return IUpdates::RESTORE_PATCH_LEVEL;
    }
    return "";
}

int AutoConfirmMinutes(std::string_view mirror) {
    int minutes = 0;
    if (!android::base::ParseInt(std::string(mirror), &minutes, 0)) return 0;
    return minutes;
}

std::optional<int64_t> AutoConfirmTime(int minutes, const SlotRecord& running) {
    if (minutes <= 0 || running.first_boot <= 0) return std::nullopt;
    return running.first_boot + int64_t{minutes} * kMinute;
}

void RecordRunningSlot() {
    const std::string slot = RunningSlot();
    if (slot.empty()) {
        LOG(ERROR) << "no slot suffix; the running build is not recorded";
        return;
    }
    std::lock_guard<std::mutex> lock(g_lock);
    SlotRecords records = ReadSlots();
    const auto it = records.find(slot);
    const SlotRecord record =
            Refreshed(it == records.end() ? nullptr : &it->second, LiveRecord(), Now());
    records[slot] = record;
    if (WriteAtomic(kSlotsFile, FormatSlots(records))) {
        LOG(INFO) << "slot " << slot << " runs " << record.build;
    }
}

void StartAutoConfirm() {
    unique_fd changed(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    unique_fd timer(timerfd_create(CLOCK_REALTIME, TFD_CLOEXEC | TFD_NONBLOCK));
    if (changed < 0 || timer < 0) {
        PLOG(ERROR) << "no eventfd or timerfd; nothing is confirmed by itself";
        return;
    }
    g_changed_fd.store(changed.release());
    std::thread(AutoConfirmLoop, std::move(timer)).detach();
    std::thread(WatchAutoConfirmChoice).detach();
}

ndk::ScopedAStatus Updates::getStatus(Status* out) {
    if (auto refused = CheckCaller()) return std::move(*refused);
    const State s = ReadState();
    out->state = s.state();
    out->mergeProgress = s.state() == IUpdates::STATE_MERGING ? s.progress : -1;
    out->autoConfirmMinutes = s.auto_confirm_minutes;
    const std::optional<int64_t> at =
            s.confirmed ? std::nullopt : AutoConfirmTime(s.auto_confirm_minutes, s.running);
    out->autoConfirmAt = at ? *at : 0;
    out->previousBuild = s.other_record ? s.other_record->build : "";
    out->restoreBlocker = s.state() == IUpdates::STATE_WAITING ? RestoreBlocker(s.facts()) : "";
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Updates::confirm() {
    if (auto refused = CheckCaller()) return std::move(*refused);
    std::lock_guard<std::mutex> lock(g_lock);
    const State s = ReadState();
    LOG(INFO) << "System updates confirms " << s.running.build;
    return ConfirmLocked(s, "owner");
}

ndk::ScopedAStatus Updates::restorePrevious() {
    if (auto refused = CheckCaller()) return std::move(*refused);
    std::lock_guard<std::mutex> lock(g_lock);
    const State s = ReadState();
    if (s.hal == nullptr) return Failed("no boot control HAL");
    if (s.state() != IUpdates::STATE_WAITING) {
        return Refused("the running build does not wait for confirmation");
    }
    const std::string blocker = RestoreBlocker(s.facts());
    if (!blocker.empty()) return Refused("the previous build cannot be restored: " + blocker);
    // Clears the slot's "successful" flag and gives it the bootloader's retries: if the previous
    // build cannot start, the bootloader falls back to the running one, whose snapshots are only
    // cancelled once the previous build has booted.
    const auto result = s.hal->SetActiveBootSlot(s.other_index);
    if (!result.IsOk()) {
        DropBootControl();
        return Failed("boot control: " + result.errMsg);
    }
    if (s.hal->GetActiveBootSlot() != s.other_index) {
        DropBootControl();
        return Failed("the boot control HAL did not keep slot " + s.other + " active");
    }
    LOG(WARNING) << "slot " << s.other << " (" << s.other_record->build
                 << ") starts at the next restart; the update to " << s.running.build
                 << " is cancelled once it has started";
    NotifyChanged();
    return ndk::ScopedAStatus::ok();
}

}  // namespace lujarch::updates
