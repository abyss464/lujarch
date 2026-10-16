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

// The desktop on the phone's screen (LNX-6): the relay of the display protocol
// (src/linux/desktop/protocol/lujarch_display.h) between the Terminal app and KWin's Lujarch
// backend in Linux, the FIFO the desktop's sound comes out of, and the desktop's cgroup, frozen
// while the desktop is not on screen.
//
// The producer connects to kDisplaySocket in Linux, as the owner's user or root, and sends its
// request line; the app's connection is one end of a socket pair from Attach(). Every message
// goes on with its fds, every fence as a sync_file the daemon makes, and each side hears of the
// other (LJD_PEER). The desktop is on screen while an app is attached and its last configure
// says so.
#pragma once

#include <android-base/result.h>
#include <android-base/unique_fd.h>

#include <chrono>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace lujarch::linux {

using android::base::Result;
using android::base::unique_fd;

class Display {
  public:
    void Start();

    // Linux started (the socket and the sound's FIFO appear) or stopped (the connections end).
    void SetLinux(bool running);

    // The app's end of a new connection; it replaces the app's previous one.
    Result<unique_fd> Attach();

    // The read end of the sound's FIFO: 16-bit little-endian PCM, 48 kHz, stereo, without what
    // was played before.
    Result<unique_fd> OpenSound();

  private:
    using Clock = std::chrono::steady_clock;

    struct Out {
        std::string bytes;
        std::vector<unique_fd> fds;
        size_t sent = 0;
    };

    struct Conn {
        unique_fd fd;
        bool producer = false;
        // A producer before its request line.
        bool greeting = false;
        Clock::time_point deadline;
        std::string in;
        std::deque<unique_fd> in_fds;
        std::deque<Out> out;
        size_t out_bytes = 0;
    };

    void Wake();
    void Loop();
    void Arrange(bool running);
    void Listen();
    void Accept();
    void Greet(Conn& c);
    void Produce(std::unique_ptr<Conn> c);
    void AttachApp(unique_fd fd);
    // Reads what c sent; false when c was closed.
    bool Readable(Conn& c);
    bool Parse(Conn& c);
    bool Send(Conn& c, std::string bytes, std::vector<unique_fd> fds);
    bool Flush(Conn& c);
    void Peer(Conn& c, bool connected);
    void Close(Conn& c, const std::string& why);
    void ApplyFrozen();

    std::mutex mutex_;
    bool linux_wanted_ = false;
    std::vector<unique_fd> attached_;
    unique_fd wake_;

    // The loop's thread only.
    bool linux_ = false;
    unique_fd listener_;
    std::vector<std::unique_ptr<Conn>> greeting_;
    std::unique_ptr<Conn> producer_;
    std::unique_ptr<Conn> app_;
    bool app_visible_ = false;
};

}  // namespace lujarch::linux
