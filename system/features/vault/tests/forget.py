"""Forget keys deletes the stored keys at once, while the chosen files stay, so setting up again
needs only the passwords and the key file; Forget keys automatically (Never by default, After the
phone restarts, After 1, 7 or 30 days without use) deletes them by itself.

verifies: VLT-3
timeout: 1800
"""
import time

from _vault import (KEEPASS_PASSWORD, SYNCED, appears, enter_password, keepass, no_screen_lock,
                    pick, reset, screen_lock, seek, set_up_keepass, summary_of, unlock_with_pin,
                    vault_page)
from lujarch_test import check, device, note, restart, run, step

DB, KEY = "lujarch-test-vault.kdbx", "lujarch-test-vault.keyx"
CHOICES = ("Never", "After the phone restarts", "After 1 day without use", "After 7 days without use",
           "After 30 days without use")


def opens(d):
    vault_page(d, ("Lujarch Vault", "Passwords"))
    return bool(appears(d, 30, text="Internet"))


def set_up_again(d):
    """Only the password and the key file: the database stays chosen."""
    vault_page(d, ("Lujarch Vault", "Sources"))
    check(DB in summary_of(d, "Database"), "the chosen database stays")
    d.tap(d.scroll_to(text="Key file"))
    d.tap(d.wait(text="Choose a file…", timeout=10))
    pick(d, "Download", KEY)
    d.tap(seek(d, text="Set up KeePass again") or d.scroll_to(text="Set up KeePass"))
    enter_password(d, KEEPASS_PASSWORD)
    check(d.wait(text="Set up KeePass again", timeout=90), "set up again")


def policy(d, choice):
    vault_page(d, ("Lujarch Vault", "Settings"))
    d.tap(d.scroll_to(text="Forget keys automatically"))
    d.tap(d.wait(text=choice))
    time.sleep(1)


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
        set_up_keepass(d, DB, KEY)

        step("Forget keys automatically: Never by default, with its choices")
        vault_page(d, ("Lujarch Vault", "Settings"))
        d.scroll_to(text="Forget keys automatically")
        check(summary_of(d, "Forget keys automatically").startswith("Never"), "Never")
        d.tap(d.find(text="Forget keys automatically"))
        for choice in CHOICES:
            check(d.wait(text=choice, timeout=5), f"offers {choice}")
        d.back()

        step("Forget keys deletes them at once; the chosen files stay")
        vault_page(d, ("Lujarch Vault", "Settings"))
        d.tap(d.scroll_to(text="Forget keys"))
        d.tap(d.wait(text="Forget"))
        time.sleep(2)
        check(not opens(d), "the vault no longer opens")
        d.shot("forgotten")
        set_up_again(d)
        check(opens(d), "it opens again")

        step("After the phone restarts")
        policy(d, "After the phone restarts")
        restart(d)
        unlock_with_pin(d)
        check(not opens(d), "the keys are gone after the restart")
        check(d.find(contains="forgotten") or d.find(text="Set up again under Sources")
              or d.find(contains="Sources"), "the page says why")
        set_up_again(d)

        step("After 1 day without use")
        policy(d, "After 1 day without use")
        check(opens(d), "used today, it opens")
        d.home()
        now = int(d.sh("date +%s").strip())
        d.sh(f"cmd alarm set-time {(now + 2 * 86400) * 1000}")
        time.sleep(3)
        try:
            check(not opens(d), "two days later the keys are gone")
        finally:
            d.sh(f"cmd alarm set-time {(now + 30) * 1000}")
    finally:
        reset(d)
        d.sh(f"rm -rf {SYNCED}", check=False)
        no_screen_lock(d)
        d.home()


run(main)
