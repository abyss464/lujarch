"""The status bar shows a globe while the proxy runs and a globe with an exclamation mark while it
is on but not running (no working configuration, stopped after repeated failures), nothing while
it is off or starting; its switch, Proxy among the status bar's items (Settings > System > Status
bar > Status bar items), hides it and leaves the proxy as it is.

verifies: PRX-19
timeout: 1500
"""
from lujarch_test import check, device, note, run, step

from _proxy import (Fixtures, configure, core_pid, fresh, proxy_page, state, until, use_proxy,
                    wait_state, wait_status)

ON, NOT_RUNNING = "Proxy on", "Proxy not running"


def icon(d):
    """The proxy's icon in the status bar: its description, or None."""
    for n in d.ui(windows=True):
        if n.desc in (ON, NOT_RUNNING):
            return n.desc
    return None


def wait_icon(d, want, timeout=30):
    until(lambda: icon(d) == want, f"the status bar shows {want or 'no proxy icon'}", timeout, 1)
    note(f"the status bar shows {want or 'no proxy icon'}")


def main():
    d = device()
    fx = Fixtures().up()
    try:
        fresh(d)
        off(d)
        not_running(d, fx)
        starting_and_stopped(d)
        switch(d)
    finally:
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def off(d):
    step("off: no proxy icon")
    proxy_page(d)
    d.home()
    wait_icon(d, None)
    d.shot("off-no-icon")


def not_running(d, fx):
    step("on without a working configuration: the globe with an exclamation mark")
    use_proxy(d, True)
    d.home()
    wait_icon(d, NOT_RUNNING)
    d.shot("no-configuration-warning-globe")

    step("running: the globe")
    configure(d, fx)
    proxy_page(d)
    wait_status(d, "Running", timeout=90)
    d.home()
    wait_icon(d, ON)
    d.shot("running-globe")


def starting_and_stopped(d):
    step("starting: no icon")
    d.home()
    for _ in range(3):
        pid = core_pid(d)
        d.sh(f"kill -9 {pid}")
        wait_state(d, "starting", timeout=20)
        if _ < 2:
            wait_state(d, "running", timeout=60)
    # The third exit pauses the core for 8 s.
    check(state(d) == "starting", "the core waits to start again")
    shown = icon(d)
    d.shot("starting-no-icon")
    note(f"while starting: {shown}")
    check(shown is None, "no proxy icon while starting")
    wait_state(d, "running", timeout=60)
    wait_icon(d, ON)

    step("stopped after repeated failures: the globe with an exclamation mark")
    for _ in range(5):
        d.sh(f"kill -9 {core_pid(d)}")
        s = wait_state(d, "starting", "crash-loop", timeout=20)
        if s == "crash-loop":
            break
        wait_state(d, "running", timeout=60)
    check(state(d) == "crash-loop", "the core stays stopped")
    wait_icon(d, NOT_RUNNING)
    d.shot("stopped-warning-globe")
    use_proxy(d, False)
    use_proxy(d, True)
    d.home()
    wait_icon(d, ON)


def system_icons(d):
    """Settings > System > Status bar > Status bar items, where the proxy's icon is an item."""
    d.unlock()
    d.start("-f 0x10008000 -n 'com.android.settings/.Settings$SystemDashboardActivity'")
    d.tap(d.scroll_to(text="Status bar"), wait=2)
    d.tap(d.scroll_to(text="Status bar items"), wait=2)
    d.wait(text="Connectivity")


def switch(d):
    step("Status bar items > Proxy off: the icon goes, the proxy stays")
    system_icons(d)
    sw = d.switch_of("Proxy")
    check(sw.checked, "the icon is on by default")
    d.tap(sw)
    d.shot("status-bar-items-proxy-off")
    d.home()
    wait_icon(d, None)
    check(state(d) == "running", "the proxy still runs")
    d.shot("running-icon-hidden")
    system_icons(d)
    d.tap(d.switch_of("Proxy"))
    d.home()
    wait_icon(d, ON)


run(main)
