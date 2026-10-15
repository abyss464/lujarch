# Delivery

How a release reaches the owner's phone. The system's release flow produces the release
(`lujarch-<n>-<commit>`: the OTA package, the images, their hashes). Delivery brings it to the
phone and confirms that it runs.

## Path

1. The release's OTA package is copied to the phone: over USB, or over the owner's remote path in
   parts of `delivery.push_part` bytes with `delivery.push_pause` seconds between them
   (`host.toml`).
2. It is installed by the phone's own System updates as a local update. A script may start that
   install; it never applies the update by another path.
3. The phone restarts into the new build.
4. The delivery check, read-only: the running slot and build are the release's; SELinux is
   enforcing; no system process has crashed since the start; Lujarch's services run. The result is
   shown to the owner.
5. The owner confirms the build in System updates, or the phone confirms it by itself after the
   time the owner chose there. Agents never confirm in the owner's place. Until then, System
   updates can return to the previous build of the same Android version.

## A new Android version

1. The owner agrees to the login risk (`AGENTS.md`).
2. A full backup (`process/backup.md`).
3. Install as above.
4. The one-time migration of the release, if there is one (`process/migration.md`), runs right
   after the first start, before the phone is used.
5. Phone checks: the items of the system's target with `Verify: phone`. Checks that need the
   screen are done with the owner present.

Once a new Android version has started, the phone returns to the previous one only by a wipe, the
previous release installed from scratch, and a restore of the backup.

## Rescue

- A build that does not start returns to the previous slot by itself after its retries. By hand:
  `adb reboot bootloader`, `fastboot set_active <previous slot>`, `fastboot reboot`.
- When the running system cannot install a release, recovery installs it by sideload.
