"""Logs shows the core's log as it is written, newest first, from the chosen level up (Error,
Warning, Info, Debug; Info by default); when the core records less than the chosen level, the
level row says so and names the core's own level, and nothing below the core's level shows.

verifies: PRX-14
timeout: 1500
"""
import re
import time

from lujarch_test import check, device, note, run, step

from _proxy import WEB_IP, WEB_NAME, Apps, Fixtures, below, open_page, setup, summary, until

LINE = re.compile(r"\d{1,2}:\d{2}(:\d{2})?\s?(AM|PM)? · (Error|Warning|Info|Debug)$")


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        live(d, apps)
        levels(d, fx, apps)
        core_level(d, apps)
    finally:
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def logs_page(d):
    open_page(d, "Logs")
    d.wait(text="Level")


def lines(d):
    """[(level, payload)] of the log lines on screen, top first."""
    nodes = d.ui()
    out = []
    for n in sorted(nodes, key=lambda n: n.y):
        m = LINE.search(n.text or "")
        if m and n.rid.endswith("title"):
            out.append((m.group(3), below(n, nodes) or ""))
    return out


def wait_line(d, want, timeout=30):
    deadline = time.monotonic() + timeout
    while True:
        got = lines(d)
        hit = [i for i, (_, payload) in enumerate(got) if want in payload]
        if hit:
            return got, hit[0]
        if time.monotonic() > deadline:
            check(False, f"a log line about {want} ({got[:4]})")
        time.sleep(2)


def set_level(d, label):
    d.tap(text="Level", wait=2)
    d.tap(text=label, wait=2)
    check((summary(d, "Level") or "").startswith(label), f"the level is {label} ({summary(d, 'Level')})")


def core_log_level(d, label):
    """Core settings > Log level, set to label."""
    open_page(d, "Core settings")
    d.tap(d.scroll_to(text="Log level"), wait=2)
    d.tap(text=label, wait=4)
    until(lambda: summary(d, "Log level") == label, f"the core's log level is {label}", 30)


def live(d, apps):
    step("Logs: the level is Info, and the core's lines come as they are written, newest first")
    logs_page(d)
    check(summary(d, "Level") == "Info", f"the level is Info by default ({summary(d, 'Level')})")
    apps.reach(f"{WEB_NAME}:80")
    time.sleep(1)
    apps.reach(f"{WEB_IP}:80")
    got, first = wait_line(d, WEB_IP)
    _, second = wait_line(d, WEB_NAME)
    note(f"lines: {got[:4]}")
    d.shot("logs-info")
    check(got[first][0] == "Info", "the connection's line is at Info")
    check(first < second, "the newer connection's line is above the older one")


def levels(d, fx, apps):
    step("Warning: a failed connection shows, a working one does not")
    logs_page(d)
    set_level(d, "Warning")
    time.sleep(3)
    fx.nodes["Tokyo 1"].down()
    try:
        apps.reach(f"{WEB_NAME}:80")
        got, i = wait_line(d, WEB_NAME, timeout=40)
        note(f"lines: {got[:3]}")
        check(got[i][0] in ("Warning", "Error"), f"the failure is logged at {got[i][0]}")
    finally:
        fx.nodes["Tokyo 1"].up()
    d.shot("logs-warning")
    apps.reach(f"{WEB_IP}:80")
    time.sleep(6)
    check(not any(WEB_IP in payload for _, payload in lines(d)), "the working connection's Info line is not shown")

    step("Debug while the core records from Info: the level row says so, and no debug line shows")
    set_level(d, "Debug")
    s = summary(d, "Level")
    note(s)
    check(s == "Debug · The proxy records only Info and above (Core settings)", "it names the core's level")
    apps.reach(f"{WEB_IP}:80")
    time.sleep(6)
    check(not any(level == "Debug" for level, _ in lines(d)), "no debug line arrives")
    d.shot("logs-debug-core-info")

    step("the core set to record Debug (Core settings > Log level): its debug lines show")
    core_log_level(d, "Debug")
    logs_page(d)
    check(summary(d, "Level") == "Debug", f"the level row names no limit ({summary(d, 'Level')})")
    apps.reach(f"{WEB_IP}:80")
    until(lambda: any(level == "Debug" for level, _ in lines(d)), "debug lines show", 40, 2)
    d.shot("logs-debug")

    step("Error: the lines below it go, and none below it arrive")
    set_level(d, "Error")
    check(not any(level != "Error" for level, _ in lines(d)), "no line below Error is shown")
    apps.reach(f"{WEB_IP}:80")
    time.sleep(6)
    shown = lines(d)
    note(f"lines: {shown[:3]}")
    check(not any(level != "Error" for level, _ in shown), "no line below Error arrives")
    d.shot("logs-error")


def core_level(d, apps):
    step("the core records only warnings: the level row says so and names the core's level")
    core_log_level(d, "Warning")
    logs_page(d)
    set_level(d, "Info")
    until(lambda: "records only" in (summary(d, "Level") or ""), "the level row explains", 20)
    s = summary(d, "Level")
    note(s)
    check(s == "Info · The proxy records only Warning and above (Core settings)", "it names the core's level")
    apps.reach(f"{WEB_IP}:80")
    time.sleep(6)
    shown = lines(d)
    note(f"lines: {shown[:3]}")
    check(not any(level in ("Info", "Debug") for level, _ in shown), "no line below Warning shows")
    d.shot("logs-core-level")


run(main)
