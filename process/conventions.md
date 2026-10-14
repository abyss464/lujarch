# Conventions

One way for each kind of thing. `tools/lujarch check` enforces what can be checked mechanically;
the rest is reviewed against this file. A service started from a script and a daemon that listens
on a socket are listed by `tools/lujarch status` instead of rejected by `check`.

## Names

The system is Lujarch. Every identifier uses `lujarch` and the feature's name; `fuxi` names the
Xiaomi 13 device and nothing else. A feature name's hyphen becomes `_` in snake_case identifiers
(`lujarch_small_windows`), is dropped in packages (`org.lujarch.smallwindows`) and starts a new
word in UpperCamelCase (`LujarchSmallWindows`).

| Kind | Form | Example |
|---|---|---|
| Name shown to users | Lujarch | Lujarch Vault |
| Feature | kebab-case word, also the target file name | `small-windows` |
| Item prefix | the target file's CODE | `WIN` |
| Kotlin and Java packages | `org.lujarch.<feature>[.<part>]` | `org.lujarch.vault` |
| App and library modules | `Lujarch<Feature>[<Part>]`, UpperCamelCase | `LujarchVault` |
| Native modules, binaries, init services | `lujarch_<feature>[_<part>]` | `lujarch_proxy` |
| SELinux domain and types | `lujarch_<feature>[_<part>]`, with `_exec`, `_data_file`, `_service` | `lujarch_sync_data_file` |
| Settings key | `lujarch_<feature>_<key>` | `lujarch_proxy_enabled` |
| System property | `ro.lujarch.<key>` (build facts), `lujarch.<feature>.<key>` (runtime state) | `lujarch.proxy.state` |
| Data directory | `/data/lujarch/<feature>/` | `/data/lujarch/linux/` |
| Metadata directory | `/metadata/lujarch/<feature>/` | `/metadata/lujarch/updates/` |
| Log tag | `lujarch_<feature>` | `lujarch_sync` |
| Patch | `system/features/<feature>/patches/<project>/NNNN-<what>.patch` | `0001-app-info-row.patch` |
| Overlay package | `org.lujarch.<feature>.overlay.<target>` | |
| Release | tag `lujarch-<n>`; artifacts `lujarch-<n>-<commit>` | |

Lujarch's pages in Settings are hosted by one app, `org.lujarch.settings` (module
`LujarchSettings`), built by `core` from the pages each feature contributes. A feature has an app
of its own only when it needs its own process or user id.

## Settings

- Every switch a user sets is declared once, in its feature's `settings.toml`, with its key,
  type, default and the target item it implements.
- Values live in Android's Settings provider under `lujarch_<feature>_<key>`: `Secure` for
  choices that belong to the user, `Global` for device-wide ones.
- Switches set in recovery live in `/metadata/lujarch/safe-boot/`, the one place recovery and
  the system both reach.
- Native services never read user choices from properties or files: the feature's daemon
  observes the setting and applies it. System properties carry build facts and runtime state,
  never a user's choice.
- Data that is not a switch (a proxy configuration, a sync configuration, a database) lives in
  the feature's data directory.

## Services and IPC

- Long-running on-device code is a C++ daemon, started by init, in its own SELinux domain.
  Third-party programs (proxy core, sync engine) are supervised by a Lujarch daemon; no shell
  script runs as a service.
- Each daemon exposes one stable AIDL interface registered with servicemanager. Callers are
  limited by `service_contexts` and checked by caller identity; one method per allowed action.
  No other socket protocols.
- Apps never run as root. Root work goes through a daemon method.
- Custom user ids come from the one AID table in `core`, in the range 5000 to 5999.

## Apps and pages

- Apps are Kotlin. Pages use the stock Settings look (SettingsLib, collapsing toolbar,
  preference lists).
- Page code never runs after its page is gone.
- A page shows a failure where it happened, says what failed and what the user can do, and
  keeps the rest of the page usable.

## Strings

- Every string Lujarch adds exists in English (`values/`), Simplified Chinese
  (`values-zh-rCN/`) and Japanese (`values-ja/`), worded and spaced as target/terms.md says.
- No other locale is written by hand; other languages show the English string.
- Code contains no user-visible text.

## Logging and errors

- Every failure is logged under the feature's tag with what failed and why; nothing is caught
  and dropped.
- Logs never contain secrets.

## SELinux

- Every domain is enforcing. No permissive domains, no `dontaudit` that hides a denial.
- Each feature's policy is in its own `sepolicy/`. Rules come from observed denials.

## Languages and style

| Layer | Language | Style |
|---|---|---|
| Apps | Kotlin | AOSP Kotlin style |
| Changes to upstream files | the file's own language | the file's own style |
| Daemons, native libraries | C++20 | AOSP `.clang-format` |
| Build | Soong (`Android.bp`); Make only for product and board variables | AOSP |
| Host tools | Python 3, standard library only | PEP 8 |
| Documents | Markdown, English (README.md: Chinese and English) | |

Files are UTF-8 with LF line ends and a final newline (`.editorconfig`).

## Comments

English, only where an edit would otherwise break a constraint the code cannot show. No history,
no reasons for choices, no references to discussions.

## Upstream

Where upstream already does what a target item asks, upstream's mechanism is used. Patches carry
only the difference between upstream and the target.
