"""Privacy indicators: the privacy chip announces an app's use of the microphone, the location
indicator its use of the location; each switch says what turning it off lets apps do unseen and
turns off without a confirmation; off, its indicator does not show.

verifies: SB-2
timeout: 1500
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step

SWITCHES = ("Privacy chip", "Location", "Privacy ring", "Privacy dot")


def items_page(d):
    d.unlock()
    d.start("-f 0x10008000 -n 'com.android.settings/.Settings$SystemDashboardActivity'")
    d.tap(d.scroll_to(text="Status bar"))
    d.tap(d.scroll_to(text="Status bar items"))
    d.scroll_to(text="Privacy indicators")


def during(d, p, action, seconds=14):
    """The status bar's elements (description and id) while the probe does action."""
    p.clear()
    d.sh(f"am start -n {p.PACKAGE}/.Probe --activity-single-top --es do {action} "
         f"--ei seconds {seconds}")
    time.sleep(2)
    d.home()
    seen = set()
    for _ in range(4):
        seen |= {(n.desc or n.text, n.rid.split("/")[-1]) for n in d.ui(windows=True)
                 if n.bounds[3] <= 200 and (n.desc or n.text)}
        time.sleep(0.5)
    deadline = time.monotonic() + seconds + 10
    while action not in p.results() and time.monotonic() < deadline:
        time.sleep(1)
    note(f"{action}: {sorted(seen)}")
    return seen


def chip(seen, word):
    """Whether the privacy chip announces word."""
    return any(rid == "privacy_chip" and word in desc.lower() for desc, rid in seen)


def location(seen):
    return any("location" in desc.lower() for desc, _ in seen)


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.sh(f"pm grant {p.PACKAGE} android.permission.RECORD_AUDIO")
    d.sh(f"pm grant {p.PACKAGE} android.permission.ACCESS_FINE_LOCATION")
    try:
        step("four switches, each saying what turning it off lets apps do unseen")
        items_page(d)
        for name in SWITCHES:
            check(d.switch_of(name).checked, f"{name} is on")
            lines = d.summary(name)
            check(any(line.startswith("Off:") for line in lines), f"{name}: {lines}")
        d.shot("privacy-indicators")

        step("on: the chip announces the microphone, the indicator the location")
        check(chip(during(d, p, "mic"), "microphone"), "the chip announces the microphone")
        d.shot("mic-in-use")
        check(location(during(d, p, "track")), "the location indicator shows")

        step("off without a confirmation: nothing announces it")
        items_page(d)
        for name in ("Privacy chip", "Location"):
            d.tap(d.switch_of(name))
            time.sleep(1)
            check(not d.find(text="Cancel") and not d.switch_of(name).checked,
                  f"{name} turned off without a confirmation")
        seen = during(d, p, "mic")
        check(not chip(seen, "microphone"), "no chip for the microphone")
        check(any(rid.startswith("privacy_dot") for _, rid in seen),
              "the privacy dot, still on, still shows")
        check(not location(during(d, p, "track")), "no location indicator")
    finally:
        items_page(d)
        for name in SWITCHES:
            if not d.switch_of(name).checked:
                d.tap(d.switch_of(name))
        d.home()


run(main)
