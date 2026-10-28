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

#include "bridge.h"

#include <aidl/android/hardware/ir/IConsumerIr.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <aidl/org/lujarch/linux/ILinux.h>
#include <android/binder_manager.h>
#include <android/looper.h>
#include <android/sensor.h>
#include <fcntl.h>
#include <json/value.h>
#include <lujarch/aid.h>
#include <lujarch/daemon.h>
#include <lujarch/text.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <thread>
#include <tuple>

#include "files.h"
#include "paths.h"

namespace lujarch::linux {

namespace {

using aidl::org::lujarch::linux::AndroidState;
using aidl::org::lujarch::linux::ILinux;
using aidl::org::lujarch::linux::LinuxLocation;
using aidl::org::lujarch::linux::LinuxNotification;
using android::base::ErrnoError;
using android::base::Error;
using android::base::unique_fd;

constexpr int kWakeIdent = 1;
constexpr int kIrIdent = 2;
constexpr int kAuthIdent = 3;
constexpr int kNfcIdent = 4;
constexpr int kInotifyIdent = 5;
constexpr int kSensorsIdent = 6;
constexpr int kClipboardIdent = 7;
constexpr int kVibrateIdent = 8;
constexpr int kAndroidSetIdent = 9;
constexpr int kTerminalIdent = 10;
// The readers' write ends, kReaderIdent + their index.
constexpr int kReaderIdent = 16;

constexpr auto kAuthTimeout = std::chrono::seconds(60);
constexpr auto kNfcTimeout = std::chrono::seconds(10);
constexpr size_t kMaxLine = 128 << 10;
constexpr size_t kMaxClipboard = 64 << 10;
constexpr size_t kMaxText = 200;
constexpr gid_t kUsbGroup = 1018;  // AID_USB
constexpr char kUsbGroupName[] = "aid_usb";
constexpr gid_t kMediaRwGroup = 1023;  // AID_MEDIA_RW, the shared storage's group
constexpr char kMediaRwGroupName[] = "aid_media_rw";
// The sampling period of the lent sensors, in microseconds.
constexpr int32_t kSensorPeriodUs = 200000;
// A vibration's pattern: at most this many steps and this long in all.
constexpr size_t kMaxVibrationSteps = 128;
constexpr int kMaxVibrationMs = 30000;
// Android's switches Linux's desktop flips, and their values: 1 or 0, or the brightness slider's
// position.
constexpr const char* kAndroidSwitches[] = {"wifi",       "mobile",   "bluetooth", "airplane",
                                            "hotspot",    "flashlight", "rotation", "dnd",
                                            "caffeine"};
constexpr int kMaxBrightness = 65535;

std::string Run(const char* name) {
    return std::string(kBridgeDir) + "/" + name;
}

// Polls fd on the bridge's looper, which returns ident for it.
void Watch(ALooper* looper, int fd, int ident, int events, const char* what) {
    if (ALooper_addFd(looper, fd, ident, events, nullptr, nullptr) != 1) {
        LOG(ERROR) << "bridge: the looper does not poll " << what;
    }
}

struct SensorKind {
    int type;
    const char* name;
    size_t values;
};

constexpr SensorKind kSensors[] = {
        {ASENSOR_TYPE_ACCELEROMETER, "accelerometer", 3},
        {ASENSOR_TYPE_MAGNETIC_FIELD, "magnetic_field", 3},
        {ASENSOR_TYPE_GYROSCOPE, "gyroscope", 3},
        {ASENSOR_TYPE_LIGHT, "light", 1},
        {ASENSOR_TYPE_PRESSURE, "pressure", 1},
        {ASENSOR_TYPE_PROXIMITY, "proximity", 1},
        {ASENSOR_TYPE_GRAVITY, "gravity", 3},
        {ASENSOR_TYPE_LINEAR_ACCELERATION, "linear_acceleration", 3},
        {ASENSOR_TYPE_ROTATION_VECTOR, "rotation_vector", 4},
        {ASENSOR_TYPE_RELATIVE_HUMIDITY, "relative_humidity", 1},
        {ASENSOR_TYPE_AMBIENT_TEMPERATURE, "ambient_temperature", 1},
};

std::optional<std::vector<uint8_t>> Hex(const std::string& text) {
    if (text.empty() || text.size() % 2 != 0) return std::nullopt;
    std::vector<uint8_t> out;
    for (size_t i = 0; i < text.size(); i += 2) {
        unsigned v;
        if (sscanf(text.c_str() + i, "%2x", &v) != 1 || !isxdigit(text[i]) || !isxdigit(text[i + 1])) {
            return std::nullopt;
        }
        out.push_back(static_cast<uint8_t>(v));
    }
    return out;
}

std::optional<std::string> FromBase64(const std::string& text) {
    static const std::string kDigits =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    uint32_t bits = 0;
    int count = 0;
    for (char c : text) {
        if (c == '=') break;
        const size_t v = kDigits.find(c);
        if (v == std::string::npos) return std::nullopt;
        bits = (bits << 6) | static_cast<uint32_t>(v);
        count += 6;
        if (count >= 8) {
            count -= 8;
            out.push_back(static_cast<char>((bits >> count) & 0xff));
        }
    }
    return out;
}

std::string ToHex(const std::vector<uint8_t>& bytes) {
    static const char kDigits[] = "0123456789abcdef";
    std::string out;
    for (uint8_t b : bytes) {
        out.push_back(kDigits[b >> 4]);
        out.push_back(kDigits[b & 15]);
    }
    return out;
}

// A time zone's name as tzdata names it: Area/Location, letters, digits, '_', '-', '+'.
bool IsZoneName(const std::string& name) {
    if (name.empty() || name.size() > 64 || name.front() == '/' || name.back() == '/') return false;
    for (const std::string& part : android::base::Split(name, "/")) {
        if (part.empty() || part == "." || part == "..") return false;
        for (char c : part) {
            if (!isalnum(static_cast<unsigned char>(c)) && c != '_' && c != '-' && c != '+') return false;
        }
    }
    return true;
}

// A request line: the reply FIFO's name, a space, the rest.
bool SplitRequest(const std::string& line, std::string* reply, std::string* rest) {
    const size_t space = line.find(' ');
    if (space == std::string::npos) return false;
    *reply = line.substr(0, space);
    *rest = line.substr(space + 1);
    return files::IsPlainName(*reply);
}

}  // namespace

Bridge::Bridge(System& system, Asks asks)
    : system_(system),
      asks_(std::move(asks)),
      readers_{{{ILinux::STREAM_MICROPHONE, "microphone", kReaderIdent},
                {ILinux::STREAM_CAMERA_BACK, "camera", kReaderIdent + 1},
                {ILinux::STREAM_CAMERA_FRONT, "camera", kReaderIdent + 2},
                {ILinux::STREAM_LOCATION, "location", kReaderIdent + 3}}} {}

void Bridge::Start() {
    wake_.reset(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    inotify_.reset(inotify_init1(IN_CLOEXEC | IN_NONBLOCK));
    if (wake_ < 0 || inotify_ < 0) PLOG(FATAL) << "bridge: eventfd or inotify";
    std::thread(&Bridge::Loop, this).detach();
}

void Bridge::Wake() {
    uint64_t one = 1;
    TEMP_FAILURE_RETRY(write(wake_.get(), &one, sizeof(one)));
}

void Bridge::SetLinux(bool running, const std::string& user) {
    {
        std::lock_guard lock(mutex_);
        if (running && !linux_) ++generation_;
        linux_ = running;
        user_ = user;
    }
    Wake();
}

void Bridge::Recheck() {
    Wake();
}

Result<void> Bridge::Group(gid_t id, const char* name, bool member) {
    if (user_.empty()) return {};
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return root.error();
    auto group = files::ReadIn(root->get(), "/etc/group", 1 << 20);
    if (!group.ok()) return group.error();
    std::vector<std::string> lines = android::base::Split(*group, "\n");
    if (!lines.empty() && lines.back().empty()) lines.pop_back();
    bool found = false;
    bool changed = false;
    for (std::string& line : lines) {
        std::vector<std::string> f = android::base::Split(line, ":");
        unsigned gid;
        if (f.size() < 4 || !android::base::ParseUint(f[2], &gid) || gid != id) continue;
        found = true;
        std::vector<std::string> members;
        for (const std::string& m : android::base::Split(f[3], ",")) {
            if (!m.empty() && m != user_) members.push_back(m);
        }
        if (member) members.push_back(user_);
        const std::string joined = android::base::Join(members, ",");
        if (joined != f[3]) {
            f[3] = joined;
            line = android::base::Join(f, ":");
            changed = true;
        }
    }
    if (!found && member) {
        lines.push_back(std::string(name) + ":x:" + std::to_string(id) + ":" + user_);
        changed = true;
    }
    if (!changed) return {};
    struct stat st;
    auto fd = files::OpenIn(root->get(), "/etc/group", O_RDONLY);
    if (!fd.ok() || fstat(fd->get(), &st) != 0) return ErrnoError() << "/etc/group";
    return files::WriteIn(root->get(), "/etc/group", android::base::Join(lines, "\n") + "\n",
                          st.st_mode & 07777, st.st_uid, st.st_gid);
}

void Bridge::Arrange() {
    auto lent = [&](const char* part) {
        return linux_ && SettingIsOn(kFeature, std::string("lend_") + part);
    };
    ALooper* looper = ALooper_forThread();
    // A new start of Linux: its /run is new, and the cameras are bound into its new namespace.
    if (generation_ != arranged_) {
        arranged_ = generation_;
        for (Reader& r : readers_) {
            ReaderGone(r);
            if (r.watch >= 0) inotify_rm_watch(inotify_.get(), r.watch);
            r.watch = -1;
        }
        for (unique_fd* fd : {&vibrate_fifo_, &android_set_fifo_, &terminal_fifo_}) {
            if (*fd >= 0) ALooper_removeFd(looper, fd->get());
            fd->reset();
        }
        android_lent_ = false;
        cameras_checked_ = false;
        storage_checked_ = false;
        timezone_.clear();
    }
    auto root = files::OpenRoot(kRoot);
    const bool ready = linux_ && root.ok() &&
                       files::MakeDirsIn(root->get(), kBridgeDir, 01775, 0, aid::kLinux).ok();

    // USB devices; at every start of Linux /etc/group is made to agree with the switch.
    const bool usb = linux_ && lent("usb");
    if (usb != usb_lent_ || (linux_ && !usb_checked_)) {
        if (auto r = system_.LendUsb(usb); !r.ok()) LOG(ERROR) << "bridge: " << r.error();
        if (auto r = Group(kUsbGroup, kUsbGroupName, usb); !r.ok()) {
            LOG(ERROR) << "bridge: aid_usb in /etc/group: " << r.error();
        }
        if (usb != usb_lent_) LOG(INFO) << "bridge: USB devices " << (usb ? "lent" : "taken back");
        usb_lent_ = usb;
    }
    usb_checked_ = linux_;

    // The cameras' device nodes; bound again into every new namespace.
    const bool cameras = linux_ && lent("camera");
    if (cameras != cameras_lent_ || (linux_ && !cameras_checked_)) {
        if (auto r = system_.LendCameras(cameras); !r.ok()) LOG(ERROR) << "bridge: " << r.error();
        if (cameras != cameras_lent_) {
            LOG(INFO) << "bridge: cameras " << (cameras ? "lent" : "taken back");
        }
        cameras_lent_ = cameras;
    }
    cameras_checked_ = linux_;

    // The shared storage; bound again into every new namespace. The owner's user is in its group
    // whenever Linux runs, so that programs running before it is lent reach it too.
    const bool storage = linux_ && lent("storage");
    if (storage != storage_lent_ || (linux_ && !storage_checked_)) {
        if (linux_ && !storage_checked_) {
            if (auto r = Group(kMediaRwGroup, kMediaRwGroupName, true); !r.ok()) {
                LOG(ERROR) << "bridge: aid_media_rw in /etc/group: " << r.error();
            }
        }
        if (auto r = system_.LendStorage(storage, user_); !r.ok()) {
            LOG(ERROR) << "bridge: " << r.error();
        }
        if (ready && storage) {
            const std::string folder =
                    "/home/" + user_ + "/" + kSharedStorageFolder + "\n";
            if (auto w = files::WriteIn(root->get(), Run("storage"), folder, 0640, 0, aid::kLinux);
                !w.ok()) {
                LOG(ERROR) << "bridge: storage: " << w.error();
            }
        } else if (ready && files::ExistsIn(root->get(), Run("storage"))) {
            files::RemoveIn(root->get(), Run("storage"));
        }
        if (storage != storage_lent_) {
            LOG(INFO) << "bridge: shared storage " << (storage ? "lent" : "taken back");
        }
        storage_lent_ = storage;
    }
    storage_checked_ = linux_;

    // A FIFO the daemon reads.
    auto fifo = [&](const char* name, bool want, unique_fd& fd, int ident, mode_t mode,
                    const char* reply_dir) {
        if (want && ready && fd < 0) {
            if (auto r = files::MakeFifoIn(root->get(), Run(name), mode, aid::kLinux); !r.ok()) {
                LOG(ERROR) << "bridge: " << r.error();
                return;
            }
            if (reply_dir != nullptr) {
                if (auto r = files::MakeDirsIn(root->get(), Run(reply_dir), 01773, 0, aid::kLinux);
                    !r.ok()) {
                    LOG(ERROR) << "bridge: " << r.error();
                }
            }
            auto opened = files::OpenIn(root->get(), Run(name), O_RDWR | O_NONBLOCK);
            if (!opened.ok()) {
                LOG(ERROR) << "bridge: " << opened.error();
                return;
            }
            fd = std::move(*opened);
            Watch(looper, fd.get(), ident, ALOOPER_EVENT_INPUT, name);
            LOG(INFO) << "bridge: " << name << " lent";
        } else if (!want && fd >= 0) {
            ALooper_removeFd(looper, fd.get());
            fd.reset();
            if (root.ok() && linux_) files::RemoveIn(root->get(), Run(name));
            LOG(INFO) << "bridge: " << name << " taken back";
        }
    };
    fifo("ir", lent("ir"), ir_, kIrIdent, 0620, nullptr);
    fifo("auth", lent("fingerprint"), auth_fifo_, kAuthIdent, 0620, "auth.d");
    fifo("nfc", lent("nfc"), nfc_fifo_, kNfcIdent, 0620, "nfc.d");
    const bool clipboard_was_lent = clipboard_fifo_ >= 0;
    fifo("clipboard-set", lent("clipboard"), clipboard_fifo_, kClipboardIdent, 0620, nullptr);
    fifo("vibrate", lent("vibration"), vibrate_fifo_, kVibrateIdent, 0620, nullptr);
    fifo("android-set", lent("quick_settings"), android_set_fifo_, kAndroidSetIdent, 0620, nullptr);
    fifo("terminal", linux_, terminal_fifo_, kTerminalIdent, 0620, nullptr);
    if (clipboard_was_lent && clipboard_fifo_ < 0) {
        if (root.ok() && linux_) files::RemoveIn(root->get(), Run("clipboard"));
        android_clipboard_.clear();
        linux_clipboard_.clear();
    }
    if (auth_fifo_ < 0) {
        for (auto& [id, p] : auth_) Reply("auth.d", p.reply, "deny");
        auth_.clear();
    }
    if (nfc_fifo_ < 0) {
        for (auto& [id, p] : nfc_) Reply("nfc.d", p.reply, "error not lent");
        nfc_.clear();
    }

    // Sensors: a FIFO the daemon writes while a program reads it.
    const bool sensors = lent("sensors");
    if (sensors && ready && sensors_watch_ < 0) {
        if (auto r = files::MakeFifoIn(root->get(), Run("sensors"), 0640, aid::kLinux); !r.ok()) {
            LOG(ERROR) << "bridge: " << r.error();
        } else if (auto path = files::OpenIn(root->get(), Run("sensors"), O_PATH | O_NOFOLLOW);
                   path.ok()) {
            const std::string link = "/proc/self/fd/" + std::to_string(path->get());
            sensors_watch_ = inotify_add_watch(inotify_.get(), link.c_str(), IN_OPEN);
            if (sensors_watch_ < 0) PLOG(ERROR) << "bridge: inotify on the sensors";
            LOG(INFO) << "bridge: sensors lent";
        }
    } else if (!sensors && sensors_watch_ >= 0) {
        SensorsStop();
        inotify_rm_watch(inotify_.get(), sensors_watch_);
        sensors_watch_ = -1;
        if (root.ok() && linux_) files::RemoveIn(root->get(), Run("sensors"));
        LOG(INFO) << "bridge: sensors taken back";
    }

    // FIFOs Android writes while a program reads them.
    for (Reader& r : readers_) {
        const bool want = lent(r.part);
        if (want && ready && r.watch < 0) {
            if (auto made = files::MakeFifoIn(root->get(), Run(r.name), 0640, aid::kLinux); !made.ok()) {
                LOG(ERROR) << "bridge: " << made.error();
                continue;
            }
            auto path = files::OpenIn(root->get(), Run(r.name), O_PATH | O_NOFOLLOW);
            if (!path.ok()) {
                LOG(ERROR) << "bridge: " << path.error();
                continue;
            }
            const std::string link = "/proc/self/fd/" + std::to_string(path->get());
            r.watch = inotify_add_watch(inotify_.get(), link.c_str(), IN_OPEN);
            if (r.watch < 0) {
                PLOG(ERROR) << "bridge: inotify on " << r.name;
                continue;
            }
            LOG(INFO) << "bridge: " << r.name << " lent";
        } else if (!want && r.watch >= 0) {
            ReaderGone(r);
            inotify_rm_watch(inotify_.get(), r.watch);
            r.watch = -1;
            if (root.ok() && linux_) files::RemoveIn(root->get(), Run(r.name));
            LOG(INFO) << "bridge: " << r.name << " taken back";
        }
    }

    // Android's switches and readings.
    const bool android = lent("quick_settings");
    if (android && ready) {
        if (!android_lent_) LOG(INFO) << "bridge: Android's quick settings lent";
        android_lent_ = true;
        WriteAndroidState();
    } else if (android_lent_) {
        android_lent_ = false;
        android_state_.reset();
        if (root.ok() && linux_) files::RemoveIn(root->get(), Run("android"));
        LOG(INFO) << "bridge: Android's quick settings taken back";
    }

    if (ready) Timezone();
    if (!linux_) timezone_.clear();

    // Notifications and the NFC card.
    const bool notifications = lent("notifications");
    if (notifications && ready) {
        if (!notifications_lent_) LOG(INFO) << "bridge: notifications lent";
        notifications_lent_ = true;
        WriteNotifications();
    } else if (notifications_lent_) {
        notifications_lent_ = false;
        if (root.ok() && linux_) files::RemoveIn(root->get(), Run("notifications"));
        LOG(INFO) << "bridge: notifications taken back";
    }
    if (root.ok() && ready) {
        if (nfc_fifo_ >= 0 && card_) {
            files::WriteIn(root->get(), Run("nfc-card"), *card_ + "\n", 0640, 0, aid::kLinux);
        } else {
            files::RemoveIn(root->get(), Run("nfc-card"));
        }
    }
}

void Bridge::Lines(unique_fd& fd, std::string& buffer,
                   const std::function<void(const std::string&)>& line) {
    char buf[4096];
    ssize_t n;
    while (fd >= 0 && (n = read(fd.get(), buf, sizeof(buf))) > 0) {
        buffer.append(buf, static_cast<size_t>(n));
        if (buffer.size() > kMaxLine) {
            LOG(WARNING) << "bridge: a request longer than " << kMaxLine << " bytes dropped";
            buffer.clear();
        }
    }
    size_t nl;
    while ((nl = buffer.find('\n')) != std::string::npos) {
        const std::string one = buffer.substr(0, nl);
        buffer.erase(0, nl + 1);
        if (!one.empty()) line(one);
    }
}

void Bridge::Ir(const std::string& line) {
    std::vector<std::string> f = android::base::Tokenize(line, " \t");
    int32_t carrier;
    if (f.size() < 2 || !android::base::ParseInt(f[0], &carrier, 1, 1000000)) {
        LOG(WARNING) << "bridge: ir: not '<carrier Hz> <durations µs...>'";
        return;
    }
    std::vector<int32_t> pattern;
    for (size_t i = 1; i < f.size() && pattern.size() < 1024; ++i) {
        int32_t us;
        if (!android::base::ParseInt(f[i], &us, 1, 2000000)) {
            LOG(WARNING) << "bridge: ir: not a duration: " << f[i];
            return;
        }
        pattern.push_back(us);
    }
    using aidl::android::hardware::ir::IConsumerIr;
    const std::string name = std::string(IConsumerIr::descriptor) + "/default";
    ndk::SpAIBinder binder(AServiceManager_checkService(name.c_str()));
    auto ir = IConsumerIr::fromBinder(binder);
    if (!ir) {
        LOG(WARNING) << "bridge: ir: the phone has no infrared transmitter";
        return;
    }
    if (auto status = ir->transmit(carrier, pattern); !status.isOk()) {
        LOG(WARNING) << "bridge: ir: " << status.getDescription();
    }
}

void Bridge::Auth(const std::string& line) {
    std::string reply;
    std::string message;
    if (!SplitRequest(line, &reply, &message)) {
        LOG(WARNING) << "bridge: auth: not '<reply> <text>'";
        return;
    }
    message = text::SanitizeUtf8(message);
    if (message.size() > kMaxText) message = text::SanitizeUtf8(message.substr(0, kMaxText));
    if (!asks_.answerable()) {
        LOG(INFO) << "bridge: auth: the Terminal app is not on screen; refused";
        Reply("auth.d", reply, "deny");
        return;
    }
    const int id = next_id_++;
    auth_[id] = {reply, Clock::now() + kAuthTimeout};
    LOG(INFO) << "bridge: auth request " << id;
    asks_.auth(id, message);
}

void Bridge::Nfc(const std::string& line) {
    std::string reply;
    std::string hex;
    if (!SplitRequest(line, &reply, &hex)) {
        LOG(WARNING) << "bridge: nfc: not '<reply> <command in hex>'";
        return;
    }
    auto command = Hex(android::base::Trim(hex));
    if (!command) {
        Reply("nfc.d", reply, "error not hex");
        return;
    }
    if (!card_ || !asks_.answerable()) {
        Reply("nfc.d", reply, "error no card");
        return;
    }
    const int id = next_id_++;
    nfc_[id] = {reply, Clock::now() + kNfcTimeout};
    asks_.nfc(id, *command);
}

void Bridge::Clipboard(const std::string& line) {
    auto decoded = FromBase64(android::base::Trim(line));
    if (!decoded) {
        LOG(WARNING) << "bridge: clipboard-set: not base64";
        return;
    }
    std::string copied = text::SanitizeUtf8(decoded->substr(0, kMaxClipboard));
    if (copied == android_clipboard_ || copied == linux_clipboard_) return;
    linux_clipboard_ = copied;
    asks_.clipboard(copied);
}

bool Bridge::SetClipboard(const std::string& value) {
    std::lock_guard lock(mutex_);
    if (clipboard_fifo_ < 0) return false;
    std::string clean = text::SanitizeUtf8(value.substr(0, kMaxClipboard));
    if (clean == android_clipboard_) return true;
    android_clipboard_ = clean;
    // Linux's own copy, back from Android: Linux has it already.
    if (clean == linux_clipboard_) return true;
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return true;
    if (auto w = files::WriteIn(root->get(), Run("clipboard"), clean, 0640, 0, aid::kLinux); !w.ok()) {
        LOG(ERROR) << "bridge: clipboard: " << w.error();
    }
    return true;
}

void Bridge::AndroidSet(const std::string& line) {
    const size_t space = line.find(' ');
    const std::string key = line.substr(0, space);
    const std::string value = space == std::string::npos ? "" : android::base::Trim(line.substr(space + 1));
    bool valid;
    if (key == "brightness") {
        int position;
        valid = android::base::ParseInt(value, &position, 0, kMaxBrightness);
    } else {
        valid = (value == "1" || value == "0") &&
                std::find_if(std::begin(kAndroidSwitches), std::end(kAndroidSwitches),
                             [&](const char* k) { return key == k; }) != std::end(kAndroidSwitches);
    }
    if (!valid) {
        LOG(WARNING) << "bridge: android-set: not '<switch> 1|0' or 'brightness <0-" << kMaxBrightness
                     << ">': " << text::SanitizeUtf8(line.substr(0, 64));
        return;
    }
    if (!asks_.answerable()) {
        LOG(INFO) << "bridge: android-set " << key << ": the Terminal app is not on screen";
        return;
    }
    asks_.android_set(key, value);
}

void Bridge::ShowTerminal(const std::string& line) {
    if (android::base::Trim(line) != "show") {
        LOG(WARNING) << "bridge: terminal: not 'show': " << text::SanitizeUtf8(line.substr(0, 64));
        return;
    }
    if (!asks_.answerable()) {
        LOG(INFO) << "bridge: terminal: the Terminal app is not on screen";
        return;
    }
    asks_.show_terminal();
}

void Bridge::Vibrate(const std::string& line) {
    const std::vector<std::string> steps = android::base::Split(android::base::Trim(line), ",");
    bool valid = !steps.empty() && steps.size() <= kMaxVibrationSteps;
    int total = 0;
    for (size_t i = 0; valid && i < steps.size(); ++i) {
        const std::vector<std::string> f = android::base::Split(steps[i], ":");
        int amplitude = 255;
        int ms = 0;
        valid = f.size() <= 2 && android::base::ParseInt(f.back(), &ms, 0, kMaxVibrationMs) &&
                (f.size() == 1 || android::base::ParseInt(f[0], &amplitude, 0, 255));
        total += ms;
    }
    if (!valid || total > kMaxVibrationMs) {
        LOG(WARNING) << "bridge: vibrate: not '<amplitude 0-255>:<ms>,...' (at most "
                     << kMaxVibrationSteps << " steps, " << kMaxVibrationMs << " ms)";
        return;
    }
    // One for each key a keyboard presses: not logged.
    if (asks_.answerable()) asks_.vibrate(android::base::Trim(line));
}

void Bridge::ReaderOpened(Reader& r) {
    if (r.out >= 0) return;
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return;
    // ENXIO: the program closed it again already.
    auto fd = files::OpenIn(root->get(), Run(r.name), O_WRONLY | O_NONBLOCK);
    if (!fd.ok()) return;
    r.out = std::move(*fd);
    // Only errors: the last reader leaving.
    Watch(ALooper_forThread(), r.out.get(), r.ident, 0, r.name);
    LOG(INFO) << "bridge: a program reads " << r.name;
    asks_.stream(r.name, true);
}

void Bridge::ReaderGone(Reader& r) {
    if (r.out < 0) return;
    ALooper_removeFd(ALooper_forThread(), r.out.get());
    r.out.reset();
    LOG(INFO) << "bridge: no program reads " << r.name;
    asks_.stream(r.name, false);
}

bool Bridge::SetAndroidState(const AndroidState& state) {
    std::lock_guard lock(mutex_);
    if (!android_lent_) return false;
    android_state_ = state;
    WriteAndroidState();
    return true;
}

void Bridge::WriteAndroidState() {
    if (!android_state_) return;
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return;
    const AndroidState& a = *android_state_;
    auto clean = [](const std::string& t) { return text::SanitizeUtf8(t.substr(0, kMaxText)); };
    auto known = [](bool k, Json::Value v) { return k ? v : Json::Value(Json::nullValue); };
    Json::Value out(Json::objectValue);
    Json::Value wifi(Json::objectValue);
    wifi["on"] = a.wifiOn;
    wifi["connected"] = a.wifiConnected;
    wifi["ssid"] = clean(a.wifiSsid);
    wifi["level"] = std::clamp(a.wifiLevel, 0, 4);
    out["wifi"] = known(a.wifiKnown, wifi);
    Json::Value mobile(Json::objectValue);
    mobile["present"] = a.mobilePresent;
    mobile["on"] = a.mobileOn;
    mobile["operator"] = clean(a.mobileOperator);
    mobile["level"] = std::clamp(a.mobileLevel, 0, 4);
    mobile["tech"] = clean(a.mobileTech);
    mobile["connected"] = a.mobileConnected;
    out["mobile"] = known(a.mobileKnown, mobile);
    Json::Value bluetooth(Json::objectValue);
    bluetooth["on"] = a.bluetoothOn;
    Json::Value devices(Json::arrayValue);
    for (size_t i = 0; i < a.bluetoothConnected.size() && i < 32; ++i) {
        devices.append(clean(a.bluetoothConnected[i]));
    }
    bluetooth["connected"] = devices;
    out["bluetooth"] = known(a.bluetoothKnown, bluetooth);
    Json::Value airplane(Json::objectValue);
    airplane["on"] = a.airplaneOn;
    out["airplane"] = known(a.airplaneKnown, airplane);
    Json::Value hotspot(Json::objectValue);
    hotspot["on"] = a.hotspotOn;
    out["hotspot"] = known(a.hotspotKnown, hotspot);
    Json::Value flashlight(Json::objectValue);
    flashlight["available"] = a.flashlightAvailable;
    flashlight["on"] = a.flashlightOn;
    out["flashlight"] = flashlight;
    Json::Value battery(Json::objectValue);
    battery["percent"] = std::clamp(a.batteryPercent, 0, 100);
    battery["charging"] = a.batteryCharging;
    battery["plugged"] = a.batteryPlugged;
    out["battery"] = known(a.batteryKnown, battery);
    Json::Value rotation(Json::objectValue);
    rotation["auto"] = a.rotationAuto;
    out["rotation"] = known(a.rotationKnown, rotation);
    Json::Value dnd(Json::objectValue);
    dnd["on"] = a.dndOn;
    out["dnd"] = known(a.dndKnown, dnd);
    Json::Value caffeine(Json::objectValue);
    caffeine["on"] = a.caffeineOn;
    out["caffeine"] = caffeine;
    Json::Value brightness(Json::objectValue);
    brightness["value"] = std::clamp(a.brightness, 0, kMaxBrightness);
    brightness["max"] = std::clamp(a.brightnessMax, 1, kMaxBrightness);
    out["brightness"] = known(a.brightnessKnown, brightness);
    const std::string transport = a.transport;
    out["default"] = transport == "wifi" || transport == "mobile" || transport == "other" ? transport : "none";
    if (auto w = files::WriteIn(root->get(), Run("android"), text::ToJson(out) + "\n", 0640, 0,
                                aid::kLinux);
        !w.ok()) {
        LOG(ERROR) << "bridge: android: " << w.error();
    }
}

void Bridge::SetLocation(const LinuxLocation& l) {
    std::lock_guard lock(mutex_);
    Reader& r = readers_[3];
    if (r.out < 0) return;
    Json::Value out(Json::objectValue);
    if (l.error) {
        out["error"] = *l.error == "permission" ? "permission" : "off";
    } else {
        if (!std::isfinite(l.latitude) || !std::isfinite(l.longitude) || std::abs(l.latitude) > 90 ||
            std::abs(l.longitude) > 180) {
            return;
        }
        out["lat"] = l.latitude;
        out["lon"] = l.longitude;
        out["time"] = static_cast<Json::Int64>(l.time);
        if (l.hasAltitude && std::isfinite(l.altitude)) out["alt"] = l.altitude;
        if (l.hasAccuracy && std::isfinite(l.accuracy)) out["acc"] = l.accuracy;
        if (l.hasSpeed && std::isfinite(l.speed)) out["speed"] = l.speed;
        if (l.hasBearing && std::isfinite(l.bearing)) out["bearing"] = l.bearing;
    }
    const std::string line = text::ToJson(out) + "\n";
    // A fix the reader has not taken yet is followed by the next; one written while it lags is
    // dropped.
    if (write(r.out.get(), line.data(), line.size()) < 0 && errno == EPIPE) ReaderGone(r);
}

Result<unique_fd> Bridge::OpenStream(const std::string& stream) {
    std::lock_guard lock(mutex_);
    for (Reader& r : readers_) {
        if (stream != r.name || r.ident == readers_[3].ident) continue;
        if (r.out < 0) return Error(ENOENT) << "no program reads " << stream;
        unique_fd fd(fcntl(r.out.get(), F_DUPFD_CLOEXEC, 0));
        if (fd < 0) return ErrnoError() << stream;
        return fd;
    }
    return Error(EINVAL) << "no stream " << stream;
}

std::vector<std::string> Bridge::Wanted() {
    std::lock_guard lock(mutex_);
    std::vector<std::string> wanted;
    for (const Reader& r : readers_) {
        if (r.out >= 0) wanted.push_back(r.name);
    }
    return wanted;
}

void Bridge::Timezone() {
    const std::string zone = android::base::GetProperty(kTimezoneProperty, "");
    if (zone.empty() || zone == timezone_) return;
    timezone_ = zone;
    if (!IsZoneName(zone)) {
        LOG(WARNING) << "bridge: Android's time zone is no zone name: " << text::SanitizeUtf8(zone);
        return;
    }
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return;
    const std::string target = std::string(kZoneinfo) + "/" + zone;
    if (!files::ExistsIn(root->get(), target)) {
        LOG(WARNING) << "bridge: Linux has no " << target << " (tzdata): its time zone stays";
        return;
    }
    std::string name;
    auto etc = files::OpenParentIn(root->get(), "/etc/localtime", &name);
    if (!etc.ok()) {
        LOG(ERROR) << "bridge: /etc/localtime: " << etc.error();
        return;
    }
    const std::string tmp = ".localtime.lujarch-new";
    unlinkat(etc->get(), tmp.c_str(), 0);
    if (symlinkat(target.c_str(), etc->get(), tmp.c_str()) != 0 ||
        renameat(etc->get(), tmp.c_str(), etc->get(), name.c_str()) != 0) {
        PLOG(ERROR) << "bridge: /etc/localtime";
        unlinkat(etc->get(), tmp.c_str(), 0);
        return;
    }
    LOG(INFO) << "bridge: Linux's time zone is " << zone;
}

void Bridge::Reply(const char* dir, const std::string& reply, const std::string& text) {
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return;
    auto fd = files::OpenIn(root->get(), Run(dir) + "/" + reply, O_WRONLY | O_NONBLOCK);
    if (!fd.ok()) {
        LOG(WARNING) << "bridge: no reply FIFO " << dir << "/" << reply << ": " << fd.error();
        return;
    }
    struct stat st;
    if (fstat(fd->get(), &st) != 0 || !S_ISFIFO(st.st_mode)) {
        LOG(WARNING) << "bridge: " << dir << "/" << reply << " is not a FIFO";
        return;
    }
    const std::string out = text + "\n";
    TEMP_FAILURE_RETRY(write(fd->get(), out.data(), out.size()));
}

void Bridge::AnswerAuth(int id, bool allowed) {
    std::lock_guard lock(mutex_);
    auto it = auth_.find(id);
    if (it == auth_.end()) return;
    LOG(INFO) << "bridge: auth request " << id << (allowed ? " confirmed" : " refused");
    Reply("auth.d", it->second.reply, allowed ? "allow" : "deny");
    auth_.erase(it);
}

void Bridge::SetNfcCard(const std::optional<std::string>& card) {
    {
        std::lock_guard lock(mutex_);
        std::optional<std::string> clean;
        if (card) clean = text::SanitizeUtf8(card->substr(0, kMaxText));
        if (clean == card_) return;
        card_ = clean;
        if (!card_) {
            for (auto& [id, p] : nfc_) Reply("nfc.d", p.reply, "error no card");
            nfc_.clear();
        }
    }
    Wake();
}

void Bridge::AnswerNfc(int id, const std::vector<uint8_t>& response,
                       const std::optional<std::string>& error) {
    std::lock_guard lock(mutex_);
    auto it = nfc_.find(id);
    if (it == nfc_.end()) return;
    std::string answer =
            error ? "error " + text::SanitizeUtf8(error->substr(0, kMaxText)) : ToHex(response);
    std::replace(answer.begin(), answer.end(), '\n', ' ');
    Reply("nfc.d", it->second.reply, answer);
    nfc_.erase(it);
}

void Bridge::SetNotifications(const std::vector<LinuxNotification>& list) {
    {
        std::lock_guard lock(mutex_);
        notifications_ = list;
    }
    Wake();
}

void Bridge::WriteNotifications() {
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return;
    Json::Value out(Json::objectValue);
    out["count"] = static_cast<Json::UInt>(notifications_.size());
    Json::Value list(Json::arrayValue);
    for (const LinuxNotification& n : notifications_) {
        Json::Value item(Json::objectValue);
        item["app"] = text::SanitizeUtf8(n.app);
        item["title"] = text::SanitizeUtf8(n.title);
        item["text"] = text::SanitizeUtf8(n.text);
        item["time"] = static_cast<Json::Int64>(n.time);
        list.append(item);
    }
    out["notifications"] = list;
    if (auto w = files::WriteIn(root->get(), Run("notifications"), text::ToJson(out) + "\n", 0640,
                                0, aid::kLinux);
        !w.ok()) {
        LOG(ERROR) << "bridge: notifications: " << w.error();
    }
}

void Bridge::SensorsReader() {
    if (sensors_out_ >= 0) return;
    auto root = files::OpenRoot(kRoot);
    if (!root.ok()) return;
    auto fd = files::OpenIn(root->get(), Run("sensors"), O_WRONLY | O_NONBLOCK);
    if (!fd.ok()) return;  // ENXIO: an open for writing, no reader
    sensors_out_ = std::move(*fd);
    ASensorManager* manager = ASensorManager_getInstanceForPackage("lujarch_linux");
    if (manager == nullptr) {
        LOG(ERROR) << "bridge: no sensor manager";
        sensors_out_.reset();
        return;
    }
    ASensorEventQueue* queue =
            ASensorManager_createEventQueue(manager, ALooper_forThread(), kSensorsIdent, nullptr, nullptr);
    if (queue == nullptr) {
        LOG(ERROR) << "bridge: no sensor event queue";
        sensors_out_.reset();
        return;
    }
    for (const SensorKind& kind : kSensors) {
        const ASensor* sensor = ASensorManager_getDefaultSensor(manager, kind.type);
        if (sensor != nullptr) ASensorEventQueue_registerSensor(queue, sensor, kSensorPeriodUs, 0);
    }
    sensors_queue_ = queue;
    sensor_values_.clear();
    LOG(INFO) << "bridge: a program reads the sensors";
}

void Bridge::SensorsStop() {
    if (sensors_queue_ != nullptr) {
        ASensorManager* manager = ASensorManager_getInstanceForPackage("lujarch_linux");
        ASensorManager_destroyEventQueue(manager, static_cast<ASensorEventQueue*>(sensors_queue_));
        sensors_queue_ = nullptr;
        LOG(INFO) << "bridge: no program reads the sensors";
    }
    sensors_out_.reset();
}

void Bridge::SensorsWrite() {
    Json::Value out(Json::objectValue);
    out["time"] = static_cast<Json::Int64>(time(nullptr)) * 1000;
    for (const SensorKind& kind : kSensors) {
        auto it = sensor_values_.find(kind.type);
        if (it == sensor_values_.end()) continue;
        if (kind.values == 1) {
            out[kind.name] = it->second[0];
        } else {
            Json::Value v(Json::arrayValue);
            for (float f : it->second) v.append(f);
            out[kind.name] = v;
        }
    }
    const std::string line = text::ToJson(out) + "\n";
    const ssize_t n = write(sensors_out_.get(), line.data(), line.size());
    if (n < 0 && errno != EAGAIN) SensorsStop();
}

void Bridge::Loop() {
    // Every fd is polled by ident, without a callback.
    ALooper* looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    Watch(looper, wake_.get(), kWakeIdent, ALOOPER_EVENT_INPUT, "its wake-up");
    Watch(looper, inotify_.get(), kInotifyIdent, ALOOPER_EVENT_INPUT, "inotify");
    auto last_write = Clock::now();
    for (;;) {
        int timeout = -1;
        {
            std::lock_guard lock(mutex_);
            const auto now = Clock::now();
            if (sensors_out_ >= 0) timeout = 1000;
            for (auto* pending : {&auth_, &nfc_}) {
                for (auto& [id, p] : *pending) {
                    const int left = static_cast<int>(std::max<int64_t>(
                            0, std::chrono::duration_cast<std::chrono::milliseconds>(p.deadline - now).count()));
                    timeout = timeout < 0 ? left : std::min(timeout, left);
                }
            }
        }
        const int ident = ALooper_pollOnce(timeout, nullptr, nullptr, nullptr);
        std::lock_guard lock(mutex_);
        switch (ident) {
            case kWakeIdent: {
                uint64_t count;
                while (read(wake_.get(), &count, sizeof(count)) > 0) {
                }
                Arrange();
                break;
            }
            case kIrIdent:
                Lines(ir_, ir_buffer_, [this](const std::string& l) { Ir(l); });
                break;
            case kAuthIdent:
                Lines(auth_fifo_, auth_buffer_, [this](const std::string& l) { Auth(l); });
                break;
            case kNfcIdent:
                Lines(nfc_fifo_, nfc_buffer_, [this](const std::string& l) { Nfc(l); });
                break;
            case kClipboardIdent:
                Lines(clipboard_fifo_, clipboard_buffer_, [this](const std::string& l) { Clipboard(l); });
                break;
            case kVibrateIdent:
                Lines(vibrate_fifo_, vibrate_buffer_, [this](const std::string& l) { Vibrate(l); });
                break;
            case kAndroidSetIdent:
                Lines(android_set_fifo_, android_set_buffer_,
                      [this](const std::string& l) { AndroidSet(l); });
                break;
            case kTerminalIdent:
                Lines(terminal_fifo_, terminal_buffer_, [this](const std::string& l) { ShowTerminal(l); });
                break;
            case kInotifyIdent: {
                alignas(inotify_event) char buf[4096];
                bool opened = false;
                std::array<bool, std::tuple_size_v<decltype(readers_)>> read_now = {};
                ssize_t n;
                while ((n = read(inotify_.get(), buf, sizeof(buf))) > 0) {
                    for (char* p = buf; p < buf + n;) {
                        auto* e = reinterpret_cast<inotify_event*>(p);
                        if ((e->mask & IN_OPEN) && e->wd >= 0) {
                            if (e->wd == sensors_watch_) opened = true;
                            for (size_t i = 0; i < readers_.size(); ++i) {
                                if (e->wd == readers_[i].watch) read_now[i] = true;
                            }
                        }
                        p += sizeof(inotify_event) + e->len;
                    }
                }
                if (opened) SensorsReader();
                for (size_t i = 0; i < readers_.size(); ++i) {
                    if (read_now[i]) ReaderOpened(readers_[i]);
                }
                break;
            }
            case kSensorsIdent: {
                ASensorEvent events[16];
                auto* queue = static_cast<ASensorEventQueue*>(sensors_queue_);
                ssize_t n;
                while (queue != nullptr && (n = ASensorEventQueue_getEvents(queue, events, 16)) > 0) {
                    for (ssize_t i = 0; i < n; ++i) {
                        for (const SensorKind& kind : kSensors) {
                            if (kind.type != events[i].type) continue;
                            sensor_values_[kind.type].assign(events[i].data,
                                                             events[i].data + kind.values);
                        }
                    }
                }
                break;
            }
            default:
                for (Reader& r : readers_) {
                    if (ident == r.ident) ReaderGone(r);
                }
                break;
        }
        const auto now = Clock::now();
        for (auto it = auth_.begin(); it != auth_.end();) {
            if (now < it->second.deadline) {
                ++it;
                continue;
            }
            LOG(INFO) << "bridge: auth request " << it->first << " not answered in time";
            Reply("auth.d", it->second.reply, "deny");
            it = auth_.erase(it);
        }
        for (auto it = nfc_.begin(); it != nfc_.end();) {
            if (now < it->second.deadline) {
                ++it;
                continue;
            }
            Reply("nfc.d", it->second.reply, "error timeout");
            it = nfc_.erase(it);
        }
        if (sensors_out_ >= 0 && now - last_write >= std::chrono::seconds(1)) {
            last_write = now;
            SensorsWrite();
        }
    }
}

}  // namespace lujarch::linux
