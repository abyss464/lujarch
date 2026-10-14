# Migration to Lujarch on Android 17

The one-time move of the owner's phone from the previous system (Lujarch on LineageOS 23.2,
Android 16, with `fuxios` identifiers) to Lujarch on LineageOS 24.0 (Android 17). It runs once.
Nothing of it enters the system: the system's repository and its images hold no migration step
and no code that reads the previous system's names or formats. After the move, this file and
`migrate/` are deleted.

## Order

1. The first Android 17 release holds every part of the previous system the owner chose to keep
   or simplify, except the parts the owner left until after the move.
2. The owner agrees to the login risk (`AGENTS.md`).
3. A full backup (`process/backup.md`).
4. The release is installed through System updates (`process/delivery.md`).
5. Right after the first start, before the phone is used, the migration runs as root over adb.
6. Delivery's phone checks.
7. The previous system's leftovers are removed.

There is no way back after step 4 except a wipe, the previous release installed from scratch, and
a restore of the backup.

## Rules

- The owner's settings and content move. What the system produced on its own (access history,
  logs, statistics, caches, learned states) does not.
- Inputs: the backup taken before the install and the data the previous system left on the phone.
- Each item goes where the release's feature keeps it. The layout inside `/data/lujarch/<feature>/`
  and the settings keys are the system repository's; this file names only the feature.
- A dry run lists every step first. Every step is logged. A step that fails stops the migration and
  names what is left. Running it again continues where it stopped.

## Lujarch's own apps

The new version removes, at its first start, the data of system apps whose package no longer
exists. Lujarch's apps change package, so their data comes from the backup.

| Previous package | Lujarch | Data |
|---|---|---|
| `org.fuxios.settings` | `org.lujarch.settings` | from the backup |
| `org.fuxios.channels` | `org.lujarch.channels` | from the backup |
| `org.fuxios.vault` | `org.lujarch.vault` | from the backup, except its Keystore key: the owner enters the vault's master password once and sets up unlocking with the phone again |
| `org.fuxios.terminal` | `org.lujarch.linux` | from the backup |
| `org.fuxios.fileaccess` | `org.lujarch.files` | none |
| `org.fuxios.branding.*`, `org.fuxios.adopted.*`, `org.fuxios.*.overlay`, `org.fuxios.miuicamera.icon`, `org.fuxios.linux.overlay` | `org.lujarch.<feature>.overlay.*` | none |
| `org.fuxios.rcprobe`, `org.fuxios.channels.probe`, `org.fuxios.probe.dozesuspend`, `org.fuxios.releasetest` | none: test components are never in an image | removed if present |

## Data on the phone

Moved in place (a rename within `/data`), then given their new SELinux labels.

| Previous | Lujarch feature | Notes |
|---|---|---|
| `/data/fuxios/mihomo` | proxy | configuration, last good copy, secret, geodata, manager data |
| `/data/fuxios/syncthing`, `/data/fuxios/syncthing-staging` | sync | paths inside the sync configuration are rewritten; the sync identity stays the same |
| `/data/fuxios/arch` | linux | the Linux system |
| `/data/fuxios/linux`, `/data/fuxios/console.conf` | linux | the console user and the Linux settings |
| `/data/fuxios/root` | root | the root shell's home |
| `/data/fuxios/fuxiosd/messages-learned.json` | none | learned state; Messages only learns again |
| `/data/fuxios/run`, `/data/fuxios/migrate-uid`, `/data/fuxios/arch.old.*`, `/data/adb/lujarch-boot` | none | removed with the leftovers |

## Switches

Values the owner set in the previous system become the values of the switches that own them.

| Previous | Lujarch feature | Meaning |
|---|---|---|
| `persist.fuxios.mihomo.enable` | proxy | the proxy is on |
| `persist.fuxios.syncthing.enable` | sync | sync is on |
| `persist.fuxios.syncthing.wifi_only` | sync | sync only on Wi-Fi |
| `persist.fuxios.syncthing.charging_only` | sync | sync only while charging |
| `persist.fuxios.console.enable` | linux | the Linux system starts with the phone |
| `persist.fuxios.cpu.idle_floor` | battery | the lowest CPU frequency when idle |
| `persist.fuxios.update.auto_confirm_minutes`, `persist.fuxios.update.auto_confirm_days` | updates | Confirm automatically |
| Settings `lujarch_history_days` | channels | the length of the access history |
| Settings `lujarch_battery_full_history` | battery | the full battery history |
| `persist.fuxios.logpersist.defaulted` | none | a marker; the log switch is Android's own and keeps its value |

## Channels and root

- Android stores each app's operation modes by operation number. Android 17 uses 170 to 178 for
  operations of its own, and the new system numbers Lujarch's channels from 179, so at the first
  start the previous system's modes for 170 to 179 are read as modes of other operations. For
  every package, the modes of operations 170 to 188 are reset to their defaults, then Lujarch's
  channel modes are written again from the backup by operation name.
- The previous system's discrete access history is deleted: its operation numbers belong to other
  operations on Android 17.
- Install verification records, channel answers and Lujarch Channels' other data come back with
  the app's data.
- Per-app network choices are Android's own per-app policy and stay as they are.
- KernelSU's allowlist stays. A grant that named a previous Lujarch package is given to the new
  package.

## User ids

When the release's id table gives the sync service or the Linux user (previously 5100 and 5200)
another number, the files they own are given to the new id.

## Porting the previous system's code

Each part of the previous system is ported, simplified, replaced by Android's own, left until after
the move, or dropped, as the owner decided for that part. Code ported for the first release meets
the system's conventions in its names (no `fuxios` identifier) and keeps every user choice in the
Settings provider. A service started from a script and a daemon that listens on a socket may stay
as they are; `tools/lujarch status` lists them.

## Afterwards

- The vault asks for its master password once.
- Every feature is checked against its items in the system's target.
- The previous system's leftovers are removed, including its records under `/metadata/lujarch/`;
  then this file and `migrate/` are deleted.
