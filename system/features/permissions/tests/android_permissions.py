"""Android's permissions offer Return empty data, under which the app holds the permission and
reads nothing; Phone covers basic phone info with its own five choices and lists every app.

verifies: PERM-2, PERM-3
timeout: 1500
"""
import re

from lujarch_test import Failure, AppProbe, app_permissions, check, choose, device, install_probe, note, run, step

EMPTY_OFFERED = ["Location", "Camera", "Microphone", "Contacts and accounts", "Calendar", "Call logs", "SMS",
                 "Photos and videos", "Music and audio", "Nearby devices", "Physical activity"]
PHONE_CHOICES = ["Allow", "Basic phone info only", "Ask every time", "Return empty data", "Don't allow"]
TARGET = "org.lujarch.probe.target"


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.sh("content insert --uri content://com.android.contacts/raw_contacts "
         "--bind account_type:n: --bind account_name:n:")
    try:
        offered(d, p)
        contacts(d, p)
        phone(d, p)
    finally:
        d.uninstall(TARGET)
        d.home()


def offered(d, p):
    step("each of Android's permissions the target names offers Return empty data")
    missing = []
    for name in EMPTY_OFFERED:
        app_permissions(d, p.PACKAGE)
        d.tap(text=name)
        d.wait(text="Don't allow")
        try:
            d.scroll_to(text="Return empty data", limit=4, quiet=True)
        except Failure:
            missing.append(name)
        d.back()
    check(not missing, f"Return empty data offered by all ({', '.join(missing)} lack it)")


def contacts(d, p):
    step("Contacts: Allow reads the contacts, Return empty data reads none with the grant held")
    choose(d, p.PACKAGE, "Contacts and accounts", "Allow")
    got = p.do("contacts")["contacts"]
    check(re.fullmatch(r"rows=[1-9]\d*", got), f"Allow: contacts read ({got})")
    choose(d, p.PACKAGE, "Contacts and accounts", "Return empty data")
    got = p.do("contacts")["contacts"]
    check(got == "rows=0", f"Return empty data: none ({got})")
    granted = d.sh(f"dumpsys package {p.PACKAGE} | grep 'READ_CONTACTS: granted=true'", check=False)
    check(granted.strip(), "the permission stays granted")
    step("the permission manager lists the app in a section of its own")
    d.start("-f 0x10008000 -a android.intent.action.MANAGE_PERMISSION_APPS "
            "--es android.intent.extra.PERMISSION_GROUP_NAME android.permission-group.CONTACTS")
    header = d.scroll_to(contains="empty data")
    probe = d.scroll_to(text="Probe")
    check(probe.y > header.y, f"Probe under “{header.text}”")
    d.shot("contacts-empty-section")
    choose(d, p.PACKAGE, "Contacts and accounts", "Allow")


def phone(d, p):
    step("Phone: five choices, basic phone info follows them")
    app_permissions(d, p.PACKAGE)
    d.tap(text="Phone")
    for c in PHONE_CHOICES:
        check(d.find(text=c) or d.scroll_to(text=c), f"Phone offers {c}")
    d.shot("phone-choices")
    d.back()
    results = {}
    for c in ("Allow", "Basic phone info only", "Return empty data", "Don't allow"):
        choose(d, p.PACKAGE, "Phone", c)
        results[c] = p.do("phone")["phone"]
        note(f"{c}: {results[c]}")
    check("operator=Android Virtual Operator" in results["Allow"], "Allow: the carrier reads")
    check("operator=Android Virtual Operator" in results["Basic phone info only"],
          "Basic phone info only: the carrier reads")
    for c in ("Return empty data", "Don't allow"):
        check(re.search(r"operator=(\s|$)", results[c]) or "refused" in results[c],
              f"{c}: no carrier")
    choose(d, p.PACKAGE, "Phone", "Allow")
    step("an app that never asked for Phone is listed under Phone")
    install_probe("target")
    d.start("-f 0x10008000 -a android.intent.action.MANAGE_PERMISSION_APPS "
            "--es android.intent.extra.PERMISSION_GROUP_NAME android.permission-group.PHONE")
    check(d.scroll_to(text="Probe target"), "Probe target is listed under Phone")
    d.shot("phone-list")


run(main)
