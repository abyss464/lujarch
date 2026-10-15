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

#include "identity.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <memory>
#include <set>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <openssl/bio.h>
#include <openssl/evp.h>
#include <openssl/mem.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <openssl/x509.h>
#include <tinyxml2.h>

#include <grp.h>
#include <lujarch/aid.h>
#include <lujarch/daemon.h>
#include <lujarch/text.h>
#include <sys/wait.h>

#include "conditions.h"
#include "rest.h"

namespace lujarch::sync::identity {

using android::base::ErrnoError;
using android::base::Error;
using android::base::Result;
using android::base::unique_fd;
using namespace std::chrono_literals;

namespace {

constexpr char kStateProperty[] = "lujarch.sync.state";
// Written into each backup: the device ID it held.
constexpr char kBackupIdFile[] = "device-id";

EngineStop engine_stop;

// Overwrites a string that held key material before it is freed.
struct Cleanser {
    std::string* s;
    ~Cleanser() {
        if (s != nullptr && !s->empty()) OPENSSL_cleanse(s->data(), s->size());
    }
};

// ---- device ID ----

constexpr char kBase32[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

std::string Base32(const uint8_t* data, size_t size) {
    std::string out;
    uint32_t buffer = 0;
    int bits = 0;
    for (size_t i = 0; i < size; ++i) {
        buffer = (buffer << 8) | data[i];
        bits += 8;
        while (bits >= 5) {
            out.push_back(kBase32[(buffer >> (bits - 5)) & 31]);
            bits -= 5;
        }
    }
    if (bits > 0) out.push_back(kBase32[(buffer << (5 - bits)) & 31]);
    return out;
}

char Luhn32(std::string_view s) {
    int factor = 1;
    int sum = 0;
    for (char c : s) {
        const int codepoint = static_cast<int>(strchr(kBase32, c) - kBase32);
        int addend = factor * codepoint;
        factor = factor == 2 ? 1 : 2;
        addend = addend / 32 + addend % 32;
        sum += addend;
    }
    return kBase32[(32 - sum % 32) % 32];
}

// ---- config.xml ----

using tinyxml2::XMLDocument;
using tinyxml2::XMLElement;

XMLElement* Child(XMLElement* parent, const char* name) {
    XMLElement* e = parent->FirstChildElement(name);
    return e != nullptr ? e : parent->InsertNewChildElement(name);
}

std::string RandomApiKey() {
    static constexpr char kChars[] =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    uint8_t random[32];
    RAND_bytes(random, sizeof(random));
    std::string key;
    for (uint8_t b : random) key.push_back(kChars[b % (sizeof(kChars) - 1)]);
    OPENSSL_cleanse(random, sizeof(random));
    return key;
}

// The settings every config.xml of this system carries.
void ApplySystemSettings(XMLElement* root, bool new_api_key) {
    XMLElement* gui = Child(root, "gui");
    gui->SetAttribute("enabled", "true");
    gui->SetAttribute("tls", "false");
    Child(gui, "address")->SetText(rest::kSocket);
    // The engine's uid and group; the daemon connects through the group.
    Child(gui, "unixSocketPermissions")->SetText("660");
    XMLElement* apikey = gui->FirstChildElement("apikey");
    const char* current = apikey != nullptr ? apikey->GetText() : nullptr;
    if (new_api_key || current == nullptr || current[0] == '\0') {
        std::string key = RandomApiKey();
        Child(gui, "apikey")->SetText(key.c_str());
        OPENSSL_cleanse(key.data(), key.size());
    }

    XMLElement* options = Child(root, "options");
    Child(options, "autoUpgradeIntervalH")->SetText("0");
    Child(options, "upgradeToPreReleases")->SetText("false");
    Child(options, "urAccepted")->SetText("-1");
    Child(options, "crashReportingEnabled")->SetText("false");
    Child(options, "startBrowser")->SetText("false");

    // Folders accepted automatically (a device's "auto accept") land under
    // the folders root, readable through the group.
    XMLElement* folder = Child(Child(root, "defaults"), "folder");
    folder->SetAttribute("path", rest::kFoldersDir);
    folder->SetAttribute("ignorePerms", "true");
}

// A directory name for a folder from its label (or ID): the characters
// IsFolderDirName allows, others replaced by '-', unique among used.
std::string DirNameFor(const std::string& label, const std::string& id,
                       std::set<std::string>* used) {
    auto clean = [](const std::string& text) {
        std::string out;
        for (unsigned char c : text) {
            const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                            (c >= '0' && c <= '9') || c == '.' || c == '_' || c == ' ' || c == '-';
            if (ok) {
                out.push_back(static_cast<char>(c));
            } else if (c < 0x80 || c >= 0xC0) {
                // One '-' per replaced character (continuation bytes add none).
                if (out.empty() || out.back() != '-') out.push_back('-');
            }
        }
        while (!out.empty() && (out.front() == '.' || out.front() == '-' || out.front() == ' '))
            out.erase(0, 1);
        while (!out.empty() && (out.back() == '-' || out.back() == ' ' || out.back() == '.'))
            out.pop_back();
        if (out.size() > 64) out.resize(64);
        return out;
    };
    std::string base = clean(label);
    if (base.empty()) base = clean(id);
    if (base.empty()) base = "folder";
    std::string name = base;
    for (int n = 2; used->count(name) != 0; ++n) name = base + "-" + std::to_string(n);
    used->insert(name);
    return name;
}

// ---- installing ----

struct Staged {
    enum class Kind { kImport, kGenerate, kRestore } kind = Kind::kImport;
    std::string backup;  // kRestore
    std::string cert, key, config;
};

std::string BackupStamp() {
    char stamp[32];
    time_t now = time(nullptr);
    tm local;
    localtime_r(&now, &local);
    strftime(stamp, sizeof(stamp), "%Y%m%d-%H%M%S", &local);
    return stamp;
}

// In the child, as the engine's user: no allocation, no logging.
int WriteFile(const char* path, const std::string& data, mode_t mode) {
    unique_fd fd(open(path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, mode));
    if (fd < 0 || fchmod(fd.get(), mode) != 0) return -1;
    if (!android::base::WriteFully(fd.get(), data.data(), data.size()) || fsync(fd.get()) != 0)
        return -1;
    return 0;
}

int CopyFile(const char* from, const char* to, mode_t mode) {
    std::string data;
    if (!android::base::ReadFileToString(from, &data)) return -1;
    int r = WriteFile(to, data, mode);
    OPENSSL_cleanse(data.data(), data.size());
    return r;
}

// Moves the current identity into a new backup and installs staged, in a child that runs as the
// engine's user, so the files get its owner, modes and label; the engine is stopped meanwhile.
Result<void> InstallAsEngine(const Staged& staged, const std::string& previous_id) {
    const std::string home = rest::kHome;
    const std::string backups = kBackupsDir;
    std::string dest = backups + "/" + BackupStamp();
    for (int n = 2; access(dest.c_str(), F_OK) == 0; ++n)
        dest = backups + "/" + BackupStamp() + "-" + std::to_string(n);
    const std::string source = backups + "/" + staged.backup;
    const char* kMoved[] = {"cert.pem",      "key.pem",    "https-cert.pem",
                            "https-key.pem", "config.xml", "index-v2"};
    std::vector<std::pair<std::string, std::string>> moves;
    for (const char* f : kMoved) moves.emplace_back(home + "/" + f, dest + "/" + f);
    const std::string cert = home + "/cert.pem", key = home + "/key.pem",
                      config = home + "/config.xml";
    const std::string id_file = dest + "/" + kBackupIdFile;
    const bool has_identity = access(cert.c_str(), F_OK) == 0 || access(key.c_str(), F_OK) == 0 ||
                              access(config.c_str(), F_OK) == 0;

    pid_t pid = fork();
    if (pid < 0) return ErrnoError() << "fork";
    if (pid == 0) {
        gid_t gid = aid::kSync;
        if (setgroups(1, &gid) != 0 || setresgid(gid, gid, gid) != 0 ||
            setresuid(aid::kSync, aid::kSync, aid::kSync) != 0) {
            _exit(10);
        }
        umask(027);
        if (has_identity) {
            mkdir(backups.c_str(), 0750);
            if (mkdir(dest.c_str(), 0750) != 0) _exit(11);
            for (const auto& [from, to] : moves) {
                if (rename(from.c_str(), to.c_str()) != 0 && errno != ENOENT) _exit(12);
            }
            if (!previous_id.empty() && WriteFile(id_file.c_str(), previous_id + "\n", 0640) != 0)
                _exit(13);
        }
        switch (staged.kind) {
            case Staged::Kind::kImport:
                if (WriteFile(cert.c_str(), staged.cert, 0600) != 0 ||
                    WriteFile(key.c_str(), staged.key, 0600) != 0 ||
                    WriteFile(config.c_str(), staged.config, 0640) != 0) {
                    _exit(14);
                }
                break;
            case Staged::Kind::kGenerate:
                if (WriteFile(config.c_str(), staged.config, 0640) != 0) _exit(14);
                break;
            case Staged::Kind::kRestore:
                if (CopyFile((source + "/cert.pem").c_str(), cert.c_str(), 0600) != 0 ||
                    CopyFile((source + "/key.pem").c_str(), key.c_str(), 0600) != 0 ||
                    CopyFile((source + "/config.xml").c_str(), config.c_str(), 0640) != 0) {
                    _exit(14);
                }
                for (const char* f : {"https-cert.pem", "https-key.pem"}) {
                    CopyFile((source + "/" + f).c_str(), (home + "/" + f).c_str(), 0600);
                }
                rename((source + "/index-v2").c_str(), (home + "/index-v2").c_str());
                break;
        }
        _exit(0);
    }
    int status = 0;
    if (TEMP_FAILURE_RETRY(waitpid(pid, &status, 0)) != pid) return ErrnoError() << "waitpid";
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        return Error() << "installing the identity failed (step "
                       << (WIFEXITED(status) ? WEXITSTATUS(status) : -1)
                       << "); the previous one is in "
                       << (has_identity ? dest : std::string("no backup"));
    }
    if (has_identity) LOG(INFO) << "the previous identity is kept in " << dest;
    return {};
}

// Installs staged while the engine is stopped; returns whether the engine runs again with it.
Result<bool> Install(const Staged& staged, const std::string& previous_id) {
    if (!engine_stop) return Error() << "the supervisor is not running";
    return engine_stop([&]() { return InstallAsEngine(staged, previous_id); });
}

// This device's current ID when Syncthing answers (for the backup's name).
std::string CurrentDeviceId() {
    auto reply = rest::Call("GET", "/rest/system/status", "", 2s);
    if (!reply.ok() || reply->status != 200) return "";
    Json::Value status;
    std::string error;
    if (!text::ParseJson(reply->body, &status, &error) || !status.isObject() ||
        !status["myID"].isString())
        return "";
    const std::string id = status["myID"].asString();
    return rest::IsDeviceId(id) ? id : "";
}

bool FileExists(const std::string& path, bool* nonempty = nullptr) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return false;
    if (nonempty != nullptr) *nonempty = st.st_size > 0;
    return S_ISREG(st.st_mode);
}

bool HasConfig() {
    bool nonempty = false;
    return FileExists(rest::kConfigFile, &nonempty) && nonempty;
}

// config.xml for a new identity without an imported one: this device's
// devices and folders when keep is set and it has a config, else a new one.
Result<std::string> ConfigForNewIdentity(bool keep) {
    if (!keep || !HasConfig()) return TemplateConfig();
    std::string current;
    Cleanser clean{&current};
    if (!android::base::ReadFileToString(rest::kConfigFile, &current)) {
        return ErrnoError() << "cannot read " << rest::kConfigFile;
    }
    auto rewritten = RewriteConfig(current, ConfigSource::kKept);
    if (!rewritten.ok())
        return Error() << "this device's config.xml: " << rewritten.error().message();
    return rewritten->xml;
}

bool GetOptionalBool(const Json::Value& request, const char* name, bool fallback, bool* out,
                     std::string* error) {
    if (!request.isMember(name)) {
        *out = fallback;
        return true;
    }
    if (!request[name].isBool()) {
        *error = std::string("\"") + name + "\" must be true or false";
        return false;
    }
    *out = request[name].asBool();
    return true;
}

Json::Value InstallReply(bool restarted) {
    Json::Value reply(Json::objectValue);
    reply["restarted"] = restarted;
    reply["enabled"] = SettingIsOn("sync", "enabled");
    // Sync is on but waits for a run condition (Wi-Fi, charging).
    reply["waiting"] = !conditions::Waiting().empty();
    return reply;
}

}  // namespace

std::string DeviceIdFromDer(std::string_view der) {
    uint8_t digest[SHA256_DIGEST_LENGTH];
    SHA256(reinterpret_cast<const uint8_t*>(der.data()), der.size(), digest);
    const std::string raw = Base32(digest, sizeof(digest));  // 52 characters
    std::string checked;
    for (size_t i = 0; i < 52; i += 13) {
        const std::string_view part = std::string_view(raw).substr(i, 13);
        checked.append(part);
        checked.push_back(Luhn32(part));
    }
    std::string id;
    for (size_t i = 0; i < checked.size(); i += 7) {
        if (!id.empty()) id.push_back('-');
        id.append(checked.substr(i, 7));
    }
    return id;
}

Result<std::string> CheckPair(std::string_view cert_pem, std::string_view key_pem) {
    if (cert_pem.empty() || cert_pem.size() > kMaxPem)
        return Error() << "the certificate file is empty or too large";
    if (key_pem.empty() || key_pem.size() > kMaxPem)
        return Error() << "the key file is empty or too large";
    bssl::UniquePtr<BIO> cert_bio(BIO_new_mem_buf(cert_pem.data(), cert_pem.size()));
    bssl::UniquePtr<X509> cert(PEM_read_bio_X509(cert_bio.get(), nullptr, nullptr, nullptr));
    if (!cert) return Error() << "the certificate file holds no PEM certificate";
    bssl::UniquePtr<BIO> key_bio(BIO_new_mem_buf(key_pem.data(), key_pem.size()));
    // No password callback: an encrypted key is refused, not prompted for.
    bssl::UniquePtr<EVP_PKEY> key(
            PEM_read_bio_PrivateKey(key_bio.get(), nullptr, nullptr, nullptr));
    if (!key) return Error() << "the key file holds no unencrypted PEM private key";
    if (X509_check_private_key(cert.get(), key.get()) != 1) {
        return Error() << "the key does not belong to the certificate";
    }
    uint8_t* der = nullptr;
    const int der_size = i2d_X509(cert.get(), &der);
    if (der_size <= 0) return Error() << "the certificate cannot be encoded";
    bssl::UniquePtr<uint8_t> der_owner(der);
    return DeviceIdFromDer(
            std::string_view(reinterpret_cast<const char*>(der), static_cast<size_t>(der_size)));
}

Result<Rewritten> RewriteConfig(std::string_view xml, ConfigSource source) {
    if (xml.empty() || xml.size() > kMaxConfig)
        return Error() << "the configuration is empty or larger than 48 KiB";
    XMLDocument doc;
    if (doc.Parse(xml.data(), xml.size()) != tinyxml2::XML_SUCCESS) {
        // The error name only: the message quotes the input.
        return Error() << "the configuration is not valid XML (" << doc.ErrorName() << ")";
    }
    XMLElement* root = doc.RootElement();
    if (root == nullptr || strcmp(root->Name(), "configuration") != 0) {
        return Error() << "not a Syncthing configuration (no <configuration>)";
    }
    Rewritten out;
    ApplySystemSettings(root, source == ConfigSource::kImported);

    if (source == ConfigSource::kImported) {
        // Folders keep a path under the folders root when they have one
        // there (a backup of this system); every other path belongs to
        // another device and moves to a directory named after the label.
        const std::string prefix = std::string(rest::kFoldersDir) + "/";
        std::set<std::string> used;
        std::vector<XMLElement*> relocate;
        for (XMLElement* f = root->FirstChildElement("folder"); f != nullptr;
             f = f->NextSiblingElement("folder")) {
            const char* path = f->Attribute("path");
            const std::string p = path != nullptr ? path : "";
            const std::string name =
                    android::base::StartsWith(p, prefix) ? p.substr(prefix.size()) : "";
            if (rest::IsFolderDirName(name) && used.insert(name).second) {
                const char* id = f->Attribute("id");
                out.folders[id != nullptr ? id : ""] = name;
            } else {
                relocate.push_back(f);
            }
            f->SetAttribute("ignorePerms", "true");
        }
        for (XMLElement* f : relocate) {
            const char* id = f->Attribute("id");
            const char* label = f->Attribute("label");
            const std::string name =
                    DirNameFor(label != nullptr ? label : "", id != nullptr ? id : "", &used);
            f->SetAttribute("path", (prefix + name).c_str());
            out.folders[id != nullptr ? id : ""] = name;
        }
    }
    tinyxml2::XMLPrinter printer;
    doc.Print(&printer);
    out.xml.assign(printer.CStr(), printer.CStrSize() > 0 ? printer.CStrSize() - 1 : 0);
    return out;
}

std::string TemplateConfig() {
    XMLDocument doc;
    XMLElement* root = doc.NewElement("configuration");
    doc.InsertEndChild(root);
    root->SetAttribute("version", kConfigVersion);
    ApplySystemSettings(root, true);
    tinyxml2::XMLPrinter printer;
    doc.Print(&printer);
    return std::string(printer.CStr(), printer.CStrSize() > 0 ? printer.CStrSize() - 1 : 0);
}

bool IsBackupName(std::string_view name) {
    // YYYYMMDD-HHMMSS[-N]
    if (name.size() < 15 || name.size() > 24 || name[8] != '-') return false;
    for (size_t i = 0; i < name.size(); ++i) {
        const char c = name[i];
        if (i == 8 || (i == 15 && c == '-')) continue;
        if (c < '0' || c > '9') return false;
    }
    return name.size() == 15 || (name[15] == '-' && name.size() > 16);
}

// ---- commands ----

Outcome CmdStatus(const Json::Value&, uid_t) {
    Json::Value reply(Json::objectValue);
    Json::Value files(Json::objectValue);
    for (const char* name : {"cert.pem", "key.pem", "config.xml"}) {
        bool nonempty = false;
        files[name] = FileExists(std::string(rest::kHome) + "/" + name, &nonempty) && nonempty;
    }
    reply["files"] = files;
    reply["enabled"] = SettingIsOn("sync", "enabled");
    const std::string state = android::base::GetProperty(kStateProperty, "");
    reply["state"] = state.empty() ? Json::Value() : Json::Value(state);

    // Backups: made as the engine's user (0750), listed through the group.
    Json::Value backups(Json::arrayValue);
    std::unique_ptr<DIR, int (*)(DIR*)> dir(opendir(kBackupsDir), closedir);
    if (dir) {
        std::vector<std::string> names;
        while (dirent* e = readdir(dir.get())) {
            if (IsBackupName(e->d_name)) names.push_back(e->d_name);
        }
        std::sort(names.rbegin(), names.rend());
        for (const std::string& name : names) {
            const std::string path = std::string(kBackupsDir) + "/" + name;
            Json::Value b(Json::objectValue);
            b["name"] = name;
            bool nonempty = false;
            b["complete"] = FileExists(path + "/cert.pem") && FileExists(path + "/key.pem") &&
                            FileExists(path + "/config.xml", &nonempty) && nonempty;
            std::string id;
            if (android::base::ReadFileToString(path + "/" + kBackupIdFile, &id)) {
                id = android::base::Trim(id);
                if (rest::IsDeviceId(id)) b["device"] = id;
            }
            backups.append(b);
        }
    } else if (errno != ENOENT) {
        PLOG(WARNING) << "cannot list " << kBackupsDir;
    }
    reply["backups"] = backups;
    return Outcome::Ok(std::move(reply));
}

Outcome CmdImport(const Json::Value& request, uid_t uid) {
    for (const char* field : {"cert", "key"}) {
        if (!request[field].isString())
            return Outcome::Refused(std::string("\"") + field + "\" must be text (PEM)");
    }
    if (request.isMember("config") && !request["config"].isString()) {
        return Outcome::Refused("\"config\" must be text (config.xml)");
    }
    bool keep = true;
    std::string error;
    if (!GetOptionalBool(request, "keep_config", true, &keep, &error))
        return Outcome::Refused(error);

    Staged staged;
    staged.kind = Staged::Kind::kImport;
    staged.cert = request["cert"].asString();
    staged.key = request["key"].asString();
    Cleanser clean_key{&staged.key};
    auto id = CheckPair(staged.cert, staged.key);
    if (!id.ok()) return Outcome::Refused(id.error().message());

    Json::Value folders(Json::arrayValue);
    if (request.isMember("config")) {
        std::string imported = request["config"].asString();
        Cleanser clean_imported{&imported};
        auto rewritten = RewriteConfig(imported, ConfigSource::kImported);
        if (!rewritten.ok()) return Outcome::Refused(rewritten.error().message());
        staged.config = std::move(rewritten->xml);
        for (const auto& [folder, dir] : rewritten->folders) {
            Json::Value f(Json::objectValue);
            f["id"] = text::SanitizeUtf8(folder);
            f["dir"] = dir;
            folders.append(f);
        }
    } else {
        auto config = ConfigForNewIdentity(keep);
        if (!config.ok()) return Outcome::Failed(config.error().message());
        staged.config = std::move(*config);
    }
    Cleanser clean_config{&staged.config};

    auto restarted = Install(staged, CurrentDeviceId());
    if (!restarted.ok()) return Outcome::Failed(restarted.error().message());
    LOG(INFO) << "uid " << uid << ": installed an imported identity (device " << id->substr(0, 7)
              << ", "
              << (request.isMember("config") ? "imported config"
                  : keep                     ? "config kept"
                                             : "new config")
              << ")";
    Json::Value reply = InstallReply(*restarted);
    reply["device"] = *id;
    reply["folders"] = folders;
    return Outcome::Ok(std::move(reply));
}

Outcome CmdGenerate(const Json::Value& request, uid_t uid) {
    bool keep = true;
    std::string error;
    if (!GetOptionalBool(request, "keep_config", true, &keep, &error))
        return Outcome::Refused(error);
    Staged staged;
    staged.kind = Staged::Kind::kGenerate;
    auto config = ConfigForNewIdentity(keep);
    if (!config.ok()) return Outcome::Failed(config.error().message());
    staged.config = std::move(*config);
    Cleanser clean_config{&staged.config};
    auto restarted = Install(staged, CurrentDeviceId());
    if (!restarted.ok()) return Outcome::Failed(restarted.error().message());
    LOG(INFO) << "uid " << uid << ": installed a new identity ("
              << (keep ? "config kept" : "new config") << ")";
    return Outcome::Ok(InstallReply(*restarted));
}

Outcome CmdRestore(const Json::Value& request, uid_t uid) {
    if (!request["backup"].isString() || !IsBackupName(request["backup"].asString())) {
        return Outcome::Refused("\"backup\" must name a backup (YYYYMMDD-HHMMSS)");
    }
    const std::string name = request["backup"].asString();
    const std::string path = std::string(kBackupsDir) + "/" + name;
    if (!FileExists(path + "/config.xml") || !FileExists(path + "/cert.pem") ||
        !FileExists(path + "/key.pem")) {
        return Outcome::Refused("backup " + name + " is missing or incomplete");
    }
    Staged staged;
    staged.kind = Staged::Kind::kRestore;
    staged.backup = name;
    auto restarted = Install(staged, CurrentDeviceId());
    if (!restarted.ok()) return Outcome::Failed(restarted.error().message());
    LOG(INFO) << "uid " << uid << ": restored identity backup " << name;
    return Outcome::Ok(InstallReply(*restarted));
}

void SetEngineStop(EngineStop stop) {
    engine_stop = std::move(stop);
}

}  // namespace lujarch::sync::identity
