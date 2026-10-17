"""Passwords lists the file's groups and entries and searches title, user name, URL, tags and
group; an entry copies its user name, password and URL and shows its notes and other fields. A
password is revealed, and with Verify to copy copied, only after the screen lock (Verify to reveal
on and Verify to copy off by default); what a page showed is hidden again when the page stops.

verifies: VLT-4
timeout: 1800
"""
import time

from _vault import (SYNCED, confirm_credential, keepass,
                    no_screen_lock, reset, screen_lock, set_up_passwords, vault_page)
from lujarch_test import AppProbe, check, device, note, run, step

DB, KEY = "lujarch-test-vault.kdbx", "lujarch-test-vault.keyx"


def clipboard(d, p):
    d.start(f"-n {p.PACKAGE}/.Probe", wait=1)
    got = p.do("clipboard").get("clipboard", "")
    d.back()
    return got


def search(d, words):
    d.tap(d.wait(desc="Search", timeout=10))
    d.type(words)
    time.sleep(2)
    return {n.text for n in d.ui() if n.text}


def main():
    d = device()
    reset(d)
    screen_lock(d)
    p = AppProbe(d)
    p.install()
    p.allow()
    try:
        db, key = keepass(DB)
        d.sh(f"mkdir -p {SYNCED}")
        d.push(str(db), f"{SYNCED}/{DB}")
        d.sh(f"chown -R 5100:5100 {SYNCED}")
        d.push(str(key), f"/sdcard/Download/{KEY}")
        set_up_passwords(d, DB, KEY)

        step("Verify to reveal on, Verify to copy off by default")
        vault_page(d, ("Lujarch Vault", "Settings"))
        check(d.switch_of("Verify to reveal").checked, "Verify to reveal is on")
        check(not d.switch_of("Verify to copy").checked, "Verify to copy is off")

        step("the groups and entries")
        vault_page(d, ("Lujarch Vault", "Passwords"))
        for name in ("Internet", "Apps"):
            check(d.wait(text=name, timeout=30), f"the group {name}")
        d.shot("passwords")
        d.tap(d.find(text="Internet"))
        for name in ("Example mail", "Example shop"):
            check(d.wait(text=name, timeout=10), f"the entry {name}")
        d.back()

        step("search: title, user name, URL, tags and group")
        for words, found in (("shop", "Example shop"), ("probe-user", "Probe app"),
                             ("mail.example.com", "Example mail"), ("work", "Example mail"),
                             ("Apps", "Probe app")):
            vault_page(d, ("Lujarch Vault", "Passwords"))
            texts = search(d, words)
            check(found in texts, f"“{words}” finds {found}")

        step("an entry: user name, password and URL copied; notes and other fields shown")
        vault_page(d, ("Lujarch Vault", "Passwords"))
        d.tap(d.wait(text="Internet", timeout=30))
        d.tap(d.wait(text="Example mail"))
        for field in ("User name", "Password", "URL", "Notes", "Account number"):
            check(d.scroll_to(text=field), f"it shows {field}")
        check(d.find(text="the mail account"), "the notes are shown")
        check(d.find(text="12345"), "the other fields are shown")
        check(not d.find(text="mail-pass-1"), "the password is hidden")
        d.shot("entry")
        d.tap(d.scroll_to(desc="User name") or d.scroll_to(text="User name"))
        check(clipboard(d, p) == "alice@example.com", "the user name is copied")
        d.tap(d.scroll_to(desc="Password") or d.scroll_to(text="Password"))
        check(clipboard(d, p) == "mail-pass-1", "the password is copied without verification")

        step("revealed only after the screen lock")
        d.tap(d.scroll_to(desc="Show"))
        confirm_credential(d)
        check(d.wait(text="mail-pass-1", timeout=10), "the password shows after the PIN")
        d.shot("revealed")

        step("hidden again when the page stops")
        d.home()
        time.sleep(2)
        # Back to the same page, as from the recent apps: the vault's task comes to the front.
        d.sh("am start -f 0x10000000 -n org.lujarch.vault/.ui.VaultActivity")
        check(d.wait(text="Account number", timeout=15), "the entry's page shows again")
        check(not d.find(text="mail-pass-1"), "the password is hidden again")

        step("Verify to copy: copying asks for the screen lock")
        vault_page(d, ("Lujarch Vault", "Settings"))
        d.tap(d.switch_of("Verify to copy"))
        vault_page(d, ("Lujarch Vault", "Passwords"))
        d.tap(d.wait(text="Internet", timeout=30))
        d.tap(d.wait(text="Example shop"))
        d.tap(d.scroll_to(desc="Password") or d.scroll_to(text="Password"))
        confirm_credential(d)
        check(clipboard(d, p) == "shop-pass-3", "copied after the PIN")
    finally:
        reset(d)
        d.sh(f"rm -rf {SYNCED}", check=False)
        no_screen_lock(d)
        d.home()


run(main)
