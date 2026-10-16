"""Nothing the system restricts by default stops alarms or the message notifications of apps the
user has not restricted: a new app, left with the defaults for new apps, is started for its alarm
clock and for a high-priority pushed message while it is not running, and its notification
shows.

verifies: CORE-13
timeout: 900
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step


def not_running(d, p):
    d.home()
    time.sleep(1)
    d.sh(f"am kill {p.PACKAGE}")
    time.sleep(1)
    check(not d.sh(f"pidof {p.PACKAGE}", check=False).strip(), "the app does not run")


def main():
    d = device()
    p = AppProbe(d)
    step("a new app keeps the defaults for new apps")
    p.install()
    d.sh(f"pm grant {p.PACKAGE} android.permission.POST_NOTIFICATIONS")
    autostart = d.appop(p.PACKAGE, "LUJARCH_AUTOSTART")
    note(f"Autostart: {autostart}")
    d.start(f"-n {p.PACKAGE}/.Probe", wait=2)

    step("its alarm clock goes off while it does not run")
    got = p.do("alarmclock", "--ei seconds 20", restart=False)
    note(f"alarm: {got.get('alarmclock')}")
    not_running(d, p)
    time.sleep(30)
    check(p.results().get("started_by") == "alarm", "the alarm started the app")

    step("a message pushed while it does not run shows its notification")
    not_running(d, p)
    # As Google Play services delivers a high-priority push: with the temporary allowlist of a
    # push message (PowerExemptionManager.REASON_PUSH_MESSAGING).
    d.sh(f"am broadcast --allow-fgs-start-reason 101 -a org.lujarch.probe.DO "
         f"-n '{p.PACKAGE}/.Probe$Background' --es do notify --es title 'A message'")
    time.sleep(5)
    shown = d.sh("dumpsys notification --noredact", check=False)
    check(f"pkg={p.PACKAGE}" in shown and "A message" in shown, "the message notification shows")
    d.sh("cmd statusbar expand-notifications")
    time.sleep(1.5)
    d.shot("message-notification")
    d.sh("cmd statusbar collapse")
    d.home()


run(main)
