"""The proxy tests' helpers: the throwaway servers the proxy uses on the virtual device (proxy
nodes and a subscription server, on this computer and reached from the device through adb
reverse), and the steps on the proxy's pages that several tests share.

Every node, subscription address and password here is made up for the tests.
"""
import base64
import contextlib
import http.server
import socket
import struct
import threading
import time

from lujarch_test import ADB, SERIAL, Failure, check, note, step

# Ports on the device; adb reverse carries each to a port of this computer.
SUB_PORT = 17700
NODE_PORT = 17710
# A destination port whose answer a node holds back: its connections stay open.
HOLD_PORT = 8081
HOLD_SECONDS = 120
# Destinations: an address and a name that exist only behind the nodes.
WEB_IP = "203.0.113.80"
WEB_NAME = "web.lujarch.test"
# The virtual Wi-Fi network's router, reached without a node.
ROUTER = "192.168.99.1"
# A DNS server behind the nodes, which never answers.
DNS_IP = "203.0.113.53"


class Node:
    """A proxy node: SOCKS5 with a user and password, or Shadowsocks without encryption. It
    answers every connection itself, as the destination would, naming itself and the
    destination it was asked for: `HTTP/1.0 200 via <node> to <host>:<port>`, and 204 to the
    connectivity checks the core, its groups and Android make."""

    def __init__(self, name, port, kind="socks5", user="lujarch-test", password="not-a-real-password"):
        self.name, self.port, self.kind = name, port, kind
        self.user, self.password = user, password
        self.seen = []  # (time, host, port)
        self.lock = threading.Lock()
        self.server = None
        self.conns = set()
        # While set, the node answers connectivity checks with 503, as a node that cannot reach
        # the internet does.
        self.checks_fail = False
        # When connectivity checks without a User-Agent (the proxy's own) came.
        self.own_checks = []

    # -- what the node is in a subscription or a link

    def yaml(self):
        if self.kind == "ss":
            return (f"  - {{name: \"{self.name}\", type: ss, server: 127.0.0.1, port: {self.port}, "
                    f"cipher: none, password: \"{self.password}\", udp: false}}\n")
        return (f"  - {{name: \"{self.name}\", type: socks5, server: 127.0.0.1, port: {self.port}, "
                f"username: \"{self.user}\", password: \"{self.password}\", udp: false}}\n")

    def link(self):
        user = base64.urlsafe_b64encode(f"none:{self.password}".encode()).decode().rstrip("=")
        return f"ss://{user}@127.0.0.1:{self.port}#{self.name.replace(' ', '%20')}"

    # -- running

    def up(self):
        if self.server:
            return
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("127.0.0.1", 0))
        s.listen(64)
        self.server = s
        self.host_port = s.getsockname()[1]
        reverse(self.port, self.host_port)
        threading.Thread(target=self._accept, args=(s,), daemon=True).start()

    def down(self):
        """The node stops answering: its listener and its open connections close."""
        s, self.server = self.server, None
        if s:
            # shutdown, not close alone, ends the accept() the node's thread waits in.
            with contextlib.suppress(OSError):
                s.shutdown(socket.SHUT_RDWR)
            with contextlib.suppress(OSError):
                s.close()
        for c in list(self.conns):
            with contextlib.suppress(OSError):
                c.shutdown(socket.SHUT_RDWR)
                c.close()

    def seen_since(self, t):
        with self.lock:
            return [(h, p) for at, h, p in self.seen if at >= t]

    def _accept(self, s):
        while True:
            try:
                c, _ = s.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(c,), daemon=True).start()

    def _serve(self, c):
        self.conns.add(c)
        try:
            c.settimeout(30)
            host, port = self._socks(c) if self.kind == "socks5" else _address(c)
            with self.lock:
                self.seen.append((time.time(), host, port))
            if port == 443:
                c.recv(4096)
                if host == DNS_IP:
                    # The proxy's own DNS over HTTPS through the node: held open, unanswered.
                    c.settimeout(HOLD_SECONDS)
                    with contextlib.suppress(OSError):
                        c.recv(1)
                return
            head = _read_head(c)
            if port == HOLD_PORT:
                c.settimeout(HOLD_SECONDS)
                with contextlib.suppress(OSError):
                    if c.recv(1) == b"":
                        return
            request = head.split(b"\r\n", 1)[0]
            if b" /stream" in request:
                # A download that lasts: 64 KiB every tenth of a second for 10 s.
                c.sendall(b"HTTP/1.0 200 OK\r\nConnection: close\r\n\r\n")
                for _ in range(100):
                    c.sendall(bytes(65536))
                    time.sleep(0.1)
                return
            if b"generate_204" in request or b"gen_204" in request:
                if b"user-agent:" not in head.lower():
                    with self.lock:
                        self.own_checks.append(time.time())
                c.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                          if self.checks_fail else
                          b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            body = f"{self.name} {host}:{port}\n".encode()
            c.sendall(f"HTTP/1.0 200 via {self.name} to {host}:{port}\r\nContent-Length: {len(body)}\r\n"
                      f"Connection: close\r\n\r\n".encode() + body)
        except (OSError, ValueError):
            pass
        finally:
            self.conns.discard(c)
            with contextlib.suppress(OSError):
                c.close()

    def _socks(self, c):
        ver, n = _exact(c, 2)
        methods = _exact(c, n)
        if ver != 5 or 2 not in methods:
            c.sendall(b"\x05\xff")
            raise ValueError("no user and password offered")
        c.sendall(b"\x05\x02")
        _, ulen = _exact(c, 2)
        user = _exact(c, ulen).decode()
        plen = _exact(c, 1)[0]
        password = _exact(c, plen).decode()
        if (user, password) != (self.user, self.password):
            c.sendall(b"\x01\x01")
            raise ValueError("wrong user or password")
        c.sendall(b"\x01\x00")
        ver, cmd, _ = _exact(c, 3)
        host, port = _address(c)
        if cmd != 1:
            c.sendall(b"\x05\x07\x00\x01" + bytes(6))
            raise ValueError("only CONNECT")
        c.sendall(b"\x05\x00\x00\x01" + bytes(6))
        return host, port


def _exact(c, n):
    data = b""
    while len(data) < n:
        chunk = c.recv(n - len(data))
        if not chunk:
            raise ValueError("closed")
        data += chunk
    return data


def _address(c):
    kind = _exact(c, 1)[0]
    if kind == 1:
        host = socket.inet_ntoa(_exact(c, 4))
    elif kind == 3:
        host = _exact(c, _exact(c, 1)[0]).decode()
    elif kind == 4:
        host = socket.inet_ntop(socket.AF_INET6, _exact(c, 16))
    else:
        raise ValueError("address type")
    return host, struct.unpack(">H", _exact(c, 2))[0]


def _read_head(c):
    data = b""
    while b"\r\n\r\n" not in data and len(data) < 65536:
        chunk = c.recv(4096)
        if not chunk:
            break
        data += chunk
    return data


class Subscriptions:
    """The subscription server: each subscription's node list as Clash YAML at an address with
    a made-up access token, with its usage and expiry in subscription-userinfo."""

    def __init__(self):
        self.lists = {}  # token -> [Node]
        self.usage = {}  # token -> (upload, download, total, expire)
        self.fetches = {}  # token -> count
        self.server = None

    def url(self, token):
        return f"http://127.0.0.1:{SUB_PORT}/sub/{token}"

    def up(self):
        if self.server:
            return
        subs = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                token = self.path.rsplit("/", 1)[-1]
                nodes = subs.lists.get(token)
                if nodes is None:
                    self.send_error(404)
                    return
                subs.fetches[token] = subs.fetches.get(token, 0) + 1
                body = ("proxies:\n" + "".join(n.yaml() for n in nodes)).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/yaml; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                if token in subs.usage:
                    up, down, total, expire = subs.usage[token]
                    self.send_header("subscription-userinfo",
                                     f"upload={up}; download={down}; total={total}; expire={expire}")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        reverse(SUB_PORT, self.server.server_address[1])
        threading.Thread(target=self.server.serve_forever, daemon=True).start()


def reverse(device_port, host_port):
    import subprocess
    r = subprocess.run([ADB, "-s", SERIAL, "reverse", f"tcp:{device_port}", f"tcp:{host_port}"],
                       capture_output=True, text=True, timeout=30)
    if r.returncode:
        raise Failure(f"adb reverse tcp:{device_port}: {r.stderr.strip()}")


class Fixtures:
    """The nodes and subscriptions of a test. Nodes Tokyo 1, Tokyo 2 and Dallas 1 come with the
    subscription Alpha (Tokyo 3 joins it when a test says so; its replacement address lists two
    nodes); Osaka-1 and Nagoya-1 are nodes added by hand, from a link or a file."""

    def __init__(self):
        self.subs = Subscriptions()
        self.nodes = {}
        for i, name in enumerate(("Tokyo 1", "Tokyo 2", "Dallas 1", "Tokyo 3")):
            self.nodes[name] = Node(name, NODE_PORT + i)
        self.nodes["Nagoya-1"] = Node("Nagoya-1", NODE_PORT + 4, kind="ss")
        self.nodes["Osaka-1"] = Node("Osaka-1", NODE_PORT + 5, kind="ss")
        self.subs.lists["alpha-7Qk2"] = [self.nodes[n] for n in ("Tokyo 1", "Tokyo 2", "Dallas 1")]
        self.subs.lists["alpha-new-3Hd8"] = [self.nodes[n] for n in ("Tokyo 1", "Dallas 1")]
        gib = 1 << 30
        self.subs.usage["alpha-7Qk2"] = (3 * gib, 17 * gib, 100 * gib, int(time.time()) + 40 * 86400)
        self.subs.usage["alpha-new-3Hd8"] = (1 * gib, 4 * gib, 50 * gib, int(time.time()) + 10 * 86400)

    def up(self):
        self.subs.up()
        for n in self.nodes.values():
            n.up()
        return self

    def again(self):
        """After the device restarted adbd (adb root, a reboot): the reverse ports again."""
        reverse(SUB_PORT, self.subs.server.server_address[1])
        for n in self.nodes.values():
            if n.server:
                reverse(n.port, n.host_port)

    @property
    def alpha(self):
        return self.subs.url("alpha-7Qk2")

    @property
    def alpha_new(self):
        return self.subs.url("alpha-new-3Hd8")

    def via(self, since):
        """{node name: [(host, port)]} the nodes were asked for since `since`."""
        return {name: n.seen_since(since) for name, n in self.nodes.items() if n.seen_since(since)}


# ---------------------------------------------------------------------------
# The apps whose traffic the tests watch

PEER = "org.lujarch.probe.peer"


class Apps:
    """The probe (org.lujarch.probe, “Probe”) and its peer (“Probe peer”): two apps that
    connect where a test tells them and say what answered."""

    def __init__(self, d):
        from lujarch_test import AppProbe
        self.d = d
        self.probe = AppProbe(d)

    def install(self):
        from lujarch_test import install_probe
        self.probe.install()
        self.probe.allow()
        self.d.uninstall(PEER)
        install_probe("peer")
        # Test setup: the peer may be started for a test's order while it does not run.
        self.d.sh(f"cmd appops set {PEER} LUJARCH_AUTOSTART allow")
        # A just installed app's network access reaches the system's DNS resolver moments later.
        time.sleep(5)
        return self

    def reach(self, target, timeout=25):
        """What the probe's connection to target (host:port) got: `connected <first line>` or
        the error."""
        got = self.probe.do("connect", f"--es to {target}", foreground=False, timeout=timeout)
        return got.get("connect") or "no answer"

    def peer_reach(self, target, timeout=30):
        from lujarch_test import probe_result
        self.d.sh(f"run-as {PEER} rm -f files/results.jsonl", check=False)
        self.d.sh(f"am broadcast -f 0x20 -a org.lujarch.probe.peer.DO -n {PEER}/.Peer --es to {target}")
        return probe_result(PEER, "connect", timeout=timeout) or "no answer"


def via(answer, node):
    return answer.startswith(f"connected HTTP/1.0 200 via {node} ")


def direct_router(answer):
    """The router of the virtual Wi-Fi network answered itself."""
    return answer.startswith("connected HTTP/1.") and " via " not in answer


# ---------------------------------------------------------------------------
# The proxy's pages

def summary(d, title, nodes=None):
    """The text under a row's title (its summary), or None."""
    nodes = nodes if nodes is not None else d.ui()
    t = d.find(text=title, nodes=nodes)
    return below(t, nodes) if t else None


def below(t, nodes):
    """The text under the title element t, in its row."""
    under = [n for n in nodes if n.text and n is not t and t.bounds[1] < n.bounds[1] <= t.bounds[3] + 60
             and abs(n.bounds[0] - t.bounds[0]) < 40 and not n.rid.endswith("id/title")]
    return min(under, key=lambda n: n.bounds[1]).text if under else None


def proxy_page(d):
    """Settings > Network & internet > Proxy."""
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.WIRELESS_SETTINGS")
    d.tap(d.scroll_to(text="Proxy"), wait=2)
    d.wait(text="Use proxy")


def open_page(d, *titles):
    """A page of the proxy's, opened from the Proxy page through the rows titled `titles`."""
    proxy_page(d)
    for t in titles:
        d.tap(d.scroll_to(text=t), wait=2)


def status(d):
    """The Proxy page's status row."""
    for _ in range(3):
        d.swipe(540, 700, 540, 1900, 300)
    return summary(d, "Status") or ""


def wait_status(d, *words, timeout=60):
    """Waits on the Proxy page until the status row holds one of words; returns it."""
    deadline = time.monotonic() + timeout
    while True:
        s = status(d)
        if any(w in s for w in words):
            return s
        if time.monotonic() > deadline:
            check(False, f"the status row says {' or '.join(words)} (it says “{s}”)")
        time.sleep(2)


def use_proxy(d, on):
    """Turns Use proxy on or off on the Proxy page, and waits for the state that follows."""
    proxy_page(d)
    switch = d.switch_of("Use proxy")
    if switch.checked != on:
        d.tap(switch)
    return wait_status(d, "Running", "No usable", "Stopped", timeout=90) if on else wait_status(d, "Off")


def fresh(d):
    """The proxy as on a new phone: off, without subscriptions, nodes, rules or settings of its
    own (the files of /data/lujarch/proxy removed, its service started again)."""
    d.put_setting("global", "lujarch_proxy_enabled", 0)
    d.put_setting("global", "lujarch_proxy_repair", 1)
    d.put_setting("global", "lujarch_proxy_ipv6_block", 1)
    d.sh("stop lujarch_proxy; rm -rf /data/lujarch/proxy/* /data/lujarch/proxy/.[!.]*; start lujarch_proxy")
    # The pages' own choices (a log level, Show system apps) as on a new phone too.
    d.sh("pm clear org.lujarch.settings", check=False)
    for _ in range(30):
        if d.prop("lujarch.proxy.state") == "off":
            return
        time.sleep(1)
    check(False, "the proxy's service is back and off")


def add_subscription(d, name, url, fetch=True):
    """Subscriptions > Add subscription, with a name and an address."""
    open_page(d, "Subscriptions")
    d.tap(text="Add subscription", wait=1.5)
    d.tap(rid="px_sub_name")
    d.type(name)
    d.tap(rid="px_sub_url")
    d.type(url)
    if not fetch:
        d.tap(rid="px_sub_fetch")
    d.tap(text="Add", wait=2)
    return wait_task(d, f"Add {name}")


def wait_task(d, title, timeout=180):
    """The summary of a change's row on the page (its task) once it has finished."""
    deadline = time.monotonic() + timeout
    while True:
        s = summary(d, title)
        if s is None:
            # The change's row is at the top of its page.
            d.swipe(540, 700, 540, 1900, 300)
            s = summary(d, title)
        if s and not s.endswith("…") and "In progress" not in s:
            return s
        if time.monotonic() > deadline:
            check(False, f"“{title}” finished (it says “{s}”)")
        time.sleep(2)


def set_location(d, node, country, kind):
    """Node locations > (node): its country and line type, set by hand."""
    n = d.scroll_to(text=node)
    d.tap(n, wait=1.5)
    d.tap(rid="px_manual_country")
    d.type(country)
    d.tap(text=kind)
    d.tap(text="Save and apply", wait=2)
    deadline = time.monotonic() + 90
    while not (summary(d, node) or "").startswith(f"{country} {kind}"):
        if time.monotonic() > deadline:
            check(False, f"{node} is located in {country}, {kind}")
        time.sleep(2)


def set_final(d, target):
    """Advanced > Rules > Final rule: target."""
    open_page(d, "Rules")
    d.tap(d.scroll_to(text="Final rule"), wait=2)
    d.tap(d.scroll_to(text=target), wait=2)
    d.wait(text=f"Final rule: {target}", timeout=60)


LOCATIONS = {"Tokyo 1": ("JP", "Residential"), "Tokyo 2": ("JP", "Data center"),
             "Dallas 1": ("US", "Data center")}


def configure(d, fx, final="JP Residential - Auto"):
    """The subscription Alpha added, its nodes located by hand (Tokyo 1: JP residential, Tokyo 2:
    JP data center, Dallas 1: US data center) and the final rule to `final`."""
    step("Subscriptions > Add subscription: Alpha, its nodes located by hand")
    result = add_subscription(d, "Alpha", fx.alpha)
    check(result == "Added, downloaded and applied", f"Alpha is added ({result})")
    d.tap(d.scroll_to(contains="without a location"), wait=2)
    for node, (country, kind) in LOCATIONS.items():
        set_location(d, node, country, kind)
    if final:
        step(f"Rules > Final rule: {final}")
        set_final(d, final)


def setup(d, fx, on=True, final="JP Residential - Auto"):
    """A fresh proxy, configured (configure), with Use proxy on or left off."""
    fresh(d)
    configure(d, fx, final)
    if on:
        step("Use proxy on")
        s = use_proxy(d, True)
        check(s.startswith("Running"), f"the proxy runs ({s})")


def details(d):
    """Proxy > Status: the details page's rows, {title: summary}, read from its top to its end."""
    titles = ("Status", "Mode", "Core version", "Last restart on its own",
              "Virtual network interface (TUN)", "IPv6", "Speed", "Traffic since the proxy started",
              "Current configuration", "Last working configuration")
    rows = {}
    for _ in range(3):
        d.swipe(540, 700, 540, 1900, 300)
    for _ in range(3):
        nodes = d.ui()
        for title in titles:
            if title not in rows:
                s = summary(d, title, nodes)
                if s is not None:
                    rows[title] = s
        if len(rows) == len(titles):
            break
        d.swipe(540, 1800, 540, 900, 500)
    for _ in range(3):
        d.swipe(540, 700, 540, 1900, 300)
    return rows


def core_pid(d):
    out = d.sh("ps -A -o pid,ppid,args | grep '[l]ujarch_proxy_core -d'", check=False)
    pids = [line.split()[0] for line in out.splitlines() if line.strip()]
    return pids[0] if pids else None


def state(d):
    """The state the proxy's service publishes (lujarch.proxy.state)."""
    return d.prop("lujarch.proxy.state")


def wait_state(d, *states, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        s = state(d)
        if s in states:
            return s
        if time.monotonic() > deadline:
            check(False, f"the proxy's state becomes {' or '.join(states)} (it is {s})")
        time.sleep(0.3)


def advanced(d, title, confirm=None):
    """Proxy > Advanced > title, confirmed with the button `confirm` when it asks first."""
    open_page(d, title)
    if confirm:
        d.tap(d.wait(text=confirm), wait=2)


# ---------------------------------------------------------------------------
# Files the owner picks

DOWNLOADS = "/sdcard/Download"


def put_download(d, name, data):
    """Test setup: a file in the phone's Downloads, as a download leaves it there."""
    import tempfile
    from pathlib import Path
    with tempfile.TemporaryDirectory() as tmp:
        local = Path(tmp) / name
        local.write_bytes(data if isinstance(data, bytes) else data.encode())
        d.push(local, f"{DOWNLOADS}/{name}")
    d.sh(f"content call --uri content://media/external/file --method scan_file "
         f"--arg {DOWNLOADS}/{name}", check=False)
    d.sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{DOWNLOADS}/{name}",
         check=False)


def downloads_folder(d):
    """In the file picker or saver a page opened (Files): Internal shared storage > Download."""
    until(lambda: d.find(text="Internal shared storage"), "the file picker opens", 30)
    nodes = d.ui()
    crumb = [n for n in nodes if n.text == "Download" and n.rid.endswith("text")]
    if not crumb:
        if not d.find(text="Download", nodes=nodes):
            d.tap(text="Internal shared storage", wait=2)
        d.tap(d.scroll_to(text="Download"), wait=3)


def pick_download(d, name):
    """In the file picker that a page opened: Download > name."""
    downloads_folder(d)
    d.tap(d.scroll_to(text=name), wait=3)


def save_download(d, name=None):
    """In the file saver that a page opened (Files): Download, the file named `name` (or as the
    page named it), Save."""
    downloads_folder(d)
    if name:
        d.tap(rid="bottomCreateFileNameEdit")
        d.sh("input keyevent KEYCODE_MOVE_END; for i in $(seq 1 80); do input keyevent KEYCODE_DEL; done",
             check=False)
        d.type(name)
    d.tap(desc="Save", wait=3)


def until(condition, what, timeout=60, every=2):
    """Waits until condition() holds and returns its value; a failed check after timeout."""
    deadline = time.monotonic() + timeout
    while True:
        value = condition()
        if value:
            return value
        if time.monotonic() > deadline:
            check(False, what)
        time.sleep(every)


def join(d, ssid):
    """Settings > Network & internet > Internet: the Wi-Fi network ssid (VirtWifi2 with its virtual
    access point's own password)."""
    if f'connected to "{ssid}"' in d.sh("cmd wifi status", check=False):
        return
    d.unlock()
    d.start("-f 0x10008000 -a android.settings.WIFI_SETTINGS")
    # The page lists the networks once its scan has found them.
    d.tap(until(lambda: d.find(text=ssid), f"the Internet page lists {ssid}", 60), wait=3)
    if d.find(text="Password") or d.find(rid="password"):
        d.type("password")
        d.tap(text="Connect", wait=3)
    until(lambda: f'connected to "{ssid}"' in d.sh("cmd wifi status", check=False), f"the phone joined {ssid}", 60)
    note(f"on {ssid}")
