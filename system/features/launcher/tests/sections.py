"""The owner makes a drawer section by dragging an app below the drawer's last row; it shows under
the drawer's apps with a header that carries the name the owner gives it; a tap on the header folds
or opens it, a long press renames it, moves it or deletes it, which puts its apps back with the
others; App drawer sections lists them for the same actions; a section exists while it holds apps;
there are none at first, and search finds the apps of folded sections.

verifies: LCH-1
timeout: 1500
"""
import time

from lujarch_test import AppProbe, check, device, note, run, step

LAUNCHER = "com.android.launcher3"


def drawer(d):
    d.home()
    d.drawer()
    time.sleep(1)


def icon(d, name):
    return next((n for n in d.ui() if n.text == name and n.rid.endswith("/icon")), None)


def drag(d, node, x, y):
    """Long press on the app's icon, then a drag to x, y, as a finger does."""
    d.drag(node.x, node.bounds[1] + (node.bounds[3] - node.bounds[1]) // 3, x, y)
    time.sleep(1)


def below_last_row(d):
    """A point under the drawer's last row of apps."""
    icons = [n for n in d.ui() if n.rid.endswith("/icon")]
    return 540, min(max(n.bounds[3] for n in icons) + 150, 2150)


def header(d, name):
    """A section's header: its name, or for an unnamed section a divider that names it by its
    place ("Section 2") to screen readers, as the list and the section's actions do."""
    return next((n for n in d.ui() if "/icon" not in n.rid and
                 (n.text == name or n.desc.endswith(name))), None)


def sections_page(d):
    d.home()
    d.sh("input swipe 540 1200 540 1200 1200")
    d.tap(d.wait(text="Home settings"))
    d.tap(d.scroll_to(text="App drawer sections"))
    time.sleep(1.5)


def main():
    d = device()
    # One app named Probe in the drawer: the probe installed under another maker's name goes.
    d.uninstall("com.example.shop")
    p = AppProbe(d)
    p.install()
    d.sh(f"pm clear {LAUNCHER}", check=False)
    time.sleep(3)
    try:
        step("no sections at first")
        sections_page(d)
        names = [n.text for n in d.ui() if n.text]
        note(f"App drawer sections: {names}")
        check("App drawer sections" in names, "the page carries its name")
        check(not any(t.startswith("Section ") for t in names), "none listed")
        check(any(t.startswith("Drag an app") for t in names), "the page says how one is made")
        d.shot("no-sections")

        step("an app dragged below the last row makes a section under the drawer's apps")
        drawer(d)
        probe = icon(d, "Probe")
        check(probe, "the probe is in the drawer")
        drag(d, probe, *below_last_row(d))
        made = header(d, "Section 1")
        check(made, "a section with a header shows")
        apps = [n for n in d.ui() if n.rid.endswith("/icon")]
        moved = icon(d, "Probe")
        check(moved and moved.y > made.y and all(a.y <= made.y for a in apps if a.text != "Probe"),
              "the app is in it, under the drawer's other apps")
        d.shot("section-made")

        step("a long press on the header renames it")
        d.sh(f"input swipe {made.x} {made.y} {made.x} {made.y} 1000")
        d.tap(d.wait(text="Rename"))
        field = next(n for n in d.ui() if n.cls.endswith("EditText"))
        d.tap(field)
        d.sh("input keycombination KEYCODE_CTRL_LEFT KEYCODE_A")
        d.type("Tools")
        d.tap(d.wait(text="OK"))
        check(d.wait(text="Tools", timeout=10), "the header carries the name")

        step("a tap on the header folds and opens it; search finds the apps of a folded section")
        d.tap(header(d, "Tools"))
        time.sleep(1)
        check(not icon(d, "Probe"), "folded, its apps are hidden")
        d.shot("section-folded")
        d.tap(d.find(text="Search apps") or d.find(desc="Search apps") or d.find(contains="Search"))
        d.type("Probe")
        time.sleep(2)
        check(icon(d, "Probe") or d.find(text="Probe"), "search finds the folded section's app")
        d.back()
        drawer(d)
        d.tap(header(d, "Tools"))
        time.sleep(1)
        check(icon(d, "Probe"), "opened, its apps show")

        step("a second section, moved up; App drawer sections lists both for the same actions")
        drag(d, icon(d, "Clock") or icon(d, "Calendar"), *below_last_row(d))
        check(header(d, "Section 2"), "a second section, named by its place")
        sections_page(d)
        check(d.find(text="Tools") and d.find(text="Section 2"), "both are listed")
        d.shot("sections-listed")
        d.tap(d.find(text="Section 2"))
        d.tap(d.wait(text="Move up"))
        time.sleep(1)
        listed = [n.text for n in sorted(d.ui(), key=lambda n: n.y) if n.text in ("Tools", "Section 1")]
        check(listed == ["Section 1", "Tools"], f"moved up ({listed})")

        step("a deleted section puts its apps back; a section exists while it holds apps")
        drawer(d)
        tools = header(d, "Tools")
        d.sh(f"input swipe {tools.x} {tools.y} {tools.x} {tools.y} 1000")
        d.tap(d.wait(text="Delete"))
        time.sleep(1)
        check(not header(d, "Tools"), "the section is gone")
        probe = icon(d, "Probe")
        other = header(d, "Section 1")
        check(probe and (not other or probe.y < other.y), "its app is back with the others")
        moved = next(n for n in d.ui() if n.rid.endswith("/icon") and other and n.y > other.y)
        drag(d, moved, 540, 600)
        check(not header(d, "Section 1"), "a section whose last app leaves is gone")
    finally:
        d.sh(f"pm clear {LAUNCHER}", check=False)
        d.home()


run(main)
