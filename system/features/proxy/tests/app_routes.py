"""Each app has a route: Follow the rules, Direct, Proxy group, Reject or Bypass the proxy entirely,
set from App info's Proxy row or from Per-app routing, which lists every app with its route (apps
with their own route first, system apps behind Show system apps). Apps that share a user ID share
one route and the page names them. A change is applied at once; when applying fails, the route
stays saved and the page offers to try again.

verifies: PRX-7
timeout: 1800
"""
import time

from lujarch_test import check, device, note, run, step

from _proxy import (ROUTER, WEB_NAME, Apps, Fixtures, direct_router, open_page, setup,
                    summary, until, via, wait_task)

PROBE = "org.lujarch.probe"


def app_info(d, package):
    d.unlock()
    d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{package}")
    d.wait(text="App info")


def proxy_row(d, package):
    """App info's Proxy row: its summary."""
    app_info(d, package)
    d.scroll_to(text="Proxy")
    return summary(d, "Proxy")


def route_page(d, package):
    app_info(d, package)
    d.tap(d.scroll_to(text="Proxy"), wait=3)
    d.wait(text="Route")


def choose(d, package, choice, group=None):
    """The app's route page: choice (and the group for Proxy group), applied."""
    route_page(d, package)
    d.tap(d.scroll_to(text=choice), wait=2)
    if group:
        d.tap(d.scroll_to(text=group), wait=3)
    until(lambda: d.find(text="Applied"), f"{choice} is applied", 90)
    note(f"{choice}: {summary(d, 'Applied')}")


def answers(fx, apps):
    since = time.time()
    got = {"name": apps.reach(f"{WEB_NAME}:80"), "router": apps.reach(f"{ROUTER}:80")}
    note(f"probe: {got}; nodes asked: {fx.via(since)}")
    return got, fx.via(since)


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        routes(d, fx, apps)
        listing(d)
        shared(d)
        not_applied(d, fx, apps)
    finally:
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def routes(d, fx, apps):
    step("Follow the rules (the default): the final rule's node carries the probe")
    row = proxy_row(d, PROBE)
    d.shot("app-info-follows")
    check(row == "Follows the rules", f"App info's Proxy row: {row}")
    got, asked = answers(fx, apps)
    check(via(got["name"], "Tokyo 1") and via(got["router"], "Tokyo 1"), "Tokyo 1 answers both")

    step("Direct: out without a node, still through the proxy")
    choose(d, PROBE, "Direct")
    d.shot("route-direct")
    got, asked = answers(fx, apps)
    check(not asked, "no node is asked")
    check(direct_router(got["router"]), "the router answers itself")
    check(got["name"].startswith("connected"), "the name was looked up by the proxy, which took the connection")
    check(proxy_row(d, PROBE) == "Direct", "App info's Proxy row says Direct")

    step("Proxy group: every connection uses the chosen group")
    choose(d, PROBE, "Proxy group", "US - Auto")
    d.shot("route-group")
    got, asked = answers(fx, apps)
    check(via(got["name"], "Dallas 1") and via(got["router"], "Dallas 1"), "Dallas 1, the group's node, answers")
    check(proxy_row(d, PROBE) == "Proxy: US - Auto", "App info's Proxy row names the group")

    step("Reject: every connection is blocked")
    choose(d, PROBE, "Reject")
    d.shot("route-reject")
    got, asked = answers(fx, apps)
    check(not asked, "no node is asked")
    check(not got["router"].startswith("connected HTTP"), "the router is not reached")
    check(not got["name"].startswith("connected HTTP"), "the name is not reached")
    check(proxy_row(d, PROBE) == "Rejected", "App info's Proxy row says Rejected")

    step("Bypass the proxy entirely: its traffic, DNS included, does not enter the proxy")
    choose(d, PROBE, "Bypass the proxy entirely")
    d.shot("route-bypass")
    got, asked = answers(fx, apps)
    check(not asked, "no node is asked")
    check("UnknownHost" in got["name"], "the name is looked up outside the proxy, where it does not exist")
    check(direct_router(got["router"]), "the router answers directly")
    peer = apps.peer_reach(f"{WEB_NAME}:80")
    check(via(peer, "Tokyo 1"), f"another app still goes through the proxy ({peer})")
    check(proxy_row(d, PROBE) == "Bypasses the proxy entirely", "App info's Proxy row says so")


def listing(d):
    step("Per-app routing lists the apps, those with a route of their own first")
    open_page(d, "Per-app routing")
    d.wait(text="Probe")
    d.tap(desc="More options", wait=1.5)
    if d.find(text="Hide system apps"):
        # Test setup: the list as it first opens, without the system apps.
        d.tap(text="Hide system apps", wait=3)
    else:
        d.back()
    nodes = d.ui()
    header = d.find(contains="with its own route", nodes=nodes)
    check(header, "the list says how many apps have a route of their own")
    rows = sorted((n for n in nodes if n.text and n.y > header.y and n.rid.endswith("title")), key=lambda n: n.y)
    names = [n.text for n in rows]
    note(f"apps: {names}")
    check(names and names[0] == "Probe", "Probe, with a route of its own, comes first")
    check(summary(d, "Probe", nodes) == "Bypasses the proxy entirely", "with its route")
    check(summary(d, "Probe peer", nodes) == "Follows the rules", "Probe peer follows the rules")
    check(not d.find(text="Android Keyboard (AOSP)", nodes=nodes), "system apps are not listed yet")
    d.shot("routing-list")
    d.tap(desc="More options", wait=1.5)
    d.tap(text="Show system apps", wait=3)
    check(d.scroll_to(text="Android Keyboard (AOSP)"), "Show system apps lists them")
    d.shot("routing-list-system")


def shared(d):
    step("apps that share a user ID share one route, and the page names them")
    route_page(d, "com.android.providers.telephony")
    text = summary(d, "Shares its user ID") or ""
    note(text)
    check("Phone Services" in text and "android.uid.phone" in text, "the page names the apps of the user ID")
    d.shot("route-shared-uid")
    choose(d, "com.android.providers.telephony", "Direct")
    row = proxy_row(d, "com.android.phone")
    check(row == "Direct", f"Phone Services, with the same user ID, has the route too ({row})")
    choose(d, "com.android.phone", "Follow the rules")
    check(proxy_row(d, "com.android.providers.telephony") == "Follows the rules", "and loses it with it")


def not_applied(d, fx, apps):
    step("a route that cannot be applied stays saved, and the page offers to try again")
    # A rule the offline phone cannot check (its domain list cannot be downloaded) keeps every
    # configuration from passing the core's check.
    open_page(d, "Rules")
    d.tap(text="Add a rule", wait=2)
    d.tap(text="default", wait=2)
    d.tap(text="Domain and subdomains (DOMAIN-SUFFIX)", wait=1.5)
    d.tap(text="Domain category (GEOSITE)", wait=1.5)
    d.tap(rid="px_rule_pattern")
    d.type("google")
    d.tap(text="Choose a target", wait=2)
    d.tap(text="Direct", wait=1.5)
    d.tap(text="Save and apply", wait=3)
    s = wait_task(d, "Add a rule to default")
    note(f"the rule: {s}")
    check(s.startswith("Failed"), "the core's check refuses it")

    route_page(d, PROBE)
    d.tap(d.scroll_to(text="Proxy group"), wait=2)
    d.tap(d.scroll_to(text="US - Auto"), wait=3)
    failed = "The route of Probe is saved but not applied"
    until(lambda: d.find(text=failed), "the page says the route is saved but not applied", 90)
    retry = summary(d, failed)
    note(retry)
    check("Tap to try again" in retry, "and offers to try again")
    check(d.find(text="org.lujarch.probe · Proxy: US - Auto"), "the route is saved")
    d.shot("route-not-applied")
    d.tap(text=failed, wait=3)
    time.sleep(5)
    again = until(lambda: d.find(contains="Tap to try again"),
                  "trying again while the cause stays fails again and offers it again", 90)
    note(again.text)
    answer = apps.reach(f"{WEB_NAME}:80")
    check("UnknownHost" in answer, f"meanwhile the running proxy keeps the previous route, Bypass ({answer})")

    step("the cause removed, the route is in effect")
    open_page(d, "Rules")
    d.tap(d.scroll_to(text="default"), wait=3)
    d.tap(d.scroll_to(text="google"), wait=2)
    d.tap(text="Remove", wait=2)
    d.tap(d.find(text="Remove", rid="button1"), wait=3)
    s = wait_task(d, "Remove the rule for google")
    check(s.startswith("Rule for google removed and applied"), f"the rule is removed ({s})")
    route_page(d, PROBE)
    row = d.find(contains="Tap to try again")
    if row:
        d.tap(row, wait=3)
    until(lambda: not d.find(contains="Tap to try again") and not d.find(contains="Applying"),
          "the page no longer says the route is not applied", 90)
    d.shot("route-applied-again")
    answer = apps.reach(f"{WEB_NAME}:80")
    check(via(answer, "Dallas 1"), f"the probe uses its group ({answer})")


run(main)
