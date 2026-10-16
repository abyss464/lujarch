"""Every page and switch of the target is found through Settings search by its title, and the result
opens the page with it in view. The titles come from the target's Home and Switch lines.

verifies: CORE-5
timeout: 2400
"""
import re
import time

from lujarch_test import ROOT, check, device, note, run, step

ITEM = re.compile(r"^### ([A-Z]+-\d+) ")
# Path segments after which a home is no page of its own: one app's, one entry's, a menu.
PLACEHOLDER = re.compile(r"^\(.*\)$|^menu$")
# Entries that exist only in the state they act on (homes.py): nothing to find otherwise.
SHOWN_IN_STATE = {"Return to the previous build"}
# Switches a page keeps in its menu: a result opens the page, whose menu shows them.
IN_MENU = set()


def targets():
    """(item, home path segments, switch titles) of every item the virtual device verifies."""
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


def queries():
    """Titles to search for, each with the items that name it."""
    found = {}
    for item, home, switch in targets():
        for alternative in home.split(";"):
            parts = [p.strip() for p in alternative.split(">")]
            if parts[0] != "Settings" or len(parts) < 2:
                continue
            kept = []
            for p in parts[1:]:
                if PLACEHOLDER.match(p):
                    break
                kept.append(p)
            if kept and kept[-1] != "Search":
                found.setdefault(kept[-1], set()).add(item)
        for path, names in re.findall(r"((?:^|;)[^;:]*?>)?\s*([A-Z][^:;>]*?): (?:on, off|off, on)", switch):
            for name in names.split(", "):
                found.setdefault(name.strip(), set()).add(item)
                if "> menu >" in path:
                    IN_MENU.add(name.strip())
    for name in SHOWN_IN_STATE:
        found.pop(name, None)
    return found


def search(d, query):
    d.start("-f 0x10008000 -a com.android.settings.action.SETTINGS_SEARCH", wait=2)
    d.type(query)
    time.sleep(3)
    return [n for n in d.ui() if n.text and n.rid.endswith("title")]


def main():
    d = device()
    d.unlock()
    wanted = queries()
    note(f"{len(wanted)} titles: {', '.join(sorted(wanted))}")
    missing, unopened = [], []
    for query, items in sorted(wanted.items()):
        step(f"search “{query}” ({', '.join(sorted(items))})")
        results = search(d, query)
        hit = next((n for n in results if n.text == query), None)
        if not hit:
            missing.append(query)
            note(f"results: {[n.text for n in results][:8]}")
            continue
        d.tap(hit, wait=3)
        if query in IN_MENU and not d.find(text=query):
            d.tap(d.wait(desc="More options", timeout=10))
            time.sleep(1)
        if not (d.find(text=query) or d.find(contains=query)):
            unopened.append(query)
        d.home()
    d.shot("search-last")
    check(not missing, f"every title is found ({missing} are not)")
    check(not unopened, f"every result opens with the title in view ({unopened} do not)")


run(main)
