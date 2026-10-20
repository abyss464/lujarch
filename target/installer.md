# App installer

The owner sees everything about an app before it is installed and controls how it is installed and
uninstalled.

## Before installing

### INS-1 Installing from files shows all that is known of the app

- Home: opening an APK, an APK bundle (.apks, .xapk, .apkm, or a zip of APKs), or several of them
- Switch: none
- Default: none
- Verify: vm

The installer opens for an app file opened or shared from any app, and for several shared at
once. It shows the app's name and icon, package name and version, and whether the install is new,
an update, a downgrade or the same version, with the installed version. It shows the files' size,
the minimum and target Android version, the processor types the app has code for, its signing
certificate's SHA-256 with the signature schemes it is signed with (v1, v2, v3, v3.1, v4), and
whether its signature matches the installed app's. It lists the permissions the app asks for, the
ones an update adds marked New and those that need the owner's consent marked; the number of its
activities, services, receivers and providers, with those other apps can use listed; its files in
a bundle; and whether it is debuggable or for testing only. An app the installer cannot read says
which file and why. The source app needs Install unknown apps (PERM-12) as Android asks.

### INS-2 A bundle installs the parts the owner chooses

- Home: the installer, Parts
- Switch: each part of the bundle
- Default: the base, every feature part, and the language, screen density and processor parts that fit the phone
- Verify: vm

A bundle's parts are listed by kind: base, features, languages, screen densities and processor
types. The base cannot be left out. Expansion files a bundle carries are copied into the app's
folder in Android/obb.

### INS-3 The install has the options the owner chooses

- Home: the installer, Options; Settings > Apps > App installer > Default options
- Switch: Allow downgrade; Install for: This user, All users; Permissions: Ask as usual, Grant all, Deny all; Allow test-only apps; Allow apps for old Android versions; Processor type: Automatic or one the phone runs; Installed by: Package installer or an installed app; Updates by other apps ask first; Optimization: As Android chooses, Speed profile, Speed, Verify only; After installing: Done, Open, App info; Delete the file after installing
- Default: off; This user; Ask as usual; off; off; Automatic; Package installer; off; As Android chooses; Done; off
- Verify: vm

The defaults page sets what the installer's options start from; the installer's own options apply
to that install. The install records where the file came from as Android does: Local file, or
Downloaded file for a file from the web. Installed by names the app recorded as the installer.
Updates by other apps ask first makes other installers' updates of the app wait for the owner.
Allow downgrade is honoured where Android allows it; a refused downgrade says so.

### INS-4 Several apps install in one go

- Home: the installer, opened with several files, or Settings > Apps > App installer > Install from files
- Switch: each app's Install; Install all
- Default: every app chosen
- Verify: vm

Each app shows its details and parts; the options apply to all of them. They install one after
the other, each showing its progress and result.

## Installing and after

### INS-5 A failed install says exactly why

- Home: the installer
- Switch: none
- Default: none
- Verify: vm

A failed install shows Android's reason in words, its code (such as INSTALL_FAILED_UPDATE_INCOMPATIBLE)
and the message the system gave. When the signature differs from the installed app's, it offers
Uninstall and install, which says that the app's data is deleted.

### INS-6 Every install is in the history

- Home: Settings > Apps > App installer > Install history
- Switch: Clear history
- Default: none
- Verify: vm

The history lists each install and update on the phone, the latest first: when, the app and its
version, the app that installed it, and the result. Installs through the installer that fail are
listed with their reason.

### INS-7 Uninstalling has the options the owner chooses

- Home: Uninstall an app
- Switch: Keep the app's data; Uninstall for all users; Archive instead
- Default: off; off; off
- Verify: vm

Keep the app's data leaves its data for a later install of the same app. Uninstall for all users
removes it from every user of the phone. Archive instead keeps the app's data and icon and removes
the app, to be restored from its icon. For an update of a system app, uninstalling removes the
update and keeps the system's version.

### INS-8 An install another app makes is confirmed in the installer

- Home: an app that installs apps itself, such as an app store, asking the owner to install
- Switch: none
- Default: none
- Verify: vm

The installer opens for an install that another app prepared and asks the owner to confirm. It
shows all that INS-1 shows of the app as that app prepared it, and installs it so: its parts and
options are the other app's, and Options names the app that chose them. Closing the installer
without installing tells that app the owner did not install it. The other app needs Install
unknown apps (PERM-12) as Android asks.
