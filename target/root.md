# Root

Root lets the apps the owner chooses run commands as root. It comes from KernelSU-Next built into
the kernel, without a manager app or modules: the owner grants and shapes it per app in Special app
access, and for every other app su does not exist.

## Granting

### ROOT-1 An app has root only when the owner allows it

- Home: Settings > Apps > Special app access > Root access; Settings > Apps > (app) > Root access
- Switch: each app: Allow Root access: on, off
- Default: off for every app
- Verify: phone

Root access lists the apps, those allowed first, with how many are allowed and each app's state:
Allowed, Allowed with a custom profile, Not allowed, or Allowed through another app that shares its
user ID. Its first row shows the kernel's KernelSU version, or that this kernel has no KernelSU, in
which case nothing on the page can be changed. An app's page holds Allow Root access; turning it on,
or changing the profile so that root can do more (ROOT-2), first asks for the fingerprint or the
screen lock, and a phone without a screen lock says so and lets the owner set one or go on. Turning
it off takes effect at once and returns the app's profile to the default. Lujarch's own apps and the
user IDs KernelSU never grants are listed as not allowed and cannot be changed, with the reason. App
info shows a Root access row with the app's state, which opens the app's page.

### ROOT-2 What root can do is set per app

- Home: Settings > Apps > (app) > Root access > Root profile
- Switch: each app: the profile's fields; Restore default profile
- Default: KernelSU's default root profile
- Verify: phone

An app's root profile has these fields: User ID, Group ID, Supplementary groups, Capabilities,
SELinux domain, Mount namespace (Inherited, Global, Individual) and No new privileges (on, off); its
su sessions run with them. KernelSU's default root profile is user and group 0, the only
supplementary group 0, every capability, KernelSU's SELinux domain, the inherited mount namespace
and No new privileges off. A profile that differs from the default is a custom profile, and Restore
default profile returns it to the default after a confirmation. KernelSU's SELinux domain is
enforcing like every other domain (CORE-9) and holds every permission.
