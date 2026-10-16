"""Every permission and background control takes its choices from one set, with the same words
everywhere, and offers only the choices that act differently for it: each page of an app's
permissions, and its Autostart, offers two or more choices, all of them from the set.

verifies: PERM-1
timeout: 2400
"""
import time

from lujarch_test import (PERMISSION_SECTIONS, AppProbe, app_permissions, check, device, note, run,
                          sections, step)

# The one set of choices, in its words.
CHOICES = {"Allow", "Allow all the time", "Allow only while using the app", "Ask every time",
           "Return empty data", "Don't allow"}
# Phone: Allow lets the app read basic phone info as well; this choice reads that alone.
PHONE_ONLY = "Basic phone info only"


def offered(d):
    """The choices of the page: the titles beside its radio buttons."""
    nodes = d.ui()
    radios = [n for n in nodes if n.checkable and n.cls.endswith("RadioButton")]
    texts = [n for n in nodes if n.text and not n.checkable]
    labels = []
    for r in radios:
        beside = [t for t in texts if abs(t.y - r.y) < 70 and t.x > r.x]
        if beside:
            labels.append(min(beside, key=lambda t: abs(t.y - r.y)).text)
    return labels


def page_choices(d):
    """Every choice of the page, read while scrolling it."""
    seen, last = [], None
    for _ in range(4):
        for label in offered(d):
            if label not in seen:
                seen.append(label)
        sig = [n.text for n in d.ui() if n.text]
        if sig == last:
            break
        last = sig
        d.swipe(540, 1800, 540, 900, 500)
    return seen


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    try:
        step("each permission of the app")
        app_permissions(d, p.PACKAGE)
        rows = [r for group, names in sections(d).items() if group in PERMISSION_SECTIONS
                for r in names if not r.startswith(("No permissions", "Remove permissions", "Pause app",
                                                     "Manage app", "Unused app"))]
        note(f"permissions: {rows}")
        check(len(rows) >= 8, "the app's permissions are listed")
        strange, single = {}, []
        for name in rows:
            app_permissions(d, p.PACKAGE)
            row = d.scroll_to(text=name, limit=8)
            d.tap(row)
            time.sleep(1.5)
            got = page_choices(d)
            note(f"{name}: {got}")
            if len(got) < 2:
                single.append(name)
            other = [c for c in got if c not in CHOICES and not (name == "Phone" and c == PHONE_ONLY)]
            if other:
                strange[name] = other
            if name == rows[0]:
                d.shot("choices")
            d.back()
        check(not strange, f"every choice is from the set ({strange})")
        check(not single, f"every page offers a choice ({single})")

        step("Autostart, a background control, from the same set")
        d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{p.PACKAGE}")
        d.tap(d.scroll_to(text="App battery usage"))
        d.tap(d.scroll_to(text="Autostart"))
        time.sleep(1.5)
        got = page_choices(d)
        note(f"Autostart: {got}")
        check(len(got) >= 2 and set(got) <= CHOICES, "Autostart's choices are from the set")
        d.shot("autostart")
    finally:
        d.home()


run(main)
