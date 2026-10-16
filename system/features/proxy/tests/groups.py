"""Proxy groups lists the running proxy's groups in sections (custom groups, countries and
regions, line types, all nodes, other groups) with a search by name; a group's page lists its
members with their delay or Not tested, tests one member or all, marks the member in use, lets
the owner choose in a group where the owner chooses, shows the choice of a group that chooses by
delay, and opens the groups inside a group from their own rows.

verifies: PRX-9
timeout: 1500
"""
import re
import time

from lujarch_test import check, device, note, run, sections, step

from _proxy import WEB_NAME, Apps, Fixtures, open_page, setup, summary, until, wait_task

SECTIONS = ("Custom groups", "Countries and regions", "Countries and regions by line type",
            "All nodes", "Other groups")


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        custom_group(d)
        listing(d)
        search(d)
        members(d, fx)
        chooses_by_delay(d, apps)
        owner_chooses(d)
    finally:
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def custom_group(d):
    step("Rules > Custom groups: a custom group Tokyo (JP, name contains Tokyo)")
    open_page(d, "Rules", "Custom groups")
    d.tap(text="Add custom group", wait=2)
    d.tap(rid="px_custom_key")
    d.type("Tokyo")
    d.tap(rid="px_manual_country")
    d.type("JP")
    d.tap(rid="px_custom_contains")
    d.type("Tokyo")
    d.tap(text="Save and apply", wait=3)
    s = wait_task(d, "Add Tokyo")
    check(s == "Tokyo added and applied", f"the group is added ({s})")


def groups_page(d):
    open_page(d, "Proxy groups")
    d.wait(text="Countries and regions", timeout=30)


def listing(d):
    step("Proxy groups: the running proxy's groups in their sections")
    groups_page(d)
    d.shot("groups")
    found = sections(d, headers=SECTIONS)
    for header, rows in found.items():
        note(f"{header}: {[r for r in rows if ' · ' not in r and not r.startswith('now ')]}")
    check(list(found) == list(SECTIONS), f"the sections in order ({list(found)})")
    check("Tokyo - Auto" in found["Custom groups"], "the custom group is under Custom groups")
    check({"JP - Auto", "US - Auto"} <= set(found["Countries and regions"]), "the countries")
    check({"JP Residential - Auto", "JP Datacenter - Auto", "US Datacenter - Auto"}
          <= set(found["Countries and regions by line type"]), "the line types")
    check("All - Auto" in found["All nodes"], "all nodes")
    check("GLOBAL" in found["Other groups"], "GLOBAL among the other groups")


def search(d):
    step("search finds a group by name")
    groups_page(d)
    d.tap(desc="Search", wait=2)
    d.type("US")
    time.sleep(2)
    texts = d.texts()
    d.shot("groups-search")
    check("US - Auto" in texts and "US Datacenter - Auto" in texts, "the US groups are found")
    check("JP - Auto" not in texts and "Tokyo - Auto" not in texts, "the others are not")
    d.back()


def member_line(d, name):
    return summary(d, name) or ""


def open_group(d, name):
    groups_page(d)
    d.tap(d.scroll_to(text=name), wait=3)
    d.wait(text="Test delay")


def members(d, fx):
    step("a group's page: its members with their delay, or Not tested")
    open_group(d, "GLOBAL")
    direct, tokyo = member_line(d, "DIRECT"), member_line(d, "Tokyo 1")
    note(f"DIRECT: {direct}; Tokyo 1: {tokyo}")
    d.shot("members-delay-or-not-tested")
    check(direct == "Direct · Not tested", "a member without a test says Not tested")
    check(re.fullmatch(r"Socks5 · \d+\sms", tokyo), "a tested member shows its delay")

    step("one member's test: Dallas 1, down, times out")
    open_group(d, "US Datacenter - Auto")
    fx.nodes["Dallas 1"].down()
    try:
        d.tap(desc="Test the delay of Dallas 1", wait=2)
        until(lambda: member_line(d, "Dallas 1") == "Socks5 · Timed out", "Dallas 1 times out", 60)
        d.shot("member-timed-out")
    finally:
        fx.nodes["Dallas 1"].up()

    step("Test delay tests every member")
    d.tap(text="Test delay", wait=2)
    until(lambda: re.fullmatch(r"Socks5 · \d+\sms", member_line(d, "Dallas 1")), "Dallas 1 answers again", 60)
    tested = summary(d, "Test delay") or ""
    note(f"Dallas 1: {member_line(d, 'Dallas 1')}; {tested}")
    check(tested.startswith("Tested at "), "the group says when it was tested")
    d.shot("group-tested")


def chooses_by_delay(d, apps):
    step("a group that chooses by delay shows its choice and marks the member in use")
    apps.reach(f"{WEB_NAME}:80")
    open_group(d, "JP Residential - Auto")
    head = summary(d, "JP Residential - Auto") or ""
    note(head)
    check(head == "Lowest delay · now Tokyo 1", "the group shows its choice")
    nodes = d.ui()
    title = d.find(text="Tokyo 1", nodes=nodes)
    radio = [n for n in nodes if n.checkable and abs(n.y - title.y) < 60]
    check(radio and radio[0].checked, "the member in use is marked")
    d.shot("group-by-delay")


def owner_chooses(d):
    step("GLOBAL, where the owner chooses: a tap selects the member")
    open_group(d, "GLOBAL")
    d.tap(d.scroll_to(text="Tokyo 2"), wait=3)
    def selected():
        if not d.find(text="GLOBAL"):
            d.swipe(540, 700, 540, 1900, 300)
        return summary(d, "GLOBAL") == "Manual selection · now Tokyo 2"
    until(selected, "Tokyo 2 is selected", 30)
    nodes = d.ui()
    title = d.find(text="Tokyo 2", nodes=nodes)
    radio = [n for n in nodes if n.checkable and abs(n.y - title.y) < 60]
    check(radio and radio[0].checked, "the chosen member is marked")
    d.shot("global-chosen")

    step("groups inside a group open from their own rows")
    header = d.scroll_to(text="Groups in this group")
    rows = [n for n in d.ui() if n.text == "JP Residential - Auto" and n.y > header.y]
    if not rows:
        d.swipe(540, 1800, 540, 900, 500)
        rows = [n for n in d.ui() if n.text == "JP Residential - Auto"]
    d.tap(rows[-1], wait=3)
    d.wait(text="Lowest delay · now Tokyo 1")
    check(d.find(text="Test delay") and d.find(text="Tokyo 1"), "its page opens")
    d.shot("nested-group")


run(main)
