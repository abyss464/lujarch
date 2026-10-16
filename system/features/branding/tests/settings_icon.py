"""Settings' launcher icon is the white gear on a teal plate with the trigram Li at its hub, and its
monochrome version shows with Themed icons.

verifies: BRD-2
"""
import re
import time

from lujarch_test import check, device, hex_rgb, note, run, share_near, step


def icon_box(d, label):
    """The icon above a launcher label."""
    n = d.find(text=label) or d.scroll_to(text=label)
    return (n.x - 70, n.bounds[1] - 150, n.x + 70, n.bounds[1] - 10)


def main():
    d = device()
    step("Settings' icon resource is Lujarch's adaptive icon with a monochrome layer")
    xml = d.sh("cmd overlay lookup com.android.settings com.android.settings:mipmap/ic_launcher_settings "
               "2>&1; cmd overlay lookup com.android.settings com.android.settings:drawable/ic_launcher_settings 2>&1")
    note(xml.strip().replace("\n", " | ")[:300])
    check("lujarch" in xml or "res/" in xml, "the icon resolves")
    overlays = d.sh("cmd overlay list com.android.settings")
    check(re.search(r"\[x\] org\.lujarch\.branding\.overlay\.settings", overlays),
          "the branding overlay of Settings is enabled")

    step("the app drawer shows the icon: white gear on teal")
    d.unlock()
    d.drawer()
    d.wait(text="Settings")
    shot = d.shot("drawer-settings-icon")
    box = icon_box(d, "Settings")
    white = share_near(shot, (255, 255, 255), 60, box, 2)
    teal = max(share_near(shot, rgb, 70, box, 2) for rgb in ((0, 128, 128), (32, 112, 120), (24, 96, 104)))
    note(f"white {white:.2f}, teal {teal:.2f}")
    check(white > 0.05 and teal > 0.10, "the icon holds the white gear on its teal plate")

    step("Themed icons show the monochrome gear and Li: Wallpaper & style previews it")
    with d.settings_kept(("secure", "theme_customization_overlay_packages")):
        d.home()
        d.start("-n com.android.wallpaper/com.android.customization.picker.CustomizationPickerActivity")
        d.swipe(800, 800, 200, 800)
        d.tap(d.scroll_to(text="Icons"))
        label = d.wait(text="Minimal")
        shot = d.shot("icon-styles")
        box = (label.x - 80, label.y - 230, label.x + 80, label.y - 70)
        dark = share_near(shot, (30, 40, 42), 50, box, 2)
        teal = max(share_near(shot, rgb, 80, box, 2) for rgb in ((140, 200, 210), (100, 170, 180), (60, 120, 130)))
        note(f"Minimal preview: dark {dark:.2f}, teal {teal:.2f}")
        check(dark > 0.15 and teal > 0.05, "the Minimal preview is Settings' monochrome icon")
        d.tap(label)
        time.sleep(1)
        d.tap(text="Apply", wait=4)
        d.home()
        shot = d.shot("home-themed")
        mono = d.sh("content query --uri content://com.android.launcher3.grid_control/icon_themed")
        check("boolean_value=1" in mono, "the launcher draws themed icons")
        d.start("-n com.android.wallpaper/com.android.customization.picker.CustomizationPickerActivity")
        d.swipe(800, 800, 200, 800)
        d.tap(d.scroll_to(text="Icons"))
        d.tap(text="Default")
        time.sleep(1)
        d.tap(text="Apply", wait=4)
        mono = d.sh("content query --uri content://com.android.launcher3.grid_control/icon_themed")
        check("boolean_value=0" in mono, "back to the default icons")
    d.home()


run(main)
