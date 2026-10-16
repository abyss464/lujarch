"""A sync service that exits is started again after a growing pause; after 5 exits within 5 minutes
it stays stopped until sync is turned off and on, and the state says so. Without a usable
configuration the service does not start, and the state says so.

verifies: SYN-3
timeout: 1200
"""
import time

from _sync import HOME, open_sync, reset, state, summary_of, turn_off, turn_on, wait_state
from lujarch_test import check, device, note, run, step


def engine(d):
    return d.sh("pidof lujarch_sync_engine", check=False).strip()


def crash(d):
    """Ends the running service as a crash does; returns how long until it runs again, or None
    when it does not within 90 s."""
    pid = engine(d)
    d.sh(f"kill -9 {pid}")
    start = time.monotonic()
    while time.monotonic() - start < 90:
        now = engine(d)
        if now and now != pid and state(d) == "running":
            return time.monotonic() - start
        if state(d) == "crash-loop":
            return None
        time.sleep(0.25)
    return None


def main():
    d = device()
    reset(d)
    try:
        step("a service that exits starts again after a growing pause")
        turn_on(d)
        pauses = []
        for _ in range(4):
            pauses.append(crash(d))
            # Long enough for the started service to settle before it ends again.
            time.sleep(3)
        note(f"pauses before it ran again: {[round(p, 1) if p else p for p in pauses]}")
        check(all(pauses), "it ran again after every exit")
        check(pauses[0] < pauses[1] < pauses[2] < pauses[3], "each pause is longer than the last")

        step("the fifth exit within 5 minutes: it stays stopped and says so")
        check(crash(d) is None and wait_state(d, "crash-loop", 30), f"it stays stopped ({state(d)})")
        time.sleep(10)
        check(not engine(d), "it does not start again by itself")
        open_sync(d)
        check(summary_of(d, "Service") == "Stopped after repeated crashes", "the state says it stopped")
        d.shot("crash-loop")

        step("off and on: it runs again")
        turn_off(d)
        turn_on(d)

        step("without a usable configuration it does not start and says so")
        turn_off(d)
        d.sh(f"mv {HOME}/config.xml {HOME}/config.xml.test-aside")
        open_sync(d)
        d.tap(d.switch_of("Use sync"))
        check(wait_state(d, "no-config", 30), f"it does not start ({state(d)})")
        check(not engine(d), "the service does not run")
        open_sync(d)
        check(summary_of(d, "Service") == "No device identity", "the state says what it lacks")
        check(d.find(text="Set up a device identity to start syncing"), "and how to get one")
        d.shot("no-configuration")
    finally:
        d.sh(f"[ -f {HOME}/config.xml.test-aside ] && mv {HOME}/config.xml.test-aside {HOME}/config.xml",
             check=False)
        reset(d)
        d.home()


run(main)
