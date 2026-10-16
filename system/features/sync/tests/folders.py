"""Folders offered by other devices are listed, to be added, ignored for good, or left for later;
a folder is added with its name, ID, local folder in the sync storage, type and devices, with an
encryption password typed into a masked field and never shown again. A folder's page shows its
state and files, rescans, moves to another directory of the sync storage, overrides remote changes
after saying what that does, and holds its settings; removing a folder keeps its files.

verifies: SYN-5
timeout: 2400
"""
import time

from _sync import (FOLDERS, Peer, accept_folder, add_device, device_files, fill, my_device_id, offer,
                   open_sync, reset, summary_of, turn_on, wait_row)
from lujarch_test import check, device, note, run, step

PASSWORD = "made-up-folder-password"


def folder_page(d, label):
    open_sync(d)
    d.tap(d.scroll_to(text=label))
    check(d.wait(text="Sync this folder", timeout=20), f"{label}'s page shows")


def main():
    d = device()
    reset(d)
    a = Peer("Desktop A")
    try:
        turn_on(d)
        my_id = my_device_id(d)
        a.start()
        a.reach_device(my_id)
        add_device(d, a, "Desktop A")
        check(a.wait("connected", lambda: a.connected(my_id), 90), "the phone and the peer connect")

        step("offered folders: left for later, ignored for good, added")
        music = a.share("music", "Music", [my_id])
        docs = a.share("docs", "Docs", [my_id])
        (docs / "a.txt").write_text("one\n")
        d.tap(offer(d, "Music"))
        d.shot("offered")
        d.tap(d.wait(text="Ignore"))
        time.sleep(3)
        check(not d.find(text="Music"), "the ignored folder is gone")
        d.tap(offer(d, "Docs"))
        d.tap(d.wait(text="Later"))
        time.sleep(3)
        check(not d.find(text="Docs"), "Later takes the offer away for now")
        # The desktop connects again, and offers the folder again with it.
        a.patch(f"/rest/config/devices/{my_id}", {"paused": True})
        time.sleep(3)
        a.patch(f"/rest/config/devices/{my_id}", {"paused": False})
        accept_folder(d, "Docs", timeout=180)
        check(a.wait("file", lambda: "a.txt" in device_files(d, f"{FOLDERS}/Docs"), 120),
              "the added folder syncs")
        open_sync(d)
        check(not d.find(text="Music"), "the ignored folder is not offered again")

        step("a folder made on the phone, shared encrypted with a password")
        open_sync(d)
        d.tap(d.scroll_to(text="Add folder"))
        d.tap(d.wait(text="Folder name"))
        fill(d, "Notes")
        d.tap(d.wait(text="OK"))
        d.tap(d.scroll_to(text="Shared with"))
        d.tap(d.wait(text="Desktop A"))
        d.tap(d.wait(text="OK"))
        d.tap(d.scroll_to(text="Encrypted sharing"))
        d.tap(d.wait(contains="Desktop A", timeout=10))
        field = next((n for n in d.ui() if n.cls.endswith("EditText")), None)
        check(field and field.password, "the password is typed into a masked field")
        fill(d, PASSWORD)
        d.tap(d.wait(text="OK"))
        d.tap(d.scroll_to(text="Local folder"))
        d.tap(d.wait(text="New directory"))
        fill(d, "Notes")
        d.tap(d.wait(text="OK"))
        for row in ("Folder name", "Folder ID", "Local folder", "Folder type", "Shared with"):
            check(d.scroll_to(text=row), f"the new folder has {row}")
        check(summary_of(d, "Local folder") == "Sync storage/Notes", "in the sync storage")
        d.shot("add-folder")
        # The page's title has the action's words too: the action is the row below.
        d.tap(max((n for n in d.ui() if n.text == "Add folder"), key=lambda n: n.y))
        offered = a.wait("offer", lambda: a.pending_folders().get("notes") or next(
            (v for k, v in a.pending_folders().items() if "Notes" in str(v)), None), 120)
        note(f"the peer is offered: {offered}")
        check(offered and '"receiveEncrypted": true' in str(offered).replace("'", '"').replace(
            "True", "true"), "the peer is offered the folder encrypted")
        folder_page(d, "Notes")
        d.scroll_to(text="Encrypted sharing")
        check(PASSWORD not in "".join(n.text for n in d.ui()), "the password is not shown again")

        step("a folder's page: state, files, rescan and its settings")
        folder_page(d, "Docs")
        for row in ("Sync state", "Local folder", "Files", "Rescan", "Folder name", "Folder ID",
                    "Folder type", "Shared with", "Encrypted sharing", "Ignore patterns",
                    "File versioning", "Watch for changes", "Full scan", "Remove folder"):
            check(d.scroll_to(text=row, limit=8), f"it holds {row}")
        d.scroll_to(text="Sync state")
        check(summary_of(d, "Sync state") in ("Up to date", "Scanning") or "Syncing" in summary_of(d, "Sync state"),
              f"the state shows ({summary_of(d, 'Sync state')})")
        check("1" in summary_of(d, "Files"), f"the files show ({summary_of(d, 'Files')})")
        d.shot("folder-page")
        d.tap(d.find(text="Rescan"))
        time.sleep(3)
        check("Last scan" in summary_of(d, "Rescan") or summary_of(d, "Sync state") == "Up to date",
              "it rescans")

        step("override: a send-only folder puts its own files back on the other devices")
        d.tap(d.scroll_to(text="Folder type"))
        d.tap(d.wait(contains="Send only"))
        time.sleep(5)
        (docs / "a.txt").write_text("changed on the desktop\n")
        a.post(f"/rest/db/scan?folder=docs")
        folder_page(d, "Docs")
        check(d.wait(text="Override changes", timeout=120), "Override changes is offered")
        d.tap(d.scroll_to(text="Override changes"))
        check(d.wait(text="Override the other devices?", timeout=10), "it says what overriding does first")
        d.shot("override")
        d.tap(d.wait(text="Override"))
        check(a.wait("restored", lambda: (docs / "a.txt").read_text() == "one\n", 120),
              "the desktop has the phone's version again")

        step("moved to another directory of the sync storage")
        d.tap(d.scroll_to(text="Folder type"))
        d.tap(d.wait(contains="Send and receive"))
        time.sleep(3)
        folder_page(d, "Docs")
        d.tap(d.scroll_to(text="Local folder"))
        d.tap(d.wait(text="New directory"))
        fill(d, "Documents")
        d.tap(d.wait(text="OK"))
        d.tap(d.wait(text="Move", timeout=10))
        check(a.wait("moved", lambda: "a.txt" in device_files(d, f"{FOLDERS}/Documents"), 180),
              "the folder's files are in the new directory")

        step("removing a folder keeps its files")
        folder_page(d, "Docs")
        d.tap(d.scroll_to(text="Remove folder"))
        d.tap(d.wait(text="Remove"))
        time.sleep(3)
        open_sync(d)
        check(not d.find(text="Docs"), "the folder is gone from the page")
        check("a.txt" in device_files(d, f"{FOLDERS}/Documents"), "its files stay")
    finally:
        a.stop()
        reset(d)
        d.home()


run(main)
