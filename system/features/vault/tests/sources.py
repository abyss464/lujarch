"""Passwords come from a file in a synced folder, listed with its folder, size and time, or from any
file chosen in the document picker; a KDBX database is set up once with its password and its key
file, chosen in the document picker and never kept on the phone. 2FA codes come from a file and are
set up with its password. A source turned off is not opened and its keys are kept. A file the vault
can no longer read says so, with Choose again.

verifies: VLT-2
timeout: 1800
"""
import hashlib
import time

from _vault import (PACKAGE, SYNCED, aegis, enter_password, keepass, no_screen_lock, pick, reset,
                    screen_lock, set_up_codes, set_up_passwords, summary_of, vault_page)
from lujarch_test import check, device, note, run, step

DB, KEY, AEGIS = "lujarch-test-vault.kdbx", "lujarch-test-vault.keyx", "lujarch-test-aegis.json"


def main():
    d = device()
    reset(d)
    screen_lock(d)
    try:
        db, key = keepass(DB)
        d.sh(f"mkdir -p {SYNCED}")
        d.push(str(db), f"{SYNCED}/{DB}")
        d.sh(f"chown -R 5100:5100 {SYNCED}")
        d.push(str(key), f"/sdcard/Download/{KEY}")
        d.push(str(aegis(AEGIS)), f"/sdcard/Download/{AEGIS}")

        step("the password file from a synced folder, listed with its folder, size and time")
        vault_page(d, ("Lujarch Vault", "Sources"), through_settings=True)
        check(summary_of(d, "File").startswith("None chosen"), "nothing is chosen at first")
        d.tap(d.scroll_to(text="File"))
        listed = d.wait(contains=DB, timeout=30)
        check(listed, "the synced folder's file is listed")
        note(f"listed: {listed.text}")
        name, _, where = listed.text.partition("\n")
        parts = where.split(" · ")
        check(name == DB and len(parts) == 3 and parts[0] == "keepass", "with its folder, size and time")
        d.shot("synced-databases")
        d.back()

        step("set up once with its password and its key file from the document picker")
        set_up_passwords(d, DB, KEY, synced=True)
        summary = summary_of(d, "File")
        note(f"File: {summary}")
        check("synced folder keepass" in summary, "the file is the synced folder's")
        digest = hashlib.sha256(key.read_bytes()).hexdigest()
        kept = d.sh(f"find /data/user/0/{PACKAGE} /data/user_de/0/{PACKAGE} -type f "
                    f"-exec sha256sum {{}} + 2>/dev/null", check=False)
        check(digest not in kept, "the key file is not kept on the phone")
        check(d.switch_of("Use passwords").checked, "the passwords are on once set up")
        d.shot("keepass-set-up")

        step("the 2FA codes from a file, set up with its password")
        set_up_codes(d, AEGIS)
        check(d.switch_of("Use 2FA codes").checked, "the 2FA codes are on once set up")

        step("a source turned off is not used, and its keys are kept")
        d.tap(d.switch_of("Use passwords"))
        time.sleep(2)
        vault_page(d, ("Lujarch Vault", "Passwords"))
        check(not d.find(text="Example mail"), "no passwords while it is off")
        vault_page(d, ("Lujarch Vault", "Sources"))
        d.tap(d.switch_of("Use passwords"))
        vault_page(d, ("Lujarch Vault", "Passwords"))
        check(d.wait(text="Internet", timeout=30), "on again, it opens without a password")

        step("or any file chosen in the document picker")
        d.push(str(db), f"/sdcard/Download/lujarch-test-copy.kdbx")
        vault_page(d, ("Lujarch Vault", "Sources"))
        d.tap(d.scroll_to(text="File"))
        d.tap(d.wait(text="Choose from another location…", timeout=20))
        pick(d, "Download", "lujarch-test-copy.kdbx")
        time.sleep(5)
        summary = summary_of(d, "File")
        note(f"File: {summary}")
        check(summary.startswith("lujarch-test-copy.kdbx"), "the chosen file is the passwords' file")

        step("a file it can no longer read says so, with Choose again")
        d.sh("rm -f /sdcard/Download/lujarch-test-copy.kdbx")
        # Left, the vault keeps nothing open; it reads the file again when a page next shows.
        d.home()
        time.sleep(6)
        vault_page(d, ("Lujarch Vault",))
        check(d.wait(text="The chosen file no longer exists. Choose it again under Sources.",
                     timeout=30), "the vault says the file is gone")
        check(d.find(text="Choose again"), "and offers Choose again")
        d.shot("database-gone")
        vault_page(d, ("Lujarch Vault", "Sources"))
        check("no longer exists" in summary_of(d, "File"), "Sources says the file is gone")
    finally:
        reset(d)
        d.sh(f"rm -rf {SYNCED}", check=False)
        no_screen_lock(d)
        d.home()


run(main)
