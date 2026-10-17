"""The image holds the product and nothing else: no development or test component, no person's
name, account or server in any default, and no personal setting. Checked on the phone's image
(the fuxi build of the same commit) and on the virtual device.

verifies: CORE-11
"""
import json
import os
import re
import subprocess
import tomllib
from pathlib import Path

from lujarch_test import ROOT, check, device, note, run, step

# Development and test components by their installed path.
TEST_PATH = re.compile(
    r"(?i)cts[._]?shim|/nativetest|/testcases|/benchmarktest"
    r"|(test|tests|demo|example|sample)[^/]*\.(apk|apex|capex|jar)$"
    r"|/bin/[^/]*test[^/]*$|[.-]example$|\.virtual\.apex$")
# Android's own libraries apps link against (uses-library), its FIPS self-test, which runs at
# every start, and toybox's test command.
ANDROID_OWN = re.compile(
    r"/framework/(oat/[^/]+/)?android\.test\.(base|mock|runner)\.(jar|odex|vdex)$"
    r"|/bin/boringssl_self_test(32|64)?$|/bin/test$")
TEXT = (".prop", ".xml", ".json", ".conf", ".yaml", ".yml", ".rc", ".toml", ".cfg", ".ini")


def image_files(product_out):
    files = []
    for listing in sorted(Path(product_out).glob("installed-files*.txt")):
        for line in listing.read_text().splitlines():
            if line.strip():
                files.append(line.split()[-1])
    return files


def device_hals(files):
    """The HAL services whose VINTF manifest fragment the image installs: the device's own, served
    whatever their name (Android's IR HAL is named example)."""
    fragments = {Path(f).stem for f in files if "/etc/vintf/manifest/" in f and f.endswith(".xml")}
    return {f for f in files if "/bin/hw/" in f and Path(f).name in fragments}


def owner_words():
    """What names the computer's owner: their git identity, user and host names."""
    words = set()
    for key in ("user.name", "user.email"):
        v = subprocess.run(["git", "-C", str(ROOT), "config", key], capture_output=True,
                           text=True).stdout.strip()
        if v:
            words.add(v)
            if "@" in v:
                words.add(v.split("@")[0])
    words.add(os.environ.get("USER", ""))
    words.add(os.uname().nodename)
    return sorted(w for w in words if len(w) > 3)


def main():
    d = device()
    info_dir = Path(os.environ["LUJARCH_DEVICES"])
    fuxi = json.loads((info_dir / "fuxi.json").read_text())
    vm = json.loads((info_dir / "vm.json").read_text())
    step("the phone's image is of this commit")
    check(fuxi.get("image") == vm.get("image"),
          f"fuxi image {fuxi.get('image')} and vm image {vm.get('image')} are one commit")
    product = Path(fuxi["product_out"])

    step("no development or test component in the image")
    files = image_files(product)
    check(len(files) > 1000, f"the image's file lists ({len(files)} files)")
    hals = device_hals(files)
    found = [f for f in files if TEST_PATH.search(f) and not ANDROID_OWN.search(f) and f not in hals]
    check(not found, f"no test component ({found[:20]})")
    packages = d.sh("pm list packages -f -s").splitlines()
    shipped = set(files)
    on_device = [p for p in packages if re.search(r"(?i)cts|shim\.|\.test(s)?\b|example|sample", p)]
    product_part = [p for p in on_device if re.sub(r"^package:(.*)=.*$", r"\1", p) in shipped]
    note(f"the virtual device's own device layer adds: {on_device}")
    check(not product_part, f"no test package of the product on the device ({product_part})")

    step("no person's name, account or server in any default")
    words = owner_words()
    check(words, "the owner's names to look for")
    hits = []
    for f in files:
        path = product / f.lstrip("/")
        if path.suffix not in TEXT + (".apk",):
            continue
        try:
            data = path.read_bytes()
        except OSError:
            continue
        for w in words:
            if w.encode() in data:
                hits.append(f"{f}: {w}")
    check(not hits, f"the owner is named nowhere in the image ({hits[:10]})")
    props = d.sh("getprop | grep -E 'build\\.(user|host)\\]'")
    check(not any(w in props for w in words), f"the build user and host name no one ({props.strip()})")

    step("Lujarch's settings default to no personal value")
    personal = []
    for path in sorted((ROOT / "system" / "features").glob("*/settings.toml")):
        for key, spec in tomllib.loads(path.read_text()).items():
            value = str(spec.get("default", ""))
            if re.search(r"@|https?://|\b\d{1,3}(\.\d{1,3}){3}\b", value) or any(
                    w in value for w in words):
                personal.append(f"{path.parent.name}.{key} = {value}")
    check(not personal, f"no address, account or server in a default ({personal})")


run(main)
