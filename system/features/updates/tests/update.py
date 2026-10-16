"""System updates installs a local package with Android's A/B update and asks no server; the new
build waits for confirmation, refuses another update meanwhile, can be confirmed or return to the
previous build, and every setting, app and all data are as before the update.

verifies: UPD-1, UPD-2, UPD-3, UPD-4, CORE-12
timeout: 5400
"""
import json
import os
import re
import subprocess
import time
from pathlib import Path

from lujarch_test import AppProbe, check, device, note, run, started, step

TREE = Path(os.environ.get("LUJARCH_TREE", os.path.expanduser("~/Code/vendor/lineage-24.0")))
PRODUCT = Path(os.environ.get("LUJARCH_PRODUCT_OUT", ""))
TESTKEY = TREE / "build/make/target/product/security/testkey"
PACKAGE = "/sdcard/Download/lujarch-update.zip"
# A setting and a piece of data the user made before the update.
SETTING = ("system", "screen_off_timeout", "123000")


def ota_package():
    """A full OTA package of the virtual device's build, signed with the key it trusts."""
    records = Path(os.environ["LUJARCH_DEVICES"])
    image = json.loads((records / "vm.json").read_text()).get("image")
    target = next(PRODUCT.glob("obj/PACKAGING/target_files_intermediates/*-target_files.zip"), None)
    check(target and target.stat().st_mtime > (PRODUCT / "system.img").stat().st_mtime - 3600,
          "the build's target files are of its images (tools/lujarch build vm "
          "target-files-package otatools)")
    out = Path(os.environ["LUJARCH_TEST_OUT"]).parents[3] / "ota" / f"vm-{image}.zip"
    if not out.is_file():
        out.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(TREE / "out/host/linux-x86/bin/ota_from_target_files"), "-k",
                        str(TESTKEY), str(target), str(out)], check=True, cwd=TREE,
                       env=dict(os.environ, PATH=f"{TREE}/out/host/linux-x86/bin:"
                                                 f"{os.environ['PATH']}"))
    return out


def updates(d):
    d.unlock()
    d.start("-f 0x10008000 -n org.lineageos.updater/.UpdatesActivity", wait=3)


def install_local(d):
    updates(d)
    d.tap(desc="More options")
    d.tap(d.wait(text="Install local update"))


def slot(d):
    return d.prop("ro.boot.slot_suffix")


def main():
    d = device()
    p = AppProbe(d)
    package = ota_package()
    note(f"package {package.name}, {package.stat().st_size >> 20} MB")
    d.push(str(package), PACKAGE)

    step("before: a setting, an app and its data the user made")
    d.put_setting(*SETTING)
    p.install()
    p.allow()
    p.do("copy", "--es text kept")
    data = p.results()
    check(data.get("copy"), "the probe has data")
    before = slot(d)

    step("Check for updates asks no server")
    d.logcat_clear()
    updates(d)
    d.tap(desc="Check for updates")
    time.sleep(5)
    log = d.logcat("UpdaterController", "UpdatesCheckReceiver", "Updater")
    check(not re.search(r"https?://", log), "no request to a server")
    jobs = d.sh("dumpsys jobscheduler | grep -i 'org.lineageos.updater'", check=False)
    check("JOB" not in jobs, f"no periodic check is scheduled ({jobs.strip()[:120]})")
    d.shot("updates-check")

    step("Install local update: copied, verified and installed by Android's A/B update")
    install_local(d)
    d.tap(d.wait(text="Download", timeout=20))
    d.tap(d.wait(text=Path(PACKAGE).name, timeout=20))
    restart = d.wait(text="Restart now", timeout=1800)
    d.shot("update-installed")
    d.tap(restart)
    started(d, 900)
    check(slot(d) != before, f"the update runs from the other slot ({before} → {slot(d)})")

    step("after: every setting, the app and its data are as before")
    check(d.setting(*SETTING[:2]) == SETTING[2], "the setting is kept")
    check(d.package_installed(p.PACKAGE) and p.results().get("copy") == data.get("copy"),
          "the app and its data are kept")

    step("the new build waits for confirmation and refuses another update")
    updates(d)
    check(d.wait(text="This build waits for confirmation", timeout=30), "the card says it waits")
    check(d.find(text="Confirm this build") and d.find(text="Return to the previous build"),
          "it offers to confirm and to return")
    d.shot("waits-for-confirmation")
    install_local(d)
    check(d.wait(text="Another update cannot be installed yet"), "another update is refused")
    check(d.find(contains="waits for confirmation"), "and the dialog says why")
    d.shot("second-update-refused")
    d.back()

    step("Confirm automatically: Never or a time, 30 minutes by default")
    updates(d)
    d.tap(d.scroll_to(text="Confirm automatically"))
    for choice in ("Never", "30 minutes", "1 day", "3 days", "7 days", "14 days", "30 days"):
        check(d.find(text=choice), f"offers {choice}")
    check(d.find(text="30 minutes").checked, "30 minutes is chosen")
    d.back()

    step("Return to the previous build")
    updates(d)
    d.tap(d.wait(text="Return to the previous build"))
    d.tap(d.wait(text="Restart now", timeout=30) or d.wait(text="OK"))
    started(d, 900)
    check(slot(d) == before, f"the previous build runs again ({slot(d)})")
    check(d.setting(*SETTING[:2]) == SETTING[2] and p.results().get("copy") == data.get("copy"),
          "the data is as it was")

    step("Confirm this build lets Android merge the update")
    d.push(str(package), PACKAGE)
    install_local(d)
    d.tap(d.wait(text="Download", timeout=20))
    d.tap(d.wait(text=Path(PACKAGE).name, timeout=20))
    d.tap(d.wait(text="Restart now", timeout=1800))
    started(d, 900)
    updates(d)
    d.tap(d.wait(text="Confirm this build", timeout=30))
    d.tap(d.wait(text="Confirm"))
    check(d.wait(contains="merg", timeout=60), "the card says the update is merged")
    d.shot("confirmed-merging")
    d.put_setting(SETTING[0], SETTING[1], None)
    d.home()


run(main)
