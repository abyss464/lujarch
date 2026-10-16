# Release flow

A release is a signed build of one commit that passed the test flow (process/test-flow.md). The
release flow ends with the release's artifacts; bringing them to a phone is delivery, which is
the owner's (the owner's repository).

`tools/lujarch release` runs the flow for `HEAD`, from the preconditions to the release notes. It
has no options.

## When

Only when the owner asks for a release.

## Preconditions

- The commit is on `main` and the worktree it is built from is clean.
- The commit passed the test flow, with the pins it carries (`lock.toml`, `upstream.xml`).

`tools/lujarch release` checks them before anything else and names each one that fails: `HEAD` is
on `main`; `git status` shows no change and no untracked file; `tools/lujarch check` reports
nothing; the commit carries no release tag; the test results of exactly this commit
(`<out>/test/<commit>/results.json`) hold a pass for every item with `Verify: vm`, and it names
the items that failed or have no result.

## Steps

1. Tag the commit `lujarch-<n>`, `n` one more than the last release, and check it out into a
   fresh worktree. The build takes its version from the tag; a release that ends before its
   artifacts are written removes the tag.
2. Sync the tree to `upstream.xml` exactly and apply the patches in `system/order`.
3. Build `fuxi` with the build variant in `lock.toml`.
4. Sign with the release keys, found at the host configuration's key location. Keys are never
   read, printed or copied by anything but the signing step.
5. Verify the signed output: every signature is the release key's except third-party apps the
   image ships with their developers' signatures, each named; APEX keys; privileged-permission
   allowlists; no file in the image that the product does not install; no test component.
6. Write the artifacts as `lujarch-<n>-<commit>`: the OTA package and the images needed to
   install from scratch, with their hashes.
7. Generate the release notes from the change of `target/` between the previous release tag
   and this one.

### How `tools/lujarch release` does them

- The worktree is `<out>/releases/.lujarch-<n>-<commit>/source`, `<commit>` the first 12 digits
  of the commit. The sync and the build are that worktree's own `tools/lujarch sync` and
  `tools/lujarch build fuxi droid target-files-package otatools`, so the version is
  `<n>-<commit>`; the release stops when the image's version file is not the tag's. From the sync
  to the verification it holds the build lock (`[build] lock` of the host configuration), and the
  builds it runs hold it with it.
- `release/sign.py` is the signing step, the one program given the key location. It passes it
  to the tree's `sign_target_files_apks`, `ota_from_target_files` and `img_from_target_files`
  and opens no key itself. The key location holds, each as `<name>.pk8` and `<name>.x509.pem`:
  `releasekey`, which replaces the build's `testkey` and the keys modules carry for their APKs;
  a key of the same name for each of the build's platform keys an APK of the image uses
  (`platform`, `shared`, `media`, `networkstack`, `bluetooth`, `nfc`, `sdk_sandbox`); for each
  APEX of the image that is not presigned, `<apex>`, with `<apex>.pem` the same key, which signs
  its payload; and the key a feature names for an app (`key` in `feature.toml`). `avb.pem`, an
  RSA-4096 key, signs vbmeta and every vbmeta image it chains. Every platform key the location
  holds replaces the build's of its name, in the SELinux signers too. APKs and APEXes the build
  marks presigned keep their signatures.
- `release/verify.py` verifies without the key location. Third-party apps are the APKs and APEXes
  the build marks presigned, each named with its certificate's subject and digest. Every other
  APK and APEX, in the partitions and inside the APEXes, is signed with the key the signing step
  names for it, all files of one key with one certificate; `releasekey`'s certificate is the one
  the system trusts for OTA packages and verifies the OTA package's signatures, `platform`'s
  holds seinfo `platform`. No signature, APEX payload key, AVB key, SELinux signer or trusted OTA
  certificate is a key whose private half is in the tree. Each APEX's payload is signed with the
  payload key it carries, its container's key unless it is presigned; vbmeta and the images it
  chains carry one AVB key. Every privileged permission of the platform a privileged app
  requests is in its partition's or APEX's privapp-permissions. Every file of a partition is in
  the build's list of the files the product installs there (`file_list.txt`). No file of the
  image has the content of a file of `test/`, and no module or package `test/` defines is in it.
  Any failure stops the release.
- The artifacts are `<out>/releases/lujarch-<n>-<commit>/`:

  | File | What |
  |---|---|
  | `lujarch-<n>-<commit>.zip` | The OTA package, which System updates installs |
  | `lujarch-<n>-<commit>-images.zip` | The images that install the system from scratch |
  | `notes.md` | The release notes |
  | `sha256` | The SHA-256 of each other file, `<file>: <sha256>` |

- The release notes list, per target file, the items added, the items whose text changed and the
  items removed, matched by title, and whether `target/terms.md` changed. The first release's
  notes list the titles of every item, per target file.
- A release that ends before its artifacts are written removes its tag and its worktree.

## What a release never is

- Built from a dirty tree, from a commit that did not pass the test flow, or with test keys.
- Installed on a phone by any path other than the phone's own System updates.
