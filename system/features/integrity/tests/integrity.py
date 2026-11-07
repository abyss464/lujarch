"""Play Integrity: Settings > Security & privacy > Play Integrity has the switch Use Play
Integrity (on by default) and imports or removes a keybox.xml through the document picker. When a
keybox is imported and Use Play Integrity is on, hardware key attestation chains are signed by the
imported keybox with a verified bootloader state; the private key is never written to logs or
Settings.

verifies: INT-1, CORE-11
timeout: 900
"""
import base64
import subprocess
import tempfile
import time
from pathlib import Path

from lujarch_test import AppProbe, check, device, note, run, step


def make_test_keybox() -> tuple[str, str]:
    """Generates a fresh ephemeral EC + RSA keybox.xml with openssl for the test."""
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        subj = "/CN=LujarchKeyboxTestCA"
        subprocess.run(
            ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout",
             "-out", str(d / "ec_key.pem")],
            check=True, capture_output=True, text=True,
        )
        subprocess.run(
            ["openssl", "req", "-new", "-x509", "-key", str(d / "ec_key.pem"),
             "-subj", subj, "-days", "365", "-out", str(d / "ec_cert.pem")],
            check=True, capture_output=True, text=True,
        )
        subprocess.run(
            ["openssl", "genrsa", "-out", str(d / "rsa_key.pem"), "2048"],
            check=True, capture_output=True, text=True,
        )
        subprocess.run(
            ["openssl", "req", "-new", "-x509", "-key", str(d / "rsa_key.pem"),
             "-subj", subj, "-days", "365", "-out", str(d / "rsa_cert.pem")],
            check=True, capture_output=True, text=True,
        )
        ec_key = (d / "ec_key.pem").read_text().strip()
        ec_cert = (d / "ec_cert.pem").read_text().strip()
        rsa_key = (d / "rsa_key.pem").read_text().strip()
        rsa_cert = (d / "rsa_cert.pem").read_text().strip()
        secret_line = [ln.strip() for ln in ec_key.splitlines() if not ln.startswith("-----")][0]
        xml = f"""<?xml version="1.0"?>
<AndroidAttestation>
    <NumberOfKeyboxes>1</NumberOfKeyboxes>
    <Keybox DeviceID="lujarch-test">
        <Key algorithm="ecdsa">
            <PrivateKey format="pem">
{ec_key}
            </PrivateKey>
            <CertificateChain>
                <NumberOfCertificates>1</NumberOfCertificates>
                <Certificate format="pem">
{ec_cert}
                </Certificate>
            </CertificateChain>
        </Key>
        <Key algorithm="rsa">
            <PrivateKey format="pem">
{rsa_key}
            </PrivateKey>
            <CertificateChain>
                <NumberOfCertificates>1</NumberOfCertificates>
                <Certificate format="pem">
{rsa_cert}
                </Certificate>
            </CertificateChain>
        </Key>
    </Keybox>
</AndroidAttestation>
"""
        return xml, secret_line


FILES = "me.zhanghai.android.files"


def open_integrity_settings(d):
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.SETTINGS", wait=2)
    d.tap(d.scroll_to(text="Security & privacy"))
    time.sleep(1.5)
    d.tap(d.scroll_to(text="Play Integrity"))
    d.wait(text="Use Play Integrity")


def pick_in_files(d, filename: str):
    deadline = time.monotonic() + 20
    while d.top()[0] != FILES and time.monotonic() < deadline:
        time.sleep(0.5)
    check(d.top()[0] == FILES, "the document picker opens")
    d.tap(d.scroll_to(text="Download"))
    time.sleep(1)
    d.tap(d.scroll_to(text=filename))


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()

    xml, secret_line = make_test_keybox()
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
        f.write(xml)
        local_xml = Path(f.name)

    try:
        d.push(str(local_xml), "/sdcard/Download/test-keybox.xml")
        d.sh("service call lujarch_integrity 3 >/dev/null && rm -f /data/lujarch/integrity/keybox.xml")
        d.logcat_clear()

        step("open Settings > Security & privacy > Play Integrity")
        open_integrity_settings(d)
        sw = d.switch_of("Use Play Integrity")
        check(sw is not None and sw.checked, "Use Play Integrity is on by default")
        check(d.find(contains="Not imported") is not None, "no keybox imported initially")

        step("import keybox.xml through the document picker")
        d.tap(d.find(text="Import keybox"))
        pick_in_files(d, "test-keybox.xml")
        check(d.wait(contains="EC, RSA", timeout=15) is not None, "imported keybox status shows")
        d.shot("integrity-keybox-imported")
        check(d.find(text="Remove keybox") is not None, "Remove keybox row is shown")

        step("hardware key attestation uses the imported keybox and verified RootOfTrust")
        got = p.do("attest").get("attest", "")
        note(f"attest: {got[:120]}")
        check("issuer=CN=LujarchKeyboxTestCA" in got and "ca=CN=LujarchKeyboxTestCA" in got,
              f"attestation chain is signed by the imported keybox: {got[:120]}")
        leaf_b64 = got.split("leaf=", 1)[-1]
        leaf_der = base64.b64decode(leaf_b64)
        # In RootOfTrust ([704], 0xbf 0x85 0x40), deviceLocked = TRUE (01 01 ff) and
        # verifiedBootState = Verified (0a 01 00).
        check(b"\x01\x01\xff\x0a\x01\x00" in leaf_der,
              "RootOfTrust in leaf certificate has deviceLocked=true and verifiedBootState=Verified")

        step("the private key is not written to logs or Settings")
        logs = d.sh("logcat -d", check=False)
        settings_dump = (d.sh("settings list global", check=False)
                         + d.sh("settings list secure", check=False))
        check(secret_line not in logs and secret_line not in settings_dump,
              "private key material does not appear in logcat or Settings")

        step("turning off Use Play Integrity bypasses the imported keybox")
        open_integrity_settings(d)
        d.tap(d.find(text="Use Play Integrity"))
        time.sleep(1)
        sw_off = d.switch_of("Use Play Integrity")
        check(sw_off is not None and not sw_off.checked, "Use Play Integrity switch turns off")
        got_off = p.do("attest").get("attest", "")
        check("LujarchKeyboxTestCA" not in got_off,
              f"attestation does not use imported keybox when switch is off: {got_off[:120]}")

        step("remove the imported keybox and restore default switch state")
        open_integrity_settings(d)
        d.tap(d.find(text="Use Play Integrity"))
        time.sleep(1)
        sw_on = d.switch_of("Use Play Integrity")
        check(sw_on is not None and sw_on.checked, "Use Play Integrity switch turns back on")
        d.tap(d.find(text="Remove keybox"))
        d.wait(text="Remove")
        d.tap(d.find(text="Remove"))
        check(d.wait(contains="Not imported", timeout=10) is not None,
              "keybox status returns to Not imported")
        d.shot("integrity-keybox-removed")
    finally:
        local_xml.unlink(missing_ok=True)
        d.sh("service call lujarch_integrity 3 >/dev/null; "
             "rm -f /sdcard/Download/test-keybox.xml /data/lujarch/integrity/keybox.xml",
             check=False)
        d.home()


run(main)
