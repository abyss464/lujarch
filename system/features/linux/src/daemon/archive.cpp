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

#include "archive.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <cstring>
#include <ctime>
#include <map>
#include <string>
#include <vector>

#include "files.h"

namespace lujarch::linux::archive {

namespace {

using android::base::ErrnoError;
using android::base::Error;
using android::base::Result;
using android::base::unique_fd;

constexpr size_t kBlock = 512;
// Storage the unpacking leaves to Android.
constexpr int64_t kReserve = int64_t{1} << 30;
constexpr int64_t kFreeEvery = int64_t{64} << 20;

// The archive's bytes, inflated when it is gzip-compressed.
class Input {
  public:
    Input(int fd, std::atomic<int64_t>* done) : fd_(fd), done_(done), raw_(1 << 16) {}
    ~Input() {
        if (gzip_) inflateEnd(&z_);
    }

    // Reads exactly n bytes; false at the end of the archive.
    Result<bool> Read(char* out, size_t n) {
        size_t got = 0;
        while (got < n) {
            auto r = Some(out + got, n - got);
            if (!r.ok()) return r.error();
            if (*r == 0) {
                if (got == 0) return false;
                return Error() << "the archive ends in the middle of an entry";
            }
            got += *r;
        }
        return true;
    }

  private:
    Result<bool> Fill() {
        if (raw_pos_ < raw_len_) return true;
        ssize_t n = TEMP_FAILURE_RETRY(read(fd_, raw_.data(), raw_.size()));
        if (n < 0) return ErrnoError() << "reading the archive";
        raw_pos_ = 0;
        raw_len_ = static_cast<size_t>(n);
        *done_ += n;
        return n > 0;
    }

    Result<size_t> Some(char* out, size_t n) {
        if (!started_) {
            started_ = true;
            auto filled = Fill();
            if (!filled.ok()) return filled.error();
            if (raw_len_ >= 2 && static_cast<unsigned char>(raw_[0]) == 0x1f &&
                static_cast<unsigned char>(raw_[1]) == 0x8b) {
                memset(&z_, 0, sizeof(z_));
                if (inflateInit2(&z_, 16 + MAX_WBITS) != Z_OK) return Error() << "zlib";
                gzip_ = true;
            }
        }
        if (!gzip_) {
            auto filled = Fill();
            if (!filled.ok()) return filled.error();
            if (!*filled) return size_t{0};
            const size_t take = std::min(n, raw_len_ - raw_pos_);
            memcpy(out, raw_.data() + raw_pos_, take);
            raw_pos_ += take;
            return take;
        }
        for (;;) {
            if (ended_) return size_t{0};
            auto filled = Fill();
            if (!filled.ok()) return filled.error();
            if (!*filled) return Error() << "the compressed archive ends early";
            z_.next_in = reinterpret_cast<Bytef*>(raw_.data() + raw_pos_);
            z_.avail_in = static_cast<uInt>(raw_len_ - raw_pos_);
            z_.next_out = reinterpret_cast<Bytef*>(out);
            z_.avail_out = static_cast<uInt>(n);
            const int rc = inflate(&z_, Z_NO_FLUSH);
            raw_pos_ = raw_len_ - z_.avail_in;
            const size_t produced = n - z_.avail_out;
            if (rc == Z_STREAM_END) {
                // gzip may hold several members; anything else after the end is ignored.
                auto more = Fill();
                if (!more.ok()) return more.error();
                if (*more && raw_len_ - raw_pos_ >= 2 &&
                    static_cast<unsigned char>(raw_[raw_pos_]) == 0x1f &&
                    static_cast<unsigned char>(raw_[raw_pos_ + 1]) == 0x8b) {
                    inflateReset(&z_);
                } else {
                    ended_ = true;
                }
            } else if (rc != Z_OK && rc != Z_BUF_ERROR) {
                return Error() << "the archive is not valid gzip (" << (z_.msg ? z_.msg : "zlib")
                               << ")";
            }
            if (produced > 0) return produced;
        }
    }

    int fd_;
    std::atomic<int64_t>* done_;
    std::vector<char> raw_;
    size_t raw_pos_ = 0;
    size_t raw_len_ = 0;
    bool started_ = false;
    bool gzip_ = false;
    bool ended_ = false;
    z_stream z_;
};

Result<uint64_t> Number(const char* field, size_t len) {
    // Base-256 for values octal cannot hold.
    if (static_cast<unsigned char>(field[0]) & 0x80) {
        uint64_t v = static_cast<unsigned char>(field[0]) & 0x7f;
        for (size_t i = 1; i < len; ++i) {
            if (v >> 55) return Error() << "a number too large";
            v = v << 8 | static_cast<unsigned char>(field[i]);
        }
        return v;
    }
    uint64_t v = 0;
    size_t i = 0;
    while (i < len && field[i] == ' ') ++i;
    for (; i < len && field[i] >= '0' && field[i] <= '7'; ++i) {
        if (v >> 60) return Error() << "a number too large";
        v = v * 8 + static_cast<uint64_t>(field[i] - '0');
    }
    return v;
}

// A decimal number of a pax record; fallback when it is not one.
uint64_t Decimal(const std::string& text, uint64_t fallback) {
    uint64_t v = 0;
    return android::base::ParseUint(text, &v) ? v : fallback;
}

std::string Field(const char* field, size_t len) {
    return std::string(field, strnlen(field, len));
}

bool ChecksumOk(const char* h) {
    auto stored = Number(h + 148, 8);
    if (!stored.ok()) return false;
    uint64_t unsigned_sum = 0;
    int64_t signed_sum = 0;
    for (size_t i = 0; i < kBlock; ++i) {
        const char c = (i >= 148 && i < 156) ? ' ' : h[i];
        unsigned_sum += static_cast<unsigned char>(c);
        signed_sum += static_cast<signed char>(c);
    }
    return *stored == unsigned_sum || static_cast<int64_t>(*stored) == signed_sum;
}

// An entry's name as components inside the system; refused when it leaves it.
Result<std::vector<std::string>> Components(const std::string& name) {
    std::vector<std::string> parts;
    for (const std::string& part : android::base::Split(name, "/")) {
        if (part.empty() || part == ".") continue;
        if (part == "..") return Error() << "the entry " << name << " leads out of the system";
        if (part.find('\0') != std::string::npos) return Error() << "a name with a NUL byte";
        parts.push_back(part);
    }
    return parts;
}

std::string Join(const std::vector<std::string>& parts, size_t count) {
    std::string out;
    for (size_t i = 0; i < count; ++i) {
        if (i) out += "/";
        out += parts[i];
    }
    return out;
}

struct DirMeta {
    std::string path;
    size_t depth;
    mode_t mode;
    uid_t uid;
    gid_t gid;
    int64_t mtime;
};

class Unpacker {
  public:
    explicit Unpacker(int root) : root_(root) {}

    // The directory holding the entry, made when missing; nothing in the path is followed.
    Result<int> Parent(const std::vector<std::string>& parts) {
        const size_t count = parts.size() - 1;
        const std::string key = Join(parts, count);
        if (parent_fd_ >= 0 && key == parent_key_) return parent_fd_.get();
        unique_fd dir(fcntl(root_, F_DUPFD_CLOEXEC, 0));
        for (size_t i = 0; i < count; ++i) {
            unique_fd next(openat(dir.get(), parts[i].c_str(),
                                  O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
            if (next < 0 && errno == ENOENT) {
                if (mkdirat(dir.get(), parts[i].c_str(), 0700) == 0) {
                    // A directory the archive does not list: as Linux makes them, unless a
                    // later entry of the archive says otherwise.
                    dirs.push_back({Join(parts, i + 1), i + 1, 0755, 0, 0, time(nullptr)});
                } else if (errno != EEXIST) {
                    return ErrnoError() << Join(parts, i + 1);
                }
                next.reset(openat(dir.get(), parts[i].c_str(),
                                  O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
            }
            if (next < 0) return ErrnoError() << Join(parts, i + 1) << " (not a directory)";
            dir = std::move(next);
        }
        parent_fd_ = std::move(dir);
        parent_key_ = key;
        return parent_fd_.get();
    }

    void Forget() { parent_fd_.reset(); }

    std::vector<DirMeta> dirs;
    int64_t written = 0;
    int skipped_devices = 0;

  private:
    int root_;
    unique_fd parent_fd_;
    std::string parent_key_;
};

Result<void> EnoughSpace(int dir) {
    struct statvfs fs;
    if (fstatvfs(dir, &fs) != 0) return ErrnoError() << "statvfs";
    const int64_t free = static_cast<int64_t>(fs.f_bavail) * static_cast<int64_t>(fs.f_frsize);
    if (free < kReserve) return Error(ENOSPC) << "the phone's storage is full";
    return {};
}

void Times(int dir, const char* name, int64_t mtime) {
    timespec ts[2] = {{static_cast<time_t>(mtime), 0}, {static_cast<time_t>(mtime), 0}};
    utimensat(dir, name, ts, AT_SYMLINK_NOFOLLOW);
}

}  // namespace

Result<void> Unpack(int fd, int root, std::atomic<int64_t>* done) {
    Input in(fd, done);
    Unpacker u(root);
    std::string long_name;
    std::string long_link;
    std::map<std::string, std::string> pax;
    int64_t next_free_check = 0;
    int zero_blocks = 0;
    size_t entries = 0;
    char h[kBlock];
    for (;;) {
        auto more = in.Read(h, kBlock);
        if (!more.ok()) return more.error();
        if (!*more) break;
        if (std::all_of(h, h + kBlock, [](char c) { return c == 0; })) {
            if (++zero_blocks == 2) break;
            continue;
        }
        zero_blocks = 0;
        if (!ChecksumOk(h)) {
            return Error() << (entries == 0 ? "this is not a tar archive"
                                            : "a damaged header in the archive");
        }
        ++entries;
        auto size = Number(h + 124, 12);
        auto mode = Number(h + 100, 8);
        auto uid = Number(h + 108, 8);
        auto gid = Number(h + 116, 8);
        auto mtime = Number(h + 136, 12);
        if (!size.ok() || !mode.ok() || !uid.ok() || !gid.ok() || !mtime.ok()) {
            return Error() << "a damaged header in the archive";
        }
        const char type = h[156];
        std::string name = Field(h, 100);
        // POSIX ustar ("ustar\0") has a name prefix there; GNU's format ("ustar ") has other fields.
        if (memcmp(h + 257, "ustar", 6) == 0) {
            const std::string prefix = Field(h + 345, 155);
            if (!prefix.empty()) name = prefix + "/" + name;
        }
        std::string link = Field(h + 157, 100);
        uint64_t data_size = *size;
        if (!long_name.empty()) name = std::move(long_name);
        if (!long_link.empty()) link = std::move(long_link);
        long_name.clear();
        long_link.clear();
        if (pax.count("path")) name = pax["path"];
        if (pax.count("linkpath")) link = pax["linkpath"];
        if (pax.count("size")) data_size = Decimal(pax["size"], data_size);
        if (pax.count("uid")) *uid = Decimal(pax["uid"], *uid);
        if (pax.count("gid")) *gid = Decimal(pax["gid"], *gid);
        // "seconds[.fraction]"
        if (pax.count("mtime")) *mtime = Decimal(pax["mtime"].substr(0, pax["mtime"].find('.')), *mtime);
        pax.clear();
        if (data_size > (uint64_t{1} << 40)) return Error() << "an entry larger than 1 TiB";

        // The entry's data, read whole for the metadata entries.
        auto read_data = [&](std::string* out) -> Result<void> {
            if (data_size > (16u << 20)) return Error() << "a metadata entry larger than 16 MiB";
            std::string data((data_size + kBlock - 1) / kBlock * kBlock, '\0');
            if (!data.empty()) {
                auto ok = in.Read(data.data(), data.size());
                if (!ok.ok()) return ok.error();
                if (!*ok) return Error() << "the archive ends in the middle of an entry";
            }
            data.resize(data_size);
            *out = std::move(data);
            return {};
        };
        auto skip_data = [&]() -> Result<void> {
            char block[kBlock];
            for (uint64_t left = (data_size + kBlock - 1) / kBlock; left > 0; --left) {
                auto ok = in.Read(block, kBlock);
                if (!ok.ok()) return ok.error();
                if (!*ok) return Error() << "the archive ends in the middle of an entry";
            }
            return {};
        };

        if (type == 'L' || type == 'K') {
            std::string data;
            if (auto r = read_data(&data); !r.ok()) return r.error();
            (type == 'L' ? long_name : long_link) = Field(data.data(), data.size());
            continue;
        }
        if (type == 'x' || type == 'g') {
            std::string data;
            if (auto r = read_data(&data); !r.ok()) return r.error();
            if (type == 'g') continue;
            // Records: "<length> <key>=<value>\n".
            size_t pos = 0;
            while (pos < data.size()) {
                const size_t space = data.find(' ', pos);
                if (space == std::string::npos) break;
                const uint64_t len = Decimal(data.substr(pos, space - pos), 0);
                if (len <= space - pos + 1 || pos + len > data.size()) {
                    return Error() << "a damaged pax header";
                }
                const std::string record = data.substr(space + 1, pos + len - space - 2);
                const size_t eq = record.find('=');
                if (eq != std::string::npos) pax[record.substr(0, eq)] = record.substr(eq + 1);
                pos += len;
            }
            continue;
        }

        auto parts = Components(name);
        if (!parts.ok()) return parts.error();
        if (parts->empty()) {
            // The system's root keeps the daemon's owner and mode.
            if (auto r = skip_data(); !r.ok()) return r.error();
            continue;
        }
        if (u.written >= next_free_check) {
            if (auto space = EnoughSpace(root); !space.ok()) return space.error();
            next_free_check = u.written + kFreeEvery;
        }
        const std::string path = Join(*parts, parts->size());
        const std::string& leaf = parts->back();
        const mode_t perms = static_cast<mode_t>(*mode & 07777);
        const uid_t owner = static_cast<uid_t>(*uid);
        const gid_t group = static_cast<gid_t>(*gid);

        if (type == '5') {
            if (auto r = skip_data(); !r.ok()) return r.error();
            auto parent = u.Parent(*parts);
            if (!parent.ok()) return parent.error();
            struct stat st;
            if (fstatat(*parent, leaf.c_str(), &st, AT_SYMLINK_NOFOLLOW) == 0) {
                if (!S_ISDIR(st.st_mode)) {
                    if (auto removed = files::RemoveTree(*parent, leaf); !removed.ok()) {
                        return removed.error();
                    }
                    if (mkdirat(*parent, leaf.c_str(), 0700) != 0) return ErrnoError() << path;
                }
            } else if (mkdirat(*parent, leaf.c_str(), 0700) != 0) {
                return ErrnoError() << path;
            }
            u.dirs.push_back({path, parts->size(), perms, owner, group,
                              static_cast<int64_t>(*mtime)});
            continue;
        }

        auto parent = u.Parent(*parts);
        if (!parent.ok()) return parent.error();
        // A later entry of the same name replaces an earlier one, never what a directory holds.
        struct stat existing;
        if (fstatat(*parent, leaf.c_str(), &existing, AT_SYMLINK_NOFOLLOW) == 0) {
            if (S_ISDIR(existing.st_mode)) {
                if (auto removed = files::RemoveTree(*parent, leaf); !removed.ok()) {
                    return removed.error();
                }
                u.Forget();
                parent = u.Parent(*parts);
                if (!parent.ok()) return parent.error();
            } else if (unlinkat(*parent, leaf.c_str(), 0) != 0) {
                return ErrnoError() << path;
            }
        }

        if (type == '0' || type == '\0' || type == '7') {
            unique_fd out(openat(*parent, leaf.c_str(),
                                 O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600));
            if (out < 0) return ErrnoError() << path;
            std::vector<char> buf(1 << 16);
            uint64_t left = data_size;
            while (left > 0) {
                const size_t blocks = std::min<uint64_t>((left + kBlock - 1) / kBlock,
                                                         buf.size() / kBlock);
                auto ok = in.Read(buf.data(), blocks * kBlock);
                if (!ok.ok()) return ok.error();
                if (!*ok) return Error() << "the archive ends in the middle of " << path;
                const size_t take = static_cast<size_t>(std::min<uint64_t>(left, blocks * kBlock));
                if (!android::base::WriteFully(out.get(), buf.data(), take)) {
                    return ErrnoError() << path;
                }
                left -= take;
                u.written += static_cast<int64_t>(take);
            }
            // chown clears the set-ID bits: the mode comes after it.
            if (fchown(out.get(), owner, group) != 0 || fchmod(out.get(), perms) != 0) {
                return ErrnoError() << path;
            }
            timespec ts[2] = {{static_cast<time_t>(*mtime), 0}, {static_cast<time_t>(*mtime), 0}};
            futimens(out.get(), ts);
        } else if (type == '1') {
            if (auto r = skip_data(); !r.ok()) return r.error();
            auto target = Components(link);
            if (!target.ok()) return target.error();
            if (target->empty()) return Error() << path << ": a hard link to the root";
            // The target's directory exists already: hard links point back in the archive.
            unique_fd tdir(fcntl(root, F_DUPFD_CLOEXEC, 0));
            for (size_t i = 0; i + 1 < target->size(); ++i) {
                unique_fd next(openat(tdir.get(), (*target)[i].c_str(),
                                      O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
                if (next < 0) return ErrnoError() << path << " -> " << link;
                tdir = std::move(next);
            }
            if (linkat(tdir.get(), target->back().c_str(), *parent, leaf.c_str(), 0) != 0) {
                return ErrnoError() << path << " -> " << link;
            }
        } else if (type == '2') {
            if (auto r = skip_data(); !r.ok()) return r.error();
            if (link.empty()) return Error() << path << ": a symbolic link to nothing";
            if (symlinkat(link.c_str(), *parent, leaf.c_str()) != 0) return ErrnoError() << path;
            fchownat(*parent, leaf.c_str(), owner, group, AT_SYMLINK_NOFOLLOW);
            Times(*parent, leaf.c_str(), static_cast<int64_t>(*mtime));
        } else if (type == '6') {
            if (auto r = skip_data(); !r.ok()) return r.error();
            if (mkfifoat(*parent, leaf.c_str(), 0600) != 0 ||
                fchownat(*parent, leaf.c_str(), owner, group, AT_SYMLINK_NOFOLLOW) != 0 ||
                fchmodat(*parent, leaf.c_str(), perms, 0) != 0) {
                return ErrnoError() << path;
            }
        } else {
            // Device nodes and unknown kinds.
            if (auto r = skip_data(); !r.ok()) return r.error();
            ++u.skipped_devices;
        }
    }
    if (entries == 0) return Error() << "the archive is empty";
    u.Forget();
    std::stable_sort(u.dirs.begin(), u.dirs.end(),
                     [](const DirMeta& a, const DirMeta& b) { return a.depth > b.depth; });
    for (const DirMeta& d : u.dirs) {
        auto parts = Components(d.path);
        if (!parts.ok() || parts->empty()) continue;
        auto parent = u.Parent(*parts);
        if (!parent.ok()) return parent.error();
        const char* leaf = parts->back().c_str();
        if (fchownat(*parent, leaf, d.uid, d.gid, AT_SYMLINK_NOFOLLOW) != 0 ||
            fchmodat(*parent, leaf, d.mode, 0) != 0) {
            return ErrnoError() << d.path;
        }
        Times(*parent, leaf, d.mtime);
    }
    if (fchmod(root, 0755) != 0) return ErrnoError() << "the system's root";
    LOG(INFO) << "archive unpacked: " << entries << " entries, " << u.written << " bytes"
              << (u.skipped_devices ? ", device nodes left out: " + std::to_string(u.skipped_devices)
                                    : std::string());
    return {};
}

Result<void> Check(int dir) {
    auto bash = files::OpenIn(dir, "/usr/bin/bash", O_RDONLY);
    if (!bash.ok()) return Error() << "the archive holds no /usr/bin/bash: not a Linux system";
    Elf64_Ehdr e{};
    if (!android::base::ReadFully(bash->get(), &e, sizeof(e)) || memcmp(e.e_ident, ELFMAG, SELFMAG) != 0) {
        return Error() << "/usr/bin/bash is not a program";
    }
#if defined(__aarch64__)
    constexpr int kMachine = EM_AARCH64;
#elif defined(__x86_64__)
    constexpr int kMachine = EM_X86_64;
#else
#error "an architecture without a Linux system"
#endif
    if (e.e_ident[EI_CLASS] != ELFCLASS64 || e.e_machine != kMachine) {
        return Error() << "the system is for another processor";
    }
    return {};
}

}  // namespace lujarch::linux::archive
