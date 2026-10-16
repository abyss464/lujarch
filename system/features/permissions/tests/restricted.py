"""App info > menu > Allow restricted settings chooses Decided by Android, Allow or Lock; Lock keeps
the app's restricted settings closed even where Android would open them (here: notification access
for an app Android trusts by its install source).

verifies: PERM-14
timeout: 900
"""
import time

from lujarch_test import AppProbe, check, device, run, step

OP = "ACCESS_RESTRICTED_SETTINGS"
CHOICES = ("Decided by Android", "Allow", "Lock")


def choose(d, p, choice):
    d.unlock()
    d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{p.PACKAGE}")
    d.wait(text="App info")
    d.tap(desc="More options")
    d.tap(d.wait(text="Allow restricted settings"))
    d.wait(text="Lock", timeout=20)
    nodes = d.ui()
    current = next((c for c in CHOICES if d.find(text=c, nodes=nodes).checked), None)
    if choice:
        d.tap(text=choice)
        time.sleep(1.5)
    else:
        d.shot("restricted-choices")
        d.tap(text="Cancel")
    return current


def listener_page(d):
    d.start("-f 0x10008000 -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    d.tap(d.scroll_to(text="Probe"))
    d.wait(text="Allow notification access")


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    try:
        step("three choices, Decided by Android by default")
        check(choose(d, p, None) == "Decided by Android", "Decided by Android is chosen")
        step("Decided by Android: Android opens notification access to this app")
        listener_page(d)
        check(not d.find(text="Controlled by Restricted Setting"), "the switch is not restricted")

        step("Lock: notification access stays closed")
        choose(d, p, "Lock")
        check(d.appop(p.PACKAGE, OP) == "deny", f"locked ({d.appop(p.PACKAGE, OP)})")
        listener_page(d)
        check(d.find(text="Controlled by Restricted Setting"), "the switch says a restricted setting holds it")
        d.tap(d.switch_of("Allow notification access"))
        check(d.wait(text="App was denied access"), "turning it on is refused")
        d.shot("restricted-locked")
        d.tap(text="Close")
        check("org.lujarch.probe" not in d.sh("settings get secure enabled_notification_listeners"),
              "the listener stays off")

        step("Allow, then Decided by Android again")
        choose(d, p, "Allow")
        check(d.appop(p.PACKAGE, OP) == "allow", f"allowed ({d.appop(p.PACKAGE, OP)})")
        choose(d, p, "Decided by Android")
        check(d.appop(p.PACKAGE, OP) == "default", f"decided by Android ({d.appop(p.PACKAGE, OP)})")
    finally:
        d.sh(f"cmd appops set {p.PACKAGE} {OP} default", check=False)
        d.home()


run(main)
