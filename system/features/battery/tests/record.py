"""Battery usage shows the past 7 days by the hour, as a chart and per app, and an app's App
battery usage the same for the app; its menu turns the record and the full battery history on and
off and clears the record. With Full battery history on, Android's battery history records every
wake lock; off, it records what Android records by default.

verifies: BAT-1, BAT-2
timeout: 1500
"""
import re
import time

from lujarch_test import AppProbe, check, device, note, run, step

RECORD = "lujarch_battery_record"
FULL = "lujarch_battery_full_history"
DAYS = re.compile(r"^(Mon|Tue|Wed|Thu|Fri|Sat|Sun|Today)$")


def usage_page(d):
    d.unlock()
    d.start("-f 0x10008000 -a android.intent.action.POWER_USAGE_SUMMARY")
    d.tap(d.scroll_to(text="Battery usage"))
    d.wait(contains="past 7 days", timeout=20)


def menu(d):
    d.tap(desc="More options")
    time.sleep(1)
    return {n.text: n.checked for n in d.ui() if n.text}


def wake_lock_recorded(d, p):
    """Whether Android's battery history records a wake lock the probe takes now, by its tag."""
    tag = f"probe:{int(time.time())}"
    d.sh("dumpsys battery unplug")
    time.sleep(1)
    p.do("wakelock", f"--es tag {tag}", restart=False)
    time.sleep(2)
    history = d.sh("dumpsys batterystats --history", timeout=120)
    d.sh("dumpsys battery reset")
    return tag in history


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    try:
        step("Battery usage: the past 7 days by the hour, as a chart and per app")
        usage_page(d)
        texts = d.texts()
        days = [t for t in texts if DAYS.match(t)]
        check(len(days) == 7, f"seven days ({days})")
        check(any(re.fullmatch(r"\d\d", t) for t in texts), "hours on the axis")
        d.shot("battery-usage")
        items = menu(d)
        note(f"menu: {items}")
        check(items.get("Record battery usage") is True, "Record battery usage is on")
        check(items.get("Full battery history") is True, "Full battery history is on")
        check("Clear record" in items, "the menu clears the record")
        d.back()

        step("an app's App battery usage shows the same for the app")
        d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS "
                f"-d package:{p.PACKAGE}")
        d.tap(d.scroll_to(text="App battery usage"))
        check(d.wait(contains="Battery usage of the app over the past 7 days", timeout=20),
              "the app's 7 days")
        d.shot("app-battery-usage")

        step("Record battery usage off: the page says so; on again")
        usage_page(d)
        menu(d)
        d.tap(text="Record battery usage")
        time.sleep(2)
        check(d.setting("global", RECORD) == "0", "recording is off")
        check(d.find(contains="recording is off") or d.find(contains="Recording of battery"),
              "the page says recording is off")
        d.shot("record-off")
        menu(d)
        d.tap(text="Record battery usage")
        check(d.setting("global", RECORD) == "1", "recording is on again")

        step("Clear record asks first")
        menu(d)
        d.tap(text="Clear record")
        check(d.wait(text="Clear the record?"), "it asks first")
        d.shot("clear-record")
        d.tap(text="Cancel")

        step("Full battery history: every wake lock is in Android's battery history")
        check(wake_lock_recorded(d, p), "on: the wake lock is recorded")
        usage_page(d)
        menu(d)
        d.tap(text="Full battery history")
        time.sleep(2)
        check(d.setting("global", FULL) == "0", "full history is off")
        check(not wake_lock_recorded(d, p), "off: it is not")
    finally:
        d.put_setting("global", RECORD, None)
        d.put_setting("global", FULL, None)
        d.sh("dumpsys battery reset", check=False)
        d.home()


run(main)
