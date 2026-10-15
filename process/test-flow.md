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

The virtual device has no Xiaomi hardware, no Qualcomm TEE, no bootloader of its own, no Google
apps (they exist only for arm64), no arm64-only programs, and no real performance or battery.
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
- Results are written per commit to `<out>/test/<commit>/results.json`; `tools/lujarch status`
  reads them.
- A commit has passed the test flow when level 1 passes and the tests of the items it changes
  pass at the level that answers them.
