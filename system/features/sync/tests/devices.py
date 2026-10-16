"""Devices asking to connect are listed, to be added with a name, ignored for good, or left for
later; a device is added by its typed device ID; each device row says where it is reached first,
Cloud or Local network, and whether it is connected. A device's page holds its name, addresses,
shared folders, introducer, accepting its folders, untrusted, compression, Replace with another
device and removal; replacing gives the new device every folder the old one has, the same way, and
then pauses it. Pause all and Resume all act on every device.

verifies: SYN-4
timeout: 2400
"""
import subprocess
import time

from _sync import (OUT, Peer, accept_folder, add_device, fill, my_device_id, open_sync, reset,
                   summary_of, turn_on, wait_row)
from lujarch_test import check, device, note, run, step

# A device the phone reaches only from the internet: a documentation address, never answered.
CLOUD_ADDRESS = "tcp://203.0.113.7:22000"


def made_up_id():
    """The device ID of an identity made for the test and never run."""
    home = OUT / "peer-unused"
    subprocess.run(["syncthing", "generate", f"--home={home}", "--no-port-probing"], check=True,
                   capture_output=True, timeout=60)
    return subprocess.run(["syncthing", "device-id", f"--home={home}"], check=True,
                          capture_output=True, text=True, timeout=60).stdout.strip()


def row_summary(d, name, want=None, timeout=60):
    open_sync(d)
    deadline = time.monotonic() + timeout
    while True:
        d.scroll_to(text=name)
        got = summary_of(d, name)
        if want is None or want in got or time.monotonic() > deadline:
            return got
        time.sleep(2)
        d.start("-f 0x10008000 -a org.lujarch.sync.action.SYNC", wait=1)


def request(d, name, timeout=90):
    """The request row of a device asking to connect, by the name it gives."""
    open_sync(d)
    row = wait_row(d, timeout, text=name)
    check(row and "Asks to connect" in summary_of(d, name), f"{name} asks to connect")
    return row


def main():
    d = device()
    reset(d)
    a, b, c, e = Peer("Desktop A", 22100), Peer("Desktop B", 22101), Peer("Desktop C", 22102), \
        Peer("Server E", 22103)
    try:
        turn_on(d)
        my_id = my_device_id(d)

        step("a device asking to connect: left for later, then added with a name")
        b.start()
        b.reach_device(my_id)
        d.tap(request(d, "Desktop B"))
        d.shot("request")
        d.tap(d.wait(text="Later"))
        time.sleep(3)
        check(not d.find(text="Desktop B"), "Later takes the request away for now")
        d.tap(request(d, "Desktop B", timeout=120))
        d.tap(d.wait(text="Add"))
        fill(d, "Desktop B")
        d.tap(d.wait(text="Next"))
        fill(d, f"tcp://127.0.0.1:{b.port}")
        d.tap(d.wait(text="Add"))
        summary = row_summary(d, "Desktop B", "Connected")
        note(f"Desktop B: {summary}")
        check(summary.startswith("Local network") and "Connected" in summary,
              "its row says Local network and Connected")

        step("a device asking to connect, ignored for good")
        c.start()
        c.reach_device(my_id)
        d.tap(request(d, "Desktop C"))
        d.tap(d.wait(text="Ignore"))
        d.tap(d.wait(text="Ignore", timeout=10))
        time.sleep(15)
        open_sync(d)
        check(not d.find(text="Desktop C"), "it is not listed again")
        d.tap(d.scroll_to(text="Ignored devices"))
        check(d.wait(text="Desktop C", timeout=15), "it is listed under Ignored devices")
        d.back()
        c.stop()

        step("a device added by its typed device ID; a public address reads Cloud")
        a.start()
        add_device(d, a, "Desktop A")
        check(a.wait("pending", lambda: my_id in a.pending_devices(), 60), "the phone dials it")
        open_sync(d)
        d.tap(d.scroll_to(text="Add device"))
        fill(d, made_up_id())
        d.tap(d.wait(text="Next"))
        fill(d, "Cloud server")
        d.tap(d.wait(text="Next"))
        fill(d, CLOUD_ADDRESS)
        d.tap(d.wait(text="Add"))
        summary = row_summary(d, "Cloud server")
        note(f"Cloud server: {summary}")
        check(summary.startswith("Cloud") and CLOUD_ADDRESS in summary, "its row says Cloud")
        d.shot("devices")

        step("a device's page")
        a.reach_device(my_id)
        a.share("docs", "Docs", [my_id])
        accept_folder(d, "Docs")
        open_sync(d)
        d.tap(d.scroll_to(text="Desktop A"))
        for row in ("Sync with this device", "Device name", "Addresses", "Shared folders",
                    "Introducer", "Accept its folders", "Untrusted device", "Compression",
                    "Replace with another device", "Remove device"):
            check(d.scroll_to(text=row, limit=8), f"it holds {row}")
        d.scroll_to(text="Sync with this device")
        d.shot("device-page")
        d.tap(d.switch_of("Introducer"))
        time.sleep(2)
        check(d.switch_of("Introducer").checked, "Introducer turns on")
        d.tap(d.switch_of("Introducer"))

        step("Replace with another device: the new one gets its folders, then it is paused")
        e.start()
        e.reach_device(my_id)
        d.tap(d.scroll_to(text="Replace with another device"))
        fill(d, e.id)
        d.tap(d.wait(text="Next"))
        fill(d, "Server E")
        d.tap(d.wait(text="Next"))
        fill(d, f"tcp://127.0.0.1:{e.port}")
        d.tap(d.wait(text="Next"))
        check(d.wait(contains="Docs", timeout=15), "the confirmation names the folders it takes over")
        d.tap(d.wait(contains="Then pause"))
        d.shot("replace")
        d.tap(d.wait(text="Replace"))
        check(e.wait("offer", lambda: "docs" in e.pending_folders(), 120),
              "the new device is offered the folder")
        check("Paused" in row_summary(d, "Desktop A", "Paused"), "the replaced device is paused")

        step("Pause all and Resume all")
        open_sync(d)
        d.tap(d.scroll_to(text="Pause all devices"))
        check(d.wait(text="Resume all devices", timeout=20), "every device is paused")
        for name in ("Desktop B", "Cloud server"):
            check("Paused" in row_summary(d, name, "Paused"), f"{name} is paused")
        d.tap(d.scroll_to(text="Resume all devices"))
        check(d.wait(text="Pause all devices", timeout=20), "every device runs again")

        step("removal")
        open_sync(d)
        d.tap(d.scroll_to(text="Cloud server"))
        d.tap(d.scroll_to(text="Remove device"))
        d.tap(d.wait(text="Remove"))
        open_sync(d)
        time.sleep(3)
        check(not d.find(text="Cloud server"), "the device is gone")
    finally:
        for p in (a, b, c, e):
            p.stop()
        reset(d)
        d.home()


run(main)
