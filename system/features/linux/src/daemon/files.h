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

// Files of the daemon and of the installed system. Programs in Linux, root among them, may link
// anything anywhere in their system: a path in it is always resolved inside it, so that no link
// leads the daemon out of it.
#pragma once

#include <android-base/result.h>
#include <android-base/unique_fd.h>
#include <sys/types.h>

#include <map>
#include <string>
#include <string_view>

namespace lujarch::linux::files {

using android::base::Result;
using android::base::unique_fd;

// The installed system's root directory (O_PATH).
Result<unique_fd> OpenRoot(const std::string& root);

// path, absolute in the system whose root is root, with its links resolved inside the system.
Result<unique_fd> OpenIn(int root, std::string_view path, int flags, mode_t mode = 0);

Result<std::string> ReadIn(int root, std::string_view path, size_t max);

// Writes the file whole: a new file beside it renamed over it, with mode and owner.
Result<void> WriteIn(int root, std::string_view path, std::string_view data, mode_t mode,
                     uid_t uid = 0, gid_t gid = 0);

// Makes the directory and its missing parents; those it makes get mode and owner.
Result<void> MakeDirsIn(int root, std::string_view path, mode_t mode, uid_t uid = 0,
                        gid_t gid = 0);

Result<void> RemoveIn(int root, std::string_view path);

// Makes a FIFO at path in place of what is there, owned by root and gid, with mode.
Result<void> MakeFifoIn(int root, std::string_view path, mode_t mode, gid_t gid);

bool ExistsIn(int root, std::string_view path);

// The parent directory of path in the system, and path's last name.
Result<unique_fd> OpenParentIn(int root, std::string_view path, std::string* name);

// Removes what dir holds under name, all of it. Directories of other users are entered with
// their owner's file system IDs: no domain of the system bypasses permissions.
Result<void> RemoveTree(int dir, const std::string& name);

// Removes what dir holds, keeping dir.
Result<void> EmptyDir(int dir);

// Android's side: the daemon's own files.
Result<std::string> ReadFile(const std::string& path, size_t max);
Result<void> WriteFile(const std::string& path, std::string_view data, mode_t mode,
                       uid_t uid = 0, gid_t gid = 0);

// "key: value" lines; values are single lines.
std::map<std::string, std::string> ReadKeyValues(const std::string& path);
Result<void> WriteKeyValues(const std::string& path, const std::map<std::string, std::string>& values,
                            mode_t mode);

// A name of one path component: letters, digits, '.', '_', '-', '@', '+'; not "." or "..".
bool IsPlainName(std::string_view name);

}  // namespace lujarch::linux::files
