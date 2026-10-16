"""The sync tests' helpers: a throwaway Syncthing peer on this computer, with its own home, identity
and ports in the test's output folder, that the device reaches through adb reverse and that reaches
the device through adb forward; and the steps on the Sync pages that several tests share.

The peer's identity is made for the tests and thrown away with them.
"""
import json
import os
import re
import shutil
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

from lujarch_test import ADB, SERIAL, Failure, check, note, step

OUT = Path(os.environ.get("LUJARCH_TEST_OUT", "/tmp"))
# The port the device dials to reach a peer; adb reverse carries it to the peer's listener.
PEER_PORT = 22100
# The engine's own listen port on the device.
DEVICE_PORT = 22000
# The sync storage and the engine's home on the device.
HOME = "/data/lujarch/sync"
FOLDERS = f"{HOME}/folders"
SYNC = "-f 0x10008000 -a org.lujarch.sync.action.SYNC"
STATE = "lujarch.sync.state"
SETTING = ("global", "lujarch_sync_enabled")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=60)


class Peer:
    """A Syncthing instance on this computer: another of the owner's devices."""

    def __init__(self, name, port=PEER_PORT):
        self.name, self.port = name, port
        self.home = OUT / f"peer-{name}"
        self.proc = None
        self.forwarded = None

    # -- running

    def start(self):
        if self.home.exists():
            shutil.rmtree(self.home)
        self.home.mkdir(parents=True)
        env = dict(os.environ, STNODEFAULTFOLDER="1", STNOUPGRADE="1")
        subprocess.run(["syncthing", "generate", f"--home={self.home}", "--no-port-probing"],
                       check=True, capture_output=True, env=env, timeout=60)
        self.gui = free_port()
        self.listen = free_port()
        self.key = re.search(r"<apikey>([^<]+)</apikey>",
                             (self.home / "config.xml").read_text()).group(1)
        self.log = open(self.home / "syncthing.log", "w")
        self.proc = subprocess.Popen(
            ["syncthing", "serve", f"--home={self.home}", "--no-browser", "--no-restart",
             f"--gui-address=http://127.0.0.1:{self.gui}"],
            stdout=self.log, stderr=subprocess.STDOUT, env=env)
        deadline = time.monotonic() + 60
        while True:
            try:
                self.id = self.get("/rest/system/status")["myID"]
                break
            except OSError:
                if time.monotonic() > deadline or self.proc.poll() is not None:
                    raise
                time.sleep(0.5)
        self.patch("/rest/config/options", {
            "listenAddresses": [f"tcp://127.0.0.1:{self.listen}"],
            "globalAnnounceEnabled": False, "localAnnounceEnabled": False,
            "relaysEnabled": False, "natEnabled": False, "urAccepted": -1,
            "crashReportingEnabled": False, "autoUpgradeIntervalH": 0,
            "reconnectionIntervalS": 5,
        })
        self.put("/rest/config/devices/" + self.id, dict(self.get("/rest/config/devices/" + self.id),
                                                         name=self.name))
        adb("reverse", f"tcp:{self.port}", f"tcp:{self.listen}")
        note(f"peer {self.name}: {self.id[:7]}, reached by the device at tcp://127.0.0.1:{self.port}")
        return self

    def stop(self):
        adb("reverse", "--remove", f"tcp:{self.port}")
        if self.forwarded:
            adb("forward", "--remove", f"tcp:{self.forwarded}")
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.proc = None

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()

    # -- its REST interface

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"http://127.0.0.1:{self.gui}{path}", data=data, method=method,
                                     headers={"X-API-Key": self.key,
                                              "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
        return json.loads(raw) if raw.strip() else None

    def get(self, path):
        return self.call("GET", path)

    def put(self, path, body):
        return self.call("PUT", path, body)

    def patch(self, path, body):
        return self.call("PATCH", path, body)

    def post(self, path, body=None):
        return self.call("POST", path, body)

    def delete(self, path):
        return self.call("DELETE", path)

    # -- as the owner uses it

    def reach_device(self, device_id, name="Phone"):
        """Knows the phone by its ID and dials it through adb forward."""
        self.forwarded = free_port()
        adb("forward", f"tcp:{self.forwarded}", f"tcp:{DEVICE_PORT}")
        self.put(f"/rest/config/devices/{device_id}", {
            "deviceID": device_id, "name": name,
            "addresses": [f"tcp://127.0.0.1:{self.forwarded}"],
        })

    def share(self, folder_id, label, devices, path=None):
        """A folder of the peer, shared with the given device IDs."""
        path = Path(path or self.home / "folders" / folder_id)
        path.mkdir(parents=True, exist_ok=True)
        self.put(f"/rest/config/folders/{folder_id}", {
            "id": folder_id, "label": label, "path": str(path), "type": "sendreceive",
            "devices": [{"deviceID": d} for d in devices], "rescanIntervalS": 5,
            "fsWatcherEnabled": False,
        })
        return path

    def connected(self, device_id):
        conns = self.get("/rest/system/connections")["connections"]
        return conns.get(device_id, {}).get("connected", False)

    def pending_devices(self):
        return self.get("/rest/cluster/pending/devices") or {}

    def pending_folders(self):
        return self.get("/rest/cluster/pending/folders") or {}

    def wait(self, what, test, timeout=90, every=1.0):
        deadline = time.monotonic() + timeout
        while True:
            got = test()
            if got or time.monotonic() > deadline:
                return got
            time.sleep(every)


# -- the device

def engine_running(d):
    return bool(d.sh("pidof lujarch_sync_engine", check=False).strip())


def state(d):
    return d.prop(STATE)


def my_device_id(d):
    """The phone's device ID, as its page shows it."""
    open_sync(d)
    deadline = time.monotonic() + 30
    while not seek(d, text="Device ID") and time.monotonic() < deadline:
        time.sleep(1)
    return summary_of(d, "Device ID").replace("\n", "").strip()


def open_sync(d, through_settings=False):
    d.unlock()
    if through_settings:
        d.start("-f 0x10008000 -a android.settings.SETTINGS", wait=2)
        d.tap(d.scroll_to(text="Connected devices"))
        d.tap(d.scroll_to(text="Sync"))
    else:
        d.start(SYNC, wait=2)
    check(d.wait(text="Use sync", timeout=20), "the Sync page shows")


def wait_state(d, want, timeout=90):
    deadline = time.monotonic() + timeout
    while state(d) != want and time.monotonic() < deadline:
        time.sleep(1)
    return state(d) == want


def turn_on(d):
    """Use sync on, with a device identity; returns once the engine runs."""
    open_sync(d)
    sw = d.switch_of("Use sync")
    if not sw.checked:
        d.tap(sw)
        time.sleep(2)
    if d.find(text="Set up a device identity to start syncing") or state(d) == "no-identity":
        make_identity(d)
    check(wait_state(d, "running", 120), f"sync runs ({state(d)})")


def turn_off(d):
    open_sync(d)
    sw = d.switch_of("Use sync")
    if sw.checked:
        d.tap(sw)
    check(wait_state(d, "off", 60), f"sync is off ({state(d)})")


def make_identity(d):
    """Device identity > Create a new identity, as on a first start."""
    open_sync(d)
    d.tap(d.scroll_to(text="Device identity"))
    d.tap(d.wait(text="Create a new identity", timeout=20))
    d.tap(d.wait(text="Create", timeout=10))
    time.sleep(3)
    d.back()


def device_files(d, path):
    """The names in a directory of the sync storage on the device."""
    out = d.sh(f"ls -1 '{path}'", check=False)
    return [l for l in out.splitlines() if l and "No such file" not in l]


def reset(d):
    """Sync off and the engine's home emptied, as on a new phone."""
    d.put_setting(*SETTING, None)
    for key in ("wifi_only", "charging_only"):
        d.put_setting("global", f"lujarch_sync_{key}", None)
    deadline = time.monotonic() + 30
    while engine_running(d) and time.monotonic() < deadline:
        time.sleep(1)
    d.sh(f"find {HOME} -mindepth 1 -maxdepth 1 ! -name folders -exec rm -rf {{}} +", check=False)
    d.sh(f"rm -rf {FOLDERS}/*", check=False)


def fill(d, text):
    """Replaces the text of the dialog's input field."""
    deadline = time.monotonic() + 10
    while not (field := next((n for n in d.ui() if n.cls.endswith("EditText")), None)):
        check(time.monotonic() < deadline, "the dialog has a text field")
        time.sleep(0.5)
    d.tap(field)
    d.sh("input keycombination KEYCODE_CTRL_LEFT KEYCODE_A", check=False)
    d.key("KEYCODE_DEL")
    for i, line in enumerate(text.split("\n")):
        if i:
            d.key("KEYCODE_ENTER")
        if line:
            d.type(line)


def add_device(d, peer, name):
    """Add device: its ID typed, a name, and the address the phone reaches it at."""
    open_sync(d)
    d.tap(d.scroll_to(text="Add device"))
    fill(d, peer.id)
    d.tap(d.wait(text="Next"))
    fill(d, name)
    d.tap(d.wait(text="Next"))
    fill(d, f"tcp://127.0.0.1:{peer.port}")
    d.tap(d.wait(text="Add"))
    check(wait_row(d, 20, text=name), f"{name} is listed under Devices")


def seek(d, limit=6, **what):
    """The element scrolled to, or None when the page does not show it."""
    try:
        return d.scroll_to(limit=limit, quiet=True, **what)
    except Failure:
        return None


def wait_row(d, timeout=60, **what):
    """A row of the page once it shows, scrolled to: the page adds rows as the service reports."""
    deadline = time.monotonic() + timeout
    while True:
        found = seek(d, **what)
        if found or time.monotonic() > deadline:
            return found
        time.sleep(2)


def offer(d, label, timeout=90):
    """The request row of a folder another device offers, once the page shows it."""
    open_sync(d)
    # The page redraws its requests as the service reports them: a row can move between reads.
    deadline = time.monotonic() + timeout
    while True:
        found = wait_row(d, max(5, deadline - time.monotonic()), text=label)
        try:
            # summary_of leaves the row in the middle of the screen.
            offered = found and summary_of(d, label).startswith("Shared by") and d.find(text=label)
        except Failure:
            offered = None
        if offered or time.monotonic() > deadline:
            break
        time.sleep(2)
    check(offered, f"{label} is offered under Requests")
    return offered


def accept_folder(d, label, timeout=90):
    """The folder a device offers: Add, then Add shared folder with what the page proposes."""
    d.tap(offer(d, label, timeout))
    d.tap(d.wait(text="Add", timeout=10))
    check(d.wait(text="Folder type", timeout=20), "the folder's page shows")
    # The page's title has the action's words too: the action is the row below.
    d.tap(max((n for n in d.ui() if n.text == "Add shared folder"), key=lambda n: n.y))
    check(wait_row(d, 30, text=label), f"{label} is listed under Folders")


def summary_of(d, title):
    """The first line under the row titled `title`: its summary."""
    lines = d.summary(title)
    return lines[0] if lines else ""
