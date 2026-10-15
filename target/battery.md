# Battery

Battery usage keeps what the phone used, hour by hour, for a week, and which app used it.

## Battery usage

### BAT-1 Battery usage covers the last 7 days by the hour

- Home: Settings > Battery > Battery usage; Settings > Apps > (app) > App battery usage
- Switch: Settings > Battery > Battery usage > menu > Record battery usage: on, off
- Default: on
- Verify: vm

Battery usage shows the last 7 days, each hour on its own: the charge used on battery with the
screen on and off, the charge taken in, the time on battery and plugged in, the battery level, and
what used the battery, as a chart and per app; an app's App battery usage shows the same for the app,
with when it ran in the foreground, its wake locks, alarms, network wakeups and foreground services.
The record is built from what Android already measures, when Settings updates its own battery usage
and when the page opens; nothing is sampled for it. Off, nothing is added to the record; the page's
menu also clears it.

### BAT-2 The battery history records every event

- Home: Settings > Battery > Battery usage > menu
- Switch: Settings > Battery > Battery usage > menu > Full battery history: on, off
- Default: on
- Verify: vm

With Full battery history on, Android's battery history records every wake lock, alarm and process
event, which the record (BAT-1) attributes to apps; off, it records only what Android records by
default.
