# Updates

A system update is a package the owner brings to the phone and installs in System updates. The
new build waits for the owner's confirmation before it replaces the previous one, and until then
System updates can return to the previous build.

## Installing

### UPD-1 Updates are installed from a local package

- Home: Settings > System > System updates > Install local update
- Switch: none
- Default: none
- Verify: vm

The package is copied, verified against the release keys and installed by Android's own A/B
update, with every setting and all data kept (CORE-12). System updates asks no server: Check for
updates makes no request and finds no update, there is no periodic check and no changelog.

### UPD-2 One update at a time

- Home: Settings > System > System updates
- Switch: none
- Default: none
- Verify: vm

While the running build waits for confirmation, installing another update is refused, and System
updates says why: the build is confirmed or the previous build restored first.

## Confirming

### UPD-3 A new build waits for confirmation

- Home: Settings > System > System updates
- Switch: Confirm this build; Confirm automatically: Never, 30 minutes, 1 day, 3 days, 7 days, 14 days, 30 days
- Default: Confirm automatically: 30 minutes
- Verify: vm

After an update, the running build waits for confirmation and System updates says so on its card.
Until it is confirmed, the previous build stays whole on the other slot: Android does not merge
the update. The owner confirms it on the card, or it is confirmed by itself once it has run for
the chosen time since it first started. Confirming lets Android merge the update; the previous
build is gone after that. When whether the build is confirmed cannot be read, it counts as not
confirmed, and the reason is logged.

### UPD-4 The previous build can be restored

- Home: Settings > System > System updates > Return to the previous build
- Switch: none
- Default: none
- Verify: vm

While the running build waits for confirmation, its card offers to return to the previous build.
After a confirmation the phone restarts into the previous build with all data as it is at that
moment: what the new build changed stays changed, and an app whose data the new build converted
may not open it. The offer is not made, and the card says why, when the previous build has an
older Android version or security patch than the running one, when the update is already being
merged, or when the previous slot cannot start. A build that does not start returns to the
previous slot by itself after the bootloader's retries.

## Rescue

### UPD-5 Safe boot turns parts off from recovery

- Home: Recovery > Lujarch safe boot; Settings > System > System updates > Return to normal
- Switch: Proxy, Sync, Linux, Root access: normal, off each; App test updates: kept, removed
- Default: normal each; kept
- Verify: phone

A part that breaks the system is turned off without reflashing, from Lujarch safe boot in
recovery. Each switch takes effect from the next start and stays until it is switched back: the
proxy, sync or Linux does not run whatever its own switch says (the switch itself keeps the owner's
choice), no app gets root, or the test updates of the system apps are removed so that the image's
own versions run. While safe boot is on, System updates says so with what it turned off, and
Return to normal clears it for the next start.

### UPD-6 Each slot's build is shown

- Home: Settings > System > System updates > System slots; Recovery > System slots
- Switch: none
- Default: none
- Verify: vm

System updates and recovery show the build of each slot: the running one with its Android version,
security update and first start, and the other one as last recorded, with whether it can still be
started. In recovery the other slot can be set to start, after a confirmation, while the update
waits for confirmation.
