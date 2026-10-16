"""Status bar items lists everything the status bar can show, in groups, each with one switch, on
by default; an item the phone cannot show is not listed. Off, an item shows neither in the status
bar, nor on the lock screen's status bar, nor in the shade header.

verifies: SB-1
timeout: 1800
"""
import re
import time

from lujarch_test import check, device, note, run, step

GROUPS = ["Connectivity", "Sound and modes", "Status", "Activity chips", "Privacy indicators",
          "Clock, battery and notifications"]
# What the virtual device's status bar shows of an item, and where it shows it.
SHOWN = {
    "Wi-Fi": (r"^Wifi signal", ("status bar", "lock screen", "shade")),
    "Mobile signal": (r"^Android Virtual Operator, ", ("status bar", "lock screen")),
    "Battery": (r"^Battery", ("status bar", "lock screen", "shade")),
    "Clock": (r"^\d{1,2}:\d{2}", ("status bar",)),
    "Notification icons": (r"notification: ", ("status bar",)),
}


def items_page(d):
    d.unlock()
    d.start("-f 0x10008000 -n 'com.android.settings/.Settings$SystemDashboardActivity'")
    d.tap(d.scroll_to(text="Status bar"))
    d.tap(d.scroll_to(text="Status bar items"))
    d.wait(text="Connectivity")


def shown(d, place):
    """What the top of the screen shows in place: its descriptions and texts."""
    if place == "status bar":
        d.unlock()
        d.home()
    elif place == "lock screen":
        d.key("KEYCODE_SLEEP")
        time.sleep(1.5)
        d.key("KEYCODE_WAKEUP")
        time.sleep(2)
    else:
        d.unlock()
        d.home()
        d.sh("cmd statusbar expand-settings")
        time.sleep(2)
    got = {n.desc or n.text for n in d.ui(windows=True) if (n.desc or n.text) and n.bounds[3] <= 200}
    if place == "shade":
        d.sh("cmd statusbar collapse")
    d.unlock()
    return got


def visible(d, item, place):
    pattern = SHOWN[item][0]
    return any(re.search(pattern, t) for t in shown(d, place))


def main():
    d = device()
    d.sh("cmd notification post -t 'Status bar test' lujarch-sb 'An icon for the status bar'")
    try:
        listing(d)
        for item in SHOWN:
            switched(d, item)
    finally:
        items_page(d)
        for item in SHOWN:
            sw = d.switch_of(item)
            if not sw.checked:
                d.tap(sw)
        d.sh("cmd notification cancel lujarch-sb", check=False)
        d.home()


def listing(d):
    step("the groups and their items, one switch each, all on")
    items_page(d)
    rows, order, seen = {}, [], set()
    for _ in range(12):
        nodes = d.ui()
        for n in sorted((n for n in nodes if n.text), key=lambda n: n.y):
            if n.text in GROUPS and n.text not in order:
                order.append(n.text)
            elif n.text not in seen and n.rid.endswith("title"):
                sw = [s for s in nodes if s.checkable and abs(s.y - n.y) < 160]
                rows[n.text] = sw[0].checked if sw else None
            seen.add(n.text)
        if "Lock screen carrier text" in seen:
            break
        d.swipe(540, 1800, 540, 700)
    d.shot("status-bar-items")
    note(f"{len(rows)} items: {', '.join(rows)}")
    check(order == GROUPS, f"the groups in order ({order})")
    missing = [r for r, on in rows.items() if on is None]
    check(not missing, f"each item has a switch ({missing} have none)")
    off = [r for r, on in rows.items() if on is False]
    check(not off, f"each is on by default ({off} are off)")
    check("Proxy" in rows, "Lujarch's proxy icon is an item like Android's")
    check("Dual-SIM signal, stacked" not in rows, "a single-SIM phone does not list the dual-SIM item")


def switched(d, item):
    step(f"{item}: off hides it everywhere, on shows it again")
    places = SHOWN[item][1]
    for place in places:
        check(visible(d, item, place), f"on: {item} shows in the {place}")
    items_page(d)
    d.tap(d.switch_of(item))
    check(not d.switch_of(item).checked, f"{item} switched off")
    if item == "Wi-Fi":
        d.home()
        d.shot("wifi-off-status-bar")
    for place in places:
        check(not visible(d, item, place), f"off: {item} does not show in the {place}")
    items_page(d)
    d.tap(d.switch_of(item))
    check(visible(d, item, places[0]), f"on again: {item} shows in the {places[0]}")


run(main)
