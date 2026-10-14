# Target

`target/` is the only description of what Lujarch is. Implementation and tests follow it.
Nothing else in the repository describes system behavior. The owner approves every change to it.

## Files

- `target/<feature>.md`: one file per feature, named after the feature directory
  `system/features/<feature>/`.
- `target/core.md`: rules that hold for the whole system (feature `core`).
- `target/device.md`: what the Xiaomi 13 device layer guarantees (`system/device/fuxi/`).
- `target/terms.md`: user-facing wording in English, Simplified Chinese and Japanese. It has no
  items and is the only target file with Chinese or Japanese text.

## Items

Every other file is a list of items:

```
# <Feature title>

<One short paragraph: what this feature is for the user.>

## <Group title>

### <CODE>-<n> <Item title in sentence case>

- Home: <UI location> | none
- Switch: <UI location of the control, its options> | none
- Default: <default state or value> | none
- Verify: vm | phone

<Behavior: short paragraphs or bullets. Every sentence is observable.>
```

- `CODE` is the file's own prefix (2 to 5 capital letters), the same for every item of the file
  and used by no other file. `n` starts at 1 and increases in file order without gaps.
- An item is one observable behavior with one switch, or one guarantee.
- `Home` is where the user meets the behavior, named by its English UI labels
  (`Settings > Network & internet > Proxy`). `none` for a guarantee with no surface.
- `Switch` is the control that turns it on or off or chooses among its options, in its native
  home. `none` only for a guarantee that must not be a choice; the item text says what is
  guaranteed.
- `Verify: vm` when the virtual device can show it; `Verify: phone` when only the Xiaomi 13 can.
- `##` groups follow the user's path through the feature. No other headings.

## Content

- Present tense, user terms, English.
- What the system is and does, never why, since when, by whom, or what it used to do.
- No implementation: no paths, property or settings keys, package, class or method names,
  commands, SELinux names, app-op names or numbers, unless the user sees them on screen.
- Each behavior is stated once, in the file of the feature that owns it; other files refer to
  that file instead of restating it.
- Personal configuration (servers, nodes, accounts, folders, device names) is never target: the
  target states the generic behavior and leaves the personal part to the user's settings.

`tools/lujarch check` rejects, in target files: dates, decision references (`D-` and three
digits), `TODO`, `TBD`, `FIXME`, legacy names, headings outside the format, missing or misordered
fields, a `Verify` other than `vm` or `phone`, duplicate prefixes and gaps in numbering.

## Approval

- Changes to `target/` are proposed on a branch named `proposal/<topic>`.
- After the owner approves, the change lands on `main` as one commit whose message carries the
  trailer `Approved-by: <owner>`.
- `tools/lujarch check` rejects every commit on the first-parent history of `main` that changes
  `target/` without that trailer.

## Verification

- A test names the items it verifies in a line `verifies: <CODE>-<n>[, <CODE>-<n>...]`
  (in a comment, in the test's own language). A test that verifies no item is not written.
- Implementation that serves an item names it the same way where the link is not obvious:
  a switch in a feature's `settings.toml` carries the item it implements.
- `tools/lujarch status` computes progress from the target, the tests and the results of the last
  test flow run: per file, the items, the items covered by a test and the items verified.
  Progress is never written down.
