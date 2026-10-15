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

#include "manager.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <initializer_list>
#include <set>
#include <string_view>
#include <thread>
#include <utility>

#include <android-base/unique_fd.h>

#include "text.h"

namespace lujarch::proxy::manager {

using android::base::ErrnoError;
using android::base::Error;
using android::base::Result;
using android::base::unique_fd;
using namespace std::chrono_literals;

namespace {

bool OneOf(std::string_view value, std::initializer_list<std::string_view> allowed) {
    return std::find(allowed.begin(), allowed.end(), value) != allowed.end();
}

bool AllChars(std::string_view value, std::string_view extra) {
    for (char c : value) {
        const bool alnum =
                (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        if (!alnum && extra.find(c) == std::string_view::npos) return false;
    }
    return true;
}

// Routing target of `app add`: DIRECT, REJECT, JP/residential,
// fallback:GROUP, balance:GROUP, custom group keys (`app targets --json` lists
// them). Never starts with '-' (no options). Bytes above ASCII pass: custom
// group keys may be named in any script (a name in Chinese, say), and the manager gets the
// target as one argv entry (no shell) and refuses invalid UTF-8 and commas.
bool IsTarget(std::string_view value) {
    if (value.empty() || value.size() > 128 || value[0] == '-') return false;
    for (char c : value) {
        if (static_cast<unsigned char>(c) >= 0x80) continue;
        if (!AllChars(std::string_view(&c, 1), "_-./:+@")) return false;
    }
    return true;
}

// Domain (or address) for `rule match`.
bool IsDomain(std::string_view value) {
    return !value.empty() && value.size() <= 253 && value[0] != '-' && AllChars(value, "-._:");
}

// A provider or rule set name: 1-64 bytes, no control characters or path
// separators, not starting with '-' (an option) or '.'. Any script passes.
bool IsName(std::string_view value) {
    if (value.empty() || value.size() > 64 || value[0] == '-' || value[0] == '.') return false;
    for (char c : value) {
        const auto u = static_cast<unsigned char>(c);
        if (u < 0x20 || u == 0x7f || c == '/' || c == '\\') return false;
    }
    return true;
}

// Filter text (`custom-group add --name-contains`): 1-64 bytes, no control
// characters, not starting with '-'.
bool IsText(std::string_view value) {
    if (value.empty() || value.size() > 64 || value[0] == '-') return false;
    for (char c : value) {
        const auto u = static_cast<unsigned char>(c);
        if (u < 0x20 || u == 0x7f) return false;
    }
    return true;
}

// A custom group key: a routing target without the '/' and ':' of the
// generated targets (JP/residential, fallback:KEY).
bool IsGroupKey(std::string_view value) {
    return IsTarget(value) && value.size() <= 64 && value.find('/') == std::string_view::npos &&
           value.find(':') == std::string_view::npos;
}

// A rule-provider file to import: a name ending in .yaml, .yml or .txt.
bool IsRulesetFile(std::string_view value) {
    auto ends = [value](std::string_view suffix) {
        return value.size() > suffix.size() && value.substr(value.size() - suffix.size()) == suffix;
    };
    return IsName(value) && (ends(".yaml") || ends(".yml") || ends(".txt"));
}

// A backup id: YYYYMMDD-HHMMSS, optionally -N.
bool IsBackupId(std::string_view value) {
    if (value.size() < 15 || value.size() > 20 || value[8] != '-') return false;
    for (size_t i = 0; i < value.size(); ++i) {
        const bool digit = value[i] >= '0' && value[i] <= '9';
        if (!digit && !(value[i] == '-' && (i == 8 || i == 15))) return false;
    }
    return true;
}

bool IsLetters(std::string_view value) {
    return std::all_of(value.begin(), value.end(),
                       [](char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); });
}

// `config set <key> <value>` keys: words, switches, numbers, a country code or an IPv4 range on
// argv (the manager checks each value against its key).
bool IsConfigKey(std::string_view value) {
    return OneOf(value, {"mode",
                         "log-level",
                         "allow-lan",
                         "ipv6",
                         "unified-delay",
                         "tcp-concurrent",
                         "find-process-mode",
                         "keep-alive-interval",
                         "keep-alive-idle",
                         "disable-keep-alive",
                         "global-client-fingerprint",
                         "geodata-mode",
                         "geodata-loader",
                         "geo-auto-update",
                         "geo-update-interval",
                         "tun.enable",
                         "tun.stack",
                         "tun.auto-route",
                         "tun.auto-detect-interface",
                         "tun.mtu",
                         "tun.strict-route",
                         "tun.endpoint-independent-nat",
                         "sniffer.enable",
                         "sniffer.force-dns-mapping",
                         "sniffer.parse-pure-ip",
                         "sniffer.override-destination",
                         "dns.enable",
                         "dns.enhanced-mode",
                         "dns.fake-ip-range",
                         "dns.fake-ip-filter-mode",
                         "dns.ipv6",
                         "dns.prefer-h3",
                         "dns.respect-rules",
                         "dns.use-hosts",
                         "dns.use-system-hosts",
                         "dns.fallback-filter.geoip",
                         "dns.fallback-filter.geoip-code",
                         "group-test.auto.interval",
                         "group-test.auto.tolerance",
                         "group-test.auto.timeout",
                         "group-test.auto.lazy",
                         "group-test.fallback.interval",
                         "group-test.fallback.timeout",
                         "group-test.fallback.lazy",
                         "group-test.load-balance.interval",
                         "group-test.load-balance.timeout",
                         "group-test.load-balance.lazy",
                         "group-test.load-balance.strategy",
                         "adaptive.fast-half-life",
                         "adaptive.slow-half-life",
                         "adaptive.race-cost",
                         "adaptive.hysteresis",
                         "adaptive.probe-interval",
                         "adaptive.dial-timeout"});
}

// `config set <key> --stdin` keys: lists, DNS servers, nameserver-policy,
// sniffer protocols, geodata URLs and the groups' test addresses as one JSON
// value on stdin (URLs and domain lists would not survive argv checks, and a
// DoH path can carry an account id).
bool IsConfigJsonKey(std::string_view value) {
    return OneOf(value, {"geox-url.geoip",
                         "geox-url.geosite",
                         "geox-url.mmdb",
                         "geox-url.asn",
                         "tun.dns-hijack",
                         "tun.route-exclude-address",
                         "sniffer.sniff",
                         "sniffer.force-domain",
                         "sniffer.skip-domain",
                         "dns.fake-ip-filter",
                         "dns.nameserver",
                         "dns.fallback",
                         "dns.default-nameserver",
                         "dns.proxy-server-nameserver",
                         "dns.direct-nameserver",
                         "dns.nameserver-policy",
                         "dns.fallback-filter.geosite",
                         "dns.fallback-filter.ipcidr",
                         "dns.fallback-filter.domain",
                         "group-test.auto.url",
                         "group-test.fallback.url",
                         "group-test.load-balance.url"});
}

// A test parameter of one custom group (`custom-group test <key> <field>`);
// the address goes on stdin, the others on argv.
bool IsTestField(std::string_view value) {
    return OneOf(value, {"interval", "tolerance", "timeout", "lazy", "strategy"});
}

bool IsConfigValue(std::string_view value) {
    return !value.empty() && value.size() <= 32 && value[0] != '-' && AllChars(value, "-./");
}

// A decimal number from 0 to max, without sign or leading zeros.
bool IsNumber(std::string_view value, unsigned max) {
    if (value.empty() || value.size() > 6 || (value.size() > 1 && value[0] == '0')) return false;
    unsigned n = 0;
    for (char c : value) {
        if (c < '0' || c > '9') return false;
        n = n * 10 + static_cast<unsigned>(c - '0');
    }
    return n <= max;
}

// A rule as the manager takes it (`DOMAIN-SUFFIX,example.com,JP/residential`,
// `AND,((NETWORK,UDP),(DST-PORT,443)),REJECT`): one line, 1-1024 bytes, not an
// option. The manager parses it and checks the type.
bool IsRuleText(std::string_view value) {
    if (value.empty() || value.size() > 1024 || value[0] == '-') return false;
    for (char c : value) {
        const auto u = static_cast<unsigned char>(c);
        if (u < 0x20 || u == 0x7f) return false;
    }
    return true;
}

// A rule's position: <set>:<index>.
bool IsSetIndex(std::string_view value) {
    const size_t colon = value.rfind(':');
    return colon != std::string_view::npos && IsName(value.substr(0, colon)) &&
           IsNumber(value.substr(colon + 1), 999999);
}

bool IsCountry(std::string_view value) {
    return value.size() == 2 && IsLetters(value);
}

bool IsNetType(std::string_view value) {
    return OneOf(value, {"datacenter", "residential", "starlink"});
}

// args[from..] are distinct flags out of allowed.
bool Flags(const std::vector<std::string>& args, size_t from,
           std::initializer_list<std::string_view> allowed) {
    std::set<std::string_view> seen;
    for (size_t i = from; i < args.size(); ++i) {
        if (!OneOf(args[i], allowed) || !seen.insert(args[i]).second) return false;
    }
    return true;
}

// args[from..] are pairs "--option value", each option at most once and its
// value accepted by the option's check; nothing else.
bool Options(const std::vector<std::string>& args, size_t from,
             std::initializer_list<std::pair<std::string_view, bool (*)(std::string_view)>> allowed,
             std::set<std::string_view>* seen_out = nullptr) {
    std::set<std::string_view> seen;
    for (size_t i = from; i < args.size(); i += 2) {
        if (i + 1 >= args.size() || !seen.insert(args[i]).second) return false;
        bool ok = false;
        for (const auto& [name, check] : allowed) {
            if (args[i] == name) {
                ok = check(args[i + 1]);
                break;
            }
        }
        if (!ok) return false;
    }
    if (seen_out != nullptr) *seen_out = std::move(seen);
    return true;
}

bool IsTimeout(std::string_view value) {
    return IsNumber(value, 30) && value != "0";
}

// Below the daemon's 240 s limit, with room for the lookups that finish after it.
bool IsBudget(std::string_view value) {
    return IsNumber(value, 200) && value != "0";
}

bool IsPlatform(std::string_view value) {
    return OneOf(value, {"android", "desktop", "all"});
}

// The rule shapes that change routing.yaml (the manager checks the rule types
// and positions; --expect makes it refuse when the list changed meanwhile).
bool IsRuleChange(const std::vector<std::string>& args) {
    const size_t n = args.size();
    const std::string& op = args[1];
    if (op == "add") {
        // rule add <rule> --set <set> [--index N]
        std::set<std::string_view> seen;
        return n >= 5 && IsRuleText(args[2]) &&
               Options(args, 3,
                       {{"--set", IsName},
                        {"--index", [](std::string_view v) { return IsNumber(v, 999999); }}},
                       &seen) &&
               seen.count("--set") == 1;
    }
    if (op == "edit") {
        // rule edit <set:i> <rule> [--expect <rule>]
        return n >= 4 && IsSetIndex(args[2]) && IsRuleText(args[3]) &&
               Options(args, 4, {{"--expect", IsRuleText}});
    }
    if (op == "remove") {
        // rule remove <set:i> [--expect <rule>], or rule remove <pattern> (every
        // rule with that pattern)
        if (n >= 3 && IsSetIndex(args[2])) return Options(args, 3, {{"--expect", IsRuleText}});
        return n == 3 && IsText(args[2]);
    }
    if (op == "move") {
        // rule move <set:i> <set:j | j> [--expect <rule>]
        return n >= 4 && IsSetIndex(args[2]) &&
               (IsSetIndex(args[3]) || IsNumber(args[3], 999999)) &&
               Options(args, 4, {{"--expect", IsRuleText}});
    }
    if (op == "move-set") return n == 4 && IsName(args[2]) && IsNumber(args[3], 9999);
    if (op == "platform") return n == 4 && IsName(args[2]) && IsPlatform(args[3]);
    if (op == "remove-set") return n == 3 && IsName(args[2]);
    if (op == "regroup") return n == 2;
    if (op == "import") {
        // rule import - --set <set>: the list on stdin
        return n == 5 && args[2] == "-" && args[3] == "--set" && IsName(args[4]);
    }
    if (op == "retarget") {
        // rule retarget --to <target> (--pattern <text> | --from <target>) [--set <set>]
        std::set<std::string_view> seen;
        return Options(args, 2,
                       {{"--to", IsTarget},
                        {"--pattern", IsText},
                        {"--from", IsTarget},
                        {"--set", IsName}},
                       &seen) &&
               seen.count("--to") == 1 && seen.count("--pattern") + seen.count("--from") == 1;
    }
    return false;
}

// custom-group add <key> [--name-contains T] [--country CC] [--net-type T]
// [--fallback-to K], each option at most once.
bool IsCustomGroupAdd(const std::vector<std::string>& args) {
    if (args.size() < 3 || !IsGroupKey(args[2])) return false;
    std::set<std::string_view> seen;
    for (size_t i = 3; i < args.size(); i += 2) {
        if (i + 1 >= args.size() || !seen.insert(args[i]).second) return false;
        const std::string& value = args[i + 1];
        bool ok = false;
        if (args[i] == "--name-contains") {
            ok = IsText(value);
        } else if (args[i] == "--country") {
            ok = value.size() == 2 && IsLetters(value);
        } else if (args[i] == "--net-type") {
            ok = OneOf(value, {"datacenter", "residential", "starlink"});
        } else if (args[i] == "--fallback-to") {
            ok = IsTarget(value);
        }
        if (!ok) return false;
    }
    return true;
}

// provider add-node <name> - [--append] [--country CC --net-type T]: nodes
// (proxy URIs with credentials, or YAML) on stdin only.
bool IsAddNode(const std::vector<std::string>& args) {
    const size_t n = args.size();
    if (n < 4 || !IsName(args[2]) || args[3] != "-") return false;
    size_t i = 4;
    if (i < n && args[i] == "--append") ++i;
    if (i == n) return true;
    return n == i + 4 && args[i] == "--country" && args[i + 1].size() == 2 &&
           IsLetters(args[i + 1]) && args[i + 2] == "--net-type" &&
           OneOf(args[i + 3], {"datacenter", "residential", "starlink"});
}

}  // namespace

std::string CheckArgs(const std::vector<std::string>& args) {
    if (args.empty()) return "no mihomo-manager subcommand";
    const std::string& sub = args[0];
    const size_t n = args.size();
    bool ok = false;
    // Only exact shapes: no global options (--dir, --runtime-dir, --core,
    // --profile) and no output paths, so every run works on the device's
    // own layout.
    if (sub == "status") {
        ok = n <= 3 && Flags(args, 1, {"--offline", "--json"});
    } else if (OneOf(sub, {"groups", "fetch"})) {
        ok = n == 1;
    } else if (sub == "classify") {
        // The report, every node's location, and locations set by hand or
        // forgotten (node names as a JSON list on stdin).
        ok = n == 1 || (n == 2 && args[1] == "--json") ||
             (n == 4 && args[1] == "set" && IsCountry(args[2]) && IsNetType(args[3])) ||
             (n == 2 && args[1] == "forget");
    } else if (sub == "probe") {
        // Locates nodes through their exit IP: a local core instance (loopback
        // listeners with a random password) and public IP lookups.
        ok = n >= 2 && args[n - 1] == "--json" &&
             Options(std::vector<std::string>(args.begin(), args.end() - 1), 1,
                     {{"--timeout", IsTimeout}, {"--budget", IsBudget}});
    } else if (OneOf(sub, {"validate", "build", "rollback"})) {
        ok = n == 1 || (n == 2 && args[1] == "--json");
    } else if (sub == "core") {
        ok = n == 2 && args[1] == "status";
    } else if (sub == "provider") {
        // A subscription URL never travels on argv (the process list, logs):
        // add and update take it on stdin.
        ok = (n == 2 && args[1] == "list") ||
             (n == 3 && args[1] == "list" && args[2] == "--json") ||
             (n == 4 && args[1] == "nodes" && IsName(args[2]) && args[3] == "--json") ||
             (n >= 4 && n <= 5 && args[1] == "add" && IsName(args[2]) && args[3] == "--url-stdin" &&
              (n == 4 || args[4] == "--no-fetch")) ||
             (n >= 3 && n <= 4 && args[1] == "update" && IsName(args[2]) &&
              (n == 3 || args[3] == "--url-stdin")) ||
             (n >= 3 && n <= 4 && args[1] == "remove" && IsName(args[2]) &&
              (n == 3 || args[3] == "--purge")) ||
             (args[1] == "add-node" && IsAddNode(args));
    } else if (sub == "custom-group") {
        ok = (n == 2 && args[1] == "list") ||
             (n == 3 && args[1] == "list" && args[2] == "--json") ||
             (n == 3 && args[1] == "remove" && IsGroupKey(args[2])) ||
             (n >= 3 && args[1] == "add" && IsCustomGroupAdd(args)) ||
             (n == 5 && args[1] == "test" && IsGroupKey(args[2]) && IsTestField(args[3]) &&
              IsConfigValue(args[4])) ||
             (n == 5 && args[1] == "test" && IsGroupKey(args[2]) && args[3] == "url" &&
              args[4] == "--stdin") ||
             // custom-group adaptive <key> on|off: one adaptive group on the phone
             (n == 4 && args[1] == "adaptive" && IsGroupKey(args[2]) &&
              OneOf(args[3], {"on", "off"}));
    } else if (sub == "config") {
        ok = (n == 3 && args[1] == "get" && args[2] == "--json") ||
             (n == 4 && args[1] == "set" && IsConfigKey(args[2]) && IsConfigValue(args[3])) ||
             (n == 4 && args[1] == "set" && IsConfigJsonKey(args[2]) && args[3] == "--stdin") ||
             (n == 3 && args[1] == "restore-recommended" && args[2] == "--json");
    } else if (sub == "secret") {
        // A new controller secret; it never reaches the app or a log.
        ok = n == 3 && args[1] == "rotate" && args[2] == "--json";
    } else if (OneOf(sub, {"files", "backups"})) {
        ok = n == 2 && args[1] == "--json";
    } else if (sub == "restore") {
        ok = (n == 2 && args[1] == "--json") ||
             (n == 3 && IsBackupId(args[1]) && args[2] == "--json");
    } else if (sub == "import") {
        ok = n >= 3 && args[n - 1] == "--json" &&
             (((args[1] == "config" || args[1] == "routing") &&
               (n == 3 || (n == 4 && args[2] == "--whole"))) ||
              (OneOf(args[1], {"providers", "bundle", "cache"}) && n == 3) ||
              (args[1] == "ruleset" && IsRulesetFile(args[2]) &&
               (n == 4 || (n == 5 && args[3] == "--remove"))) ||
              (args[1] == "geodata" && n == 4 &&
               OneOf(args[2], {"mmdb", "geoip", "geosite", "asn"})));
    } else if (sub == "app") {
        // list and targets also in --json form, the only output the per-app
        // routing page parses (the text forms are for people).
        ok = (n == 2 && OneOf(args[1], {"list", "targets"})) ||
             (n == 3 && OneOf(args[1], {"list", "targets"}) && args[2] == "--json") ||
             (n == 4 && args[1] == "add" && IsPackageName(args[2]) && IsTarget(args[3])) ||
             (n == 3 && args[1] == "remove" && IsPackageName(args[2])) ||
             (n == 4 && args[1] == "tun" && OneOf(args[2], {"include", "exclude", "clear"}) &&
              IsPackageName(args[3]));
    } else if (sub == "rule") {
        ok = (n == 2 && OneOf(args[1], {"list", "sets", "default"})) ||
             (n == 3 && OneOf(args[1], {"list", "sets", "default"}) && args[2] == "--json") ||
             (n == 5 && args[1] == "list" && args[2] == "--set" && IsName(args[3]) &&
              args[4] == "--json") ||
             (n >= 2 && IsRuleChange(args)) ||
             (n == 3 && args[1] == "match" && IsDomain(args[2])) ||
             (n == 4 && args[1] == "match" && IsDomain(args[2]) && args[3] == "--json") ||
             (n == 3 && OneOf(args[1], {"enable", "disable"}) && IsName(args[2])) ||
             (n == 3 && args[1] == "default" && IsTarget(args[2]));
    } else {
        return "mihomo-manager subcommand not allowed";
    }
    return ok ? "" : "arguments not allowed for mihomo-manager " + sub;
}

bool TakesInput(const std::vector<std::string>& args) {
    if (args.empty()) return false;
    // Every import but the removal of a rule list reads the file on stdin.
    if (args[0] == "import") return std::find(args.begin(), args.end(), "--remove") == args.end();
    if (args[0] == "classify") return args.size() >= 2 && OneOf(args[1], {"set", "forget"});
    if (args[0] == "rule") return args.size() >= 3 && args[1] == "import" && args[2] == "-";
    if (args[0] == "config") return args.size() == 4 && args[1] == "set" && args[3] == "--stdin";
    if (args[0] == "custom-group")
        return args.size() == 5 && args[1] == "test" && args[4] == "--stdin";
    if (args[0] != "provider" || args.size() < 4) return false;
    return (OneOf(args[1], {"add", "update"}) && args[3] == "--url-stdin") ||
           (args[1] == "add-node" && args[3] == "-");
}

Result<Output> Run(const std::vector<std::string>& args, std::chrono::milliseconds timeout,
                   int input, int out) {
    int pipe_fds[2];
    if (pipe2(pipe_fds, O_CLOEXEC) != 0) return ErrnoError() << "pipe2";
    unique_fd read_end(pipe_fds[0]);
    unique_fd write_end(pipe_fds[1]);
    unique_fd null_fd(open("/dev/null", O_RDONLY | O_CLOEXEC));
    if (input < 0 && null_fd < 0) return ErrnoError() << "open /dev/null";

    // Everything the child needs is prepared before fork(): after it, only async-signal-safe
    // calls (the daemon is multithreaded).
    std::vector<std::string> argv_storage;
    argv_storage.push_back(kBinary);
    argv_storage.insert(argv_storage.end(), args.begin(), args.end());
    std::vector<char*> argv;
    for (std::string& arg : argv_storage) argv.push_back(arg.data());
    argv.push_back(nullptr);
    // A clean environment: the manager honours MIHOMO_MANAGER_CORE, and its getprop comes from
    // PATH.
    static char kPath[] = "PATH=/system_ext/bin:/system/bin";
    char* envp[] = {kPath, nullptr};
    const int in_fd = input >= 0 ? input : null_fd.get();
    const int out_fd = out >= 0 ? out : write_end.get();

    const pid_t pid = fork();
    if (pid < 0) return ErrnoError() << "fork";
    if (pid == 0) {
        // Own process group, so that a timeout kills the manager's children too.
        setpgid(0, 0);
        if (dup2(in_fd, STDIN_FILENO) < 0 || dup2(out_fd, STDOUT_FILENO) < 0 ||
            dup2(write_end.get(), STDERR_FILENO) < 0) {
            _exit(126);
        }
        // The daemon ignores SIGPIPE; an ignored signal stays ignored across exec.
        struct sigaction dfl = {};
        dfl.sa_handler = SIG_DFL;
        sigaction(SIGPIPE, &dfl, nullptr);
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, nullptr);
        execve(kBinary, argv.data(), envp);
        static constexpr char kMessage[] = "lujarch_proxy: cannot execute the manager\n";
        (void)!write(STDERR_FILENO, kMessage, sizeof(kMessage) - 1);
        _exit(127);
    }
    // Both sides set the group, whichever runs first.
    setpgid(pid, pid);
    write_end.reset();

    const auto deadline = std::chrono::steady_clock::now() + timeout;
    auto remaining_ms = [&] {
        const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now());
        return static_cast<int>(std::max<int64_t>(0, left.count()));
    };

    Output output;
    bool timed_out = false;
    std::string read_error;
    char buf[8192];
    while (true) {
        const int left = remaining_ms();
        if (left == 0) {
            timed_out = true;
            break;
        }
        pollfd p{read_end.get(), POLLIN, 0};
        const int r = TEMP_FAILURE_RETRY(poll(&p, 1, left));
        if (r < 0) {
            read_error = std::string("poll: ") + strerror(errno);
            break;
        }
        if (r == 0) {
            timed_out = true;
            break;
        }
        const ssize_t n = TEMP_FAILURE_RETRY(read(read_end.get(), buf, sizeof(buf)));
        if (n < 0) {
            read_error = std::string("read: ") + strerror(errno);
            break;
        }
        if (n == 0) break;  // every writer is gone
        const size_t room = kMaxOutput - output.text.size();
        const size_t take = std::min(room, static_cast<size_t>(n));
        output.text.append(buf, take);
        // Keep draining past the limit so that the manager never blocks.
        if (take < static_cast<size_t>(n)) output.truncated = true;
    }
    read_end.reset();

    int status = 0;
    while (true) {
        if (timed_out || !read_error.empty()) {
            kill(-pid, SIGKILL);
            TEMP_FAILURE_RETRY(waitpid(pid, &status, 0));
            break;
        }
        const pid_t w = TEMP_FAILURE_RETRY(waitpid(pid, &status, WNOHANG));
        if (w == pid) break;
        if (w < 0) return ErrnoError() << "waitpid";
        if (remaining_ms() == 0) {
            timed_out = true;
            continue;
        }
        std::this_thread::sleep_for(20ms);
    }
    if (timed_out) {
        return Error() << "the manager's " << args[0] << " did not finish within "
                       << timeout.count() / 1000 << " s and was killed";
    }
    if (!read_error.empty()) return Error() << "reading the manager's output: " << read_error;
    if (WIFEXITED(status)) {
        output.code = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        output.code = 128 + WTERMSIG(status);
    }
    return output;
}

}  // namespace lujarch::proxy::manager
