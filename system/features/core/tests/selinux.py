"""SELinux is enforcing, every domain of the loaded policy is enforcing but su, the domain of root
access itself (Rooted debugging, Root grants), which runs nothing of the system, and About phone
shows the status.

verifies: CORE-9
"""
import os
import subprocess
from pathlib import Path

from lujarch_test import OUT, check, device, note, run, step

ROOT_ACCESS = {"su"}


def main():
    d = device()
    step("the kernel enforces the policy")
    check(d.sh("getenforce").strip() == "Enforcing", "getenforce says Enforcing")
    check(d.sh("cat /sys/fs/selinux/enforce").strip() == "1", "the kernel's enforce flag is 1")

    step("no domain of the policy the device loaded is permissive, but root access")
    policy = OUT / "policy"
    d.pull("/sys/fs/selinux/policy", policy)
    tool = Path(os.environ["LUJARCH_TREE"]) / "out" / "host" / "linux-x86" / "bin" / "sepolicy-analyze"
    out = subprocess.run([str(tool), str(policy), "permissive"], capture_output=True, text=True)
    policy.unlink()
    check(out.returncode == 0, f"sepolicy-analyze read the policy {out.stderr.strip()[:200]}")
    permissive = set(out.stdout.split())
    check(permissive <= ROOT_ACCESS, f"permissive domains: {', '.join(sorted(permissive)) or 'none'}")

    step("only root access runs in su: adb's root shell and what it starts")
    rows = [l.split() for l in d.sh("ps -A -o label,pid,ppid,name").splitlines()[1:]]
    procs = {r[1]: (r[0], r[2], r[3]) for r in rows if len(r) >= 4}
    for pid, (label, ppid, name) in procs.items():
        if not label.startswith("u:r:su:"):
            continue
        chain, p = [], pid
        while p in procs and p != "1":
            chain.append(procs[p][2])
            p = procs[p][1]
        check("adbd" in chain or "ksud" in chain, f"{name} ({pid}) in su descends from adbd: {chain}")
    note(f"{sum(1 for l, _, _ in procs.values() if l.startswith('u:r:su:'))} processes in su")

    step("About phone > Android version shows the status")
    d.unlock()
    d.start("-a android.settings.DEVICE_INFO_SETTINGS")
    d.wait(text="Device name", timeout=40)
    d.tap(d.scroll_to(text="Android version"))
    row = d.scroll_to(text="SELinux status")
    nodes = d.ui()
    below = [n for n in nodes if n.text and abs(n.x - row.x) < 400 and 0 < n.y - row.y < 140]
    check(any(n.text == "Enforcing" for n in below), "SELinux status: Enforcing")
    d.shot("about-phone-selinux")
    d.home()


run(main)
