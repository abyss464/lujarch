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

// The running system: its mount namespace, its programs, its share of memory and its swap.
//
// Linux runs beside Android on the same kernel, network and process IDs, in a mount namespace of
// its own whose root is the installed system. The namespace holds only binds (no domain of the
// system may mount a file system): Android's /proc, /sys and /dev/pts, single device nodes (the
// GPU's among them, for the desktop), Android's fonts at /usr/share/fonts/android and
// /usr/share/fonts/android-product, Lujarch's files for Linux, /dev/bus/usb while USB devices
// are lent and the cameras' nodes while the cameras are. A child of the daemon
// that made it holds the namespace and dies with the daemon; every program of Linux enters it
// through that child's pidfd and starts through
// lujarch_linux_enter, which enters the system's domain, changes its root to the system and
// becomes the program's user. Every program of the environment, Android's root sessions among
// them, runs in the cgroup kCgroup, whose memory.high is the Memory switch (LNX-5).
#pragma once

#include <android-base/result.h>
#include <android-base/unique_fd.h>
#include <sys/types.h>

#include <functional>
#include <mutex>
#include <string>
#include <vector>

namespace lujarch::linux {

using android::base::Result;
using android::base::unique_fd;

struct Process {
    pid_t pid = -1;
    unique_fd pidfd;
};

struct Spawn {
    enum class As {
        kUser,         // the owner's user (aid::kLinux) in Linux
        kRoot,         // root in Linux
        kAndroidRoot,  // root in Android, through KernelSU's su
    };
    As as = As::kRoot;
    // The program in Linux and its arguments; empty with login.
    std::vector<std::string> argv;
    // The user's login shell.
    bool login = false;
    // The working directory in Linux; empty: the user's home.
    std::string cwd;
    // Groups besides those /etc/group gives the user.
    std::vector<gid_t> groups;
    // NAME=value
    std::vector<std::string> env;
    // A terminal, open, which becomes the program's controlling terminal and standard streams;
    // otherwise the three descriptors (-1: /dev/null).
    int tty = -1;
    int in = -1;
    int out = -1;
    int err = -1;
    // It runs in kDesktopCgroup instead of kCgroup.
    bool desktop = false;
    // Called with the program's process before it goes on; an error ends the start.
    std::function<Result<void>(pid_t)> prepare;
};

class System {
  public:
    // Makes the namespace of the installed system; usb: /dev/bus/usb is lent.
    Result<void> Create(bool usb);
    // Lets go of the namespace; it ends with the last program in it.
    void Drop();
    bool Created();

    // Starts a program; it runs in a session of its own.
    Result<Process> Start(const Spawn& spawn);

    // Binds Android's /dev/bus/usb into the namespace, or takes it away.
    Result<void> LendUsb(bool lend);

    // Binds the cameras' device nodes (kCameraNodes) Android has into the namespace's /dev, or
    // takes them away; either any number of times.
    Result<void> LendCameras(bool lend);

    // Binds Android's shared storage (kSharedStorage) into the namespace as the folder Android in
    // the user's home, or takes it away and removes the folder while it is empty.
    Result<void> LendStorage(bool lend, const std::string& user);

  private:
    // Enters the namespace from a child of the daemon.
    std::mutex mutex_;
    Process anchor_;
};

// Starts an Android program as uid with the groups, outside the environment's cgroup; out takes
// its standard output and error.
Result<Process> StartAndroid(const std::string& path, std::vector<std::string> args, uid_t uid,
                             const std::vector<gid_t>& groups, int out);

// Ends a program started by Start: waits for it after it was told to end.
int Wait(Process& process);

// The exit status as the pages say it: the code, or 128 plus the signal.
int ExitCode(int wait_status);

namespace cgroup {

// Kills every program of the environment and waits until none is left (at most a few seconds).
void KillAll();

// memory.high for a Memory choice: android, balanced, linux or unlimited.
Result<void> SetMemory(const std::string& choice);

// Bytes in use, and the limit (0: unlimited).
int64_t MemoryUsed();
int64_t MemoryLimit();

// The desktop's group is frozen while SetDesktopFrozen says so and no hold keeps it thawed: a
// program starting in it, or the desktop service while it stops.
void SetDesktopFrozen(bool frozen);
void HoldDesktop(bool hold);
// Kills every program of the desktop's group.
void KillDesktop();

}  // namespace cgroup

// The phone's memory in bytes.
int64_t MemoryTotal();

namespace swap {

// Makes the swap file when it is missing and swaps on it.
Result<void> On();
Result<void> Off();
// Swaps off and removes the file.
Result<void> Remove();
bool Active();
int64_t Size();

}  // namespace swap

}  // namespace lujarch::linux
