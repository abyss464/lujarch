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

// Requests to the sync engine's REST API, signed here with its API key so that no app sees it.
// The engine listens on a unix socket in its home, which only its own user and group (the daemon
// has the group) may connect to.
#pragma once

#include <chrono>
#include <cstdint>
#include <string>
#include <string_view>

#include <android-base/result.h>
#include <json/value.h>

#include <lujarch/http.h>
#include "outcome.h"

namespace lujarch::sync::rest {

inline constexpr char kHome[] = "/data/lujarch/sync";
inline constexpr char kConfigFile[] = "/data/lujarch/sync/config.xml";
inline constexpr char kSocket[] = "/data/lujarch/sync/rest.sock";
// The sync storage (SYN-8), the only place the engine's domain may keep synced folders: one
// directory per folder, labeled lujarch_sync_folder_file.
inline constexpr char kFoldersDir[] = "/data/lujarch/sync/folders";

// Error codes ("code" in a refusal or failure, docs/settings.md).
inline constexpr char kCodeNotAllowed[] = "not_allowed";  // outside the allowlist
inline constexpr char kCodeStopped[] = "sync_stopped";    // the service is not running
inline constexpr char kCodeUnreachable[] =
        "sync_unreachable";                                  // running, but its API does not answer
inline constexpr char kCodeFolderInUse[] = "folder_in_use";  // another folder uses that directory
inline constexpr char kCodeNoFolder[] = "no_folder";         // no folder with that ID
// sync.folder.move of a folder type that a new, empty index never fills
// (send-only, IsMovableType).
inline constexpr char kCodeNotMovable[] = "folder_not_movable";
// Override of a send-only folder that lacks items the other devices have:
// Syncthing announces them as deleted on every device (OverrideDeletes).
// The request is carried out when its body is {"deleteMissing": true}.
inline constexpr char kCodeOverrideDeletes[] = "override_deletes";
// A versions directory inside the directory of a synced folder, or a folder
// in one (VersionsConflict): the versions would be synced like any file.
inline constexpr char kCodeVersionsInFolder[] = "versions_in_folder";

// Checks a sync.api request against the allowlist (docs/settings.md,
// "Syncthing requests"). Returns why it is refused, or "" when allowed.
// body is null when the request has none.
std::string CheckApi(std::string_view method, std::string_view target, const Json::Value& body);

// A directory name under kFoldersDir that every part of the system can
// open: [A-Za-z0-9._ -], 1 to 255 bytes, not starting with '.' (the rule
// of the vault's folder names, vault::IsFileName).
bool IsFolderDirName(std::string_view name);

// A device ID as Syncthing prints it: base32 upper case in groups
// separated by '-' (52 or 56 characters without the dashes).
bool IsDeviceId(std::string_view id);

// An address this device dials another device at (a device's "addresses"):
// "dynamic" (found through discovery), tcp[46]:// or quic[46]:// with a
// host and an optional port (22000 when absent), or a relay://host:port
// with an optional ?id=<relay's device ID>&token=.... No other schemes (no
// file://, unix:// or http://), no user info, no path or query on a direct
// address.
bool IsDeviceAddress(std::string_view address);

// An entry of the options' "listenAddresses": "default" (TCP and QUIC on
// 22000 on every address plus the public relay pool), tcp[46]:// or
// quic[46]:// with a port (an empty host or 0.0.0.0 is every address, port
// 0 a free one), a relay://host:port as for IsDeviceAddress, or a relay
// pool dynamic+https://host[:port]/path (or dynamic+http).
bool IsListenAddress(std::string_view address);

// An entry of the options' "globalAnnounceServers": "default",
// "default-v4", "default-v6", or a discovery server (stdiscosrv)
// https://host[:port][/path] with the query members Syncthing reads (id
// with a device ID, insecure, noannounce, nolookup); plain http only with
// id or insecure and noannounce, as Syncthing itself requires.
bool IsDiscoveryServer(std::string_view server);

// A folder's device list in a change (devices), completed from the list
// Syncthing holds (current): an entry without "encryptionPassword" keeps
// the password Syncthing holds for that device; an entry with
// "encryptionPasswordFrom": D gets the folder as device D does (D's
// password, or unencrypted when D has none), for replacing D by another
// device. Returns why it is refused ("" when not): D does not share the
// folder.
std::string KeepPasswords(const Json::Value& current, Json::Value* devices);

// The API key from the <gui> element of config.xml's content; an error
// (which never contains the key) when there is none or it is unusable.
android::base::Result<std::string> ExtractApiKey(std::string_view config_xml);

// One request with the API key; the reply is read until Syncthing closes.
android::base::Result<http::Reply> Call(std::string_view method, std::string_view target,
                                        std::string_view body, std::chrono::milliseconds timeout);

// Removes members that hold secrets (folder encryption passwords, the API
// key, the GUI password) from a reply, at any depth. A removed folder
// encryption password leaves "encryptionPasswordSet": whether one is set.
void Redact(Json::Value* value);

// A versioning "fsPath" as text: empty (the folder's .stversions), relative
// to the folder, or a directory under kFoldersDir (Syncthing's domain may
// write nowhere else). Where it may point, given the folders, is
// VersionsConflict's part.
bool IsVersionsPath(std::string_view path);

// Whether a folder of this type can be moved by removing it and adding it
// again at the new path. Syncthing drops the removed folder's index
// (model.removeFolder: DropFolder), so the new directory starts from an
// empty one: send-receive, receive-only and receive-encrypted folders pull
// what the other devices have (no deletion is announced: the empty index
// holds no delete records). A send-only folder never pulls
// (sendOnlyFolder.pull only merges files already identical on disk): it
// stays empty, and the only way out, Override, announces every file
// missing here as deleted on every device (sendOnlyFolder.override).
bool IsMovableType(std::string_view type);

// How many items an Override of a send-only folder would announce as
// deleted on every device, from its /rest/db/status: the global items the
// phone lacks, i.e. globalFiles + globalDirectories + globalSymlinks -
// (localFiles + localDirectories + localSymlinks) + needDeletes (local items
// that the other devices deleted count in local but not in global; the
// override brings those back). -1 when the counts are missing.
int64_t OverrideDeletes(const Json::Value& status);

// Where a folder at folder_path keeps file versions with this versioning
// fsPath, as a clean absolute path; "" when they stay in its own
// .stversions (Syncthing's internal directory, never synced).
std::string VersionsDir(std::string_view folder_path, std::string_view fs_path);

// Syncthing's folder list (/rest/config/folders) after a change of folder
// id: members of change replace the folder's (a PATCH; "versioning" member
// by member, as Syncthing decodes a patch onto the folder), or the folder is
// added when the list has no such ID.
Json::Value FoldersAfter(const Json::Value& folders, const std::string& id,
                         const Json::Value& change);

// Why the versions of a folder would be synced, or "": for the pairs that
// involve folder id, a versions directory (VersionsDir, while versioning
// is on) inside any folder's directory, or a folder's directory inside one.
std::string VersionsConflict(const Json::Value& folders, const std::string& id);

// Why sync.folder.move of folder id to target is refused (with *code), or
// "": no such folder, target used by a folder, a type IsMovableType
// refuses, or a versions directory that would end up in a synced folder.
std::string CheckMove(const Json::Value& folders, const std::string& id, const std::string& target,
                      std::string* code);

// Completes a checked sync.api request before it goes to Syncthing: a
// folder's new device list keeps the encryption password Syncthing holds
// for each device (the pages never see them) unless the entry sets one ("":
// shared unencrypted again) or copies another device's (KeepPasswords); a
// receive-encrypted folder keeps its type; a
// new or changed versions path, or a new folder, must not put versions in a
// synced directory (kCodeVersionsInFolder); an Override that would delete
// items on the other devices needs {"deleteMissing": true}
// (kCodeOverrideDeletes), and goes to Syncthing without a body. Returns ""
// or why it is refused (with *code).
std::string Prepare(std::string_view method, std::string_view target, Json::Value* body,
                    std::string* code);

// The code of a failed request: kCodeStopped while the engine is not
// running, kCodeUnreachable otherwise.
std::string FailureCode();

// Whether the engine runs, as the supervisor tells it.
void SetRunning(bool running);

// ---- commands ----

// sync.dirs: the directories under kFoldersDir, for the folder picker.
Outcome CmdDirs(const Json::Value& request, uid_t uid);

// sync.ignored: the devices Syncthing ignores (remoteIgnoredDevices, which
// only the whole configuration holds): list, add or remove one.
Outcome CmdIgnored(const Json::Value& request, uid_t uid);

// sync.folder.move {"folder": ID, "dir": NAME}: the folder's local
// directory becomes kFoldersDir/NAME. Syncthing restarts a folder whose path
// changes with its old index, and would announce every file missing from
// the new directory as deleted; so the folder is removed and added again
// with the same ID and settings (devices with their encryption passwords,
// ignore patterns copied, paused until they are in place): the new
// directory is scanned from an empty index and filled from the other
// devices. The old directory keeps its files. Refused as CheckMove says
// (send-only folders: kCodeNotMovable).
Outcome CmdFolderMove(const Json::Value& request, uid_t uid);

}  // namespace lujarch::sync::rest
