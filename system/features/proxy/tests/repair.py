"""After the phone changes network and the network stays the same for 10 seconds, the proxy checks
that a request through its interface gets through; when it does not while the proxy's own request
does, it restarts once and the status shows it; when the proxy's own request fails too it does not
restart and checks again every 30 seconds, at most 10 times; a restart that did not repair the
path is not repeated; with Repair after network changes off it never restarts on its own.

The network changes are the virtual device's two Wi-Fi networks; a path broken after the change is
the proxy's routing rules removed from the phone, as a stale route after a change leaves it.

verifies: PRX-4
timeout: 2700
"""
import time

from lujarch_test import check, device, note, quote, run, step

from _proxy import (WEB_NAME, Apps, Fixtures, advanced, core_pid, details, join, open_page,
                    proxy_page, setup, until, via, wait_state, wait_status)

def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        repaired(d, fx, apps)
        repair_off(d, apps)
        node_down(d, fx)
        not_repeated(d, apps)
    finally:
        join(d, "VirtWifi")
        d.put_setting("global", "lujarch_proxy_repair", 1)
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def last_restart(d):
    open_page(d, "Status")
    time.sleep(4)
    return details(d).get("Last restart on its own", "")


def break_path(d, seconds=0):
    """Test setup: the proxy's routing rules removed, once or for `seconds` (removed again as soon
    as they come back)."""
    drop = ("for p in $(ip rule | grep 'lookup 2022' | cut -d: -f1); do "
            "ip rule del priority $p 2>/dev/null; done")
    if seconds:
        loop = f"end=$(($(date +%s)+{seconds})); while [ $(date +%s) -lt $end ]; do {drop}; sleep 0.2; done"
        d.sh(f"setsid sh -c {quote(loop)} >/dev/null 2>&1 < /dev/null &")
    else:
        d.sh(drop)


def other_network(d):
    return "VirtWifi" if 'connected to "VirtWifi2"' in d.sh("cmd wifi status") else "VirtWifi2"


def path_works(apps):
    return via(apps.reach(f"{WEB_NAME}:80"), "Tokyo 1")


def repaired(d, fx, apps):
    step("no restart on its own yet")
    check(last_restart(d) == "None", "the status shows none")
    pid = core_pid(d)

    step("a network change that leaves the path broken while the proxy's own request works: one restart")
    join(d, other_network(d))
    break_path(d)
    check(not path_works(apps), "the apps' path through the interface is broken")
    restart = until(lambda: core_pid(d) not in (pid, None) and core_pid(d), "the proxy restarts its core", 90)
    note(f"core {pid} -> {restart}")
    wait_state(d, "running", timeout=60)
    reason = last_restart(d)
    note(reason)
    d.shot("repaired")
    check(reason.startswith("Repaired the path after a network change · "), "the status shows the restart and why")
    until(lambda: path_works(apps), "the path works again", 60, 3)


def repair_off(d, apps):
    step("Repair after network changes off: no restart on its own")
    proxy_page(d)
    switch = d.switch_of("Repair after network changes")
    check(switch.checked, "it is on by default")
    d.tap(switch)
    check(d.setting("global", "lujarch_proxy_repair") == "0", "it is off")
    d.shot("repair-off")
    before = last_restart(d)
    pid = core_pid(d)
    join(d, other_network(d))
    break_path(d)
    time.sleep(45)
    check(core_pid(d) == pid, "the core was not restarted")
    check(last_restart(d) == before, "the status shows no new restart")
    check(not path_works(apps), "the path stays broken")
    advanced(d, "Restart proxy", confirm="Restart")
    proxy_page(d)
    wait_status(d, "Running", timeout=60)
    proxy_page(d)
    d.tap(d.switch_of("Repair after network changes"))
    check(d.setting("global", "lujarch_proxy_repair") == "1", "on again")
    until(lambda: path_works(apps), "the path works after the restart", 60, 3)


def node_down(d, fx):
    step("the proxy's own request fails too: no restart, a check every 30 s, at most 10")
    before = last_restart(d)
    pid = core_pid(d)
    # Test setup: every node fails its connectivity checks, so no group finds one that works.
    nodes = list(fx.nodes.values())
    since = time.time()
    try:
        for node in nodes:
            node.checks_fail = True
        join(d, other_network(d))
        time.sleep(6 * 60)
    finally:
        for node in nodes:
            node.checks_fail = False
    checks = sorted(at for node in nodes for at in node.own_checks if at >= since)
    rounds = []
    for at in checks:
        if not rounds or at - rounds[-1] > 15:
            rounds.append(at)
    gaps = [round(b - a) for a, b in zip(rounds, rounds[1:])]
    note(f"{len(rounds)} checks through the node, {gaps} s apart")
    check(core_pid(d) == pid, "the core was not restarted")
    check(last_restart(d) == before, "the status shows no new restart")
    check(len(rounds) == 10, "10 checks")
    check(all(25 <= g <= 40 for g in gaps), "30 s apart")
    # The groups test their members again with the next start.
    advanced(d, "Restart proxy", confirm="Restart")
    proxy_page(d)
    wait_status(d, "Running", timeout=60)


def not_repeated(d, apps):
    step("a restart that did not repair the path is not repeated")
    pid = core_pid(d)
    join(d, other_network(d))
    break_path(d, seconds=60)
    first = until(lambda: core_pid(d) not in (pid, None) and core_pid(d), "the proxy restarts once", 90)
    reason = last_restart(d)
    note(f"restarted: {reason}")
    check(reason.startswith("Repaired the path after a network change"), "the repair restart")
    time.sleep(70)
    check(core_pid(d) == first, "the restart is not repeated while the path stays broken")
    check(not path_works(apps), "the path is still broken")
    join(d, other_network(d))
    time.sleep(45)
    check(core_pid(d) == first, "nor after the next network change, before the path has worked again")
    d.shot("not-repeated")
    advanced(d, "Restart proxy", confirm="Restart")
    proxy_page(d)
    wait_status(d, "Running", timeout=60)
    until(lambda: path_works(apps), "the path works after a restart by hand", 60, 3)


run(main)
