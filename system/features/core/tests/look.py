"""Lujarch's own pages look like Android's Settings pages: the collapsing toolbar, preference
lists, and Android's colors in the dark theme, as an Android page beside them shows them.

verifies: CORE-7
timeout: 1200
"""
import time

from lujarch_test import check, device, note, png, run, step

# Pages Lujarch adds, each with how to reach it from Settings.
PAGES = {
    "Status bar items": ["System", "Status bar", "Status bar items"],
    "Lujarch motion": ["Accessibility", "Color & motion", "Lujarch motion"],
    "Defaults for new apps": ["Apps", "Defaults for new apps"],
    "Proxy": ["Network & internet", "Proxy"],
    "Sync": ["Connected devices", "Sync"],
    "Lujarch Vault": ["Passwords & passkeys", "Lujarch Vault"],
}


def open_page(d, path):
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.SETTINGS", wait=2)
    for seg in path:
        d.tap(d.scroll_to(text=seg))
        time.sleep(1.5)


def card(path, titles):
    """The color of the rows' cards: inside each, left of its title; the most common one, as a
    category's title sits on the page itself."""
    w, h, rows = png(path)
    colors = [tuple(rows[t.y][max(0, t.bounds[0] - 20)][:3]) for t in titles]
    return max(set(colors), key=colors.count)


def looks(d, name):
    nodes = d.ui()
    rids = {n.rid.split("/")[-1] for n in nodes}
    toolbar = bool(rids & {"collapsing_toolbar", "app_bar", "action_bar"}) or any(
        n.cls.endswith("CollapsingToolbarLayout") for n in nodes)
    titles = [n for n in nodes if n.rid == "android:id/title" and n.text]
    shot = d.shot(name.lower().replace(" ", "-"))
    return toolbar, len(titles), card(shot, titles) if titles else None


def main():
    d = device()
    step("an Android page for comparison: Display")
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.DISPLAY_SETTINGS", wait=3)
    _, _, android = looks(d, "android-display")
    note(f"Android's rows: {android}")
    for name, path in PAGES.items():
        step(name)
        open_page(d, path)
        toolbar, rows, ground = looks(d, name)
        note(f"{name}: toolbar {toolbar}, {rows} preference rows, ground {ground}")
        check(toolbar, f"{name} has Settings' collapsing toolbar")
        check(rows >= 1, f"{name} lists preferences")
        check(ground and sum(abs(a - b) for a, b in zip(ground, android)) <= 24,
              f"{name}'s rows have the color of Android's ({ground} vs {android})")
    d.home()


run(main)
