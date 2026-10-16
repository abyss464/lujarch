"""The library of Lujarch's tests (process/test-flow.md): the device a test runs on, as a person
uses it, and the steps and checks a test is made of.

`tools/lujarch test` runs each test with this directory on PYTHONPATH and these variables:
LUJARCH_ADB (adb), LUJARCH_SERIAL (the device), LUJARCH_TEST_OUT (the test's own output
directory: its log, screenshots and files), LUJARCH_TOOL (tools/lujarch), LUJARCH_ROOT (the
repository), LUJARCH_TREE (the source tree), LUJARCH_PROBES (built probes), LUJARCH_PRODUCT_OUT
(the vm build's product directory), LUJARCH_DEVICES (each device's build record, <device>.json)
and LUJARCH_SDK (the Android SDK, for probes).
"""
import contextlib
import hashlib
import html
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

ADB = os.environ.get("LUJARCH_ADB", "adb")
SERIAL = os.environ.get("LUJARCH_SERIAL", "127.0.0.1:6520")
OUT = Path(os.environ.get("LUJARCH_TEST_OUT", tempfile.mkdtemp(prefix="lujarch-test-")))
TOOL = os.environ.get("LUJARCH_TOOL", "")
ROOT = Path(os.environ.get("LUJARCH_ROOT", Path(__file__).resolve().parents[2]))
SDK = Path(os.environ.get("LUJARCH_SDK", os.path.expanduser("~/Android/Sdk")))
OUT.mkdir(parents=True, exist_ok=True)

_shots = 0


class Failure(Exception):
    pass


def step(message):
    print(f"- {message}", flush=True)


def note(message):
    print(f"  {message}", flush=True)


def check(condition, message, quiet=False):
    """A check of what the person sees or the system does; a failed one ends the test with a
    screenshot of the moment (quiet: no screenshot, for a check a test catches)."""
    if quiet and not condition:
        raise Failure(message)
    if condition:
        note(f"ok: {message}")
        return
    try:
        device().shot("failed")
    except Exception as e:  # the failure stays the test's result
        note(f"no screenshot of the failure: {e}")
    raise Failure(message)


def run(main):
    """Runs a test's main(); exit 0 when every check holds."""
    try:
        main()
    except Failure as e:
        print(f"FAILED: {e}", flush=True)
        sys.exit(1)
    print("passed", flush=True)


# ---------------------------------------------------------------------------
# The device


def plain(text):
    """Android writes apostrophes and hyphens typographically (Don\u2019t, Wi\u2011Fi); tests
    write them plainly."""
    return text.replace("\u2019", "'").replace("\u2011", "-")


class Node:
    """One element of the screen, as the accessibility tree describes it."""

    def __init__(self, attrs):
        self.text = plain(attrs.get("text", ""))
        self.desc = plain(attrs.get("content-desc", ""))
        self.rid = attrs.get("resource-id", "")
        self.cls = attrs.get("class", "")
        self.package = attrs.get("package", "")
        self.checked = attrs.get("checked") == "true"
        self.checkable = attrs.get("checkable") == "true"
        self.enabled = attrs.get("enabled") == "true"
        self.selected = attrs.get("selected") == "true"
        self.password = attrs.get("password") == "true"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", attrs.get("bounds", "[0,0][0,0]")))
        self.bounds = (x1, y1, x2, y2)
        self.x, self.y = (x1 + x2) // 2, (y1 + y2) // 2

    def __repr__(self):
        return f"<{self.cls.rsplit('.', 1)[-1]} {self.text or self.desc or self.rid!r} {self.bounds}>"


def _attrs(tag):
    return {k: html.unescape(v) for k, v in re.findall(r'([\w-]+)="([^"]*)"', tag)}


class Device:
    def __init__(self, serial=SERIAL):
        self.serial = serial

    # -- adb

    def adb(self, *args, timeout=120, check=True, input=None):
        r = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, text=True,
                           timeout=timeout, input=input)
        if check and r.returncode:
            raise Failure(f"adb {' '.join(args)[:200]}: {(r.stderr or r.stdout).strip()[:400]}")
        return r.stdout

    def sh(self, command, timeout=120, check=True):
        r = subprocess.run([ADB, "-s", self.serial, "shell", command], capture_output=True,
                           text=True, timeout=timeout)
        if check and r.returncode:
            raise Failure(f"on the device: {command[:200]}: exit {r.returncode}: "
                          f"{(r.stderr or r.stdout).strip()[:400]}")
        return r.stdout

    def sh_rc(self, command, timeout=120):
        r = subprocess.run([ADB, "-s", self.serial, "shell", command], capture_output=True,
                           text=True, timeout=timeout)
        return r.returncode, r.stdout + r.stderr

    def push(self, local, remote):
        self.adb("push", str(local), remote, timeout=600)

    def pull(self, remote, local):
        self.adb("pull", remote, str(local), timeout=600)

    def install(self, apk, grant=False):
        args = ["install", "-r", "-t"] + (["-g"] if grant else []) + [str(apk)]
        self.adb(*args, timeout=600)

    def uninstall(self, package):
        self.adb("uninstall", package, check=False)

    # -- system state

    def prop(self, name):
        return self.sh(f"getprop {name}").strip()

    def setting(self, table, key):
        v = self.sh(f"settings get {table} {key}").strip()
        return None if v == "null" else v

    def put_setting(self, table, key, value):
        if value is None:
            self.sh(f"settings delete {table} {key}", check=False)
        else:
            self.sh(f"settings put {table} {key} {quote(str(value))}")

    @contextlib.contextmanager
    def settings_kept(self, *keys):
        """Restores each (table, key) to its value before the block."""
        before = [(t, k, self.setting(t, k)) for t, k in keys]
        try:
            yield
        finally:
            for t, k, v in before:
                self.put_setting(t, k, v)

    def appop(self, package, op):
        out = self.sh(f"cmd appops get {package} {op}", check=False)
        m = re.search(r":\s*(\w+)", out)
        return m.group(1) if m else out.strip()

    def logcat_clear(self):
        self.sh("logcat -c", check=False)

    def logcat(self, *tags):
        spec = " ".join(f"{t}:V" for t in tags) + (" *:S" if tags else "")
        return self.sh(f"logcat -d -v brief {spec}", check=False)

    def denials(self):
        """SELinux denials since boot, without the kernel's timestamps."""
        out = self.sh("dmesg | grep 'avc:  denied' || true", check=False)
        return sorted({re.sub(r"^.*?avc:", "avc:", l) for l in out.splitlines() if l.strip()})

    def package_installed(self, package):
        return f"package:{package}" in self.sh(f"pm list packages {package}", check=False).split()

    # -- the screen

    def wake(self):
        self.sh("svc power stayon true; input keyevent KEYCODE_WAKEUP", check=False)

    def unlock(self):
        self.wake()
        self.sh("wm dismiss-keyguard", check=False)
        time.sleep(0.8)

    def home(self):
        self.sh("input keyevent KEYCODE_HOME", check=False)
        time.sleep(0.8)

    def back(self):
        self.sh("input keyevent KEYCODE_BACK", check=False)
        time.sleep(0.8)

    def drawer(self):
        """The launcher's app drawer, opened as a person opens it."""
        self.home()
        # A slow drag: the virtual device draws slowly enough that a quick fling's speed is
        # misread and the drawer falls back.
        self.swipe(540, 1900, 540, 300, 1200)
        time.sleep(1.5)

    def restart_framework(self, timeout=240):
        """Restarts zygote, system_server and the apps, as a reboot does for them, and waits
        until the phone is usable again."""
        self.sh("stop; start")
        time.sleep(5)
        deadline = time.monotonic() + timeout
        while self.sh("getprop sys.boot_completed", check=False).strip() != "1" or \
                self.sh_rc("cmd input keyevent KEYCODE_WAKEUP")[0] != 0:
            check(time.monotonic() < deadline, "the framework starts again")
            time.sleep(3)
        time.sleep(15)
        self.unlock()

    def key(self, code):
        self.sh(f"input keyevent {code}", check=False)

    def start(self, args, wait=2.0):
        """`am start` with these arguments (a string)."""
        out = self.sh(f"am start -W {args}", check=False)
        if "Error" in out or "Exception" in out:
            raise Failure(f"am start {args}: {out.strip()[:300]}")
        time.sleep(wait)
        return out

    def stop(self, package):
        self.sh(f"am force-stop {package}", check=False)

    def top(self):
        out = self.sh("dumpsys activity activities | grep -m1 topResumedActivity", check=False)
        m = re.search(r"u0 (\S+)/(\S+)", out)
        return (m.group(1), m.group(2)) if m else (None, None)

    def _screen(self, windows):
        """The screen as the screen reader probe (test/probes/ui) answers it, started on the
        first call; None when it cannot run. It reads pages that never fall still, which
        uiautomator's dump refuses."""
        out = self._reader(b"windows\n" if windows else b"active\n")
        return out if out and "<hierarchy" in out else None

    def _reader(self, command, timeout=10):
        """The screen reader probe's answer to a command, the probe installed as its source is
        now and started on the first call; None when it cannot run."""
        port = 27100 + int(self.serial.rsplit(":", 1)[-1]) - 6520

        def ask():
            with contextlib.suppress(OSError):
                with socket.create_connection(("127.0.0.1", port), timeout=timeout) as c:
                    c.sendall(command)
                    data = b""
                    while chunk := c.recv(65536):
                        data += chunk
                if data:
                    return data.decode("utf-8", "replace")
            return None

        apk = probe("ui")
        if getattr(self, "_reader_apk", None) == apk and (out := ask()) is not None:
            return out
        stamp = "/data/local/tmp/lujarch-ui.apk-name"
        if self.sh(f"cat {stamp}", check=False).strip() != apk.name or \
                not self.package_installed("org.lujarch.probe.ui"):
            self.sh("am force-stop org.lujarch.probe.ui", check=False)
            self.install(apk)
            self.sh(f"echo {apk.name} > {stamp}")
        self._reader_apk = apk
        if (out := ask()) is not None:
            return out
        self.adb("forward", f"tcp:{port}", "localabstract:lujarch-ui", check=False)
        subprocess.Popen([ADB, "-s", self.serial, "shell",
                          "am instrument -w org.lujarch.probe.ui/.Server"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
        for _ in range(15):
            time.sleep(1)
            if (out := ask()) is not None:
                return out
        return None

    def drag(self, x1, y1, x2, y2, hold=1.0, move=0.8):
        """A finger pressed at x1, y1 for hold seconds, moved to x2, y2 over move seconds,
        resting there a moment, then lifted, as one gesture."""
        done = self._reader(f"drag {x1} {y1} {x2} {y2} {int(hold * 1000)} {int(move * 1000)}\n"
                            .encode(), timeout=30)
        check(done and done.strip() == "ok", f"dragged from {x1}, {y1} to {x2}, {y2}")
        time.sleep(1)

    def ui(self, tries=3, windows=False):
        """The screen's elements: of the focused window, or with windows of every window (the
        status bar, the shade, the navigation bar and the app)."""
        for _ in range(tries):
            out = self._screen(windows)
            if out:
                return [Node(_attrs(t)) for t in re.findall(r"<node [^>]*>", out)]
            time.sleep(1)
        flag = "--windows " if windows else ""
        for _ in range(tries):
            out = self.sh(f"uiautomator dump {flag}/data/local/tmp/ui.xml >/dev/null 2>&1; "
                          "cat /data/local/tmp/ui.xml", check=False)
            if "<hierarchy" in out:
                return [Node(_attrs(t)) for t in re.findall(r"<node [^>]*>", out)]
            time.sleep(1)
        return []

    def texts(self):
        return [n.text for n in self.ui() if n.text]

    def find(self, text=None, desc=None, rid=None, contains=None, nodes=None):
        for n in nodes if nodes is not None else self.ui():
            if text is not None and n.text != text:
                continue
            if desc is not None and n.desc != desc:
                continue
            if rid is not None and not n.rid.endswith(rid):
                continue
            if contains is not None and contains not in n.text and contains not in n.desc:
                continue
            return n
        return None

    def wait(self, timeout=15, **what):
        deadline = time.monotonic() + timeout
        while True:
            n = self.find(**what)
            if n:
                return n
            if time.monotonic() > deadline:
                check(False, f"on screen: {what}")
            time.sleep(1)

    def gone(self, timeout=15, **what):
        deadline = time.monotonic() + timeout
        while self.find(**what):
            if time.monotonic() > deadline:
                check(False, f"no longer on screen: {what}")
            time.sleep(1)

    def tap(self, node=None, wait=1.2, **what):
        n = node or self.find(**what)
        if n is None:
            n = self.scroll_to(**what)
        if not what and (n.text or n.desc):
            what = {"text": n.text} if n.text else {"desc": n.desc}
        if what:
            n = self.middle(n, **what)
        self.sh(f"input tap {n.x} {n.y}")
        time.sleep(wait)
        return n

    def middle(self, n, **what):
        """The element found by `what`, brought from under the status bar or the gesture area,
        where a tap lands on them and its row can be cut off, to the middle of the screen."""
        if 100 < n.y < 2150:
            return n
        self.swipe(540, 1200 + (n.y - 1200) // 2, 540, 1200 - (n.y - 1200) // 2, 600)
        return self.find(**what) or n

    def tap_xy(self, x, y, wait=1.0):
        self.sh(f"input tap {x} {y}")
        time.sleep(wait)

    def swipe(self, x1, y1, x2, y2, ms=300):
        self.sh(f"input swipe {x1} {y1} {x2} {y2} {ms}")
        time.sleep(0.8)

    def scroll_to(self, limit=12, quiet=False, **what):
        """Scrolls the page down, then up, until the element shows."""
        for direction in ((540, 1800, 540, 700), (540, 700, 540, 1800)):
            last = None
            for _ in range(limit):
                nodes = self.ui()
                n = self.find(nodes=nodes, **what)
                if n:
                    return n
                sig = [x.text for x in nodes]
                if sig == last:
                    break
                last = sig
                self.swipe(*direction)
        check(False, f"on the page: {what}", quiet=quiet)

    def summary(self, title, reach=160):
        """The lines under the row titled `title` (its state), the row scrolled into the middle."""
        row = self.middle(self.scroll_to(text=title), text=title)
        return [n.text for n in self.ui() if n.text and 0 < n.y - row.y < reach]

    def switch_of(self, title):
        """The switch in the row titled `title`, found as the checkable element nearest to the
        title on the same row."""
        t = self.find(text=title) or self.scroll_to(text=title)
        self.middle(t, text=title)
        nodes = self.ui()
        t = self.find(text=title, nodes=nodes)
        row = [n for n in nodes if n.checkable and abs(n.y - t.y) < 160]
        check(row, f"a switch beside “{title}”")
        return min(row, key=lambda n: abs(n.y - t.y))

    def type(self, text):
        escaped = text.replace("%", "%%").replace(" ", "%s")
        self.sh(f"input text {quote(escaped)}")
        time.sleep(0.5)

    def shot(self, name):
        """What the display shows (the device's composited screen), saved in the test's
        output as <n>-<name>.png."""
        global _shots
        _shots += 1
        path = OUT / f"{_shots:02d}-{re.sub(r'[^a-z0-9-]+', '-', name.lower())}.png"
        if TOOL:
            r = subprocess.run([TOOL, "vm", "screenshot", str(path)], capture_output=True,
                               text=True, timeout=120)
            if r.returncode == 0 and path.is_file():
                note(f"screenshot {path.name}")
                return path
        data = subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"],
                              capture_output=True, timeout=60).stdout
        path.write_bytes(data)
        note(f"screenshot {path.name} (screencap)")
        return path


_device = None


def device():
    global _device
    if _device is None:
        _device = Device()
    return _device


def quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


# ---------------------------------------------------------------------------
# Screenshots: pixels of the PNGs the display gives (8-bit RGB or RGBA, not interlaced).


def png(path):
    """(width, height, rows): rows of (r, g, b) tuples."""
    import struct
    import zlib
    data = Path(path).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise Failure(f"{path} is not a PNG")
    pos, idat, width = 8, b"", 0
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or ctype not in (2, 6) or interlace:
                raise Failure(f"{path}: not 8-bit RGB(A)")
        elif kind == b"IDAT":
            idat += body
        pos += 12 + length
    channels = 4 if ctype == 6 else 3
    raw = zlib.decompress(idat)
    stride = width * channels
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(height):
        f, line = raw[i], bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b, c = prev[x], prev[x - channels] if x >= channels else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append([tuple(line[x:x + 3]) for x in range(0, stride, channels)])
        prev = line
    return width, height, rows


def share_near(path, rgb, tolerance=40, box=None, step=4):
    """The share of sampled pixels within tolerance of rgb, in box (x1, y1, x2, y2)."""
    w, h, rows = png(path)
    x1, y1, x2, y2 = box or (0, 0, w, h)
    hits = total = 0
    for y in range(y1, min(y2, h), step):
        for x in range(x1, min(x2, w), step):
            total += 1
            if sum(abs(p - q) for p, q in zip(rows[y][x], rgb)) <= tolerance:
                hits += 1
    return hits / total if total else 0.0


def hex_rgb(value):
    v = value.lstrip("#")[-6:]
    return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))


# ---------------------------------------------------------------------------
# Probes: small apps that act as an app would, built from test/probes/<name>/ with the SDK's
# own tools and installed only while a test runs.

PROBES = ROOT / "test" / "probes"
TEST_KEY = "build/make/target/product/security/testkey"


def sdk_tool(name):
    """A tool of the newest build-tools of the Android SDK."""
    tools = sorted((SDK / "build-tools").iterdir(),
                   key=lambda p: [int(x) for x in re.findall(r"\d+", p.name)])
    return str(tools[-1] / name)


def _platform_jar():
    platforms = sorted((SDK / "platforms").iterdir(),
                       key=lambda p: [int(x) for x in re.findall(r"\d+", p.name)])
    return str(platforms[-1] / "android.jar")


def probe(name, package=None):
    """The probe's APK, built when its sources changed; with package, installed under that
    package name (an app the system treats by its name)."""
    src = PROBES / name
    files = sorted(p for p in src.rglob("*") if p.is_file())
    digest = hashlib.sha256((package or "").encode())
    for p in files:
        digest.update(str(p.relative_to(src)).encode() + b"\0" + p.read_bytes())
    cache = Path(os.environ.get("LUJARCH_PROBES", OUT.parent.parent / "probes")) / name
    apk = cache / f"{digest.hexdigest()[:16]}.apk"
    if apk.is_file():
        return apk
    cache.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=f"probe-{name}-"))
    try:
        jar = _platform_jar()
        res = src / "res"
        flat = work / "res.zip"
        if res.is_dir():
            subprocess.run([sdk_tool("aapt2"), "compile", "--dir", str(res), "-o", str(flat)],
                           check=True)
        linked = work / "linked.apk"
        gen = work / "gen"
        gen.mkdir()
        cmd = [sdk_tool("aapt2"), "link", "-I", jar, "--manifest", str(src / "AndroidManifest.xml"),
               "-o", str(linked), "--java", str(gen), "--min-sdk-version", "29",
               "--target-sdk-version", "36"]
        if package:
            cmd += ["--rename-manifest-package", package]
        if res.is_dir():
            cmd += [str(flat)]
        subprocess.run(cmd, check=True)
        unsigned = work / "unsigned.apk"
        shutil.copy(linked, unsigned)
        java = [str(p) for p in files if p.suffix == ".java"]
        if java:
            classes = work / "classes"
            classes.mkdir()
            java += [str(p) for p in gen.rglob("*.java")]
            r = subprocess.run(["javac", "-source", "17", "-target", "17", "-nowarn", "-cp", jar,
                                "-d", str(classes), *java], capture_output=True, text=True)
            if r.returncode:
                raise Failure(f"probe {name} does not compile: {r.stderr[-1500:]}")
            dex = work / "dex"
            dex.mkdir()
            subprocess.run([sdk_tool("d8"), "--lib", jar, "--min-api", "29", "--output", str(dex),
                            *[str(p) for p in classes.rglob("*.class")]], check=True)
            with zipfile.ZipFile(unsigned, "a") as z:
                z.write(dex / "classes.dex", "classes.dex")
        aligned = work / "aligned.apk"
        subprocess.run([sdk_tool("zipalign"), "-f", "4", str(unsigned), str(aligned)], check=True)
        tree = Path(os.environ.get("LUJARCH_TREE", os.path.expanduser("~/Code/vendor/lineage-24.0")))
        key = tree / TEST_KEY
        subprocess.run([sdk_tool("apksigner"), "sign", "--key", f"{key}.pk8", "--cert",
                        f"{key}.x509.pem", "--out", str(apk), str(aligned)], check=True)
        return apk
    finally:
        shutil.rmtree(work, ignore_errors=True)


def install_probe(name, grant=False, package=None):
    apk = probe(name, package)
    device().install(apk, grant=grant)
    return apk


def probe_result(package, key, timeout=20):
    """A probe writes what it saw as JSON lines into its files dir; the last value of key."""
    deadline = time.monotonic() + timeout
    while True:
        out = device().sh(f"run-as {package} cat files/results.jsonl 2>/dev/null || "
                          f"cat /data/data/{package}/files/results.jsonl 2>/dev/null",
                          check=False)
        values = [json.loads(l) for l in out.splitlines() if l.startswith("{")]
        hits = [v for v in values if v.get("key") == key]
        if hits:
            return hits[-1].get("value")
        if time.monotonic() > deadline:
            return None
        time.sleep(1)


class AppProbe:
    """The probe app (test/probes/app), driven as an app that reads, asks and installs."""

    PACKAGE = "org.lujarch.probe"
    OPS = ("LUJARCH_SENSORS", "LUJARCH_INSTALLED_APPS", "LUJARCH_DEVICE_IDS", "LUJARCH_AUTOSTART",
           "LUJARCH_PHONE_INFO", "LUJARCH_NETWORK_INFO", "LUJARCH_DEVICE_INFO", "READ_CLIPBOARD")

    def __init__(self, d=None):
        self.d = d or device()

    def install(self):
        """A fresh install, as a person installs an app."""
        self.d.uninstall(self.PACKAGE)
        install_probe("app")

    def allow(self, *ops):
        """Test setup: the probe allowed what the test is not about."""
        for op in ops or self.OPS:
            self.d.sh(f"cmd appops set {self.PACKAGE} {op} allow")

    def clear(self):
        self.d.sh(f"run-as {self.PACKAGE} rm -f files/results.jsonl", check=False)

    def results(self):
        out = self.d.sh(f"run-as {self.PACKAGE} cat files/results.jsonl 2>/dev/null", check=False)
        values = {}
        for line in out.splitlines():
            if line.startswith("{"):
                v = json.loads(line)
                values[v["key"]] = v["value"]
        return values

    def do(self, actions, extras="", foreground=True, timeout=20, restart=True):
        """The probe performs actions (comma-separated); their results by action."""
        self.clear()
        if foreground:
            flags = "-S " if restart else "--activity-single-top "
            self.d.sh(f"am start {flags}-W -n {self.PACKAGE}/.Probe --es do {actions} {extras}")
        else:
            self.d.sh(f"am broadcast -f 0x20 -a org.lujarch.probe.DO -n '{self.PACKAGE}/.Probe$Background' "
                      f"--es do {actions} {extras}")
        wanted = actions.split(",")
        deadline = time.monotonic() + timeout
        while True:
            got = self.results()
            if all(a in got for a in wanted) or time.monotonic() > deadline:
                return got
            time.sleep(1)


def app_permissions(d, package):
    """Settings > Apps > (app) > Permissions."""
    d.unlock()
    d.start(f"-f 0x10008000 -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{package}")
    d.tap(d.wait(text="Permissions"))
    d.wait(text="Allowed")


PERMISSION_SECTIONS = ("Allowed", "Allowed only while in use", "Allowed to keep app working",
                       "Ask every time", "Return empty data", "Not allowed", "Unused app settings")


def sections(d, headers=PERMISSION_SECTIONS, pages=8):
    """The page's rows grouped under its section headers, read while scrolling it from the top."""
    for _ in range(3):
        d.swipe(540, 700, 540, 1900, 300)
    groups, current, seen, last = {}, None, set(), None
    for _ in range(pages):
        nodes = sorted((n for n in d.ui() if n.text), key=lambda n: n.y)
        for n in nodes:
            if n.text in headers:
                current = n.text
                groups.setdefault(current, [])
            elif current and n.text not in seen:
                groups[current].append(n.text)
                seen.add(n.text)
        sig = [n.text for n in nodes]
        if sig == last:
            break
        last = sig
        d.swipe(540, 1800, 540, 900, 500)
    return groups


def choose(d, package, permission, choice):
    """Settings > Apps > (app) > Permissions > permission > choice."""
    app_permissions(d, package)
    d.tap(text=permission)
    d.tap(d.wait(text=choice))
    time.sleep(1)
    d.back()
    d.back()



# ---------------------------------------------------------------------------
# Starting the device again


def restart(d=None, timeout=300):
    """Restarts the device and waits until it has started; adb then runs as root again (which
    ends adb's reverse ports) and the device is awake, unlocked and on its Wi-Fi network, as
    `lujarch vm start` leaves it."""
    d = d or device()
    d.adb("reboot", check=False, timeout=60)
    started(d, timeout)


def started(d=None, timeout=300):
    """Waits until the device, restarting for any reason, has started, and leaves it as
    `lujarch vm start` does: adb as root, awake, unlocked, on its Wi-Fi network."""
    d = d or device()
    time.sleep(15)
    deadline = time.monotonic() + timeout
    while True:
        try:
            subprocess.run([ADB, "connect", d.serial], capture_output=True, timeout=30)
            rc, out = d.sh_rc("getprop sys.boot_completed", timeout=30)
            if rc == 0 and out.strip() == "1":
                break
        except subprocess.TimeoutExpired:
            pass
        if time.monotonic() > deadline:
            raise Failure(f"the device did not start within {timeout} s")
        time.sleep(3)
    for _ in range(20):
        with contextlib.suppress(subprocess.TimeoutExpired):
            if d.sh_rc("id -u", timeout=30)[1].strip() == "0":
                break
            d.adb("root", check=False, timeout=30)
        time.sleep(3)
        with contextlib.suppress(subprocess.TimeoutExpired):
            subprocess.run([ADB, "connect", d.serial], capture_output=True, timeout=30)
    else:
        raise Failure("adb does not run as root after the start")
    d.unlock()
    for _ in range(30):
        wifi = d.sh("cmd wifi status", check=False)
        if 'connected to "VirtWifi"' in wifi:
            break
        if "Wifi is enabled" in wifi:
            d.sh("cmd wifi connect-network VirtWifi open", check=False)
        time.sleep(2)
    else:
        raise Failure("the device did not join its Wi-Fi network after the start")
