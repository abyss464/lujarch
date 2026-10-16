"""A new app starts with Defaults for new apps (Ask every time for Lujarch's permissions); its
first access is answered with empty data and a question: a dialog while it is on screen, one
notification per app, hidden on the lock screen, while it is not. The answer becomes its choice.

verifies: PERM-11, PERM-18
timeout: 1500
"""
import re
import time

from lujarch_test import AppProbe, app_permissions, check, device, note, run, sections, step

ASKED = ["Motion and environment sensors", "Clipboard", "Installed apps", "Device identifiers",
         "Device info", "Network info"]


def notifications(d, tag):
    out = d.sh("dumpsys notification --noredact")
    return [b for b in out.split("NotificationRecord(")[1:] if f"tag={tag} " in b]


def answer_all(d, wanted, otherwise="Return empty data"):
    """Answers the questions on screen: wanted[permission words] or otherwise; returns titles."""
    seen = []
    for _ in range(12):
        title = d.find(contains="Allow Probe to")
        if not title:
            break
        seen.append(title.text)
        choice = next((c for words, c in wanted.items() if words in title.text), otherwise)
        nodes = d.ui()
        for c in (choice, "Allow", "Basic phone info only"):
            n = d.find(text=c, nodes=nodes)
            if n:
                d.tap(n, wait=1.5)
                break
        else:
            check(False, f"a choice to answer “{title.text}”")
    return seen


def main():
    d = device()
    p = AppProbe(d)
    step("a freshly installed app starts with Ask every time for Lujarch's permissions")
    p.install()
    app_permissions(d, p.PACKAGE)
    d.shot("new-app-permissions")
    groups = sections(d)
    asked = set(groups.get("Ask every time", []))
    check(set(ASKED) <= asked, f"under Ask every time: {sorted(asked)}")
    d.home()

    step("Defaults for new apps holds those choices and names the exceptions")
    d.start("-f 0x10008000 -n 'com.android.settings/.Settings$AppDashboardActivity'")
    d.tap(d.scroll_to(text="Defaults for new apps"))
    for name in ASKED + ["Autostart"]:
        summary = d.summary(name, reach=120)
        check("Ask every time" in summary, f"{name}: Ask every time ({summary})")
    texts = " ".join(d.texts())
    d.shot("defaults-for-new-apps")
    page = " ".join(t for t in [texts] + [" ".join(d.texts()) for _ in [d.swipe(540, 1800, 540, 400)]])
    check(re.search(r"home app", page, re.I), "the page names the default home app's exception")
    d.home()

    step("an app that comes with the system keeps its choices")
    app_permissions(d, "me.zhanghai.android.files")
    groups = sections(d)
    check(not set(ASKED) & set(groups.get("Ask every time", [])),
          f"Files' permissions are not set to Ask every time ({groups.get('Ask every time')})")
    d.home()

    step("on screen: the first access gets empty data and a dialog asks")
    d.start(f"-n {p.PACKAGE}/.Probe", wait=3)
    answer_all(d, {}, otherwise="Allow")  # what the start itself reads
    d.home()
    got = p.do("sensors", timeout=8, restart=False)
    check(got.get("sensors") in ("no readings", None), f"the first read is empty ({got})")
    d.wait(contains="Allow Probe to", timeout=20)
    counter = d.find(contains=" of ")
    note(f"question: {d.find(contains='Allow Probe to').text}; {counter.text if counter else ''}")
    d.shot("question-dialog")
    titles = answer_all(d, {"sensors": "Allow"})
    note(f"answered {len(titles)} questions")
    check(any("sensors" in t for t in titles), "the sensors were asked about")
    got = p.do("sensors")["sensors"]
    check(got.startswith("readings"), f"the answer Allow became the choice ({got})")

    step("off screen: one notification per app, hidden on the lock screen")
    p.install()
    d.start(f"-n {p.PACKAGE}/.Probe", wait=3)
    answer_all(d, {})  # what the start itself asks
    d.home()
    p.do("ids,network", foreground=False)
    time.sleep(3)
    records = notifications(d, p.PACKAGE)
    check(len(records) == 1, f"one question notification for the app ({len(records)})")
    check("vis=-1" in records[0], "it is hidden on the lock screen")
    title = re.search(r"android.title=\S+ \((.*)\)", records[0]).group(1)
    check("Probe" in title, f"it names the app and the permission: {title}")
    d.sh("cmd statusbar expand-notifications")
    time.sleep(2)
    d.shot("question-notification")
    d.tap(d.wait(contains="Allow Probe to"), wait=3)
    d.wait(contains="Allow Probe to")
    counter = d.find(contains=" of ")
    check(counter and re.search(r"\b1 of [2-9]", counter.text), "both waiting permissions are one question")
    answer_all(d, {"identif": "Allow"}, otherwise="Allow")
    got = p.do("ids")["ids"]
    check(not got.endswith("0000000000000000"), f"the answer became the choice ({got})")
    d.home()


run(main)
