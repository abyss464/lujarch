# Sync

Sync keeps folders on the phone in step with the owner's other devices, peer to peer, with the
owner's own device identity. Its page in Connected devices turns it on, says when it runs and holds
its devices, folders and connections. The folders live in the phone's sync storage, which only sync
and Lujarch reach.

## Turning it on

### SYN-1 Sync keeps folders in step with other devices

- Home: Settings > Connected devices > Sync
- Switch: Settings > Connected devices > Sync > Use sync
- Default: off
- Verify: vm

While Use sync is on, the sync service runs from every start of the phone and exchanges the
folders' changes with the devices they are shared with; off, it does not run. The page shows the
service's state and version, this device's name (which can be changed) and its device ID, as text
to copy and as a QR code. Connected devices has a Sync row whose summary is the state.

### SYN-2 Sync runs only when its conditions are met

- Home: Settings > Connected devices > Sync
- Switch: Only on Wi-Fi: on, off; Only while charging: on, off
- Default: Only on Wi-Fi: off; Only while charging: off
- Verify: vm

With Only on Wi-Fi, sync runs only while the phone is connected to a Wi-Fi network; with Only while
charging, only while a charger is connected. While a chosen condition is not met, the service waits
and the state says what it waits for. A change shorter than a few seconds, such as a Wi-Fi roam,
does not stop it. A condition the phone cannot read counts as its last known state.

### SYN-3 A failing sync service stops and says so

- Home: Settings > Connected devices > Sync
- Switch: none
- Default: none
- Verify: vm

A service that exits is started again after a growing pause. After 5 exits within 5 minutes it stays
stopped until sync is turned off and on, and the state says so. Without a usable configuration the
service does not start, and the state says so.

## Devices and folders

### SYN-4 Devices are added, changed and replaced on the phone

- Home: Settings > Connected devices > Sync > (device)
- Switch: each device: paused or not
- Default: none
- Verify: vm

Devices asking to connect are listed, to be added with a name, ignored for good, or left for later.
A device is added by its device ID, typed or scanned. Each device row says where it is reached
first, Cloud for a public address or name and Local network for a private one, and whether it is
connected. A device's page holds its name, its addresses, the folders shared with it, introducer,
accepting its folders, untrusted (every folder shared with it encrypted), compression, Replace with
another device, and removal. Replacing gives a new device every folder this one has, the way this
one has it, encryption passwords included without showing them, and then removes, pauses or keeps
this one. Pause all and Resume all act on every device.

### SYN-5 Folders are added, changed and removed on the phone

- Home: Settings > Connected devices > Sync > (folder)
- Switch: each folder: paused or not
- Default: none
- Verify: vm

Folders offered by other devices are listed, to be added, ignored for good, or left for later. A
folder is added with its name, ID, local folder in the sync storage, type and devices, and an
encryption password for each device that needs one; passwords are typed into masked fields and
never shown again. A folder's page shows its state and files, rescans it, moves it to another
directory of the sync storage, reverts local changes or overrides remote ones after saying what
that does, lists the items that failed and the conflict copies, and holds its settings: name, type,
devices, encrypted sharing, ignore patterns, file versioning, watching for changes and the full
scan interval. Removing a folder keeps its files.

### SYN-6 Discovery and connections are the owner's

- Home: Settings > Connected devices > Sync > Discovery and connections
- Switch: Global discovery, Local discovery, Relays, NAT traversal: on, off
- Default: as the sync service's own defaults
- Verify: vm

The page holds global discovery and its servers, local discovery, the listen addresses, relays and
their servers, NAT traversal, how often an unconnected device is dialled again, and the upload and
download limits. Each list is edited one entry per line and can be restored to its default.

### SYN-7 The device identity can be imported, renewed and restored

- Home: Settings > Connected devices > Sync > Device identity
- Switch: none
- Default: none
- Verify: vm

The identity is imported from files chosen in the document picker (certificate, key, and
optionally a configuration), or created anew, keeping this device's devices and folders or not.
The files are never shown, logged or kept by the page. The identity it replaces is kept as a
backup and can be restored. A new identity takes effect at the next start of the service, and the
page says whether sync restarted with it.

### SYN-8 The sync storage is reached only by sync and Lujarch

- Home: Settings > Connected devices > Sync
- Switch: none
- Default: none
- Verify: vm

The folders live in the phone's sync storage, a directory of the system that no app reaches; only
the sync service and Lujarch's own services read it, and Lujarch Vault reads only its KeePass
databases through them (VLT-2). When the storage's permissions are wrong, the Sync page says so.
