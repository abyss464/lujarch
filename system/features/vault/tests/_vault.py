"""The vault tests' helpers: a KDBX database with a key file and an encrypted Aegis export, both made
up for the tests in the test's output folder, and the steps on the vault's pages that several tests
share.

Every password, key file and 2FA secret here is made up for the tests.
"""
import base64
import json
import os
import secrets
import subprocess
import time
import uuid
from pathlib import Path
from xml.sax.saxutils import escape

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

from lujarch_test import AppProbe, Failure, check, install_probe, step

OUT = Path(os.environ.get("LUJARCH_TEST_OUT", "/tmp"))
PACKAGE = "org.lujarch.vault"
FILES = "me.zhanghai.android.files"
KEEPASS_PASSWORD = "made-up-keepass-password"
AEGIS_PASSWORD = "made-up-aegis-password"
PIN = "1357"
# The probe app installed as another maker's app: the vault never fills Lujarch's own apps.
SHOP = "com.example.shop"
# The fields of the probe's sign-in form, in order.
FIELDS = ("User name", "Password", "One-time code")
# The synced folder the vault looks in first, in the sync storage.
SYNCED = "/data/lujarch/sync/folders/keepass"

# The database's entries: title, user name, password, URL, tags, notes, group, and a TOTP secret.
ENTRIES = [
    ("Example mail", "alice@example.com", "mail-pass-1", "https://mail.example.com", "mail;work",
     "the mail account", "Internet", "JBSWY3DPEHPK3PXP"),
    ("Probe app", "probe-user", "probe-pass-2", f"androidapp://{SHOP}", "", "", "Apps", None),
    ("Example shop", "bob", "shop-pass-3", "https://shop.example.org/login", "shopping", "", "Internet",
     None),
]
# The Aegis vault's accounts: issuer, name, type, secret, counter.
ACCOUNTS = [
    ("Forge", "alice", "totp", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 0),
    ("Bank", "alice", "hotp", "MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U", 3),
]


def keepass(name="vault.kdbx", entries=ENTRIES, password=KEEPASS_PASSWORD):
    """A KDBX database with a key file, as KeePassXC writes it: (database, key file)."""
    folder = OUT / "keepass"
    folder.mkdir(parents=True, exist_ok=True)
    db, key = folder / name, folder / (Path(name).stem + ".keyx")
    if db.exists():
        db.unlink()
    key.write_bytes(secrets.token_bytes(64))
    groups = {}
    for title, user, pw, url, tags, notes, group, otp in entries:
        fields = [("Title", title), ("UserName", user), ("Password", pw), ("URL", url),
                  ("Notes", notes)]
        if otp:
            fields.append(("otp", f"otpauth://totp/{title}:{user}?secret={otp}&issuer={title}"))
        fields.append(("Account number", "12345"))
        # KeePassXC keeps the password and the code's secret protected.
        strings = "".join(
            f"<String><Key>{escape(k)}</Key><Value"
            f"{' ProtectInMemory=\"True\"' if k in ('Password', 'otp') else ''}>{escape(v)}</Value></String>"
            for k, v in fields)
        groups.setdefault(group, []).append(
            f"<Entry><UUID>{base64.b64encode(uuid.uuid4().bytes).decode()}</UUID>"
            f"<Tags>{escape(tags)}</Tags>{strings}</Entry>")
    xml = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><KeePassFile><Root><Group><Name>Vault</Name>"
           + "".join(f"<Group><Name>{escape(g)}</Name>{''.join(es)}</Group>" for g, es in groups.items())
           + "</Group></Root></KeePassFile>")
    source = folder / (Path(name).stem + ".xml")
    source.write_text(xml)
    subprocess.run(["keepassxc-cli", "import", "-q", "-p", "--set-key-file", str(key), str(source),
                    str(db)], input=f"{password}\n{password}\n", text=True, check=True,
                   capture_output=True, timeout=120)
    source.unlink()
    return db, key


def aegis(name="aegis.json", accounts=ACCOUNTS, password=AEGIS_PASSWORD):
    """An encrypted Aegis export with the given accounts."""
    entries = []
    for issuer, account, kind, secret, counter in accounts:
        info = {"secret": secret, "algo": "SHA1", "digits": 6}
        if kind == "totp":
            info["period"] = 30
        else:
            info["counter"] = counter
        entries.append({"type": kind, "uuid": str(uuid.uuid4()), "name": account, "issuer": issuer,
                        "note": "", "favorite": False, "icon": None, "info": info})
    plain = json.dumps({"version": 3, "entries": entries, "groups": []}).encode()
    master = secrets.token_bytes(32)
    salt = secrets.token_bytes(32)
    derived = Scrypt(salt=salt, length=32, n=32768, r=8, p=1).derive(password.encode())
    key_nonce, db_nonce = secrets.token_bytes(12), secrets.token_bytes(12)
    sealed_key = AESGCM(derived).encrypt(key_nonce, master, None)
    sealed_db = AESGCM(master).encrypt(db_nonce, plain, None)
    vault = {
        "version": 1,
        "header": {
            "slots": [{"type": 1, "uuid": str(uuid.uuid4()), "key": sealed_key[:-16].hex(),
                       "key_params": {"nonce": key_nonce.hex(), "tag": sealed_key[-16:].hex()},
                       "n": 32768, "r": 8, "p": 1, "salt": salt.hex(), "repaired": True,
                       "is_backup": False}],
            "params": {"nonce": db_nonce.hex(), "tag": sealed_db[-16:].hex()},
        },
        "db": base64.b64encode(sealed_db[:-16]).decode(),
    }
    path = OUT / name
    path.write_text(json.dumps(vault))
    return path


def screen_lock(d):
    """A PIN, as an owner who uses the vault has one, and the phone unlocked with it once: the
    Keystore releases keys bound to the unlocked phone only after an unlock with the PIN."""
    d.sh(f"locksettings set-pin {PIN}", check=False)
    d.key("KEYCODE_SLEEP")
    time.sleep(2)
    unlock_with_pin(d)


def no_screen_lock(d):
    """The PIN removed, the lock screen answered first when it asks for it."""
    if keyguard_showing(d):
        unlock_with_pin(d)
    d.sh(f"locksettings clear --old {PIN}", check=False)


def confirm_credential(d):
    """Answers the phone's verification with the PIN."""
    check(d.wait(text="Confirm with your fingerprint or screen lock", timeout=15),
          "the phone asks for its screen lock")
    d.type(PIN)
    d.key("KEYCODE_ENTER")
    time.sleep(2)


def vault_page(d, path=("Lujarch Vault",), through_settings=False):
    """Settings > Passwords & passkeys > Lujarch Vault, and on through path. The vault's home
    opens fresh, as from its row in Settings, unless the walk from Settings itself is checked."""
    d.unlock()
    if path[0] == "Lujarch Vault" and not through_settings:
        d.start(f"-f 0x10008000 -n {PACKAGE}/.ui.VaultActivity", wait=2)
        path = path[1:]
    else:
        d.start("-f 0x10008000 -a android.settings.SETTINGS", wait=2)
        d.tap(d.scroll_to(text="Passwords & passkeys"))
    for seg in path:
        d.tap(d.scroll_to(text=seg))
        time.sleep(1.5)


def pick(d, *path):
    """A file chosen in the document picker, through its folders."""
    deadline = time.monotonic() + 20
    while d.top()[0] != FILES and time.monotonic() < deadline:
        time.sleep(0.5)
    check(d.top()[0] == FILES, "the document picker opens")
    for seg in path:
        d.tap(d.scroll_to(text=seg))
        time.sleep(1)


def enter_password(d, password):
    field = next((n for n in d.ui() if n.cls.endswith("EditText")), None)
    check(field and field.password, "the password is typed into a masked field")
    d.tap(field)
    d.type(password)
    d.tap(d.wait(text="OK", timeout=10))


def set_up_passwords(d, db_name, key_name, synced=True):
    """Sources: the password file (from the synced folder or the document picker), its key file
    from the document picker, and its password."""
    vault_page(d, ("Lujarch Vault", "Sources"))
    d.tap(d.scroll_to(text="File"))
    if synced:
        d.tap(d.wait(contains=db_name, timeout=20))
    else:
        d.tap(d.wait(text="Choose from another location…", timeout=20))
        pick(d, "Download", db_name)
    d.tap(d.scroll_to(text="Key file"))
    d.tap(d.wait(text="Choose a file…", timeout=10))
    pick(d, "Download", key_name)
    d.tap(d.scroll_to(text="Set up passwords"))
    enter_password(d, KEEPASS_PASSWORD)
    check(d.wait(text="Set up passwords again", timeout=90), "the passwords are set up")


def set_up_codes(d, file_name):
    vault_page(d, ("Lujarch Vault", "Sources"))
    d.tap(d.scroll_to(text="Accounts"))
    pick(d, "Download", file_name)
    # The picked file is set up at once with its password.
    enter_password(d, AEGIS_PASSWORD)
    check(d.wait(text="Set up 2FA codes again", timeout=90), "the 2FA codes are set up")


def reset(d):
    """The vault as on a new phone: its data cleared, nothing pushed left behind."""
    d.sh(f"pm clear {PACKAGE}", check=False)
    d.sh("rm -f /sdcard/Download/lujarch-test-*", check=False)


def summary_of(d, title):
    """The first line under the row titled `title`: its summary."""
    lines = d.summary(title)
    return lines[0] if lines else ""


def appears(d, timeout=15, **what):
    """The element once it shows, or None when it does not within timeout."""
    deadline = time.monotonic() + timeout
    while not (found := d.find(**what)) and time.monotonic() < deadline:
        time.sleep(1)
    return found


def seek(d, limit=6, **what):
    """The element scrolled to, or None when the page does not show it."""
    try:
        return d.scroll_to(limit=limit, quiet=True, **what)
    except Failure:
        return None


def keyguard_showing(d):
    return "KeyguardShowing=true" in d.sh(
        "dumpsys activity activities | grep -m1 -o -E 'KeyguardShowing=[a-z]+'", check=False)


def unlock_with_pin(d):
    """The lock screen answered with the PIN, as after the phone starts or locks."""
    deadline = time.monotonic() + 40
    while keyguard_showing(d) or \
            "RUNNING_UNLOCKED" not in d.sh("cmd activity get-started-user-state 0", check=False):
        check(time.monotonic() < deadline, "the PIN unlocks the phone")
        d.key("KEYCODE_WAKEUP")
        time.sleep(1)
        # The lock screen's own way to its PIN pad, as a swipe up opens it.
        d.sh("wm dismiss-keyguard")
        time.sleep(2)
        d.type(PIN)
        d.key("KEYCODE_ENTER")
        time.sleep(2)
    d.sh("cmd statusbar collapse")

def pin_pad(d):
    """The PIN tapped on the lock screen's PIN pad, then its Enter, as a finger does."""
    pad = {}
    deadline = time.monotonic() + 10
    while len(pad) < 10 and time.monotonic() < deadline:
        pad = {n.text: n for n in d.ui(windows=True) if len(n.text) == 1 and n.text.isdigit()}
        time.sleep(0.5)
    check(len(pad) == 10, "the PIN pad shows")
    for digit in PIN:
        d.tap_xy(pad[digit].x, pad[digit].y, wait=0.3)
    enter = next((n for n in d.ui(windows=True) if n.rid.endswith("key_enter")), None)
    if enter:
        d.tap_xy(enter.x, enter.y)


class ShopProbe(AppProbe):
    """The probe app as another maker's app, under its own package name."""

    PACKAGE = SHOP
    LOGIN = f"{SHOP}/org.lujarch.probe.Login"

    def install(self):
        self.d.uninstall(self.PACKAGE)
        install_probe("app", package=self.PACKAGE)

    def sign_in(self, web=None, over_lock=False):
        """The sign-in form, an app's own or a site's page at web."""
        self.d.sh(f"am force-stop {self.PACKAGE}")
        self.clear()
        self.d.sh(f"am start -n {self.LOGIN}" + (f" --es web {web}" if web else "")
                  + (" --ez over_lock true" if over_lock else ""))
        time.sleep(1.5)

    def tap_field(self, name, web=False):
        """Taps a field of the form, as the owner does to sign in."""
        if not web:
            self.d.tap(appears(self.d, 15, text=name) or self.d.wait(contains=name, timeout=5))
            return
        deadline = time.monotonic() + 20
        while "fields" not in (got := self.results()):
            check(time.monotonic() < deadline, "the site's page shows its form")
            time.sleep(1)
        origin, _, centers = got["fields"].partition(":")
        x0, y0 = map(int, origin.split(","))
        x, y = json.loads(json.loads(centers))[FIELDS.index(name)]
        self.d.tap_xy(x0 + x, y0 + y)
