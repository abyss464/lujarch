"""An app whose notification icon is a colored picture, which Android shows as a filled shape, shows
a silhouette of its logo in the status bar and its notification when the system's set covers it;
with Fit notification icons off it shows its own icon.

verifies: ICON-2
timeout: 900
"""
import time

from lujarch_test import AppProbe, check, device, install_probe, note, run, share_near, step

# An app the system's set of notification icons covers; the probe is installed under its name and
# posts with its own icon, a filled square.
PACKAGE = "com.tencent.mm"
SETTING = "lujarch_icons_notifications"


def post(d, title):
    """The app posts its notification anew, with its own small icon."""
    d.sh(f"am broadcast -f 0x20 -a org.lujarch.probe.DO -n '{PACKAGE}/org.lujarch.probe.Probe$Background' "
         f"--es do notify --es title '{title}'")
    time.sleep(3)
    d.home()
    time.sleep(1)


def filled(d, name):
    """The share of the status bar icon's box its white shape fills."""
    icon = next((n for n in d.ui(windows=True)
                 if "Probe notification" in n.desc and n.bounds[3] <= 120), None)
    check(icon, "the app's icon is in the status bar")
    path = d.shot(name)
    x1, y1, x2, y2 = icon.bounds
    share = share_near(path, (255, 255, 255), tolerance=150, box=(x1, y1, x2, y2), step=1)
    note(f"{name}: the icon fills {share:.0%} of its box")
    return share


def notifications_page(d):
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.NOTIFICATION_SETTINGS")
    return d.switch_of("Fit notification icons")


def main():
    d = device()
    d.uninstall(PACKAGE)
    install_probe("app", package=PACKAGE)
    d.sh(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")
    for op in AppProbe.OPS:
        d.sh(f"cmd appops set {PACKAGE} {op} allow")
    try:
        step("Fit notification icons is on by default")
        check(notifications_page(d).checked, "Fit notification icons is on")
        step("on: the silhouette of the app's logo")
        post(d, "Fitted")
        fitted = filled(d, "silhouette")
        step("off: the app's own icon, a filled square")
        d.tap(notifications_page(d))
        check(d.setting("global", SETTING) == "0", "turned off")
        post(d, "Own")
        own = filled(d, "own-icon")
        check(own > 0.25, f"off: the own icon is a filled square ({own:.0%} of its box)")
        check(fitted < own * 0.6, f"on: a silhouette, not the filled square ({fitted:.0%} < {own:.0%})")
    finally:
        d.put_setting("global", SETTING, None)
        d.uninstall(PACKAGE)
        d.home()


run(main)
