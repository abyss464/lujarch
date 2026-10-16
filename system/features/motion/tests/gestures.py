"""The back gesture draws a line out of the edge instead of Android's arrow panel, and the volume
slider's filled part is water; each has a switch in Lujarch motion, and off plays Android's own.

verifies: MOT-6, MOT-7
timeout: 1200
"""
import os
import subprocess
import time
from pathlib import Path

from lujarch_test import ADB, check, device, note, png, run, step

OUT = Path(os.environ.get("LUJARCH_TEST_OUT", "/tmp"))
SETTINGS = {"Back gesture": "lujarch_motion_back_gesture", "Volume": "lujarch_motion_volume"}


def frames(name, count, interval):
    folder = OUT / name
    folder.mkdir(parents=True, exist_ok=True)
    subprocess.run([os.environ["LUJARCH_TOOL"], "vm", "screenshot", "--frames", str(count),
                    "--interval", str(interval), str(folder / "frame.png")], check=True,
                   capture_output=True, timeout=300)
    return sorted(folder.glob("*.png"))


def stroke(path):
    """What the back gesture draws at the left edge of a frame: the light grey or white pixels
    left of the page's icons near the finger, how its rows reach toward the edge, and the colors
    beside it and above it. None when nothing is drawn there."""
    w, h, rows = png(path)
    pts = [(x, y) for y in range(800, 1300, 2) for x in range(0, 150, 2)
           if min(rows[y][x][:3]) >= 150 and max(rows[y][x][:3]) - min(rows[y][x][:3]) < 50]
    if len(pts) < 40:
        return None
    xs, ys = [x for x, _ in pts], [y for _, y in pts]
    x1, y1, x2, y2 = min(xs), min(ys), max(xs), max(ys)
    if x2 - x1 > 60 or y2 - y1 > 120:
        return None
    cy, part = (y1 + y2) // 2, max(2, (y2 - y1) // 5)

    def reach(a, b):
        return min(x for x, y in pts if a <= y <= b)

    return {"box": (x1, y1, x2, y2), "edge": (reach(y1, y1 + part), reach(cy - part // 2, cy + part // 2),
                                         reach(y2 - part, y2)),
            "left": rows[cy][max(0, x1 - 12)][:3], "right": rows[cy][x2 + 12][:3],
            "above": rows[max(0, cy - 150)][(x1 + x2) // 2][:3]}


def near(a, b, by):
    return sum(abs(p - q) for p, q in zip(a, b)) <= by


def back_shape(d, name):
    """What the back gesture draws while held past arming: 'dart' for an arrowhead aimed at the
    edge with nothing behind it, 'panel' for an arrow on a panel of its own color, or None."""
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.SETTINGS", wait=2)
    swipe = subprocess.Popen([ADB, "-s", d.serial, "shell", "input swipe 2 1200 300 1200 2400"])
    time.sleep(0.3)
    shots = frames(name, 8, 0.2)
    swipe.wait(timeout=30)
    kinds = []
    for f in shots:
        s = stroke(f)
        if not s:
            continue
        top, mid, bottom = s["edge"]
        if near(s["left"], s["right"], 12) and not near(s["left"], s["above"], 20):
            kinds.append("panel")
        elif mid + 6 <= min(top, bottom):
            kinds.append("dart")
        else:
            kinds.append("other")
        note(f"{f.name}: {kinds[-1]}, box {s['box']}, reach {s['edge']}, beside {s['left']} "
             f"{s['right']}, above {s['above']}")
    time.sleep(1)
    return max(set(kinds), key=kinds.count) if kinds else None


def motion_page(d):
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.ACCESSIBILITY_SETTINGS")
    d.tap(d.scroll_to(text="Color & motion"))
    d.tap(d.scroll_to(text="Lujarch motion"))


def set_switch(d, name, on):
    motion_page(d)
    sw = d.switch_of(name)
    if sw.checked != on:
        d.tap(sw)
    time.sleep(1)
    check(d.setting("secure", SETTINGS[name]) in (("1", None) if on else ("0",)),
          f"{name} {'on' if on else 'off'}")
    d.home()


def volume_slider(d, name):
    """Frames of the volume dialog just after a step up, and its slider."""
    d.home()
    d.key("KEYCODE_VOLUME_UP")
    time.sleep(0.3)
    shots = frames(name, 4, 0.25)
    slider = next((n for n in d.ui(windows=True) if n.rid.endswith("volume_dialog_slider")), None)
    return shots, slider


def shard(path, box):
    """Pixels of the mark's red in the box: the floating shard."""
    rows = png(path)[2]
    x1, y1, x2, y2 = box
    return sum(1 for y in range(y1, y2, 2) for x in range(x1, x2, 2)
               if rows[y][x][0] > 110 and rows[y][x][0] > 1.8 * max(rows[y][x][1:3]))


def moving(paths, box):
    """The share of the box's samples that change between the first and the last frame."""
    first, last = png(paths[0])[2], png(paths[-1])[2]
    x1, y1, x2, y2 = box
    samples = [(x, y) for y in range(y1, y2, 3) for x in range(x1, x2, 3)]
    return sum(1 for x, y in samples
               if sum(abs(a - b) for a, b in zip(first[y][x][:3], last[y][x][:3])) > 30) / len(samples)


def main():
    d = device()
    try:
        step("Lujarch motion: Back gesture and Volume, on by default")
        motion_page(d)
        for name in SETTINGS:
            check(d.switch_of(name).checked, f"{name} is on")
        d.shot("lujarch-motion")

        step("the back gesture: a line out of the edge, Android's arrow panel when off")
        check(back_shape(d, "back-line") == "dart",
              "on: armed, the line is an arrowhead aimed at the edge, with no panel behind it")
        set_switch(d, "Back gesture", False)
        check(back_shape(d, "back-panel") == "panel", "off: Android's arrow on its panel")

        step("the volume slider: water in the filled part, Android's plain fill when off")
        water, slider = volume_slider(d, "volume-water")
        check(slider, "the volume dialog shows its slider")
        box = slider.bounds
        on = shard(water[-1], box), moving(water, box)
        set_switch(d, "Volume", False)
        plain, _ = volume_slider(d, "volume-plain")
        off = shard(plain[-1], box), moving(plain, box)
        note(f"red shard samples and moving share after a step: on {on}, off {off}")
        check(on[0] >= 20, "on: a shard of the mark floats in the water")
        check(on[1] > off[1], "on: the water still moves after the step, Android's fill has settled")
        check(off[0] < 5, "off: Android's plain fill, no shard")
    finally:
        for key in SETTINGS.values():
            d.put_setting("secure", key, None)
        d.home()


run(main)
