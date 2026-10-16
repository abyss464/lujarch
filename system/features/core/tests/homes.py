"""Every capability has an entry in the UI, in the one place Android keeps its concept: each Home of
the target is reached in Settings by its path, and the switches its item names are on that page.
Settings has no tile that gathers Lujarch's features.

verifies: CORE-1, CORE-2, CORE-3
timeout: 3600
"""
import re
import time

from lujarch_test import AppProbe, ROOT, Failure, check, device, note, run, step

ITEM = re.compile(r"^### ([A-Z]+-\d+) ")
# Entries that exist only in the state they act on (the updates test reaches them there).
SHOWN_IN_STATE = {"Return to the previous build"}
# Android's top-level Settings: the places a capability may live in.
NATIVE = {"Network & internet", "Connected devices", "Apps", "Notifications", "Battery",
          "Storage", "Sound & vibration", "Display", "Wallpaper & style", "Accessibility",
          "Security & privacy", "Location", "Passwords & passkeys", "Passwords, passkeys & accounts",
          "Digital Wellbeing & parental controls", "Google", "System", "About phone", "Search",
          "Emergency information"}


def targets():
    for path in sorted((ROOT / "target").glob("*.md")):
        item, fields = None, {}
        for line in path.read_text().splitlines() + ["### END-0 "]:
            m = ITEM.match(line)
            if m:
                if item and fields.get("Verify") == "vm":
                    yield item, fields.get("Home", ""), fields.get("Switch", "")
                item, fields = m.group(1), {}
            elif line.startswith("- ") and ":" in line:
                key, _, value = line[2:].partition(":")
                fields[key.strip()] = value.strip()


def paths():
    """(item, path segments after Settings, switch names) for every Settings home."""
    for item, home, switch in targets():
        names = [n.strip() for names in re.findall(r"(?:^|;)\s*([A-Z][^:;>]*?): (?:on, off)", switch)
                 for n in names.split(", ")]
        for alternative in home.split(";"):
            parts = [p.strip() for p in alternative.split(">")]
            if parts[0] == "Settings" and len(parts) > 1 and parts[1] != "Search":
                yield item, parts[1:], names


def open_path(d, segments, probe):
    """Opens Settings and taps through segments: (app) is the probe, menu opens the page's menu,
    and another placeholder (one device, folder or group) ends the path at the page listing it."""
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.SETTINGS", wait=2)
    for seg in segments:
        if seg == "(app)":
            d.tap(d.scroll_to(contains="See all", limit=6))
            d.tap(d.scroll_to(text=probe))
        elif seg.startswith("(") or seg in SHOWN_IN_STATE:
            return
        elif seg == "menu":
            d.tap(desc="More options")
        else:
            d.tap(d.scroll_to(text=seg, limit=10, quiet=True))
        time.sleep(1.5)


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    step("Settings has no tile of Lujarch's own")
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.SETTINGS", wait=2)
    tiles = set()
    for _ in range(6):
        tiles |= {n.text for n in d.ui() if n.text and n.rid.endswith("title")}
        d.swipe(540, 1800, 540, 700)
    note(f"top level: {sorted(tiles)}")
    check(not [t for t in tiles if "Lujarch" in t], "no Lujarch tile")
    foreign = sorted(t for t in tiles if t not in NATIVE)
    check(not foreign, f"only Android's own top-level places ({foreign})")

    unreached, unswitched, seen = [], [], set()
    for item, segments, switches in paths():
        key = " > ".join(segments)
        if key in seen and not switches:
            continue
        seen.add(key)
        step(f"{item}: Settings > {key}")
        try:
            open_path(d, segments, "Probe")
        except Failure as e:
            unreached.append(f"{item} {key}: {e}")
            continue
        for name in switches:
            try:
                d.switch_of(name)
            except Failure:
                unswitched.append(f"{item} {name}")
    d.home()
    check(not unreached, f"every home is reached ({unreached})")
    check(not unswitched, f"every switch is on its home ({unswitched})")


run(main)
