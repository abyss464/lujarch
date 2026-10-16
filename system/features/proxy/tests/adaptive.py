"""An adaptive group uses, per connection, the member expected to connect fastest on the network the
phone is on; a member that fails twice in a row gives way; its page shows the network, the member
used first and why, the members in the order they are tried with their success rate and times,
and the networks it remembers; Measure all members tests them on this network; pinning asks
first and holds on every network, Automatic choice unpins; Advanced > Adaptive groups holds the
parameters.

verifies: PRX-10
timeout: 2100
"""
import re

from lujarch_test import check, device, note, run, step

from _proxy import (WEB_NAME, Apps, Fixtures, join, open_page, set_final, setup, summary, via,
                    wait_task, until)

GROUP = "Tokyo - Adaptive"
NET1 = "Wi-Fi (192.168.99.0/25)"
NET2 = "Wi-Fi (192.168.99.128/25)"


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        make_adaptive(d)
        page_on_network(d, apps)
        failing_member(d, fx, apps)
        other_network(d, apps)
        pin(d, apps)
        parameters(d)
    finally:
        join(d, "VirtWifi")
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def make_adaptive(d):
    step("a custom group Tokyo made adaptive, and the final rule sent to it")
    open_page(d, "Rules", "Custom groups")
    d.tap(text="Add custom group", wait=2)
    d.tap(rid="px_custom_key")
    d.type("Tokyo")
    d.tap(rid="px_manual_country")
    d.type("JP")
    d.tap(rid="px_custom_contains")
    d.type("Tokyo")
    d.tap(text="Save and apply", wait=3)
    check(wait_task(d, "Add Tokyo") == "Tokyo added and applied", "the group is added")
    d.tap(text="Tokyo", wait=3)
    d.tap(d.switch_of("Adaptive choice"), wait=2)
    d.wait(text="Make this an adaptive group?")
    d.tap(text="Make adaptive", wait=3)
    check(wait_task(d, "Making “Tokyo” adaptive") == "“Tokyo” is an adaptive group", "it is adaptive")
    set_final(d, GROUP)


def adaptive_page(d):
    open_page(d, "Proxy groups")
    d.tap(d.scroll_to(text=GROUP), wait=4)
    d.wait(text="Members, in the order they are tried")


def members(d):
    """[(member, its line)] in the order the page lists them."""
    nodes = d.ui()
    header = d.find(contains="in the order they are tried", nodes=nodes)
    titles = sorted((n for n in nodes if n.text in ("Tokyo 1", "Tokyo 2") and n.y > header.y),
                    key=lambda n: n.y)
    return [(t.text, summary(d, t.text, nodes)) for t in titles]


def page_on_network(d, apps):
    step("the group's page: the network, the member used first and why, the members in order")
    adaptive_page(d)
    head = summary(d, GROUP)
    choice = summary(d, "Choice") or ""
    order = members(d)
    note(f"{head}; choice: {choice}; members: {order}")
    check(head == f"Adaptive · {NET1}", "the network the phone is on")
    check(re.match(r"Tokyo [12] · (Nothing measured on this network yet: in configuration order|"
                   r"Lowest expected time on this network|Kept: no other member is \d+% faster)$", choice),
          "the member used first and why")
    check(len(order) == 2 and all(line == "Not measured here yet" or "success" in line for _, line in order),
          "both members, in the order they are tried, each measured or not yet")
    d.shot("adaptive-new")

    step("Measure all members: their success rate and times on this network")
    d.tap(text="Measure all members", wait=3)
    until(lambda: all(re.match(r"\d+% success · first byte \d+\sms", line or "") for _, line in members(d)),
          "the members are measured", 60)
    choice = summary(d, "Choice") or ""
    note(f"choice: {choice}; members: {members(d)}")
    check(choice.endswith("Lowest expected time on this network") or "Kept" in choice, "the choice says why")
    d.shot("adaptive-measured")

    step("the probe's connections use the group")
    for _ in range(3):
        answer = apps.reach(f"{WEB_NAME}:80")
        check(via(answer, "Tokyo 1") or via(answer, "Tokyo 2"), f"a member carries it ({answer})")
    adaptive_page(d)
    record = summary(d, "On this network") or ""
    note(record)
    check(re.match(r"[1-9]\d* connections?", record), "the connections on this network are counted")


def success(order, name):
    line = dict(order).get(name) or ""
    m = re.search(r"(\d+)% success", line)
    return int(m.group(1)) if m else None


def failing_member(d, fx, apps):
    step("a member that fails twice in a row gives way to the next")
    adaptive_page(d)
    first = members(d)[0][0]
    other = "Tokyo 1" if first == "Tokyo 2" else "Tokyo 2"
    note(f"first: {first}")
    fx.nodes[first].down()
    try:
        answers = [apps.reach(f"{WEB_NAME}:80") for _ in range(3)]
        note(f"answers: {answers}")
        check(all(via(a, other) for a in answers), f"{other} carries the connections")
        adaptive_page(d)
        d.tap(text="Measure all members", wait=8)
        adaptive_page(d)
        order = members(d)
        note(f"members: {order}")
        d.shot("adaptive-failing")
        check(order[0][0] == other, f"{first} gives way: {other} is tried first")
        check(via(apps.reach(f"{WEB_NAME}:80"), other), f"{other} keeps the connections")
        low = success(order, first)
    finally:
        fx.nodes[first].up()

    step("until it succeeds again")
    for _ in range(2):
        d.tap(text="Measure all members", wait=8)
        adaptive_page(d)
    order = members(d)
    note(f"after it answers again: {order}")
    d.shot("adaptive-recovered")
    check(success(order, first) > low, f"its success rate rises again ({low}% before)")
    check(not any("Failing" in (line or "") for _, line in order), "no member is failing")


def other_network(d, apps):
    step("another network: the group learns it apart and remembers both")
    join(d, "VirtWifi2")
    apps.reach(f"{WEB_NAME}:80")
    adaptive_page(d)
    head = summary(d, GROUP)
    order = members(d)
    note(f"{head}; members: {order}")
    check(head == f"Adaptive · {NET2}", "the page shows the network the phone is on now")
    nets = d.scroll_to(text="Networks")
    check(d.scroll_to(text=NET1) and d.scroll_to(text=NET2), "both networks are remembered")
    d.shot("adaptive-networks")
    join(d, "VirtWifi")


def pin(d, apps):
    step("pinning a member asks first, says what it stops, and holds on every network")
    adaptive_page(d)
    d.tap(d.scroll_to(text="Tokyo 1"), wait=2)
    message = d.find(contains="on every network")
    check(d.find(text="Pin this member?") and message, "a confirmation says what pinning stops")
    note(message.text)
    d.shot("adaptive-pin-confirm")
    d.tap(text="Pin", wait=4)
    until(lambda: summary(d, "Choice") == "Pinned to “Tokyo 1”", "the group is pinned", 30)
    check(d.find(text="Automatic choice"), "Automatic choice is offered")
    d.shot("adaptive-pinned")
    check(via(apps.reach(f"{WEB_NAME}:80"), "Tokyo 1"), "the pinned member carries the connections")
    join(d, "VirtWifi2")
    check(via(apps.reach(f"{WEB_NAME}:80"), "Tokyo 1"), "on the other network too")
    join(d, "VirtWifi")

    step("Automatic choice unpins it")
    adaptive_page(d)
    d.tap(d.scroll_to(text="Automatic choice"), wait=4)
    until(lambda: not (summary(d, "Choice") or "").startswith("Pinned"), "the pin is gone", 30)
    check(not d.find(text="Automatic choice"), "the group chooses again")
    d.shot("adaptive-automatic")


def parameters(d):
    step("Advanced > Adaptive groups: the parameters of the choice")
    open_page(d, "Adaptive groups")
    for title in ("Reaction to failures", "Memory of failures", "Price of a parallel attempt",
                  "Switching margin", "Probe interval", "Attempt limit"):
        check(d.scroll_to(text=title), f"{title} is there ({summary(d, title)})")
    d.shot("adaptive-parameters")


run(main)
