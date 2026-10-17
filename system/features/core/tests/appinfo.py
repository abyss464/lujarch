"""App info shows each app's part of every capability that concerns single apps as a row with the
app's current state, and the row opens the capability's page for that app.

verifies: CORE-4
timeout: 1200
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step

# Row: what the page it opens shows for the app.
ROWS = {
    "Notifications": "All Probe notifications",
    "Permissions": "Allowed",
    "Icon": "Redrawn",
    "Proxy": "Probe",
    "Mobile data & Wi-Fi": "Allow network access",
    "App battery usage": "Background usage, Keep and Autostart",
    "Background": "Background usage",
}


def app_info(d, p):
    d.unlock()
    d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{p.PACKAGE}")
    d.wait(text="App info")


def summary(d, title):
    return next(iter(d.summary(title, reach=100)), "")


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    d.start(f"-n {p.PACKAGE}/.Probe", wait=2)
    d.home()
    states = {}
    for title, shows in ROWS.items():
        step(f"{title}: a row with the app's state, opening the app's page")
        app_info(d, p)
        states[title] = summary(d, title)
        check(states[title], f"{title} shows a state: {states[title]}")
        d.tap(d.scroll_to(text=title), wait=3)
        check(d.wait(contains=shows, timeout=20), f"{title} opens the app's page ({shows})")
        if title == "Icon":
            d.shot("appinfo-icon-page")
    note(f"states: {states}")

    step("a row follows the app's state")
    app_info(d, p)
    before = summary(d, "Notifications")
    d.sh(f"pm {'revoke' if before != 'Off' else 'grant'} {p.PACKAGE} "
         "android.permission.POST_NOTIFICATIONS")
    time.sleep(1)
    app_info(d, p)
    after = summary(d, "Notifications")
    check(after != before, f"Notifications: {before} → {after}")
    d.shot("appinfo-rows")
    d.home()


run(main)
