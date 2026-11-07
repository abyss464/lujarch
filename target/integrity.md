# Integrity

Play Integrity answers Google's device integrity checks from the system, with or without an imported
keybox.

## Play Integrity

### INT-1 Play Integrity

- Home: Settings > Security & privacy > Play Integrity
- Switch: Use Play Integrity: on, off
- Default: Use Play Integrity: on
- Verify: vm

While Use Play Integrity is on, Google Play services' attestation process sees a certified device
profile. When no keybox is imported, hardware key attestation from that process falls back to the
device profile. When a keybox is imported on the page, key attestation certificates are signed with
the keybox's key and certificate chain and report a locked, verified boot state. While Use Play
Integrity is off, apps and Google Play services see the phone's own build and key attestation
certificates.

The page shows whether a keybox is imported and which keys it holds, imports a keybox from a file,
and removes the imported keybox.
