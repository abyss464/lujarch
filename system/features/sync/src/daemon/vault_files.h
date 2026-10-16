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

// Read access to the KeePass databases in the synced folders for Lujarch Vault (VLT-2). Every
// directory under kFoldersRoot is one synced folder; the vault finds the databases (.kdbx files)
// in them and opens the one the owner chose. No writes, no other files.
#pragma once

#include <stdint.h>

#include <map>
#include <string>
#include <string_view>
#include <vector>

#include <android-base/result.h>
#include <android-base/unique_fd.h>
#include <json/value.h>

namespace lujarch::sync::vault {

// The sync storage: one directory per synced folder, labeled lujarch_sync_folder_file.
inline constexpr char kFoldersRoot[] = "/data/lujarch/sync/folders";
// The largest database served: the decompressed payload limit of the
// vault's KDBX reader (KdbxReader.MAX_XML_BYTES); a larger file cannot be
// opened there anyway. Read hands it out in parts of at most kMaxChunk.
inline constexpr uint64_t kMaxDatabaseSize = 64u << 20;
inline constexpr uint64_t kMaxChunk = 4u << 20;
// Databases are served down to this many directory levels below a folder's
// top level; Find looks down to kMaxWalkDepth only to report deeper ones.
inline constexpr int kMaxDepth = 8;
inline constexpr int kMaxWalkDepth = 16;
// Find stops after this many databases or directory entries.
inline constexpr size_t kMaxFound = 256;
inline constexpr size_t kMaxVisited = 20000;
// At most this many skipped databases are reported.
inline constexpr size_t kMaxSkipped = 64;

// A path component vault.* serves or lists: valid UTF-8 (the replies are
// JSON), 1 to 255 bytes, no '/', not "." or "..", and not one of
// Syncthing's own entries (.stfolder, .stversions, .stignore and its temp
// files .syncthing.* and ~syncthing~*).
bool IsFileName(std::string_view name);

// Strict UTF-8: no overlong forms, surrogates or code points past U+10FFFF.
bool IsUtf8(std::string_view text);

// A path relative to a folder: 1 to kMaxDepth + 1 IsFileName components
// separated by single '/', at most kMaxPathSize bytes.
inline constexpr size_t kMaxPathSize = 4096;
bool IsRelativePath(std::string_view path);

// A KeePass database by name: ends in ".kdbx", any case.
bool IsDatabaseName(std::string_view name);

// Absolute path of folder under root; folder must pass IsFileName.
std::string FolderPath(std::string_view folder, const std::string& root = kFoldersRoot);

struct Entry {
    std::string name;
    uint64_t size = 0;
    int64_t mtime_ms = 0;  // modification time, ms since the epoch
};

// A part of a database file and the facts of the whole file; a client
// reading it in parts compares size, mtime_ms and inode across the parts to
// notice a file replaced in between (Syncthing renames a new version over it).
struct File {
    std::string data;
    uint64_t offset = 0;
    uint64_t size = 0;  // of the whole file
    int64_t mtime_ms = 0;
    uint64_t inode = 0;
};

struct Found {
    std::string folder;  // directory name under the root
    std::string path;    // relative to the folder
    uint64_t size = 0;
    int64_t mtime_ms = 0;
};

// A database Find saw but cannot serve, and why.
enum class SkipReason {
    kName,        // the name is not valid UTF-8
    kTooLarge,    // larger than kMaxDatabaseSize
    kTooDeep,     // more than kMaxDepth directory levels down
    kSymlink,     // a symbolic link
    kNotRegular,  // a FIFO, socket or device with a database name
};
const char* SkipReasonName(SkipReason reason);

struct Skipped {
    std::string folder;
    std::string path;  // relative to the folder; may be invalid UTF-8 (kName)
    SkipReason reason;
    uint64_t size = 0;
};

struct FindResult {
    std::vector<std::string> folders;  // every folder, sorted
    std::vector<Found> files;          // sorted by folder, then path
    std::vector<Skipped> skipped;      // sorted by folder, then path
    bool truncated =
            false;  // a limit (kMaxFound, kMaxVisited, kMaxWalkDepth, kMaxSkipped) was reached
};

// Regular files at the top level of folder_dir whose names IsFileName
// accepts, sorted by name. Symlinks, directories and other names are left out.
android::base::Result<std::vector<Entry>> List(const std::string& folder_dir);

// The databases (IsDatabaseName, regular files of at most kMaxDatabaseSize)
// in every folder under root, down to kMaxDepth directory levels. Entries
// with a database name that cannot be served (Skipped) are reported with the
// reason; Syncthing's own entries are not looked at. A folder that cannot be
// read is left out, not an error.
android::base::Result<FindResult> Find(const std::string& root = kFoldersRoot);

// folder_dir/path opened for reading, under the rules of Stat; refused for a file larger than
// kMaxDatabaseSize.
android::base::Result<android::base::unique_fd> Open(std::string_view path,
                                                     const std::string& folder_dir);

// Size and modification time of folder_dir/path; refused when path is not
// an IsRelativePath database name or not a regular file reached without
// symlinks.
android::base::Result<Entry> Stat(std::string_view path, const std::string& folder_dir);

// Up to length (at most kMaxChunk) bytes of folder_dir/path from offset, under
// the rules of Stat; refused for a file larger than kMaxDatabaseSize or an
// offset past its end.
android::base::Result<File> Read(std::string_view path, const std::string& folder_dir,
                                 uint64_t offset = 0, uint64_t length = kMaxChunk);

// Folder directory name -> Syncthing label (or id when the label is empty),
// from the array /rest/config/folders returns; folders outside root are left
// out.
std::map<std::string, std::string> FolderLabels(const Json::Value& folders,
                                                const std::string& root = kFoldersRoot);

std::string Sha256Hex(std::string_view data);
std::string Base64(std::string_view data);

}  // namespace lujarch::sync::vault
