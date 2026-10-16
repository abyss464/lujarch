"""Special app access > See more lists each access Android records that has no page of its own
elsewhere, with the apps that used it; when it is not allowed, Android ignores the app's requests
for it. Accesses with a page of their own are not listed.

verifies: PERM-13
timeout: 900
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step

# Accesses that have their own page in Special app access or the permission manager.
OWN_PAGES = ["Display over other apps", "Modify system settings", "Install unknown apps",
             "Alarms & reminders", "Picture-in-picture", "Full-screen notifications",
             "Camera", "Location", "Microphone", "Notifications"]


def ignored(d, p):
    """The vibrations of the probe that Android ignored for its access."""
    return [line for line in d.sh("dumpsys vibrator_manager").splitlines()
            if "ignored_app_ops" in line and p.PACKAGE in line]


def titles(d):
    seen = []
    for _ in range(8):
        before = len(seen)
        seen += [t for t in d.texts() if t not in seen]
        if len(seen) == before:
            break
        d.swipe(540, 1800, 540, 700)
    return seen


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    p.do("vibrate,wakelock")
    d.home()
    try:
        step("See more closes Special app access")
        d.unlock()
        d.start("-f 0x10008000 -n 'com.android.settings/.Settings$AppDashboardActivity'")
        d.tap(d.scroll_to(text="Special app access"))
        d.wait(text="All files access", timeout=20)
        rows = titles(d)
        check(rows[-1] == "See more", f"See more is the last row ({rows[-3:]})")

        step("Other access lists what the probe used, and nothing with a page of its own")
        d.tap(d.scroll_to(text="See more"))
        d.wait(text="Other access", timeout=20)
        listed = titles(d)
        d.shot("other-access")
        note(f"listed: {listed}")
        for access in ("Control vibration", "Prevent phone from sleeping"):
            check(access in listed, f"{access} is listed")
        doubled = [a for a in OWN_PAGES if a in listed]
        check(not doubled, f"no access with a page of its own ({doubled})")

        step("not allowed, Android ignores the app's requests")
        d.tap(d.scroll_to(text="Control vibration"))
        d.tap(d.wait(text="Probe", timeout=20))
        d.tap(d.wait(text="Don't allow"))
        time.sleep(1)
        check(d.appop(p.PACKAGE, "VIBRATE") == "ignore", "Control vibration: Don't allow")
        d.shot("vibration-dont-allow")
        before = set(ignored(d, p))
        got = p.do("vibrate")["vibrate"]
        check(got == "vibrated", f"the app is not told ({got})")
        check(set(ignored(d, p)) - before, "Android ignored its vibration")
    finally:
        d.sh(f"cmd appops set {p.PACKAGE} VIBRATE allow", check=False)
        d.home()


run(main)
