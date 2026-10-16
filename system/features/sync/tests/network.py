"""Discovery and connections holds global discovery and its servers, local discovery, the listen
addresses, relays and their servers, NAT traversal, the retry interval and the upload and download
limits, with the sync service's own defaults; each list is edited one entry per line and can be
restored to its default.

verifies: SYN-6
timeout: 1200
"""
import re
import time

from _sync import HOME, fill, open_sync, reset, summary_of, turn_on
from lujarch_test import check, device, note, run, step

SWITCHES = {"Global discovery": "globalAnnounceEnabled", "Local discovery": "localAnnounceEnabled",
            "Relays": "relaysEnabled", "NAT traversal": "natEnabled"}


# The engine's defaults: its configuration holds only the values that differ from them.
DEFAULTS = {"globalAnnounceEnabled": ["true"], "localAnnounceEnabled": ["true"],
            "relaysEnabled": ["true"], "natEnabled": ["true"], "listenAddress": ["default"],
            "globalAnnounceServer": ["default"]}


def option(d, name):
    """The engine's value of an option, from its configuration."""
    config = d.sh(f"cat {HOME}/config.xml")
    # The options for every device, not a device's own of the same name.
    options = (re.search(r"<options>(.*?)</options>", config, re.S) or re.search("()", "")).group(1)
    return re.findall(rf"<{name}>([^<]*)</{name}>", options) or DEFAULTS.get(name, [])


def direct(addresses):
    """The listen addresses without the relays' entry, which the Relays rows hold."""
    return [a for a in addresses if not a.startswith(("dynamic+", "relay://"))]


def network_page(d):
    open_sync(d)
    d.tap(d.scroll_to(text="Discovery and connections"))
    check(d.wait(text="Global discovery", timeout=20), "the page shows")


def wait_option(d, name, want, timeout=20):
    deadline = time.monotonic() + timeout
    while option(d, name) != want and time.monotonic() < deadline:
        time.sleep(1)
    return option(d, name)


def main():
    d = device()
    reset(d)
    try:
        turn_on(d)
        step("the sync service's own defaults")
        network_page(d)
        for name, key in SWITCHES.items():
            check(d.switch_of(name).checked and option(d, key) == ["true"], f"{name} is on")
        for row, default in (("Global discovery servers", "Default: the public discovery servers"),
                             ("Listen addresses", "Default: TCP and QUIC on port 22000"),
                             ("Relay servers", "Default: the public relay pool"),
                             ("Upload limit", "Unlimited"), ("Download limit", "Unlimited")):
            d.scroll_to(text=row)
            check(summary_of(d, row) == default, f"{row}: {default}")
        d.scroll_to(text="Retry interval")
        note(f"Retry interval: {summary_of(d, 'Retry interval')}")
        d.scroll_to(text="Global discovery")
        d.shot("network")

        step("each switch is the owner's")
        for name, key in SWITCHES.items():
            d.tap(d.switch_of(name))
            check(wait_option(d, key, ["false"]) == ["false"], f"{name} off")
        d.scroll_to(text="Global discovery servers")
        check("Not used while global discovery is off" in summary_of(d, "Global discovery servers"),
              "the servers say they are not used meanwhile")
        d.shot("network-off")
        for name, key in SWITCHES.items():
            d.tap(d.switch_of(name))
            check(wait_option(d, key, ["true"]) == ["true"], f"{name} on again")

        step("a list, one entry per line, and its default restored")
        d.tap(d.scroll_to(text="Listen addresses"))
        fill(d, "tcp://0.0.0.0:22010\nquic://0.0.0.0:22010")
        d.tap(d.wait(text="OK"))
        typed = ["tcp://0.0.0.0:22010", "quic://0.0.0.0:22010"]
        deadline = time.monotonic() + 20
        while direct(option(d, "listenAddress")) != typed and time.monotonic() < deadline:
            time.sleep(1)
        got = option(d, "listenAddress")
        note(f"listen addresses: {got}")
        check(direct(got) == typed, "the two addresses, the relays' entry kept on its own row")
        d.tap(d.scroll_to(text="Listen addresses"))
        d.tap(d.wait(text="Restore default"))
        deadline = time.monotonic() + 20
        while direct(option(d, "listenAddress")) != ["default"] and time.monotonic() < deadline:
            time.sleep(1)
        check(direct(option(d, "listenAddress")) == ["default"], "the default again")
        d.tap(d.scroll_to(text="Global discovery servers"))
        fill(d, "default-v4")
        d.tap(d.wait(text="OK"))
        check(wait_option(d, "globalAnnounceServer", ["default-v4"]) == ["default-v4"],
              "the discovery servers are the owner's")
        d.tap(d.scroll_to(text="Global discovery servers"))
        d.tap(d.wait(text="Restore default"))
        check(wait_option(d, "globalAnnounceServer", ["default"]) == ["default"], "the default again")

        step("the retry interval and the limits")
        d.tap(d.scroll_to(text="Retry interval"))
        fill(d, "120")
        d.tap(d.wait(text="OK"))
        check(wait_option(d, "reconnectionIntervalS", ["120"]) == ["120"], "Retry interval 120 s")
        d.tap(d.scroll_to(text="Upload limit"))
        fill(d, "100")
        d.tap(d.wait(text="OK"))
        check(wait_option(d, "maxSendKbps", ["100"]) == ["100"], "Upload limit 100 KiB/s")
        d.scroll_to(text="Upload limit")
        check(summary_of(d, "Upload limit") == "100 KiB/s", "the row says so")
    finally:
        reset(d)
        d.home()


run(main)
