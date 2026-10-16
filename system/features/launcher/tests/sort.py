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
    nodes = [n for n in d.ui() if n.text and n.rid.endswith("/icon")]
    nodes.sort(key=lambda n: (n.bounds[1], n.bounds[0]))
    return nodes


def hue(path, node):
    """The icon's hue (0-1, None for a grey icon) and how much of it is colored: sampled in the
    icon's circle, its white ground left out."""
    w, h, rows = png(path)
    x1, y1, x2, _ = node.bounds
    size = x2 - x1
    cx, cy, r = (x1 + x2) // 2, y1 + size * 3 // 5, size * 29 // 100
    total, colored, xs, ys = 0, 0, 0.0, 0.0
    for y in range(cy - r, min(cy + r, h), 2):
        for x in range(cx - r, min(cx + r, w), 2):
            if (x - cx) ** 2 + (y - cy) ** 2 > r * r:
                continue
            hh, ll, ss = colorsys.rgb_to_hls(*(c / 255 for c in rows[y][x][:3]))
            if ll > 0.92:
                continue
            total += 1
            if ss > 0.3 and 0.12 < ll < 0.9:
                colored += 1
                xs += math.cos(2 * math.pi * hh)
                ys += math.sin(2 * math.pi * hh)
    share = colored / total if total else 0
    angle = (math.atan2(ys, xs) / (2 * math.pi)) % 1 if share >= 0.1 else None
    return angle, share


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
        colored = [h for _, h, _ in order if h is not None]
        grays = [i for i, (_, h, _) in enumerate(order) if h is None]
        check(not grays or min(grays) >= len(order) - len(grays),
              "gray icons come after the colored ones")
        drops = sum(1 for a, b in zip(colored, colored[1:]) if b < a - 0.06)
        check(drops <= 1, f"the colored icons go once around the wheel ({drops} steps back)")
    finally:
        d.put_setting("secure", SETTING, None)
        d.home()


run(main)
