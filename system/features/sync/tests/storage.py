"""The folders live in the phone's sync storage, a directory of the system that no app reaches; only
the sync service and Lujarch's own services read it. When the storage's permissions are wrong, the
Sync page says so.

verifies: SYN-8
timeout: 900
"""
import time

from _sync import FOLDERS, HOME, open_sync, reset, summary_of, turn_on
from lujarch_test import AppProbe, check, device, note, run, step


def main():
    d = device()
    reset(d)
    p = AppProbe(d)
    try:
        turn_on(d)
        d.sh(f"mkdir -p {FOLDERS}/Docs && echo kept > {FOLDERS}/Docs/a.txt && "
             f"chown -R 5100:5100 {FOLDERS}/Docs")

        step("a directory of the system, owned by the sync service")
        for path, label in ((HOME, "lujarch_sync_data_file"), (FOLDERS, "lujarch_sync_folder_file")):
            mode = d.sh(f"stat -c '%a %u:%g' {path}").strip()
            context = d.sh(f"ls -Zd {path}").split()[0]
            note(f"{path}: {mode} {context}")
            check(mode == "750 5100:5100", f"{path} is the sync service's alone")
            check(label in context, f"{path} has its own SELinux type")

        step("no app reaches it")
        p.install()
        p.allow()
        for path in (HOME, FOLDERS, f"{FOLDERS}/Docs"):
            got = p.do("list", f"--es path {path}").get("list", "")
            note(f"an app lists {path}: {got}")
            check(got and not got.startswith("listed"), f"an app cannot read {path}")

        step("wrong permissions: the Sync page says so")
        open_sync(d)
        check(not d.find(text="Sync storage"), "no warning while the permissions are right")
        d.sh(f"chmod 0755 {FOLDERS}")
        open_sync(d)
        row = d.wait(text="Sync storage", timeout=60)
        check(row and summary_of(d, "Sync storage").startswith("Permissions are wrong"),
              "the page says the permissions are wrong")
        d.shot("storage-wrong")
        d.sh(f"chmod 0750 {FOLDERS}")
        d.back()
        open_sync(d)
        deadline = time.monotonic() + 60
        while d.find(text="Sync storage") and time.monotonic() < deadline:
            time.sleep(3)
            open_sync(d)
        check(not d.find(text="Sync storage"), "the warning goes once they are right again")
    finally:
        d.sh(f"chmod 0750 {FOLDERS}", check=False)
        reset(d)
        d.home()


run(main)
