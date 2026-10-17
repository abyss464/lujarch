"""Everything the vault copies is marked sensitive, so the clipboard does not show it, and is cleared
the chosen time after the last copy: Clear the clipboard offers Never, After 15 seconds, After 30
seconds (the default), After 1 minute, After 2 minutes and After 5 minutes.

verifies: VLT-6
timeout: 1200
"""
import time

from _vault import (SYNCED, keepass, no_screen_lock, reset, screen_lock, set_up_passwords,
                    summary_of, vault_page)
from lujarch_test import AppProbe, check, device, note, run, step

DB, KEY = "lujarch-test-vault.kdbx", "lujarch-test-vault.keyx"
CHOICES = ("Never", "After 15 seconds", "After 30 seconds", "After 1 minute", "After 2 minutes",
           "After 5 minutes")


def copy_user_name(d):
    vault_page(d, ("Lujarch Vault", "Passwords"))
    d.tap(d.wait(text="Internet", timeout=30))
    d.tap(d.wait(text="Example mail"))
    d.tap(d.scroll_to(desc="User name") or d.scroll_to(text="User name"))
    return time.monotonic()


def clip(d, p):
    """What the clipboard holds and whether it is marked sensitive, read by an app in front."""
    d.start(f"-n {p.PACKAGE}/.Probe", wait=1)
    text = p.do("clipboard").get("clipboard", "")
    sensitive = p.do("clipsensitive").get("clipsensitive", "")
    d.home()
    return text, sensitive


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

        step("Clear the clipboard: After 30 seconds by default, with its choices")
        vault_page(d, ("Lujarch Vault", "Settings"))
        d.scroll_to(text="Clear the clipboard")
        check(summary_of(d, "Clear the clipboard").startswith("After 30 seconds"), "After 30 seconds")
        d.tap(d.find(text="Clear the clipboard"))
        for choice in CHOICES:
            check(d.wait(text=choice, timeout=5), f"offers {choice}")
        d.shot("clear-clipboard")
        d.tap(d.find(text="After 15 seconds"))

        step("a copied value is marked sensitive and cleared after 15 seconds")
        copied = copy_user_name(d)
        text, sensitive = clip(d, p)
        note(f"clipboard: {text!r}, {sensitive}")
        check(text == "alice@example.com", "the value is on the clipboard")
        check(sensitive == "sensitive", "marked sensitive")
        time.sleep(max(0, copied + 20 - time.monotonic()))
        check(clip(d, p)[0] == "", "the clipboard is empty after 15 seconds")

        step("Never: the value stays")
        vault_page(d, ("Lujarch Vault", "Settings"))
        d.tap(d.scroll_to(text="Clear the clipboard"))
        d.tap(d.wait(text="Never"))
        copy_user_name(d)
        time.sleep(40)
        check(clip(d, p)[0] == "alice@example.com", "the value is still there after 40 seconds")
    finally:
        reset(d)
        d.sh(f"rm -rf {SYNCED}", check=False)
        no_screen_lock(d)
        d.home()


run(main)
