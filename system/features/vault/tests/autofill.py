"""Lujarch Vault is the preferred autofill service; user name, password and one-time code fields are
filled from the entries that match: an app by its package, a website by its domain or a parent
domain, or with Same host only by its own host. The suggestions end with Search the vault while
that is on; nothing is offered while the database is turned off or the phone is locked, and never
to Lujarch's own apps.

verifies: VLT-7
timeout: 2400
"""
import time

from _vault import (SYNCED, ShopProbe, appears, keepass, no_screen_lock, reset, screen_lock,
                    set_up_keepass, unlock_with_pin, vault_page)
from lujarch_test import check, device, note, run, step

DB, KEY = "lujarch-test-vault.kdbx", "lujarch-test-vault.keyx"


def suggestions(d, p, web=None, field="User name"):
    """The suggestions offered on the probe's sign-in form, as the user sees them when the field
    gets the focus."""
    p.sign_in(web)
    p.tap_field(field, web=bool(web))
    time.sleep(3)
    return [n for n in d.ui(windows=True) if n.text and n.package in ("android", "org.lujarch.vault",
                                                                      "com.android.inputmethod.latin")]


def offered(rows):
    return {n.text for n in rows}


def main():
    d = device()
    reset(d)
    screen_lock(d)
    d.uninstall("org.lujarch.probe")
    p = ShopProbe(d)
    p.install()
    p.allow()
    try:
        db, key = keepass(DB)
        d.sh(f"mkdir -p {SYNCED}")
        d.push(str(db), f"{SYNCED}/{DB}")
        d.sh(f"chown -R 5100:5100 {SYNCED}")
        d.push(str(key), f"/sdcard/Download/{KEY}")

        step("Lujarch Vault is the preferred service")
        vault_page(d, ("Preferred service",))
        check(d.wait(text="Lujarch Vault", timeout=15), "Preferred service: Lujarch Vault")
        d.shot("preferred-service")
        service = d.sh("settings get secure autofill_service").strip()
        check(service.startswith("org.lujarch.vault/"), f"the autofill service ({service})")

        step("nothing while the database is not set up")
        check(not offered(suggestions(d, p)) & {"Probe app", "Search the vault"}, "nothing offered")
        set_up_keepass(d, DB, KEY)

        step("an app by its package: the entry for it, then Search the vault")
        rows = suggestions(d, p)
        names = [n.text for n in rows]
        note(f"offered in the probe: {names}")
        check("Probe app" in names, "the probe's entry is offered")
        check(names and "Search the vault" in names and names.index("Search the vault") > names.index("Probe app"),
              "Search the vault comes last")
        d.shot("suggestions-app")
        d.tap(next(n for n in rows if n.text == "Probe app"))
        time.sleep(2)
        check(d.find(text="probe-user"), "the user name is filled")
        d.tap(d.find(text="Sign in"))
        check(p.results().get("login", "").startswith("probe-user/probe-pass-2"),
              "and the password with it")

        step("a website by its domain or a parent domain")
        rows = suggestions(d, p, web="https://mail.example.com/login")
        check("Example mail" in offered(rows), "its own host's entry")
        rows = suggestions(d, p, web="https://login.shop.example.org/")
        check("Example shop" in offered(rows), "a parent domain's entry")
        d.tap(next(n for n in rows if n.text == "Example shop"))
        time.sleep(2)
        d.shot("filled-web")

        step("a one-time code field is filled from the entry's code")
        rows = suggestions(d, p, web="https://mail.example.com/login", field="One-time code")
        d.tap(next(n for n in rows if n.text == "Example mail"))
        time.sleep(2)
        check(any(n.text.isdigit() and len(n.text) == 6 for n in d.ui()), "the code is filled")

        step("Same host only: a parent domain's entry is no longer offered")
        vault_page(d, ("Lujarch Vault", "Settings"))
        check(not d.switch_of("Same host only").checked, "Same host only is off by default")
        d.tap(d.switch_of("Same host only"))
        rows = suggestions(d, p, web="https://login.shop.example.org/")
        check("Example shop" not in offered(rows), "not on another host")
        rows = suggestions(d, p, web="https://mail.example.com/")
        check("Example mail" in offered(rows), "still on its own host")

        step("Search the vault off")
        vault_page(d, ("Lujarch Vault", "Settings"))
        d.tap(d.switch_of("Search the vault suggestion"))
        check("Search the vault" not in offered(suggestions(d, p)), "no Search the vault")

        step("nothing while the database is off")
        vault_page(d, ("Lujarch Vault", "Sources"))
        d.tap(d.switch_of("Use the KeePass database"))
        check("Probe app" not in offered(suggestions(d, p)), "nothing offered")
        vault_page(d, ("Lujarch Vault", "Sources"))
        d.tap(d.switch_of("Use the KeePass database"))

        step("never to Lujarch's own apps: the vault's own search field")
        vault_page(d, ("Lujarch Vault", "Passwords"))
        d.tap(d.wait(desc="Search", timeout=20))
        time.sleep(3)
        check(not offered(d.ui(windows=True)) & {"Probe app", "Example mail", "Search the vault"},
              "nothing offered in Lujarch's pages")
        d.shot("own-app")
        d.back()

        step("nothing while the phone is locked, in a form shown over the lock screen")
        d.key("KEYCODE_SLEEP")
        time.sleep(2)
        d.key("KEYCODE_WAKEUP")
        p.sign_in(over_lock=True)
        check(appears(d, 15, text="User name"), "the form shows over the lock screen")
        p.tap_field("User name")
        time.sleep(3)
        check(not offered(d.ui(windows=True)) & {"Probe app", "Search the vault"}, "nothing offered")
        d.shot("locked-nothing")
        d.sh(f"am force-stop {p.PACKAGE}")
        unlock_with_pin(d)
    finally:
        reset(d)
        d.sh(f"rm -rf {SYNCED}", check=False)
        no_screen_lock(d)
        d.home()


run(main)
