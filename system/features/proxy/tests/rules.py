"""Rules holds the rule sets in order, each with on or off, its rule count and where it applies,
and the final rule; a rule set's page adds, changes, moves and removes rules, imports a rule list
from a file, changes the set's targets, moves the set and removes it; a rule is changed only while
it is still as shown; Rule check tells which rule a domain matches; custom groups collect nodes by
country, line type and name, as groups that choose by delay or as one adaptive group; every change
is applied to the running proxy.

verifies: PRX-15
timeout: 2700
"""
import time

from lujarch_test import check, device, note, run, step

from _proxy import (WEB_IP, WEB_NAME, Apps, Fixtures, open_page, pick_download, put_download, setup,
                    summary, until, via, wait_task)

SET = "test-sites"


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    try:
        setup(d, fx)
        overview(d)
        add(d, fx, apps)
        rule_check(d)
        change(d, apps)
        move_and_remove(d, fx, apps)
        import_list(d, apps)
        retarget(d, apps)
        set_settings(d, apps)
        as_shown(d, apps)
        final_rule(d, fx, apps)
        custom_groups(d, apps)
        remove_set(d, apps)
    finally:
        d.sh("rm -f /sdcard/Download/lujarch-rules.txt", check=False)
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def rules_page(d):
    open_page(d, "Rules")
    d.wait(text="Rule check")


def set_page(d, name=SET):
    rules_page(d)
    d.tap(d.scroll_to(text=name), wait=3)
    d.wait(text="Use this rule set")


def listed(d, text):
    """The item `text` of the open drop-down list, scrolled into view inside the list (a swipe
    that starts outside it closes it)."""
    for direction in (1, -1):
        last = None
        for _ in range(8):
            items = [n for n in d.ui() if n.cls.endswith("CheckedTextView")]
            hit = next((n for n in items if n.text == text), None)
            if hit:
                return hit
            check(items, "the list is open")
            if [n.text for n in items] == last:
                break
            last = [n.text for n in items]
            top, bottom = min(n.y for n in items), max(n.y for n in items)
            x = items[0].x
            if direction == 1:
                d.swipe(x, top, x, bottom)
            else:
                d.swipe(x, bottom, x, top)
    check(False, f"the list offers {text}")


def editor(d, kind, pattern, to, position=None):
    """The rule editor on screen: type, pattern, target (and position), saved and applied."""
    current = [n for n in d.ui() if n.cls.endswith("CheckedTextView")]
    if current and current[0].text != kind:
        d.tap(current[0], wait=1.5)
        d.tap(listed(d, kind), wait=1.5)
    field = d.find(rid="px_rule_pattern")
    d.tap(field)
    d.sh("input keyevent KEYCODE_MOVE_END; for i in $(seq 1 60); do input keyevent KEYCODE_DEL; done", check=False)
    d.type(pattern)
    d.tap(d.find(text="Choose a target") or [n for n in d.ui() if n.cls.endswith("Button") and n.y < 1600
                                              and n.text not in ("Cancel", "Save and apply")][0], wait=2)
    d.tap(d.scroll_to(text=to), wait=2)
    if position:
        d.tap(rid="px_rule_position")
        d.type(str(position))
    d.tap(text="Save and apply", wait=3)


def set_position(d, n):
    """The set's Position, changed to n and saved."""
    d.tap(d.scroll_to(text="Position"), wait=2)
    d.tap(rid="px_rule_set_position")
    d.sh("input keyevent KEYCODE_MOVE_END KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL", check=False)
    d.type(str(n))
    d.tap(text="Save and apply", wait=3)


def probe(fx, apps, target):
    since = time.time()
    answer = apps.reach(target)
    note(f"{target}: {answer}; nodes asked: {fx.via(since) if fx else '-'}")
    return answer


def overview(d):
    step("Rules: the rule sets in order, and the final rule")
    rules_page(d)
    check(summary(d, "Final rule") == "JP Residential - Auto", "the final rule's target")
    d.scroll_to(text="default")
    check(summary(d, "default") == "On · 1 rule · holds the final rule", f"the set holding it ({summary(d, 'default')})")
    d.shot("rules")


def add(d, fx, apps):
    step("Add a rule in a new set: lujarch.test and its subdomains to US - Auto")
    rules_page(d)
    d.tap(text="Add a rule", wait=2)
    d.tap(text="New rule set", wait=2)
    d.tap(rid="px_rule_new_set")
    d.type(SET)
    d.tap(text="Next", wait=2)
    editor(d, "Domain and subdomains (DOMAIN-SUFFIX)", "lujarch.test", "US - Auto")
    s = wait_task(d, f"Add a rule to {SET}")
    check(s == f"Rule added to {SET} and applied", f"added ({s})")
    rules_page(d)
    d.scroll_to(text="default")
    nodes = d.ui()
    new, default = d.find(text=SET, nodes=nodes), d.find(text="default", nodes=nodes)
    check(new and default and new.y < default.y, "the new set comes before the set holding the final rule")
    check(summary(d, SET, nodes) == "On · 1 rule", f"on, with its count ({summary(d, SET, nodes)})")
    d.shot("rules-new-set")
    check(via(probe(fx, apps, f"{WEB_NAME}:80"), "Dallas 1"), "the running proxy applies it at once")


def rule_check(d):
    step("Rule check tells which rule a domain matches")
    rules_page(d)
    d.tap(text="Rule check", wait=2)
    d.type(WEB_NAME)
    d.tap(text="Check", wait=4)
    message = until(lambda: d.find(contains="matches rule"), "the answer", 30)
    note(message.text)
    check(f"{WEB_NAME} matches rule 1 of the rule set {SET}" in message.text and "US - Auto" in message.text,
          "it names the set, the rule and where the traffic goes")
    d.shot("rule-check")
    d.tap(text="OK", wait=1)


def rule_actions(d, pattern):
    set_page(d)
    d.tap(d.scroll_to(text=pattern), wait=2)


def change(d, apps):
    step("Change the rule: its target to JP Datacenter - Auto")
    rule_actions(d, "lujarch.test")
    d.tap(text="Change the rule", wait=2)
    editor(d, "Domain and subdomains (DOMAIN-SUFFIX)", "lujarch.test", "JP Datacenter - Auto")
    s = wait_task(d, "Change the rule for lujarch.test")
    check(s == "Rule for lujarch.test changed and applied", f"changed ({s})")
    check(via(probe(None, apps, f"{WEB_NAME}:80"), "Tokyo 2"), "the probe follows the changed rule")


def move_and_remove(d, fx, apps):
    step("a second rule, moved above the first, decides")
    set_page(d)
    d.tap(d.scroll_to(text="Add a rule"), wait=2)
    editor(d, "Domain (DOMAIN)", WEB_NAME, "Direct")
    s = wait_task(d, f"Add a rule to {SET}")
    check(s == f"Rule added to {SET} and applied", f"added at the end ({s})")
    check(via(probe(fx, apps, f"{WEB_NAME}:80"), "Tokyo 2"), "the first rule still decides")
    rule_actions(d, WEB_NAME)
    d.tap(text="Move up", wait=3)
    s = wait_task(d, f"Move the rule for {WEB_NAME}")
    check(s == f"Rule for {WEB_NAME} moved and applied", f"moved ({s})")
    set_page(d)
    d.scroll_to(text="lujarch.test")
    nodes = d.ui()
    first, second = d.find(text=WEB_NAME, nodes=nodes), d.find(text="lujarch.test", nodes=nodes)
    check(first and second and first.y < second.y, "it is first now")
    d.shot("rule-moved")
    since = time.time()
    answer = apps.reach(f"{WEB_NAME}:80")
    check(not fx.via(since) and not answer.startswith("connected HTTP"), f"Direct decides: no node ({answer})")

    step("Remove the rule")
    rule_actions(d, WEB_NAME)
    d.tap(text="Remove", wait=2)
    d.tap(d.find(text="Remove", rid="button1"), wait=3)
    s = wait_task(d, f"Remove the rule for {WEB_NAME}")
    check(s == f"Rule for {WEB_NAME} removed and applied", f"removed ({s})")
    check(via(probe(fx, apps, f"{WEB_NAME}:80"), "Tokyo 2"), "the first rule decides again")


def import_list(d, apps):
    step("Import rules from a file, added at the end of the set")
    put_download(d, "lujarch-rules.txt", f"# test rules\nIP-CIDR,{WEB_IP}/32,US - Auto,no-resolve\n")
    set_page(d)
    d.tap(d.scroll_to(text="Import rules from a file"), wait=3)
    pick_download(d, "lujarch-rules.txt")
    question = until(lambda: d.find(contains="1 line of the file"), "it asks before importing", 30)
    note(question.text)
    d.tap(text="Import", wait=3)
    s = wait_task(d, f"Import rules into {SET}")
    check(s == f"Rules imported into {SET} and applied", f"imported ({s})")
    check(d.scroll_to(text=f"{WEB_IP}/32"), "the imported rule is in the set")
    d.shot("rules-imported")
    check(via(probe(None, apps, f"{WEB_IP}:80"), "Dallas 1"), "the imported rule applies")


def retarget(d, apps):
    step("Change targets: the set's rules to JP Datacenter - Auto go to JP Residential - Auto")
    set_page(d)
    d.tap(d.scroll_to(text="Change targets"), wait=2)
    d.tap(text="Choose the current target", wait=2)
    d.tap(d.scroll_to(text="JP Datacenter - Auto"), wait=2)
    d.tap(text="Choose the new target", wait=2)
    d.tap(d.scroll_to(text="JP Residential - Auto"), wait=2)
    d.tap(text="Save and apply", wait=3)
    s = wait_task(d, "Change targets to JP Residential - Auto")
    check(s == "Targets changed to JP Residential - Auto and applied", f"changed ({s})")
    check(via(probe(None, apps, f"{WEB_NAME}:80"), "Tokyo 1"), "the rule goes to the new target")


def set_settings(d, apps):
    step("the set moved after the set holding the final rule is never reached")
    set_page(d)
    set_position(d, 2)
    s = wait_task(d, f"Move {SET}")
    check(s == f"{SET} is set 2 now", f"moved ({s})")
    check(via(probe(None, apps, f"{WEB_IP}:80"), "Tokyo 1"), "its rules are not reached: the final rule decides")
    set_page(d)
    set_position(d, 1)
    check(wait_task(d, f"Move {SET}") == f"{SET} is set 1 now", "moved back")
    check(via(probe(None, apps, f"{WEB_IP}:80"), "Dallas 1"), "its rules decide again")

    step("the set turned off is left out")
    set_page(d)
    d.tap(d.switch_of("Use this rule set"), wait=3)
    s = wait_task(d, f"Turn off {SET}")
    check(s == f"{SET} is off and applied", f"off ({s})")
    rules_page(d)
    d.scroll_to(text=SET)
    check((summary(d, SET) or "").startswith("Off"), f"the list shows it off ({summary(d, SET)})")
    d.shot("rules-set-off")
    check(via(probe(None, apps, f"{WEB_IP}:80"), "Tokyo 1"), "its rules do not apply")
    set_page(d)
    d.tap(d.switch_of("Use this rule set"), wait=3)
    check(wait_task(d, f"Turn on {SET}") == f"{SET} is on and applied", "on again")

    step("where it applies: computers only leaves it out on this phone")
    set_page(d)
    d.tap(d.scroll_to(text="Applies on"), wait=2)
    d.tap(text="Computers only", wait=3)
    s = wait_task(d, f"Change where {SET} applies")
    check(s == f"{SET} applies there now", f"changed ({s})")
    rules_page(d)
    d.scroll_to(text=SET)
    check("computers only" in (summary(d, SET) or ""), f"the list says where it applies ({summary(d, SET)})")
    check(via(probe(None, apps, f"{WEB_IP}:80"), "Tokyo 1"), "on this phone its rules do not apply")
    set_page(d)
    d.tap(d.scroll_to(text="Applies on"), wait=2)
    d.tap(text="Every device", wait=3)
    wait_task(d, f"Change where {SET} applies")
    check(via(probe(None, apps, f"{WEB_IP}:80"), "Dallas 1"), "every device: they apply again")


def as_shown(d, apps):
    step("a rule is changed only while it is still as shown")
    set_page(d)
    d.scroll_to(text="lujarch.test")
    # Test setup: the rule changes behind the page, as another change of the rules would.
    d.sh("sed -i 's/DOMAIN-SUFFIX,lujarch.test,JP\\/residential/DOMAIN-SUFFIX,lujarch.test,US/' "
         "/data/lujarch/proxy/manager/routing.yaml")
    d.tap(d.scroll_to(text="lujarch.test"), wait=2)
    d.tap(text="Change the rule", wait=2)
    editor(d, "Domain and subdomains (DOMAIN-SUFFIX)", "lujarch.test", "JP Datacenter - Auto")
    s = wait_task(d, "Change the rule for lujarch.test")
    note(s)
    d.shot("rule-changed-meanwhile")
    check("The list changed in the meantime" in s, "the change is refused and the list is read again")
    set_page(d)
    d.scroll_to(text="lujarch.test")
    line = summary(d, "lujarch.test") or ""
    check("to US" in line, f"the page shows the rule as it is ({line})")


def final_rule(d, fx, apps):
    step("the final rule takes every connection no other rule matched")
    rules_page(d)
    d.tap(d.scroll_to(text="Final rule"), wait=2)
    d.tap(d.scroll_to(text="Direct"), wait=3)
    until(lambda: d.find(text="Final rule: Direct"), "the final rule goes to Direct", 60)
    since = time.time()
    answer = apps.reach("203.0.113.9:80")
    check(not fx.via(since) and not answer.startswith("connected HTTP"), f"unmatched traffic goes direct ({answer})")
    rules_page(d)
    d.tap(d.scroll_to(text="Final rule"), wait=2)
    d.tap(d.scroll_to(text="JP Residential - Auto"), wait=3)
    until(lambda: d.find(text="Final rule: JP Residential - Auto"), "the final rule is back", 60)


def custom_groups(d, apps):
    step("a custom group collects nodes by country, line type and name")
    open_page(d, "Rules", "Custom groups")
    d.tap(text="Add custom group", wait=2)
    d.tap(rid="px_custom_key")
    d.type("Fast-JP")
    d.tap(rid="px_manual_country")
    d.type("JP")
    d.tap(text="Data center")
    d.tap(rid="px_custom_contains")
    d.type("Tokyo")
    d.tap(text="Save and apply", wait=3)
    check(wait_task(d, "Add Fast-JP") == "Fast-JP added and applied", "added")
    d.scroll_to(text="Fast-JP")
    line = summary(d, "Fast-JP") or ""
    note(line)
    check("1 node" in line, "it holds the one node that matches every filter")
    open_page(d, "Proxy groups")
    texts = d.texts()
    check({"Fast-JP - Auto"} <= set(texts), "it is a group that chooses by delay")
    d.shot("custom-group")
    rules_page(d)
    d.tap(d.scroll_to(text="Change targets"), wait=2)
    d.tap(text="Choose the current target", wait=2)
    d.tap(d.scroll_to(text="US - Auto"), wait=2)
    d.tap(text="Choose the new target", wait=2)
    d.tap(d.scroll_to(text="Fast-JP - Auto"), wait=2)
    d.tap(text="Save and apply", wait=3)
    wait_task(d, "Change targets to Fast-JP - Auto")
    check(via(probe(None, apps, f"{WEB_IP}:80"), "Tokyo 2"), "rules can send traffic to it")

    step("as one adaptive group")
    open_page(d, "Rules", "Custom groups")
    d.tap(text="Fast-JP", wait=3)
    d.tap(d.switch_of("Adaptive choice"), wait=2)
    d.tap(text="Make adaptive", wait=3)
    check(wait_task(d, "Making “Fast-JP” adaptive") == "“Fast-JP” is an adaptive group", "adaptive")
    open_page(d, "Proxy groups")
    check(d.scroll_to(text="Fast-JP - Adaptive"), "the adaptive group replaces its delay groups")
    check(not d.find(text="Fast-JP - Auto"), "and the delay group is gone")
    check(via(probe(None, apps, f"{WEB_IP}:80"), "Tokyo 2"), "the rule now uses the adaptive group")


def remove_set(d, apps):
    step("Remove this rule set")
    set_page(d)
    d.tap(d.scroll_to(text="Remove this rule set"), wait=2)
    d.tap(d.find(text="Remove", rid="button1") or d.find(text="Remove"), wait=3)
    rules_page(d)
    until(lambda: not d.find(text=SET), "the set is gone", 60)
    d.shot("rules-set-removed")
    check(via(probe(None, apps, f"{WEB_IP}:80"), "Tokyo 1"), "its rules no longer apply")


run(main)
