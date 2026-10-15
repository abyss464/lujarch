# Lujarch owner: rules for agents

This repository holds everything about the owner's own phone and computer for Lujarch. The system
itself is in `~/Code/apps/lujarch`; read its `AGENTS.md` for the system's rules. Read `README.md`,
this file, and the `process/` document of the work at hand.

| Document | What it governs |
|---|---|
| `host.toml` | The computer's paths, key location, sync and build resources (linked as `~/.config/lujarch/host.toml`) |
| `sync.gitconfig` | Git settings used for repo sync (mirrors) |
| `process/delivery.md` | Bringing a release to the owner's phone |
| `process/backup.md` | Exporting and restoring the owner's personal settings and data |
| `process/migration.md` | The one-time move from the previous system to Lujarch on Android 17 |

The principles of the system apply here too: only the target, one source of truth, one way for
each thing, no decision records.

## The phone

The owner's Xiaomi 13 is in daily use.

- Read-only inspection over adb is always allowed.
- The phone takes part in the test flow only for tests that depend on its hardware and for module
  tests (the system's `process/test-flow.md`). Everything else is tested on the computer and on
  virtual devices.
- A system build is installed only through the phone's own System updates. A script may copy the
  package to the phone and start that install; nothing applies an update by another path.
- While the phone is connected, these need no further approval: installing a build through System
  updates (which restarts the phone), module installs for module tests (an app update, a system
  component mounted over its installed copy), restarts, Wi-Fi, mobile data and airplane mode
  toggles, the tests above and the temporary settings they restore, and UI input and screenshots
  those tests need. Say what is being done when it starts.
- A build that can make apps see a different device is installed only after the owner agrees,
  told which apps may ask to log in again. Such changes: the Android version; the model, brand,
  device, manufacturer or product name; fingerprint parts other than the build number; the build
  type; device identifiers (serial, IMEI, MAC, Android ID); app signing certificates or user ids;
  a Keystore or attestation change; the defaults of the identifier channels.
- Never use the emergency dialer or any emergency-call surface. A test that needs an unlocked
  phone asks the owner to unlock it and runs over an ordinary app.
- Ask first: anything that wipes or deletes the owner's data, locking or unlocking the
  bootloader, root grants, persistent settings that are not restored, security or account
  settings, confirming or rolling back a build in the owner's place, and anything that cannot be
  undone.
- A build that does not start: `process/delivery.md`, Rescue.

## The computer

- `host.toml` is the computer's only configuration for Lujarch's tools.
- The computer's own proxy (`~/.config/mihomo*` and its service) is off-limits.
- Builds run in a memory-capped scope on the CPUs `host.toml` gives them.

## Secrets

Never read, printed, committed or copied: signing keys, proxy credentials and subscription
addresses, controller secrets, sync identities, vault databases and key files. Backups that
contain them stay in the backup directory of `host.toml`, readable by the owner only.

## Documents

English, except `README.md` (Chinese and English).
