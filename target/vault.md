# Vault

Lujarch Vault shows the passwords of the owner's password file and the 2FA codes of the owner's
authenticator accounts, and fills them into apps and websites. It reads the files of the common
password managers and authenticator apps. It opens only while the phone is unlocked and keeps
nothing decrypted when no page shows it. It is the phone's autofill service.

## Unlocking

### VLT-1 The vault opens only while the phone is unlocked

- Home: none
- Switch: none
- Default: none
- Verify: vm

The keys that open the passwords and the 2FA codes are stored only wrapped by a key of the phone's
keystore that works only while the phone is unlocked; a password file without a password of its
own is read only after such a key was released. Nothing decrypted is kept once no vault page is
visible and no autofill request runs. No vault page can be captured in a screenshot
or a recording. A page shown while the phone is locked says so and opens once it is unlocked.

### VLT-2 The sources are set up once

- Home: Settings > Passwords & passkeys > Lujarch Vault > Sources
- Switch: each source: on, off
- Default: on once set up
- Verify: vm

The two sources are Passwords and 2FA codes. Passwords come from one file in a synced folder
(SYN-8), listed with its folder, size and time, or from any file chosen in the document picker.
The vault tells the file's format from its content:

- a KDBX 3.1 or 4.x database;
- a Bitwarden JSON export, unencrypted or protected with a password;
- a 1Password export (1PUX);
- a CSV export of Chrome, Edge, Brave, Firefox, Safari, Bitwarden, LastPass, Proton Pass,
  KeePassXC or Dashlane, by its header row.

The file is set up once: one with a password with its password and, for a KDBX database, its key
file if it has one, chosen in the document picker and never kept on the phone; one without a
password as it is. About the vault shows the file's format.

2FA codes come from a file chosen in the document picker: an export of Aegis, 2FAS, andOTP or
Proton Authenticator (each plain or encrypted), FreeOTP+ (JSON or a list of links), Bitwarden or
Bitwarden Authenticator (JSON or CSV), Ente Auth (plain text) or Steam Guard, Google
Authenticator's export links, or any text file of otpauth links, one per line. The vault keeps the accounts in a
file of its own, encrypted with a password: the picked file's own, or for a file without one a
password chosen and repeated at setup. A source turned off is not opened and not used for codes or
autofill, and its keys are kept. A file the vault can no longer read says so, with Choose again.

### VLT-3 Stored keys are forgotten on the owner's terms

- Home: Settings > Passwords & passkeys > Lujarch Vault > Settings
- Switch: Forget keys: Never, After a restart, After 1 day unused, After 7 days unused, After 30 days unused
- Default: Never
- Verify: vm

Forget keys deletes the stored keys at once; the chosen password file and the kept 2FA accounts
stay, so setting up again needs only the passwords and the key file. The chosen policy deletes them
by itself.

## Passwords and codes

### VLT-4 Passwords are found and copied

- Home: Settings > Passwords & passkeys > Lujarch Vault > Passwords
- Switch: Verify to reveal: on, off; Verify to copy: on, off
- Default: Verify to reveal: on; Verify to copy: off
- Verify: vm

Passwords lists the file's groups (KDBX groups, folders, vaults) and entries and searches title,
user name, URL, tags and group. An entry copies its user name, password and URL, and shows its notes and other fields. A
password is revealed, and with Verify to copy copied, only after a fingerprint or the screen lock.
What a page showed is hidden again when the page stops.

### VLT-5 2FA codes are shown, copied and added

- Home: Settings > Passwords & passkeys > Lujarch Vault > 2FA codes; Quick Settings > 2FA codes
- Switch: none
- Default: none
- Verify: vm

2FA codes lists the accounts of the 2FA codes, then the password entries that carry a code, each
with its code and countdown; a tap copies it. An account is added from a QR code taken with the
camera, a QR code in an image, a typed key, or an otpauth link, each shown for review first; the
QR code of a Google Authenticator export adds its accounts, listed for a confirmation first. Import
from another file adds the accounts of a file in any format the 2FA codes are set up from, except
those already there, and says how many it added and how many it could not read. An account is
renamed, starred, advanced (counter codes) or deleted after a confirmation. The Quick Settings tile
2FA codes opens the codes, after unlocking on a locked phone.

### VLT-6 Copied secrets leave the clipboard

- Home: Settings > Passwords & passkeys > Lujarch Vault > Settings
- Switch: Clear the clipboard: Never, After 15 seconds, After 30 seconds, After 1 minute, After 2 minutes, After 5 minutes
- Default: After 30 seconds
- Verify: vm

Everything the vault copies is marked sensitive, so the clipboard does not show it, and is cleared
the chosen time after the last copy.

## Autofill

### VLT-7 The vault fills apps and websites

- Home: Settings > Passwords & passkeys > Preferred service
- Switch: Preferred service: Lujarch Vault or another; Search the vault suggestion: on, off; Same host only: on, off
- Default: Preferred service: Lujarch Vault; Search the vault suggestion: on; Same host only: off
- Verify: vm

User name, password and one-time code fields are filled from the entries that match: an app by its
package, a website by its domain or a parent domain, or with Same host only by its own host. The
suggestions show in the dropdown and the keyboard's strip, which ends with Search the vault while
that is on. Nothing is offered while the passwords are not set up or turned off or the phone is
locked, and never to Lujarch's own apps. When opening takes longer than the moment autofill waits,
the suggestion says so and fills once tapped.

### VLT-8 Apps and sites can be left unfilled

- Home: Settings > Passwords & passkeys > Lujarch Vault > Settings > Apps and sites not filled
- Switch: each app or site; Default scope for sites: This host only, Whole site
- Default: none listed; Default scope for sites: This host only
- Verify: vm

An app is left unfilled by its package, a site by its host with the scope chosen for it: the host
alone, its www form included, or the whole site with every subdomain. A site without a scope of its
own follows the default scope.
