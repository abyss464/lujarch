"""The vault opens only while the phone is unlocked: its keys are wrapped by a Keystore key that
works only then, a page left open while the phone locks opens again once it is unlocked, nothing
decrypted is kept once no vault page is visible, and no vault page can be captured in a screenshot.

verifies: VLT-1
timeout: 1500
"""
import subprocess
import time

from _vault import (OUT, PACKAGE, SYNCED, keepass, keyguard_showing, no_screen_lock, reset,
                    screen_lock, set_up_passwords, summary_of, unlock_with_pin, vault_page)
from lujarch_test import ADB, SERIAL, check, device, note, run, step

DB, KEY = "lujarch-test-vault.kdbx", "lujarch-test-vault.keyx"
SECRETS = ("mail-pass-1", "probe-pass-2", "shop-pass-3")


def held_secrets(d):
    """The passwords of the file found in the vault's memory, after a collection."""
    pid = d.sh(f"pidof {PACKAGE}", check=False).strip()
    note(f"the vault's process: {pid or 'ended'}")
    if not pid:
        return []
    d.sh(f"am dumpheap -g {pid} /data/local/tmp/vault.hprof", check=False)
    time.sleep(5)
    local = OUT / "vault.hprof"
    d.pull("/data/local/tmp/vault.hprof", str(local))
    d.sh("rm -f /data/local/tmp/vault.hprof", check=False)
    data = local.read_bytes()
    local.unlink()
    return [s for s in SECRETS if s.encode() in data or s.encode("utf-16-le") in data]


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
        set_up_passwords(d, DB, KEY)

        step("the keys are wrapped by a Keystore key that works only while unlocked")
        vault_page(d, ("Lujarch Vault", "Settings", "About the vault"))
        d.scroll_to(text="Wrapping key")
        note(f"Wrapping key: {summary_of(d, 'Wrapping key')}")
        check(summary_of(d, "Wrapping key") not in ("", "No key (nothing set up)"), "a Keystore key wraps them")

        step("no vault page can be captured")
        vault_page(d, ("Lujarch Vault", "Passwords"))
        d.wait(text="Internet", timeout=30)
        windows = d.sh("dumpsys window windows | grep -E 'Window #|fl=' | grep -A1 org.lujarch.vault",
                       check=False)
        check("SECURE" in windows, "the page's window is secure")
        shot = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap"], capture_output=True,
                              timeout=30).stdout[16:]
        lit = sum(1 for i in range(0, len(shot), 4096) if any(shot[i:i + 3]))
        note(f"lit samples in a screenshot of the page: {lit} of {len(shot) // 4096}")
        check(lit < len(shot) // 4096 // 10, "a screenshot of it shows nothing of the page")

        step("a page open while the phone locks opens again once it is unlocked")
        d.key("KEYCODE_SLEEP")
        time.sleep(3)
        check(keyguard_showing(d), "the phone is locked")
        unlock_with_pin(d)
        check(d.wait(text="Internet", timeout=30), "the page shows the passwords again")

        step("nothing decrypted is kept once no vault page is visible")
        vault_page(d, ("Lujarch Vault", "Passwords"))
        d.tap(d.wait(text="Internet", timeout=30))
        d.tap(d.wait(text="Example mail"))
        d.home()
        time.sleep(5)
        held = held_secrets(d)
        note(f"passwords in the vault's memory after leaving it: {held}")
        check(not held, "none")
    finally:
        reset(d)
        d.sh(f"rm -rf {SYNCED}", check=False)
        no_screen_lock(d)
        d.home()


run(main)
