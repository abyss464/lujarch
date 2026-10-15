# Files

Files is the phone's one window onto its files: every app that asks to choose a file, save one or
choose a folder gets Files, and its answer reaches the app as Android's own documents, which the app
keeps access to as it would with Android's file picker.

## Choosing and saving

### FILE-1 Files answers every file request

- Home: the request
- Switch: none
- Default: none
- Verify: vm

When an app asks to choose one or more files, to save a file or to choose a folder, Files opens for
it; images and videos alone go to Android's photo picker, whose Browse opens Files. A file or folder
chosen on the phone's storage reaches the app as a document of the phone's storage, with the access
Android's own picker gives (reading; writing and keeping access for documents opened or saved; the
whole folder for a chosen folder). Saving creates the file in the chosen folder, or replaces the file
the owner chose. A folder Android does not let apps take (the storage's top, Download, Android) is
refused with the reason and Choose again; anything else Files offers (a network share, a file in an
archive) reaches the app as Files serves it.

### FILE-2 A request Files cannot serve says so

- Home: the request
- Switch: none
- Default: none
- Verify: vm

When Files is missing, disabled or not the system's own, or a location cannot be opened in Files (a
connected USB device, another app's documents), a dialog says which, with App info or OK, and the
app gets no answer.

## Browsing

### FILE-3 Browsing storage opens the matching app

- Home: Settings > Storage
- Switch: none
- Default: none
- Verify: vm

Settings > Storage opens images and videos in the gallery, audio in Music, and documents, other
files, Download and the storage itself in Files at the matching folder; a USB device connected for
files opens in Files.
