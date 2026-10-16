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
