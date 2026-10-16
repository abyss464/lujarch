"""While the proxy runs with its virtual network interface, IPv6 traffic that would go past the
interface is refused, except the proxy's own connections to nodes with IPv6 addresses; the block
is lifted as soon as the proxy stops or its interface is turned off; a change of Block IPv6 while
the proxy runs asks whether to restart the proxy now or at its next start.

The virtual device has no IPv6 route to the internet: past the block, an app's IPv6 connection
fails as unreachable; refused by the block, it fails with permission denied.

verifies: PRX-5
timeout: 1800
"""
import time

from lujarch_test import check, device, note, run, step

from _proxy import (Apps, Fixtures, Node, WEB_NAME, advanced, details, open_page, proxy_page, setup,
                    use_proxy, wait_status, wait_task, until)

V6 = "[2001:db8::80]:80"
V6_NODE = "2001:db8::6"


def v6(apps):
    answer = apps.peer_reach(V6)
    note(f"the peer's IPv6 connection: {answer}")
    return answer


def blocked(answer):
    return "EACCES" in answer or "Permission denied" in answer


def unreachable(answer):
    return "ENETUNREACH" in answer or "unreachable" in answer


def ipv6_row(d):
    open_page(d, "Status")
    time.sleep(4)
    return details(d).get("IPv6", "")


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        running(d, apps)
        node_exception(d, fx, apps)
        stops(d, apps)
        interface_off(d, apps)
        switch(d, apps)
    finally:
        d.put_setting("global", "lujarch_proxy_ipv6_block", 1)
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def running(d, apps):
    step("running: an app's IPv6 traffic is refused")
    row = ipv6_row(d)
    d.shot("status-ipv6-blocked")
    check(row == "Off while the proxy runs", f"the status says so ({row})")
    check(blocked(v6(apps)), "the connection is refused")


def node_exception(d, fx, apps):
    step("the proxy's own connections to a node with an IPv6 address are let through")
    node = Node("Berlin-6", 17716, kind="ss")
    nodes_file = node.yaml().replace("server: 127.0.0.1", f"server: \"{V6_NODE}\"")
    from _proxy import pick_download, put_download
    put_download(d, "lujarch-v6-node.yaml", "proxies:\n" + nodes_file)
    open_page(d, "Subscriptions")
    d.tap(text="Add nodes by hand", wait=2)
    d.tap(rid="px_manual_name")
    d.type("Berlin")
    d.tap(text="Next", wait=2)
    d.tap(text="Choose a file", wait=3)
    pick_download(d, "lujarch-v6-node.yaml")
    d.wait(text="Save and apply", timeout=30)
    d.tap(rid="px_manual_country")
    d.type("DE")
    d.tap(text="Data center")
    d.tap(text="Save and apply", wait=3)
    check(wait_task(d, "Save the nodes of Berlin") == "Nodes saved and applied", "the node is added")
    # Added while the proxy runs: the running proxy takes the node without a restart.
    # The probe's connections go through the IPv6 node; the core logs why each fails.
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:org.lujarch.probe")
    d.tap(d.scroll_to(text="Proxy"), wait=3)
    d.tap(d.scroll_to(text="Proxy group"), wait=2)
    d.tap(d.scroll_to(text="DE - Auto"), wait=3)
    until(lambda: d.find(text="Applied"), "the probe's route is applied", 90, 2)
    open_page(d, "Logs")
    d.tap(text="Level", wait=2)
    d.tap(text="Warning", wait=2)
    time.sleep(3)
    apps.reach(f"{WEB_NAME}:80")
    line = until(lambda: next((n.text for n in d.ui() if V6_NODE in (n.text or "")), None),
                 "the core logs its connection to the IPv6 node", 40)
    note(line)
    d.shot("logs-ipv6-node")
    check("permission denied" not in line.lower(), "the block does not refuse the core's connection to the node")
    check("unreachable" in line.lower(), "it goes on to the network, which has no IPv6 route here")
    check(blocked(v6(apps)), "an app's IPv6 connection is still refused")


def stops(d, apps):
    step("the proxy stops: the block is lifted at once")
    use_proxy(d, False)
    answer = v6(apps)
    check(unreachable(answer) and not blocked(answer), "the connection is no longer refused")
    row = ipv6_row(d)
    check(row == "Available", f"the status says IPv6 is available ({row})")
    use_proxy(d, True)
    check(blocked(v6(apps)), "running again, it is refused again")


def interface_off(d, apps):
    step("the interface turned off: the block is lifted")
    open_page(d, "Core settings")
    d.tap(d.switch_of("Interface"), wait=2)
    d.tap(d.wait(text="Change anyway"), wait=3)
    until(lambda: unreachable(v6(apps)), "the block is lifted with the interface", 60, 3)
    row = ipv6_row(d)
    note(f"IPv6: {row}")
    d.shot("interface-off-ipv6")
    open_page(d, "Core settings")
    d.tap(d.scroll_to(contains="not the recommended value"), wait=2)
    d.tap(d.wait(text="Restore the recommended values"), wait=3)
    proxy_page(d)
    wait_status(d, "Running", timeout=60)
    until(lambda: blocked(v6(apps)), "with the interface back, IPv6 is refused again", 60, 3)


def switch(d, apps):
    step("Block IPv6 off: the change asks to restart now or at the next start")
    open_page(d, "Core settings")
    d.tap(d.switch_of("Block IPv6 while the proxy runs"), wait=2)
    check(d.find(text="Save and restart the proxy") and d.find(text="Save only"), "it asks when to take effect")
    d.shot("ipv6-block-asks")
    d.tap(text="Save only", wait=3)
    check(blocked(v6(apps)), "saved only: the running proxy still blocks")
    advanced(d, "Restart proxy", confirm="Restart")
    proxy_page(d)
    wait_status(d, "Running", timeout=60)
    answer = v6(apps)
    check(unreachable(answer) and not blocked(answer), "from its next start it does not block")
    row = ipv6_row(d)
    check(row == "Not blocked: IPv6 bypasses the proxy", f"the status says so ({row})")
    d.shot("ipv6-not-blocked")

    step("Block IPv6 on, with a restart now")
    open_page(d, "Core settings")
    d.tap(d.switch_of("Block IPv6 while the proxy runs"), wait=2)
    d.tap(text="Save and restart the proxy", wait=3)
    proxy_page(d)
    wait_status(d, "Running", timeout=60)
    until(lambda: blocked(v6(apps)), "blocked again after the restart", 40, 3)
    check(ipv6_row(d) == "Off while the proxy runs", "the status says so")


run(main)
