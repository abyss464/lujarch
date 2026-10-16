"""Connections lists the proxy's open connections live, newest first, each with its app's name and
icon, its destination and the route it took; the proxy's own connections show as Proxy core and
a user ID without an app as System or its number; a filter shows one app's connections; a
connection's details offer Close connection.

verifies: PRX-13
timeout: 1500
"""
import time

from lujarch_test import check, device, note, quote, run, step

from _proxy import (DNS_IP, HOLD_PORT, PEER, WEB_NAME, Apps, Fixtures, below, open_page, setup,
                    summary, until)

HELD = f"{WEB_NAME}:{HOLD_PORT}"


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        listed(d, apps)
        filtered(d)
        close(d, apps)
        own(d, apps)
    finally:
        d.sh("pkill -f 'nc .*8081' || true", check=False)
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def background(d, command):
    """command on the device, left running after this call returns."""
    d.sh(f"setsid sh -c {quote(command)} >/dev/null 2>&1 < /dev/null &")


def rows_of(d):
    """[(destination, its lines)] of the listed connections, top first."""
    nodes = d.ui()
    out = []
    for n in sorted(nodes, key=lambda n: n.y):
        if n.rid.endswith("title") and ":" in (n.text or "") and n.text.split(":")[-1].isdigit():
            out.append((n.text, below(n, nodes) or ""))
    return out


def connections_page(d):
    open_page(d, "Connections")
    until(lambda: d.find(contains="active connection") or d.find(text="No active connections"),
          "the page lists the connections", 30)


def listed(d, apps):
    step("three held connections: the probe's, the peer's and one of root")
    # The connections stay open while the node holds its answer back, and the broadcasts that
    # order them return only then: they are sent from the background.
    apps.probe.clear()
    background(d, f"am broadcast -f 0x20 -a org.lujarch.probe.DO -n 'org.lujarch.probe/.Probe$Background' "
               f"--es do connect --es to {HELD}")
    time.sleep(2)
    d.sh(f"run-as {PEER} rm -f files/results.jsonl", check=False)
    background(d, f"am broadcast -f 0x20 -a org.lujarch.probe.peer.DO -n {PEER}/.Peer --es to {HELD}")
    time.sleep(2)
    background(d, f"(printf 'GET / HTTP/1.0\\r\\n\\r\\n'; sleep 100) | nc {WEB_NAME} {HOLD_PORT}")
    time.sleep(3)

    step("Connections: newest first, with the app, the destination and the route")
    connections_page(d)
    got = until(lambda: (lambda r: r if len([x for x in r if x[0] == HELD]) >= 3 else None)(rows_of(d)),
                "the three connections are listed", 30)
    for dest, lines in got:
        note(f"{dest}: {lines!r}")
    d.shot("connections")
    held = [lines for dest, lines in got if dest == HELD]
    check(held[0].startswith("System (Root) · TCP"), "the newest, root's, comes first, as System")
    check(held[1].startswith("Probe peer · TCP"), "then the peer's, by its app's name")
    check(held[2].startswith("Probe · TCP"), "then the probe's")
    for lines in held:
        check("Route: JP Residential - Auto → Tokyo 1" in lines, f"each with its route ({lines!r})")


def own(d, apps):
    step("the proxy's own connections show as Proxy core")
    # The proxy's DNS asks a server through a group, and the probe's connections go out
    # directly, for which the proxy looks the name up.
    open_page(d, "Core settings")
    d.tap(d.scroll_to(text="DNS"), wait=3)
    d.scroll_to(text="Servers")
    rows = sorted((n for n in d.ui() if n.text == "Servers"), key=lambda n: n.y)
    d.tap(rows[-1], wait=3)
    d.tap(text="Add", wait=2)
    d.type(f"https://{DNS_IP}/dns-query#JP Residential - Auto")
    d.tap(text="Save and apply", wait=3)
    until(lambda: d.find(contains="added") or d.find(contains="applied"), "the server is added", 60)
    route_direct(d)
    connections_page(d)
    background(d, f"am broadcast -f 0x20 -a org.lujarch.probe.DO -n 'org.lujarch.probe/.Probe$Background' "
               f"--es do connect --es to {WEB_NAME}:80")
    hit = until(lambda: next(((dest, lines) for dest, lines in rows_of(d) if lines.startswith("Proxy core")), None),
                "a connection of the proxy core is listed", 30, 1)
    note(f"{hit}")
    d.shot("connections-core")


def route_direct(d):
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:org.lujarch.probe")
    d.tap(d.scroll_to(text="Proxy"), wait=3)
    d.tap(d.scroll_to(text="Direct"), wait=2)
    until(lambda: d.find(text="Applied"), "the probe's route is Direct", 90)


def filtered(d):
    step("a filter shows one app's connections")
    connections_page(d)
    d.tap(text="Show connections of", wait=2)
    d.tap(d.scroll_to(contains="Probe peer ("), wait=3)
    s = summary(d, "Show connections of")
    note(s)
    check(s.startswith("Probe peer (1 connection"), "the filter names the app and its count")
    got = rows_of(d)
    check(got and all(lines.startswith("Probe peer") for _, lines in got), f"only the peer's are listed ({got})")
    d.shot("connections-filtered")
    d.tap(text="Show connections of", wait=2)
    d.tap(text="All apps", wait=3)


def close(d, apps):
    step("a connection's details offer Close connection, which closes it")
    connections_page(d)
    got = rows_of(d)
    nodes = d.ui()
    probe_row = next(n for n in sorted(nodes, key=lambda n: n.y)
                     if n.text == HELD and (below(n, nodes) or "").startswith("Probe · TCP"))
    d.tap(probe_row, wait=2)
    texts = d.texts()
    note(" | ".join(texts)[:400])
    check("Close connection" in texts, "the details offer Close connection")
    check("App: Probe / org.lujarch.probe" in "\n".join(texts), "the details name the app")
    d.shot("connection-details")
    started = time.monotonic()
    d.tap(text="Close connection", wait=2)
    answer = until(lambda: apps.probe.results().get("connect"), "the probe's connection ends", 30)
    note(f"the probe got {answer!r} {time.monotonic() - started:.0f} s after the close")
    check(answer.startswith("connected") and "HTTP" not in answer, "it was closed before the node answered")
    connections_page(d)
    left = [lines for dest, lines in rows_of(d) if dest == HELD]
    check(not any(lines.startswith("Probe · ") for lines in left), "it is gone from the list")


run(main)
