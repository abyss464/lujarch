# Release flow

A release is a signed build of one commit that passed the test flow (process/test-flow.md). The
release flow ends with the release's artifacts; bringing them to a phone is delivery, which is
the owner's (the owner's repository).

## When

Only when the owner asks for a release.

## Preconditions

- The commit is on `main` and the worktree it is built from is clean.
- The commit passed the test flow, with the pins it carries (`lock.toml`, `upstream.xml`).

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

## What a release never is

- Built from a dirty tree, from a commit that did not pass the test flow, or with test keys.
- Installed on a phone by any path other than the phone's own System updates.
