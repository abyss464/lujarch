"""Linux is the home screen's other face: the terminal holds sessions as tabs, made with +, switched
by a tap and ended from a tab's long press, each a shell of the owner's user; the keyboard types
into the session on screen, keys and a keyboard's composing and committing alike; a swipe from the
bottom turns to Android's home and the sessions go on.

The test installs Arch Linux from the archive named by LUJARCH_LINUX_ARCHIVE (an archive of the
device's architecture) when Linux is not installed yet. Every step's screen goes into one sheet.

verifies: LNX-3
timeout: 3600
"""
import os
import subprocess
import time
from pathlib import Path

from lujarch_test import OUT, check, device, install_probe, note, run, share_near, step

LAUNCHER = "com.android.launcher3"
TERMINAL = f"-a org.lujarch.linux.action.TERMINAL -n {LAUNCHER}/.uioverrides.QuickstepLauncher"
IME = "org.lujarch.probe.ime/.Keyboard"
USER = "tester"
ARCHIVE = os.environ.get("LUJARCH_LINUX_ARCHIVE", "")


def linux_pid(d):
    """A process in Linux, through whose root Linux's own files are read."""
    for line in d.sh("ps -A -Z -o LABEL,PID", check=False).splitlines():
        if "lujarch_linux_arch" in line:
            return line.split()[-1]
    return None


def marker(d, name, timeout=8):
    """What a command in a session wrote into Linux's /tmp/<name>."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pid = linux_pid(d)
        if pid:
            out = d.sh(f"cat /proc/{pid}/root/tmp/{name} 2>/dev/null", check=False).strip()
            if out:
                return out
        time.sleep(0.5)
    return ""


def install(d):
    """Arch Linux installed from the archive in Download and started for USER, as the owner does it
    on the page of the Linux development environment."""
    check(ARCHIVE and Path(ARCHIVE).is_file(), "an archive to install (LUJARCH_LINUX_ARCHIVE)")
    name = Path(ARCHIVE).name
    d.push(ARCHIVE, f"/sdcard/Download/{name}")
    d.sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE "
         f"-d file:///sdcard/Download/{name}", check=False)
    d.start("-a org.lujarch.linux.action.LINUX")
    if not d.find(text="Select a file"):
        use = d.switch_of("Use Linux development environment")
        if not use.checked:
            d.tap(use)
        d.tap(d.scroll_to(text="Install the root file system"))
        time.sleep(2)
    if not d.find(text=name):
        d.tap(d.wait(text="Download"))
    d.tap(d.wait(text=name, timeout=30))
    d.shot("install-user")
    field = next(n for n in d.ui() if n.cls.endswith("EditText"))
    d.tap(field)
    d.type(USER)
    for label in ("Install", "OK", "Continue"):
        if d.find(text=label):
            d.tap(text=label)
            break
    for label in ("Install", "OK", "Replace"):
        if d.find(text=label):
            d.tap(text=label)
    deadline = time.monotonic() + 1800
    while not d.find(text=f"Running for {USER}"):
        check(time.monotonic() < deadline, "Linux runs for the new user")
        time.sleep(10)
    d.shot("installed")


BUTTONS = {"New session", "Display", "Settings"}


def tab_nodes(d):
    """The tab row's sessions, left to right."""
    nodes = d.ui()
    plus = d.find(desc="New session", nodes=nodes)
    if plus is None:
        return []
    row = [n for n in nodes if abs(n.y - plus.y) < 60 and n.desc and n.desc not in BUTTONS]
    return sorted(row, key=lambda n: n.x)


def tabs(d):
    return [n.desc for n in tab_nodes(d)]


def run_in(d, command, typed_by_ime=False):
    """A command typed into the session on screen and entered."""
    if typed_by_ime:
        d.sh(f"am broadcast -a org.lujarch.probe.ime.TYPE --es compose {quote_arg(command)} "
             "--es commit 1", check=False)
        time.sleep(0.5)
        d.sh("am broadcast -a org.lujarch.probe.ime.TYPE --es key 66", check=False)
    else:
        d.type(command)
        d.key(66)
    time.sleep(1)


def quote_arg(s):
    return "'" + s.replace("'", "'\\''") + "'"


def no_android_bars(path):
    """Android's status bar draws white icons where Linux's status line has grey text."""
    white = share_near(path, (255, 255, 255), tolerance=60, box=(0, 0, 1080, 90), step=3)
    note(f"white in the status line's strip: {white:.4f}")
    return white < 0.004


def timed(label, action, until, timeout=10):
    start = time.monotonic()
    action()
    while not until():
        if time.monotonic() - start > timeout:
            note(f"{label}: not done in {timeout} s")
            return None
        time.sleep(0.2)
    took = time.monotonic() - start
    note(f"{label}: {took:.1f} s")
    return took


def new_session(d):
    """+, then the menu's Arch Linux: the row above Arch Linux as root, which only the menu has."""
    d.tap(desc="New session", wait=0.5)
    root = d.wait(text="Arch Linux as root")
    item = max((n for n in d.ui() if n.text == "Arch Linux" and n.y < root.y), key=lambda n: n.y)
    d.tap(item, wait=0.5)


def sheet():
    shots = sorted(p for p in OUT.glob("[0-9][0-9]-*.png"))
    if shots:
        out = OUT / "sheet.png"
        subprocess.run(["magick", "montage", *map(str, shots), "-tile", "6x", "-geometry",
                        "360x800+6+6", "-title", "LNX-3 terminal", "-label", "%t", str(out)],
                       check=False)
        note(f"sheet {out}")


def main():
    d = device()
    d.unlock()
    try:
        if d.sh("ls /data/lujarch/linux/os/root/usr/bin/bash", check=False).strip() == "":
            step("Arch Linux installed from an archive")
            install(d)

        step("the terminal opens in the home screen's place with a session")
        # Sessions of an earlier run end with the daemon; the terminal then offers a new one.
        d.sh("setprop ctl.restart lujarch_linux")
        time.sleep(3)
        deadline = time.monotonic() + 120
        while d.prop("init.svc.lujarch_linux") != "running":
            check(time.monotonic() < deadline, "the daemon runs again", quiet=True)
            time.sleep(2)
        time.sleep(5)
        d.start(TERMINAL, wait=3)
        if d.find(text="Arch Linux as root"):
            d.key(4)
        if not tabs(d):
            d.tap(d.wait(text="New session"), wait=2)
        check(no_android_bars(d.shot("terminal")), "Android's bars are hidden")
        names = tabs(d)
        note(f"tabs {names}")
        check(names, "a session's tab shows")

        step("keys typed reach the session on screen")
        run_in(d, "echo keys > /tmp/lnx-keys")
        check(marker(d, "lnx-keys") == "keys", "the shell ran what the keys typed")

        step("a keyboard's composing and committing reach the session")
        install_probe("ime")
        d.sh(f"ime enable {IME}; ime set {IME}", check=False)
        d.tap_xy(540, 1200, wait=1.5)
        run_in(d, "echo composed > /tmp/lnx-ime", typed_by_ime=True)
        d.shot("typed-by-keyboard")
        check(marker(d, "lnx-ime") == "composed", "the shell ran what the keyboard committed")
        cjk = "\u4e2d\u6587"
        run_in(d, f"echo {cjk} > /tmp/lnx-cjk", typed_by_ime=True)
        check(marker(d, "lnx-cjk") == cjk, "committed CJK text reaches the shell")

        step("+ makes a new session, which shows at once and takes input")
        before = len(tabs(d))
        timed("a new session's tab", lambda: new_session(d), lambda: len(tabs(d)) > before)
        d.shot("second-session")
        check(len(tabs(d)) == before + 1, "a second tab")
        run_in(d, "echo second > /tmp/lnx-second", typed_by_ime=True)
        check(marker(d, "lnx-second") == "second", "the new session takes the keyboard's input")

        step("a tap on a tab switches to its session, which takes input")
        d.tap(tab_nodes(d)[0])
        d.shot("first-again")
        run_in(d, "echo first > /tmp/lnx-first", typed_by_ime=True)
        check(marker(d, "lnx-first") == "first", "the first session takes input after the switch")

        step("sessions made and switched quickly keep answering")
        for i in range(3):
            new_session(d)
        names = tabs(d)
        note(f"tabs {names}")
        for n in tab_nodes(d):
            d.tap(n, wait=0.3)
        run_in(d, "echo quick > /tmp/lnx-quick", typed_by_ime=True)
        d.shot("many-sessions")
        check(marker(d, "lnx-quick") == "quick", "the session on screen answers after quick switches")

        step("a session ended from its tab goes, and the one on screen takes input")
        count = len(tabs(d))
        last = tab_nodes(d)[-1]
        d.sh(f"input swipe {last.x} {last.y} {last.x} {last.y} 900")
        time.sleep(1)
        d.shot("session-sheet")
        timed("an ended session's tab", lambda: d.tap(text="End session", wait=0.3),
              lambda: len(tabs(d)) == count - 1)
        d.shot("session-ended")
        check(len(tabs(d)) == count - 1, "its tab goes")
        run_in(d, "echo after-end > /tmp/lnx-after-end", typed_by_ime=True)
        check(marker(d, "lnx-after-end") == "after-end", "the session on screen takes input")

        step("a swipe from the bottom turns to Android's home; the terminal comes back with its sessions")
        d.swipe(540, 2390, 540, 1400, 300)
        time.sleep(1.5)
        d.shot("android-home")
        d.start(TERMINAL, wait=2)
        check(no_android_bars(d.shot("terminal-back")), "Android's bars are hidden again")
        check(len(tabs(d)) == count - 1, "the sessions go on")
        run_in(d, "echo back > /tmp/lnx-back", typed_by_ime=True)
        check(marker(d, "lnx-back") == "back", "the session on screen takes input after coming back")
    finally:
        d.sh("ime reset", check=False)
        sheet()


if __name__ == "__main__":
    run(main)
