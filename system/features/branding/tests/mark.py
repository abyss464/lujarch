"""The ten-shard symbol is the mark: the start animation the device plays, recovery's and
fastbootd's images and the System updates card are those the one drawing makes.

verifies: BRD-3
"""
import hashlib
import os
import time
from pathlib import Path

from lujarch_test import check, device, hex_rgb, note, run, share_near, step

OCHRE = hex_rgb("#B5462B")


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    d = device()
    tree = Path(os.environ["LUJARCH_TREE"])
    generated = tree / "vendor" / "extra" / "features" / "branding" / "generated"
    product = Path(os.environ["LUJARCH_PRODUCT_OUT"])

    step("the start animation on the device is the one the drawing makes")
    on_device = d.sh("sha256sum /product/media/bootanimation.zip").split()[0]
    check(on_device == sha(generated / "bootanimation.zip"), "bootanimation.zip is the generated one")

    step("the start animation plays the symbol")
    d.sh("setprop service.bootanim.exit 0; setprop service.bootanim.progress 0; start bootanim")
    try:
        time.sleep(4.5)
        frame = d.shot("start-animation")
        share = share_near(frame, OCHRE, 90, (240, 800, 840, 1600), 3)
        note(f"ochre share in the middle: {share:.3f}")
        check(share > 0.01, "the ochre shards show in the middle of the screen")
    finally:
        d.sh("setprop service.bootanim.exit 1")
    time.sleep(2)

    step("recovery and fastbootd show the drawing's images")
    recovery = product / "recovery" / "root" / "res" / "images"
    made = generated / "recovery" / "res" / "images"
    names = sorted(p.name for p in made.iterdir())
    check(names, f"{len(names)} recovery images made")
    differ = [n for n in names if not (recovery / n).is_file() or sha(recovery / n) != sha(made / n)]
    check(not differ, f"recovery's ramdisk holds each of them: {', '.join(differ[:5])}")
    check(b"Lujarch" in (product / "recovery" / "root" / "system" / "bin" / "recovery").read_bytes(),
          "recovery names Lujarch")

    step("the System updates card shows the mark in the symbol's colors")
    color = d.sh("cmd overlay lookup org.lineageos.updater org.lineageos.updater:color/brand_primary").strip()
    check(color.lower().endswith("b5462b"), f"the card's color is the symbol's ({color})")
    d.unlock()
    d.start("-n org.lineageos.updater/.UpdatesActivity")
    shot = d.shot("system-updates")
    check(share_near(shot, OCHRE, 60, (40, 250, 1040, 830), 4) > 0.3, "the card is red ochre")
    d.home()


run(main)
