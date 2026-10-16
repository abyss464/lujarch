# Layout

What lives where in this repository, and where things that are not here live.

## The three kinds of content

| Kind | What | Where |
|---|---|---|
| In the system | Everything the image contains: the generic features, the device layer of each supported device, and all preinstalled apps | this repository |
| Outside the system | Test-only components (probes, harnesses), build and release tooling | this repository, under `test/`, `release/` and `tools/`; never in an image |
| The owner's own | Personal settings and data on the owner's phone; the owner's computer (paths, key locations, mirrors, remote access); rules for the owner's phone; delivery to it; backups; one-time migrations | the owner's repository (`lujarch-owner`); personal settings themselves live only on the phone |

The image ships product defaults only. A setting a person chooses is made on the phone, in the
system's own UI, and is never a default in this repository.

## Top level

| Path | What |
|---|---|
| `README.md` | What Lujarch is and how the repository is used (Chinese and English) |
| `AGENTS.md` | Rules for agents (`CLAUDE.md` is a link to it) |
| `target/` | The target system (process/target.md) |
| `process/` | How the repository works: this file, target format, conventions, test flow, release flow |
| `system/features/<feature>/` | One feature's implementation |
| `system/device/<device>/` | One device's layer: `fuxi` (Xiaomi 13) and `vm` (the virtual device) |
| `system/order` | The order in which every patch is applied |
| `lock.toml` | Every pinned input of a build |
| `upstream.xml` | Every upstream project pinned to a commit (a repo manifest) |
| `test/` | The test flow: host checks, virtual-device tests, phone tests (process/test-flow.md) |
| `release/` | The release flow (process/release-flow.md) |
| `tools/lujarch` | The command line for everything above |
| `tools/patches/<project>/` | Patches to the build system itself, which reach no image; applied before all others |

## Features

A feature is one capability as the user knows it. Its name is a kebab-case word used for the
directory, the target file and every identifier (process/conventions.md).

```
system/features/<feature>/
├── feature.toml          prefix (the target CODE), devices it applies to, components it builds,
│                         prebuilts it takes, files its scripts generate, sources its programs
│                         download, apps whose texts it renames
├── patches/<project>/    NNNN-<what>.patch, changes to the upstream project at that tree path
├── components/<name>/    NNNN-<what>.patch, changes to a component's source
├── src/                  what the feature adds: apps, daemons, libraries, configs, overlays
├── sepolicy/             the feature's SELinux policy
├── settings.toml         the feature's switches: key, type, default, the item each implements
├── product.mk            product variables (and board.mk for board variables, if any)
└── tests/                tests; each names the items it verifies
```

- A feature's pages in Settings are the Kotlin library `Lujarch<Feature>Settings` in its
  `src/settings/`, with its own manifest and resources; the tools build every such library into
  Lujarch Settings. Its manifest registers what it adds to App info and search as application
  meta-data `org.lujarch.settings.contribution.<feature>` naming its `Contribution` class, built
  against `core`'s `LujarchSettingsContribution`. Its pages are built on `core`'s page framework,
  the library `LujarchSettingsPages`.
- `core` is the feature of the rules that hold everywhere (target/core.md): the settings
  registry and its mirror, the page framework of Lujarch's pages in Settings with the App info
  rows and search entries features contribute, the daemon base, the AID table, shared SELinux
  macros.
- A component is a program a feature builds outside the tree with the host's Go or the tree's own
  Rust toolchain, and the host's NDK: `feature.toml` names it under `[components.<name>]` with its
  `build` (`go` or `cargo`), the `module` it becomes and how it is built; `lock.toml` pins its
  commit; the host configuration's `[sources]` says where its repository is on the computer.
  The tools build it once for each pin, patch series and toolchain, for every device
  architecture, offline (Go modules from the computer's module cache, crates from Cargo's), and
  stage it at `vendor/extra/features/<feature>/prebuilt/<arch>/<module>` for the feature's
  `Android.bp`.
- An app a feature builds from its source is a component whose `build` is `gradle` (one APK for
  every architecture, staged under `common`) or `flutter` (one APK for each architecture; its
  pin also names the Flutter SDK's `flutter` commit), with the `task` or `args` that build it
  and the `output` APK, staged as `<module>.apk`; `key` names the key in the keys directory a
  release signs it with, the release key otherwise. Only `tools/lujarch fetch` builds apps,
  since Gradle and Flutter download what a build needs; every other step stages the APK fetch
  kept for the pin, patch series and recipe.
- A prebuilt is a program or data a feature takes as its upstream releases it, where the release
  is the only build of it that matches the pinned source: `feature.toml` names it under
  `[prebuilts.<name>]` with the `module` it becomes; `lock.toml` pins the `url` and `sha256` of
  each architecture it exists for, or of `common` when it has none. `tools/lujarch fetch`
  downloads what the host lacks into the host's `prebuilts` path, and the tools stage it like a
  component.
- A generated file is `[generated.<name>]` in `feature.toml`: a `script` of the feature's own
  (Python, run as `script OUT WORKDIR`) and the `output` it makes, a file or a directory, staged at
  `vendor/extra/features/<feature>/generated/<output>` for the feature's `Android.bp`. The tools
  run it at every staging; one that needs tools beyond Python names them under `fetch`, and only
  `tools/lujarch fetch` runs it, once for each state of the script, the modules of its directory
  it imports and those tools' versions; every other step stages what fetch made.
- A download is a source a feature's programs fetch on the device, such as a release tarball
  Linux builds from: `feature.toml` names it under `[downloads.<name>]`; `lock.toml` pins its
  `url`, `version` and `sha256`. The tools stage the pins of a feature's downloads as
  `vendor/extra/features/<feature>/downloads`, `<name>.<key>: <value>` lines, for the feature's
  `Android.bp`.
- A rename is `[renames.<name>]` in `feature.toml`: an app's `package` and its resource
  directories `res` in the tree. The tools generate a static overlay of the app in which every
  string of those directories whose text names LineageOS says Lujarch in its place, in every
  locale, except the strings named under `keep`; a `keep` entry that names no such string stops
  the staging.
- A feature that applies to some devices only says so in `feature.toml`; the build of another
  device leaves it out.
- A feature's patches cover every upstream project it changes, including device projects
  (a kernel change for each device kernel it needs).

## Devices

```
system/device/<device>/
├── device.toml           the device's facts: names, display, update scheme
├── patches/<project>/    changes to upstream projects the device needs
├── Android.bp            the device's modules, prebuilts from its stock release included
├── product.mk            product variables (and board.mk for board variables, if any)
├── sepolicy/             the device's own SELinux policy
└── src/                  device-only sources and configuration
```

- `fuxi`: the Xiaomi 13. Its firmware and vendor files are those of LineageOS' device
  projects in `upstream.xml`; what they lack (Xiaomi apps, missing vendor files) comes from the
  stock release of that firmware, pinned in `lock.toml`.
- `vm`: the virtual device of the test flow, for every test that does not depend on the Xiaomi
  13's hardware. Its display copies the phone's.
- Build variables derived from `device.toml` are generated by the tools, never written by hand.

## In the tree

`tools/lujarch patch`, and every build, stages each feature's and device's directory without its
`patches/` and `tests/` at `vendor/extra/<kind>/<name>/` in the tree, each file a device takes
from its stock release at `vendor/extra/device/<device>/stock/<path in the release>`, and generates
`vendor/extra/product.mk` and `vendor/extra/BoardConfigExtra.mk`, the two files LineageOS
includes from there. They include every feature's `product.mk` and `board.mk`, `core` first, a
feature limited to some devices only for their products, and each device's own for its product;
`product.mk` first sets `LUJARCH_ARCH`, the product's architecture from its `device.toml`.
Makefiles and `Android.bp` files name the staged paths. `vendor/extra` is never edited in the tree.

## Patches

`tools/patches/` applies first. `system/order` lists every other directory that has patches, one
per line, relative to `system/`
(`device/fuxi`, `features/core`, `features/channels`, ...), in application order. Patches of
one directory apply in path order. A patch is a `git diff` of one upstream project with full
blob ids.

- In the tree, every patch is one commit on top of its project's pinned commit, made by
  `tools/lujarch patch` in that order. The patch files are the source; the tool rebuilds the
  commits whenever they differ from the files, and counts no commit it did not make.
- An upstream file is changed in the tree, then `tools/lujarch save <entry>` folds the change
  into the entry's patch for that project (`tools` for `tools/patches/`; `--name` starts a new
  one, `--patch` picks one of several) and rewrites the patch file. Later patches of the project are rebased onto it; a later
  patch's file is rewritten only when it no longer reproduces its commit.
- A patch that no longer applies is merged three ways where the project's history holds the
  blobs it was made from, otherwise applied with rejected hunks. Its conflicts are resolved in
  the tree, and `tools/lujarch patch` saves the resolved patch and goes on.
- A build starts only from a tree that is exactly `upstream.xml` plus the patches.

## Pins

- `lock.toml`: the upstream branch and AOSP tag, the build variant, each device's stock
  release, and every source or prebuilt a feature fetches (version, commit or URL, and hash):
  a component is `[components.<name>]` with its `commit` (and `version` and `url` where it has
  them); a prebuilt is `[prebuilts.<name>.<arch>]` with its `url` and `sha256`; a download is
  `[downloads.<name>]` with its `url`, `version` and `sha256`.
  A stock release is `[stock.<device>]` with `release`, `urls` (mirrors of one package) and
  `sha256`; `[stock.<device>.files]` holds the sha256 of every file the device takes from it.
  `tools/lujarch stock <device>` downloads, checks and unpacks the package under the host's
  `stock` path; `--add` pins files from it.
- `upstream.xml`: the repo manifest with every upstream project at its commit. The same commit
  of this repository always builds the same system.
- Changing a pin is a commit that goes through the test flow like any other change.

## Host configuration

The tools read the computer's own settings from `$LUJARCH_HOST`, or from
`$XDG_CONFIG_HOME/lujarch/host.toml` (`~/.config/lujarch/host.toml`). Nothing host-specific is in
this repository. A relative path in it is relative to the file's real location.

```toml
[paths]
tree = "..."          # the LineageOS source tree
out = "..."           # build outputs, test results, releases
keys = "..."          # signing keys; only their location, never their content
ccache = "..."
stock = "..."         # unpacked stock releases
ndk = "..."           # Android NDK for components built outside the tree
go = "..."            # Go installation (GOROOT) for components built outside the tree
prebuilts = "..."     # pinned prebuilts, each named by its sha256
jdk = "..."           # JDK for apps built from source (JAVA_HOME)
android_sdk = "..."   # Android SDK for apps built from source
gradle = "..."        # Gradle's cache for apps built from source (GRADLE_USER_HOME)
pub = "..."           # Dart's package cache for Flutter apps (PUB_CACHE)

[sources]
mihomo = "..."          # where each component's repository is; its commit is in lock.toml
mihomo-manager = "..."

[computer]
cpus = "..."          # the CPUs builds and the virtual device run on; the others stay free

[sync]
jobs = 12
gitconfig = "..."     # git configuration used for repo sync (mirrors)

[build]
jobs = 0              # 0: derived from memory
memory_high = ""      # empty: derived from free memory
memory_max = ""       # empty: derived from free memory; a build waits until enough is free
highmem_jobs = 3
lock = "..."          # one lock for every Android build on the computer

[vm]
cpus = 4              # the virtual device's CPUs and memory
memory_mb = 8192
```
