"""Core settings holds every setting of the core (log level, connections, app recognition, IPv6,
the virtual network interface, group testing, domain sniffing, DNS, country and domain lists);
each change is saved and applied to the running proxy; the five values the proxy needs show their
recommended value while they differ, a change away from it first says what breaks, and while any
differs a row at the top names them and restores them all with one tap.

verifies: PRX-16
timeout: 1800
"""
import time

from lujarch_test import check, device, note, run, step

from _proxy import (WEB_NAME, Apps, Fixtures, advanced, details, open_page, proxy_page, setup,
                    summary, until, via, wait_status, wait_task)

ROWS = ("Log level", "Unified delay", "Concurrent connections", "Find the app of each connection",
        "IPv6 in the proxy", "Block IPv6 while the proxy runs", "Network stack", "Packet size (MTU)",
        "Interface", "Routes into the interface", "Detect the network for the proxy's own connections",
        "Redirected DNS", "Group testing", "Adaptive groups", "Domain sniffing", "DNS",
        "Country and domain lists")
MIXED = "Mixed (system for TCP, gVisor for UDP)"


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        every_setting(d)
        applied(d)
        risky(d, apps)
    finally:
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def row(d, title):
    """The summary of the row titled `title`, scrolled into view."""
    d.scroll_to(text=title)
    return summary(d, title)


def choose(d, title, value):
    """The row titled `title`, set to `value` in its dialog (tapped again while the page still
    settles after a change)."""
    for _ in range(3):
        d.tap(d.scroll_to(text=title), wait=2)
        if d.find(text=value):
            d.tap(text=value, wait=3)
            return
    check(False, f"{title} offers {value}")


def core_page(d):
    open_page(d, "Core settings")
    d.wait(text="Log level", timeout=30)


def every_setting(d):
    step("Core settings holds every setting of the core")
    core_page(d)
    d.shot("core-settings")
    for name in ROWS:
        check(d.scroll_to(text=name), f"{name}: {summary(d, name)}")
    core_page(d)
    d.tap(d.scroll_to(text="DNS"), wait=3)
    check(d.find(text="Mode") or d.scroll_to(text="Mode"), "DNS has its own page")
    d.shot("core-dns")


def applied(d):
    step("a change is saved and applied to the running proxy: the network stack")
    core_page(d)
    choose(d, "Network stack", "gVisor")
    s = wait_task(d, "Change Network stack")
    check(s == "Network stack changed and applied", f"applied ({s})")
    check(row(d, "Network stack") == "gVisor", "the row shows it")
    open_page(d, "Status")
    rows = until(lambda: (lambda r: r if r.get("Virtual network interface (TUN)", "").endswith("gVisor") else None)(details(d)),
                 "the running proxy uses it", 60)
    note(f"interface: {rows['Virtual network interface (TUN)']}")
    d.shot("stack-applied")
    advanced(d, "Restart proxy", confirm="Restart")
    proxy_page(d)
    wait_status(d, "Running", timeout=60)
    open_page(d, "Status")
    time.sleep(4)
    check(details(d).get("Virtual network interface (TUN)") == "On · Network stack: gVisor", "it is saved for the next start")
    core_page(d)
    choose(d, "Network stack", MIXED)
    wait_task(d, "Change Network stack")
    check(row(d, "Network stack") == MIXED, "the recommended stack is back")


def risk_row(d):
    core_page(d)
    for _ in range(3):
        d.swipe(540, 700, 540, 1900, 300)
    return d.find(contains="not the recommended value")


def risky(d, apps):
    step("a value the proxy needs: turning it off first says what it breaks")
    check(not risk_row(d), "with the recommended values there is no row about them")
    d.tap(d.switch_of("Interface"), wait=2)
    warning = d.find(contains="Apps reach the proxy only through this interface")
    check(warning, "the question says what turning the interface off breaks")
    note(warning.text)
    d.shot("risk-question")
    d.tap(text="Change anyway", wait=3)
    s = wait_task(d, "Change Interface")
    check(s == "Interface changed and applied", f"applied ({s})")
    line = row(d, "Interface") or ""
    note(line)
    check("not recommended (recommended: On)" in line, "the row shows the recommended value")
    answer = apps.reach(f"{WEB_NAME}:80")
    check(not answer.startswith("connected HTTP/1.0 200 via"), f"applied: the apps' traffic no longer enters the proxy ({answer})")

    step("a second one: the routes into the interface")
    core_page(d)
    d.tap(d.switch_of("Routes into the interface"), wait=2)
    check(d.find(contains="Without these routes no traffic enters the interface"), "it says what it breaks")
    d.tap(text="Change anyway", wait=3)
    wait_task(d, "Change Routes into the interface")

    step("a row at the top names them and restores them with one tap")
    top = risk_row(d)
    check(top and top.text == "2 settings are not the recommended value", f"the row counts them ({top and top.text})")
    names = summary(d, top.text) or ""
    note(names)
    check("Interface" in names and "Routes into the interface" in names, "it names them")
    d.shot("risk-row")
    d.tap(top, wait=2)
    d.tap(d.wait(text="Restore the recommended values"), wait=3)
    s = wait_task(d, "Restore the recommended values")
    check(s == "Recommended values restored", f"restored ({s})")
    check(not risk_row(d), "the row is gone")
    check(d.switch_of("Interface").checked, "the interface is on")
    check("not recommended" not in (row(d, "Interface") or ""), "its row no longer names a recommendation")
    d.shot("risk-restored")
    proxy_page(d)
    wait_status(d, "Running", timeout=60)
    answer = until(lambda: (lambda a: a if via(a, "Tokyo 1") else None)(apps.reach(f"{WEB_NAME}:80")),
                   "the apps' traffic enters the proxy again", 60, 3)
    note(answer)


run(main)
