"""Use proxy: off by default; while on, every app's traffic, name lookups included, passes through
the proxy and its nodes; while off, the phone connects directly. The switch is on the Proxy page,
beside the Proxy row of Network & internet and in the Quick Settings tile Proxy (which shows the
state and the mode, asks for unlocking on a locked phone and opens the page on a long press), it
takes effect at once, and the proxy runs again from every start of the phone.

verifies: PRX-1
timeout: 1800
"""
import time

from lujarch_test import Failure, check, device, note, restart, run, step

from _proxy import (ROUTER, WEB_IP, WEB_NAME, Apps, Fixtures, direct_router, proxy_page, setup,
                    status, summary, via, wait_status)

PIN = "1590"


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx, on=False)
        default_off(d, apps)
        page_switch(d, fx, apps)
        network_row(d, apps)
        tile(d, apps)
        every_start(d, fx, apps)
    finally:
        d.sh(f"locksettings clear --old {PIN}", check=False)
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def proxied(apps, node="Tokyo 1"):
    """The probe's name lookup and connection, and the peer's, through the node."""
    return {"probe, by name": apps.reach(f"{WEB_NAME}:80"), "probe, by address": apps.reach(f"{WEB_IP}:80"),
            "peer, by name": apps.peer_reach(f"{WEB_NAME}:80")}


def default_off(d, apps):
    step("a new phone: Use proxy is off and the phone connects directly")
    proxy_page(d)
    check(not d.switch_of("Use proxy").checked, "Use proxy is off")
    check(status(d) == "Off", "the status says Off")
    d.shot("proxy-page-off")
    check(d.setting("global", "lujarch_proxy_enabled") in (None, "0"), "the switch's setting is off")
    direct(apps)


def direct(apps):
    got = proxied(apps)
    for what, answer in got.items():
        note(f"{what}: {answer}")
        check(not answer.startswith("connected HTTP/1.0 200 via"), f"{what}: no node answers")
    check("UnknownHost" in got["probe, by name"] or "error" in got["probe, by name"],
          "the name is looked up outside the proxy, where it does not exist")
    router = apps.reach(f"{ROUTER}:80")
    check(direct_router(router), f"the router answers the probe directly ({router})")


def through(apps, fx, since):
    got = proxied(apps)
    for what, answer in got.items():
        note(f"{what}: {answer}")
        check(via(answer, "Tokyo 1"), f"{what}: the node Tokyo 1 answers")
    asked = fx.via(since).get("Tokyo 1", [])
    check((WEB_NAME, 80) in asked, f"the node was asked for {WEB_NAME} by name: the app's name lookup went "
          f"through the proxy ({asked})")


def page_switch(d, fx, apps):
    step("Proxy page: Use proxy on takes effect at once")
    proxy_page(d)
    d.tap(d.switch_of("Use proxy"))
    started = time.monotonic()
    s = wait_status(d, "Running", timeout=60)
    since = time.time()
    through(apps, fx, since)
    took = time.monotonic() - started
    note(f"running and carrying the apps' traffic {took:.0f} s after the tap")
    check(took < 45, "it takes effect at once")
    d.shot("proxy-page-running")
    check("Rule" in s, f"the status row shows the state and the mode ({s})")

    step("Use proxy off: the phone connects directly at once")
    proxy_page(d)
    d.tap(d.switch_of("Use proxy"))
    wait_status(d, "Off", timeout=30)
    d.shot("proxy-page-off-again")
    since = time.time()
    direct(apps)
    check(not fx.via(since), f"no node was asked while it is off ({fx.via(since)})")


def network_row(d, apps):
    step("Network & internet: the Proxy row shows the state and carries the switch")
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.WIRELESS_SETTINGS")
    d.scroll_to(text="Proxy")
    check(summary(d, "Proxy") == "Off", f"the row says Off ({summary(d, 'Proxy')})")
    row = d.switch_of("Proxy")
    check(not row.checked, "its switch is off")
    d.tap(row)
    deadline = time.monotonic() + 60
    while (summary(d, "Proxy") or "") != "On · Rule" and time.monotonic() < deadline:
        time.sleep(2)
    s = summary(d, "Proxy")
    d.shot("network-row-on")
    check(s == "On · Rule", f"the row says the proxy runs, in Rule mode ({s})")
    check(d.switch_of("Proxy").checked, "its switch is on")
    answer = apps.reach(f"{WEB_NAME}:80")
    check(via(answer, "Tokyo 1"), f"the row's switch turned the proxy on ({answer})")
    d.tap(d.switch_of("Proxy"))
    deadline = time.monotonic() + 30
    while summary(d, "Proxy") != "Off" and time.monotonic() < deadline:
        time.sleep(2)
    check(summary(d, "Proxy") == "Off", "the row's switch turned it off")
    d.shot("network-row-off")


def quick_settings(d):
    d.sh("cmd statusbar expand-settings")
    time.sleep(2)


def find_tile(d):
    """The Proxy tile, on whichever page of Quick Settings holds it."""
    for _ in range(3):
        n = d.find(text="Proxy")
        if n:
            return n
        d.swipe(900, 700, 150, 700)
        time.sleep(1.5)
    check(False, "the Quick Settings tile Proxy")


def tile_state(d):
    """(on, the line under its title) of the tile, as the panel shows it."""
    t = find_tile(d)
    nodes = d.ui()
    toggle = [n for n in nodes if n.checkable and n.bounds[0] <= t.x <= n.bounds[2]
              and n.bounds[1] <= t.y <= n.bounds[3]]
    sub = [n.text for n in nodes if n.text and 0 < n.y - t.y < 80 and abs(n.x - t.x) < 200]
    return bool(toggle and toggle[0].checked), (sub[0] if sub else "")


def wait_tile(d, on, line, timeout=60):
    deadline = time.monotonic() + timeout
    while tile_state(d) != (on, line) and time.monotonic() < deadline:
        time.sleep(2)
    got = tile_state(d)
    check(got == (on, line), f"the tile is {'on' if on else 'off'}{', ' + line if line else ''} ({got})")


def tile(d, apps):
    step("Quick Settings: the tile Proxy, added and made large in the editor")
    d.home()
    quick_settings(d)
    d.tap(desc="Edit order of Quick Settings.", wait=2)
    try:
        add = d.scroll_to(desc="Add Proxy to the last position", quiet=True)
    except Failure:
        # Test setup: the tile was added before; it is removed first, as on a new phone.
        d.tap(d.scroll_to(desc="Proxy"), wait=1.5)
        d.tap(text="Remove", wait=1.5)
        add = d.scroll_to(desc="Add Proxy to the last position")
    d.tap(add, wait=1.5)
    d.tap(desc="Proxy", wait=1.5)
    d.tap(desc="toggle the tile's size", wait=1.5)
    d.shot("tile-editor")
    d.back()
    d.back()

    step("the tile shows the state; a tap turns the proxy on, and the tile shows the mode")
    quick_settings(d)
    wait_tile(d, False, "")
    d.shot("tile-off")
    d.tap(find_tile(d), wait=2)
    wait_tile(d, True, "Rule")
    d.shot("tile-on")
    answer = apps.reach(f"{WEB_NAME}:80")
    check(via(answer, "Tokyo 1"), f"the tile turned the proxy on ({answer})")

    step("a long press opens the Proxy page")
    t = find_tile(d)
    d.swipe(t.x, t.y, t.x, t.y, 1500)
    time.sleep(2.5)
    check(d.find(text="Use proxy"), "the Proxy page opened")
    d.shot("tile-long-press")

    step("locked: a tap asks for unlocking first, then turns the proxy off")
    d.sh(f"locksettings set-pin {PIN}")
    d.key("KEYCODE_SLEEP")
    time.sleep(2)
    d.wake()
    time.sleep(1.5)
    quick_settings(d)
    d.tap(find_tile(d), wait=3)
    d.shot("tile-locked-asks")
    check(d.find(text="Enter your PIN") or d.find(contains="PIN"), "the phone asks for its PIN")
    check(d.setting("global", "lujarch_proxy_enabled") == "1", "the proxy is still on before unlocking")
    d.type(PIN)
    d.key("KEYCODE_ENTER")
    time.sleep(4)
    deadline = time.monotonic() + 30
    while d.setting("global", "lujarch_proxy_enabled") != "0" and time.monotonic() < deadline:
        time.sleep(1)
    check(d.setting("global", "lujarch_proxy_enabled") == "0", "after unlocking the tap turned the proxy off")
    d.sh(f"locksettings clear --old {PIN}")
    quick_settings(d)
    wait_tile(d, False, "")
    d.shot("tile-off-after-unlock")
    d.home()


def every_start(d, fx, apps):
    step("on: the proxy runs again after the phone starts")
    proxy_page(d)
    d.tap(d.switch_of("Use proxy"))
    wait_status(d, "Running", timeout=60)
    restart(d)
    fx.again()
    d.unlock()
    proxy_page(d)
    s = wait_status(d, "Running", timeout=120)
    d.shot("running-after-start")
    note(f"after the start: {s}")
    check(d.switch_of("Use proxy").checked, "Use proxy is still on")
    since = time.time()
    through(apps, fx, since)


run(main)
