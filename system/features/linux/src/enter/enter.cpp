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

// lujarch_linux_enter ROOT UID LOGIN CWD GROUPS -- [PROGRAM [ARGUMENT...]]
//
// The way into the installed system: lujarch_linux starts every program of Linux through it, in
// the system's mount namespace. Its execution enters the system's domain. It changes the root to
// ROOT, becomes the user with user ID UID (0, or the owner's user) as the system's /etc/passwd
// and /etc/group describe it, with the additional GROUPS (comma-separated IDs), and executes
// PROGRAM, or with LOGIN 1 the user's login shell, in CWD or the user's home. It runs Android's C
// library: the system's own name service is read from its files, not through it.

#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <fstream>
#include <set>
#include <sstream>
#include <string>
#include <vector>

namespace {

[[noreturn]] void Die(const std::string& what, int code = 127) {
    fprintf(stderr, "lujarch_linux_enter: %s\n", what.c_str());
    exit(code);
}

[[noreturn]] void DieErrno(const std::string& what) {
    Die(what + ": " + strerror(errno));
}

std::vector<std::string> Split(const std::string& text, char sep) {
    std::vector<std::string> parts;
    std::string part;
    std::istringstream in(text);
    while (std::getline(in, part, sep)) parts.push_back(part);
    if (!text.empty() && text.back() == sep) parts.emplace_back();
    return parts;
}

bool ParseId(const std::string& text, unsigned long* out) {
    if (text.empty() || text.size() > 10) return false;
    char* end = nullptr;
    errno = 0;
    *out = strtoul(text.c_str(), &end, 10);
    return errno == 0 && end != nullptr && *end == '\0';
}

struct User {
    std::string name;
    uid_t uid = 0;
    gid_t gid = 0;
    std::string home;
    std::string shell;
};

bool FindUser(uid_t uid, User* user) {
    std::ifstream passwd("/etc/passwd");
    std::string line;
    while (std::getline(passwd, line)) {
        std::vector<std::string> f = Split(line, ':');
        unsigned long id;
        unsigned long gid;
        if (f.size() < 7 || !ParseId(f[2], &id) || id != uid || !ParseId(f[3], &gid)) continue;
        user->name = f[0];
        user->uid = static_cast<uid_t>(id);
        user->gid = static_cast<gid_t>(gid);
        user->home = f[5];
        user->shell = f[6];
        return true;
    }
    return false;
}

std::set<gid_t> GroupsOf(const std::string& name) {
    std::set<gid_t> groups;
    std::ifstream group("/etc/group");
    std::string line;
    while (std::getline(group, line)) {
        std::vector<std::string> f = Split(line, ':');
        unsigned long gid;
        if (f.size() < 4 || !ParseId(f[2], &gid)) continue;
        for (const std::string& member : Split(f[3], ',')) {
            if (member == name) groups.insert(static_cast<gid_t>(gid));
        }
    }
    return groups;
}

std::string Lang() {
    std::ifstream conf("/etc/locale.conf");
    std::string line;
    while (std::getline(conf, line)) {
        if (line.rfind("LANG=", 0) == 0 && line.size() > 5) return line.substr(5);
    }
    return "C.UTF-8";
}

bool IsExecutable(const std::string& path) {
    struct stat st;
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode) &&
           access(path.c_str(), X_OK) == 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 7 || strcmp(argv[6], "--") != 0) {
        Die("usage: lujarch_linux_enter ROOT UID LOGIN CWD GROUPS -- [PROGRAM [ARGUMENT...]]", 2);
    }
    const std::string root = argv[1];
    unsigned long wanted_uid;
    if (!ParseId(argv[2], &wanted_uid)) Die(std::string("not a user ID: ") + argv[2], 2);
    const bool login = strcmp(argv[3], "1") == 0;
    const std::string cwd = argv[4];
    std::vector<gid_t> extra;
    for (const std::string& id : Split(argv[5], ',')) {
        unsigned long gid;
        if (id.empty()) continue;
        if (!ParseId(id, &gid)) Die("not a group ID: " + id, 2);
        extra.push_back(static_cast<gid_t>(gid));
    }
    if (!login && argc < 8) Die("no program", 2);

    if (chroot(root.c_str()) != 0) DieErrno("chroot " + root);
    if (chdir("/") != 0) DieErrno("chdir /");

    User user;
    if (!FindUser(static_cast<uid_t>(wanted_uid), &user)) {
        if (wanted_uid != 0) {
            Die("Linux has no user with user ID " + std::to_string(wanted_uid) +
                ": run the initial setup again");
        }
        user = User{"root", 0, 0, "/root", "/usr/bin/bash"};
    }
    if (!IsExecutable(user.shell)) user.shell = "/usr/bin/bash";
    std::set<gid_t> groups = GroupsOf(user.name);
    groups.insert(user.gid);
    groups.insert(extra.begin(), extra.end());
    std::vector<gid_t> list(groups.begin(), groups.end());
    if (setgroups(list.size(), list.data()) != 0) DieErrno("setgroups");
    if (setresgid(user.gid, user.gid, user.gid) != 0) DieErrno("setresgid");
    if (setresuid(user.uid, user.uid, user.uid) != 0) DieErrno("setresuid");
    umask(022);

    std::vector<std::string> env = {
            "HOME=" + user.home,
            "USER=" + user.name,
            "LOGNAME=" + user.name,
            "SHELL=" + user.shell,
            "PATH=" + std::string(user.uid == 0 ? "/usr/local/sbin:/usr/local/bin:/usr/bin"
                                                : "/usr/local/bin:/usr/bin"),
            "LANG=" + Lang(),
    };
    for (const char* name : {"TERM", "COLORTERM", "LUJARCH_SESSION"}) {
        if (const char* value = getenv(name)) env.push_back(std::string(name) + "=" + value);
    }
    std::vector<char*> envp;
    for (std::string& e : env) envp.push_back(e.data());
    envp.push_back(nullptr);

    const std::string dir = cwd.empty() ? user.home : cwd;
    if (chdir(dir.c_str()) != 0 && chdir("/") != 0) DieErrno("chdir");

    if (login) {
        const size_t slash = user.shell.rfind('/');
        std::string name = "-" + user.shell.substr(slash == std::string::npos ? 0 : slash + 1);
        char* args[] = {name.data(), nullptr};
        execve(user.shell.c_str(), args, envp.data());
        DieErrno("execute " + user.shell);
    }
    execve(argv[7], argv + 7, envp.data());
    DieErrno(std::string("execute ") + argv[7]);
}
