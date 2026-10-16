"""The core's control interface answers only to a key no app sees, while Lujarch's pages reach it
through the system; Change the control key makes a new key, which the running proxy takes with a
short interruption. The test never reads the key: it sees what an app gets from the interface,
what the pages show, and when the key's file was written.

verifies: PRX-18
timeout: 1200
"""
import re
import time

from lujarch_test import check, device, note, run, step

from _proxy import (WEB_NAME, Apps, Fixtures, open_page, proxy_page, setup, until, via, wait_status)

CONTROLLER = "127.0.0.1:9090"


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        no_app(d, apps)
        change(d, apps)
    finally:
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def pages_work(d):
    open_page(d, "Proxy groups")
    check(until(lambda: d.find(text="JP Residential - Auto"), "the groups load", 30), "Lujarch's page reaches the core")


def no_app(d, apps):
    step("an app that asks the control interface without the key is refused")
    answer = apps.reach(f"{CONTROLLER}")
    note(f"the probe: {answer}")
    check(re.match(r"connected HTTP/1\.[01] 401", answer), "the interface refuses it")
    meta = d.sh("ls -l /data/lujarch/proxy/secret").split()
    note(f"the key's file: {meta[0]} {meta[2]} {meta[3]}")
    check(meta[0] == "-rw-------" and meta[2] == "root", "the key's file is the system's alone")
    pages_work(d)
    d.shot("groups-through-the-system")


def change(d, apps):
    step("Change the control key: asks first, makes a new key, the proxy takes it")
    written = d.sh("stat -c %Y /data/lujarch/proxy/secret").strip()
    open_page(d, "Change the control key")
    question = d.find(contains="A new key is generated")
    check(question, "it asks first and says what happens")
    note(question.text)
    d.shot("control-key-asks")
    d.tap(text="Change", wait=3)
    s = until(lambda: next((t for t in d.texts() if t.startswith("The proxy") and "control key" in t), None),
              "the change finishes", 120)
    note(s)
    check(s in ("The proxy uses the new control key", "The proxy was restarted with the new control key"),
          "the running proxy takes the new key")
    d.shot("control-key-changed")
    time.sleep(1)
    check(d.sh("stat -c %Y /data/lujarch/proxy/secret").strip() != written, "a new key was written")
    proxy_page(d)
    wait_status(d, "Running", timeout=60)
    pages_work(d)
    answer = apps.reach(f"{CONTROLLER}")
    check(re.match(r"connected HTTP/1\.[01] 401", answer), f"apps are still refused ({answer})")
    check(via(apps.reach(f"{WEB_NAME}:80"), "Tokyo 1"), "the proxy carries traffic")


run(main)
