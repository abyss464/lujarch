"""While a song plays whose app gives its lyrics, the current line shows on the left of the status
bar in place of the notification icons, following the song; a tap opens the player. Lyrics off,
no line shows.

verifies: SB-3
timeout: 900
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step

LYRICS = "[00:00.00]Lujarch line one|[00:05.00]Lujarch line two|[00:10.00]Lujarch line three"


def play(d, p, seconds=16):
    p.clear()
    d.sh(f"am start -n {p.PACKAGE}/.Probe --activity-single-top --es do media "
         f"--ei seconds {seconds} --es lyrics '{LYRICS}'")
    time.sleep(2)
    d.home()


def bar(d):
    return [n for n in d.ui(windows=True) if n.bounds[3] <= 120 and (n.text or n.desc)]


def line(d):
    return next((n for n in bar(d) if n.text.startswith("Lujarch line")), None)


def items_page(d):
    d.unlock()
    d.start("-f 0x10008000 -n 'com.android.settings/.Settings$SystemDashboardActivity'")
    d.tap(d.scroll_to(text="Status bar"))
    d.tap(d.scroll_to(text="Status bar items"))


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.sh(f"pm grant {p.PACKAGE} android.permission.POST_NOTIFICATIONS")
    d.sh("cmd notification post -t 'Lyrics test' lujarch-lyrics 'An icon for the status bar'")
    try:
        step("the playing song's line, in place of the notification icons, following the song")
        play(d, p)
        deadline = time.monotonic() + 8
        while not (first := line(d)) and time.monotonic() < deadline:
            time.sleep(0.5)
        check(first, "a line of the song shows in the status bar")
        note(f"first: {first.text} at {first.bounds}")
        check(first.bounds[0] < 540, "on the left")
        icons = [n.desc for n in bar(d) if "notification:" in n.desc]
        check(not icons, f"in place of the notification icons ({icons})")
        d.shot("lyrics-line")
        time.sleep(6)
        later = line(d)
        check(later and later.text != first.text, f"the line follows the song ({later and later.text})")
        step("a tap opens the player")
        d.tap_xy(later.x, later.y)
        time.sleep(2)
        check(d.top()[0] == p.PACKAGE, f"the player opens ({d.top()[0]})")
        d.home()
        time.sleep(10)

        step("Lyrics off: no line")
        items_page(d)
        d.tap(d.switch_of("Lyrics"))
        check(not d.switch_of("Lyrics").checked, "Lyrics is off")
        play(d, p, 10)
        time.sleep(4)
        check(not line(d), "no line shows")
        time.sleep(8)
    finally:
        items_page(d)
        if not d.switch_of("Lyrics").checked:
            d.tap(d.switch_of("Lyrics"))
        d.sh("cmd notification cancel lujarch-lyrics", check=False)
        d.home()


run(main)
