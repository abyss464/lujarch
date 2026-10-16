"""Lujarch's permissions, chosen on the app's permission pages, act on what the app reads:
sensors, clipboard, installed apps, device identifiers, device info and network info, each with
Allow, Return empty data and, where offered, Don't allow.

verifies: PERM-4, PERM-5, PERM-6, PERM-7, PERM-8, PERM-9
timeout: 1500
"""
import re

from lujarch_test import AppProbe, check, choose, device, install_probe, note, run, step

TARGET = "org.lujarch.probe.target"


def main():
    d = device()
    p = AppProbe(d)
    step("a fresh probe, and a user app it may see")
    p.install()
    d.uninstall(TARGET)
    install_probe("target")
    p.allow()
    d.sh("cmd alarm set-timezone Asia/Tokyo")
    try:
        sensors(d, p)
        clipboard(d, p)
        apps(d, p)
        ids(d, p)
        device_info(d, p)
        network(d, p)
    finally:
        d.sh("cmd alarm set-timezone GMT")
        d.uninstall(TARGET)
        d.home()


def sensors(d, p):
    step("Motion and environment sensors")
    got = p.do("sensors")["sensors"]
    check(got.startswith("readings"), f"Allow: the sensors read ({got})")
    choose(d, p.PACKAGE, "Motion and environment sensors", "Return empty data")
    got = p.do("sensors")["sensors"]
    check(got == "no readings", f"Return empty data: no readings ({got})")
    choose(d, p.PACKAGE, "Motion and environment sensors", "Don't allow")
    got = p.do("sensors")["sensors"]
    check(got in ("refused", "no sensor"), f"Don't allow: the sensors do not open ({got})")
    d.shot("sensors-dont-allow")
    choose(d, p.PACKAGE, "Motion and environment sensors", "Allow")


def clipboard(d, p):
    step("Clipboard")
    got = p.do("copy,clipboard", "--es text lujarch-clip")["clipboard"]
    check(got == "lujarch-clip", f"Allow: the copied text reads ({got!r})")
    choose(d, p.PACKAGE, "Clipboard", "Return empty data")
    got = p.do("copy,clipboard", "--es text lujarch-clip")["clipboard"]
    check(got == "", f"Return empty data: the clipboard is empty ({got!r})")
    choose(d, p.PACKAGE, "Clipboard", "Allow")


def apps(d, p):
    step("Installed apps")
    got = p.do("apps")["apps"]
    total = int(re.search(r"total=(\d+)", got).group(1))
    check(TARGET in got, f"Allow: the user's apps are seen ({got[:120]})")
    choose(d, p.PACKAGE, "Installed apps", "Return empty data")
    got = p.do("apps")["apps"]
    user = re.search(r"user=(\S*)", got).group(1).split("|")
    check(TARGET not in got and user == [p.PACKAGE],
          f"Return empty data: no app the user installed but itself ({got[:120]})")
    choose(d, p.PACKAGE, "Installed apps", "Don't allow")
    got = p.do("apps")["apps"]
    fewer = int(re.search(r"total=(\d+)", got).group(1))
    note(f"Allow saw {total} apps, Don't allow {fewer}")
    check(fewer < total / 4, f"Don't allow: itself and the system parts every app needs ({fewer})")
    d.shot("installed-apps-dont-allow")
    choose(d, p.PACKAGE, "Installed apps", "Allow")


def ids(d, p):
    step("Device identifiers")
    got = p.do("ids")["ids"]
    check(re.fullmatch(r"android_id=[0-9a-f]{16}", got) and got != "android_id=" + "0" * 16,
          f"Allow: the Android ID reads ({got})")
    choose(d, p.PACKAGE, "Device identifiers", "Return empty data")
    got = p.do("ids")["ids"]
    check(re.fullmatch(r"android_id=(0*|null|)", got), f"Return empty data: empty or zeros ({got})")
    choose(d, p.PACKAGE, "Device identifiers", "Allow")


def device_info(d, p):
    step("Device info, from the app's next start")
    got = p.do("device")["device"]
    check("model=unknown" not in got and "tz=Asia/Tokyo" in got, f"Allow: model and time zone ({got})")
    choose(d, p.PACKAGE, "Device info", "Return empty data")
    got = p.do("device", restart=True)["device"]
    check("model=unknown" in got and "maker=unknown" in got, f"Return empty data: model unknown ({got})")
    check("tz=UTC" in got or "tz=GMT" in got, "Return empty data: the time zone is UTC")
    check("battery=-1" in got or "battery=0" in got, "Return empty data: no battery state")
    d.shot("device-info-empty")
    choose(d, p.PACKAGE, "Device info", "Allow")


def network(d, p):
    step("Network info")
    got = p.do("network")["network"]
    check("192.168." in got, f"Allow: the addresses read ({got[:160]})")
    choose(d, p.PACKAGE, "Network info", "Return empty data")
    got = p.do("network")["network"]
    check("addresses=[]" in got and "dns=[]" in got and "vpn=false" in got and "bluetooth=false" in got,
          f"Return empty data: empty, no VPN, Bluetooth off ({got[:160]})")
    got = p.do("connect", "--es to 192.168.99.1:80")["connect"]
    check(got.startswith("connected"), f"the app's own connections keep working ({got})")
    choose(d, p.PACKAGE, "Network info", "Allow")


run(main)
