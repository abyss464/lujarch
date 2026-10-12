# Backup

The owner's personal settings and data that live in the system, exported from the phone to the
computer and restored from there.

## What is exported

- Lujarch's feature data: the proxy configuration with its last good copy, its secrets and the
  manager's data; the sync configuration and identity; the Linux system's settings and the root
  shell's home; the vault's settings (the vault databases themselves live in synced folders).
- The data of Lujarch's own apps.
- Choices the owner made that the system keeps outside Lujarch's data: per-app channel modes and
  answers, per-app network choices, root grants, the values of Lujarch's switches in Settings, the
  System updates choices.
- The Linux system itself only when the owner asks (it is large and stays on the phone).

Not exported: what the system produced on its own (access history, logs, statistics, caches,
learned states).

## Where

`<backup.dir>/<date>-<build>/` on the computer (`host.toml`), readable by the owner only. Never in
git, never printed, never copied anywhere else.

## When

Before every release on a new Android version, before a one-time migration, and whenever the owner
asks.

## How

- Over adb with root. Each item is copied with its owner, mode and SELinux context recorded, and a
  manifest lists every item with its hash.
- A restore writes each item where the running release's feature keeps it, sets its owner and mode,
  and relabels it.
