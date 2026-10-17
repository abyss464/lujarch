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

#include "vault_files.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <functional>
#include <memory>

#include <android-base/logging.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <openssl/base64.h>
#include <openssl/sha.h>

namespace lujarch::sync::vault {

using android::base::ErrnoError;
using android::base::Error;
using android::base::Result;
using android::base::unique_fd;

namespace {

int64_t MtimeMs(const struct stat& st) {
    return static_cast<int64_t>(st.st_mtim.tv_sec) * 1000 + st.st_mtim.tv_nsec / 1000000;
}

// A directory, never through a symlink (the last component; the ones above
// it are system paths).
Result<unique_fd> OpenDir(const std::string& path) {
    unique_fd fd(open(path.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (fd < 0) return ErrnoError() << "cannot open the folder " << path;
    return fd;
}

// Calls visit(name, st) for every entry of the directory fd except "." and
// ".." (st from lstat: symlinks are reported as such); entries removed
// between readdir and stat (Syncthing renames temp files over their targets)
// are skipped.
Result<void> ForEachEntry(
        int fd, const std::function<bool(const std::string&, const struct stat&)>& visit) {
    // fdopendir takes over the fd; the dup keeps fd for fstatat.
    const int dir_fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (dir_fd < 0) return ErrnoError() << "dup";
    std::unique_ptr<DIR, int (*)(DIR*)> dir(fdopendir(dir_fd), closedir);
    if (!dir) {
        close(dir_fd);
        return ErrnoError() << "cannot list the folder";
    }
    while (true) {
        errno = 0;
        const dirent* de = readdir(dir.get());
        if (de == nullptr) {
            if (errno != 0) return ErrnoError() << "cannot list the folder";
            return {};
        }
        const std::string name = de->d_name;
        if (name == "." || name == "..") continue;
        struct stat st;
        if (fstatat(fd, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0) {
            if (errno == ENOENT) continue;
            // One entry that cannot be examined does not hide the others. A
            // symbolic link is still reported as one (readdir's type).
            if (de->d_type != DT_LNK) {
                PLOG(WARNING) << "vault: cannot stat " << name << ", skipped";
                continue;
            }
            st = {};
            st.st_mode = S_IFLNK;
        }
        if (!visit(name, st)) return {};
    }
}

// Opens folder_dir/path component by component without following a
// symlink anywhere, and checks that it is a database file.
Result<unique_fd> OpenDatabase(std::string_view path, const std::string& folder_dir) {
    if (!IsRelativePath(path) || !IsDatabaseName(path)) return Error() << "invalid database path";
    auto dir = OpenDir(folder_dir);
    if (!dir.ok()) return dir.error();
    unique_fd current = std::move(*dir);
    const std::vector<std::string> parts = android::base::Split(std::string(path), "/");
    for (size_t i = 0; i + 1 < parts.size(); ++i) {
        unique_fd next(openat(current.get(), parts[i].c_str(),
                              O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        if (next < 0) {
            if (errno == ENOENT) return Error() << "no such file: " << path;
            if (errno == ELOOP || errno == ENOTDIR)
                return Error() << "not a regular file: " << path;
            return ErrnoError() << "cannot open " << path;
        }
        current = std::move(next);
    }
    // O_NONBLOCK: a FIFO planted under this name must not block the daemon.
    unique_fd fd(openat(current.get(), parts.back().c_str(),
                        O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC));
    if (fd < 0) {
        if (errno == ENOENT) return Error() << "no such file: " << path;
        if (errno == ELOOP) return Error() << "not a regular file: " << path;
        return ErrnoError() << "cannot open " << path;
    }
    return fd;
}

Result<struct stat> StatRegular(int fd, std::string_view path) {
    struct stat st;
    if (fstat(fd, &st) != 0) return ErrnoError() << "cannot stat " << path;
    if (!S_ISREG(st.st_mode)) return Error() << "not a regular file: " << path;
    return st;
}

// Syncthing's own entries in a folder: its marker, versions, ignore file and
// temp files (Unix and Windows prefixes).
bool IsSyncthingEntry(std::string_view name) {
    return name == ".stfolder" || android::base::StartsWith(name, ".stfolder.") ||
           name == ".stversions" || name == ".stignore" ||
           android::base::StartsWith(name, ".syncthing.") ||
           android::base::StartsWith(name, "~syncthing~");
}

struct Walk {
    FindResult* result;
    size_t visited = 0;

    void Skip(const std::string& folder, std::string path, SkipReason reason, uint64_t size) {
        if (result->skipped.size() >= kMaxSkipped) {
            result->truncated = true;
            return;
        }
        result->skipped.push_back(Skipped{folder, std::move(path), reason, size});
    }

    // depth: directory levels below the folder's top level; servable: every
    // directory on the way has a name a request can carry. false when a limit
    // is reached.
    bool Folder(int fd, const std::string& folder, const std::string& prefix, int depth,
                bool servable) {
        bool more = true;
        auto listed = ForEachEntry(fd, [&](const std::string& name, const struct stat& st) {
            if (++visited > kMaxVisited || result->files.size() >= kMaxFound) {
                result->truncated = true;
                more = false;
                return false;
            }
            if (IsSyncthingEntry(name)) return true;
            const std::string path = prefix + name;
            const bool name_ok = servable && IsUtf8(name);
            if (S_ISDIR(st.st_mode)) {
                if (depth + 1 > kMaxWalkDepth) {
                    result->truncated = true;
                    return true;
                }
                unique_fd sub(
                        openat(fd, name.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
                // A directory Syncthing just removed, or one without access: skipped.
                if (sub >= 0) more = Folder(sub.get(), folder, path + "/", depth + 1, name_ok);
                return more;
            }
            if (!IsDatabaseName(name)) return true;
            const auto size = static_cast<uint64_t>(st.st_size);
            if (S_ISLNK(st.st_mode)) {
                Skip(folder, path, SkipReason::kSymlink, 0);
            } else if (!S_ISREG(st.st_mode)) {
                Skip(folder, path, SkipReason::kNotRegular, 0);
            } else if (!name_ok) {
                Skip(folder, path, SkipReason::kName, size);
            } else if (depth > kMaxDepth || path.size() > kMaxPathSize) {
                Skip(folder, path, SkipReason::kTooDeep, size);
            } else if (size > kMaxDatabaseSize) {
                Skip(folder, path, SkipReason::kTooLarge, size);
            } else {
                result->files.push_back(Found{folder, path, size, MtimeMs(st)});
            }
            return true;
        });
        // One unreadable directory does not hide the databases elsewhere.
        if (!listed.ok()) {
            LOG(WARNING) << "vault.find: " << folder << "/" << prefix << ": "
                         << listed.error().message();
        }
        return more;
    }
};

}  // namespace

bool IsUtf8(std::string_view text) {
    size_t i = 0;
    while (i < text.size()) {
        const auto c = static_cast<unsigned char>(text[i]);
        if (c < 0x80) {
            ++i;
            continue;
        }
        // Lead byte: sequence length and the range of the second byte
        // (excludes overlong forms, surrogates and code points > U+10FFFF).
        size_t len = 0;
        unsigned char lo = 0x80, hi = 0xBF;
        if (c >= 0xC2 && c <= 0xDF) {
            len = 2;
        } else if (c >= 0xE0 && c <= 0xEF) {
            len = 3;
            if (c == 0xE0) lo = 0xA0;
            if (c == 0xED) hi = 0x9F;
        } else if (c >= 0xF0 && c <= 0xF4) {
            len = 4;
            if (c == 0xF0) lo = 0x90;
            if (c == 0xF4) hi = 0x8F;
        } else {
            return false;
        }
        if (i + len > text.size()) return false;
        for (size_t k = 1; k < len; ++k) {
            const auto b = static_cast<unsigned char>(text[i + k]);
            if (k == 1 ? (b < lo || b > hi) : (b < 0x80 || b > 0xBF)) return false;
        }
        i += len;
    }
    return true;
}

bool IsFileName(std::string_view name) {
    if (name.empty() || name.size() > 255 || name == "." || name == "..") return false;
    if (name.find('/') != std::string_view::npos || name.find('\0') != std::string_view::npos)
        return false;
    return IsUtf8(name) && !IsSyncthingEntry(name);
}

const char* SkipReasonName(SkipReason reason) {
    switch (reason) {
        case SkipReason::kName:
            return "name";
        case SkipReason::kTooLarge:
            return "too_large";
        case SkipReason::kTooDeep:
            return "too_deep";
        case SkipReason::kSymlink:
            return "symlink";
        case SkipReason::kNotRegular:
            return "not_regular";
    }
    return "unknown";
}

bool IsRelativePath(std::string_view path) {
    if (path.empty() || path.size() > kMaxPathSize) return false;
    int components = 0;
    size_t start = 0;
    while (true) {
        const size_t slash = path.find('/', start);
        const std::string_view part = path.substr(
                start, slash == std::string_view::npos ? std::string_view::npos : slash - start);
        if (!IsFileName(part) || ++components > kMaxDepth + 1) return false;
        if (slash == std::string_view::npos) return true;
        start = slash + 1;
    }
}

bool IsDatabaseName(std::string_view name) {
    for (std::string_view suffix : {".kdbx", ".json", ".1pux", ".csv"}) {
        if (name.size() > suffix.size() &&
            android::base::EqualsIgnoreCase(name.substr(name.size() - suffix.size()), suffix)) {
            return true;
        }
    }
    return false;
}

std::string FolderPath(std::string_view folder, const std::string& root) {
    return root + "/" + std::string(folder);
}

Result<std::vector<Entry>> List(const std::string& folder_dir) {
    auto fd = OpenDir(folder_dir);
    if (!fd.ok()) return fd.error();
    std::vector<Entry> entries;
    auto listed =
            ForEachEntry(fd->get(), [&entries](const std::string& name, const struct stat& st) {
                if (S_ISREG(st.st_mode) && IsFileName(name))
                    entries.push_back(Entry{name, static_cast<uint64_t>(st.st_size), MtimeMs(st)});
                return true;
            });
    if (!listed.ok()) return listed.error();
    std::sort(entries.begin(), entries.end(),
              [](const Entry& a, const Entry& b) { return a.name < b.name; });
    return entries;
}

Result<FindResult> Find(const std::string& root) {
    auto fd = OpenDir(root);
    if (!fd.ok()) return fd.error();
    FindResult result;
    std::vector<std::string> folders;
    auto listed =
            ForEachEntry(fd->get(), [&folders](const std::string& name, const struct stat& st) {
                if (S_ISDIR(st.st_mode) && IsFileName(name)) folders.push_back(name);
                return true;
            });
    if (!listed.ok()) return listed.error();
    std::sort(folders.begin(), folders.end());
    result.folders = folders;
    Walk walk{&result};
    for (const std::string& folder : folders) {
        unique_fd dir(
                openat(fd->get(), folder.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        if (dir < 0) continue;
        if (!walk.Folder(dir.get(), folder, "", 0, true)) break;
    }
    std::sort(result.files.begin(), result.files.end(), [](const Found& a, const Found& b) {
        return a.folder != b.folder ? a.folder < b.folder : a.path < b.path;
    });
    std::sort(result.skipped.begin(), result.skipped.end(), [](const Skipped& a, const Skipped& b) {
        return a.folder != b.folder ? a.folder < b.folder : a.path < b.path;
    });
    return result;
}

Result<unique_fd> Open(std::string_view path, const std::string& folder_dir) {
    auto fd = OpenDatabase(path, folder_dir);
    if (!fd.ok()) return fd.error();
    auto st = StatRegular(fd->get(), path);
    if (!st.ok()) return st.error();
    if (static_cast<uint64_t>(st->st_size) > kMaxDatabaseSize) {
        return Error() << "larger than " << (kMaxDatabaseSize >> 20) << " MiB";
    }
    return std::move(*fd);
}

Result<Entry> Stat(std::string_view path, const std::string& folder_dir) {
    auto fd = OpenDatabase(path, folder_dir);
    if (!fd.ok()) return fd.error();
    auto st = StatRegular(fd->get(), path);
    if (!st.ok()) return st.error();
    return Entry{std::string(path), static_cast<uint64_t>(st->st_size), MtimeMs(*st)};
}

Result<File> Read(std::string_view path, const std::string& folder_dir, uint64_t offset,
                  uint64_t length) {
    auto fd = OpenDatabase(path, folder_dir);
    if (!fd.ok()) return fd.error();
    auto st = StatRegular(fd->get(), path);
    if (!st.ok()) return st.error();
    File file;
    file.offset = offset;
    file.size = static_cast<uint64_t>(st->st_size);
    file.mtime_ms = MtimeMs(*st);
    file.inode = static_cast<uint64_t>(st->st_ino);
    if (file.size > kMaxDatabaseSize) {
        return Error() << path << " is larger than " << (kMaxDatabaseSize >> 20) << " MiB";
    }
    if (offset > file.size) return Error() << "offset " << offset << " is past the end of " << path;
    // Syncthing replaces files by renaming a finished temp file over them, so
    // the open file does not change; the facts above let a client reading in
    // parts notice a replacement between two parts.
    const uint64_t want = std::min({length, kMaxChunk, file.size - offset});
    file.data.resize(want);
    size_t total = 0;
    while (total < want) {
        const ssize_t n =
                TEMP_FAILURE_RETRY(pread(fd->get(), file.data.data() + total, want - total,
                                         static_cast<off_t>(offset + total)));
        if (n < 0) return ErrnoError() << "cannot read " << path;
        if (n == 0) break;
        total += static_cast<size_t>(n);
    }
    file.data.resize(total);
    return file;
}

std::map<std::string, std::string> FolderLabels(const Json::Value& folders,
                                                const std::string& root) {
    std::map<std::string, std::string> labels;
    if (!folders.isArray()) return labels;
    const std::string prefix = root + "/";
    for (const Json::Value& folder : folders) {
        if (!folder.isObject()) continue;
        const Json::Value& path_value = folder["path"];
        if (!path_value.isString()) continue;
        std::string path = path_value.asString();
        while (path.size() > 1 && path.back() == '/') path.pop_back();
        if (!android::base::StartsWith(path, prefix)) continue;
        const std::string name = path.substr(prefix.size());
        if (!IsFileName(name)) continue;
        const Json::Value& label = folder["label"];
        const Json::Value& id = folder["id"];
        if (label.isString() && !label.asString().empty()) {
            labels[name] = label.asString();
        } else if (id.isString() && !id.asString().empty()) {
            labels[name] = id.asString();
        }
    }
    return labels;
}

std::string Sha256Hex(std::string_view data) {
    uint8_t digest[SHA256_DIGEST_LENGTH];
    SHA256(reinterpret_cast<const uint8_t*>(data.data()), data.size(), digest);
    static constexpr char kHex[] = "0123456789abcdef";
    std::string hex;
    hex.reserve(sizeof(digest) * 2);
    for (uint8_t b : digest) {
        hex.push_back(kHex[b >> 4]);
        hex.push_back(kHex[b & 0xf]);
    }
    return hex;
}

std::string Base64(std::string_view data) {
    size_t length = 0;
    if (!EVP_EncodedLength(&length, data.size())) return "";
    std::string out(length, '\0');
    const size_t written =
            EVP_EncodeBlock(reinterpret_cast<uint8_t*>(out.data()),
                            reinterpret_cast<const uint8_t*>(data.data()), data.size());
    out.resize(written);  // EVP_EncodedLength counts the trailing NUL
    return out;
}

}  // namespace lujarch::sync::vault
