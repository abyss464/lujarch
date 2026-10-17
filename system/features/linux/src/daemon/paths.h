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

// Where the Linux development environment keeps its parts, in Android and in Linux.
#pragma once

namespace lujarch::linux {

inline constexpr char kFeature[] = "linux";
inline constexpr char kServiceName[] = "lujarch_linux";
inline constexpr char kSettingsPackage[] = "org.lujarch.settings";
inline constexpr char kTerminalPackage[] = "org.lujarch.linux.terminal";

// Android's side. init makes the directories (lujarch_linux.rc).
inline constexpr char kData[] = "/data/lujarch/linux";
// The installed systems: root is the one that runs, root.<date> the ones kept beside it,
// root.new the one an install unpacks. init mounts the directory without nosuid.
inline constexpr char kSystems[] = "/data/lujarch/linux/os";
inline constexpr char kRoot[] = "/data/lujarch/linux/os/root";
inline constexpr char kStaging[] = "/data/lujarch/linux/os/root.new";
inline constexpr char kKeptPrefix[] = "root.";
inline constexpr char kStateFile[] = "/data/lujarch/linux/state";
inline constexpr char kColorsFile[] = "/data/lujarch/linux/colors.properties";
inline constexpr char kSetupLog[] = "/data/lujarch/linux/setup.log";
inline constexpr char kDesktopLog[] = "/data/lujarch/linux/desktop.log";
inline constexpr char kRemoteFile[] = "/data/lujarch/linux/remote";
inline constexpr char kRemoteKeysFile[] = "/data/lujarch/linux/remote-keys";
inline constexpr char kRelayDir[] = "/data/lujarch/linux/relay";
inline constexpr char kRelayConfig[] = "/data/lujarch/linux/relay/frpc.toml";
inline constexpr char kSwapFile[] = "/data/lujarch/linux/swap";
// HOME of root sessions in Android.
inline constexpr char kAndroidRootHome[] = "/data/lujarch/linux/home";
inline constexpr char kCgroup[] = "/sys/fs/cgroup/lujarch_linux";
// The desktop service's group in it, frozen while the desktop is not on screen.
inline constexpr char kDesktopCgroup[] = "/sys/fs/cgroup/lujarch_linux/desktop";
inline constexpr char kEnter[] = "/system_ext/bin/lujarch_linux_enter";
inline constexpr char kRelay[] = "/system_ext/bin/lujarch_linux_frpc";
// Lujarch's files for Linux (the initial setup, the fingerprint helper, the desktop's recipe), at
// kLinuxFiles in Linux.
inline constexpr char kFiles[] = "/system_ext/etc/lujarch/linux";

// Linux's side, as paths inside the system.
inline constexpr char kLinuxFiles[] = "/usr/lib/lujarch";
inline constexpr char kLinuxSetup[] = "/usr/lib/lujarch/setup";
inline constexpr char kLinuxDesktopInstall[] = "/usr/lib/lujarch/desktop/install";
inline constexpr char kServicesDir[] = "/etc/lujarch/services";
inline constexpr char kServiceLogDir[] = "/var/log/lujarch";
inline constexpr char kRemoteDir[] = "/etc/lujarch/remote";
inline constexpr char kBridgeDir[] = "/run/lujarch";
// The display protocol's socket (lujarch_display.h), and the FIFO the desktop plays its sound
// into.
inline constexpr char kDisplaySocket[] = "/run/lujarch/display";
inline constexpr char kSoundFifo[] = "/run/lujarch/sound";
// The cameras' v4l2loopback devices (Linux's user's), under /dev in Android and in Linux.
inline constexpr const char* kCameraNodes[] = {"video40", "video41"};
// Android's time zone, and the zone files of Linux it names.
inline constexpr char kTimezoneProperty[] = "persist.sys.timezone";
inline constexpr char kZoneinfo[] = "/usr/share/zoneinfo";
// The service that is the desktop: it runs in kDesktopCgroup.
inline constexpr char kDesktopService[] = "desktop";
inline constexpr char kShell[] = "/usr/bin/bash";
inline constexpr char kSshd[] = "/usr/bin/sshd";
inline constexpr char kSshKeygen[] = "/usr/bin/ssh-keygen";
inline constexpr int kSshPort = 2022;

}  // namespace lujarch::linux
