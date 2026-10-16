"""On the lock screen the playing media shows as a circle with its app's icon; a tap grows Android's
media card out of it, a tap on the card's app icon folds it back, and it stays as the owner left it.

verifies: MOT-3
timeout: 900
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step

SETTING = "lujarch_motion_media_open"
SHOW, HIDE = "Show media controls", "Hide media controls"


def play(d, p, seconds=90):
    p.clear()
    d.sh(f"am start -n {p.PACKAGE}/.Probe --activity-single-top --es do media "
         f"--ei seconds {seconds}")
    time.sleep(3)
    d.home()


def lock_screen(d):
    d.key("KEYCODE_SLEEP")
    time.sleep(1.5)
    d.key("KEYCODE_WAKEUP")
    time.sleep(2.5)


def shown(d, desc):
    return next((n for n in d.ui(windows=True) if n.desc == desc), None)


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.sh(f"pm grant {p.PACKAGE} android.permission.POST_NOTIFICATIONS")
    d.put_setting("secure", SETTING, None)
    try:
        step("folded by default: a circle with the app's icon")
        play(d, p)
        lock_screen(d)
        circle = shown(d, SHOW)
        check(circle, "the lock screen shows the media as a circle")
        note(f"circle at {circle.bounds}")
        check(circle.bounds[2] - circle.bounds[0] < 300, "a small circle, not the card")
        check(not shown(d, HIDE), "and not the card")
        d.shot("media-circle")

        step("a tap grows the card out of it, and it stays open")
        d.tap_xy(circle.x, circle.y)
        time.sleep(1.5)
        check(shown(d, HIDE), "the card opens")
        check(d.setting("secure", SETTING) == "1", "the owner's choice is kept")
        d.shot("media-card")
        lock_screen(d)
        check(shown(d, HIDE) and not shown(d, SHOW), "on the next lock screen the card is open")

        step("a tap on the card's app icon folds it back")
        icon = shown(d, HIDE)
        d.tap_xy(icon.x, icon.y)
        time.sleep(1.5)
        check(shown(d, SHOW) and not shown(d, HIDE), "folded again")
        check(d.setting("secure", SETTING) in ("0", None), "and kept folded")
    finally:
        d.put_setting("secure", SETTING, None)
        d.unlock()
        d.sh(f"am force-stop {p.PACKAGE}", check=False)
        d.home()


run(main)
