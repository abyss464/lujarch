"""The device identity is imported from files chosen in the document picker, keeping this device's
devices and folders, or created anew without them; the identity it replaces is kept as a backup and
can be restored, and a new identity is in place at once: running sync restarts with it, and the
page says so, or says that sync uses it once it is turned on. The page never shows, logs or keeps
the files.

verifies: SYN-7
timeout: 1800
"""
import os
import subprocess
import time
from pathlib import Path

from _sync import (Peer, add_device, my_device_id, open_sync, reset, seek, state, summary_of, turn_off,
                   turn_on, wait_state)
from lujarch_test import check, device, note, run, step

OUT = Path(os.environ.get("LUJARCH_TEST_OUT", "/tmp"))
FILES = "me.zhanghai.android.files"
SETTINGS = "org.lujarch.settings"
CERT, KEY = "lujarch-test-cert.pem", "lujarch-test-key.pem"


def identity_page(d):
    open_sync(d)
    d.tap(d.scroll_to(text="Device identity"))
    check(d.wait(text="Create a new identity", timeout=20), "Device identity shows")


def choose(d, row, name):
    """A file for a row, chosen in the document picker."""
    d.tap(d.scroll_to(text=row))
    deadline = time.monotonic() + 20
    while d.top()[0] != FILES and time.monotonic() < deadline:
        time.sleep(0.5)
    check(d.top()[0] == FILES, f"{row}: the document picker opens")
    d.tap(d.scroll_to(text="Download"))
    time.sleep(1)
    d.tap(d.scroll_to(text=name))
    check(d.wait(text=name, timeout=15) and summary_of(d, row) == name, f"{row}: {name}")


def device_listed(d, name):
    open_sync(d)
    return bool(seek(d, text=name))


def main():
    d = device()
    reset(d)
    peer = Peer("Desktop A")
    home = OUT / "imported"
    try:
        turn_on(d)
        first = my_device_id(d)
        peer.start()
        add_device(d, peer, "Desktop A")
        subprocess.run(["syncthing", "generate", f"--home={home}", "--no-port-probing"], check=True,
                       capture_output=True, timeout=60)
        imported = subprocess.run(["syncthing", "device-id", f"--home={home}"], check=True,
                                  capture_output=True, text=True, timeout=60).stdout.strip()
        d.push(str(home / "cert.pem"), f"/sdcard/Download/{CERT}")
        d.push(str(home / "key.pem"), f"/sdcard/Download/{KEY}")
        marker = (home / "key.pem").read_text().splitlines()[1][:24]
        d.logcat_clear()

        step("imported from files chosen in the document picker, keeping the devices and folders")
        identity_page(d)
        d.tap(d.wait(text="Import from files"))
        choose(d, "Certificate", CERT)
        choose(d, "Private key", KEY)
        d.scroll_to(text="Configuration")
        check(summary_of(d, "Configuration") == "Optional", "without a configuration, which is optional")
        check(d.switch_of("Keep this device's devices and folders").checked, "keeping them is chosen")
        d.shot("import")
        d.tap(d.scroll_to(text="Import"))
        d.tap(d.wait(text="Import", timeout=10))
        check(d.wait(contains="Sync is restarting with it", timeout=60), "the page says sync restarts with it")
        d.shot("imported")
        d.tap(d.wait(text="OK"))
        check(wait_state(d, "running", 120), "sync runs again")
        now = my_device_id(d)
        note(f"device ID {first[:7]} → {now[:7]} (imported {imported[:7]})")
        check(now == imported, "the phone has the imported identity")
        check(device_listed(d, "Desktop A"), "its devices are kept")

        step("the files are never shown, logged or kept by the page")
        check(marker not in d.sh("logcat -d", check=False), "the key is not in the log")
        kept = d.sh(f"grep -rl '{marker}' /data/user/0/{SETTINGS} /data/user_de/0/{SETTINGS} "
                    f"2>/dev/null", check=False).strip()
        check(not kept, f"Settings keeps no copy ({kept})")

        step("the replaced identity is kept and can be restored")
        identity_page(d)
        backup = d.scroll_to(text=f"Device {first[:7]}")
        check(backup, "the previous identity is listed")
        d.shot("previous-identities")
        d.tap(backup)
        d.tap(d.wait(text="Restore", timeout=10))
        d.wait(text="OK", timeout=60) and d.tap(d.find(text="OK"))
        check(wait_state(d, "running", 120), "sync runs again")
        check(my_device_id(d) == first, "the previous identity is back")

        step("created anew without the devices and folders, while sync is off")
        turn_off(d)
        identity_page(d)
        d.tap(d.wait(text="Create a new identity"))
        box = d.wait(text="Keep this device's devices and folders", timeout=10)
        d.tap(box)
        d.tap(d.wait(text="Create"))
        check(d.wait(text="Turn on sync", timeout=60), "the page says sync uses it once turned on")
        d.shot("created")
        d.tap(d.find(text="Turn on sync"))
        check(wait_state(d, "running", 120), f"sync runs ({state(d)})")
        created = my_device_id(d)
        check(created not in (first, imported), "the phone has a new identity")
        check(not device_listed(d, "Desktop A"), "without the devices")
    finally:
        peer.stop()
        d.sh(f"rm -f /sdcard/Download/{CERT} /sdcard/Download/{KEY}", check=False)
        reset(d)
        d.home()


run(main)
