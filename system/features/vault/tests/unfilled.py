"""Apps and sites not filled: an app is left unfilled by its package, a site by its host with the
scope chosen for it: the host alone, its www form included, or the whole site with every subdomain;
a site without a scope of its own follows the default scope, This host only by default.

verifies: VLT-8
timeout: 2400
"""
import time

from _vault import (SYNCED, ShopProbe, appears, keepass, no_screen_lock, reset, screen_lock,
                    set_up_passwords, summary_of, vault_page)
from lujarch_test import check, device, note, run, step

DB, KEY = "lujarch-test-vault.kdbx", "lujarch-test-vault.keyx"


def offered(d, p, web=None):
    p.sign_in(web)
    p.tap_field("User name", web=bool(web))
    time.sleep(3)
    return {n.text for n in d.ui(windows=True) if n.text}


def page(d):
    vault_page(d, ("Lujarch Vault", "Settings", "Apps and sites not filled"))
    check(d.wait(text="Add a site", timeout=15), "Apps and sites not filled shows")


def add_site(d, host):
    page(d)
    d.tap(d.scroll_to(text="Add a site"))
    field = next(n for n in d.ui() if n.cls.endswith("EditText"))
    d.tap(field)
    d.type(host)
    d.tap(d.wait(text="Add"))
    check(d.wait(text=host, timeout=10), f"{host} is listed")


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
        set_up_passwords(d, DB, KEY)

        step("none listed, the default scope This host only")
        page(d)
        check(summary_of(d, "Default scope for sites").startswith("A site covers that host"),
              "This host only: a site covers that host, with or without www.")
        d.shot("unfilled-empty")

        step("an app left unfilled by its package")
        check("Probe app" in offered(d, p), "the probe is filled before")
        page(d)
        d.tap(d.scroll_to(text="Add an app"))
        d.tap(d.scroll_to(text="Probe"))
        check(d.wait(text="Probe", timeout=10), "the app is listed")
        check("Probe app" not in offered(d, p), "nothing is offered in it")
        # Its sites are shown in the same app: it is filled again before them.
        page(d)
        d.tap(d.scroll_to(text="Probe"))
        d.tap(d.wait(text="Remove"))
        check("Probe app" in offered(d, p), "removed from the list, the app is filled again")

        step("a site by its host: the host and its www form, not other subdomains")
        add_site(d, "example.com")
        d.shot("unfilled-site")
        check("Example mail" in offered(d, p, "https://mail.example.com/"), "another subdomain is filled")
        offered_www = offered(d, p, "https://www.example.com/")
        check("Example mail" not in offered_www and "Search the vault" not in offered_www,
              "its www form is not filled")

        step("the whole site with every subdomain, by the site's own scope")
        page(d)
        d.tap(d.scroll_to(text="example.com"))
        d.tap(d.wait(text="Whole site"))
        check(not {"Example mail", "Search the vault"} & offered(d, p, "https://mail.example.com/"),
              "no subdomain is filled")

        step("a site without a scope of its own follows the default")
        add_site(d, "example.org")
        page(d)
        d.tap(d.scroll_to(text="Default scope for sites"))
        d.tap(d.wait(text="Whole site"))
        check(not {"Example shop", "Search the vault"} & offered(d, p, "https://shop.example.org/login"),
              "with the default Whole site, its subdomains are not filled")
    finally:
        reset(d)
        d.sh(f"rm -rf {SYNCED}", check=False)
        no_screen_lock(d)
        d.home()


run(main)
