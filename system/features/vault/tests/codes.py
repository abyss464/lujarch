"""2FA codes lists the accounts of the 2FA codes, then the password entries that carry a code, each
with its code and countdown; a tap copies it. An account is added from a QR code in an image, a typed
key or an otpauth link, each shown for review first, and the accounts of another file are imported.
An account is renamed, starred, advanced (counter codes) or deleted after a confirmation. The Quick
Settings tile 2FA codes opens the codes, after unlocking on a locked phone.

verifies: VLT-5
timeout: 2400
"""
import base64
import hashlib
import hmac
import struct
import subprocess
import time

from _vault import (ACCOUNTS, AEGIS_PASSWORD, OUT, SYNCED, aegis, appears, confirm_credential,
                    enter_password, keepass, no_screen_lock, pick, pin_pad, reset, screen_lock,
                    set_up_codes, set_up_passwords, vault_page)
from lujarch_test import AppProbe, check, device, note, run, step

DB, KEY, AEGIS, OTHER = ("lujarch-test-vault.kdbx", "lujarch-test-vault.keyx",
                         "lujarch-test-aegis.json", "lujarch-test-other.json")
QR = "lujarch-test-qr.png"
TILE = "org.lujarch.vault/.system.CodesTileService"


def hotp(secret, counter):
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1000000:06d}"


def totp(secret, at=None):
    return hotp(secret, int((at or time.time()) // 30))


def shown_code(d, label):
    """The code on the row whose summary is label, without spaces."""
    nodes = d.ui()
    for i, n in enumerate(nodes):
        if n.text.startswith(label):
            above = [m for m in nodes[:i] if m.text and m.text.replace(" ", "").isdigit()]
            return above[-1].text.replace(" ", "") if above else None
    return None


def long_press(d, node):
    d.sh(f"input swipe {node.x} {node.y} {node.x} {node.y} 900")
    time.sleep(1)


def clipboard(d, p):
    d.start(f"-n {p.PACKAGE}/.Probe", wait=1)
    got = p.do("clipboard").get("clipboard", "")
    d.back()
    return got


def add_tile(d):
    """Quick Settings > Edit tiles > 2FA codes, as the owner adds a tile."""
    d.home()
    d.sh("cmd statusbar collapse")
    d.sh("cmd statusbar expand-settings")
    time.sleep(3)
    d.tap(next(n for n in d.ui(windows=True) if n.desc.startswith("Edit order of Quick Settings")))
    time.sleep(3)
    for _ in range(15):
        add = next((n for n in d.ui(windows=True) if n.desc.startswith("Add 2FA codes")), None)
        if add:
            d.tap(add)
            break
        d.swipe(540, 1900, 540, 900)
        time.sleep(2)
    check(add, "the editor offers the 2FA codes tile")
    time.sleep(2)
    d.back()
    d.sh("cmd statusbar collapse")


def codes_page(d):
    vault_page(d, ("Lujarch Vault", "2FA codes"))
    check(d.wait(text="Accounts", timeout=30), "2FA codes shows")


def add(d, how):
    d.tap(appears(d, 10, desc="Add account") or d.wait(text="Add account"))
    d.tap(d.wait(text=how, timeout=10))


def main():
    d = device()
    reset(d)
    screen_lock(d)
    p = AppProbe(d)
    p.install()
    p.allow()
    d.sh(f"cmd statusbar remove-tile '{TILE}'", check=False)
    try:
        db, key = keepass(DB)
        d.sh(f"mkdir -p {SYNCED}")
        d.push(str(db), f"{SYNCED}/{DB}")
        d.sh(f"chown -R 5100:5100 {SYNCED}")
        d.push(str(key), f"/sdcard/Download/{KEY}")
        d.push(str(aegis(AEGIS)), f"/sdcard/Download/{AEGIS}")
        d.push(str(aegis(OTHER, [("Cloud", "carol", "totp", "KRUGS4ZANFZSAYJAORSXG5A", 0)])),
               f"/sdcard/Download/{OTHER}")
        link = "otpauth://totp/Mail:dave?secret=ONSWG4TFOQ======&issuer=Mail"
        subprocess.run(["qrencode", "-s", "8", "-o", str(OUT / QR),
                        "otpauth://totp/Chat:erin?secret=MFRGGZDFMZTWQ2LK&issuer=Chat"], check=True)
        d.push(str(OUT / QR), f"/sdcard/Download/{QR}")
        set_up_passwords(d, DB, KEY)
        set_up_codes(d, AEGIS)

        step("the accounts, then the password entries with a code, each with its code")
        codes_page(d)
        check(d.find(text="Passwords"), "the passwords' section follows")
        forge = shown_code(d, "Forge · alice")
        mail = shown_code(d, "Example mail · alice@example.com")
        note(f"Forge {forge}, Example mail {mail}")
        check(forge in (totp(ACCOUNTS[0][3]), totp(ACCOUNTS[0][3], time.time() - 30)),
              "an account's code is right")
        check(mail in (totp("JBSWY3DPEHPK3PXP"), totp("JBSWY3DPEHPK3PXP", time.time() - 30)),
              "a password entry's code is right")
        check(shown_code(d, "Bank · alice · counter 3") == hotp(ACCOUNTS[1][3], 3),
              "a counter account shows its counter's code")
        d.shot("codes")

        step("a tap copies the code")
        codes_page(d)
        d.tap(d.find(text="Forge · alice"))
        at = time.time()
        copied = clipboard(d, p)
        note(f"copied {copied}")
        check(copied in (totp(ACCOUNTS[0][3], at), totp(ACCOUNTS[0][3], at - 30)), "the code is copied")

        step("added from a QR code in an image, shown for review first")
        codes_page(d)
        add(d, "Scan QR code in an image")
        pick(d, "Download", QR)
        check(d.wait(text="Chat", timeout=20), "the account is shown for review")
        d.shot("review")
        d.tap(d.wait(text="Add"))
        codes_page(d)
        check(d.wait(contains="Chat · erin", timeout=20), "the account is added")

        step("added from a typed key and from an otpauth link opened elsewhere")
        add(d, "Enter key")
        fields = [n for n in d.ui() if n.cls.endswith("EditText")]
        d.tap(fields[0])
        d.type("Shop")
        d.tap(fields[1])
        d.type("frank")
        d.tap(fields[2])
        d.type("GEZDGNBVGY3TQOJQ")
        d.tap(d.wait(text="Add"))
        codes_page(d)
        check(d.wait(contains="Shop · frank", timeout=20), "the typed account is added")
        # A site's "add to authenticator" link, opened as from a browser.
        d.sh(f"am start -a android.intent.action.VIEW -d '{link}'")
        check(d.wait(text="Mail", timeout=20) and d.find(text="dave"),
              "the link opens the account filled in, for review")
        d.shot("link-review")
        d.tap(d.wait(text="Add"))
        codes_page(d)
        check(d.wait(contains="Mail · dave", timeout=20), "the linked account is added")

        step("the accounts of another file are imported")
        add(d, "Import from another file")
        pick(d, "Download", OTHER)
        enter_password(d, AEGIS_PASSWORD)
        codes_page(d)
        check(d.wait(contains="Cloud · carol", timeout=30), "its account is added")

        step("renamed, starred, advanced and deleted")
        codes_page(d)
        long_press(d, d.find(contains="Shop · frank"))
        d.tap(d.wait(text="Edit account"))
        field = [n for n in d.ui() if n.cls.endswith("EditText")][0]
        d.tap(field)
        d.sh("input keycombination KEYCODE_CTRL_LEFT KEYCODE_A")
        d.type("Shop2")
        d.tap(appears(d, 10, text="OK") or d.wait(text="Save"))
        codes_page(d)
        check(d.wait(contains="Shop2 · frank", timeout=20), "renamed")
        long_press(d, d.find(contains="Shop2 · frank"))
        d.tap(d.wait(text="Add to favorites"))
        codes_page(d)
        long_press(d, d.find(contains="Shop2 · frank"))
        check(d.wait(text="Remove from favorites"), "starred")
        d.back()
        long_press(d, d.find(contains="Bank · alice"))
        d.tap(d.wait(text="Next code"))
        codes_page(d)
        check(shown_code(d, "Bank · alice · counter 4") == hotp(ACCOUNTS[1][3], 4), "advanced")
        long_press(d, d.find(contains="Mail · dave"))
        d.tap(d.wait(text="Delete"))
        check(d.wait(text="Delete this account?"), "deleting asks first")
        d.tap(d.wait(text="Delete"))
        codes_page(d)
        time.sleep(2)
        check(not d.find(contains="Mail · dave"), "deleted")

        step("the Quick Settings tile opens the codes, after unlocking on a locked phone")
        add_tile(d)
        check(TILE in d.sh("settings get secure sysui_qs_tiles"), "the tile is in Quick Settings")
        d.home()
        d.sh("cmd statusbar expand-settings")
        time.sleep(2)
        d.swipe(950, 820, 130, 820)
        time.sleep(1)
        d.shot("tile")
        # A tap on the tile: SystemUI's click, which runs the tile's own onClick.
        d.sh(f"cmd statusbar click-tile {TILE}")
        check(d.wait(text="Accounts", timeout=20), "the tile opens the codes")
        d.key("KEYCODE_POWER")
        time.sleep(2)
        d.key("KEYCODE_WAKEUP")
        # On the lock screen: Quick Settings pulled down, the tile tapped, the PIN entered.
        d.sh("cmd statusbar expand-settings")
        time.sleep(2)
        for _ in range(3):
            tile = next((n for n in d.ui(windows=True) if n.desc == "2FA codes"), None)
            if tile:
                break
            # The editor adds a tile last, on the last page of Quick Settings.
            d.swipe(950, 820, 130, 820)
            time.sleep(1)
        check(tile, "the tile shows in Quick Settings on the lock screen")
        d.tap_xy(tile.x, tile.y, wait=2)
        d.shot("tile-locked")
        pin_pad(d)
        check(d.wait(text="Accounts", timeout=20), "after unlocking it opens the codes")
        d.shot("tile-unlocked")
    finally:
        d.sh(f"cmd statusbar remove-tile '{TILE}'", check=False)
        reset(d)
        d.sh(f"rm -rf {SYNCED}", check=False)
        no_screen_lock(d)
        d.home()


run(main)
