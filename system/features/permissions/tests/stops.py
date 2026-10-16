"""When an app stops within seconds after a choice of the user's refused it or returned empty data,
Android's "has stopped" dialog names each such permission with its choice and offers a button per
permission that sets it to Allow and one to the app's permissions; nothing changes without a tap.
When the phone is not in use a notification says the same. Explain why apps stop turns it off.

verifies: PERM-21
timeout: 1500
"""
import re
import time

from lujarch_test import AppProbe, check, device, note, run, step

SENSORS = "Motion and environment sensors"
IDS = "Device identifiers"
REASON = "In the seconds before it stopped"
SETTING = "lujarch_permissions_explain_crashes"
# Android shows no dialog for an app that stops again within two minutes, and the system
# explains one app's stops at most once in 30 seconds.
SPACING = 125


def stop_after(d, p, actions, foreground=True):
    """The probe does actions and stops right after them."""
    if foreground:
        d.start(f"-n {p.PACKAGE}/.Probe", wait=3)
        d.sh(f"am start --activity-single-top -n {p.PACKAGE}/.Probe --es do {actions} --ez crash true")
    else:
        d.sh(f"am broadcast -f 0x20 -a org.lujarch.probe.DO -n '{p.PACKAGE}/.Probe$Background' "
             f"--es do {actions} --ez crash true")


def notices(d, since):
    """The system's notifications that Probe stopped, posted since (device ms)."""
    out = d.sh("dumpsys notification --noredact")
    return [b for b in out.split("NotificationRecord(")[1:]
            if "pkg=android " in b and "android.title=String (Probe stopped)" in b
            and int(re.search(r"when=(\d+)", b).group(1)) >= since]


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.put_setting("global", SETTING, "1")

    step("not in use: a notification names the permission and its choice")
    d.sh(f"cmd appops set {p.PACKAGE} LUJARCH_SENSORS deny")
    d.key("KEYCODE_SLEEP")
    time.sleep(2)
    since = int(d.sh("date +%s%3N").strip())
    stop_after(d, p, "sensors", foreground=False)
    deadline = time.monotonic() + 20
    while not notices(d, since) and time.monotonic() < deadline:
        time.sleep(1)
    found = notices(d, since)
    check(found, "a notification says Probe stopped")
    text = re.search(r"android.text=String \(([^)]*\))", found[0])
    check(text and f"{SENSORS} (Don't allow)" in text.group(1).replace("’", "'"),
          f"it names the permission and the choice ({text.group(1) if text else ''})")
    d.wake()
    d.unlock()
    d.sh("cmd statusbar expand-notifications")
    d.wait(text="Probe stopped")
    d.shot("stopped-notification")
    d.sh("cmd statusbar collapse")
    check(d.appop(p.PACKAGE, "LUJARCH_SENSORS") == "deny", "the notification changed nothing")
    time.sleep(SPACING)

    step("on screen: the dialog names it and changes nothing without a tap")
    stop_after(d, p, "sensors")
    reason = d.wait(contains=REASON, timeout=20)
    check(f"{SENSORS} (Don't allow)" in reason.text, f"the reason names it: {reason.text}")
    check(d.find(text=f"Allow {SENSORS}"), f"a button: Allow {SENSORS}")
    check(d.find(text="App permissions"), "a button: App permissions")
    d.shot("stopped-dialog")
    time.sleep(3)
    check(d.appop(p.PACKAGE, "LUJARCH_SENSORS") == "deny", "without a tap the choice stays")
    d.tap(text=f"Allow {SENSORS}")
    d.gone(contains=REASON)
    check(d.appop(p.PACKAGE, "LUJARCH_SENSORS") == "allow", "the button set the permission to Allow")
    time.sleep(SPACING)

    step("each refusing permission is named; App permissions opens the app's permissions")
    d.sh(f"cmd appops set {p.PACKAGE} LUJARCH_SENSORS deny")
    d.sh(f"cmd appops set {p.PACKAGE} LUJARCH_DEVICE_IDS ignore")
    stop_after(d, p, "sensors,ids")
    reason = d.wait(contains=REASON, timeout=20)
    note(reason.text)
    check(f"{SENSORS} (Don't allow)" in reason.text and f"{IDS} (Return empty data)" in reason.text,
          "both permissions with their choices")
    check(d.find(text=f"Allow {SENSORS}") and d.find(text=f"Allow {IDS}"), "a button for each")
    d.shot("stopped-dialog-two")
    d.tap(text="App permissions")
    d.wait(text="Allowed")
    check(d.find(text="Return empty data") or d.scroll_to(text="Return empty data", quiet=True),
          "the app's permissions page opened")
    d.shot("stopped-app-permissions")
    check(d.appop(p.PACKAGE, "LUJARCH_SENSORS") == "deny"
          and d.appop(p.PACKAGE, "LUJARCH_DEVICE_IDS") == "ignore", "nothing changed")
    d.home()
    time.sleep(SPACING)

    step("Explain why apps stop: off, a stop after a refusal is not explained")
    d.start("-f 0x10008000 -a android.intent.action.MANAGE_PERMISSIONS")
    switch = d.switch_of("Explain why apps stop")
    check(switch.checked, "Explain why apps stop is on by default")
    d.tap(switch)
    d.shot("explain-off")
    check(d.setting("global", SETTING) == "0", "the switch turned it off")
    stop_after(d, p, "sensors")
    time.sleep(8)
    check(not d.find(contains=REASON), "no explanation")
    for c in ("Close app", "OK"):
        if d.find(text=c):
            d.tap(text=c)
    d.start("-f 0x10008000 -a android.intent.action.MANAGE_PERMISSIONS")
    d.tap(d.switch_of("Explain why apps stop"))
    check(d.setting("global", SETTING) == "1", "turned on again")
    d.home()


run(main)
