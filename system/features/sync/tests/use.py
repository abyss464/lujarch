"""Use sync, off by default, runs the sync service from every start of the phone and exchanges the
folders' changes with the devices they are shared with; off, it does not run. The page shows the
service's state and version, this device's name, which can be changed, and its device ID as text
to copy and as a QR code; Connected devices has a Sync row whose summary is the state.

verifies: SYN-1
timeout: 1800
"""
import time

from _sync import (FOLDERS, Peer, accept_folder, add_device, device_files, engine_running, fill,
                   open_sync, reset, state, summary_of, turn_off, turn_on, wait_state)
from lujarch_test import check, device, note, restart, run, step

NAME = "Lujarch test phone"


def connected_devices_summary(d):
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.SETTINGS", wait=2)
    d.tap(d.scroll_to(text="Connected devices"))
    d.scroll_to(text="Sync")
    # Settings fills the row's summary in once Sync's provider answers.
    deadline = time.monotonic() + 15
    while (got := summary_of(d, "Sync")) in ("", "Loading…") and time.monotonic() < deadline:
        time.sleep(1)
    note(f"Connected devices: Sync, {got!r}")
    return got


def main():
    d = device()
    reset(d)
    peer = Peer("Desktop")
    try:
        step("off by default: the service does not run")
        open_sync(d, through_settings=True)
        check(not d.switch_of("Use sync").checked, "Use sync is off")
        check(not engine_running(d), "the sync service does not run")
        d.shot("sync-off")
        check(connected_devices_summary(d) == "Off", "Connected devices: Sync, Off")

        step("on: the service runs, with its state and version")
        turn_on(d)
        open_sync(d)
        service = summary_of(d, "Service")
        note(f"Service: {service}")
        check(service.startswith("Running") and "v2." in service, "the state and the version show")
        summary = connected_devices_summary(d)
        check(summary.startswith("On") and "Running" in summary, f"Connected devices: Sync, {summary}")

        step("this device's name can be changed")
        open_sync(d)
        d.tap(d.scroll_to(text="Device name"))
        fill(d, NAME)
        d.tap(d.wait(text="OK"))
        deadline = time.monotonic() + 15
        while summary_of(d, "Device name") != NAME and time.monotonic() < deadline:
            time.sleep(1)
        check(summary_of(d, "Device name") == NAME, "the new name shows")

        step("the device ID as text to copy and as a QR code")
        d.scroll_to(text="Device ID")
        shown = summary_of(d, "Device ID").replace("\n", "")
        d.tap(d.find(text="Device ID"))
        d.wait(text="Copy", timeout=10)
        dialog = {n.desc for n in d.ui() if n.desc}
        my_id = next((t for t in dialog if len(t) == 63 and t.count("-") == 7), "")
        note(f"device ID {my_id[:7]}…, shown as {shown[:7]}…")
        check(my_id and shown == my_id, "the page and the QR code carry the same device ID")
        check(d.find(text="Copy"), "it can be copied")
        d.shot("device-id")
        d.tap(d.find(text="Copy"))

        step("the folders' changes are exchanged with a device")
        peer.start()
        peer.reach_device(my_id, NAME)
        add_device(d, peer, "Desktop")
        check(peer.wait("connected", lambda: peer.connected(my_id), 90), "the phone and the peer connect")
        folder = peer.share("docs", "Docs", [my_id])
        (folder / "from-desktop.txt").write_text("written on the desktop\n")
        accept_folder(d, "Docs")
        check(peer.wait("file", lambda: "from-desktop.txt" in device_files(d, f"{FOLDERS}/Docs"), 120),
              "a file of the desktop reaches the phone")
        d.sh(f"echo written on the phone > {FOLDERS}/Docs/from-phone.txt; "
             f"chown 5100:5100 {FOLDERS}/Docs/from-phone.txt")
        check(peer.wait("file", lambda: (folder / "from-phone.txt").is_file(), 120),
              "a file of the phone reaches the desktop")
        open_sync(d)
        d.shot("sync-running")

        step("it runs again from the next start of the phone")
        peer.stop()
        restart(d)
        check(wait_state(d, "running", 120), f"the service runs after the start ({state(d)})")

        step("off: the service stops and stays off")
        turn_off(d)
        time.sleep(3)
        check(not engine_running(d), "the sync service does not run")
        check(connected_devices_summary(d) == "Off", "Connected devices: Sync, Off")
    finally:
        peer.stop()
        reset(d)
        d.home()


run(main)
