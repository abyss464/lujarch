"""Every text of the image that names LineageOS says Lujarch on the device, except the credits;
About phone > Android version shows the Lujarch version.

verifies: BRD-1
"""
import os
import re
import subprocess
import tomllib
from pathlib import Path

from lujarch_test import ROOT, check, device, note, quote, run, sdk_tool, step

NAMES = re.compile(r"\bLineage(?:OS)?\b")
# Strings that credit the LineageOS project itself.
CREDITS = re.compile(r"licen[cs]e|legal|contributor|credit|api_level|copyright", re.I)


def aapt2():
    return sdk_tool("aapt2")


def kept():
    names = set()
    for f in (ROOT / "system" / "features").glob("*/feature.toml"):
        for spec in tomllib.loads(f.read_text()).get("renames", {}).values():
            names.update(spec.get("keep", []))
    return names


def naming_strings(apk):
    """(package, names of default strings that name LineageOS) of one APK."""
    package = subprocess.run([aapt2(), "dump", "packagename", str(apk)], capture_output=True,
                             text=True).stdout.strip()
    dump = subprocess.run([aapt2(), "dump", "resources", str(apk)], capture_output=True,
                          text=True).stdout
    found = []
    for m in re.finditer(r"resource 0x\w+ string/(\S+)\n\s+\(\) \"(.*)\"", dump):
        if NAMES.search(m.group(2)):
            found.append(m.group(1))
    return package, found


def main():
    d = device()
    product = Path(os.environ["LUJARCH_PRODUCT_OUT"])
    keep = kept()
    step("find every string of the image's apps whose text names LineageOS")
    wanted = {}
    for apk in sorted(p for part in ("system", "system_ext", "product")
                      for p in (product / part).rglob("*.apk")):
        package, names = naming_strings(apk)
        names = [n for n in names if not CREDITS.search(n) and n not in keep]
        if package and names:
            wanted.setdefault(package, set()).update(names)
    note(f"{sum(len(v) for v in wanted.values())} strings in {len(wanted)} packages")
    check(wanted, "the image's apps have strings that name the system")

    step("on the device each of them says Lujarch")
    wrong = []
    for package, names in sorted(wanted.items()):
        script = "; ".join(f"echo {n}=$(cmd overlay lookup {package} {package}:string/{n} 2>&1)"
                           for n in sorted(names))
        for line in d.sh(script, timeout=600).splitlines():
            name, _, value = line.partition("=")
            if NAMES.search(value) or "Error" in value:
                wrong.append(f"{package}:{name} = {value[:80]}")
    for w in wrong[:20]:
        note(w)
    check(not wrong, f"{len(wrong)} texts still name LineageOS")

    step("About phone > Android version shows the Lujarch version")
    version = d.prop("ro.lujarch.version")
    check(re.fullmatch(r"(\d+-)?[0-9a-f]{12}", version or ""), f"the version is a commit: {version}")
    d.unlock()
    d.start("-a android.settings.DEVICE_INFO_SETTINGS")
    d.wait(text="Device name", timeout=40)
    shown = set(d.texts())
    for _ in range(4):
        d.swipe(540, 1800, 540, 700)
        shown |= set(d.texts())
    named = sorted(t for t in shown if "LineageOS" in t)
    check(not named, f"About phone names LineageOS nowhere ({named})")
    d.tap(d.scroll_to(text="Android version"))
    label = d.scroll_to(text="Lujarch version")
    nodes = d.ui()
    check(any(n.text == version for n in nodes if 0 < n.y - label.y < 140),
          f"Lujarch version: {version}")
    check(not d.find(contains="LineageOS version", nodes=nodes), "no LineageOS version row")
    d.shot("android-version")
    d.home()


run(main)
