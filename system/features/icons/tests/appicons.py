"""An app whose icon is a single picture gets an icon in the shape of all others: Redrawn draws its
logo in one color on a plain ground, Fitted places the picture on a ground of its own colors,
Original keeps the app's own icon; App info > Icon offers the three with previews. With Fit app
icons off every app shows its own icon and App info has no Icon row.

verifies: ICON-1
timeout: 1200
"""
import time

from lujarch_test import AppProbe, check, device, note, png, run, share_near, step

SETTING = "lujarch_icons_fit"
RED = (204, 60, 40)


def drawer_icon(d, name):
    """The drawer's screenshot and the box of the app's icon in it."""
    d.drawer()
    node = d.scroll_to(text=name)
    path = d.shot(f"drawer-{name.lower()}")
    x1, y1, x2, y2 = node.bounds
    size = x2 - x1
    side = min(size, (y2 - y1)) * 3 // 5
    left = x1 + (size - side) // 2
    return path, (left, y1 + 8, left + side, y1 + 8 + side)


def corners_red(path, box):
    """The share of the icon box's corners the red picture fills: a square fills them, the icon
    shape leaves them to the wallpaper."""
    x1, y1, x2, y2 = box
    c = max(4, (x2 - x1) // 10)
    shares = [share_near(path, RED, 90, (x, y, x + c, y + c), 1)
              for x, y in ((x1, y1), (x2 - c, y1), (x1, y2 - c), (x2 - c, y2 - c))]
    return sum(shares) / 4


def choose(d, p, choice):
    d.unlock()
    d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{p.PACKAGE}")
    d.tap(d.scroll_to(text="Icon"))
    d.tap(d.wait(text=choice, timeout=15))
    time.sleep(2)
    d.home()


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    try:
        step("Fit app icons is on; the probe's picture is Redrawn by default")
        check(d.setting("secure", SETTING) in (None, "1"), "Fit app icons is on")
        d.unlock()
        d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS "
                f"-d package:{p.PACKAGE}")
        check("Redrawn" in d.summary("Icon"), "App info > Icon: Redrawn")
        d.tap(d.scroll_to(text="Icon"))
        for c in ("Original", "Fitted", "Redrawn"):
            check(d.wait(text=c, timeout=15), f"Icon offers {c}")
        d.shot("icon-choices")
        d.home()
        redrawn = drawer_icon(d, "Probe")
        step("Original: the app's own square picture")
        choose(d, p, "Original")
        original = drawer_icon(d, "Probe")
        step("Fitted: the picture on a ground of its own colors, in the icon shape")
        choose(d, p, "Fitted")
        fitted = drawer_icon(d, "Probe")
        o, f, r = (corners_red(*x) for x in (original, fitted, redrawn))
        rf = share_near(redrawn[0], RED, 90, redrawn[1], 2)
        ff = share_near(fitted[0], RED, 90, fitted[1], 2)
        note(f"red corners: original {o:.2f}, fitted {f:.2f}, redrawn {r:.2f}; red overall: "
             f"fitted {ff:.2f}, redrawn {rf:.2f}")
        check(o > 0.8, "Original keeps the square picture")
        check(f < 0.3 and r < 0.3, "Fitted and Redrawn take the icon shape")
        check(ff > 0.5, "Fitted keeps the picture's colors")
        check(rf < ff - 0.2, "Redrawn draws it in one color on a plain ground")

        step("Fit app icons off: the own icon, and no Icon row")
        d.unlock()
        d.start("-f 0x10008000 -n com.android.wallpaper/.picker.CustomizationPickerActivity", wait=3)
        d.tap(d.switch_of("Fit app icons"))
        check(d.setting("secure", SETTING) == "0", "turned off")
        off = drawer_icon(d, "Probe")
        check(corners_red(*off) > 0.8, "every app shows its own icon")
        d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS "
                f"-d package:{p.PACKAGE}")
        d.wait(text="App info")
        rows = set()
        for _ in range(4):
            rows |= set(d.texts())
            d.swipe(540, 1800, 540, 700)
        check("Icon" not in rows, "App info has no Icon row")
    finally:
        d.put_setting("secure", SETTING, None)
        d.home()


run(main)
