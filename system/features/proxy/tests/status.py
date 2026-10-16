"""The proxy shows its state (Off, Starting, Running, Running on the last working configuration,
No usable configuration, Stopped after repeated failures), mode and live speed on its status row,
and the details behind it; a failure of the proxy never leaves the phone without a connection:
a configuration that fails the core's check is not started (the last working one runs, or with
none the phone connects directly), an exiting core is started again after a pause that grows from
2 seconds to a minute and stays stopped after 5 exits within 5 minutes, and a core that stops
answering or loses its interface is stopped; the status says which happened.

verifies: PRX-2, PRX-3
timeout: 3000
"""
import re
import time

from lujarch_test import check, device, note, run, step

from _proxy import (ROUTER, WEB_NAME, Apps, Fixtures, advanced, configure, core_pid, details,
                    direct_router, fresh, open_page, proxy_page, state, status, until, use_proxy,
                    via, wait_state, wait_status)


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        no_configuration(d, fx, apps)
        running(d, fx, apps)
        source_fails(d)
        unhealthy(d)
        interface_lost(d)
        fallback(d, apps)
        repeated_exits(d, apps)
        growing_pause(d)
    finally:
        d.sh("for p in $(pidof lujarch_proxy_core); do kill -CONT $p; done; "
             "umount /system_ext/bin/lujarch_proxy_manager 2>/dev/null", check=False)
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def connects_directly(apps):
    by_name = apps.reach(f"{WEB_NAME}:80")
    router = apps.reach(f"{ROUTER}:80")
    note(f"probe: {WEB_NAME}: {by_name}; router: {router}")
    check(not by_name.startswith("connected HTTP/1.0 200 via"), "no node carries the probe's connection")
    check(direct_router(router), "the phone connects directly: the router answers the probe")


def proxied(apps):
    answer = apps.reach(f"{WEB_NAME}:80")
    check(via(answer, "Tokyo 1"), f"the probe's connection goes through the node ({answer})")


def no_configuration(d, fx, apps):
    step("on without any configuration: No usable configuration, and the phone connects directly")
    fresh(d)
    use_proxy(d, True)
    s = status(d)
    check(s.startswith("No usable configuration"), f"the status row says so ({s})")
    d.shot("no-usable-configuration")
    d.tap(text="Status", wait=3)
    rows = details(d)
    note(f"details: {rows}")
    check(rows["Status"].startswith("No usable configuration\n"), "the details say why")
    d.shot("details-no-configuration")
    connects_directly(apps)

    step("a configuration that arrives is tried at once")
    configure(d, fx)
    proxy_page(d)
    s = wait_status(d, "Running", timeout=90)
    check(s.startswith("Running · Rule"), f"the proxy runs it ({s})")
    proxied(apps)


def running(d, fx, apps):
    step("Running: the status row shows the state, the mode and the live speed")
    d.sh(f"curl -s -o /dev/null -m 15 http://{WEB_NAME}/stream >/dev/null 2>&1 &")
    time.sleep(4)
    s = status(d)
    d.shot("status-row-speed")
    note(f"status row: {s}")
    m = re.match(r"Running · Rule · ↑ (\S+ \S+)\s+↓ (\S+ \S+)$", s)
    check(m, f"state, mode, upload and download speed ({s})")
    check(not m.group(2).startswith("0 "), f"the download shows its speed ({m.group(2)})")

    step("the details: core version, interface, IPv6, traffic, configurations, restarts")
    d.tap(text="Status", wait=4)
    time.sleep(8)
    rows = details(d)
    for k, v in rows.items():
        note(f"{k}: {v!r}")
    d.shot("details-running")
    check(rows.get("Status") == "Running", "Status: Running")
    check(rows.get("Mode") == "Rule", "Mode: Rule")
    check(re.match(r"v\d+\.\d+\.\d+", rows.get("Core version", "")), "the core's version")
    check(rows.get("Last restart on its own") == "None", "no restart on its own yet")
    check(rows.get("Virtual network interface (TUN)") == "On · Network stack: Mixed", "the interface and its stack")
    check(rows.get("IPv6") == "Off while the proxy runs", "IPv6 is blocked")
    check(re.match(r"↑ .+↓ ", rows.get("Speed", "")), "the speed")
    check(re.match(r"↑ .+↓ ", rows.get("Traffic since the proxy started", ""))
          and not rows["Traffic since the proxy started"].endswith("↓ 0 B"), "the traffic since the start")
    check(rows.get("Current configuration", "").startswith("Built "), "when the running configuration was built")
    check(rows.get("Last working configuration") == "Same as the current one",
          "the running configuration works and is the last working one")


def source_fails(d):
    step("a detail whose source fails says so, and the others fill in")
    # Test setup: the proxy's manager cannot run, so the configuration rows' source fails.
    d.sh("touch /data/local/tmp/lujarch-no-manager; chmod 000 /data/local/tmp/lujarch-no-manager; "
         "mount -o bind /data/local/tmp/lujarch-no-manager /system_ext/bin/lujarch_proxy_manager")
    try:
        open_page(d, "Status")
        rows = until(lambda: (lambda r: r if r.get("Current configuration") == "Unknown" else None)(details(d)),
                     "the configuration rows say they are unknown", 60)
        note(f"details: {rows}")
        d.shot("details-source-fails")
        texts = " ".join(d.texts())
        check("Couldn't load the configuration files" in texts, "the page says what failed")
        check(rows.get("Status") == "Running" and rows.get("Mode") == "Rule"
              and rows.get("Core version", "").startswith("v"), "the other rows are there")
    finally:
        d.sh("umount /system_ext/bin/lujarch_proxy_manager; rm -f /data/local/tmp/lujarch-no-manager")


def restart_reason(d):
    open_page(d, "Status")
    time.sleep(4)
    return details(d).get("Last restart on its own", "")


def unhealthy(d):
    step("a core that stops answering is stopped after 3 checks and started again")
    pid = core_pid(d)
    d.sh(f"kill -STOP {pid}")
    started = time.monotonic()
    wait_state(d, "starting", timeout=60)
    note(f"stopped {time.monotonic() - started:.0f} s after it stopped answering")
    wait_state(d, "running", timeout=60)
    reason = restart_reason(d)
    d.shot("details-stopped-answering")
    check(reason.startswith("The core stopped answering · "), f"the details say so ({reason})")


def interface_lost(d):
    step("a core whose interface disappears is stopped after 3 checks and started again")
    time.sleep(20)
    d.sh("ip link delete Meta")
    wait_state(d, "starting", timeout=60)
    wait_state(d, "running", timeout=60)
    reason = restart_reason(d)
    d.shot("details-interface-lost")
    check(reason.startswith("The virtual network interface disappeared · "), f"the details say so ({reason})")


def break_config(d, path):
    """Test setup: a configuration the core's check refuses (its first rule names a group that
    does not exist)."""
    d.sh(f"sed -i 's/^rules:$/rules:\\n- MATCH,No-Such-Group/' {path}")


def fallback(d, apps):
    step("the current configuration fails the core's check: the last working one runs")
    break_config(d, "/data/lujarch/proxy/config.yaml")
    advanced(d, "Restart proxy", confirm="Restart")
    proxy_page(d)
    s = wait_status(d, "Running on the last working configuration", "No usable", timeout=90)
    d.shot("running-last-working")
    check(s.startswith("Running on the last working configuration"), f"the status row says so ({s})")
    d.tap(text="Status", wait=4)
    rows = details(d)
    note(f"Status: {rows.get('Status')!r}")
    check("No-Such-Group" in rows.get("Status", ""), "the details say why the current one failed")
    d.shot("details-last-working")
    proxied(apps)

    step("without a working configuration: No usable configuration, the phone connects directly")
    break_config(d, "/data/lujarch/proxy/config.last-good.yaml")
    advanced(d, "Restart proxy", confirm="Restart")
    proxy_page(d)
    s = wait_status(d, "No usable", timeout=90)
    d.shot("no-usable-after-check")
    d.tap(text="Status", wait=4)
    rows = details(d)
    check(rows.get("Status", "").startswith("No usable configuration\n") and "No-Such-Group" in rows["Status"],
          f"the details say why ({rows.get('Status')!r})")
    connects_directly(apps)

    step("Apply configuration builds a working one, which runs at once")
    advanced(d, "Apply configuration", confirm="Apply")
    proxy_page(d)
    wait_status(d, "Running · Rule", timeout=120)
    proxied(apps)


def kill_core(d):
    pid = core_pid(d)
    if not pid:
        check(False, "the core runs")
    d.sh(f"kill -9 {pid}")
    return time.monotonic()


def pause(d):
    """Kills the core; the seconds until it runs again."""
    killed = kill_core(d)
    wait_state(d, "starting", "crash-loop", timeout=20)
    if state(d) == "crash-loop":
        return None
    wait_state(d, "running", timeout=120)
    return time.monotonic() - killed


def repeated_exits(d, apps):
    step("an exiting core starts again after 2, 4, 8 and 16 s; the fifth exit stops it")
    advanced(d, "Restart proxy", confirm="Restart")
    wait_state(d, "running")
    pauses = [pause(d) for _ in range(5)]
    note("pauses: " + ", ".join("stopped" if p is None else f"{p:.1f} s" for p in pauses))
    for p, want in zip(pauses, (2, 4, 8, 16)):
        check(p is not None and want <= p < want + 5, f"a pause of {want} s ({p})")
    check(pauses[4] is None, "the fifth exit within 5 minutes stops the core")
    proxy_page(d)
    s = wait_status(d, "Stopped after repeated failures", timeout=20)
    d.shot("stopped-after-failures")
    d.tap(text="Status", wait=4)
    rows = details(d)
    check(rows.get("Last restart on its own", "").startswith("The core exited · "), "the details say the core exited")
    d.shot("details-stopped-after-failures")
    connects_directly(apps)

    step("Restart proxy ends the stop")
    advanced(d, "Restart proxy", confirm="Restart")
    proxy_page(d)
    wait_status(d, "Running · Rule", timeout=60)
    proxied(apps)

    step("turning the proxy off and on ends it too")
    for _ in range(5):
        if pause(d) is None:
            break
    proxy_page(d)
    wait_status(d, "Stopped after repeated failures", timeout=20)
    use_proxy(d, False)
    s = use_proxy(d, True)
    check(s.startswith("Running"), f"the proxy runs again ({s})")


def growing_pause(d):
    step("exits spread over more than 5 minutes: the pause grows to a minute, the core is not stopped")
    first = time.monotonic()
    pauses = [pause(d) for _ in range(4)]
    wait = 302 - (time.monotonic() - first)
    note(f"pauses 2 to 16 s: {', '.join(f'{p:.1f}' for p in pauses)}; the fifth exit {wait:.0f} s later")
    time.sleep(max(0.0, wait))
    fifth = pause(d)
    note(f"fifth pause: {fifth:.1f} s")
    check(fifth is not None and 32 <= fifth < 40, "the fifth exit, 5 minutes after the first, pauses 32 s")
    killed = kill_core(d)
    wait_state(d, "starting", timeout=20)
    time.sleep(30)
    proxy_page(d)
    s = status(d)
    d.shot("starting-in-the-pause")
    check(s == "Starting", f"during the pause the status says Starting ({s})")
    wait_state(d, "running", timeout=120)
    sixth = time.monotonic() - killed
    note(f"sixth pause: {sixth:.1f} s")
    check(60 <= sixth < 70, "the pause has grown to a minute")


run(main)
