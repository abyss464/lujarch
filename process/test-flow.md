# Test flow

The test flow shows that a commit meets the target before anything reaches a phone. It is
separate from the release flow (process/release-flow.md) and shares nothing with it but the
source.

## Who and where

- Agents run it at any time, without approval.
- It runs on the computer and on virtual devices. No personal phone takes part in it.
- Builds are signed with the public AOSP test keys. A test build cannot be installed on a phone
  that runs a release: its update and its system apps are refused for their signature.
- Its outputs stay on the computer, under the host's output directory.

## Levels

### 1. Host

- `tools/lujarch check`.
- The tree is synced to `upstream.xml` and patched per `system/order`.
- Both devices build: `fuxi` to compilation and static checks, `vm` to a bootable image.
- Static checks: the SELinux policy with its neverallow rules, VINTF compatibility, the
  privileged-permission allowlists, every pin in `lock.toml` matching its hash.
- Host tests of each feature.

### 2. Virtual device

The virtual device is Cuttlefish for x86_64, built from the same commit as `vm`. It must provide:

- virtual A/B updates with snapshots, merge and rollback;
- a userdata checkpoint;
- a KeyMint that binds keys to the OS version and patch level and supports rollback resistance;
- a simulated modem (calls, SMS, mobile data);
- a kernel built from source with the features' kernel changes;
- a screen the owner can watch and use from a browser;
- several instances at once.

It runs in Docker on the host with KVM. Its screen copies the phone's resolution, density and
refresh rate from `system/device/fuxi/device.toml`.

- Every item with `Verify: vm` is verified here.
- Test components (probes, harness apps) are installed into the virtual device when a test runs.
  No test code is ever part of an image.
- When an item concerns look or feel, the owner looks at it on the virtual device's screen.

The virtual device has no Xiaomi hardware, no Qualcomm TEE, no bootloader of its own, no
Google apps (they exist only for arm64) and no real performance or battery. Items about those
carry `Verify: phone` and are verified during delivery on the phone (the owner's repository).

### 3. Upgrade rehearsal

Run whenever a change moves to a new Android version or changes where or how data is stored:

1. A virtual device runs the previous system, filled with data shaped like a real user's (no
   personal secrets): settings of every feature, app data, keys in Keystore, a Linux system,
   proxy and sync configuration.
2. The new system is installed. A new Android version starts as a trial (target/updates.md).
3. Return to the previous system. Every setting, file and key is as before the install.
4. Install again, keep the new version, run the one-time migration if there is one, and
   verify every feature against its items.

## Tests

- Each test names the items it verifies (process/target.md). One item may have several tests.
- Results are written per commit to `<out>/test/<commit>/results.json`; `tools/lujarch status`
  reads them.
- A commit has passed the test flow when level 1 passes and every `Verify: vm` item is verified
  on that commit; a new Android version also needs level 3.
