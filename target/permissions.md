# Permissions

Everything an app reads from the phone or does beyond its own data is a permission, a special app
access or a background control on Android's own pages. The user chooses each one per app with the
same set of choices everywhere, sees every use in the privacy dashboard, and is told when a choice
made an app stop.

## Choices

### PERM-1 One set of choices

- Home: Settings > Apps > (app) > Permissions > (permission)
- Switch: the permission's choices: Allow, Allow all the time, Allow only while using the app, Ask every time, Return empty data, Don't allow
- Default: as each item of this file says
- Verify: vm

Every permission, special app access and background control of this file takes its choices from
this one set, with the same words everywhere, and offers only the choices that act differently
for it.

- Allow only while using the app: the access works while the app is on screen or runs a
  foreground service the user sees.
- Ask every time: the app is asked about as PERM-11 says.
- Return empty data: the app keeps running and its reads come back empty: no value, an empty
  list, "unknown" or zero, as the permission's page says.
- Don't allow: the app is refused, as an app that lacks the permission is.

The footer of each permission's page says what the permission covers and what it does not reach.

### PERM-2 Android's permissions can return empty data

- Home: Settings > Apps > (app) > Permissions > (permission)
- Switch: Android's choices for the permission, and Return empty data
- Default: Android's
- Verify: vm

Location, Camera, Microphone, Contacts and accounts, Calendar, Call logs, SMS, Photos and videos,
Music and audio, Nearby devices and Physical activity offer Return empty data. An app set to it
holds the permission as granted, and what it reads through the permission comes back empty. The
permission's lists in the permission manager show such apps in their own section.

### PERM-3 Phone covers the phone's basic information

- Home: Settings > Apps > (app) > Permissions > Phone
- Switch: Allow, Basic phone info only, Ask every time, Return empty data, Don't allow
- Default: Allow for an app granted the permission; Basic phone info only for every other app
- Verify: vm

Basic phone info is the carrier, its country and code, the SIM's state, signal strength, the
network type, the data and call state and the subscription numbers: what Android gives an app
without the Phone permission. Every app is listed under Phone, also an app that never asked for
the permission.

- Allow: the Phone permission, basic phone info included.
- Basic phone info only: no Phone permission; basic phone info reads as usual.
- Ask every time: as Basic phone info only, with basic phone info asked about as PERM-11 says.
- Return empty data: the Phone permission held as granted; everything it and basic phone info
  give comes back empty.
- Don't allow: no Phone permission and no basic phone info.

The footer says that the carrier's code, which the system publishes as a system property, stays
readable to an app's own native code.

## Lujarch's permissions

### PERM-4 Motion and environment sensors

- Home: Settings > Security & privacy > Privacy controls > Permission manager > Motion and environment sensors
- Switch: Allow, Allow only while using the app, Ask every time, Return empty data, Don't allow
- Default: Allow
- Verify: vm

Covers every sensor Android opens without a permission: accelerometer, gyroscope, magnetometer,
barometer, light, proximity and the like. Return empty data: the app's sensors deliver no
readings. Don't allow: the app cannot open them.

### PERM-5 Clipboard

- Home: Settings > Security & privacy > Privacy controls > Permission manager > Clipboard
- Switch: Allow, Ask every time, Return empty data
- Default: Allow
- Verify: vm

Covers reading what was copied. Return empty data: the app finds the clipboard empty. Android's
own "Show clipboard access" message stays as it is.

### PERM-6 Installed apps

- Home: Settings > Security & privacy > Privacy controls > Permission manager > Installed apps
- Switch: Allow, Ask every time, Return empty data, Don't allow
- Default: Allow
- Verify: vm

Covers which other apps an app sees: the app list, and the accounts, keyboards, accessibility
services and notification listeners that reveal them. Return empty data: the app sees the apps
that come with the system and itself, and none the user installed. Don't allow: the app sees only
itself and the system parts every app needs (Google Play services, the WebView, the keyboard and
the home app); features that rely on another app (sharing, sign-in through another app) can fail.


### PERM-7 Device identifiers

- Home: Settings > Security & privacy > Privacy controls > Permission manager > Device identifiers
- Switch: Allow, Ask every time, Return empty data, Don't allow
- Default: Allow
- Verify: vm

Covers the numbers that identify this phone: the IMEI, the serial number, the SIM's serial and
subscriber numbers, and the Android ID. Apps that Android lets read the hardware numbers, Google
Play services included, are covered too. Return empty data: the numbers read as empty or zeros.
Don't allow: the read is refused where Android refuses a missing permission, and empty elsewhere.

### PERM-8 Device info

- Home: Settings > Security & privacy > Privacy controls > Permission manager > Device info
- Switch: Allow, Ask every time, Return empty data
- Default: Allow
- Verify: vm

Covers what describes this phone through the system's interfaces: model and maker, the system
build, the time zone, the battery's state, connected USB devices, the device name, the number of
boots and whether developer options are on. Return empty data: the model and build read
"unknown", the time zone UTC, the battery and USB nothing. A change applies from the app's next
start, and the page says so. The footer says that an app's own native code still reads the model
and build from system properties, and the processor and graphics names from the kernel.

### PERM-9 Network info

- Home: Settings > Security & privacy > Privacy controls > Permission manager > Network info
- Switch: Allow, Ask every time, Return empty data, Don't allow
- Default: Allow
- Verify: vm

Covers what the system's interfaces tell about the networks: IP addresses, DNS servers, the
proxy, whether a VPN is on, Wi-Fi signal and link details, data totals, and whether Bluetooth and
NFC are on. Return empty data: these read as empty, a VPN as absent, Bluetooth and NFC as off;
the app's own connections keep working, and an app allowed Nearby devices still sees whether
Bluetooth is on. The footer says that an app's own native code still reads
the addresses of the phone's network interfaces from the kernel, and that any server the app
talks to sees the phone's public address.

### PERM-10 Every app can be restricted

- Home: Settings > Security & privacy > Privacy controls > Permission manager
- Switch: none
- Default: none
- Verify: vm

Every app, the apps that come with the system and Google Play services included, can be set to
every choice of PERM-1 for the permissions of this file. The system's own core processes are
never restricted, and the lists do not show them.

## Asking

### PERM-11 A question when the access happens

- Home: the question; Settings > Apps > (app) > Permissions
- Switch: the Ask every time choice of each permission
- Default: as each permission says
- Verify: vm

For a permission set to Ask every time, the app's first access is answered with empty data and
the user is asked at once:

- in a dialog that looks like Android's permission dialog, when the app is on screen and the
  phone is unlocked;
- otherwise in a notification, never on the lock screen; snoozing it uses Android's notification
  snooze.

The question names the app and the permission and offers the permission's choices. The answer
becomes the app's choice. Several permissions of one app waiting together are one question.

## Special app access

### PERM-12 Install unknown apps covers every installer

- Home: Settings > Apps > Special app access > Install unknown apps
- Switch: per app: Allow, Ask every time, Don't allow; the page's menu: Wait for an answer: 1, 2, 5, 10, 15, 30 or 60 minutes
- Default: Allow for the app stores that come with the system; Don't allow for other apps; 10 minutes
- Verify: vm

Every app that can install or update apps is listed, app stores included.

- Allow: the app installs as Android lets it.
- Ask every time: each install and update by the app waits for the user. The question, as in
  PERM-11, shows the app's name and version and whether its signature matches the installed
  app's. Without an answer within the wait time, the install is refused and a notification says
  so.
- Don't allow: the app's installs and updates are refused.

### PERM-13 Every other access has a page

- Home: Settings > Apps > Special app access > See more
- Switch: per access and app, the choices of PERM-1 that act differently for it
- Default: Android's
- Verify: vm

Each access Android records for apps that has no page of its own elsewhere (vibrating, keeping
the phone awake, playing audio, showing pop-up messages and the like) is listed here with the apps
that use it. When it is not allowed, Android ignores the app's requests for it. An access that has
its own page in Special app access or the permission manager is chosen only there; an access
Android records but does not enforce is not listed.

### PERM-14 Restricted settings can be locked

- Home: Settings > Apps > (app) > menu > Allow restricted settings
- Switch: Decided by Android, Allow, Lock
- Default: Decided by Android
- Verify: vm

Lock keeps the app's restricted settings closed even where Android would open them.

## Background, network and notifications

### PERM-15 Autostart

- Home: Settings > Apps > (app) > App battery usage > Autostart
- Switch: Allow, Ask every time, Don't allow
- Default: Allow
- Verify: vm

Covers the system starting an app that is not running, for a broadcast: at boot, for an alarm or
for a push message. Don't allow: the app starts only when the user opens it or another app opens
it, and push messages that arrive while it is not running can be lost. The row's summary says
so.

### PERM-16 Network access is chosen in Mobile data & Wi-Fi

- Home: Settings > Apps > (app) > Mobile data & Wi-Fi
- Switch: Allow network access, Wi-Fi data, Mobile data, Background data, VPN data
- Default: all on
- Verify: vm

An app's use of the networks is chosen on this page only.

### PERM-17 Notifications are chosen with Android's switch

- Home: Settings > Apps > (app) > Notifications
- Switch: All notifications from the app
- Default: Android's
- Verify: vm

Whether an app shows notifications is this switch only; the Notifications permission is the same
state.

## New apps

### PERM-18 Defaults for new apps

- Home: Settings > Apps > Defaults for new apps
- Switch: per permission of this file, basic phone info, Install unknown apps, Autostart and network access: the choice a new app starts with, or Not changed
- Default: Ask every time for Motion and environment sensors, Clipboard, Installed apps, Device identifiers, Device info, Network info, basic phone info and Autostart; Not changed for the rest
- Verify: vm

An app the user installs starts with these choices before it first runs; apps that come with the
system and updates keep their choices. An app is not asked about what its role needs: the default
home app about Installed apps, the default SMS app about Autostart and basic phone info, the
default phone app about basic phone info, an app with a VPN service about Network info. The page
lists these exceptions.

## Record

### PERM-19 The privacy dashboard shows every access

- Home: Settings > Security & privacy > Privacy dashboard
- Switch: none
- Default: none
- Verify: vm

The privacy dashboard lists every permission and access of this file that apps used, each with
its apps and each use with its time and outcome: allowed, returned empty data, refused or asked.
Its time ranges reach back as far as the history is kept.

### PERM-20 How long the history is kept

- Home: Settings > Security & privacy > Privacy dashboard > menu > Keep history
- Switch: 1 day, 7 days, 30 days, 90 days
- Default: 7 days
- Verify: vm

A shorter choice asks first, since the older records are deleted.

## When an app stops

### PERM-21 The reason an app stopped

- Home: the "has stopped" dialog; Settings > Security & privacy > Privacy controls > Permission manager > Explain why apps stop
- Switch: Explain why apps stop: on, off
- Default: on
- Verify: vm

When an app stops within a few seconds after a choice of the user's returned empty data to it or
refused it, Android's "has stopped" dialog appears, also on the app's first stop. It names each
such permission and choice, and offers a button per permission that sets it to Allow, and a button
to the app's permissions. Nothing changes without a tap. When the phone is not
in use, a notification says the same.
