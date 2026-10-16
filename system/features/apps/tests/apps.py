"""Files, Music, App Store and Reader come with the system: installed in the product partition,
built and signed by the system's build rather than their developers, in the launcher, Files, Music
and App Store named in each language as terms.md names them.

verifies: APP-2, APP-3
timeout: 900
"""
import os
import re
import subprocess
import tempfile
from pathlib import Path

from lujarch_test import ROOT, check, device, note, run, sdk_tool, step

APPS = {"Files": "me.zhanghai.android.files", "Music": "org.feeluown.mobile",
        "App Store": "dev.imranr.obtainium.fdroid", "Reader": "org.lujarch.reader"}
TREE = Path(os.environ.get("LUJARCH_TREE", os.path.expanduser("~/Code/vendor/lineage-24.0")))
# The virtual device's build signs apps without their own certificate with the test key; a
# release signs them with the owner's keys (the release flow verifies those).
BUILD_CERT = TREE / "build/make/target/product/security/testkey.x509.pem"


def terms():
    rows = {}
    for line in (ROOT / "target" / "terms.md").read_text().splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) == 3:
            rows[cells[0]] = {"": cells[0], "zh-CN": cells[1], "ja": cells[2]}
    return rows


def cert_digest(path):
    out = subprocess.run(["openssl", "x509", "-in", str(path), "-noout", "-fingerprint", "-sha256"],
                         capture_output=True, text=True).stdout
    return out.split("=", 1)[-1].replace(":", "").strip().lower()


def main():
    d = device()
    names = terms()
    build = cert_digest(BUILD_CERT)
    work = Path(tempfile.mkdtemp(prefix="apps-"))
    for label, package in APPS.items():
        step(f"{label} ({package})")
        path = d.sh(f"pm path {package}").strip().removeprefix("package:")
        check(path.startswith("/product/app/"), f"{label} comes with the system: {path}")
        launcher = d.sh(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "
                        f"-a android.intent.action.MAIN {package}").strip().splitlines()[-1]
        check(launcher.startswith(package + "/"), f"{label} is in the launcher ({launcher})")
        apk = work / f"{package}.apk"
        d.pull(path, str(apk))
        certs = subprocess.run([sdk_tool("apksigner"), "verify", "--min-sdk-version", "34",
                                "--print-certs", str(apk)],
                               capture_output=True, text=True).stdout
        digest = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", certs)
        if label == "Reader":
            note(f"Reader is signed with {digest.group(1)[:16] if digest else '?'}; a release signs "
                 "it with its own key")
        else:
            check(digest and digest.group(1) == build,
                  f"{label} is signed by the system's build, not its developer")
        if label in names and label != "Reader":
            badging = subprocess.run([sdk_tool("aapt2"), "dump", "badging", str(apk)],
                                     capture_output=True, text=True).stdout
            for lang, want in names[label].items():
                key = "application-label" + (f"-{lang}" if lang else "")
                got = re.search(rf"^{key}:'([^']*)'", badging, re.M)
                check(got and got.group(1) == want, f"{label} in {lang or 'en'}: {want} "
                                                     f"({got.group(1) if got else 'none'})")
    step("Settings > Apps > Reader")
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS "
            "-d package:org.lujarch.reader")
    check(d.wait(text="Reader", timeout=20), "Reader's App info opens")
    d.shot("reader-app-info")
    d.drawer()
    d.shot("drawer")
    d.home()


run(main)
