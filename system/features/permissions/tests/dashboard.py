"""The privacy dashboard lists every permission of this target that apps used, each with its apps
and each use with its time and outcome, over as far back as the history is kept; Keep history
chooses 1, 7, 30 or 90 days, and a shorter choice asks first.

verifies: PERM-19, PERM-20
timeout: 1500
"""
import re
import time

from lujarch_test import AppProbe, Failure, check, device, note, run, step

SETTING = "lujarch_permissions_history_days"
USED = {"Motion and environment sensors": "sensors", "Clipboard": "copy,clipboard",
        "Installed apps": "apps", "Device identifiers": "ids", "Device info": "device",
        "Network info": "network", "Phone": "phone"}
OUTCOMES = ("Returned empty data", "Not allowed", "Asked")
TIME = re.compile(r"\b\d{1,2}:\d{2}\b")


def dashboard(d):
    d.start("-f 0x10008000 -a android.intent.action.REVIEW_PERMISSION_USAGE", wait=3)
    d.wait(text="See other permissions")


def menu(d):
    d.tap(desc="More options")
    time.sleep(1)
    return d.texts()


def summary_of(d, title):
    return next(iter(d.summary(title, reach=100)), "")


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.put_setting("global", SETTING, None)
    try:
        uses(d, p)
        timeline(d)
        history(d)
    finally:
        d.put_setting("global", SETTING, None)
        d.home()


def uses(d, p):
    step("the probe uses each permission; the sensors under every outcome")
    for actions in USED.values():
        p.do(actions, "--es text dashboard")
    for choice in ("ignore", "deny"):
        d.sh(f"cmd appops set {p.PACKAGE} LUJARCH_SENSORS {choice}")
        p.do("sensors")
    d.sh(f"cmd appops set {p.PACKAGE} LUJARCH_SENSORS default")
    d.home()
    p.do("sensors", foreground=False, timeout=8)

    step("the dashboard lists every permission the probe used")
    dashboard(d)
    for _ in range(3):
        d.tap(text="See other permissions")
        try:
            d.scroll_to(text="Clipboard", limit=4, quiet=True)
            break
        except Failure:
            d.swipe(540, 700, 540, 1800)
    unused = {}
    for title in USED:
        s = summary_of(d, title)
        if not s.startswith("Used by"):
            unused[title] = s
    check(not unused, f"each says which apps used it ({unused})")
    d.shot("dashboard-used")


def timeline(d):
    step("each use with its time and outcome")
    d.tap(d.scroll_to(text="Motion and environment sensors"))
    d.wait(contains="Timeline of when apps used")
    seen = []
    for _ in range(6):
        seen += [t for t in d.texts() if t not in seen]
        if all(any(o in t for t in seen) for o in OUTCOMES):
            break
        d.swipe(540, 1800, 540, 900)
    note(f"timeline: {seen}")
    check("Probe" in seen, "the probe is listed")
    for o in OUTCOMES:
        check(any(o in t for t in seen), f"a use shows the outcome {o}")
    check(sum(1 for t in seen if TIME.search(t)) >= 3, "the uses show their times")
    d.shot("sensors-timeline")
    d.back()


def history(d):
    step("Keep history: 1, 7, 30 or 90 days, 7 by default; the range follows it")
    dashboard(d)
    items = menu(d)
    check("Show 7 days" in items, f"the dashboard reaches back 7 days ({items})")
    d.tap(text="Keep history")
    for c in ("1 day", "7 days", "30 days", "90 days"):
        check(d.find(text=c), f"Keep history offers {c}")
    check(d.find(text="7 days").checked, "7 days is chosen")
    d.shot("keep-history")
    d.tap(text="30 days")
    time.sleep(1)
    check(not d.find(text="Delete older history?"), "a longer choice does not ask")
    check(d.setting("global", SETTING) == "30", "kept 30 days")
    dashboard(d)
    items = menu(d)
    check("Show 30 days" in items, f"the dashboard reaches back 30 days ({items})")
    d.tap(text="Show 30 days")
    check(d.wait(text="Past\n30 days", timeout=15), "the dashboard shows the past 30 days")
    d.shot("past-30-days")

    step("a shorter choice asks first, since the older records are deleted")
    menu(d)
    d.tap(text="Keep history")
    d.tap(text="1 day")
    d.wait(text="Delete older history?")
    check(d.find(text="Records older than 1 day are deleted."), "the question says what goes")
    d.shot("shorter-asks")
    d.tap(text="Cancel")
    check(d.setting("global", SETTING) == "30", "cancelled, still 30 days")
    menu(d)
    d.tap(text="Keep history")
    d.tap(text="1 day")
    d.tap(d.wait(text="Delete"))
    time.sleep(1)
    check(d.setting("global", SETTING) == "1", "kept 1 day")
    dashboard(d)
    items = menu(d)
    check(not any(re.fullmatch(r"Show \d+ days?", t) for t in items),
          f"with 1 day there is no longer range ({items})")
    d.back()


run(main)
