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

#include "system.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/openat2.h>
#include <lujarch/aid.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/pidfd.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/swap.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <iterator>
#include <thread>

#include "files.h"
#include "paths.h"

namespace lujarch::linux {

namespace {

using android::base::ErrnoError;
using android::base::Error;

// Android's device nodes Linux sees, each bound onto an empty file of the system's /dev.
constexpr const char* kDeviceNodes[] = {"null", "zero", "random", "urandom", "tty", "ptmx"};
// The GPU's nodes the desktop draws with, bound the same way when Android has them: KGSL, the
// display driver's render node (EGL and GBM), the system DMA heap.
constexpr const char* kGpuNodes[] = {"kgsl-3d0", "dri/renderD128", "dma_heap/system"};

std::vector<std::string> DeviceNodes() {
    std::vector<std::string> nodes(std::begin(kDeviceNodes), std::end(kDeviceNodes));
    for (const char* node : kGpuNodes) {
        if (access((std::string("/dev/") + node).c_str(), F_OK) == 0) nodes.push_back(node);
    }
    return nodes;
}

struct Bind {
    std::string source;
    std::string target;  // relative to the system's root
    bool recursive;
};

std::vector<Bind> Binds(const std::vector<std::string>& nodes, bool usb) {
    std::vector<Bind> binds = {
            {"/proc", "proc", true},
            {"/sys", "sys", true},
            {"/dev/pts", "dev/pts", false},
            {kFiles, std::string(kLinuxFiles).substr(1), false},
    };
    for (const std::string& node : nodes) binds.push_back({"/dev/" + node, "dev/" + node, false});
    if (usb) binds.push_back({"/dev/bus/usb", "dev/bus/usb", true});
    return binds;
}

Result<void> PrepareMountPoints(int root, const std::vector<std::string>& nodes, bool usb) {
    struct Dir {
        const char* path;
        mode_t mode;
    };
    for (const Dir& d : {Dir{"/proc", 0555}, Dir{"/sys", 0555}, Dir{"/dev", 0755},
                         Dir{"/dev/pts", 0755}, Dir{"/dev/shm", 01777}, Dir{kLinuxFiles, 0755},
                         Dir{"/run", 0755}, Dir{"/tmp", 01777}}) {
        if (auto made = files::MakeDirsIn(root, d.path, d.mode); !made.ok()) return made.error();
    }
    if (usb) {
        if (auto made = files::MakeDirsIn(root, "/dev/bus/usb", 0755); !made.ok()) {
            return made.error();
        }
    }
    for (const std::string& node : nodes) {
        const std::string path = "/dev/" + node;
        if (files::ExistsIn(root, path)) continue;
        if (const size_t slash = path.rfind('/'); slash > 4) {
            if (auto made = files::MakeDirsIn(root, path.substr(0, slash), 0755); !made.ok()) {
                return made.error();
            }
        }
        auto made = files::OpenIn(root, path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0644);
        if (!made.ok()) return made.error();
    }
    auto dev = files::OpenIn(root, "/dev", O_PATH | O_DIRECTORY);
    if (!dev.ok()) return dev.error();
    for (const auto& [name, target] : {std::pair<const char*, const char*>{"fd", "/proc/self/fd"},
                                       {"stdin", "/proc/self/fd/0"},
                                       {"stdout", "/proc/self/fd/1"},
                                       {"stderr", "/proc/self/fd/2"}}) {
        if (symlinkat(target, dev->get(), name) != 0 && errno != EEXIST) {
            return ErrnoError() << "/dev/" << name;
        }
    }
    return {};
}

// What a child reports before it goes on: one byte for success, or the step and errno.
struct ChildError {
    int32_t step;
    int32_t error;
};

[[noreturn]] void ChildFail(int fd, int step) {
    ChildError e{step, errno};
    TEMP_FAILURE_RETRY(write(fd, &e, sizeof(e)));
    _exit(127);
}

// "/proc/self/fd/<fd>" without the allocator: the child of a threaded process.
void FdPath(int fd, char (&out)[32]) {
    static constexpr char kPrefix[] = "/proc/self/fd/";
    size_t n = sizeof(kPrefix) - 1;
    for (size_t i = 0; i < n; ++i) out[i] = kPrefix[i];
    char digits[12];
    size_t d = 0;
    do {
        digits[d++] = static_cast<char>('0' + fd % 10);
        fd /= 10;
    } while (fd > 0 && d < sizeof(digits));
    while (d > 0) out[n++] = digits[--d];
    out[n] = '\0';
}

int OpenInRoot(int root, const char* rel, int flags) {
    open_how how = {};
    how.flags = static_cast<uint64_t>(flags | O_CLOEXEC);
    how.resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS;
    return static_cast<int>(syscall(__NR_openat2, root, rel, &how, sizeof(how)));
}

// Reads a child's report: success, or its failure as an error. A child that executes a program
// reports success by the report pipe closing on its execution (executes).
Result<void> ReadReport(int fd, const char* what, const std::vector<std::string>& steps,
                        bool executes = false) {
    ChildError e{};
    ssize_t n = TEMP_FAILURE_RETRY(read(fd, &e, sizeof(e)));
    if (n == 1 || (n == 0 && executes)) return {};
    if (n == static_cast<ssize_t>(sizeof(e))) {
        const std::string step = e.step >= 0 && static_cast<size_t>(e.step) < steps.size()
                                         ? steps[e.step]
                                         : std::to_string(e.step);
        return Error(e.error) << what << ": " << step;
    }
    return Error() << what << ": the child ended without a report";
}

std::string JoinIds(const std::vector<gid_t>& ids) {
    std::vector<std::string> parts;
    for (gid_t id : ids) parts.push_back(std::to_string(id));
    return android::base::Join(parts, ",");
}

std::string CgroupFile(const char* name) {
    return std::string(kCgroup) + "/" + name;
}

Result<void> Join(pid_t pid, bool desktop) {
    const std::string path =
            desktop ? std::string(kDesktopCgroup) + "/cgroup.procs" : CgroupFile("cgroup.procs");
    if (!android::base::WriteStringToFile(std::to_string(pid), path)) {
        return ErrnoError() << path;
    }
    return {};
}

}  // namespace

Result<void> System::Create(bool usb) {
    std::lock_guard lock(mutex_);
    if (anchor_.pid > 0) return {};
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return root.error();
    const std::vector<std::string> nodes = DeviceNodes();
    if (auto prepared = PrepareMountPoints(root->get(), nodes, usb); !prepared.ok()) {
        return Error() << "mount points: " << prepared.error().message();
    }
    const std::vector<Bind> binds = Binds(nodes, usb);
    std::vector<std::string> steps = {"unshare", "mount propagation", "the system's root",
                                      "open the root"};
    for (const Bind& b : binds) steps.push_back(b.source + " -> /" + b.target);

    int status[2];
    if (pipe2(status, O_CLOEXEC) != 0) return ErrnoError() << "pipe";
    unique_fd status_r(status[0]), status_w(status[1]);
    const pid_t parent = getpid();

    const pid_t pid = fork();
    if (pid < 0) return ErrnoError() << "fork";
    if (pid == 0) {
        const int out = status_w.get();
        // The anchor ends with the daemon, and the namespace with the last program in it.
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        if (getppid() != parent) _exit(1);
        if (unshare(CLONE_NEWNS) != 0) ChildFail(out, 0);
        if (mount(nullptr, "/", nullptr, MS_REC | MS_SLAVE, nullptr) != 0) ChildFail(out, 1);
        // The root as a mount point: programs that look for "/" in mountinfo find it.
        if (mount(kRoot, kRoot, nullptr, MS_BIND | MS_REC, nullptr) != 0) ChildFail(out, 2);
        const int ns_root = open(kRoot, O_PATH | O_DIRECTORY | O_CLOEXEC);
        if (ns_root < 0) ChildFail(out, 3);
        for (size_t i = 0; i < binds.size(); ++i) {
            const int target = OpenInRoot(ns_root, binds[i].target.c_str(), O_PATH | O_NOFOLLOW);
            if (target < 0) ChildFail(out, static_cast<int>(4 + i));
            char path[32];
            FdPath(target, path);
            const unsigned long flags = MS_BIND | (binds[i].recursive ? MS_REC : 0);
            if (mount(binds[i].source.c_str(), path, nullptr, flags, nullptr) != 0) {
                ChildFail(out, static_cast<int>(4 + i));
            }
            close(target);
        }
        close(ns_root);
        const char ok = 1;
        TEMP_FAILURE_RETRY(write(out, &ok, 1));
        // The anchor keeps none of the daemon's files: a session's stream ends when the daemon
        // closes it.
        syscall(__NR_close_range, 0, ~0U, 0);
        for (;;) pause();
    }
    status_w.reset();
    Result<void> report = ReadReport(status_r.get(), "the system's namespace", steps);
    if (!report.ok()) {
        TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
        return report.error();
    }
    anchor_.pid = pid;
    anchor_.pidfd.reset(pidfd_open(pid, 0));
    if (anchor_.pidfd < 0) {
        const int saved = errno;
        kill(pid, SIGKILL);
        TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
        anchor_.pid = -1;
        errno = saved;
        return ErrnoError() << "pidfd_open";
    }
    LOG(INFO) << "namespace of the system made" << (usb ? " with USB devices" : "")
              << ", anchor " << pid;
    return {};
}

void System::Drop() {
    std::lock_guard lock(mutex_);
    if (anchor_.pid <= 0) return;
    kill(anchor_.pid, SIGKILL);
    Wait(anchor_);
}

bool System::Created() {
    std::lock_guard lock(mutex_);
    return anchor_.pid > 0;
}

Result<void> System::LendUsb(bool lend) {
    std::lock_guard lock(mutex_);
    if (anchor_.pid <= 0) return {};
    if (lend) {
        auto root = files::OpenRoot(kRoot);
        if (!root.ok()) return root.error();
        if (auto made = files::MakeDirsIn(root->get(), "/dev/bus/usb", 0755); !made.ok()) {
            return made.error();
        }
    }
    int status[2];
    if (pipe2(status, O_CLOEXEC) != 0) return ErrnoError() << "pipe";
    unique_fd status_r(status[0]), status_w(status[1]);
    const int ns = anchor_.pidfd.get();
    const pid_t pid = fork();
    if (pid < 0) return ErrnoError() << "fork";
    if (pid == 0) {
        const int out = status_w.get();
        if (setns(ns, CLONE_NEWNS) != 0) ChildFail(out, 0);
        const int root = open(kRoot, O_PATH | O_DIRECTORY | O_CLOEXEC);
        if (root < 0) ChildFail(out, 1);
        if (lend) {
            const int target = OpenInRoot(root, "dev/bus/usb", O_PATH | O_NOFOLLOW);
            if (target < 0) ChildFail(out, 2);
            char path[32];
            FdPath(target, path);
            if (mount("/dev/bus/usb", path, nullptr, MS_BIND | MS_REC, nullptr) != 0) {
                ChildFail(out, 2);
            }
        } else {
            const int target = OpenInRoot(root, "dev/bus/usb", O_PATH | O_NOFOLLOW);
            if (target >= 0) {
                char path[32];
                FdPath(target, path);
                if (umount2(path, MNT_DETACH) != 0 && errno != EINVAL) ChildFail(out, 3);
            }
        }
        const char ok = 1;
        TEMP_FAILURE_RETRY(write(out, &ok, 1));
        _exit(0);
    }
    status_w.reset();
    Result<void> report = ReadReport(status_r.get(), lend ? "lend USB" : "take USB back",
                                     {"enter the namespace", "open the root",
                                      "bind /dev/bus/usb", "unmount /dev/bus/usb"});
    TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
    return report;
}

Result<Process> System::Start(const Spawn& s) {
    unique_fd ns;
    {
        std::lock_guard lock(mutex_);
        if (anchor_.pid <= 0) return Error() << "Linux does not run";
        ns.reset(fcntl(anchor_.pidfd.get(), F_DUPFD_CLOEXEC, 0));
        if (ns < 0) return ErrnoError() << "dup";
    }
    const bool android_root = s.as == Spawn::As::kAndroidRoot;
    std::string path;
    std::vector<std::string> args;
    if (android_root) {
        // KernelSU's su: the kernel runs it as root for a user ID allowed root, which
        // aid::kLinuxRoot is while Root sessions is on.
        path = "/system/bin/su";
        args = {"su"};
    } else {
        path = kEnter;
        args = {"lujarch_linux_enter",
                kRoot,
                std::to_string(s.as == Spawn::As::kUser ? aid::kLinux : 0),
                s.login ? "1" : "0",
                s.cwd,
                JoinIds(s.groups),
                "--"};
        args.insert(args.end(), s.argv.begin(), s.argv.end());
    }
    std::vector<char*> argv;
    for (std::string& a : args) argv.push_back(a.data());
    argv.push_back(nullptr);
    std::vector<std::string> env = s.env;
    if (android_root) {
        env.push_back(std::string("HOME=") + kAndroidRootHome);
        env.push_back("PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:"
                      "/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:"
                      "/vendor/xbin");
    }
    std::vector<char*> envp;
    for (std::string& e : env) envp.push_back(e.data());
    envp.push_back(nullptr);
    const char* tty = s.tty.empty() ? nullptr : s.tty.c_str();
    const uid_t root_uid = aid::kLinuxRoot;

    int gate[2];
    int report[2];
    if (pipe2(gate, O_CLOEXEC) != 0) return ErrnoError() << "pipe";
    unique_fd gate_r(gate[0]), gate_w(gate[1]);
    if (pipe2(report, O_CLOEXEC) != 0) return ErrnoError() << "pipe";
    unique_fd report_r(report[0]), report_w(report[1]);

    const pid_t pid = fork();
    if (pid < 0) return ErrnoError() << "fork";
    if (pid == 0) {
        const int out = report_w.get();
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, nullptr);
        struct sigaction dfl = {};
        dfl.sa_handler = SIG_DFL;
        sigaction(SIGPIPE, &dfl, nullptr);
        if (setsid() < 0) ChildFail(out, 0);
        if (tty != nullptr) {
            const int fd = open(tty, O_RDWR);
            if (fd < 0 || ioctl(fd, TIOCSCTTY, 0) != 0) ChildFail(out, 1);
            if (dup2(fd, 0) < 0 || dup2(fd, 1) < 0 || dup2(fd, 2) < 0) ChildFail(out, 1);
            if (fd > 2) close(fd);
        } else {
            const int null = open("/dev/null", O_RDWR);
            if (null < 0) ChildFail(out, 1);
            if (dup2(s.in >= 0 ? s.in : null, 0) < 0 || dup2(s.out >= 0 ? s.out : null, 1) < 0 ||
                dup2(s.err >= 0 ? s.err : null, 2) < 0) {
                ChildFail(out, 1);
            }
            if (null > 2) close(null);
        }
        // The daemon puts the child in the environment's cgroup before it goes on.
        char c;
        TEMP_FAILURE_RETRY(read(gate_r.get(), &c, 1));
        if (android_root) {
            if (setgroups(0, nullptr) != 0 || setresgid(root_uid, root_uid, root_uid) != 0 ||
                setresuid(root_uid, root_uid, root_uid) != 0) {
                ChildFail(out, 2);
            }
            if (chdir(kAndroidRootHome) != 0) chdir("/");
        } else if (setns(ns.get(), CLONE_NEWNS) != 0) {
            ChildFail(out, 3);
        }
        execve(path.c_str(), argv.data(), envp.data());
        ChildFail(out, 4);
    }
    gate_r.reset();
    report_w.reset();
    if (auto joined = Join(pid, s.desktop); !joined.ok()) {
        LOG(ERROR) << "cgroup of " << path << ": " << joined.error();
    }
    gate_w.reset();
    Result<void> started = ReadReport(report_r.get(), path.c_str(),
                                      {"setsid", "standard streams", "become the root session user",
                                       "enter the namespace", "execute"},
                                      true);
    if (!started.ok()) {
        TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
        return started.error();
    }
    Process p;
    p.pid = pid;
    p.pidfd.reset(pidfd_open(pid, 0));
    if (p.pidfd < 0) {
        const int saved = errno;
        kill(pid, SIGKILL);
        TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
        errno = saved;
        return ErrnoError() << "pidfd_open";
    }
    return p;
}

Result<Process> StartAndroid(const std::string& path, std::vector<std::string> args, uid_t uid,
                             const std::vector<gid_t>& groups, int out) {
    std::vector<char*> argv;
    for (std::string& a : args) argv.push_back(a.data());
    argv.push_back(nullptr);
    char* envp[] = {const_cast<char*>("PATH=/system/bin"), nullptr};
    int report[2];
    if (pipe2(report, O_CLOEXEC) != 0) return ErrnoError() << "pipe";
    unique_fd report_r(report[0]), report_w(report[1]);
    const pid_t pid = fork();
    if (pid < 0) return ErrnoError() << "fork";
    if (pid == 0) {
        const int fd = report_w.get();
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, nullptr);
        struct sigaction dfl = {};
        dfl.sa_handler = SIG_DFL;
        sigaction(SIGPIPE, &dfl, nullptr);
        if (setsid() < 0) ChildFail(fd, 0);
        const int null = open("/dev/null", O_RDWR);
        if (null < 0 || dup2(null, 0) < 0 || dup2(out, 1) < 0 || dup2(out, 2) < 0) {
            ChildFail(fd, 1);
        }
        if (setgroups(groups.size(), groups.data()) != 0 || setresgid(uid, uid, uid) != 0 ||
            setresuid(uid, uid, uid) != 0) {
            ChildFail(fd, 2);
        }
        execve(path.c_str(), argv.data(), envp);
        ChildFail(fd, 3);
    }
    report_w.reset();
    Result<void> started = ReadReport(report_r.get(), path.c_str(),
                                      {"setsid", "standard streams", "become its user", "execute"},
                                      true);
    if (!started.ok()) {
        TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
        return started.error();
    }
    Process p;
    p.pid = pid;
    p.pidfd.reset(pidfd_open(pid, 0));
    if (p.pidfd < 0) {
        const int saved = errno;
        kill(pid, SIGKILL);
        TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
        errno = saved;
        return ErrnoError() << "pidfd_open";
    }
    return p;
}

int Wait(Process& process) {
    int status = 0;
    if (process.pid > 0) TEMP_FAILURE_RETRY(waitpid(process.pid, &status, 0));
    process.pid = -1;
    process.pidfd.reset();
    return status;
}

int ExitCode(int wait_status) {
    if (WIFEXITED(wait_status)) return WEXITSTATUS(wait_status);
    if (WIFSIGNALED(wait_status)) return 128 + WTERMSIG(wait_status);
    return 255;
}

namespace cgroup {

namespace {

bool Populated() {
    std::string events;
    if (!android::base::ReadFileToString(CgroupFile("cgroup.events"), &events)) return false;
    return events.find("populated 1") != std::string::npos;
}

}  // namespace

void KillAll() {
    if (!android::base::WriteStringToFile("1", CgroupFile("cgroup.kill"))) {
        PLOG(WARNING) << "cgroup.kill; killing the programs one by one";
        std::string procs;
        if (android::base::ReadFileToString(CgroupFile("cgroup.procs"), &procs)) {
            for (const std::string& line : android::base::Split(procs, "\n")) {
                pid_t pid;
                if (android::base::ParseInt(line, &pid) && pid > 0) kill(pid, SIGKILL);
            }
        }
    }
    for (int i = 0; i < 50 && Populated(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    if (Populated()) LOG(ERROR) << "programs of the environment are still alive after SIGKILL";
}

Result<void> SetMemory(const std::string& choice) {
    const int64_t total = MemoryTotal();
    std::string value = "max";
    if (total > 0) {
        if (choice == "android") value = std::to_string(total / 4);
        if (choice == "balanced") value = std::to_string(total / 2);
        if (choice == "linux") value = std::to_string(total / 4 * 3);
    }
    const std::string path = CgroupFile("memory.high");
    if (!android::base::WriteStringToFile(value, path)) return ErrnoError() << path;
    LOG(INFO) << "memory: " << choice << ", memory.high " << value;
    return {};
}

int64_t MemoryUsed() {
    std::string text;
    int64_t value = 0;
    if (android::base::ReadFileToString(CgroupFile("memory.current"), &text)) {
        android::base::ParseInt(android::base::Trim(text), &value);
    }
    return value;
}

int64_t MemoryLimit() {
    std::string text;
    int64_t value = 0;
    if (android::base::ReadFileToString(CgroupFile("memory.high"), &text)) {
        android::base::ParseInt(android::base::Trim(text), &value);
    }
    return value;
}

namespace {

std::mutex desktop_mutex;
bool desktop_wish = true;
int desktop_holds = 0;
int desktop_applied = -1;

void ApplyDesktop() {
    const bool frozen = desktop_wish && desktop_holds == 0;
    if (static_cast<int>(frozen) == desktop_applied) return;
    const std::string path = std::string(kDesktopCgroup) + "/cgroup.freeze";
    if (!android::base::WriteStringToFile(frozen ? "1" : "0", path)) {
        PLOG(ERROR) << path;
        return;
    }
    desktop_applied = frozen;
    LOG(INFO) << "the desktop " << (frozen ? "is frozen" : "runs");
}

}  // namespace

void SetDesktopFrozen(bool frozen) {
    std::lock_guard lock(desktop_mutex);
    desktop_wish = frozen;
    ApplyDesktop();
}

void HoldDesktop(bool hold) {
    std::lock_guard lock(desktop_mutex);
    desktop_holds += hold ? 1 : -1;
    ApplyDesktop();
}

void KillDesktop() {
    const std::string path = std::string(kDesktopCgroup) + "/cgroup.kill";
    if (!android::base::WriteStringToFile("1", path)) PLOG(ERROR) << path;
}

}  // namespace cgroup

int64_t MemoryTotal() {
    std::string text;
    if (!android::base::ReadFileToString("/proc/meminfo", &text)) return 0;
    for (const std::string& line : android::base::Split(text, "\n")) {
        if (!android::base::StartsWith(line, "MemTotal:")) continue;
        std::vector<std::string> fields = android::base::Tokenize(line, " ");
        int64_t kb = 0;
        if (fields.size() >= 2 && android::base::ParseInt(fields[1], &kb)) return kb * 1024;
    }
    return 0;
}

namespace swap {

namespace {

// F2FS keeps a pinned file's blocks where they are, which swap needs.
constexpr unsigned long kF2fsSetPinFile = _IOW(0xf5, 13, uint32_t);
constexpr int64_t kGiB = int64_t{1} << 30;

int64_t WantedSize() {
    const int64_t half = MemoryTotal() / 2;
    const int64_t size = std::clamp(half, kGiB, 8 * kGiB);
    return size / (int64_t{1} << 20) * (int64_t{1} << 20);
}

Result<void> Make() {
    const int64_t size = WantedSize();
    struct statvfs fs;
    if (statvfs(kData, &fs) != 0) return ErrnoError() << kData;
    const int64_t free = static_cast<int64_t>(fs.f_bavail) * static_cast<int64_t>(fs.f_frsize);
    if (free < size + 2 * kGiB) {
        return Error(ENOSPC) << "the swap file needs " << size << " bytes and 2 GiB to spare; "
                             << free << " are free";
    }
    unique_fd fd(TEMP_FAILURE_RETRY(
            open(kSwapFile, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600)));
    if (fd < 0) return ErrnoError() << kSwapFile;
    auto fail = [&](const char* what) -> Result<void> {
        const int saved = errno;
        unlink(kSwapFile);
        errno = saved;
        return ErrnoError() << kSwapFile << ": " << what;
    };
    uint32_t pin = 1;
    if (ioctl(fd.get(), kF2fsSetPinFile, &pin) != 0 && errno != ENOTTY && errno != EOPNOTSUPP) {
        return fail("pin");
    }
    if (fallocate(fd.get(), 0, 0, size) != 0) return fail("fallocate");
    // The swap header (linux/swap.h, union swap_header): version 1, its last page, the magic at
    // the end of the first page.
    const size_t page = static_cast<size_t>(getpagesize());
    std::string header(page, '\0');
    const uint32_t version = 1;
    const uint32_t last_page = static_cast<uint32_t>(size / static_cast<int64_t>(page) - 1);
    memcpy(header.data() + 1024, &version, sizeof(version));
    memcpy(header.data() + 1028, &last_page, sizeof(last_page));
    memcpy(header.data() + page - 10, "SWAPSPACE2", 10);
    if (!android::base::WriteFully(fd.get(), header.data(), header.size())) return fail("header");
    if (fsync(fd.get()) != 0) return fail("fsync");
    LOG(INFO) << "swap file made: " << size << " bytes";
    return {};
}

}  // namespace

bool Active() {
    std::string swaps;
    if (!android::base::ReadFileToString("/proc/swaps", &swaps)) return false;
    for (const std::string& line : android::base::Split(swaps, "\n")) {
        if (android::base::StartsWith(line, std::string(kSwapFile) + " ") ||
            android::base::StartsWith(line, std::string(kSwapFile) + "\t")) {
            return true;
        }
    }
    return false;
}

int64_t Size() {
    struct stat st;
    return stat(kSwapFile, &st) == 0 ? st.st_size : 0;
}

Result<void> On() {
    if (Active()) return {};
    if (Size() == 0) {
        unlink(kSwapFile);
        if (auto made = Make(); !made.ok()) return made.error();
    }
    // No priority: below zram's, so Android's memory goes to zram first.
    if (swapon(kSwapFile, 0) != 0) return ErrnoError() << "swapon " << kSwapFile;
    LOG(INFO) << "swap on";
    return {};
}

Result<void> Off() {
    if (!Active()) return {};
    if (swapoff(kSwapFile) != 0) return ErrnoError() << "swapoff " << kSwapFile;
    LOG(INFO) << "swap off";
    return {};
}

Result<void> Remove() {
    if (auto off = Off(); !off.ok()) return off.error();
    if (unlink(kSwapFile) != 0 && errno != ENOENT) return ErrnoError() << kSwapFile;
    return {};
}

}  // namespace swap

}  // namespace lujarch::linux
