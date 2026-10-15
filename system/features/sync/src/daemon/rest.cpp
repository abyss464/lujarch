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

#include "rest.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <map>
#include <memory>
#include <utility>
#include <vector>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>

#include <lujarch/text.h>
#include <atomic>

namespace lujarch::sync::rest {

namespace {
std::atomic<bool> engine_running{false};
}  // namespace

using android::base::ErrnoError;
using android::base::Error;
using android::base::Result;
using namespace std::chrono_literals;

namespace {

constexpr auto kConnectTimeout = 2s;
constexpr auto kConfigTimeout = 18s;
constexpr size_t kMaxReply = 16u << 20;
constexpr size_t kMaxTarget = 2048;
constexpr size_t kMaxText = 256;
constexpr int64_t kMaxIntervalS = 365LL * 24 * 3600;
constexpr char kWhat[] = "Syncthing";
// Ignore patterns: lines of .stignore.
constexpr size_t kMaxIgnoreLines = 4096;
constexpr size_t kMaxIgnoreLine = 1024;
// Folder encryption passwords (never logged or returned).
constexpr size_t kMaxPassword = 1024;
constexpr size_t kMaxVersionsPath = 1024;

// ---- allowlist ----

enum class BodyRule {
    kNone,           // no body
    kPaused,         // {"paused": true|false}
    kDeviceAdd,      // a new device
    kDevicePatch,    // changes to a device
    kFolderAdd,      // a new folder under kFoldersDir
    kFolderPatch,    // changes to a folder (not its ID or path)
    kOptionsPatch,   // discovery, relays, NAT, rate limits
    kDefaultFolder,  // {"path": kFoldersDir, "ignorePerms": true}
    kIgnores,        // {"ignore": [pattern lines]}
    kOverride,       // none, or {"deleteMissing": true|false} (Prepare)
};

struct Route {
    std::string_view method;
    std::string_view path;  // "*" matches one segment
    BodyRule body;
    std::string_view query;     // allowed query keys, space-separated
    std::string_view required;  // keys that must be present
};

// What the Sync pages use of the REST API, and nothing else. Refused,
// among everything else: the whole config (/rest/config, which carries the
// API key and GUI password; sync.ignored edits one list of it inside the
// daemon), /rest/config/gui, config replacement (PUT), /rest/system/{restart,
// shutdown,upgrade,reset,loglevels}, /rest/db/prio, /rest/debug,
// /rest/events, /rest/folder/versions.
constexpr Route kRoutes[] = {
        {"GET", "/rest/system/status", BodyRule::kNone, "", ""},
        {"GET", "/rest/system/version", BodyRule::kNone, "", ""},
        {"GET", "/rest/system/connections", BodyRule::kNone, "", ""},
        {"GET", "/rest/system/error", BodyRule::kNone, "", ""},
        {"GET", "/rest/stats/device", BodyRule::kNone, "", ""},
        {"GET", "/rest/stats/folder", BodyRule::kNone, "", ""},
        {"GET", "/rest/config/devices", BodyRule::kNone, "", ""},
        {"GET", "/rest/config/devices/*", BodyRule::kNone, "", ""},
        {"GET", "/rest/config/folders", BodyRule::kNone, "", ""},
        {"GET", "/rest/config/folders/*", BodyRule::kNone, "", ""},
        {"GET", "/rest/config/options", BodyRule::kNone, "", ""},
        {"GET", "/rest/db/status", BodyRule::kNone, "folder", "folder"},
        {"GET", "/rest/db/completion", BodyRule::kNone, "folder device", ""},
        {"GET", "/rest/db/browse", BodyRule::kNone, "folder prefix levels", "folder"},
        {"GET", "/rest/folder/errors", BodyRule::kNone, "folder page perpage", "folder"},
        {"GET", "/rest/cluster/pending/devices", BodyRule::kNone, "", ""},
        {"GET", "/rest/cluster/pending/folders", BodyRule::kNone, "device", ""},
        {"GET", "/rest/svc/deviceid", BodyRule::kNone, "id", "id"},
        // Changes.
        {"PATCH", "/rest/config/devices/*", BodyRule::kDevicePatch, "", ""},
        {"PATCH", "/rest/config/folders/*", BodyRule::kFolderPatch, "", ""},
        {"PATCH", "/rest/config/options", BodyRule::kOptionsPatch, "", ""},
        {"PATCH", "/rest/config/defaults/folder", BodyRule::kDefaultFolder, "", ""},
        {"POST", "/rest/config/devices", BodyRule::kDeviceAdd, "", ""},
        {"POST", "/rest/config/folders", BodyRule::kFolderAdd, "", ""},
        {"DELETE", "/rest/config/devices/*", BodyRule::kNone, "", ""},
        {"DELETE", "/rest/config/folders/*", BodyRule::kNone, "", ""},
        {"DELETE", "/rest/cluster/pending/devices", BodyRule::kNone, "device", "device"},
        {"DELETE", "/rest/cluster/pending/folders", BodyRule::kNone, "folder device", "folder"},
        {"POST", "/rest/db/scan", BodyRule::kNone, "folder", "folder"},
        // Ignore patterns (.stignore in the folder; #include reads only
        // files inside the folder). Receive-only: revert local changes;
        // send-only: override the other devices' changes.
        {"GET", "/rest/db/ignores", BodyRule::kNone, "folder", "folder"},
        {"POST", "/rest/db/ignores", BodyRule::kIgnores, "folder", "folder"},
        {"POST", "/rest/db/revert", BodyRule::kNone, "folder", "folder"},
        {"POST", "/rest/db/override", BodyRule::kOverride, "folder", "folder"},
        // Without a device: every device (Syncthing's "pause all").
        {"POST", "/rest/system/pause", BodyRule::kNone, "", ""},
        {"POST", "/rest/system/resume", BodyRule::kNone, "", ""},
};

bool InList(std::string_view list, std::string_view word) {
    for (const std::string& item : android::base::Split(std::string(list), " ")) {
        if (!item.empty() && item == word) return true;
    }
    return false;
}

// key=value pairs separated by '&': known keys, each at most once, a
// non-empty value without a further '='.
std::string CheckQuery(std::string_view query, const Route& route) {
    std::vector<std::string> seen;
    if (!query.empty()) {
        for (const std::string& param : android::base::Split(std::string(query), "&")) {
            const size_t eq = param.find('=');
            if (eq == std::string::npos || eq == 0 || eq + 1 == param.size() ||
                param.find('=', eq + 1) != std::string::npos) {
                return "malformed query parameter";
            }
            const std::string key = param.substr(0, eq);
            if (!InList(route.query, key)) return "query parameter not allowed: " + key;
            for (const std::string& s : seen) {
                if (s == key) return "repeated query parameter: " + key;
            }
            seen.push_back(key);
        }
    }
    for (const std::string& key : android::base::Split(std::string(route.required), " ")) {
        if (key.empty()) continue;
        bool present = false;
        for (const std::string& s : seen) present = present || s == key;
        if (!present) return "missing query parameter: " + key;
    }
    return "";
}

// ---- body checks ----

// Printable text (UTF-8 allowed, no control characters), at most max bytes.
bool IsText(const Json::Value& v, size_t max = kMaxText, bool allow_empty = true) {
    if (!v.isString()) return false;
    const std::string s = v.asString();
    if (s.size() > max || (!allow_empty && s.empty())) return false;
    return std::none_of(s.begin(), s.end(), [](char c) {
        const auto u = static_cast<unsigned char>(c);
        return u < 0x20 || u == 0x7f;
    });
}

bool IsIntIn(const Json::Value& v, int64_t min, int64_t max) {
    if (!v.isIntegral()) return false;
    const int64_t n = v.asInt64();
    return n >= min && n <= max;
}

bool IsNumberIn(const Json::Value& v, double min, double max) {
    if (!v.isNumeric()) return false;
    const double d = v.asDouble();
    return std::isfinite(d) && d >= min && d <= max;
}

bool IsOneOf(const Json::Value& v, std::initializer_list<std::string_view> values) {
    if (!v.isString()) return false;
    const std::string s = v.asString();
    return std::find(values.begin(), values.end(), s) != values.end();
}

// ---- addresses (URLs Syncthing dials, listens on or asks) ----

bool IsAsciiIn(std::string_view s, std::string_view extra) {
    return std::all_of(s.begin(), s.end(), [extra](char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
               extra.find(c) != std::string_view::npos;
    });
}

enum class Port { kOptional, kRequired, kRequiredOrZero };

// host[:port] of a URL: a host name or IPv4 address ([A-Za-z0-9._-], not
// starting with '.' or '-'), or an IPv6 address in brackets (with an
// optional %zone); empty only where Syncthing reads it as every address
// (a listen address). No user info: the text holds no '@'.
bool IsHostPort(std::string_view text, bool allow_empty_host, Port port) {
    std::string_view host = text;
    std::string_view port_text;
    bool has_port = false;
    if (!text.empty() && text.front() == '[') {
        const size_t close = text.find(']');
        if (close == std::string_view::npos) return false;
        host = text.substr(0, close + 1);
        const std::string_view rest = text.substr(close + 1);
        if (!rest.empty()) {
            if (rest.front() != ':') return false;
            has_port = true;
            port_text = rest.substr(1);
        }
        const std::string_view inner = host.substr(1, host.size() - 2);
        const size_t zone = inner.find('%');
        const std::string_view address = inner.substr(0, zone);
        if (address.empty() || address.find(':') == std::string_view::npos ||
            !std::all_of(address.begin(), address.end(), [](char c) {
                return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') ||
                       c == ':' || c == '.';
            })) {
            return false;
        }
        if (zone != std::string_view::npos &&
            (zone + 1 == inner.size() || !IsAsciiIn(inner.substr(zone + 1), "_-"))) {
            return false;
        }
    } else {
        const size_t colon = text.rfind(':');
        if (colon != std::string_view::npos) {
            host = text.substr(0, colon);
            has_port = true;
            port_text = text.substr(colon + 1);
        }
        if (host.empty() ? !allow_empty_host
                         : (host.size() > 253 || !IsAsciiIn(host, "._-") || host.front() == '.' ||
                            host.front() == '-')) {
            return false;
        }
    }
    if (!has_port) return port == Port::kOptional;
    if (port_text.empty() || port_text.size() > 5 ||
        !std::all_of(port_text.begin(), port_text.end(),
                     [](char c) { return c >= '0' && c <= '9'; })) {
        return false;
    }
    const int n = std::stoi(std::string(port_text));
    return n <= 65535 && (n > 0 || port == Port::kRequiredOrZero);
}

// A query of key[=value] pairs (a key alone is a flag: "?insecure"): keys
// among known (space-separated), each at most once; an "id" value is a
// device ID, other values URL text. *keys: the keys present.
bool IsQuery(std::string_view query, std::string_view known,
             std::vector<std::string>* keys = nullptr) {
    if (query.empty()) return true;
    std::vector<std::string> seen;
    for (const std::string& param : android::base::Split(std::string(query), "&")) {
        const size_t eq = param.find('=');
        const std::string key = param.substr(0, eq);
        const std::string value = eq == std::string::npos ? "" : param.substr(eq + 1);
        if (key.empty() || !InList(known, key) ||
            std::find(seen.begin(), seen.end(), key) != seen.end()) {
            return false;
        }
        if (key == "id" ? !IsDeviceId(value)
                        : (value.size() > kMaxText || !IsAsciiIn(value, "._~%:+-")))
            return false;
        seen.push_back(key);
    }
    if (keys != nullptr) *keys = seen;
    return true;
}

// scheme://host[:port][/path][?query] split; false without "://".
struct Url {
    std::string scheme, authority, path, query;
    bool has_query = false;
};

bool SplitUrl(std::string_view text, Url* url) {
    const size_t sep = text.find("://");
    if (sep == std::string_view::npos || sep == 0) return false;
    url->scheme = std::string(text.substr(0, sep));
    std::string_view rest = text.substr(sep + 3);
    const size_t q = rest.find('?');
    if (q != std::string_view::npos) {
        url->has_query = true;
        url->query = std::string(rest.substr(q + 1));
        rest = rest.substr(0, q);
    }
    const size_t slash = rest.find('/');
    url->authority = std::string(rest.substr(0, slash));
    url->path = slash == std::string_view::npos ? "" : std::string(rest.substr(slash));
    return true;
}

bool IsUrlPath(std::string_view path) {
    return path.empty() ||
           (path.front() == '/' && path.size() <= kMaxText && IsAsciiIn(path, "._~%/-") &&
            path.find("/../") == std::string_view::npos && !android::base::EndsWith(path, "/.."));
}

bool IsDirectScheme(std::string_view scheme) {
    for (const char* s : {"tcp", "tcp4", "tcp6", "quic", "quic4", "quic6"}) {
        if (scheme == s) return true;
    }
    return false;
}

// relay://host:port[/][?id=<relay's device ID>&token=...]: a relay server
// (strelaysrv); the other members are what the public pool lists.
bool IsRelayUrl(const Url& url) {
    return url.scheme == "relay" && IsHostPort(url.authority, false, Port::kRequired) &&
           (url.path.empty() || url.path == "/") &&
           IsQuery(url.query, "id token pingInterval networkTimeout statusAddr providedBy");
}

// Text of an address list entry: printable ASCII without spaces.
bool IsAddressText(const Json::Value& v) {
    if (!IsText(v, kMaxText, false)) return false;
    const std::string s = v.asString();
    return std::all_of(s.begin(), s.end(), [](char c) { return c > ' ' && c < 0x7f; });
}

std::string CheckList(const Json::Value& v, const char* name, bool (*check)(std::string_view),
                      const char* what, bool allow_empty) {
    if (!v.isArray() || v.size() > 16 || (!allow_empty && v.empty())) {
        return std::string("\"") + name + "\" must be a list of " +
               (allow_empty ? "at most" : "1 to") + " 16 entries";
    }
    for (const Json::Value& a : v) {
        if (!IsAddressText(a) || !check(a.asString())) {
            return std::string("\"") + name + "\" holds an entry that is not " + what;
        }
    }
    return "";
}

std::string CheckAddresses(const Json::Value& v) {
    return CheckList(v, "addresses", IsDeviceAddress,
                     "dynamic, tcp://, quic:// or relay:// with host and port", true);
}

// A folder's devices: [{"deviceID": ID, "encryptionPassword": P}], the
// password optional (absent: the one Syncthing holds is kept, see Prepare;
// "": shared unencrypted). A device with a password gets the folder
// encrypted (an untrusted device). In a change (from_allowed), an entry may
// instead name a device already sharing the folder in
// "encryptionPasswordFrom": it gets the folder as that one does (the
// replacement of a device; KeepPasswords), and no password reaches the app.
std::string CheckFolderDevices(const Json::Value& v, bool from_allowed) {
    if (!v.isArray() || v.size() > 64) return "\"devices\" must be a list of at most 64 devices";
    for (const Json::Value& d : v) {
        if (!d.isObject() || !d.isMember("deviceID") || !d["deviceID"].isString() ||
            !IsDeviceId(d["deviceID"].asString())) {
            return "\"devices\" entries must be {\"deviceID\": <device ID>}";
        }
        if (d.isMember("encryptionPassword") && d.isMember("encryptionPasswordFrom")) {
            return "a \"devices\" entry sets encryptionPassword or encryptionPasswordFrom, not "
                   "both";
        }
        for (const std::string& key : d.getMemberNames()) {
            if (key == "deviceID") continue;
            if (key == "encryptionPasswordFrom" && from_allowed) {
                if (!d[key].isString() || !IsDeviceId(d[key].asString())) {
                    return "\"encryptionPasswordFrom\" must be a device ID";
                }
                continue;
            }
            if (key != "encryptionPassword") {
                return from_allowed
                               ? "\"devices\" entries hold deviceID and encryptionPassword or "
                                 "encryptionPasswordFrom only"
                               : "\"devices\" entries hold deviceID and encryptionPassword only";
            }
            if (!IsText(d[key], kMaxPassword))
                return "an encryption password must be text of at most 1024 bytes";
        }
    }
    return "";
}

// No external versioning: it runs a command given in the config.
std::string CheckVersioning(const Json::Value& v) {
    if (!v.isObject()) return "\"versioning\" must be an object";
    for (const std::string& key : v.getMemberNames()) {
        const Json::Value& value = v[key];
        if (key == "type") {
            if (!IsOneOf(value, {"", "trashcan", "simple", "staggered"})) {
                return "versioning type must be none, trashcan, simple or staggered";
            }
        } else if (key == "params") {
            if (!value.isObject() || value.size() > 4) return "versioning params must be an object";
            for (const std::string& p : value.getMemberNames()) {
                if (p != "cleanoutDays" && p != "keep" && p != "maxAge" && p != "cleanInterval") {
                    return "versioning param not allowed: " + text::SanitizeUtf8(p.substr(0, 64));
                }
                const Json::Value& pv = value[p];
                if (!pv.isString() || pv.asString().empty() || pv.asString().size() > 12 ||
                    pv.asString().find_first_not_of("0123456789") != std::string::npos) {
                    return "versioning params must be decimal numbers as strings";
                }
            }
        } else if (key == "cleanupIntervalS") {
            if (!IsIntIn(value, 0, kMaxIntervalS)) return "cleanupIntervalS out of range";
        } else if (key == "fsPath") {
            if (!value.isString() || !IsVersionsPath(value.asString())) {
                return std::string("the versions path must be relative to the folder or under ") +
                       kFoldersDir;
            }
        } else if (key == "fsType") {
            if (!IsOneOf(value, {"basic"})) return "the versions path's file system must be basic";
        } else {
            return "versioning member not allowed: " + text::SanitizeUtf8(key.substr(0, 64));
        }
    }
    return "";
}

std::string CheckIgnoredFolders(const Json::Value& v) {
    if (!v.isArray() || v.size() > 256)
        return "\"ignoredFolders\" must be a list of at most 256 folders";
    for (const Json::Value& f : v) {
        if (!f.isObject()) return "\"ignoredFolders\" entries must be objects";
        for (const std::string& key : f.getMemberNames()) {
            if ((key != "id" && key != "label" && key != "time") || !IsText(f[key])) {
                return "\"ignoredFolders\" entries hold id, label and time only";
            }
        }
        if (!f.isMember("id") || f["id"].asString().empty())
            return "\"ignoredFolders\" entries need an id";
    }
    return "";
}

// Members of a device the pages set.
std::string CheckDeviceMember(const std::string& key, const Json::Value& value) {
    if (key == "paused" || key == "introducer" || key == "autoAcceptFolders" ||
        key == "untrusted") {
        return value.isBool() ? "" : "\"" + key + "\" must be true or false";
    }
    if (key == "name") return IsText(value) ? "" : "\"name\" must be text of at most 256 bytes";
    if (key == "addresses") return CheckAddresses(value);
    if (key == "compression") {
        return IsOneOf(value, {"metadata", "always", "never"})
                       ? ""
                       : "\"compression\" must be metadata, always or never";
    }
    if (key == "ignoredFolders") return CheckIgnoredFolders(value);
    return "device member not allowed: " + text::SanitizeUtf8(key.substr(0, 64));
}

// Members of a folder the pages set; not its ID or path. add: a new folder.
std::string CheckFolderMember(const std::string& key, const Json::Value& value, bool add) {
    if (key == "paused" || key == "fsWatcherEnabled") {
        return value.isBool() ? "" : "\"" + key + "\" must be true or false";
    }
    if (key == "label") return IsText(value) ? "" : "\"label\" must be text of at most 256 bytes";
    if (key == "type") {
        return IsOneOf(value, {"sendreceive", "sendonly", "receiveonly", "receiveencrypted"})
                       ? ""
                       : "\"type\" must be sendreceive, sendonly, receiveonly or receiveencrypted";
    }
    if (key == "devices") return CheckFolderDevices(value, !add);
    if (key == "rescanIntervalS") {
        return IsIntIn(value, 0, kMaxIntervalS) ? "" : "\"rescanIntervalS\" out of range";
    }
    if (key == "fsWatcherDelayS") {
        return IsNumberIn(value, 1, 3600) ? "" : "\"fsWatcherDelayS\" out of range (1 to 3600)";
    }
    if (key == "versioning") return CheckVersioning(value);
    return "folder member not allowed: " + text::SanitizeUtf8(key.substr(0, 64));
}

std::string CheckBody(BodyRule rule, const Json::Value& body) {
    switch (rule) {
        case BodyRule::kNone:
            return body.isNull() ? "" : "this request takes no body";
        case BodyRule::kPaused:
            if (!body.isObject() || body.size() != 1 || !body.isMember("paused") ||
                !body["paused"].isBool()) {
                return "body must be {\"paused\": true|false}";
            }
            return "";
        case BodyRule::kDevicePatch:
        case BodyRule::kDeviceAdd: {
            if (!body.isObject() || body.empty()) return "body must be a non-empty object";
            const bool add = rule == BodyRule::kDeviceAdd;
            if (add && (!body.isMember("deviceID") || !body["deviceID"].isString() ||
                        !IsDeviceId(body["deviceID"].asString()))) {
                return "a new device needs a valid \"deviceID\"";
            }
            for (const std::string& key : body.getMemberNames()) {
                if (add && key == "deviceID") continue;
                if (add && key == "ignoredFolders") return "a new device has no ignored folders";
                if (std::string why = CheckDeviceMember(key, body[key]); !why.empty()) return why;
            }
            return "";
        }
        case BodyRule::kFolderPatch:
        case BodyRule::kFolderAdd: {
            if (!body.isObject() || body.empty()) return "body must be a non-empty object";
            const bool add = rule == BodyRule::kFolderAdd;
            for (const std::string& key : body.getMemberNames()) {
                const Json::Value& value = body[key];
                if (add && key == "id") {
                    if (!IsText(value, kMaxText, false))
                        return "\"id\" must be text of 1 to 256 bytes";
                    continue;
                }
                if (add && key == "path") {
                    // One directory directly under the folders root: Syncthing's
                    // domain may write nowhere else, the daemon reads the folders
                    // there, and Lujarch Vault opens them by that name.
                    const std::string prefix = std::string(kFoldersDir) + "/";
                    if (!value.isString() || !android::base::StartsWith(value.asString(), prefix) ||
                        !IsFolderDirName(value.asString().substr(prefix.size()))) {
                        return std::string("\"path\" must be ") + kFoldersDir + "/<name>";
                    }
                    continue;
                }
                if (add && key == "ignorePerms") {
                    // Files 0666 & ~umask: the daemon reads them through the group.
                    if (!value.isBool() || !value.asBool()) return "\"ignorePerms\" must be true";
                    continue;
                }
                if (!add && key == "type" && value.isString() &&
                    value.asString() == "receiveencrypted") {
                    // Syncthing sets it up from an encrypted offer only.
                    return "a folder is receive-encrypted only from when it is added";
                }
                if (std::string why = CheckFolderMember(key, value, add); !why.empty()) return why;
            }
            if (add &&
                (!body.isMember("id") || !body.isMember("path") || !body.isMember("ignorePerms"))) {
                return "a new folder needs \"id\", \"path\" and \"ignorePerms\": true";
            }
            return "";
        }
        case BodyRule::kOptionsPatch: {
            if (!body.isObject() || body.empty()) return "body must be a non-empty object";
            for (const std::string& key : body.getMemberNames()) {
                const Json::Value& value = body[key];
                if (key == "globalAnnounceEnabled" || key == "localAnnounceEnabled" ||
                    key == "relaysEnabled" || key == "natEnabled") {
                    if (!value.isBool()) return "\"" + key + "\" must be true or false";
                } else if (key == "maxSendKbps" || key == "maxRecvKbps") {
                    if (!IsIntIn(value, 0, 10000000)) return "\"" + key + "\" out of range";
                } else if (key == "listenAddresses") {
                    // No entries: this device only dials.
                    std::string why = CheckList(
                            value, "listenAddresses", IsListenAddress,
                            "default, tcp:// or quic:// with a port, relay://host:port or "
                            "dynamic+https://host/path",
                            true);
                    if (!why.empty()) return why;
                } else if (key == "globalAnnounceServers") {
                    std::string why = CheckList(value, "globalAnnounceServers", IsDiscoveryServer,
                                                "default, default-v4, default-v6 or "
                                                "https://host[:port][/path][?id=...]",
                                                true);
                    if (!why.empty()) return why;
                } else if (key == "reconnectionIntervalS") {
                    // Syncthing raises anything below 5 s to 5 s.
                    if (!IsIntIn(value, 5, 86400))
                        return "\"reconnectionIntervalS\" out of range (5 to 86400)";
                } else {
                    return "option not allowed: " + text::SanitizeUtf8(key.substr(0, 64));
                }
            }
            return "";
        }
        case BodyRule::kIgnores: {
            if (!body.isObject() || body.size() != 1 || !body.isMember("ignore") ||
                !body["ignore"].isArray()) {
                return "body must be {\"ignore\": [lines]}";
            }
            const Json::Value& lines = body["ignore"];
            if (lines.size() > kMaxIgnoreLines) return "at most 4096 ignore lines";
            for (const Json::Value& line : lines) {
                if (!IsText(line, kMaxIgnoreLine))
                    return "ignore lines must be text of at most 1024 bytes";
            }
            return "";
        }
        case BodyRule::kOverride:
            // The confirmation that items missing here are deleted on every
            // device; Prepare consumes it and sends no body.
            if (body.isNull()) return "";
            if (!body.isObject() || body.size() != 1 || !body.isMember("deleteMissing") ||
                !body["deleteMissing"].isBool()) {
                return "body must be none or {\"deleteMissing\": true|false}";
            }
            return "";
        case BodyRule::kDefaultFolder:
            if (!body.isObject() || body.size() != 2 || !body["path"].isString() ||
                body["path"].asString() != kFoldersDir || !body["ignorePerms"].isBool() ||
                !body["ignorePerms"].asBool()) {
                return std::string("body must be {\"path\": \"") + kFoldersDir +
                       "\", \"ignorePerms\": true}";
            }
            return "";
    }
    return "unknown body rule";
}

std::string DecodeEntities(std::string_view text, bool* ok) {
    std::string out;
    *ok = true;
    for (size_t i = 0; i < text.size(); ++i) {
        if (text[i] != '&') {
            out.push_back(text[i]);
            continue;
        }
        const size_t semi = text.find(';', i);
        if (semi == std::string_view::npos) {
            *ok = false;
            return "";
        }
        const std::string_view entity = text.substr(i + 1, semi - i - 1);
        if (entity == "amp") {
            out.push_back('&');
        } else if (entity == "lt") {
            out.push_back('<');
        } else if (entity == "gt") {
            out.push_back('>');
        } else if (entity == "quot") {
            out.push_back('"');
        } else if (entity == "apos") {
            out.push_back('\'');
        } else {
            *ok = false;
            return "";
        }
        i = semi;
    }
    return out;
}

Result<std::string> ReadApiKey() {
    std::string xml;
    // config.xml is the engine's, 0640; the daemon reads it through the
    // group (lujarch_sync.rc).
    if (!android::base::ReadFileToString(kConfigFile, &xml)) {
        return ErrnoError() << "cannot read " << kConfigFile;
    }
    return ExtractApiKey(xml);
}

bool IsSecretMember(const std::string& name) {
    for (const char* secret : {"encryptionPassword", "apiKey", "apikey", "password"}) {
        if (strcasecmp(name.c_str(), secret) == 0) return true;
    }
    return false;
}

std::string Rfc3339Now() {
    const time_t now = time(nullptr);
    tm utc{};
    gmtime_r(&now, &utc);
    char buf[32];
    strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%SZ", &utc);
    return buf;
}

}  // namespace

std::string CheckApi(std::string_view method, std::string_view target, const Json::Value& body) {
    if (method != "GET" && method != "PATCH" && method != "POST" && method != "DELETE") {
        return "method not allowed (GET, PATCH, POST, DELETE)";
    }
    if (std::string why = http::CheckTarget(target, kMaxTarget); !why.empty()) return why;
    const size_t query = target.find('?');
    std::vector<std::string_view> segments;
    if (!http::SplitPath(target.substr(0, query), &segments)) return "path not allowed";

    const Route* route = nullptr;
    bool known_path = false;
    for (const Route& r : kRoutes) {
        if (!http::Matches(r.path, segments)) continue;
        known_path = true;
        if (r.method == method) {
            route = &r;
            break;
        }
    }
    if (route == nullptr)
        return known_path ? "method not allowed for this path" : "path not allowed";
    const std::string_view query_text =
            query == std::string_view::npos ? std::string_view() : target.substr(query + 1);
    if (query != std::string_view::npos && route->query.empty())
        return "no query allowed for this path";
    if (std::string why = CheckQuery(query_text, *route); !why.empty()) return why;
    return CheckBody(route->body, body);
}

bool IsFolderDirName(std::string_view name) {
    if (name.empty() || name.size() > 255 || name[0] == '.') return false;
    return std::all_of(name.begin(), name.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
               c == '.' || c == '_' || c == ' ' || c == '-';
    });
}

bool IsDeviceId(std::string_view id) {
    size_t chars = 0;
    for (char c : id) {
        if (c == '-') continue;
        if (!((c >= 'A' && c <= 'Z') || (c >= '2' && c <= '7'))) return false;
        ++chars;
    }
    return (chars == 52 || chars == 56) && id.size() <= 63 && id.front() != '-' && id.back() != '-';
}

bool IsDeviceAddress(std::string_view address) {
    if (address == "dynamic") return true;
    Url url;
    if (!SplitUrl(address, &url)) return false;
    if (url.scheme == "relay") return IsRelayUrl(url);
    // Without a port Syncthing dials 22000.
    return IsDirectScheme(url.scheme) && IsHostPort(url.authority, false, Port::kOptional) &&
           (url.path.empty() || url.path == "/") && !url.has_query;
}

bool IsListenAddress(std::string_view address) {
    if (address == "default") return true;
    Url url;
    if (!SplitUrl(address, &url)) return false;
    if (url.scheme == "relay") return IsRelayUrl(url);
    if (url.scheme == "dynamic+https" || url.scheme == "dynamic+http") {
        // A relay pool: the relays it lists are joined as relay:// URLs.
        return IsHostPort(url.authority, false, Port::kOptional) && IsUrlPath(url.path) &&
               !url.has_query;
    }
    // An empty host or 0.0.0.0 listens on every address; port 0 on a free one.
    return IsDirectScheme(url.scheme) && IsHostPort(url.authority, true, Port::kRequiredOrZero) &&
           (url.path.empty() || url.path == "/") && !url.has_query;
}

bool IsDiscoveryServer(std::string_view server) {
    if (server == "default" || server == "default-v4" || server == "default-v6") return true;
    Url url;
    if (!SplitUrl(server, &url) || (url.scheme != "https" && url.scheme != "http")) return false;
    std::vector<std::string> keys;
    if (!IsHostPort(url.authority, false, Port::kOptional) || !IsUrlPath(url.path) ||
        !IsQuery(url.query, "id insecure noannounce nolookup", &keys)) {
        return false;
    }
    auto has = [&keys](const char* key) {
        return std::find(keys.begin(), keys.end(), key) != keys.end();
    };
    // As Syncthing's discover.parseOptions: plain HTTP only for lookups, and
    // only when the server is known by its ID or explicitly unchecked.
    return url.scheme == "https" || ((has("id") || has("insecure")) && has("noannounce"));
}

std::string KeepPasswords(const Json::Value& current, Json::Value* devices) {
    std::map<std::string, std::string> passwords;
    std::vector<std::string> sharing;
    if (current.isArray()) {
        for (const Json::Value& d : current) {
            if (!d.isObject() || !d["deviceID"].isString()) continue;
            sharing.push_back(d["deviceID"].asString());
            if (d["encryptionPassword"].isString()) {
                passwords[d["deviceID"].asString()] = d["encryptionPassword"].asString();
            }
        }
    }
    if (!devices->isArray()) return "";
    for (Json::Value& d : *devices) {
        if (!d.isObject()) continue;
        if (d.isMember("encryptionPasswordFrom")) {
            const std::string from = std::as_const(d)["encryptionPasswordFrom"].asString();
            d.removeMember("encryptionPasswordFrom");
            if (std::find(sharing.begin(), sharing.end(), from) == sharing.end()) {
                return "device " + text::SanitizeUtf8(from.substr(0, 7)) +
                       " does not share this folder, so there is no encryption to copy from it";
            }
            // As the source device has it: encrypted with its password, or not.
            const auto source = passwords.find(from);
            d["encryptionPassword"] = source != passwords.end() ? source->second : "";
            continue;
        }
        if (d.isMember("encryptionPassword")) continue;
        const auto kept = passwords.find(std::as_const(d)["deviceID"].asString());
        if (kept != passwords.end() && !kept->second.empty())
            d["encryptionPassword"] = kept->second;
    }
    return "";
}

Result<std::string> ExtractApiKey(std::string_view xml) {
    size_t gui = std::string_view::npos;
    for (size_t pos = xml.find("<gui"); pos != std::string_view::npos;
         pos = xml.find("<gui", pos + 1)) {
        const char next = pos + 4 < xml.size() ? xml[pos + 4] : '\0';
        if (next == ' ' || next == '>' || next == '\t' || next == '\n' || next == '\r') {
            gui = pos;
            break;
        }
    }
    if (gui == std::string_view::npos) return Error() << "config.xml has no <gui> element";
    const size_t gui_end = xml.find("</gui>", gui);
    if (gui_end == std::string_view::npos) return Error() << "config.xml has no </gui>";
    const std::string_view section = xml.substr(gui, gui_end - gui);
    const size_t open = section.find("<apikey>");
    if (open == std::string_view::npos) return Error() << "config.xml has no API key";
    const size_t start = open + 8;
    const size_t close = section.find("</apikey>", start);
    if (close == std::string_view::npos) return Error() << "config.xml has an unterminated API key";
    bool ok = false;
    std::string key = DecodeEntities(
            android::base::Trim(std::string(section.substr(start, close - start))), &ok);
    if (!ok) return Error() << "the API key in config.xml has an unknown entity";
    if (key.empty()) return Error() << "the API key in config.xml is empty";
    for (char c : key) {
        // It goes into a header line.
        if (static_cast<unsigned char>(c) <= ' ' || static_cast<unsigned char>(c) >= 0x7f) {
            return Error() << "the API key in config.xml contains characters a header cannot carry";
        }
    }
    return key;
}

Result<http::Reply> Call(std::string_view method, std::string_view target, std::string_view body,
                         std::chrono::milliseconds timeout) {
    auto key = ReadApiKey();
    if (!key.ok()) return key.error();
    auto fd = http::ConnectUnix(kSocket, kConnectTimeout, "Syncthing (rest.sock)");
    if (!fd.ok()) return fd.error();
    // Syncthing checks the Host header only when it listens on localhost.
    const std::string request =
            http::BuildRequest(method, target, "localhost", {"X-API-Key: " + *key}, body);
    return http::Exchange(fd->get(), request, timeout, kMaxReply, kWhat);
}

void Redact(Json::Value* value) {
    if (value->isObject()) {
        for (const std::string& name : value->getMemberNames()) {
            if (IsSecretMember(name)) {
                if (strcasecmp(name.c_str(), "encryptionPassword") == 0) {
                    const Json::Value& secret = (*value)[name];
                    (*value)["encryptionPasswordSet"] =
                            secret.isString() && !secret.asString().empty();
                }
                value->removeMember(name);
            } else {
                Redact(&(*value)[name]);
            }
        }
    } else if (value->isArray()) {
        for (Json::Value& item : *value) Redact(&item);
    }
}

bool IsVersionsPath(std::string_view path) {
    if (path.empty()) return true;
    if (path.size() > kMaxVersionsPath) return false;
    std::string_view rest = path;
    const std::string root = std::string(kFoldersDir) + "/";
    if (path.front() == '/') {
        if (!android::base::StartsWith(path, root)) return false;
        rest = path.substr(root.size());
    }
    if (rest.empty()) return false;
    for (const std::string& part : android::base::Split(std::string(rest), "/")) {
        if (part.empty() || part == "." || part == ".." || part.size() > 255) return false;
        for (char c : part) {
            const auto u = static_cast<unsigned char>(c);
            if (u < 0x20 || u == 0x7f || c == '\\') return false;
        }
    }
    // A directory of its own under the root, not the root's own entries.
    if (path.front() == '/') {
        const std::string first = std::string(rest.substr(0, rest.find('/')));
        if (!IsFolderDirName(first)) return false;
    }
    return true;
}

void SetRunning(bool running) {
    engine_running = running;
}

std::string FailureCode() {
    return engine_running ? kCodeUnreachable : kCodeStopped;
}

bool IsMovableType(std::string_view type) {
    // Every other type, and none (Syncthing's default, send-receive), pulls.
    return type != "sendonly";
}

int64_t OverrideDeletes(const Json::Value& status) {
    if (!status.isObject()) return -1;
    int64_t items = 0;
    for (const auto& [name, sign] : {std::pair<const char*, int>{"globalFiles", 1},
                                     {"globalDirectories", 1},
                                     {"globalSymlinks", 1},
                                     {"localFiles", -1},
                                     {"localDirectories", -1},
                                     {"localSymlinks", -1},
                                     {"needDeletes", 1}}) {
        const Json::Value& count = status[name];
        if (!count.isIntegral()) return -1;
        items += sign * count.asInt64();
    }
    return std::max<int64_t>(items, 0);
}

namespace {

// An absolute path without empty, "." or ".." components ("/" at most once
// at the start). ".." above the root stays at the root, as the kernel does.
std::string CleanPath(std::string_view path) {
    std::vector<std::string> parts;
    for (const std::string& part : android::base::Split(std::string(path), "/")) {
        if (part.empty() || part == ".") continue;
        if (part == "..") {
            if (!parts.empty()) parts.pop_back();
            continue;
        }
        parts.push_back(part);
    }
    return "/" + android::base::Join(parts, '/');
}

// path is dir or below it (both clean).
bool IsWithin(std::string_view path, std::string_view dir) {
    if (dir == "/") return true;
    return path == dir || (path.size() > dir.size() && android::base::StartsWith(path, dir) &&
                           path[dir.size()] == '/');
}

}  // namespace

std::string VersionsDir(std::string_view folder_path, std::string_view fs_path) {
    if (fs_path.empty()) return "";
    const std::string folder = CleanPath(folder_path);
    const std::string versions = CleanPath(
            fs_path.front() == '/' ? std::string(fs_path) : folder + "/" + std::string(fs_path));
    // Syncthing never scans .stversions (fs.IsInternal).
    if (IsWithin(versions, folder + "/.stversions")) return "";
    return versions;
}

Json::Value FoldersAfter(const Json::Value& folders, const std::string& id,
                         const Json::Value& change) {
    Json::Value out(Json::arrayValue);
    bool found = false;
    if (folders.isArray()) {
        for (const Json::Value& f : folders) {
            if (!f.isObject() || !f["id"].isString() || f["id"].asString() != id ||
                !change.isObject()) {
                out.append(f);
                continue;
            }
            found = true;
            Json::Value patched = f;
            for (const std::string& key : change.getMemberNames()) {
                const Json::Value& value = change[key];
                if (key == "versioning" && value.isObject() &&
                    std::as_const(patched)[key].isObject()) {
                    for (const std::string& member : value.getMemberNames())
                        patched[key][member] = value[member];
                } else {
                    patched[key] = value;
                }
            }
            out.append(patched);
        }
    }
    if (!found && change.isObject()) {
        Json::Value added = change;
        added["id"] = id;
        out.append(added);
    }
    return out;
}

std::string VersionsConflict(const Json::Value& folders, const std::string& id) {
    struct Place {
        std::string id, dir, versions;
    };
    std::vector<Place> places;
    if (!folders.isArray()) return "";
    for (const Json::Value& f : folders) {
        // Syncthing's own folders are absolute paths (a relative or ~ path
        // would depend on its working directory; the pages never set one).
        if (!f.isObject() || !f["id"].isString() || !f["path"].isString() ||
            !android::base::StartsWith(f["path"].asString(), "/")) {
            continue;
        }
        Place p{f["id"].asString(), CleanPath(f["path"].asString()), ""};
        const Json::Value& v = f["versioning"];
        if (v.isObject() && v["type"].isString() && !v["type"].asString().empty() &&
            v["fsPath"].isString()) {
            p.versions = VersionsDir(p.dir, v["fsPath"].asString());
        }
        places.push_back(std::move(p));
    }
    auto name = [](const std::string& text) {
        return "\"" + text::SanitizeUtf8(text.substr(0, 64)) + "\"";
    };
    for (const Place& p : places) {
        if (p.versions.empty()) continue;
        for (const Place& q : places) {
            // A change of folder id: its versions against every directory,
            // every folder's versions against its directory.
            if (p.id != id && q.id != id) continue;
            if (IsWithin(p.versions, q.dir)) {
                return "the versions directory " + p.versions + " of folder " + name(p.id) +
                       (p.id == q.id ? " is inside that folder"
                                     : " is inside folder " + name(q.id));
            }
            if (IsWithin(q.dir, p.versions)) {
                return "folder " + name(q.id) + " (" + q.dir +
                       ") is inside the versions directory " + p.versions + " of folder " +
                       name(p.id);
            }
        }
    }
    return "";
}

std::string CheckMove(const Json::Value& folders, const std::string& id, const std::string& target,
                      std::string* code) {
    if (!folders.isArray()) return "Syncthing sent a folder list that is not a list";
    const Json::Value* original = nullptr;
    for (const Json::Value& f : folders) {
        if (!f.isObject() || !f["path"].isString()) continue;
        const bool self = f["id"].isString() && f["id"].asString() == id;
        if (self) original = &f;
        if (CleanPath(f["path"].asString()) == CleanPath(target)) {
            *code = kCodeFolderInUse;
            return (self ? "the folder already uses " : "another folder uses ") + target;
        }
    }
    if (original == nullptr) {
        *code = kCodeNoFolder;
        return "no folder " + text::SanitizeUtf8(id);
    }
    const Json::Value& type = (*original)["type"];
    if (type.isString() && !IsMovableType(type.asString())) {
        *code = kCodeNotMovable;
        return "a " + type.asString() +
               " folder cannot be moved: its new directory would never be filled from the other "
               "devices, and "
               "overriding them would delete its files on every device";
    }
    Json::Value change(Json::objectValue);
    change["path"] = target;
    if (std::string why = VersionsConflict(FoldersAfter(folders, id, change), id); !why.empty()) {
        *code = kCodeVersionsInFolder;
        return why;
    }
    return "";
}

namespace {

// A path segment percent-encoded (folder IDs may hold any character).
std::string EncodeSegment(std::string_view text) {
    static const char kHex[] = "0123456789ABCDEF";
    std::string out;
    for (char c : text) {
        const auto u = static_cast<unsigned char>(c);
        if ((u >= 'a' && u <= 'z') || (u >= 'A' && u <= 'Z') || (u >= '0' && u <= '9') ||
            c == '-' || c == '.' || c == '_' || c == '~') {
            out.push_back(c);
        } else {
            out.push_back('%');
            out.push_back(kHex[u >> 4]);
            out.push_back(kHex[u & 15]);
        }
    }
    return out;
}

std::string DecodeSegment(std::string_view text) {
    std::string out;
    for (size_t i = 0; i < text.size(); ++i) {
        if (text[i] == '%' && i + 2 < text.size()) {
            auto hex = [](char c) -> int {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                if (c >= 'A' && c <= 'F') return c - 'A' + 10;
                return -1;
            };
            const int hi = hex(text[i + 1]), lo = hex(text[i + 2]);
            if (hi >= 0 && lo >= 0) {
                out.push_back(static_cast<char>(hi * 16 + lo));
                i += 2;
                continue;
            }
        }
        out.push_back(text[i]);
    }
    return out;
}

// A JSON reply of Syncthing with status 200, parsed; an error text otherwise.
Result<Json::Value> GetJson(const std::string& target) {
    auto reply = Call("GET", target, "", kConfigTimeout);
    if (!reply.ok()) return reply.error();
    if (reply->status != 200) {
        return Error() << "Syncthing answered GET " << target.substr(0, target.find('?'))
                       << " with HTTP " << reply->status;
    }
    Json::Value value;
    std::string error;
    if (!text::ParseJson(reply->body, &value, &error))
        return Error() << "Syncthing sent a reply that does not parse";
    return value;
}

}  // namespace

namespace {

// POST /rest/db/override?QUERY: refused while the override would delete
// items on the other devices, unless the body confirms it.
std::string PrepareOverride(std::string_view query, Json::Value* body, std::string* code) {
    const bool confirmed = body->isObject() && std::as_const(*body)["deleteMissing"].asBool();
    // Syncthing's override takes no body.
    *body = Json::Value();
    auto status = GetJson("/rest/db/status?" + std::string(query));
    if (!status.ok()) {
        if (confirmed) {
            LOG(WARNING) << "sync override confirmed without the folder's counts: "
                         << status.error().message();
            return "";
        }
        *code = FailureCode();
        return status.error().message();
    }
    const int64_t deletes = OverrideDeletes(*status);
    if (deletes == 0) return "";
    if (confirmed) {
        LOG(WARNING) << "sync override confirmed: "
                     << (deletes < 0 ? std::string("an unknown number of")
                                     : std::to_string(deletes))
                     << " items missing on this phone are deleted on every device";
        return "";
    }
    *code = kCodeOverrideDeletes;
    if (deletes < 0) {
        return "Syncthing did not report the folder's counts, so the items an override deletes on "
               "the other "
               "devices are unknown; confirm with {\"deleteMissing\": true}";
    }
    return "this phone lacks " + std::to_string(deletes) +
           " items the other devices have; an override deletes them on every device; confirm with "
           "{\"deleteMissing\": true}";
}

}  // namespace

std::string Prepare(std::string_view method, std::string_view target, Json::Value* body,
                    std::string* code) {
    std::vector<std::string_view> segments;
    const size_t query = target.find('?');
    if (!http::SplitPath(target.substr(0, query), &segments)) return "";
    if (method == "POST" && http::Matches("/rest/db/override", segments)) {
        return PrepareOverride(query == std::string_view::npos ? "" : target.substr(query + 1),
                               body, code);
    }
    const bool add = method == "POST" && http::Matches("/rest/config/folders", segments);
    const bool patch = method == "PATCH" && http::Matches("/rest/config/folders/*", segments);
    if ((!add && !patch) || !body->isObject()) return "";
    const bool versioning = body->isMember("versioning");
    if (patch && !body->isMember("devices") && !body->isMember("type") && !versioning) return "";

    auto folders = GetJson("/rest/config/folders");
    if (!folders.ok()) {
        *code = FailureCode();
        return folders.error().message();
    }
    if (!folders->isArray()) {
        // Nothing to check against: a password to keep or copy would be lost.
        *code = FailureCode();
        return "Syncthing sent a folder list that is not a list";
    }
    const Json::Value& list = *folders;
    const std::string id =
            add ? std::as_const(*body)["id"].asString() : DecodeSegment(segments.back());
    if (add) {
        // POST replaces a folder with the same ID.
        Json::Value after(Json::arrayValue);
        for (const Json::Value& f : list) {
            if (!f.isObject() || !f["id"].isString() || f["id"].asString() != id) after.append(f);
        }
        after.append(*body);
        if (std::string why = VersionsConflict(after, id); !why.empty()) {
            *code = kCodeVersionsInFolder;
            return why;
        }
        return "";
    }
    if (versioning) {
        if (std::string why = VersionsConflict(FoldersAfter(list, id, *body), id); !why.empty()) {
            *code = kCodeVersionsInFolder;
            return why;
        }
    }
    const Json::Value* current = nullptr;
    for (const Json::Value& f : list) {
        if (f.isObject() && f["id"].isString() && f["id"].asString() == id) current = &f;
    }
    // No such folder: Syncthing answers the change itself (404); a password
    // to copy has no source (KeepPasswords refuses it).
    const Json::Value no_devices;
    const Json::Value& now = current != nullptr ? *current : no_devices;
    if (body->isMember("type") && now["type"].isString() &&
        now["type"].asString() == "receiveencrypted" &&
        std::as_const(*body)["type"].asString() != "receiveencrypted") {
        *code = kCodeNotAllowed;
        return "a receive-encrypted folder keeps its type";
    }
    if (body->isMember("devices")) {
        if (std::string why = KeepPasswords(now["devices"], &(*body)["devices"]); !why.empty()) {
            *code = kCodeNotAllowed;
            return why;
        }
    }
    return "";
}

// ---- commands ----

Outcome CmdDirs(const Json::Value&, uid_t) {
    // The folders root is 0750 and the engine's; the daemon lists it
    // through the group.
    Json::Value reply(Json::objectValue);
    reply["root"] = kFoldersDir;
    reply["dirs"] = Json::Value(Json::arrayValue);
    std::unique_ptr<DIR, int (*)(DIR*)> dir(opendir(kFoldersDir), closedir);
    if (!dir) {
        if (errno == ENOENT) return Outcome::Ok(std::move(reply));
        return Outcome::Failed("cannot open " + std::string(kFoldersDir) + ": " + strerror(errno));
    }
    std::vector<std::string> names;
    const int dfd = dirfd(dir.get());
    while (dirent* e = readdir(dir.get())) {
        const std::string name = e->d_name;
        if (!IsFolderDirName(name)) continue;
        struct stat st;
        if (fstatat(dfd, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0 || !S_ISDIR(st.st_mode))
            continue;
        names.push_back(name);
        if (names.size() >= 1000) break;
    }
    std::sort(names.begin(), names.end());
    for (const std::string& name : names) reply["dirs"].append(name);
    return Outcome::Ok(std::move(reply));
}

Outcome CmdIgnored(const Json::Value& request, uid_t uid) {
    std::string op = "list";
    if (request.isMember("op")) {
        if (!request["op"].isString())
            return Outcome::Refused("\"op\" must be list, add or remove");
        op = request["op"].asString();
    }
    if (op != "list" && op != "add" && op != "remove")
        return Outcome::Refused("\"op\" must be list, add or remove");
    std::string device;
    if (op != "list") {
        if (!request["device"].isString() || !IsDeviceId(request["device"].asString())) {
            return Outcome::Refused("\"device\" must be a device ID");
        }
        device = request["device"].asString();
    }
    for (const char* field : {"name", "address"}) {
        if (request.isMember(field) && (op != "add" || !IsText(request[field]))) {
            return Outcome::Refused(std::string("\"") + field + "\" is text for op add only");
        }
    }

    // The whole configuration stays inside the daemon: only this list leaves.
    auto reply = Call("GET", "/rest/config", "", kConfigTimeout);
    if (!reply.ok()) return Outcome::Failed(reply.error().message(), android::base::WARNING);
    if (reply->status != 200) {
        return Outcome::Failed(
                "Syncthing answered GET /rest/config with HTTP " + std::to_string(reply->status),
                android::base::WARNING);
    }
    Json::Value config;
    std::string error;
    if (!text::ParseJson(reply->body, &config, &error) || !config.isObject()) {
        return Outcome::Failed("Syncthing sent a configuration that does not parse");
    }
    Json::Value& ignored = config["remoteIgnoredDevices"];
    if (ignored.isNull()) ignored = Json::Value(Json::arrayValue);
    if (!ignored.isArray()) return Outcome::Failed("remoteIgnoredDevices is not a list");

    if (op != "list") {
        Json::Value kept(Json::arrayValue);
        bool present = false;
        for (const Json::Value& entry : ignored) {
            if (entry.isObject() && entry["deviceID"].isString() &&
                entry["deviceID"].asString() == device) {
                present = true;
                if (op == "remove") continue;
            }
            kept.append(entry);
        }
        if (op == "add" && !present) {
            Json::Value entry(Json::objectValue);
            entry["time"] = Rfc3339Now();
            entry["deviceID"] = device;
            entry["name"] = request.isMember("name") ? request["name"].asString() : "";
            entry["address"] = request.isMember("address") ? request["address"].asString() : "";
            kept.append(entry);
        }
        const bool changed = (op == "add") != present;
        if (changed) {
            ignored = kept;
            auto put = Call("PUT", "/rest/config", text::ToJson(config), kConfigTimeout);
            if (!put.ok()) return Outcome::Failed(put.error().message(), android::base::WARNING);
            if (put->status < 200 || put->status > 299) {
                return Outcome::Failed("Syncthing answered PUT /rest/config with HTTP " +
                                               std::to_string(put->status),
                                       android::base::WARNING);
            }
            LOG(INFO) << "uid " << uid << ": syncthing "
                      << (op == "add" ? "ignores" : "stops ignoring") << " device "
                      << device.substr(0, 7);
            if (op == "add") {
                // The pending entry goes too, as Syncthing's own GUI does.
                Call("DELETE", "/rest/cluster/pending/devices?device=" + device, "",
                     kConfigTimeout);
            }
        }
    }
    Json::Value devices(Json::arrayValue);
    for (const Json::Value& entry : ignored) {
        if (!entry.isObject()) continue;
        Json::Value out(Json::objectValue);
        for (const char* key : {"deviceID", "name", "address", "time"}) {
            if (entry[key].isString()) out[key] = text::SanitizeUtf8(entry[key].asString());
        }
        devices.append(out);
    }
    Json::Value out(Json::objectValue);
    out["devices"] = devices;
    return Outcome::Ok(std::move(out));
}

Outcome CmdFolderMove(const Json::Value& request, uid_t uid) {
    if (!request["folder"].isString() || request["folder"].asString().empty() ||
        request["folder"].asString().size() > kMaxText) {
        return Outcome::Refused("\"folder\" must be a folder ID");
    }
    if (!request["dir"].isString() || !IsFolderDirName(request["dir"].asString())) {
        return Outcome::Refused(
                "\"dir\" must be a directory name ([A-Za-z0-9._ -], not starting with '.')");
    }
    const std::string id = request["folder"].asString();
    const std::string target = std::string(kFoldersDir) + "/" + request["dir"].asString();
    const std::string folder_path = "/rest/config/folders/" + EncodeSegment(id);

    auto folders = GetJson("/rest/config/folders");
    if (!folders.ok())
        return Outcome::Failed(folders.error().message(), android::base::WARNING, FailureCode());
    if (!folders->isArray())
        return Outcome::Failed("Syncthing sent a folder list that is not a list");
    std::string code;
    if (std::string why = CheckMove(*folders, id, target, &code); !why.empty()) {
        return Outcome::Refused(why, code);
    }
    Json::Value original;
    for (const Json::Value& f : std::as_const(*folders)) {
        if (f.isObject() && f["id"].isString() && f["id"].asString() == id) original = f;
    }

    // The ignore patterns live in the old directory's .stignore.
    Json::Value ignore(Json::arrayValue);
    auto ignores = GetJson("/rest/db/ignores?folder=" + EncodeSegment(id));
    if (ignores.ok() && ignores->isObject() && std::as_const(*ignores)["ignore"].isArray()) {
        ignore = std::as_const(*ignores)["ignore"];
    } else if (!ignores.ok()) {
        LOG(WARNING) << "sync.folder.move: ignore patterns of " << id
                     << " not read: " << ignores.error().message();
    }

    const Json::Value& before = original;  // read without adding members
    const bool was_paused = before["paused"].isBool() && before["paused"].asBool();
    Json::Value moved = original;
    moved["path"] = target;
    moved["paused"] = true;
    // Only the pieces a new folder in the sync storage may carry, besides
    // what Syncthing keeps as it is.
    moved["ignorePerms"] = true;

    auto removed = Call("DELETE", folder_path, "", kConfigTimeout);
    if (!removed.ok())
        return Outcome::Failed(removed.error().message(), android::base::WARNING, FailureCode());
    if (removed->status < 200 || removed->status > 299) {
        return Outcome::Failed("Syncthing answered DELETE " + folder_path + " with HTTP " +
                                       std::to_string(removed->status),
                               android::base::WARNING);
    }
    auto added = Call("POST", "/rest/config/folders", text::ToJson(moved), kConfigTimeout);
    if (!added.ok() || added->status < 200 || added->status > 299) {
        const std::string why =
                added.ok() ? "HTTP " + std::to_string(added->status) : added.error().message();
        // Put the folder back as it was.
        auto back = Call("POST", "/rest/config/folders", text::ToJson(original), kConfigTimeout);
        const bool restored = back.ok() && back->status >= 200 && back->status <= 299;
        LOG(ERROR) << "sync.folder.move: adding " << id << " at " << target << " failed (" << why
                   << "); " << (restored ? "restored at its old path" : "could not restore it");
        return Outcome::Failed(
                "Syncthing did not add the folder at " + target + " (" + why + ")" +
                (restored ? "; it is back at its old path" : "; it could not be restored"));
    }
    if (!ignore.empty()) {
        Json::Value lines(Json::objectValue);
        lines["ignore"] = ignore;
        auto set = Call("POST", "/rest/db/ignores?folder=" + EncodeSegment(id), text::ToJson(lines),
                        kConfigTimeout);
        if (!set.ok() || set->status != 200) {
            LOG(WARNING) << "sync.folder.move: ignore patterns of " << id << " not copied: "
                         << (set.ok() ? "HTTP " + std::to_string(set->status)
                                      : set.error().message());
        }
    }
    bool resumed = false;
    if (!was_paused) {
        Json::Value resume(Json::objectValue);
        resume["paused"] = false;
        auto patched = Call("PATCH", folder_path, text::ToJson(resume), kConfigTimeout);
        resumed = patched.ok() && patched->status >= 200 && patched->status <= 299;
        if (!resumed) LOG(WARNING) << "sync.folder.move: " << id << " stays paused (resume failed)";
    }
    LOG(INFO) << "uid " << uid << ": syncthing folder " << id << " moved from "
              << (before["path"].isString() ? before["path"].asString() : "?") << " to " << target;
    Json::Value reply(Json::objectValue);
    reply["path"] = target;
    reply["previous"] = before["path"].isString() ? before["path"] : Json::Value();
    reply["paused"] = was_paused || !resumed;
    reply["ignores_copied"] = static_cast<int>(ignore.size());
    return Outcome::Ok(std::move(reply));
}

}  // namespace lujarch::sync::rest
