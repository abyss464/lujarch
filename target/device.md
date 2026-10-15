# Xiaomi 13

Lujarch runs on the Xiaomi 13 with Xiaomi's own firmware and hardware support. Apps see a Xiaomi 13 running this build, and nothing that tells them where the phone was
sold.

## Identity

### DEV-1 Apps see a Xiaomi 13

- Home: Settings > About phone
- Switch: none
- Default: none
- Verify: phone

Every app, and every browser's user agent, reports the model as Xiaomi 13, the brand and the
manufacturer as Xiaomi. Key attestation reports the identifiers provisioned at the factory.

### DEV-2 The build describes itself

- Home: Settings > About phone
- Switch: none
- Default: none
- Verify: phone

Every partition carries this build's own fingerprint, which agrees with its build number, type and
tags. A system update changes only the build number in it. Nothing the build reports names a
person, a computer or a time zone.

### DEV-3 No region reaches apps

- Home: none
- Switch: none
- Default: none
- Verify: phone

No property, fingerprint or hardware revision an app can read names the region the phone was sold
in.

## Firmware and hardware

### DEV-4 One stock release

- Home: Settings > About phone
- Switch: none
- Default: none
- Verify: phone

The firmware and the vendor files are the set tested together for the Xiaomi 13. Every Xiaomi
app or file that set lacks comes from the stock release of its firmware. Every system update
carries that firmware and installs it with the system.

### DEV-5 The replacement battery cell is charged with its own profile

- Home: Settings > Battery
- Switch: none
- Default: none
- Verify: phone

A replacement 4850 mAh cell is recognized, charged with its own profile and kept within its own
thermal limits, like the original 4500 mAh cell.

### DEV-6 A crashing radio restarts alone

- Home: none
- Switch: none
- Default: none
- Verify: phone

When the modem, a signal processor or the Wi-Fi firmware crashes, that part restarts and
reconnects; the phone keeps running.

### DEV-7 Wi-Fi reconnects with the screen off

- Home: Settings > Network & internet > Internet
- Switch: none
- Default: none
- Verify: phone

A phone that lost its Wi-Fi network finds it again and reconnects while the screen is off.

### DEV-8 Apps are compiled

- Home: none
- Switch: none
- Default: none
- Verify: phone

Installed apps are compiled after installation and in the background, as on any Android phone.

### DEV-9 The phone starts with charging control on

- Home: Settings > Battery > Charging control
- Switch: none
- Default: none
- Verify: phone

The phone starts normally whatever the state of charging control.

## Camera

### DEV-10 Xiaomi's camera

- Home: Camera
- Switch: none
- Default: none
- Verify: phone

The camera app is Xiaomi's own. It takes photos with every lens, in portrait and night modes, and
records video up to 4K.

## NFC

### DEV-11 Cards and payments use the secure element

- Home: Settings > Connected devices > Connection preferences > NFC
- Switch: none
- Default: none
- Verify: phone

The phone's embedded secure element is connected. Card emulation goes to it by default, and the
cards an app registers go to that app.
