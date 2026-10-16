"""While the phone starts, an arrow falls and the shards gather into the mark, which keeps moving
until the start is done and then fades out: the start animation's first part plays once, its
second loops until the start is done and fades on its way out.

verifies: MOT-1
timeout: 600
"""
import os
import subprocess
import time
import zipfile
from pathlib import Path

from lujarch_test import check, device, hex_rgb, note, png, run, step

OCHRE = hex_rgb("#B5462B")
OUT = Path(os.environ.get("LUJARCH_TEST_OUT", "/tmp"))


def frames(name, count, interval):
    """count screenshots interval seconds apart of what the display shows."""
    folder = OUT / name
    folder.mkdir(parents=True, exist_ok=True)
    subprocess.run([os.environ["LUJARCH_TOOL"], "vm", "screenshot", "--frames", str(count),
                    "--interval", str(interval), str(folder / "frame.png")], check=True,
                   capture_output=True, timeout=300)
    return sorted(folder.glob("*.png"))


def ochre_box(path):
    """The ochre pixels' count and bounding box (x1, y1, x2, y2), sampled every 4 pixels."""
    w, h, rows = png(path)
    xs, ys = [], []
    for y in range(0, h, 4):
        row = rows[y]
        for x in range(0, w, 4):
            if sum(abs(a - b) for a, b in zip(row[x][:3], OCHRE)) <= 110:
                xs.append(x)
                ys.append(y)
    if not xs:
        return 0, None
    return len(xs), (min(xs), min(ys), max(xs), max(ys))


def main():
    d = device()
    step("the animation: an intro played once, a loop until the start is done that fades out")
    path = OUT / "bootanimation.zip"
    d.pull("/product/media/bootanimation.zip", str(path))
    desc = zipfile.ZipFile(path).read("desc.txt").decode().split("\n")
    note(f"desc.txt: {desc}")
    parts = [line.split() for line in desc[1:] if line.strip()]
    check(parts[0][0] == "c" and parts[0][1] == "1", "the first part plays once, to its end")
    check(parts[1][0] == "f" and parts[1][1] == "0" and int(parts[1][4]) > 0,
          "the second loops until the start is done and fades out")

    step("the arrow falls, then the shards gather into the mark")
    d.sh("setprop service.bootanim.exit 0; setprop service.bootanim.progress 0; start bootanim")
    try:
        intro = frames("intro", 8, 0.45)
        shapes = [ochre_box(f) for f in intro]
        for f, (n, box) in zip(intro, shapes):
            note(f"{f.name}: {n} ochre samples, box {box}")
        early = next(((n, b) for n, b in shapes if b), None)
        late = shapes[-1]
        check(early and early[1][2] - early[1][0] < (early[1][3] - early[1][1]),
              "early, a tall thin shape: the falling arrow")
        check(late[1] and late[1][2] - late[1][0] > 200 and late[0] > early[0] * 2,
              "later, the wide mark")
        step("the mark keeps moving while the start goes on")
        loop = frames("loop", 4, 0.5)
        boxes = [ochre_box(f) for f in loop]
        check(all(n for n, _ in boxes), "the mark shows")
        check(len({b for _, b in boxes}) > 1 or len({n for n, _ in boxes}) > 1, "and moves")
    finally:
        d.sh("setprop service.bootanim.exit 1")
    step("once the start is done it fades out")
    fade = frames("fade", 6, 0.1)
    counts = [ochre_box(f)[0] for f in fade]
    note(f"fading: {counts}")
    check(counts[-1] < max(counts[0], 1), "the mark fades out")
    time.sleep(2)


run(main)
