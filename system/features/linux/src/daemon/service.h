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

// ILinux for Lujarch Settings and the Terminal app, and the life of the installed system: it
// runs while Use Linux development environment is on (LNX-1), from every start of the phone,
// and stops at once when it goes off. The first start of a newly installed system runs the
// initial setup (LNX-2), which makes the owner's user with aid::kLinux; an install unpacks the
// archive beside the running system and replaces it, which is kept as root.<its install time>.
// The desktop's install (LNX-6) runs in Linux like the setup, while Linux goes on running.
#pragma once

#include <aidl/org/lujarch/linux/BnLinux.h>
#include <android-base/unique_fd.h>

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "bridge.h"
#include "display.h"
#include "remote.h"
#include "services.h"
#include "sessions.h"
#include "system.h"

namespace lujarch::linux {

class Service : public aidl::org::lujarch::linux::BnLinux {
  public:
    Service();

    // Starts the threads and follows the switches.
    void Start();

    ndk::ScopedAStatus getStatus(aidl::org::lujarch::linux::Status* status) override;
    ndk::ScopedAStatus registerListener(
            const std::shared_ptr<aidl::org::lujarch::linux::ILinuxListener>& listener) override;
    ndk::ScopedAStatus unregisterListener(
            const std::shared_ptr<aidl::org::lujarch::linux::ILinuxListener>& listener) override;
    ndk::ScopedAStatus createSession(const std::string& kind, const std::string& name,
                                     int32_t columns, int32_t rows, std::string* id) override;
    ndk::ScopedAStatus attachSession(const std::string& id, int32_t columns, int32_t rows,
                                     ndk::ScopedFileDescriptor* stream) override;
    ndk::ScopedAStatus resizeSession(const std::string& id, int32_t columns, int32_t rows) override;
    ndk::ScopedAStatus renameSession(const std::string& id, const std::string& name) override;
    ndk::ScopedAStatus setSessionAwake(const std::string& id, bool awake) override;
    ndk::ScopedAStatus endSession(const std::string& id) override;
    ndk::ScopedAStatus install(const ndk::ScopedFileDescriptor& archive,
                               const std::string& user) override;
    ndk::ScopedAStatus runSetup() override;
    ndk::ScopedAStatus installDesktop() override;
    ndk::ScopedAStatus listServices(
            std::vector<aidl::org::lujarch::linux::LinuxService>* services) override;
    ndk::ScopedAStatus controlService(const std::string& name, const std::string& action) override;
    ndk::ScopedAStatus setServiceStartsWithLinux(const std::string& name, bool starts) override;
    ndk::ScopedAStatus readServiceLog(const std::string& name, std::string* log) override;
    ndk::ScopedAStatus listServiceFiles(const std::string& name,
                                        std::vector<std::string>* files) override;
    ndk::ScopedAStatus readServiceFile(const std::string& name, const std::string& file,
                                       std::string* text) override;
    ndk::ScopedAStatus getRemote(aidl::org::lujarch::linux::RemoteStatus* status) override;
    ndk::ScopedAStatus setRemote(const aidl::org::lujarch::linux::RemoteConfig& config) override;
    ndk::ScopedAStatus getColors(std::string* colors) override;
    ndk::ScopedAStatus setColors(const std::string& colors) override;
    ndk::ScopedAStatus attachDisplay(ndk::ScopedFileDescriptor* connection) override;
    ndk::ScopedAStatus openSound(ndk::ScopedFileDescriptor* sound) override;
    ndk::ScopedAStatus setClipboard(const std::string& text) override;
    ndk::ScopedAStatus setNotifications(
            const std::vector<aidl::org::lujarch::linux::LinuxNotification>& list) override;
    ndk::ScopedAStatus answerAuth(int32_t id, bool allowed) override;
    ndk::ScopedAStatus setNfcCard(const std::optional<std::string>& card) override;
    ndk::ScopedAStatus answerNfc(int32_t id, const std::vector<uint8_t>& response,
                                 const std::optional<std::string>& error) override;
    ndk::ScopedAStatus setAndroidState(const aidl::org::lujarch::linux::AndroidState& state) override;
    ndk::ScopedAStatus openStream(const std::string& stream, ndk::ScopedFileDescriptor* fd) override;
    ndk::ScopedAStatus setLocation(const aidl::org::lujarch::linux::LinuxLocation& location) override;

  private:
    struct Listener {
        std::shared_ptr<aidl::org::lujarch::linux::ILinuxListener> listener;
        uid_t uid;
        bool terminal;
    };

    // The persistent state: the installed system and the last action.
    struct State {
        int64_t installed_at = 0;
        std::string user;
        bool setup_done = false;
        std::string action;
        bool action_ok = false;
        std::string action_message;
        int64_t action_time = 0;
    };

    void Loop();
    void Wake();
    void WatchProperty(const std::string& name);
    // Makes the system's life follow the switches and the install; on the supervisor's thread.
    void Reconcile();
    void StartLinux();
    void StopLinux();
    // The initial setup (ILinux::ACTION_SETUP) or the desktop's install (ILinux::ACTION_DESKTOP)
    // in Linux, as root.
    void StartJob(const std::string& kind);
    void JobEnded(int status);
    void Replace();
    void InstallThread(android::base::unique_fd archive, std::string user);
    // KernelSU's grant for root sessions in Android, applied by its own thread through lujarch_root.
    void SetKernelSuRoot(bool allowed);
    void KernelSuRootLoop();
    // Lets KernelSU see a root session in Android before it becomes aid::kLinuxRoot (System::Start).
    static Result<void> MarkForKernelSu(pid_t pid);
    void SetState(const std::string& state, const std::string& reason = "");
    void SaveState();
    void Changed();
    void Exited(const std::string& id, int status);
    void PruneListeners();
    static void OnListenerDied(void* cookie);
    void ForTerminal(const std::function<void(aidl::org::lujarch::linux::ILinuxListener&)>& call);

    System system_;
    Sessions sessions_;
    Services services_;
    Remote remote_;
    Bridge bridge_;
    Display display_;

    std::mutex mutex_;
    State persisted_;
    std::string state_;
    std::string reason_;
    bool action_running_ = false;
    std::atomic<int64_t> action_done_ = 0;
    int64_t action_total_ = 0;
    // An unpacked system waits for the running one to stop.
    bool replace_pending_ = false;
    std::string replace_user_;
    // Linux could not start; it stays stopped until the switch or the system changes.
    bool held_ = false;
    std::string memory_;
    bool swap_ = false;
    bool root_sessions_ = false;
    bool root_sessions_known_ = false;

    Process job_;
    std::string job_kind_;
    android::base::unique_fd job_output_;
    android::base::unique_fd job_log_;
    std::string job_partial_;
    std::string job_last_line_;
    bool setup_requested_ = false;
    bool desktop_requested_ = false;
    // The services, Remote access and the lent parts follow Linux once the setup ended.
    bool linux_ready_ = false;

    std::mutex listeners_mutex_;
    std::vector<Listener> listeners_;
    ndk::ScopedAIBinder_DeathRecipient death_;

    std::mutex root_mutex_;
    std::condition_variable root_cv_;
    std::optional<bool> root_pending_;

    android::base::unique_fd wake_;
};

}  // namespace lujarch::linux
