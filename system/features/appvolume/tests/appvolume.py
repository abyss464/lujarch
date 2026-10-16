"""Under Media the volume panel shows a line with the playing apps and how many they are; it opens to
one slider per app. An app's volume scales only its own media, as a share of Media; it follows the
app across restarts of the app and the phone and ends when the app is uninstalled; the line stays
open or folded as the owner left it.

verifies: VOL-1
timeout: 1500
"""
import re
import time

from lujarch_test import AppProbe, check, device, note, run, step

EXPANDED = "lujarch_appvolume_expanded"


def play(d, p, seconds=40):
    p.clear()
    d.sh(f"am start -n {p.PACKAGE}/.Probe --activity-single-top --es do play --ei seconds {seconds}")
    time.sleep(3)
    d.home()


def gain(d, uid):
    out = d.sh("dumpsys media.audio_flinger")
    m = re.search(rf"^\s*uid {uid} gain ([0-9.]+)", out, re.M)
    return float(m.group(1)) if m else 1.0


def panel(d):
    d.start("-f 0x10008000 -a android.settings.panel.action.VOLUME", wait=3)
    return d.wait(contains="app playing", timeout=20)


def main():
    d = device()
    p = AppProbe(d)
    p.install()
    uid = int(re.search(r"userId=(\d+)", d.sh(f"dumpsys package {p.PACKAGE}")).group(1))
    d.put_setting("secure", EXPANDED, None)
    try:
        step("a line under Media names the playing apps; it opens to one slider per app")
        play(d, p)
        line = panel(d)
        note(line.text)
        check(re.search(r"\b1 app playing", line.text), f"the line counts the app ({line.text})")
        d.shot("volume-panel-folded")
        d.tap(line)
        slider = d.wait(contains="Probe media volume", timeout=10)
        d.shot("volume-panel-open")

        step("the app's volume is a share of Media for that app alone")
        x1, y1, x2, y2 = slider.bounds
        d.tap_xy(x1 + (x2 - x1) // 2, (y1 + y2) // 2)
        time.sleep(1)
        half = gain(d, uid)
        check(0.3 < half < 0.7, f"the probe's media plays at about half ({half:.2f})")
        check(re.search(r"media streams: \d", d.sh("dumpsys media.audio_flinger")),
              "the share applies to media streams")
        d.back()

        step("the line stays open as left")
        play(d, p)
        panel(d)
        check(d.find(contains="Probe media volume"), "it opens open again")
        d.back()

        step("the volume follows the app across its restart and the phone's")
        d.stop(p.PACKAGE)
        play(d, p, 10)
        check(abs(gain(d, uid) - half) < 0.05, "after the app's restart")
        d.restart_framework()
        play(d, p, 10)
        check(abs(gain(d, uid) - half) < 0.05, f"after the phone's restart ({gain(d, uid):.2f})")

        step("it ends when the app is uninstalled")
        p.install()
        uid = int(re.search(r"userId=(\d+)", d.sh(f"dumpsys package {p.PACKAGE}")).group(1))
        check(gain(d, uid) == 1.0, "a reinstalled app plays at 100 %")
    finally:
        d.put_setting("secure", EXPANDED, None)
        d.home()


run(main)
