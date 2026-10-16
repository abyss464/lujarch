"""Only on Wi-Fi and Only while charging, both off by default: with Only on Wi-Fi sync runs only
while the phone is on a Wi-Fi network, and while it is not, the service waits and the state says
what it waits for; a change shorter than a few seconds does not stop it. The virtual device's
charger is always connected, so Only while charging is shown met and its switch is checked.

verifies: SYN-2
timeout: 1200
"""
import time

from _sync import engine_running, open_sync, reset, state, summary_of, turn_on, wait_state
from lujarch_test import check, device, note, run, step


def wifi(d, on):
    """Wi-Fi off, or on and joined: the virtual network has no internet, so Android joins it
    again only when asked, as when the owner taps it."""
    d.sh(f"svc wifi {'enable' if on else 'disable'}")
    deadline = time.monotonic() + 60
    while on and 'connected to "VirtWifi"' not in d.sh("cmd wifi status", check=False):
        check(time.monotonic() < deadline, "the phone joins its Wi-Fi network again")
        d.sh("cmd wifi connect-network VirtWifi open", check=False)
        time.sleep(2)


def main():
    d = device()
    reset(d)
    try:
        step("both conditions off by default")
        turn_on(d)
        open_sync(d)
        check(not d.switch_of("Only on Wi-Fi").checked, "Only on Wi-Fi is off")
        check(not d.switch_of("Only while charging").checked, "Only while charging is off")
        d.scroll_to(text="Only while charging")
        check("Now: charging" in summary_of(d, "Only while charging"), "it reads the charger")
        d.shot("conditions")

        step("Only on Wi-Fi: without Wi-Fi the service waits and says for what")
        d.tap(d.switch_of("Only on Wi-Fi"))
        time.sleep(3)
        check(d.setting("global", "lujarch_sync_wifi_only") == "1", "Only on Wi-Fi is on")
        check(state(d) == "running", "on Wi-Fi it runs")
        wifi(d, False)
        check(wait_state(d, "waiting", 60), f"off Wi-Fi it waits ({state(d)})")
        check(not engine_running(d), "the sync service does not run meanwhile")
        open_sync(d)
        service = summary_of(d, "Service")
        note(f"Service: {service}")
        check(service == "Waiting for Wi-Fi", "the state says it waits for Wi-Fi")
        d.scroll_to(text="Only on Wi-Fi")
        check("Now: no Wi-Fi" in summary_of(d, "Only on Wi-Fi"), "the switch says no Wi-Fi is connected now")
        d.shot("waiting-for-wifi")

        step("back on Wi-Fi it runs again")
        wifi(d, True)
        check(wait_state(d, "running", 120), f"it runs again ({state(d)})")

        step("a change shorter than a few seconds does not stop it")
        pid = d.sh("pidof lujarch_sync_engine", check=False).strip()
        # The Wi-Fi link drops and the phone joins again at once, as in a roam.
        d.sh("logcat -c; ip link set wlan0 down; sleep 0.3; ip link set wlan0 up; "
             "cmd wifi connect-network VirtWifi open")
        time.sleep(15)
        events = [float(l.split()[0]) for l in d.sh(
            "logcat -d -v epoch -s wpa_supplicant | grep -E 'CTRL-EVENT-(DIS)?CONNECTED'",
            check=False).splitlines() if l.strip()]
        note(f"Wi-Fi was away {events[-1] - events[0]:.1f} s" if len(events) >= 2 else "no Wi-Fi events")
        check(d.sh("pidof lujarch_sync_engine", check=False).strip() == pid and state(d) == "running",
              "the same service runs on")

        step("Only while charging: met while the charger is connected")
        open_sync(d)
        d.tap(d.switch_of("Only while charging"))
        time.sleep(3)
        check(d.setting("global", "lujarch_sync_charging_only") == "1", "Only while charging is on")
        check(state(d) == "running", "with the charger connected it runs")
    finally:
        wifi(d, True)
        reset(d)
        d.home()


run(main)
