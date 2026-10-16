"""Home settings > Sort apps in drawer: By name is Android's alphabetical order; By icon color orders
the apps by the hue of their icons around the color wheel, gray and black icons last.

verifies: LCH-2
timeout: 900
"""
import colorsys
import math
import time

from lujarch_test import check, device, note, png, run, step

SETTING = "lujarch_launcher_drawer_sort"


def drawer_apps(d):
    """The drawer's apps in their order, each with its icon's box, from the top."""
    d.drawer()
    nodes = [n for n in d.ui() if n.text and n.cls.endswith("TextView")
             and n.rid.endswith("icon") or (n.text and "BubbleTextView" in n.cls)]
    nodes.sort(key=lambda n: (n.bounds[1] // 40, n.bounds[0]))
    return nodes


def hue(path, node):
    """The icon's hue (0-1) and how colored it is (0 gray to 1)."""
    w, h, rows = png(path)
    x1, y1, x2, y2 = node.bounds
    size = x2 - x1
    box = (x1 + size // 4, y1 + size // 8, x2 - size // 4, y1 + size * 5 // 8)
    total, sat, xs, ys = 0, 0.0, 0.0, 0.0
    for y in range(box[1], min(box[3], h), 3):
        for x in range(box[0], min(box[2], w), 3):
            r, g, b = (c / 255 for c in rows[y][x][:3])
            hh, ll, ss = colorsys.rgb_to_hls(r, g, b)
            total += 1
            if ss > 0.25 and 0.15 < ll < 0.9:
                sat += 1
                xs += math.cos(2 * math.pi * hh)
                ys += math.sin(2 * math.pi * hh)
    colored = sat / total if total else 0
    angle = (math.atan2(ys, xs) / (2 * math.pi)) % 1 if sat else None
    return angle, colored


def sort_choice(d, choice):
    d.home()
    d.sh("input swipe 540 1200 540 1200 1200")
    d.tap(d.wait(text="Home settings"))
    d.tap(d.scroll_to(text="Sort apps in drawer"))
    d.tap(d.wait(text=choice))
    time.sleep(1)
    d.home()


def main():
    d = device()
    try:
        step("By name is the default and alphabetical")
        check(d.setting("secure", SETTING) in (None, "0"), "By name by default")
        names = [n.text for n in drawer_apps(d)]
        note(f"by name: {names}")
        check(names == sorted(names, key=str.casefold), "the apps are in alphabetical order")
        d.shot("drawer-by-name")

        step("By icon color: around the color wheel, gray icons last")
        sort_choice(d, "By icon color")
        check(d.setting("secure", SETTING) == "1", "By icon color is chosen")
        nodes = drawer_apps(d)
        path = d.shot("drawer-by-color")
        order = [(n.text, *hue(path, n)) for n in nodes]
        note("by color: " + ", ".join(f"{t} {h if h is None else round(h * 360)}°/{c:.2f}"
                                      for t, h, c in order))
        colored = [h for _, h, c in order if h is not None and c > 0.15]
        grays = [i for i, (_, h, c) in enumerate(order) if h is None or c <= 0.15]
        check(not grays or min(grays) >= len(order) - len(grays),
              "gray icons come after the colored ones")
        drops = sum(1 for a, b in zip(colored, colored[1:]) if b < a - 0.06)
        check(drops <= 1, f"the colored icons go once around the wheel ({drops} steps back)")
    finally:
        d.put_setting("secure", SETTING, None)
        d.home()


run(main)
