"""Subscriptions lists each subscription with its node count, last update, usage of its total and
expiry; a subscription is added with a name and an address typed into a masked field kept from
autofill and keyboard learning, and the address is never shown again, only replaced; nodes are
added by hand from a node link or a file; Update all and Update now download the node lists
again; a subscription's page tests its nodes, replaces its address or its hand-added nodes and
removes it; after adding, updating or replacing, the result says how many nodes were located.

verifies: PRX-11
timeout: 2100
"""
import re
import time

from lujarch_test import check, device, note, run, step

from _proxy import (Fixtures, fresh, open_page, pick_download, put_download, summary, until,
                    use_proxy, wait_task)

TOKEN = "alpha-7Qk2"


def main():
    d = device()
    fx = Fixtures().up()
    try:
        fresh(d)
        adding(d, fx)
        listed(d)
        never_shown(d, fx)
        updates(d, fx)
        sub_page(d, fx)
        by_hand(d, fx)
        removing(d)
    finally:
        d.sh("rm -f /sdcard/Download/lujarch-nodes.yaml", check=False)
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def subs_page(d):
    open_page(d, "Subscriptions")
    d.wait(text="Add subscription")


def located_result(d, title):
    """The result page of the change `title`: how many nodes were located."""
    d.tap(d.scroll_to(text=title), wait=3)
    d.wait(text="Result", timeout=30)
    s = summary(d, "Node locations") or ""
    note(f"{title}: node locations: {s}")
    d.shot(re.sub(r"\W+", "-", title.lower()) + "-result")
    d.back()
    return s


def adding(d, fx):
    step("Add subscription: a name, and the address in a masked field")
    subs_page(d)
    d.tap(text="Add subscription", wait=2)
    d.tap(rid="px_sub_name")
    d.type("Alpha")
    d.tap(rid="px_sub_url")
    d.type(fx.alpha)
    # A password field shows the last typed character for a moment.
    time.sleep(2)
    field = d.find(rid="px_sub_url")
    shown = field.text
    note(f"the field shows {shown!r}")
    check(TOKEN not in shown and "127.0.0.1" not in shown and set(shown) == {"•"}, "the address is masked")
    field_xml = re.search(r'<node [^>]*px_sub_url[^>]*>', d._screen(False) or "")
    check(field_xml and 'password="true"' in field_xml.group(0), "it is a password field")
    editor = d.sh("dumpsys input_method | grep -m1 'inputType=0x'", check=False)
    m = re.search(r"inputType=0x([0-9a-f]+) imeOptions=0x([0-9a-f]+)", editor)
    check(m, f"the keyboard is told about the field ({editor.strip()})")
    input_type, ime = int(m.group(1), 16), int(m.group(2), 16)
    note(f"inputType 0x{input_type:x}, imeOptions 0x{ime:x}")
    check(input_type & 0xff0 == 0x80, "the keyboard gets a password field, which it offers no suggestions for")
    check(ime & 0x1000000, "the keyboard is told not to learn from it")
    autofill = d.sh("dumpsys autofill", check=False)
    check(TOKEN not in autofill and "px_sub_url" not in autofill, "no autofill session holds the field")
    d.shot("add-subscription-masked")
    d.tap(text="Add", wait=2)
    result = wait_task(d, "Add Alpha")
    check(result == "Added, downloaded and applied", f"Alpha is added and its nodes downloaded ({result})")
    located = located_result(d, "Add Alpha")
    check(re.fullmatch(r"\d+ nodes? located", located), "the result says how many nodes were located")


def listed(d):
    step("the list: node count, last update, usage of its total, expiry")
    subs_page(d)
    until(lambda: "used" in (summary(d, "Alpha") or ""), "Alpha's usage shows", 30)
    s = summary(d, "Alpha")
    note(s)
    d.shot("subscriptions")
    check(re.fullmatch(r"3 nodes · updated just now · 20\xa0GB of 100\xa0GB used · expires in (39|40) days", s),
          "3 nodes, updated just now, 20 GB of 100 GB used, expiring in 40 days")


def page_texts(d):
    texts = []
    for _ in range(3):
        texts += [n.text for n in d.ui() if n.text]
        d.swipe(540, 1800, 540, 900, 400)
    return " ".join(texts)


def never_shown(d, fx):
    step("the address is never shown again")
    subs_page(d)
    d.tap(text="Alpha", wait=3)
    d.wait(text="Update now")
    texts = page_texts(d)
    d.shot("subscription-page")
    check(TOKEN not in texts and "/sub/" not in texts, "the subscription's page does not show it")
    check("The current address is not shown" in texts, "Replace address says it is not shown")
    d.tap(d.scroll_to(text="Replace address"), wait=2)
    field = d.find(rid="px_sub_url")
    check(field and field.text in ("", "Subscription address"), f"the replace field starts empty ({field and field.text})")
    check(not d.find(rid="px_sub_name"), "only the address is asked")
    d.shot("replace-address-empty")
    d.back()
    d.back()


def updates(d, fx):
    step("Update now downloads the node list again")
    fx.subs.lists[TOKEN].append(fx.nodes["Tokyo 3"])
    before = fx.subs.fetches.get(TOKEN, 0)
    subs_page(d)
    d.tap(text="Alpha", wait=3)
    d.tap(d.scroll_to(text="Update now"), wait=2)
    d.tap(d.wait(text="Update"), wait=2)
    result = wait_task(d, "Update Alpha")
    check(result == "Updated and applied", f"updated ({result})")
    check(fx.subs.fetches.get(TOKEN, 0) > before, "the list was downloaded again")
    located = located_result(d, "Update Alpha")
    check(re.fullmatch(r"\d+ nodes? located", located), "the result says how many nodes were located")
    subs_page(d)
    until(lambda: (summary(d, "Alpha") or "").startswith("4 nodes"), "Alpha has 4 nodes", 30)

    step("Update all subscriptions downloads every list again")
    fx.subs.lists[TOKEN].pop()
    before = fx.subs.fetches.get(TOKEN, 0)
    d.tap(text="Update all subscriptions", wait=2)
    d.tap(d.wait(text="Update"), wait=2)
    result = wait_task(d, "Update all subscriptions")
    note(result)
    check(fx.subs.fetches.get(TOKEN, 0) > before, "the list was downloaded again")
    until(lambda: (summary(d, "Alpha") or "").startswith("3 nodes"), "Alpha has 3 nodes again", 60)
    d.shot("updated-all")


def sub_page(d, fx):
    step("a subscription's page tests its nodes, through the running proxy")
    use_proxy(d, True)
    subs_page(d)
    d.tap(text="Alpha", wait=3)
    d.tap(d.scroll_to(text="Test node delay"), wait=2)
    def line(node):
        d.scroll_to(text=node)
        return summary(d, node) or ""
    until(lambda: re.search(r"\d+\sms", line("Tokyo 1")), "Tokyo 1 shows its delay", 60)
    note(f"Tokyo 1: {line('Tokyo 1')}; Dallas 1: {line('Dallas 1')}")
    d.shot("subscription-tested")

    step("Replace address: the new address's nodes")
    d.tap(d.scroll_to(text="Replace address"), wait=2)
    d.tap(rid="px_sub_url")
    d.type(fx.alpha_new)
    d.tap(text="Replace", wait=2)
    result = wait_task(d, "Replace the address of Alpha")
    check(result == "Address replaced; nodes updated and applied", f"replaced ({result})")
    located = located_result(d, "Replace the address of Alpha")
    check(re.fullmatch(r"\d+ nodes? located", located), "the result says how many nodes were located")
    subs_page(d)
    until(lambda: re.match(r"2 nodes · .* 5\xa0GB of 50\xa0GB used", summary(d, "Alpha") or ""),
          "Alpha has the new address's nodes and usage", 30)
    d.shot("address-replaced")


def by_hand(d, fx):
    step("Add nodes by hand: from a node link, in a masked field")
    subs_page(d)
    d.tap(text="Add nodes by hand", wait=2)
    d.tap(rid="px_manual_name")
    d.type("Osaka")
    d.tap(text="Next", wait=2)
    d.tap(text="Enter a node link", wait=2)
    field = [n for n in d.ui() if n.cls.endswith("EditText")][0]
    d.tap(field)
    d.type(fx.nodes["Osaka-1"].link())
    time.sleep(2)
    shown = [n for n in d.ui() if n.cls.endswith("EditText")][0].text
    check(set(shown) == {"•"}, f"the link is masked ({shown[:10]}…)")
    d.shot("node-link-masked")
    d.tap(text="Next", wait=2)
    d.tap(rid="px_manual_country")
    d.type("JP")
    d.tap(text="Residential")
    d.tap(text="Save and apply", wait=3)
    result = wait_task(d, "Save the nodes of Osaka")
    check(result == "Nodes saved and applied", f"saved ({result})")
    subs_page(d)
    check((summary(d, "Osaka") or "").startswith("Nodes added by hand · 1 node · "),
          f"Osaka holds 1 node, added by hand ({summary(d, 'Osaka')})")

    step("its page replaces the hand-added nodes from a file")
    put_download(d, "lujarch-nodes.yaml",
                 "proxies:\n" + fx.nodes["Osaka-1"].yaml() + fx.nodes["Nagoya-1"].yaml())
    d.tap(text="Osaka", wait=3)
    d.tap(d.scroll_to(text="Replace nodes"), wait=2)
    d.tap(text="Choose a file", wait=3)
    pick_download(d, "lujarch-nodes.yaml")
    d.wait(text="Save and apply", timeout=30)
    d.tap(rid="px_manual_country")
    d.type("JP")
    d.tap(text="Data center")
    d.tap(text="Save and apply", wait=3)
    result = wait_task(d, "Save the nodes of Osaka")
    check(result == "Nodes saved and applied", f"saved ({result})")
    subs_page(d)
    check((summary(d, "Osaka") or "").startswith("Nodes added by hand · 2 nodes · "),
          f"Osaka holds the file's 2 nodes ({summary(d, 'Osaka')})")
    d.shot("hand-added-from-file")


def removing(d):
    step("a subscription's page removes it")
    subs_page(d)
    d.tap(text="Alpha", wait=3)
    d.tap(d.scroll_to(text="Remove"), wait=2)
    d.wait(text="Remove Alpha?")
    d.shot("remove-confirm")
    d.tap(d.find(text="Remove", rid="button1"), wait=3)
    subs_page(d)
    until(lambda: not d.find(text="Alpha"), "Alpha is gone from the list", 60)
    check(d.find(text="Osaka"), "Osaka stays")
    d.shot("removed")


run(main)
