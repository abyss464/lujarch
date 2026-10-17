# Test flow

The test flow shows that a change works before it becomes part of a release. It is separate from
the release flow (process/release-flow.md) and shares nothing with it but the source.

Each test runs at the first level that can answer it: the computer, then the virtual device, then
a Xiaomi 13. Only what depends on the Xiaomi 13's hardware, and module tests, reach a phone.

## Levels

### 1. Computer

- `tools/lujarch check`.
- The tree is synced to `upstream.xml` and patched per `system/order`.
- Both devices build: `fuxi` to compilation and static checks, `vm` to a bootable image.
- Static checks: the SELinux policy with its neverallow rules, VINTF compatibility, the
  privileged-permission allowlists, every pin in `lock.toml` matching its hash.
- Host tests of each feature.

### 2. Virtual device

The virtual device is Cuttlefish for x86_64, built from the same commit as `vm`, running on the
computer under KVM. Its screen copies the phone's resolution, density and refresh rate from
`system/device/fuxi/device.toml`, and the owner can watch and use it from a browser.

- Every test that does not depend on the Xiaomi 13's hardware runs here.
- Test components (probes, harness apps) are installed into the virtual device when a test runs.
  No test code is ever part of an image.
- `tools/lujarch vm start` boots a copy of the images of the `vm` build (so that builds go on while
  it runs) with an empty data partition of the host's `vm.data_mb` and leaves it ready
  for tests: set up, awake, Developer options and Rooted debugging on, adb as root, on its Wi-Fi
  network. A module build installs files without packing them into the images. While the images
  lack files installed since the last full build, `vm start` names them and stops:
  `tools/lujarch build vm` packs them, and `vm start --packed` boots the images as they are.
  `tools/lujarch vm status` counts those files. A system server that keeps dying stops `vm start`
  and every wait for the device to come back, with the crash that ended its first start.
- `tools/lujarch vm sync <module>...` copies what the named modules install (with the modules
  they require) into the running virtual device after `tools/lujarch build vm <module>...`;
  `tools/lujarch vm sync` alone copies the files the images lack, except those of the boot
  images' ramdisks, which only a full build packs. It copies only the files the device lacks or
  holds with other content, keeps the device's data, and restarts what reads the copied files,
  naming each restart: the init services whose modules install or require them; the framework
  (zygote, system_server and the apps) for apps, overlays, permissions and the system
  configuration; the whole device for what init and the kernel read only when it starts (init
  scripts, the SELinux policy and file contexts, VINTF, the framework's jars, properties), for
  files no known reader reads, and when a process still runs a replaced program. A whole-device
  restart parses every package's manifest again, and after any copy Settings search builds its
  index again, as both happen after an update. The boot image
  (`dex_bootjars`) and the boot classpath jars of `system` are built and copied as one unit:
  naming one of them brings all. Code compiled ahead of time against an earlier boot image and
  not copied with the unit runs without that compiled code. The SELinux policy of `system_ext`
  is the module `selinux_policy_system_ext`. A framework resource package (an `.apk` in a
  partition's `framework/`) whose build gives one of the device's resource ids to another
  resource is not copied: the apps and the system server hold those ids as compiled, so it comes
  only with a full build and a fresh `vm start`.
- `tools/lujarch vm screenshot [PATH]` writes what the virtual device's display shows, as the
  browser view shows it, to PATH (`.png`, `.jpg` or `.webp`; `<out>/vm/screenshots/` by
  default); `--frames N --interval S` takes N frames S seconds apart, for animations.
  `adb exec-out screencap` renders the layers itself: it leaves out SystemUI's screen decorations
  and the boot animation, and shows the dimmed background behind a dialog, which the display
  shows black.

The virtual device has no Xiaomi hardware, no Qualcomm TEE, no bootloader of its own, no Google
apps, no arm64-only programs, and no real performance or battery.
Items about those carry `Verify: phone`.

### 3. Phone

A Xiaomi 13 is used only for:

- tests that depend on its hardware (items with `Verify: phone`);
- module tests: one app, daemon or system component of the commit under test, installed into
  the running system without a system update.

Rules:

- A system build is installed only through the phone's own System updates. A script may copy the
  package to the phone and start that install; it never applies the update by another path.
- Everything that goes to a phone, a module or a system build, is signed with the release keys:
  the phone accepts an update of a system app or of the system only with the signature it already
  has.
- On the owner's phone, the owner's repository's rules decide what needs the owner's approval.

Linux's desktop is built on this computer, not on the phone's small cores: `tools/lujarch desktop`
streams the headers and libraries of the phone's Linux (`sysroot`), builds KWin with the Lujarch
backend from the recipe and its pin in lock.toml (`kwin`), and builds the Plasma components the
distribution has not built yet (`plasma VERSION NAME...`, packaged with release 0 so that the
distribution's own replaces them); `--install` installs the packages in the phone's Linux and
records the recipe's state, so that Install the desktop finds them current.

## Keys

- Builds for the virtual device are signed with the public AOSP test keys. A test build cannot be
  installed on a phone that runs a release: its update and its system apps are refused for their
  signature.
- Builds and modules for a phone are signed with the release keys, found at the host
  configuration's key location. Keys are never read, printed or copied by anything but the
  signing step.

## Tests

- Each test names the items it verifies (process/target.md). One item may have several tests.
- Tests may be the feature's own scripts and programs; a test that needs to install a system
  build uses the System updates install path above.
- A test is a Python script in its feature's or device's `tests/`, whose text says
  `verifies: <ITEM>, ...` (and `timeout: <seconds>` when it needs more than 900). It uses the
  device as a person does, through the library `test/lib/lujarch_test.py`: taps on what the
  screen shows, the system's own pages, screenshots of the display; it exits 0 when every check
  holds. Files whose name starts with `_` are a feature's helpers, not tests.
- Probes, the apps a test installs to act as an app would, are built from `test/probes/<name>/`
  with the SDK's own tools when a test first needs them, and installed only while it runs.
- `tools/lujarch test [ITEM | FEATURE | TEST ...]` runs the tests on the virtual device, each
  from the unlocked home screen, and keeps each test's log and screenshots under
  `<out>/test/<commit>/<feature>/<test>/`. A test of a `Verify: phone` item is not run there.
- Results are written per commit to `<out>/test/<commit>/results.json`, an item passing when
  every test that names it passed; `tools/lujarch status` reads them. They are written only for a
  clean worktree, run on a device that `vm start` booted from that commit's packed images and
  into which nothing was copied since; other runs keep their output under `<out>/test/work/`.
- `tools/lujarch test --script FILE [ARG ...]` runs any Python file on the virtual device as a
  test runs (its environment, the library, the unlocked home screen), in the folder
  `<out>/test/work/scratch/<name>/`, its output on the terminal, and records nothing.
- `LUJARCH_VM=<n>` runs a second (third...) virtual device beside the first: every `vm` and
  `test` command then addresses instance n, with its own container, ports, runtime directory
  and unrecorded output (`<out>/test/work-<n>/`).
- A commit has passed the test flow when level 1 passes and the tests of the items it changes
  pass at the level that answers them.
