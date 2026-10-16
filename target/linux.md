# Linux

The Linux development environment runs Arch Linux ARM on the phone beside Android, on the same
kernel and the same network: its own user, its services, terminal sessions in an app of the system,
its desktop on the phone's screen, and the phone's sensors, USB devices, fingerprint, infrared and
NFC when the owner lends them. Its page in System holds all of it.

## Turning it on

### LNX-1 Linux runs while it is on

- Home: Settings > System > Linux development environment
- Switch: Settings > System > Linux development environment > Use Linux development environment
- Default: off
- Verify: phone

While the switch is on, the installed system runs from every start of the phone with its own
services, and the page shows its state (running, stopped, not installed) and its sessions; off, it
does not run and its sessions end. Turning the switch on or off takes effect at once. System has a
Linux development environment row with the state as its summary.

### LNX-2 The system is installed from an archive

- Home: Settings > System > Linux development environment > Install the root file system
- Switch: none
- Default: none
- Verify: phone

An Arch Linux ARM archive chosen in Files is checked and installed, or replaces the installed system,
which is kept beside it under its date. The first start creates the owner's user with the name the
owner gives, which has its own user ID, and runs the initial setup, which can be run again. The page
shows the root file system's state and the last action with its outcome.

## Using it

### LNX-3 Terminal sessions

- Home: the Terminal app; Settings > System > Linux development environment > When the terminal opens
- Switch: When the terminal opens: Arch Linux, Arch Linux as root, Android as root; each session: when the phone sleeps: sleep with the phone, keep awake
- Default: When the terminal opens: Arch Linux; each session: sleep with the phone
- Verify: phone

The Terminal app holds up to 16 sessions as tabs, each a shell of the owner's user, of root in Arch
Linux, or of root in Android; sessions go on while the app is closed and end when Linux stops. Each
can be renamed and ended, shows whether a command runs, and keeps the phone awake only if the owner
chooses so. The page holds the colors (follow the system, classic, the owner's own) and the keys row
above the keyboard.

### LNX-4 Root sessions only while the owner allows them

- Home: Settings > System > Linux development environment > Root sessions
- Switch: Root sessions: on, off
- Default: off
- Verify: phone

Sessions as root, in Arch Linux or in Android, open only while Root sessions is on; turning it on
asks for the fingerprint or the screen lock first (as ROOT-1), and turning it off ends the open root
sessions. While it is on, Root access (ROOT-1) shows a row saying so.

### LNX-5 Memory and swap

- Home: Settings > System > Linux development environment > Memory; Swap
- Switch: Memory: For Android, Balanced, For Linux, Unlimited; Swap: on, off
- Default: Memory: Balanced; Swap: off
- Verify: phone

Memory sets how much of the phone's memory Linux may use before Android's apps are favored, and the
page shows the current use. Swap gives Linux a swap file in its own storage while it runs.

### LNX-6 The desktop on the phone's screen

- Home: the Terminal app > Display; Settings > System > Linux development environment > Open desktop; Install the desktop
- Switch: none
- Default: none
- Verify: phone

The desktop of Arch Linux shows full screen on the phone through the Terminal app, with touch, the
keyboard and the phone's sound, and stops drawing while it is not on screen. Install the desktop
installs Plasma Mobile in Linux and builds KWin with Lujarch's display and Mesa for the phone's
GPU, as root in Linux while it runs and on the small cores only; installing again updates it. The
page shows the last install with its outcome. While Linux is on
screen, the Mode Linux (Settings > Modes) lets calls, repeat callers, alarms and media through and
holds other notifications; the status bar stays locked and a swipe from the bottom returns to
Android. A double tap on an empty space of the home screen opens Linux when the owner chooses so
(Home settings > Double tap: Nothing, Turn off screen, Enter Linux).

## Lending the phone

### LNX-7 Linux uses the phone's parts only when lent

- Home: Settings > System > Linux development environment
- Switch: Shared clipboard, Android notifications, Sensors, USB devices, Confirm authorization with fingerprint, Infrared transmitter, NFC card reading: on, off each
- Default: off each
- Verify: phone

Each switch lends Linux one part of the phone: the clipboard both ways, a count and list of Android's
notifications, the motion and environment sensors, USB devices connected to the phone, the
fingerprint to confirm authorization requests in Linux, the infrared transmitter and NFC card reading.
Off, Linux cannot reach that part.

### LNX-8 Services are run and watched on the phone

- Home: Settings > System > Linux development environment > Services
- Switch: each service: Run: on, off; Starts with Linux: on, off
- Default: as the installed system has them
- Verify: phone

Services lists Linux's services with their state; a service's page starts, stops and restarts it,
sets whether it starts with Linux, and shows its log and its files.

### LNX-9 Remote access to Linux runs on the owner's terms

- Home: Settings > System > Linux development environment > Remote access; Quick Settings > Remote access
- Switch: When it runs: While charging, Always, Off
- Default: Off
- Verify: phone

Remote access lets the owner's computers log in to Linux by SSH through the owner's own relay server:
its address, port and token, the entry's name and secret, and the public keys allowed to log in are
set on its page; the token and the secret are never shown again. Logging in by password and as root
is refused. The Quick Settings tile Remote access shows when it runs and switches between Always and
While charging.
