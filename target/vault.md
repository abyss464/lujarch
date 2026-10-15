# Vault

Lujarch Vault shows the passwords of the owner's KeePass database and the 2FA codes of the owner's
Aegis vault, and fills them into apps and websites. It opens only while the phone is unlocked and
keeps nothing decrypted when no page shows it. It is the phone's autofill service.

## Unlocking

### VLT-1 The vault opens only while the phone is unlocked

- Home: none
- Switch: none
- Default: none
- Verify: vm

The keys that open the KeePass database and the Aegis vault are stored only wrapped by a key of the
phone's keystore that works only while the phone is unlocked. Nothing decrypted is kept once no
vault page is visible and no autofill request runs. No vault page can be captured in a screenshot
or a recording. A page shown while the phone is locked says so and opens once it is unlocked.

### VLT-2 The sources are set up once

- Home: Settings > Passwords & accounts > Lujarch Vault > Sources
- Switch: each source: on, off
- Default: on once set up
- Verify: vm

The KeePass database comes from a synced folder (SYN-8), listed with its folder, size and time, or
from any file chosen in the document picker. It is set up once with its password and its key file,
chosen in the document picker and never kept on the phone. The Aegis vault comes from a file and is
set up with its password. A source turned off is not opened and not used for codes or autofill, and
its keys are kept. A database the vault can no longer read says so, with Choose again.

### VLT-3 Stored keys are forgotten on the owner's terms

- Home: Settings > Passwords & accounts > Lujarch Vault > Settings
- Switch: Forget keys: Never, After a restart, After 1 day unused, After 7 days unused, After 30 days unused
- Default: Never
- Verify: vm

Forget keys deletes the stored keys at once; the chosen files stay, so setting up again needs only
the passwords and the key file. The chosen policy deletes them by itself.

## Passwords and codes

### VLT-4 Passwords are found and copied

- Home: Settings > Passwords & accounts > Lujarch Vault > Passwords
- Switch: Verify to reveal: on, off; Verify to copy: on, off
- Default: Verify to reveal: on; Verify to copy: off
- Verify: vm

Passwords lists the database's groups and entries and searches title, user name, URL, tags and
group. An entry copies its user name, password and URL, and shows its notes and other fields. A
password is revealed, and with Verify to copy copied, only after a fingerprint or the screen lock.
What a page showed is hidden again when the page stops.

### VLT-5 2FA codes are shown, copied and added

- Home: Settings > Passwords & accounts > Lujarch Vault > 2FA codes; Quick Settings > 2FA codes
- Switch: none
- Default: none
- Verify: vm

2FA codes lists the Aegis accounts, then the KeePass entries that carry a code, each with its code
and countdown; a tap copies it. An account is added to the Aegis vault from a QR code taken with the
camera, a QR code in an image, a typed key, or an otpauth link, each shown for review first; the
accounts of another Aegis file are imported. An Aegis account is renamed, starred, advanced (counter
codes) or deleted after a confirmation. The Quick Settings tile 2FA codes opens the codes, after
unlocking on a locked phone.

### VLT-6 Copied secrets leave the clipboard

- Home: Settings > Passwords & accounts > Lujarch Vault > Settings
- Switch: Clear the clipboard: Never, After 15 seconds, After 30 seconds, After 1 minute, After 2 minutes, After 5 minutes
- Default: After 30 seconds
- Verify: vm

Everything the vault copies is marked sensitive, so the clipboard does not show it, and is cleared
the chosen time after the last copy.

## Autofill

### VLT-7 The vault fills apps and websites

- Home: Settings > Passwords & accounts > Preferred service
- Switch: Preferred service: Lujarch Vault or another; Search the vault suggestion: on, off; Same host only: on, off
- Default: Preferred service: Lujarch Vault; Search the vault suggestion: on; Same host only: off
- Verify: vm

User name, password and one-time code fields are filled from the entries that match: an app by its
package, a website by its domain or a parent domain, or with Same host only by its own host. The
suggestions show in the dropdown and the keyboard's strip, which ends with Search the vault while
that is on. Nothing is offered while the database is not set up or turned off or the phone is
locked, and never to Lujarch's own apps. When opening takes longer than the moment autofill waits,
the suggestion says so and fills once tapped.

### VLT-8 Apps and sites can be left unfilled

- Home: Settings > Passwords & accounts > Lujarch Vault > Settings > Apps and sites not filled
- Switch: each app or site; Default scope for sites: This host only, Whole site
- Default: none listed; Default scope for sites: This host only
- Verify: vm

An app is left unfilled by its package, a site by its host with the scope chosen for it: the host
alone, its www form included, or the whole site with every subdomain. A site without a scope of its
own follows the default scope.
