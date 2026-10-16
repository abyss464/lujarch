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
#include <android-base/strings.h>
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
#include <cstdio>
#include <thread>

#include "files.h"
#include "paths.h"

namespace lujarch::linux {

namespace {

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

constexpr auto kAuthTimeout = std::chrono::seconds(60);
constexpr auto kNfcTimeout = std::chrono::seconds(10);
constexpr size_t kMaxLine = 128 << 10;
constexpr size_t kMaxClipboard = 64 << 10;
constexpr size_t kMaxText = 200;
constexpr gid_t kUsbGroup = 1018;  // AID_USB
constexpr char kUsbGroupName[] = "aid_usb";
// The sampling period of the lent sensors, in microseconds.
constexpr int32_t kSensorPeriodUs = 200000;

std::string Run(const char* name) {
    return std::string(kBridgeDir) + "/" + name;
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

// A request line: the reply FIFO's name, a space, the rest.
bool SplitRequest(const std::string& line, std::string* reply, std::string* rest) {
    const size_t space = line.find(' ');
    if (space == std::string::npos) return false;
    *reply = line.substr(0, space);
    *rest = line.substr(space + 1);
    return files::IsPlainName(*reply);
}

}  // namespace

Bridge::Bridge(System& system, Asks asks) : system_(system), asks_(std::move(asks)) {}

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
        linux_ = running;
        user_ = user;
    }
    Wake();
}

void Bridge::Recheck() {
    Wake();
}

Result<void> Bridge::UsbGroup(bool lend) {
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
        if (f.size() < 4 || !android::base::ParseUint(f[2], &gid) || gid != kUsbGroup) continue;
        found = true;
        std::vector<std::string> members;
        for (const std::string& m : android::base::Split(f[3], ",")) {
            if (!m.empty() && m != user_) members.push_back(m);
        }
        if (lend) members.push_back(user_);
        const std::string joined = android::base::Join(members, ",");
        if (joined != f[3]) {
            f[3] = joined;
            line = android::base::Join(f, ":");
            changed = true;
        }
    }
    if (!found && lend) {
        lines.push_back(std::string(kUsbGroupName) + ":x:" + std::to_string(kUsbGroup) + ":" + user_);
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
    auto root = files::OpenRoot(kRoot);
    const bool ready = linux_ && root.ok() &&
                       files::MakeDirsIn(root->get(), kBridgeDir, 01775, 0, aid::kLinux).ok();

    // USB devices; at every start of Linux /etc/group is made to agree with the switch.
    const bool usb = linux_ && lent("usb");
    if (usb != usb_lent_ || (linux_ && !usb_checked_)) {
        if (auto r = system_.LendUsb(usb); !r.ok()) LOG(ERROR) << "bridge: " << r.error();
        if (auto r = UsbGroup(usb); !r.ok()) {
            LOG(ERROR) << "bridge: aid_usb in /etc/group: " << r.error();
        }
        if (usb != usb_lent_) LOG(INFO) << "bridge: USB devices " << (usb ? "lent" : "taken back");
        usb_lent_ = usb;
    }
    usb_checked_ = linux_;

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
            ALooper_addFd(looper, fd.get(), ident, ALOOPER_EVENT_INPUT, nullptr, nullptr);
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
            ALooper_addFd(looper, inotify_.get(), kInotifyIdent, ALOOPER_EVENT_INPUT, nullptr,
                          nullptr);
            LOG(INFO) << "bridge: sensors lent";
        }
    } else if (!sensors && sensors_watch_ >= 0) {
        SensorsStop();
        inotify_rm_watch(inotify_.get(), sensors_watch_);
        sensors_watch_ = -1;
        ALooper_removeFd(looper, inotify_.get());
        if (root.ok() && linux_) files::RemoveIn(root->get(), Run("sensors"));
        LOG(INFO) << "bridge: sensors taken back";
    }

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
    ALooper* looper = ALooper_prepare(0);
    ALooper_addFd(looper, wake_.get(), kWakeIdent, ALOOPER_EVENT_INPUT, nullptr, nullptr);
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
            case kInotifyIdent: {
                char buf[4096];
                bool opened = false;
                ssize_t n;
                while ((n = read(inotify_.get(), buf, sizeof(buf))) > 0) {
                    for (char* p = buf; p < buf + n;) {
                        auto* e = reinterpret_cast<inotify_event*>(p);
                        if (e->wd == sensors_watch_ && (e->mask & IN_OPEN)) opened = true;
                        p += sizeof(inotify_event) + e->len;
                    }
                }
                if (opened) SensorsReader();
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
