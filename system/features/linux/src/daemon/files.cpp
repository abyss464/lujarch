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

#include "files.h"

#include <android-base/file.h>
#include <android-base/strings.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <sys/fsuid.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <memory>
#include <vector>

namespace lujarch::linux::files {

namespace {

using android::base::ErrnoError;
using android::base::Error;

std::string Relative(std::string_view path) {
    while (!path.empty() && path.front() == '/') path.remove_prefix(1);
    return path.empty() ? std::string(".") : std::string(path);
}

// The file system IDs of this thread for a scope: the daemon acts on a user's directory as the
// user, since no domain bypasses permissions. CAP_SETUID survives the change.
class AsIds {
  public:
    AsIds(uid_t uid, gid_t gid) {
        old_gid_ = setfsgid(gid);
        old_uid_ = setfsuid(uid);
    }
    ~AsIds() {
        setfsuid(old_uid_);
        setfsgid(old_gid_);
    }
    AsIds(const AsIds&) = delete;
    AsIds& operator=(const AsIds&) = delete;

  private:
    int old_uid_;
    int old_gid_;
};

std::string FdPath(int fd) {
    return "/proc/self/fd/" + std::to_string(fd);
}

}  // namespace

Result<unique_fd> OpenRoot(const std::string& root) {
    unique_fd fd(open(root.c_str(), O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (fd < 0) return ErrnoError() << root;
    return fd;
}

Result<unique_fd> OpenIn(int root, std::string_view path, int flags, mode_t mode) {
    open_how how = {};
    how.flags = static_cast<uint64_t>(flags | O_CLOEXEC);
    how.mode = (flags & (O_CREAT | O_TMPFILE)) ? mode : 0;
    how.resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS;
    const std::string rel = Relative(path);
    unique_fd fd(static_cast<int>(
            TEMP_FAILURE_RETRY(syscall(__NR_openat2, root, rel.c_str(), &how, sizeof(how)))));
    if (fd < 0) return ErrnoError() << path;
    return fd;
}

Result<std::string> ReadIn(int root, std::string_view path, size_t max) {
    auto fd = OpenIn(root, path, O_RDONLY | O_NOCTTY | O_NONBLOCK);
    if (!fd.ok()) return fd.error();
    struct stat st;
    if (fstat(fd->get(), &st) != 0) return ErrnoError() << path;
    if (!S_ISREG(st.st_mode)) return Error() << path << " is not a file";
    std::string data;
    data.resize(std::min<size_t>(max, static_cast<size_t>(st.st_size)));
    size_t done = 0;
    while (done < data.size()) {
        ssize_t n = TEMP_FAILURE_RETRY(read(fd->get(), data.data() + done, data.size() - done));
        if (n < 0) return ErrnoError() << path;
        if (n == 0) break;
        done += static_cast<size_t>(n);
    }
    data.resize(done);
    return data;
}

Result<unique_fd> OpenParentIn(int root, std::string_view path, std::string* name) {
    std::string p(path);
    while (p.size() > 1 && p.back() == '/') p.pop_back();
    const size_t slash = p.rfind('/');
    *name = slash == std::string::npos ? p : p.substr(slash + 1);
    if (!IsPlainName(*name)) return Error() << path << ": not a plain name at its end";
    const std::string dir = slash == std::string::npos ? "/" : p.substr(0, slash);
    return OpenIn(root, dir.empty() ? "/" : dir, O_PATH | O_DIRECTORY);
}

Result<void> WriteIn(int root, std::string_view path, std::string_view data, mode_t mode,
                     uid_t uid, gid_t gid) {
    std::string name;
    auto dir = OpenParentIn(root, path, &name);
    if (!dir.ok()) return dir.error();
    const std::string tmp = "." + name + ".lujarch-new";
    unlinkat(dir->get(), tmp.c_str(), 0);
    unique_fd fd(TEMP_FAILURE_RETRY(openat(dir->get(), tmp.c_str(),
                                           O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
                                           0600)));
    if (fd < 0) return ErrnoError() << path;
    if (!android::base::WriteFully(fd.get(), data.data(), data.size()) ||
        fchown(fd.get(), uid, gid) != 0 || fchmod(fd.get(), mode) != 0 || fsync(fd.get()) != 0) {
        const int saved = errno;
        unlinkat(dir->get(), tmp.c_str(), 0);
        errno = saved;
        return ErrnoError() << path;
    }
    if (renameat(dir->get(), tmp.c_str(), dir->get(), name.c_str()) != 0) {
        const int saved = errno;
        unlinkat(dir->get(), tmp.c_str(), 0);
        errno = saved;
        return ErrnoError() << path;
    }
    return {};
}

Result<void> MakeDirsIn(int root, std::string_view path, mode_t mode, uid_t uid, gid_t gid) {
    std::string prefix;
    for (const std::string& part : android::base::Split(std::string(path), "/")) {
        if (part.empty()) continue;
        if (!IsPlainName(part)) return Error() << path << ": not a plain path";
        const std::string parent = prefix.empty() ? "/" : prefix;
        prefix += "/" + part;
        auto dir = OpenIn(root, parent, O_PATH | O_DIRECTORY);
        if (!dir.ok()) return dir.error();
        if (mkdirat(dir->get(), part.c_str(), mode) == 0) {
            if (fchownat(dir->get(), part.c_str(), uid, gid, AT_SYMLINK_NOFOLLOW) != 0 ||
                fchmodat(dir->get(), part.c_str(), mode, 0) != 0) {
                return ErrnoError() << prefix;
            }
        } else if (errno != EEXIST) {
            return ErrnoError() << prefix;
        }
    }
    auto check = OpenIn(root, path, O_PATH | O_DIRECTORY);
    if (!check.ok()) return check.error();
    return {};
}

Result<void> RemoveIn(int root, std::string_view path) {
    std::string name;
    auto dir = OpenParentIn(root, path, &name);
    if (!dir.ok()) {
        if (dir.error().code() == ENOENT) return {};
        return dir.error();
    }
    struct stat st;
    if (fstatat(dir->get(), name.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno == ENOENT) return {};
        return ErrnoError() << path;
    }
    // RemoveTree needs a directory it can read; an O_PATH one only resolves.
    unique_fd readable(openat(dir->get(), ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (readable < 0) return ErrnoError() << path;
    return RemoveTree(readable.get(), name);
}

bool ExistsIn(int root, std::string_view path) {
    return OpenIn(root, path, O_PATH | O_NOFOLLOW).ok();
}

Result<void> RemoveTree(int dir, const std::string& name) {
    struct stat st;
    if (fstatat(dir, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0) {
        if (errno == ENOENT) return {};
        return ErrnoError() << name;
    }
    if (!S_ISDIR(st.st_mode)) {
        if (unlinkat(dir, name.c_str(), 0) != 0 && errno != ENOENT) {
            return ErrnoError() << "unlink " << name;
        }
        return {};
    }
    unique_fd path(openat(dir, name.c_str(), O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    if (path < 0) return ErrnoError() << name;
    {
        // A directory its owner made unreadable is opened up first (CAP_FOWNER as root).
        AsIds as_root(0, 0);
        if (chmod(FdPath(path.get()).c_str(), 0700) != 0) return ErrnoError() << "chmod " << name;
    }
    {
        AsIds as_owner(st.st_uid, st.st_gid);
        unique_fd fd(openat(path.get(), ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
        if (fd < 0) return ErrnoError() << name;
        if (auto emptied = EmptyDir(fd.get()); !emptied.ok()) {
            return Error() << name << "/" << emptied.error().message();
        }
    }
    if (unlinkat(dir, name.c_str(), AT_REMOVEDIR) != 0) {
        // A sticky directory: only its owner, the entry's owner or CAP_FOWNER remove entries.
        AsIds as_root(0, 0);
        if (unlinkat(dir, name.c_str(), AT_REMOVEDIR) != 0 && errno != ENOENT) {
            return ErrnoError() << "rmdir " << name;
        }
    }
    return {};
}

Result<void> EmptyDir(int dir) {
    unique_fd copy(fcntl(dir, F_DUPFD_CLOEXEC, 0));
    if (copy < 0) return ErrnoError() << "dup";
    if (lseek(copy.get(), 0, SEEK_SET) != 0) return ErrnoError() << "lseek";
    std::unique_ptr<DIR, int (*)(DIR*)> d(fdopendir(copy.release()), closedir);
    if (!d) return ErrnoError() << "fdopendir";
    std::vector<std::string> names;
    while (dirent* e = readdir(d.get())) {
        std::string_view n(e->d_name);
        if (n == "." || n == "..") continue;
        names.emplace_back(n);
    }
    for (const std::string& n : names) {
        if (auto removed = RemoveTree(dir, n); !removed.ok()) return removed.error();
    }
    return {};
}

Result<std::string> ReadFile(const std::string& path, size_t max) {
    unique_fd fd(TEMP_FAILURE_RETRY(open(path.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC)));
    if (fd < 0) return ErrnoError() << path;
    std::string data(max, '\0');
    size_t done = 0;
    while (done < max) {
        ssize_t n = TEMP_FAILURE_RETRY(read(fd.get(), data.data() + done, max - done));
        if (n < 0) return ErrnoError() << path;
        if (n == 0) break;
        done += static_cast<size_t>(n);
    }
    data.resize(done);
    return data;
}

Result<void> WriteFile(const std::string& path, std::string_view data, mode_t mode, uid_t uid,
                       gid_t gid) {
    const size_t slash = path.rfind('/');
    if (slash == std::string::npos) return Error() << path << " is not absolute";
    unique_fd dir(open(path.substr(0, slash).c_str(), O_PATH | O_DIRECTORY | O_CLOEXEC));
    if (dir < 0) return ErrnoError() << path.substr(0, slash);
    return WriteIn(dir.get(), path.substr(slash), data, mode, uid, gid);
}

std::map<std::string, std::string> ReadKeyValues(const std::string& path) {
    std::map<std::string, std::string> values;
    auto text = ReadFile(path, 1 << 20);
    if (!text.ok()) return values;
    for (const std::string& line : android::base::Split(*text, "\n")) {
        const size_t colon = line.find(':');
        if (colon == std::string::npos || colon == 0) continue;
        values[line.substr(0, colon)] = android::base::Trim(line.substr(colon + 1));
    }
    return values;
}

Result<void> WriteKeyValues(const std::string& path,
                            const std::map<std::string, std::string>& values, mode_t mode) {
    std::string text;
    for (const auto& [key, value] : values) {
        if (value.find('\n') != std::string::npos) return Error() << key << ": more than a line";
        text += key + ": " + value + "\n";
    }
    return WriteFile(path, text, mode);
}

bool IsPlainName(std::string_view name) {
    if (name.empty() || name.size() > 255 || name == "." || name == "..") return false;
    for (char c : name) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                        c == '.' || c == '_' || c == '-' || c == '@' || c == '+';
        if (!ok) return false;
    }
    return true;
}

}  // namespace lujarch::linux::files
