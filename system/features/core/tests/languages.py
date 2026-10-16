"""Every string Lujarch adds or changes exists in English, Simplified Chinese and Japanese, and the
phone shows the one of its language; with another system language Lujarch's strings show in
English and Android's in that language.

verifies: CORE-8
timeout: 1800
"""
import html
import os
import re
import time
from pathlib import Path

from lujarch_test import ROOT, check, device, note, run, step

STRING = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.S)
TREE = Path(os.environ.get("LUJARCH_TREE", os.path.expanduser("~/Code/vendor/lineage-24.0")))
LANGS = {"": "en-US", "zh-rCN": "zh-CN", "ja": "ja-JP"}


def plain(text):
    text = html.unescape(re.sub(r"<[^>]+>", "", text)).strip()
    if len(text) > 1 and text[0] == text[-1] == '"':
        text = text[1:-1]
    return text.replace("\\'", "'").replace("\\u2019", "'").replace("’", "'")


def lujarch_strings():
    """{(component, name): {values dir suffix: text}} of every translatable string Lujarch adds,
    in its sources and its patches."""
    found = {}

    def add(component, suffix, body):
        for name, attrs, text in STRING.findall(body):
            if 'translatable="false"' not in attrs:
                found.setdefault((component, name), {})[suffix] = plain(text)

    for path in ROOT.glob("system/**/res/values*/*.xml"):
        m = re.search(r"/values(?:-([a-zA-Z-]+))?$", str(path.parent))
        if m and (m.group(1) or "") in LANGS:
            add(str(path.parent.parent), m.group(1) or "", path.read_text())
    for patch in ROOT.glob("system/**/patches/**/*.patch"):
        target, lines = None, []
        for line in patch.read_text(errors="replace").splitlines() + ["+++ end"]:
            if line.startswith("+++ "):
                if target:
                    add(*target, "\n".join(lines))
                m = re.search(r"^\+\+\+ b/(.*)/res/values(?:-([a-zA-Z-]+))?/[^/]+\.xml$", line)
                target = (f"{patch.parent}:{m.group(1)}", m.group(2) or "") if m and (
                    m.group(2) or "") in LANGS else None
                lines = []
            elif target and line.startswith("+"):
                lines.append(line[1:])
    return found


def android_string(path, name):
    m = re.search(rf'<string name="{name}"[^>]*>(.*?)</string>', (TREE / path).read_text(), re.S)
    return plain(m.group(1)) if m else None


def set_locale(d, locale):
    d.sh(f"setprop persist.sys.locale {locale}")
    d.restart_framework()


def permission_manager(d):
    d.start("-f 0x10008000 -a android.intent.action.MANAGE_PERMISSIONS", wait=4)
    texts = set()
    for _ in range(6):
        texts |= set(d.texts())
        d.swipe(540, 1800, 540, 700)
    return texts


def main():
    d = device()
    step("every translatable string Lujarch adds has Chinese and Japanese")
    strings = lujarch_strings()
    untranslated = [f"{c.split('system/')[-1]} {n}" for (c, n), t in strings.items()
                    if "" in t and not {"zh-rCN", "ja"} <= set(t)]
    note(f"{len(strings)} strings")
    check(len(strings) > 1000, "Lujarch's strings are found")
    check(not untranslated, f"each in three languages ({untranslated[:20]})")

    explain = next(t for (c, n), t in strings.items() if n == "lujarch_explain_crashes")
    camera_fr = android_string("frameworks/base/core/res/res/values-fr/strings.xml",
                               "permgrouplab_camera")
    camera_en = android_string("frameworks/base/core/res/res/values/strings.xml",
                               "permgrouplab_camera")
    try:
        for suffix, locale in (("zh-rCN", "zh-CN"), ("ja", "ja-JP")):
            step(f"{locale}: Lujarch's strings in the phone's language")
            set_locale(d, locale)
            texts = permission_manager(d)
            check(explain[suffix] in texts, f"“{explain[suffix]}” shows")
            d.shot(f"permission-manager-{locale}")
        step("fr-FR: Lujarch's strings in English, Android's in French")
        set_locale(d, "fr-FR")
        texts = permission_manager(d)
        check(explain[""] in texts, f"Lujarch's “{explain['']}” shows in English")
        check(camera_fr in texts and camera_en not in texts,
              f"Android's “{camera_en}” shows in French: “{camera_fr}”")
        d.shot("permission-manager-fr-FR")
    finally:
        set_locale(d, "en-US")
        d.home()


run(main)
