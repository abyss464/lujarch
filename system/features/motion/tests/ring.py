"""When an app uses the microphone, the shards rise out of the camera hole's rim and stay a ring of
teeth round the camera while the access lasts, and sink into the hole when it stops; with Privacy
ring off, the privacy dot shows in its place. The virtual display takes Android's emulated punch
hole as its camera.

verifies: MOT-5
timeout: 900
"""
import os
import re
import subprocess
import time
from pathlib import Path

from lujarch_test import AppProbe, check, device, note, png, run, step

OUT = Path(os.environ.get("LUJARCH_TEST_OUT", "/tmp"))
HOLE = "com.android.internal.display.cutout.emulation.hole"
RING_KEY = "lujarch_privacy_ring"


def frames(name, count, interval):
    folder = OUT / name
    folder.mkdir(parents=True, exist_ok=True)
    subprocess.run([os.environ["LUJARCH_TOOL"], "vm", "screenshot", "--frames", str(count),
                    "--interval", str(interval), str(folder / "frame.png")], check=True,
                   capture_output=True, timeout=300)
    return sorted(folder.glob("*.png"))


def cutout(d):
    """The camera hole's box from the display's cutout."""
    out = d.sh("dumpsys window | grep -m1 'mDisplayCutout='", check=False)
    m = re.search(r"boundingRect=\{Bounds=\[Rect\(0, 0 - 0, 0\), Rect\((\d+), (\d+) - (\d+), (\d+)\)",
                  out)
    return tuple(int(v) for v in m.groups()) if m else None


def colored_round(path, hole):
    """Colored (not grey) samples in the ring round the hole, out to twice its size."""
    w, h, rows = png(path)
    x1, y1, x2, y2 = hole
    cx, cy, r = (x1 + x2) // 2, (y1 + y2) // 2, (x2 - x1) // 2
    n = 0
    for y in range(max(0, cy - 2 * r), min(h, cy + 2 * r), 2):
        for x in range(max(0, cx - 2 * r), min(w, cx + 2 * r), 2):
            dist = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if r < dist < 2 * r:
                red, green, blue = rows[y][x][:3]
                if max(red, green, blue) - min(red, green, blue) > 60:
                    n += 1
    return n


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.sh(f"pm grant {p.PACKAGE} android.permission.RECORD_AUDIO")
    d.sh(f"cmd overlay enable {HOLE}")
    time.sleep(3)
    # The screen decorations take the display's cutout when SystemUI starts.
    d.sh("kill $(pidof com.android.systemui)", check=False)
    time.sleep(10)
    d.unlock()
    try:
        hole = cutout(d)
        check(hole, f"the display has a camera hole ({hole})")
        note(f"hole {hole}")
        step("while the microphone is used: a ring of teeth round the camera")
        d.home()
        idle = colored_round(frames("idle", 1, 0)[0], hole)
        # The probe stays in front: Android takes the microphone from an app in the background.
        d.sh(f"am start -n {p.PACKAGE}/.Probe --activity-single-top --es do mic --ei seconds 12")
        time.sleep(1)
        during = [colored_round(f, hole) for f in frames("during", 5, 1.2)]
        note(f"colored round the hole: idle {idle}, during {during}")
        check(max(during[2:]) > idle + 20, "the ring stays round the camera while it lasts")
        time.sleep(8)
        after = colored_round(frames("after", 1, 0)[0], hole)
        check(after <= idle + 10, f"it sinks into the hole when the access stops ({after})")

        step("Privacy ring off: the privacy dot shows in its place")
        d.sh(f"settings put secure icon_blacklist {RING_KEY}")
        d.sh(f"am start -n {p.PACKAGE}/.Probe --activity-single-top --es do mic --ei seconds 10")
        dot = False
        for _ in range(12):
            time.sleep(0.5)
            if any(n.rid.endswith("privacy_dot") for n in d.ui(windows=True)):
                dot = True
                break
        check(dot, "the privacy dot shows")
        time.sleep(2)
        off = colored_round(frames("ring-off", 1, 0)[0], hole)
        check(off <= idle + 10, f"no ring round the camera ({off})")
        time.sleep(6)
    finally:
        d.sh("settings put secure icon_blacklist ''", check=False)
        d.sh(f"cmd overlay disable {HOLE}", check=False)
        d.sh("kill $(pidof com.android.systemui)", check=False)
        time.sleep(10)
        d.unlock()
        d.home()


run(main)
