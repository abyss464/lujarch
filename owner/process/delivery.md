# Delivery

How a release reaches the owner's phone. The system repository's release flow produces the release
(`lujarch-<n>-<commit>`: the OTA package, the images, their hashes). Delivery brings it to the phone
and confirms that it runs. Delivery never handles a test build.

## Path

1. The release's OTA package is copied to the phone: over USB, or over the owner's remote path in
   parts of `delivery.push_part` bytes with `delivery.push_pause` seconds between them
   (`host.toml`).
2. It is installed by the phone's own System updates as a local install. This is the only install
   path.
3. The phone restarts into the new build.
4. The delivery check, read-only: the running slot and build are the release's; SELinux is
   enforcing; no system process has crashed since the start; Lujarch's services run. The result is
   shown to the owner.
5. The owner confirms the build in System updates, or the phone confirms it by itself after the
   time the owner chose there. Agents never confirm in the owner's place.

## A new Android version

A release on a new Android version starts on the phone as a trial (the system's
`target/updates.md`):

1. The owner agrees to the login risk (`AGENTS.md`).
2. A full backup (`process/backup.md`).
3. Install as above. The new version starts as a trial, with apps offline.
4. Phone checks: every item of the system's target with `Verify: phone`. Checks that need the screen
   are done with the owner present.
5. The one-time migration of the release, if it has one (`process/migration.md`), runs inside the
   trial, so a return discards it as well.
6. The owner keeps the new version, or returns to the previous one with everything as it was before
   the install.

## Rescue

- A build that does not start returns to the previous slot by itself after its retries. By hand:
  `adb reboot bootloader`, `fastboot set_active <previous slot>`, `fastboot reboot`.
- When the running system cannot install a release, recovery installs it by sideload.
