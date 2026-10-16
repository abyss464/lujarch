"""Mode: Rule (each connection goes where the app's route and the rules send it), Global (every
connection uses one node, named in Exit node for Global, which opens its group) or Direct (no
node, the traffic still passes through the proxy); the mode can be chosen while the proxy is off
and is used from its next start; while the running proxy uses another mode than the chosen one, a
row says so and switches it on a tap.

verifies: PRX-6
timeout: 1500
"""
import time

from lujarch_test import check, device, note, run, step

from _proxy import (ROUTER, WEB_NAME, Apps, Fixtures, direct_router, proxy_page, setup, status,
                    summary, use_proxy, via, wait_status, until)


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        rule(d, fx, apps)
        global_mode(d, fx, apps)
        direct(d, fx, apps)
        while_off(d, apps)
        differs(d, apps)
    finally:
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def chosen(d):
    proxy_page(d)
    nodes = d.ui()
    out = None
    for mode in ("Rule", "Global", "Direct"):
        t = d.find(text=mode, nodes=nodes)
        radio = [n for n in nodes if n.checkable and t and abs(n.y - t.y) < 80 and n.x < 300]
        if radio and radio[0].checked:
            out = mode
    return out


def choose(d, mode):
    proxy_page(d)
    d.tap(d.scroll_to(text=mode), wait=3)


def traffic(fx, apps):
    since = time.time()
    got = {"probe": apps.reach(f"{WEB_NAME}:80"), "peer": apps.peer_reach(f"{WEB_NAME}:80"),
           "probe to the router": apps.reach(f"{ROUTER}:80")}
    note(f"{got}; nodes asked: {fx.via(since)}")
    return got, fx.via(since)


def set_probe_route(d, group):
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:org.lujarch.probe")
    d.tap(d.scroll_to(text="Proxy"), wait=3)
    d.tap(d.scroll_to(text="Proxy group"), wait=2)
    d.tap(d.scroll_to(text=group), wait=3)
    until(lambda: d.find(text="Applied"), "the route is applied", 90, 2)


def rule(d, fx, apps):
    step("Rule (the default): the app's route and the rules decide")
    check(chosen(d) == "Rule", "Rule is chosen")
    set_probe_route(d, "US - Auto")
    got, _ = traffic(fx, apps)
    check(via(got["probe"], "Dallas 1"), "the probe goes where its route sends it")
    check(via(got["peer"], "Tokyo 1"), "the peer goes where the final rule sends it")
    proxy_page(d)
    d.shot("mode-rule")


def global_mode(d, fx, apps):
    step("Global: every connection uses the node named in Exit node for Global")
    choose(d, "Global")
    s = wait_status(d, "Running · Global", timeout=30)
    check(chosen(d) == "Global", "Global is chosen")
    row = d.scroll_to(text="Exit node for Global")
    note(f"Exit node for Global: {summary(d, 'Exit node for Global')}")
    d.tap(row, wait=3)
    check(d.find(text="GLOBAL"), "the row opens the GLOBAL group")
    d.tap(d.scroll_to(text="Tokyo 2"), wait=3)
    proxy_page(d)
    d.scroll_to(text="Exit node for Global")
    exit_node = summary(d, "Exit node for Global")
    d.shot("mode-global")
    check(exit_node == "Tokyo 2", f"the row names the node ({exit_node})")
    got, _ = traffic(fx, apps)
    for what, answer in got.items():
        check(via(answer, "Tokyo 2"), f"{what}: Tokyo 2")


def direct(d, fx, apps):
    step("Direct: no connection uses a node; the traffic still passes through the proxy")
    choose(d, "Direct")
    wait_status(d, "Running · Direct", timeout=30)
    check(chosen(d) == "Direct", "Direct is chosen")
    d.shot("mode-direct")
    got, asked = traffic(fx, apps)
    check(not asked, "no node is asked")
    check(direct_router(got["probe to the router"]), "the router answers directly")
    check(got["probe"].startswith("connected") and "UnknownHost" not in got["probe"],
          "the name was looked up by the proxy, which took the connection")


def while_off(d, apps):
    step("chosen while the proxy is off: used from its next start")
    use_proxy(d, False)
    choose(d, "Global")
    check(chosen(d) == "Global", "Global can be chosen while off")
    check(status(d) == "Off", "the proxy stays off")
    d.shot("mode-chosen-off")
    s = use_proxy(d, True)
    check(s.startswith("Running · Global"), f"it starts in Global ({s})")
    answer = apps.reach(f"{WEB_NAME}:80")
    check(via(answer, "Tokyo 2"), f"Global's node carries the probe ({answer})")


def differs(d, apps):
    step("the running proxy uses another mode than the chosen one: a row says so and switches it")
    # Test setup: the chosen mode changes where the running proxy does not learn of it, as when a
    # change reached the saved settings but not the running proxy.
    d.sh("sed -i 's/^mode: .*/mode: direct/' /data/lujarch/proxy/manager/config.yaml")
    proxy_page(d)
    def row_shown():
        found = d.find(contains="The saved mode is")
        if not found:
            proxy_page(d)
        return found
    row = until(row_shown, "a row says the modes differ", 30)
    note(f"{row.text}: {summary(d, row.text)}")
    check(row.text == "The saved mode is Direct; the running proxy uses Global", "it names both modes")
    d.shot("mode-differs")
    d.tap(row, wait=3)
    s = wait_status(d, "Running · Direct", timeout=30)
    check(not d.find(contains="The saved mode is"), "the row is gone")
    check(chosen(d) == "Direct", "the running proxy uses the chosen mode")
    d.shot("mode-switched")


run(main)
