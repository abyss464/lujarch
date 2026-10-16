"""Nothing happens silently: when the system refuses, restricts or stops something on its own, it
shows what happened and which part of the system did it. A new app's start for its alarm, refused
by the defaults for new apps, is shown with Autostart; a read the defaults keep from a new app
running in the background asks in a notification. Questions waiting together are one notification
that names each.

verifies: CORE-6
timeout: 1500
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step


def notifications(d, package=None):
    out = d.sh("dumpsys notification --noredact", check=False)
    return [block for block in out.split("NotificationRecord(")[1:]
            if package is None or f"pkg={package}" in block]


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    d.sh(f"pm grant {p.PACKAGE} android.permission.POST_NOTIFICATIONS")
    try:
        step("a new app's start refused by the defaults is shown with Autostart")
        d.start(f"-n {p.PACKAGE}/.Probe", wait=2)
        p.do("alarmclock", "--ei seconds 15", restart=False)
        d.home()
        time.sleep(1)
        d.sh(f"am kill {p.PACKAGE}")
        before = len(notifications(d))
        time.sleep(25)
        started = p.results().get("started_by") == "alarm"
        # The question of the permissions, about Autostart: "Allow Probe to start while it is closed?"
        shown = [b for b in notifications(d, "com.android.permissioncontroller")
                 if "Probe" in b and "start while it is closed" in b]
        note(f"the alarm started the app: {started}; notifications about it: {len(shown)} "
             f"(of {len(notifications(d))}, {before} before)")
        d.sh("cmd statusbar expand-notifications")
        time.sleep(1.5)
        d.shot("autostart-refused")
        d.sh("cmd statusbar collapse")
        check(started or shown, "the refused start is shown, or the alarm is not stopped")

        step("a read the defaults keep from an app in the background asks in a notification")
        d.sh(f"am broadcast -a org.lujarch.probe.DO -n '{p.PACKAGE}/.Probe$Background' --es do device")
        time.sleep(4)
        # "... to read the model, system build, battery and other details of this phone?"
        asked = [b for b in notifications(d, "com.android.permissioncontroller")
                 if "Probe" in b and "read the model" in b]
        note(f"questions shown: {len(asked)}")
        d.sh("cmd statusbar expand-notifications")
        time.sleep(1.5)
        d.shot("background-question")
        d.sh("cmd statusbar collapse")
        check(asked, "the question shows in a notification from the permissions")
    finally:
        d.home()


run(main)
