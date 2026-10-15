# Lujarch core

Lujarch is an Android system for the Xiaomi 13, built on LineageOS 24 (Android 17) and signed with
its owner's keys. Its owner sees and decides everything that crosses the system's boundary, in the
places Android already keeps each concept. This file holds what is true everywhere in the system;
each feature's file holds the rest.

## Control

### CORE-1 Every capability has an entry

- Home: none
- Switch: none
- Default: none
- Verify: vm

Every capability of the system has an entry in the UI where the user sees its state and changes
it. A command line or a computer connection is not an entry.

### CORE-2 One native home per capability

- Home: none
- Switch: none
- Default: none
- Verify: vm

Each capability lives in the one place where Android keeps its concept, under Android's name for
it: App info, the permission manager, the privacy dashboard, Network & internet, Connected
devices, Battery, Display, Sound, Security & privacy, System, About phone, Quick Settings. A page
of Lujarch's own exists only where Android has no home for the concept. Settings has no tile that
gathers Lujarch's features.

### CORE-3 Everything the system does on its own has a switch

- Home: none
- Switch: none
- Default: none
- Verify: vm

Everything the system shows or does on its own (a status bar icon, a notification, a background
action, a restriction) has a switch in its native home. An item of the target without a switch is
a guarantee and says what it guarantees.

### CORE-4 App info shows each app's part

- Home: Settings > Apps > (app)
- Switch: none
- Default: none
- Verify: vm

Where a capability concerns single apps, App info shows that app's part of it as a row with the
app's current state, and the row opens the capability's page for that app.

### CORE-5 Every page and switch is found by search

- Home: Settings > Search
- Switch: none
- Default: none
- Verify: vm

Every Lujarch page and switch is found through Settings search by its title and keywords in the
language the phone uses, and the result opens the page with the switch in view.

### CORE-6 Nothing happens silently

- Home: none
- Switch: none
- Default: none
- Verify: vm

When the system refuses, restricts, stops or changes something on its own, it shows what happened
and which part of the system did it, in the notification or on the page of that capability. The
system proposes, with what it knows; the user decides.

## Look and language

### CORE-7 Lujarch's pages look like Android's

- Home: none
- Switch: none
- Default: none
- Verify: vm

Lujarch's pages use the look of Android's own Settings pages: the collapsing toolbar, preference
lists, Android's type, colors, spacing and dark theme. A page shows a failure where it happened,
says what failed and what the user can do, and keeps the rest of the page usable.

### CORE-8 English, Simplified Chinese and Japanese

- Home: Settings > System > Languages
- Switch: Settings > System > Languages (Android's own)
- Default: the language chosen in setup
- Verify: vm

Every string Lujarch adds or changes exists in English, Simplified Chinese and Japanese, worded as
terms.md says. With any other system language, Lujarch's own strings show in English and
Android's own strings in that language.

## Integrity

### CORE-9 SELinux is always enforcing

- Home: Settings > About phone > Android version
- Switch: none
- Default: none
- Verify: vm

SELinux is enforcing from the first boot, and every part of the system, Lujarch's own included,
runs under an enforcing policy. About phone shows the status as Enforcing.

### CORE-10 Every signature is the owner's

- Home: none
- Switch: none
- Default: none
- Verify: phone

Every partition, APEX and app in a release is signed with the owner's keys, except the Google
apps of apps.md, which the image ships with Google's signatures.

### CORE-11 The image holds the product and nothing else

- Home: none
- Switch: none
- Default: none
- Verify: vm

The image contains the system's features, the device layer and the preinstalled apps. It contains
no development or test component, no person's name, account or server in any default, and no
personal setting.

### CORE-12 An update keeps every setting and all data

- Home: Settings > System > System updates
- Switch: none
- Default: none
- Verify: vm

After a system update every setting the user made, every app and all data are as before the
update.

## Daily use

### CORE-13 Restrictions never break calls, alarms and messages

- Home: none
- Switch: none
- Default: none
- Verify: vm

Nothing the system restricts on its own by default stops incoming calls, alarms, or the message
notifications of apps the user has not restricted.
