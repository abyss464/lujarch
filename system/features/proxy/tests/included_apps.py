"""Apps that use the proxy: with All apps every app enters the proxy but those set to Bypass the
proxy entirely; with apps turned on in the list only they enter it, every other app connects
directly, and an app on the list still follows its route.

verifies: PRX-8
timeout: 1500
"""
import time

from lujarch_test import check, device, note, run, step

from _proxy import (ROUTER, WEB_NAME, Apps, Fixtures, direct_router, open_page, proxy_page, setup,
                    summary, until, via)

PEER = "org.lujarch.probe.peer"


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        all_apps(d, fx, apps)
        listed(d, fx, apps)
    finally:
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def both(apps):
    got = {"probe": apps.reach(f"{WEB_NAME}:80"), "peer": apps.peer_reach(f"{WEB_NAME}:80"),
           "peer router": apps.peer_reach(f"{ROUTER}:80")}
    note(f"{got}")
    return got


def include_page(d):
    """Proxy > Apps > Apps that use the proxy."""
    open_page(d, "Apps that use the proxy")
    until(lambda: d.find(contains=" on the list"), "the page shows its list", 30)


def probe_switch(d):
    """The Probe's switch on Apps that use the proxy, found with the page's search."""
    d.tap(desc="Search", wait=2)
    d.type("Probe")
    time.sleep(2)
    nodes = d.ui()
    title = next(n for n in nodes if n.text == "Probe" and n.rid.endswith("id/title"))
    row = [n for n in nodes if n.checkable and abs(n.y - title.y) < 160]
    check(row, "a switch beside Probe")
    return min(row, key=lambda n: abs(n.y - title.y))


def set_route(d, package, choice, group=None):
    d.unlock()
    d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{package}")
    d.tap(d.scroll_to(text="Proxy"), wait=3)
    d.tap(d.scroll_to(text=choice), wait=2)
    if group:
        d.tap(d.scroll_to(text=group), wait=3)
    until(lambda: d.find(text="Applied"), f"{choice} is applied", 90, 2)


def all_apps(d, fx, apps):
    step("All apps (the default): every app enters the proxy")
    proxy_page(d)
    row = d.scroll_to(text="Apps that use the proxy")
    section = d.find(text="Apps")
    check(section and section.y < row.y, "the Proxy page's Apps section holds Apps that use the proxy")
    note(f"Apps that use the proxy: {summary(d, 'Apps that use the proxy')}")
    d.shot("proxy-apps")
    include_page(d)
    check(summary(d, "All apps") == "Turn on apps below to limit the proxy to them", "the page is on All apps")
    check(d.find(text="Apps · 0 on the list"), "no app is on the list")
    d.shot("all-apps")
    got = both(apps)
    check(via(got["probe"], "Tokyo 1") and via(got["peer"], "Tokyo 1"), "both apps go through the node")

    step("All apps, but an app set to Bypass the proxy entirely stays out")
    set_route(d, PEER, "Bypass the proxy entirely")
    # The interface's rules for the app follow the applied route within moments.
    time.sleep(5)
    got = both(apps)
    check(via(got["probe"], "Tokyo 1"), "the probe goes through the node")
    check("UnknownHost" in got["peer"] and direct_router(got["peer router"]), "the peer connects directly")
    set_route(d, PEER, "Follow the rules")


def listed(d, fx, apps):
    step("Probe turned on in the list: only it enters the proxy")
    include_page(d)
    d.tap(probe_switch(d), wait=3)
    until(lambda: d.find(contains="1 on the list"), "the list holds Probe", 90, 2)
    include_page(d)
    s = summary(d, "Only 1 app") or ""
    note(f"the page: Only 1 app · {s}")
    check(s == "Every app that is not on the list connects directly, outside the proxy", "the page says only it uses the proxy")
    d.shot("only-probe")
    got = both(apps)
    check(via(got["probe"], "Tokyo 1"), "the probe, on the list, goes through the node")
    check("UnknownHost" in got["peer"], "the peer's name lookup stays outside the proxy")
    check(direct_router(got["peer router"]), "the peer connects directly")

    step("an app on the list still follows its route")
    set_route(d, "org.lujarch.probe", "Proxy group", "US - Auto")
    answer = apps.reach(f"{WEB_NAME}:80")
    check(via(answer, "Dallas 1"), f"the probe goes through its group's node ({answer})")
    d.shot("listed-app-route")

    step("the list emptied: every app enters the proxy again")
    include_page(d)
    d.tap(probe_switch(d), wait=3)
    until(lambda: d.find(contains="0 on the list"), "the list is empty", 90, 2)
    d.shot("all-apps-again")
    got = both(apps)
    check(via(got["peer"], "Tokyo 1"), "the peer goes through the node again")


run(main)
