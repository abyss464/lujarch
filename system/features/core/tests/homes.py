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
# Entries that exist only in the state they act on; each feature's tests reach them there.
SHOWN_IN_STATE = {
    "Return to the previous build",  # while a new build waits for confirmation
    "Discovery and connections",  # once Sync has a device identity
    "Passwords", "2FA codes", "Settings",  # once Lujarch Vault has a source
    "Node locations",  # once Proxy has a subscription
}
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


def all_apps(d):
    """From Apps to the list of every app: See all under the recent apps, or All apps while no
    app was used lately."""
    row = d.find(contains="See all") or d.find(text="All apps")
    d.tap(row or d.scroll_to(text="All apps", limit=6))


def open_path(d, segments, probe, opened=None):
    """Taps through segments from Settings' top, or from the pages `opened` left open (the pages
    each segment opened, from an earlier walk that shares the start of the path): (app) is the
    probe, menu opens the page's menu, and another placeholder (one device, folder or group) ends
    the path at the page listing it. The pages each segment opened, or None when the walk ended
    where no later walk can go on from."""
    pages = list(opened or [])
    if not pages:
        d.unlock()
        d.start("-f 0x10008000 -a android.settings.SETTINGS", wait=2)
    previous = segments[len(pages) - 1] if pages else None
    for seg in segments[len(pages):]:
        if seg == "(app)":
            all_apps(d)
            d.tap(d.scroll_to(text=probe))
            pages.append(2)
        elif seg.startswith("(") or seg in SHOWN_IN_STATE:
            return None
        elif seg == "menu":
            d.tap(desc="More options")
            return None
        elif previous == "Apps" and segments[0] == "Apps" and len(pages) == 1 and not d.find(text=seg):
            # One app: Apps lists the recent ones, the list of every app the rest.
            all_apps(d)
            d.tap(d.scroll_to(text=seg))
            pages.append(2)
        else:
            d.tap(d.scroll_to(text=seg, limit=10, quiet=True))
            pages.append(1)
        previous = seg
        time.sleep(1.5)
    return pages


def walk(d, segments, last):
    """Reaches segments, going back from the last walk's pages as far as both paths share, or
    from Settings' top when that fails. Returns the pages it opened, as open_path does."""
    last_segments, last_pages = last
    shared = 0
    if last_pages:
        while (shared < min(len(segments), len(last_pages)) and
               segments[shared] == last_segments[shared]):
            shared += 1
        shared = min(shared, len(segments) - 1)
    if shared:
        for _ in range(sum(last_pages[shared:])):
            d.back()
            time.sleep(0.6)
        try:
            return open_path(d, segments, "Probe", last_pages[:shared])
        except Failure:
            pass
    return open_path(d, segments, "Probe")


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
    sections = {segments[0] for _, segments, _ in paths()}
    pages = {segments[-1] for _, segments, _ in paths()
             if len(segments) > 1 and "(app)" not in segments} - sections
    lujarch = sorted(t for t in tiles if "Lujarch" in t or t in pages)
    check(not lujarch, f"no tile of a Lujarch page at the top level ({lujarch})")

    unreached, unswitched, seen = [], [], set()
    # Walked in the order of their paths, so each goes on from the pages the last one opened.
    last = ([], None)
    for item, segments, switches in sorted(paths(), key=lambda w: (w[1], w[0])):
        key = " > ".join(segments)
        if key in seen and not switches:
            continue
        seen.add(key)
        step(f"{item}: Settings > {key}")
        try:
            last = (segments, walk(d, segments, last))
        except Failure as e:
            unreached.append(f"{item} {key}: {e}")
            last = ([], None)
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
