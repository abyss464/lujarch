"""Install unknown apps lists every app that can install apps, app stores included, with Allow, Ask
every time and Don't allow: Allow installs as Android lets it; Ask every time makes the install
wait for the user, whose question names the app, its version and whether its signature matches,
and refuses it with a notification when no answer comes within Wait for an answer; Don't allow
refuses it.

verifies: PERM-12
timeout: 1500
"""
import re
import time

from lujarch_test import AppProbe, check, device, note, probe, run, step

TARGET = "org.lujarch.probe.target"
WAIT = "lujarch_permissions_install_wait_minutes"
APK = "/data/data/org.lujarch.probe/files/target.apk"


def installed(d):
    return d.package_installed(TARGET)


def install(d, p):
    d.uninstall(TARGET)
    return p.do("install", f"--es apk {APK}", timeout=8, restart=False)


def choose(d, choice):
    d.start(f"-f 0x10008000 -a android.settings.MANAGE_UNKNOWN_APP_SOURCES "
            f"-d package:{AppProbe.PACKAGE}")
    d.tap(d.wait(text=choice))
    d.home()


def notice(d, words, since):
    out = d.sh("dumpsys notification --noredact")
    for b in out.split("NotificationRecord(")[1:]:
        when = re.search(r"when=(\d+)", b)
        if words in b and when and int(when.group(1)) >= since:
            return b
    return None


def now(d):
    return int(d.sh("date +%s%3N").strip())


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    p.allow()
    d.push(str(probe("target")), "/data/local/tmp/target.apk")
    d.sh(f"run-as {p.PACKAGE} sh -c 'mkdir -p files && cp /data/local/tmp/target.apk files/'")
    d.put_setting("global", WAIT, None)
    d.start(f"-n {p.PACKAGE}/.Probe", wait=2)
    try:
        listed(d)
        refused(d, p)
        allowed(d, p)
        asked(d, p)
        unanswered(d, p)
    finally:
        d.put_setting("global", WAIT, None)
        d.uninstall(TARGET)
        d.home()


def listed(d):
    step("every app that can install is listed; the app store is allowed, others are not")
    d.start("-f 0x10008000 -a android.settings.MANAGE_UNKNOWN_APP_SOURCES")
    for app, state in (("App Store", "Allow"), ("Probe", "Don't allow")):
        summary = d.summary(app, reach=100)
        check(state in summary, f"{app}: {state} ({summary})")
    d.shot("install-unknown-apps")
    d.start(f"-f 0x10008000 -a android.settings.MANAGE_UNKNOWN_APP_SOURCES "
            f"-d package:{AppProbe.PACKAGE}")
    for c in ("Allow", "Ask every time", "Don't allow"):
        check(d.wait(text=c), f"the app's page offers {c}")
    d.shot("install-choices")
    d.home()


def refused(d, p):
    step("Don't allow: the install is refused and a notification says so")
    since = now(d)
    install(d, p)
    deadline = time.monotonic() + 20
    while not notice(d, "Probe target was not installed", since) and time.monotonic() < deadline:
        time.sleep(1)
    n = notice(d, "Probe target was not installed", since)
    check(n and "does not allow Probe to install apps" in n, "a notification names the app and why")
    check(not installed(d), "not installed")


def allowed(d, p):
    step("Allow: the app installs as Android lets it")
    choose(d, "Allow")
    install(d, p)
    button = d.wait(text="Install", timeout=20)
    d.shot("install-android-confirm")
    d.tap(button)
    deadline = time.monotonic() + 30
    while not installed(d) and time.monotonic() < deadline:
        time.sleep(1)
    check(installed(d), "installed")


def asked(d, p):
    step("Ask every time: the install waits for the answer, which names app, version, signature")
    choose(d, "Ask every time")
    install(d, p)
    d.wait(contains="Let Probe install Probe target", timeout=20)
    texts = " ".join(d.texts())
    note(texts)
    check("Version 7.0" in texts, "the question shows the version")
    check("Signed like the installed app, or a new app" in texts, "and the signature")
    d.shot("install-question")
    check(not installed(d), "nothing is installed before the answer")
    d.tap(text="Install")
    deadline = time.monotonic() + 30
    while not installed(d) and time.monotonic() < deadline:
        if d.find(text="Install"):
            d.tap(text="Install")
        time.sleep(1)
    check(installed(d), "the answer Install installed it")


def unanswered(d, p):
    step("Wait for an answer: 1, 2, 5, 10, 15, 30 or 60 minutes, 10 by default")
    d.start("-f 0x10008000 -a android.settings.MANAGE_UNKNOWN_APP_SOURCES")
    d.tap(desc="More options")
    d.tap(d.wait(text="Wait for an answer"))
    for m in ("1 minute", "2 minutes", "5 minutes", "10 minutes", "15 minutes", "30 minutes",
              "60 minutes"):
        check(d.find(text=m), f"offers {m}")
    check(d.find(text="10 minutes").checked, "10 minutes is chosen")
    d.shot("install-wait")
    d.tap(text="1 minute")
    check(d.setting("global", WAIT) == "1", "waits 1 minute")
    d.home()
    step("without an answer the install is refused, and a notification says so")
    since = now(d)
    install(d, p)
    d.wait(contains="Let Probe install Probe target", timeout=20)
    d.home()
    deadline = time.monotonic() + 100
    while not notice(d, "No answer within the wait time", since) and time.monotonic() < deadline:
        time.sleep(5)
    check(notice(d, "No answer within the wait time", since), "a notification: no answer in time")
    check(not installed(d), "not installed")
    d.sh("cmd statusbar expand-notifications")
    d.wait(contains="was not installed")
    d.shot("install-unanswered")
    d.sh("cmd statusbar collapse")
    choose(d, "Don't allow")


run(main)
