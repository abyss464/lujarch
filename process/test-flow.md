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
- One build runs on the computer at a time. Requests of `tools/lujarch build` that wait for it
  are built together when it ends: one build of every module they name (all of the device when
  one of them names none), which starts after each of them was made and whose result each of
  them ends with.
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
  `tools/lujarch vm sync` alone brings the running device to the `vm` build as it is: it copies
  the files that the images the device booted lack or hold with other content, whether module
  builds installed them or a full build packed them since, and removes the files the build no
  longer packs. A property file that differs from the device's properties only in when the
  build ran is left as it is; a change of the boot images' ramdisks comes only with a full build
  and a new `vm start`. It copies only the files the device lacks or
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
  resource is not copied after a module build: the apps and the system server hold those ids as
  compiled. It is copied when a full build packed it since the device started, with the rest of
  that build, whose apps hold its ids, and the device restarts.
- `tools/lujarch vm screenshot [PATH]` writes what the virtual device's display shows, as the
  browser view shows it, to PATH (`.png`, `.jpg` or `.webp`; `<out>/vm/screenshots/` by
  default), turned as the screen is turned at that frame; `--frames N --interval S` takes N
  frames S seconds apart, for animations, and `--sheet` adds PATH-sheet, the frames side by side
  at a quarter size.
  `adb exec-out screencap` renders the layers itself: it leaves out SystemUI's screen decorations
  and the boot animation, and shows the dimmed background behind a dialog, which the display
  shows black.
- `tools/lujarch vm swipe X1 Y1 X2 Y2` moves one finger across the screen, in its pixels as it
  is turned now: `--press S` keeps it still where it goes down (a long press), `--move S` moves
  it, `--hold S` rests it at the end before the lift. `adb shell input swipe` lifts the finger
  while it still moves, so what waits for a finger at rest, such as Recents from the gesture
  bar, never sees one. The test library's drags are made by it.

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
backend (`kwin`) and Plasma Mobile with the phone's landscape layout (`plasma-mobile`) from the
recipe and their pins in lock.toml, and builds the Plasma components the
distribution has not built yet (`plasma VERSION NAME...`, packaged with release 0 so that the
distribution's own replaces them); `--install` installs the packages in the phone's Linux and
records the recipe's state, so that Install the desktop finds them current.

## Keys

- Builds for the virtual device are signed with the public AOSP test keys. A test build cannot be
  installed on a phone that runs a release: its update and its system apps are refused for their
  signature.
- Builds and modules for a phone are signed with the release keys, found at the host
  configuration's key location. Keys are never read, printed or copied by anything but the
  signing step (`release/sign.py`).
- `tools/lujarch build fuxi <module>...` signs the APKs the modules install with the key a release
  gives each, by the last full build's apkcerts.txt, into `<out>/modules/fuxi/`; a module test
  installs them from there as an app update.

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
