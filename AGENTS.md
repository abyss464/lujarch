# Lujarch: rules for agents

Lujarch is an Android system for the Xiaomi 13 (`fuxi`), built on LineageOS 24.0 (Android 17) and
signed with its owner's keys. This repository is its only source. Read `README.md`, this file,
`target/core.md`, and the `process/` document of the work at hand.

| Document | What it governs |
|---|---|
| `target/` | What the system is: the target, approved by the owner (`process/target.md`) |
| `process/layout.md` | What lives where, here and in the owner's repository |
| `process/conventions.md` | Names, settings, services, strings, style |
| `process/test-flow.md` | Proving a commit against the target on the computer and virtual devices |
| `process/release-flow.md` | Building and signing a release |

## Principles

1. **Only the target.** The repository holds the target, its implementation, the tests against
   it and the tools. No plans, to-do lists, status or device logs, decision records, release
   narratives, comparisons with older systems, or compatibility code. Progress is computed by
   `tools/lujarch status`; history is git's.
2. **One source of truth.** Every fact lives in one place. What else needs it is generated from
   it or checked against it, never copied by hand.
3. **Highest consistency.** One way for each kind of thing (`process/conventions.md`).
4. **No decision records.** Documents say what is, never why or since when. A code comment states
   only a constraint an edit would otherwise break.
5. **The target is the only constraint.** Nothing is kept for an older repository, name, format or
   data layout. Data moves once, by a migration that lives in the owner's repository.

## Changing the target

- `target/` changes only on a branch `proposal/<topic>`. The owner approves it, and it lands on
  `main` as one commit with the trailer `Approved-by: <owner>`.
- Never commit a change to `target/` on `main` any other way; `tools/lujarch check` rejects it.
- Work that the target does not ask for is not done. When the target is unclear, ask the owner.

## Working

- A feature is `system/features/<feature>/` with `target/<feature>.md`; the layout is in
  `process/layout.md`.
- Changes to upstream projects are patches of the feature that needs them, applied in the order
  of `system/order`; never leave unsaved edits in the tree.
- Versions and pins live only in `lock.toml` and `upstream.xml`.
- Each test names the items it verifies. A test that verifies no item is not written.
- Run `tools/lujarch check` before every commit. It must report nothing.

## Flows

- **Test flow** (`process/test-flow.md`): any time, without approval, on the computer and on
  virtual devices, with test keys. Its outputs never leave the computer.
- **Release flow** (`process/release-flow.md`): only when the owner asks, from a commit that
  passed the test flow, with the release keys.
- This repository never touches a personal phone. Delivery to the owner's phone, its rules,
  backups and migrations are in the owner's repository, `~/Code/apps/lujarch-owner`.

## Rules

- Secrets are never read, printed, committed or copied: signing keys, proxy credentials and
  subscription addresses, controller secrets, sync identities, vault databases and key files.
  The tools know only where keys are, from the host configuration.
- SELinux stays enforcing and every domain is enforcing.
- Everything the system shows or does on its own has a switch in its native home
  (`target/core.md`).
- Strings exist in English, Simplified Chinese and Japanese (`target/terms.md`).
- Documents are in English; `README.md` is in Chinese and English.
- Failures are logged; nothing fails silently.
