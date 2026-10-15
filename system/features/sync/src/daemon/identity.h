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

// The sync engine's device identity (certificate, key and configuration) chosen in the Sync pages
// (SYN-7): imported from files, created anew, or restored from a backup. The daemon checks what the
// owner chose and installs it while the engine is stopped, as the engine's user, so the files get
// its owner, modes and label; the identity it replaces goes into a backup. Key material is never
// logged; buffers holding it are cleansed.
#pragma once

#include <functional>
#include <map>
#include <string>
#include <string_view>
#include <vector>

#include <android-base/result.h>
#include <json/value.h>

#include "outcome.h"

namespace lujarch::sync::identity {

inline constexpr char kBackupsDir[] = "/data/lujarch/sync/identity-backups";
// config.xml version written by Syncthing v2.1.5 (lib/config CurrentVersion);
// a newer Syncthing migrates it like any older config.
inline constexpr int kConfigVersion = 52;
inline constexpr size_t kMaxPem = 16u << 10;
inline constexpr size_t kMaxConfig = 48u << 10;

// Syncthing's device ID of a DER certificate: base32 of its SHA-256 with a
// Luhn mod 32 check character after every 13 characters, in groups of 7.
std::string DeviceIdFromDer(std::string_view der);

// Checks that cert_pem holds a certificate and key_pem an unencrypted
// private key of that certificate; returns the device ID. Errors never
// contain the input.
android::base::Result<std::string> CheckPair(std::string_view cert_pem, std::string_view key_pem);

enum class ConfigSource {
    kImported,  // from another installation: new API key, folders moved under the folders root
    kKept,      // this device's own config.xml: API key and folder paths kept
};

struct Rewritten {
    std::string xml;
    // Folder ID -> directory name under the folders root (kImported only).
    std::map<std::string, std::string> folders;
};

// config.xml as this system runs it: the REST API on the unix socket
// (mode 660, no TLS), no upgrades, usage and crash reporting off, new
// folders under the folders root with ignorePerms (the daemon reads them
// through the group). Errors never contain the input.
android::base::Result<Rewritten> RewriteConfig(std::string_view xml, ConfigSource source);

// A minimal config.xml for a new identity (Syncthing fills in its defaults
// and creates the certificate and key at start).
std::string TemplateConfig();

// A backup's directory name as the daemon makes it: YYYYMMDD-HHMMSS,
// optionally followed by -N.
bool IsBackupName(std::string_view name);

// ---- commands ----

Outcome CmdStatus(const Json::Value& request, uid_t uid);
Outcome CmdImport(const Json::Value& request, uid_t uid);
Outcome CmdGenerate(const Json::Value& request, uid_t uid);
Outcome CmdRestore(const Json::Value& request, uid_t uid);

// Runs install while the engine is stopped and starts it again when sync is on; returns whether it
// runs again. Given by the supervisor.
using EngineStop = std::function<android::base::Result<bool>(
        const std::function<android::base::Result<void>()>& install)>;
void SetEngineStop(EngineStop stop);

}  // namespace lujarch::sync::identity
