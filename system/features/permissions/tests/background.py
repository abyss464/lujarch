"""Autostart decides whether the system starts an app that is not running for its alarm; network
access is chosen in Mobile data & Wi-Fi; notifications are Android's one switch, which is the
Notifications permission.

verifies: PERM-15, PERM-16, PERM-17
timeout: 1500
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step


def app_info(d, package):
    d.unlock()
    d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{package}")
    d.wait(text="App info")


def autostart_choice(d, p, choice):
    app_info(d, p.PACKAGE)
    d.tap(d.scroll_to(text="App battery usage"))
    d.tap(d.scroll_to(text="Autostart"))
    d.tap(d.wait(text=choice))
    time.sleep(1)
    summary = d.summary("Autostart")
    return summary


def started_by_alarm(d, p):
    p.do("alarm", "--ei seconds 15")
    d.home()
    time.sleep(2)
    d.sh(f"am kill {p.PACKAGE}")
    time.sleep(1)
    alive = d.sh(f"pidof {p.PACKAGE}", check=False).strip()
    check(not alive, "the probe no longer runs")
    time.sleep(25)
    return p.results().get("started_by") == "alarm"


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.start(f"-n {p.PACKAGE}/.Probe", wait=3)
    d.home()

    step("Autostart: Allow lets the system start the app for its alarm")
    summary = autostart_choice(d, p, "Allow")
    note(f"Autostart: {summary}")
    check(started_by_alarm(d, p), "allowed, the alarm started the probe")
    step("Autostart: Don't allow keeps it stopped, and the row says what that costs")
    summary = autostart_choice(d, p, "Don't allow")
    d.shot("autostart-dont-allow")
    check(any("push" in s.lower() or "lost" in s.lower() for s in summary),
          f"the summary says pushes can be lost ({summary})")
    check(not started_by_alarm(d, p), "not allowed, the alarm did not start the probe")
    autostart_choice(d, p, "Allow")

    step("Mobile data & Wi-Fi: Allow network access")
    got = p.do("connect", "--es to 192.168.99.1:80")["connect"]
    check(got.startswith("connected"), f"with network access the probe connects ({got})")
    app_info(d, p.PACKAGE)
    d.tap(d.scroll_to(text="Mobile data & Wi-Fi"))
    switch = d.switch_of("Allow network access")
    d.tap(switch)
    d.shot("network-access-off")
    got = p.do("connect", "--es to 192.168.99.1:80")["connect"]
    check(not got.startswith("connected"), f"without network access it cannot ({got})")
    app_info(d, p.PACKAGE)
    d.tap(d.scroll_to(text="Mobile data & Wi-Fi"))
    d.tap(d.switch_of("Allow network access"))
    got = p.do("connect", "--es to 192.168.99.1:80")["connect"]
    check(got.startswith("connected"), f"turned on again it connects ({got})")

    step("Notifications: the app's switch is the Notifications permission")
    app_info(d, p.PACKAGE)
    d.tap(d.scroll_to(text="Notifications"))
    switch = d.switch_of(f"All {('Probe')} notifications")
    if switch.checked:
        d.tap(switch)
    granted = d.sh(f"dumpsys package {p.PACKAGE} | grep 'POST_NOTIFICATIONS: granted'", check=False)
    check("granted=false" in granted, f"off: the permission is not granted ({granted.strip()})")
    check("enabled=false" in p.do("notify")["notify"], "off: the app's notifications are disabled")
    app_info(d, p.PACKAGE)
    d.tap(d.scroll_to(text="Notifications"))
    d.tap(d.switch_of("All Probe notifications"))
    d.shot("notifications-on")
    granted = d.sh(f"dumpsys package {p.PACKAGE} | grep 'POST_NOTIFICATIONS: granted'", check=False)
    check("granted=true" in granted, f"on: the permission is granted ({granted.strip()})")
    check("enabled=true" in p.do("notify")["notify"], "on: the app's notifications are enabled")
    d.home()


run(main)
