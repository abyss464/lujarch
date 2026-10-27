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
//   clipboard      file: Android's clipboard text as the Terminal app last saw it
//   clipboard-set  FIFO: a line, text in base64, becomes Android's clipboard through the
//                  Terminal app
//   microphone     FIFO: while a program reads it, Android's microphone records into it through
//                  the Terminal app on screen: 16-bit little-endian PCM, 48 kHz, mono
//   camera-back,   FIFO: while a program reads one, that camera of the phone films into it
//   camera-front   through the Terminal app on screen: frames of "LJF1", the frame's size as a
//                  32-bit little-endian number, NV12 of 1280 x 720 (ILinux::STREAM_)
//   location       FIFO: while a program reads it, one JSON line for each of Android's fixes:
//                  {"lat","lon","time"[,"alt","acc","speed","bearing"]} or {"error":"permission"
//                  |"off"}
//   vibrate        FIFO: a line "<amplitude 0-255>:<ms>,..." (or "<ms>") vibrates the phone
//                  through the Terminal app on screen, as touch feedback
//   android        file: Android's switches and readings (AndroidState) as JSON, for the
//                  desktop's quick settings and status bar
//   android-set    FIFO: a line "<key> <value>" flips one of Android's switches through the
//                  Terminal app on screen (ILinuxListener.onAndroidSet)
//   terminal       FIFO, there while Linux runs, lent or not: a line "show" turns the desktop on
//                  screen to the terminal (ILinuxListener.onShowTerminal)
//
// The terminal's sessions share the clipboard in the Terminal app itself; the desktop shares it
// through clipboard and clipboard-set. USB devices are /dev/bus/usb in Linux and the owner's user
// in the group aid_usb (1018) of its /etc/group; the cameras are /dev/video40 (back) and
// /dev/video41 (front), v4l2loopback devices of Linux's user that Linux's desktop writes the
// camera FIFOs' frames into. The shared storage is the folder Android in the owner's home, which
// the owner's user reads and writes as a member of aid_media_rw (1023), and the file storage
// holds that folder's path while it is lent. Linux's /etc/localtime follows Android's time zone while Linux runs.
#pragma once

#include <aidl/org/lujarch/linux/AndroidState.h>
#include <aidl/org/lujarch/linux/LinuxLocation.h>
#include <aidl/org/lujarch/linux/LinuxNotification.h>
#include <android-base/unique_fd.h>

#include <array>
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
        std::function<void(const std::string& text)> clipboard;
        std::function<void(const std::string& key, const std::string& value)> android_set;
        std::function<void(const std::string& pattern)> vibrate;
        std::function<void(const std::string& stream, bool wanted)> stream;
        std::function<void()> show_terminal;
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
    // Android's clipboard as the Terminal app sees it; false while the clipboard is not lent.
    bool SetClipboard(const std::string& value);
    void AnswerNfc(int id, const std::vector<uint8_t>& response,
                   const std::optional<std::string>& error);
    // false while Android's quick settings are not lent.
    bool SetAndroidState(const aidl::org::lujarch::linux::AndroidState& state);
    void SetLocation(const aidl::org::lujarch::linux::LinuxLocation& location);
    // The write end of a stream a program reads now (ILinux::STREAM_), for the Terminal app.
    Result<unique_fd> OpenStream(const std::string& stream);
    // The streams programs read now.
    std::vector<std::string> Wanted();

  private:
    using Clock = std::chrono::steady_clock;

    struct Pending {
        std::string reply;
        Clock::time_point deadline;
    };

    // A FIFO that Android writes while a program in Linux reads it. The daemon holds a write end
    // from the program's open until the last reader leaves, so the readers see no end of file
    // while the Terminal app comes and goes.
    struct Reader {
        const char* name;
        // The switch's part: lend_<part>.
        const char* part;
        int ident;
        int watch = -1;
        unique_fd out;
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
    void Clipboard(const std::string& line);
    void AndroidSet(const std::string& line);
    void ShowTerminal(const std::string& line);
    void Vibrate(const std::string& line);
    void WriteAndroidState();
    void ReaderOpened(Reader& r);
    void ReaderGone(Reader& r);
    void Timezone();
    void Reply(const char* dir, const std::string& reply, const std::string& text);
    void WriteNotifications();
    void SensorsReader();
    void SensorsStop();
    void SensorsWrite();
    // Makes the owner's user a member of the group id (name) in Linux's /etc/group, or not.
    Result<void> Group(gid_t id, const char* name, bool member);

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
    android::base::unique_fd clipboard_fifo_;
    std::string clipboard_buffer_;
    // The last text each side gave, so that neither gets its own text back.
    std::string android_clipboard_;
    std::string linux_clipboard_;
    android::base::unique_fd inotify_;
    int sensors_watch_ = -1;
    android::base::unique_fd sensors_out_;
    void* sensors_queue_ = nullptr;
    std::map<int, std::vector<float>> sensor_values_;
    bool usb_lent_ = false;
    bool cameras_lent_ = false;
    bool cameras_checked_ = false;
    bool storage_lent_ = false;
    bool storage_checked_ = false;
    std::array<Reader, 4> readers_;
    android::base::unique_fd vibrate_fifo_;
    std::string vibrate_buffer_;
    android::base::unique_fd android_set_fifo_;
    std::string android_set_buffer_;
    android::base::unique_fd terminal_fifo_;
    std::string terminal_buffer_;
    std::optional<aidl::org::lujarch::linux::AndroidState> android_state_;
    bool android_lent_ = false;
    std::string timezone_;
    // Starts of Linux, and the one Arrange made the parts for.
    uint64_t generation_ = 0;
    uint64_t arranged_ = 0;
};

}  // namespace lujarch::linux
