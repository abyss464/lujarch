"""Advanced: Apply configuration (built, checked and loaded; when the proxy does not take it, the
previous one stays), Check configuration (built and checked, not loaded), Roll back configuration
(the last working one), Restart proxy; Configuration files lists every file with its size and
time, exports them to a file the owner chooses (subscription addresses and node passwords only
when included after a warning), imports such a file after saying what it holds, and keeps the
replaced files to restore; each action that interrupts the proxy asks first.

verifies: PRX-17
timeout: 2700
"""
import re
import time

from lujarch_test import check, device, note, run, step

from _proxy import (WEB_NAME, Apps, Fixtures, core_pid, open_page, pick_download, proxy_page,
                    save_download, set_final, setup, summary, until, via, wait_state, wait_status,
                    wait_task)

TOKEN = "alpha-7Qk2"
PASSWORD = "not-a-real-password"


def main():
    d = device()
    fx = Fixtures().up()
    apps = Apps(d).install()
    d.sh("rm -f /sdcard/Download/lujarch-proxy-*.json", check=False)
    try:
        setup(d, fx)
        check_and_apply(d, apps)
        roll_back(d, apps)
        restart(d)
        files(d)
        exports(d)
        import_and_restore(d, apps)
    finally:
        d.sh("rm -f /sdcard/Download/lujarch-proxy-*.json", check=False)
        d.put_setting("global", "lujarch_proxy_enabled", 0)
        d.home()


def task_result(d, title):
    """The result page of the change that is shown (TaskFragment): its texts."""
    until(lambda: d.find(text="Result") or d.find(contains="Failed"), f"{title} has a result", 120)
    time.sleep(2)
    return " | ".join(d.texts())


def offline_rule(d):
    """A rule the offline phone's core check refuses (its domain list cannot be downloaded)."""
    open_page(d, "Rules")
    d.tap(text="Add a rule", wait=2)
    d.tap(text="default", wait=2)
    d.tap(text="Domain and subdomains (DOMAIN-SUFFIX)", wait=1.5)
    d.tap(text="Domain category (GEOSITE)", wait=1.5)
    d.tap(rid="px_rule_pattern")
    d.type("google")
    d.tap(text="Choose a target", wait=2)
    d.tap(text="Direct", wait=1.5)
    d.tap(text="Save and apply", wait=3)
    return wait_task(d, "Add a rule to default")


def remove_offline_rule(d):
    open_page(d, "Rules")
    d.tap(d.scroll_to(text="default"), wait=3)
    d.tap(d.scroll_to(text="google"), wait=2)
    d.tap(text="Remove", wait=2)
    d.tap(d.find(text="Remove", rid="button1"), wait=3)
    return wait_task(d, "Remove the rule for google")


def check_and_apply(d, apps):
    step("a configuration the core refuses: Check says so, Apply keeps the previous one")
    s = offline_rule(d)
    check(s.startswith("Failed"), f"the rule's own apply is refused ({s})")
    open_page(d, "Check configuration")
    texts = task_result(d, "Check configuration")
    note(texts[:300])
    d.shot("check-refused")
    check("Failed" in texts or "did not accept" in texts or "not valid" in texts, "Check says the configuration fails")
    open_page(d, "Apply configuration")
    question = d.find(contains="If the proxy does not accept it")
    check(question, "Apply asks first")
    d.tap(text="Apply", wait=3)
    texts = task_result(d, "Apply configuration")
    note(texts[:300])
    d.shot("apply-refused")
    check("did not accept" in texts or "Failed" in texts, "the proxy does not take it")
    answer = apps.reach(f"{WEB_NAME}:80")
    check(via(answer, "Tokyo 1"), f"the previous configuration stays in use ({answer})")

    step("the cause removed: Check passes, Apply loads it")
    remove_offline_rule(d)
    set_final(d, "US - Auto")
    open_page(d, "Check configuration")
    texts = task_result(d, "Check configuration")
    check("The configuration is valid" in texts, "Check passes")
    open_page(d, "Apply configuration")
    d.tap(text="Apply", wait=3)
    texts = task_result(d, "Apply configuration")
    note(texts[:300])
    check("Loaded and working" in texts, "Apply loads it")
    d.shot("applied")
    check(via(apps.reach(f"{WEB_NAME}:80"), "Dallas 1"), "the proxy uses it")


def roll_back(d, apps):
    step("Roll back configuration: the last working one")
    # Test setup: the current configuration breaks (its first rule names a group that does not
    # exist), so the proxy runs the last working one.
    d.sh("sed -i 's/^rules:$/rules:\\n- MATCH,No-Such-Group/' /data/lujarch/proxy/config.yaml")
    open_page(d, "Restart proxy")
    d.tap(d.wait(text="Restart"), wait=3)
    proxy_page(d)
    wait_status(d, "Running on the last working configuration", timeout=90)
    open_page(d, "Roll back configuration")
    check(d.find(contains="Replace the current configuration with the last one that worked"), "it asks first")
    d.tap(text="Roll back", wait=3)
    texts = task_result(d, "Roll back configuration")
    note(texts[:300])
    check("The last working configuration is in use" in texts, "the result says so")
    d.shot("rolled-back")
    open_page(d, "Restart proxy")
    d.tap(d.wait(text="Restart"), wait=3)
    proxy_page(d)
    s = wait_status(d, "Running · ", "Running on the last", timeout=90)
    check(s.startswith("Running · "), f"the current configuration is the working one again ({s})")


def restart(d):
    step("Restart proxy asks first, then stops and starts the core")
    before = core_pid(d)
    open_page(d, "Restart proxy")
    check(d.find(text="Connections through the proxy are interrupted for a few seconds."), "it says what it interrupts")
    d.shot("restart-asks")
    d.tap(d.wait(text="Restart"), wait=2)
    wait_state(d, "running", timeout=60)
    after = until(lambda: core_pid(d) if core_pid(d) != before else None, "a new core runs", 60)
    note(f"core {before} -> {after}")


def files(d):
    step("Configuration files: every file with its size and time")
    open_page(d, "Configuration files")
    d.wait(text="Files", timeout=30)
    d.shot("configuration-files")
    sized = r"\d+(\.\d+)?\s?(B|kB|MB) · "
    for title in ("Proxy settings", "Routing rules", "Subscription list", "Node lists", "Node locations"):
        line = summary(d, title) or (d.scroll_to(text=title) and summary(d, title)) or ""
        note(f"{title}: {line}")
        if title == "Proxy settings" and line == "Not present":
            continue  # no setting changed yet on this phone
        check(re.search(sized, line) and ("changed" in line or "updated" in line), f"{title}: its size and time")


def exported():
    from lujarch_test import device as dev
    out = dev().sh("ls -t /sdcard/Download/ | grep '^lujarch-proxy-.*json$' | head -1", check=False).strip()
    return out or None


def export(d, secrets):
    before = exported()
    open_page(d, "Configuration files")
    d.tap(d.scroll_to(text="Export all"), wait=2)
    if secrets:
        d.tap(text="Include subscription addresses and node passwords", wait=1)
    d.tap(text="Next", wait=2)
    if secrets:
        warning = d.find(contains="Anyone who gets the exported file")
        check(d.find(text="Include access keys?") and warning, "a warning comes first")
        d.shot("export-warning")
        d.tap(text="Include and export", wait=3)
    save_download(d, None)
    name = until(lambda: exported() if exported() != before else None, "the file is written", 60)
    text = d.sh(f"cat /sdcard/Download/{name}")
    note(f"{name}: {len(text)} bytes")
    return name, text


def exports(d):
    step("Export all writes the files to a file the owner chooses, without access keys")
    name, text = export(d, secrets=False)
    check('"lujarch-proxy"' in text, "it is the proxy's export")
    check('"routing.yaml"' in text, "it holds the routing rules")
    check(TOKEN not in text and PASSWORD not in text, "without subscription addresses and node passwords")

    step("with access keys only after a warning")
    name, text = export(d, secrets=True)
    check(TOKEN in text and PASSWORD in text, "with them included")


def import_and_restore(d, apps):
    step("Import replaces the files after saying what the file holds")
    name = exported()
    set_final(d, "Direct")
    open_page(d, "Configuration files")
    d.tap(d.scroll_to(text="Import all"), wait=3)
    pick_download(d, name)
    message = until(lambda: d.find(contains="The file holds"), "it says what the file holds", 30)
    note(message.text)
    check("subscription addresses and node passwords included" in message.text, "it says the keys are included")
    d.shot("import-asks")
    d.tap(text="Import", wait=3)
    texts = task_result(d, "Import all files")
    note(texts[:300])
    check("Imported and applied" in texts, "imported and applied")
    check(via(apps.reach(f"{WEB_NAME}:80"), "Dallas 1"), "the imported rules are in use")

    step("the replaced files are kept and can be restored")
    open_page(d, "Configuration files")
    row = d.scroll_to(contains="Before all files were imported")
    d.shot("previous-files")
    d.tap(row, wait=2)
    check(d.find(contains="Put back"), "it asks first")
    d.tap(text="Restore", wait=3)
    texts = task_result(d, "Restore previous files")
    check("Restored and applied" in texts, "restored and applied")
    answer = apps.reach(f"{WEB_NAME}:80")
    check(not answer.startswith("connected HTTP/1.0 200 via"), f"the replaced rules (final rule Direct) are back ({answer})")


run(main)
