"""When an app asks to choose a file, save one or choose a folder, Files opens for it; the answer
reaches the app as a document of the phone's storage with the access Android's picker gives
(reading, writing, keeping access; the whole folder for a folder). A folder Android does not let
apps take is refused with the reason and Choose again; images alone go to the photo picker,
whose Browse opens Files.

verifies: FILE-1
timeout: 1500
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step

FILES = "me.zhanghai.android.files"
STORAGE = "content://com.android.externalstorage.documents/"


def ask(d, p, action, extras=""):
    p.clear()
    d.sh(f"am start -n {p.PACKAGE}/.Probe --activity-single-top --es do {action} {extras}")
    deadline = time.monotonic() + 20
    while d.top()[0] in (p.PACKAGE, None) and time.monotonic() < deadline:
        time.sleep(1)
    return d.top()[0]


def answer(p, key, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        got = p.results()
        if key in got:
            time.sleep(2)
            return p.results()
        time.sleep(1)
    return p.results()


def open_folder(d, name):
    d.tap(d.scroll_to(text=name))
    time.sleep(1.5)


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.sh("mkdir -p /sdcard/Download /sdcard/Documents/probe-folder; "
         "echo 'picked by the probe' > /sdcard/Download/probe-pick.txt; "
         "rm -f /sdcard/Download/probe-saved.txt; touch /sdcard/Documents/probe-folder/a.txt")
    d.start(f"-n {p.PACKAGE}/.Probe", wait=2)

    step("choose a file: Files opens; the app reads the document and keeps access")
    check(ask(d, p, "pick") == FILES, "Files opens for the request")
    d.shot("pick-files")
    open_folder(d, "Download")
    d.tap(d.scroll_to(text="probe-pick.txt"))
    got = answer(p, "picked")
    note(got)
    check(got.get("picked", "").startswith(STORAGE), f"a document of the storage ({got.get('picked')})")
    check(got.get("read") == "picked by the probe", f"the app reads it ({got.get('read')})")
    check(got.get("persisted") == "yes", f"and keeps access ({got.get('persisted')})")

    step("save a file: created in the chosen folder")
    check(ask(d, p, "save") == FILES, "Files opens to save")
    open_folder(d, "Download")
    d.tap(d.wait(text="Save", timeout=10))
    got = answer(p, "wrote")
    check(got.get("saved", "").startswith(STORAGE), f"a document of the storage ({got.get('saved')})")
    check(got.get("wrote") == "yes", "the app writes it")
    check("probe wrote this" in d.sh("cat /sdcard/Download/probe-saved.txt", check=False),
          "the file is in the folder with what the app wrote")

    step("choose a folder: the app gets the whole folder")
    check(ask(d, p, "tree") == FILES, "Files opens to choose a folder")
    open_folder(d, "Documents")
    open_folder(d, "probe-folder")
    d.tap(d.wait(contains="Use this folder", timeout=10))
    got = answer(p, "listed")
    check(got.get("tree", "").startswith(STORAGE + "tree/"), f"the folder ({got.get('tree')})")
    check(got.get("listed") == "1", f"the app lists its files ({got.get('listed')})")

    step("Download cannot be given: the reason and Choose again")
    ask(d, p, "tree")
    open_folder(d, "Download")
    d.tap(d.wait(contains="Use this folder", timeout=10))
    check(d.wait(text="Can't use this folder"), "the folder is refused")
    check(d.find(text="To protect your privacy, choose another folder"), "with the reason")
    d.shot("folder-refused")
    d.tap(text="Choose again")
    check(d.top()[0] == FILES, "Choose again returns to Files")
    d.back()

    step("images alone: the photo picker, whose Browse opens Files")
    target = ask(d, p, "pick", "--es type image/*")
    note(f"images open {target}")
    check(target and target != FILES and ("photopicker" in target or "media" in target),
          f"the photo picker opens ({target})")
    d.shot("photo-picker")
    d.tap(d.wait(text="Browse", timeout=15))
    time.sleep(2)
    check(d.top()[0] == FILES, "Browse opens Files")
    d.back()
    d.home()


run(main)
