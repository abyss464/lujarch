# Apps

The apps the system comes with besides Android's and Lujarch's own: Google's services, the
third-party apps that take the place of stock apps, and the owner's Reader. Each comes from a pinned
version; every one but Google's is built from source and signed with the owner's keys.

## Google

### APP-1 Google's services come with the system

- Home: Settings > Google
- Switch: none
- Default: none
- Verify: phone

Google Play services, Google Services Framework and the Play Store come from MindTheGapps for
Android 17 at its pinned release, with Google's signatures, and nothing else of Google's. The Play
Store installs apps like any other installer (PERM-12); it does not verify packages on its own.
Google Play services is not the preferred credential service (VLT-7).

## The system's apps

### APP-2 Files, Music and App Store are the system's

- Home: the launcher; Settings > Apps
- Switch: none
- Default: none
- Verify: vm

Files (Material Files), Music (FuoEvolve) and App Store (Obtainium) are built from their source at
the pinned versions, signed with the owner's keys and come with the system. Each is named in the
phone's language as Android names the stock app it replaces (Files, Music; App Store) and has its
icon: Files takes the place of Android's file manager (FILE-1), Music that of LineageOS' music app.
They are updated with the system.

### APP-3 Reader comes with the system

- Home: the launcher; Settings > Apps > Reader
- Switch: none
- Default: none
- Verify: vm

Reader, the owner's reading app, is built from its source at the pinned commit and signed with its
own key, one of the owner's, so that the owner's own builds of it update it with its data.

### APP-4 Replaced stock apps are not in the image

- Home: none
- Switch: none
- Default: none
- Verify: vm

LineageOS' browser, calendar, camera, audio effects, messaging and music apps and Android's file
manager are not in the image: Play apps the owner chooses, the Xiaomi camera, Files and Music take
their place.
