"""When Files cannot serve a request (turned off here), a dialog says which, with App info or OK,
and the app gets no answer. Settings > Storage opens each kind of file in the matching app:
documents and other files in Files, images and videos in the gallery, audio in Music.

verifies: FILE-2, FILE-3
timeout: 900
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step

FILES = "me.zhanghai.android.files"
MUSIC = "org.feeluown.mobile"
GALLERY = "com.android.gallery3d"


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    try:
        step("Files turned off: a dialog says so, and the app gets no answer")
        d.sh(f"pm disable-user --user 0 {FILES}")
        d.start(f"-n {p.PACKAGE}/.Probe", wait=2)
        p.clear()
        d.sh(f"am start -n {p.PACKAGE}/.Probe --activity-single-top --es do pick")
        check(d.wait(text="Files isn't available", timeout=20), "a dialog names Files")
        check(d.find(contains="It's turned off"), "and says it is turned off")
        check(d.find(text="App info") and d.find(text="OK"), "with App info and OK")
        d.shot("files-turned-off")
        d.tap(text="OK")
        time.sleep(2)
        check(p.results().get("picked") == "no answer", f"no answer ({p.results().get('picked')})")
    finally:
        d.sh(f"pm enable --user 0 {FILES}", check=False)

    step("Settings > Storage opens each kind of file in its app")
    opened = {}
    for category, want in (("Documents & other", FILES), ("Images", GALLERY), ("Audio", MUSIC)):
        d.unlock()
        d.start("-f 0x10008000 -a android.settings.INTERNAL_STORAGE_SETTINGS", wait=3)
        d.tap(d.scroll_to(text=category))
        time.sleep(3)
        opened[category] = d.top()[0]
        if category == "Documents & other":
            d.shot("storage-documents")
        check(opened[category] == want, f"{category} opens in {want} ({opened[category]})")
        d.home()
    note(f"opened: {opened}")


run(main)
