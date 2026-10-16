"""An app whose icon is a single picture gets an icon in the shape of all others: Redrawn draws its
logo in one color on a plain ground, Fitted places the picture on a ground of its own colors,
Original keeps the app's own icon; App info > Icon offers the three with previews. With Fit app
icons off every app shows its own icon and App info has no Icon row.

verifies: ICON-1
timeout: 1200
"""
import time

from lujarch_test import check, device, install_probe, note, png, run, step

SETTING = "lujarch_icons_fit"
# The logo probe: a white logo on a red ground, as a single picture.
LOGO = ("org.lujarch.probe.logo", "Logo probe")
RED = (204, 60, 40)
# The launcher's own record of Themed icons, which Wallpaper & style sets.
THEMED = "content query --uri content://com.android.launcher3.grid_control/icon_themed"


def drawer_icon(d, name):
    """The shares of the app's drawer icon that are red (the picture's ground) and white (the
    ground Android puts a picture on)."""
    d.drawer()
    node = d.scroll_to(text=name)
    return shares(d.shot(f"drawer-{name.lower().replace(' ', '-')}"), node)


def home_icon(d, name):
    """The same shares of the app's icon on the home screen, where Themed icons apply."""
    d.home()
    time.sleep(1)
    node = next((n for n in d.ui() if n.text == name), None)
    if not node:
        # On the home screen as the owner puts it there: the icon's menu in the drawer.
        d.drawer()
        icon = d.scroll_to(text=name)
        d.sh(f"input swipe {icon.x} {icon.y} {icon.x} {icon.y} 1200")
        d.tap(d.wait(text="Add to home screen"))
        time.sleep(2)
        d.home()
        time.sleep(1)
        node = next((n for n in d.ui() if n.text == name), None)
    check(node, f"{name} is on the home screen")
    return shares(d.shot(f"home-{name.lower().replace(' ', '-')}"), node, home=True)


def shares(path, node, home=False):
    w, h, rows = png(path)
    x1, y1, x2, y2 = node.bounds
    size = x2 - x1
    cx, cy, r = (x1 + x2) // 2, y1 + size * 3 // 5, size * 29 // 100
    if home:
        # A home screen cell is taller: its icon is the first band that stands out from the
        # wallpaper; its label follows.
        band = []
        for y in range(y1, min(y2, h), 4):
            line = rows[y][x1:x2:6]
            if sum(1 for px in line if max(px[:3]) - min(px[:3]) > 40 or min(px[:3]) > 200) >= 5:
                band.append(y)
            elif band:
                break
        check(band, "the icon shows")
        cy, r = (band[0] + band[-1]) // 2, (band[-1] - band[0]) * 42 // 100
    counts, total = {"red": 0, "white": 0}, 0
    for y in range(cy - r, cy + r, 2):
        for x in range(cx - r, cx + r, 2):
            if (x - cx) ** 2 + (y - cy) ** 2 > r * r:
                continue
            total += 1
            pixel = rows[y][x][:3]
            if sum(abs(a - b) for a, b in zip(pixel, RED)) < 90:
                counts["red"] += 1
            if min(pixel) > 225:
                counts["white"] += 1
    return {k: v / total for k, v in counts.items()}


def app_info(d):
    d.unlock()
    d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{LOGO[0]}")
    d.wait(text="App info", timeout=20)


def choose(d, choice):
    app_info(d)
    d.tap(d.scroll_to(text="Icon"))
    d.tap(d.wait(text=choice, timeout=15))
    time.sleep(2)
    d.home()


def icons_page(d):
    """Wallpaper & style > Icons."""
    d.unlock()
    d.home()
    d.start("-n com.android.wallpaper/com.android.customization.picker.CustomizationPickerActivity")
    d.swipe(800, 800, 200, 800)
    d.tap(d.scroll_to(text="Icons"))
    time.sleep(1)


def themed(d, on):
    """Wallpaper & style > Icons: Minimal (themed icons) or Default, applied."""
    want = "boolean_value=1" if on else "boolean_value=0"
    for style in ("Default" if on else "Minimal", "Minimal" if on else "Default"):
        if want in d.sh(THEMED, check=False):
            break
        # The page applies a change only: the other style is applied first when the page
        # already shows the chosen one.
        icons_page(d)
        d.tap(d.wait(text="Minimal" if on else "Default"))
        time.sleep(1)
        apply = d.find(text="Apply")
        if apply and apply.enabled:
            d.tap(apply, wait=4)
            continue
        d.tap(d.wait(text=style))
        time.sleep(1)
        d.tap(text="Apply", wait=4)
    deadline = time.monotonic() + 15
    while want not in d.sh(THEMED, check=False) and time.monotonic() < deadline:
        time.sleep(1)
    check(want in d.sh(THEMED, check=False), f"Themed icons {'on' if on else 'off'}")
    d.home()


def main():
    d = device()
    d.uninstall(LOGO[0])
    install_probe("logo")
    try:
        step("Fit app icons is on; the probe's picture is Redrawn by default")
        check(d.setting("global", SETTING) in (None, "1"), "Fit app icons is on")
        app_info(d)
        check("Redrawn" in d.summary("Icon"), "App info > Icon: Redrawn")
        d.tap(d.scroll_to(text="Icon"))
        for c in ("Original", "Fitted", "Redrawn"):
            check(d.wait(text=c, timeout=15), f"Icon offers {c}")
        d.shot("icon-choices")
        d.home()
        redrawn = drawer_icon(d, LOGO[1])
        step("Original: Android's own icon for a picture, on its white ground")
        choose(d, "Original")
        original = drawer_icon(d, LOGO[1])
        step("Fitted: the picture on a ground of its own colors, in the icon shape")
        choose(d, "Fitted")
        fitted = drawer_icon(d, LOGO[1])
        note(f"original {original}, fitted {fitted}, redrawn {redrawn}")
        check(original["white"] > 0.4, "Original: Android puts the picture on its white ground")
        check(fitted["red"] > 0.6 and redrawn["red"] > 0.6,
              "Fitted and Redrawn fill the icon shape with the picture's ground")

        step("Themed icons on the home screen: Redrawn has a themed, one-color version")
        with d.settings_kept(("secure", "theme_customization_overlay_packages")):
            choose(d, "Redrawn")
            colored = home_icon(d, LOGO[1])
            themed(d, True)
            redrawn_themed = home_icon(d, LOGO[1])
            note(f"home: {colored}, themed {redrawn_themed}")
            check(colored["red"] > 0.5, "without Themed icons, its colors")
            check(redrawn_themed["red"] < 0.1, "with Themed icons, its themed, one-color version")
            themed(d, False)

        step("Fit app icons off, applied like the switches beside it: the own icon, no Icon row")
        icons_page(d)
        d.tap(d.switch_of("Fit app icons"))
        time.sleep(1)
        check(d.setting("global", SETTING) in (None, "1"), "toggled, nothing changes before Apply")
        d.shot("fit-off")
        d.tap(d.wait(text="Apply"), wait=3)
        check(d.setting("global", SETTING) == "0", "applied, it is off")
        off = drawer_icon(d, LOGO[1])
        check(off["white"] > 0.4, f"every app shows its own icon ({off})")
        app_info(d)
        rows = set()
        for _ in range(4):
            rows |= set(d.texts())
            d.swipe(540, 1800, 540, 700)
        check("Icon" not in rows, "App info has no Icon row")
    finally:
        d.put_setting("global", SETTING, None)
        d.uninstall(LOGO[0])
        d.home()


run(main)
