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

// The parts of the phone Linux is lent (LNX-7). Each one exists in Linux only while its switch
// is on, under /run/lujarch (root, the owner's group):
//
//   notifications  file: {"count":N,"notifications":[{"app","title","text","time"}]}, Android's
//                  notifications as the Terminal app's listener hands them over
//   sensors        FIFO: while a program reads it, one JSON line a second with the motion and
//                  environment sensors' last values; the sensors run only then
//   ir             FIFO: a line "<carrier Hz> <on µs> <off µs> ..." transmits through the
//                  infrared transmitter (the consumer IR HAL)
//   auth           FIFO: a line "<reply> <text>" asks the owner, through the Terminal app on
//                  screen, to confirm an authorization with the fingerprint or the screen lock;
//                  the FIFO auth.d/<reply>, which the asking program made, gets "allow" or
//                  "deny" (deny after a minute)
//   nfc            FIFO: a line "<reply> <command in hex>" sends a command to the card NFC reads
//                  through the Terminal app on screen; nfc.d/<reply> gets "<answer in hex>" or
//                  "error <why>" (after ten seconds at the latest); nfc-card holds the card's
//                  kind and ID while one is held to the phone
//
// The shared clipboard is the terminal's and the desktop's (the Terminal app). USB devices are
// /dev/bus/usb in Linux and the owner's user in the group aid_usb (1018) of its /etc/group.
#pragma once

#include <aidl/org/lujarch/linux/LinuxNotification.h>
#include <android-base/unique_fd.h>

#include <chrono>
#include <functional>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "system.h"

namespace lujarch::linux {

class Bridge {
  public:
    struct Asks {
        // The Terminal app is there to answer; false: requests are refused at once.
        std::function<bool()> answerable;
        std::function<void(int id, const std::string& text)> auth;
        std::function<void(int id, const std::vector<uint8_t>& command)> nfc;
    };

    Bridge(System& system, Asks asks);

    void Start();

    // Linux started, with the owner's user, or stopped: the lent parts appear or go.
    void SetLinux(bool running, const std::string& user);

    // A switch's mirror changed.
    void Recheck();

    void SetNotifications(const std::vector<aidl::org::lujarch::linux::LinuxNotification>& list);
    void AnswerAuth(int id, bool allowed);
    void SetNfcCard(const std::optional<std::string>& card);
    void AnswerNfc(int id, const std::vector<uint8_t>& response,
                   const std::optional<std::string>& error);

  private:
    using Clock = std::chrono::steady_clock;

    struct Pending {
        std::string reply;
        Clock::time_point deadline;
    };

    void Loop();
    void Wake();
    // Makes the lent parts as the switches say; under mutex_.
    void Arrange();
    void Lines(android::base::unique_fd& fd, std::string& buffer,
               const std::function<void(const std::string&)>& line);
    void Ir(const std::string& line);
    void Auth(const std::string& line);
    void Nfc(const std::string& line);
    void Reply(const char* dir, const std::string& reply, const std::string& text);
    void WriteNotifications();
    void SensorsReader();
    void SensorsStop();
    void SensorsWrite();
    Result<void> UsbGroup(bool lend);

    System& system_;
    Asks asks_;
    std::mutex mutex_;
    bool linux_ = false;
    std::string user_;
    bool notifications_lent_ = false;
    bool usb_checked_ = false;
    std::vector<aidl::org::lujarch::linux::LinuxNotification> notifications_;
    std::optional<std::string> card_;
    std::map<int, Pending> auth_;
    std::map<int, Pending> nfc_;
    int next_id_ = 1;
    android::base::unique_fd wake_;
    android::base::unique_fd ir_;
    android::base::unique_fd auth_fifo_;
    android::base::unique_fd nfc_fifo_;
    std::string ir_buffer_;
    std::string auth_buffer_;
    std::string nfc_buffer_;
    android::base::unique_fd inotify_;
    int sensors_watch_ = -1;
    android::base::unique_fd sensors_out_;
    void* sensors_queue_ = nullptr;
    std::map<int, std::vector<float>> sensor_values_;
    bool usb_lent_ = false;
};

}  // namespace lujarch::linux
