"""Every app, those that come with the system included, can be set to every choice of Lujarch's
permissions; the system's own core processes are never restricted and the lists do not show them.

verifies: PERM-10
timeout: 1200
"""
import time

from lujarch_test import check, choose, device, note, run, step

PERMISSION = "Motion and environment sensors"
OP = "LUJARCH_SENSORS"
# Apps that come with the system: one in the launcher, one only under Show system.
CLOCK = ("Clock", "com.android.deskclock")
# The system's core processes run with the system's own uids.
CORE = ["Android System", "Settings", "Phone Services", "Shell"]


def listed(d, show_system):
    d.unlock()
    d.start("-f 0x10008000 -a android.intent.action.MANAGE_PERMISSION_APPS --es "
            "android.intent.extra.PERMISSION_GROUP_NAME android.permission-group.LUJARCH_SENSORS")
    d.wait(text="Allowed", timeout=20)
    if show_system:
        d.tap(desc="More options")
        d.tap(d.wait(text="Show system"))
        time.sleep(2)
    names = set()
    for _ in range(14):
        before = len(names)
        names |= set(d.texts())
        d.swipe(540, 1800, 540, 700)
        if len(names) == before:
            break
    return names


def main():
    d = device()
    step("the permission's list: the apps, with Show system those that come with the system")
    shown = listed(d, False)
    everything = listed(d, True)
    d.shot("sensors-show-system")
    extra = sorted(everything - shown)
    note(f"Show system adds {len(extra)}: {extra[:15]}")
    check(CLOCK[0] in shown, "Clock, an app that comes with the system, is listed")
    check(len(extra) >= 3, "Show system adds the system's other apps")
    check(not set(CORE) & everything, f"no core process is listed ({sorted(set(CORE) & everything)})")

    step("an app that comes with the system takes every choice")
    try:
        for choice, mode in (("Return empty data", "ignore"), ("Don't allow", "deny"),
                             ("Ask every time", "default"), ("Allow", "allow")):
            choose(d, CLOCK[1], PERMISSION, choice)
            got = d.appop(CLOCK[1], OP)
            check(got == mode, f"Clock: {choice} ({got})")
        d.shot("clock-sensors")
    finally:
        d.sh(f"cmd appops set {CLOCK[1]} {OP} allow", check=False)
        d.home()


run(main)
