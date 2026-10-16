"""Each node has a country or region and a line type, from which the groups are built; Node
locations lists the nodes without a location first, then the located ones; locating asks first,
names the outside services it asks, and connects through each node to ask them; a location is set
by hand for one node or for every node without one, and forgotten.

The virtual device has no internet: the outside services cannot answer there, so the test sees
locating ask through every node and end with the nodes still without a location.

verifies: PRX-12
timeout: 1500
"""
import re
import time

from lujarch_test import check, device, note, run, step

from _proxy import Fixtures, add_subscription, fresh, open_page, summary, use_proxy, wait_task

NODES = ("Tokyo 1", "Tokyo 2", "Dallas 1")


def main():
    d = device()
    fx = Fixtures().up()
    try:
        fresh(d)
        check(add_subscription(d, "Alpha", fx.alpha) == "Added, downloaded and applied", "Alpha is added")
        use_proxy(d, True)
        unlocated(d)
        locate(d, fx)
        by_hand(d)
        forget(d)
    finally:
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def locations_page(d):
    open_page(d, "Subscriptions")
    row = d.find(contains="without a location")
    if row:
        d.tap(row, wait=3)
    else:
        open_page(d, "Subscriptions", "Alpha")
        d.tap(d.scroll_to(contains="Node locations"), wait=3)
    d.wait(text="Locate nodes without a location")


def sections(d):
    """{"Without a location": [...], "Located": [...]} in the page's order."""
    for _ in range(3):
        d.swipe(540, 700, 540, 1900, 300)
    found, current = {}, None
    for _ in range(3):
        for n in sorted((n for n in d.ui() if n.text), key=lambda n: n.y):
            if n.text in ("Without a location", "Located"):
                current = n.text
                found.setdefault(current, [])
            elif current and n.text in NODES and n.text not in found[current]:
                found[current].append(n.text)
        d.swipe(540, 1800, 540, 1000, 400)
    return found


def unlocated(d):
    step("new nodes have no location: Subscriptions says so and leads to Node locations")
    open_page(d, "Subscriptions")
    warning = d.find(contains="without a location")
    check(warning and warning.text == "3 nodes without a location", "Subscriptions warns of 3 nodes")
    d.shot("subscriptions-unlocated")
    locations_page(d)
    check(summary(d, "All nodes") == "3 of 3 nodes have no location", f"the page counts them ({summary(d, 'All nodes')})")
    found = sections(d)
    check(sorted(found.get("Without a location", [])) == sorted(NODES), f"all are without a location ({found})")
    d.shot("locations-unlocated")
    open_page(d, "Proxy groups")
    texts = d.texts()
    check("JP - Auto" not in texts and "GLOBAL" in texts, "without locations they are in no country group")


def locate(d, fx):
    step("Locate asks first and names the outside services")
    locations_page(d)
    d.tap(text="Locate nodes without a location", wait=2)
    message = d.find(contains="api.ipify.org")
    check(message and "ip-api.com" in message.text and "ipapi.is" in message.text,
          "the question names api.ipify.org, ip-api.com and ipapi.is")
    note(message.text)
    d.shot("locate-asks")

    step("locating connects through each node to ask")
    since = time.time()
    d.tap(text="Locate", wait=3)
    result = wait_task(d, "Locate nodes", timeout=240)
    note(f"result: {result}")
    asked = fx.via(since)
    note(f"asked through the nodes: {asked}")
    for node in NODES:
        check(("api.ipify.org", 443) in asked.get(node, []), f"{node} was asked for api.ipify.org")
    d.shot("locate-result")
    d.tap(text="Locate nodes", wait=3)
    d.wait(text="Result", timeout=30)
    texts = " ".join(d.texts())
    note(texts[:400])
    check(re.search(r"0 nodes located|Locating did not succeed", texts),
          "without the outside services' answers no node is located, and the result says so")
    d.shot("locate-result-page")


def by_hand(d):
    step("a location by hand for one node: it moves to the located ones")
    locations_page(d)
    d.tap(d.scroll_to(text="Tokyo 1"), wait=2)
    d.tap(rid="px_manual_country")
    d.type("JP")
    d.tap(text="Residential")
    d.tap(text="Save and apply", wait=3)
    s = wait_task(d, "Set the location of Tokyo 1")
    check(s == "1 node located and applied", f"located ({s})")
    found = sections(d)
    note(f"{found}")
    check(list(found) == ["Without a location", "Located"], "the nodes without a location come first")
    check(found["Located"] == ["Tokyo 1"], "Tokyo 1 is located")
    check(summary(d, "Tokyo 1") == "JP Residential · set by hand · Alpha", f"its location ({summary(d, 'Tokyo 1')})")
    d.shot("located-one")

    step("a location by hand for every node without one")
    d.tap(text="Set a location for all nodes without one", wait=2)
    d.tap(rid="px_manual_country")
    d.type("US")
    d.tap(text="Data center")
    d.tap(text="Save and apply", wait=3)
    s = wait_task(d, "Set the location of Location of 2 nodes")
    check(s == "2 nodes located and applied", f"located ({s})")
    check(summary(d, "All nodes") == "All 3 nodes located", f"all are located ({summary(d, 'All nodes')})")
    d.shot("located-all")

    step("the groups are built from the locations")
    open_page(d, "Proxy groups")
    texts = d.texts()
    check("JP - Auto" in texts and "US - Auto" in texts and "JP Residential - Auto" in texts,
          "country and line-type groups follow the locations")
    d.shot("groups-from-locations")


def forget(d):
    step("a location is forgotten")
    locations_page(d)
    d.tap(d.scroll_to(text="Dallas 1"), wait=2)
    d.tap(text="Forget the location", wait=2)
    check(d.find(contains="leaves its country and line-type groups"), "the question says what it does")
    d.tap(text="Forget", wait=3)
    s = wait_task(d, "Forget the location of “Dallas 1”")
    check(s == "The location of “Dallas 1” is forgotten", f"forgotten ({s})")
    found = sections(d)
    check(found.get("Without a location") == ["Dallas 1"], f"Dallas 1 is without a location again ({found})")
    d.shot("forgotten")


run(main)
