"""LineageOS' browser, calendar, camera, audio effects, messaging and music apps and Android's file
manager are not in the image: not on the virtual device, and not in the phone's image of the same
commit.

verifies: APP-4
"""
import json
import os
from pathlib import Path

from lujarch_test import check, device, note, run, step

REPLACED = {
    "browser": ("org.lineageos.jelly", "Jelly"),
    "calendar": ("org.lineageos.etar", "Etar"),
    "camera": ("org.lineageos.aperture", "Aperture"),
    "audio effects": ("org.lineageos.audiofx", "AudioFX"),
    "messaging": ("com.android.messaging", "messaging"),
    "music": ("org.lineageos.twelve", "Twelve"),
    "file manager": ("com.android.documentsui", "DocumentsUI"),
}


def main():
    d = device()
    packages = set(d.sh("pm list packages").replace("package:", "").split())
    step("not on the virtual device")
    for what, (package, _) in REPLACED.items():
        check(package not in packages, f"no {what} ({package})")
    step("not in the phone's image")
    records = Path(os.environ["LUJARCH_DEVICES"])
    fuxi = json.loads((records / "fuxi.json").read_text())
    vm = json.loads((records / "vm.json").read_text())
    note(f"the phone's image: {fuxi.get('image')}")
    check(fuxi.get("image") == vm.get("image"), "the phone's image is of the same commit")
    files = []
    for listing in Path(fuxi["product_out"]).glob("installed-files*.txt"):
        files += [line.split()[-1] for line in listing.read_text().splitlines() if line.strip()]
    for what, (_, module) in REPLACED.items():
        found = [f for f in files if f"/{module}/" in f or f"/{module}.apk" in f]
        check(not found, f"no {what} in the phone's image ({found[:3]})")


run(main)
