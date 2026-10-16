#!/usr/bin/env python3
"""The signing step of a release (process/release-flow.md), with the tree's own tools.

  sign.py --tree TREE --keys KEYS [--app-key APK=KEY ...] TARGET_FILES WORK

Signs TARGET_FILES, a build's target files, with the keys in KEYS and writes into WORK:

  signed-target_files.zip  the target files, signed
  ota.zip                  the OTA package made from them, signed with releasekey
  images.zip               the images that install the system from scratch
  keys.txt                 the key that signs each APK and APEX, "<file>: <key>"; PRESIGNED for
                           those that keep the signatures they were built with

This is the one step of a release that is given the keys' location. It passes it on to the
tree's tools and opens no key itself.
"""

import argparse
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

RELEASE_KEY = "releasekey"
# The build's own default keys, which the release key replaces.
DEFAULT_KEYS = {"testkey": RELEASE_KEY, "devkey": RELEASE_KEY}
AVB_KEY = "avb.pem"
AVB_ALGORITHM = "SHA256_RSA4096"
# The partitions sign_target_files_apks takes an AVB key for by an option of their own; it takes
# any other by --avb_extra_custom_image_key.
AVB_OPTIONS = {"vbmeta", "boot", "init_boot", "dtbo", "recovery", "system", "system_other",
               "vendor", "vbmeta_system", "vbmeta_vendor"}
PRESIGNED = "PRESIGNED"


def log(message):
    print(f"[lujarch] {message}", file=sys.stderr, flush=True)


def die(message):
    log(message)
    sys.exit(1)


def fields(line):
    return dict(re.findall(r'(\w+)="([^"]*)"', line))


def apex_name(entry):
    """An APEX's name as META/apexkeys.txt has it: the file name, a compressed one's as .apex."""
    name = os.path.basename(entry)
    return name[:-len(".capex")] + ".apex" if name.endswith(".capex") else name


def key_plan(tree, keys, target_files, app_keys):
    """sign_target_files_apks' key options, the key of each APK and APEX by file name, and the
    key files they need."""
    with zipfile.ZipFile(target_files) as z:
        names = z.namelist()
        misc = dict(line.split("=", 1) for line in z.read("META/misc_info.txt").decode().split("\n")
                    if "=" in line)
        apkcerts = [fields(line) for line in z.read("META/apkcerts.txt").decode().split("\n")]
        apexkeys = [fields(line) for line in z.read("META/apexkeys.txt").decode().split("\n")]
    dev_dir = os.path.dirname(misc.get("default_system_dev_certificate",
                                       "build/make/target/product/security/testkey"))
    options, assigned, needed = [], {}, set()

    # The build's platform keys: each becomes the release key of its name.
    platform = {}
    for cert in sorted((tree / dev_dir).glob("*.x509.pem")):
        name = cert.name[:-len(".x509.pem")]
        platform[name] = DEFAULT_KEYS.get(name, name)
    for name in DEFAULT_KEYS:
        platform.setdefault(name, RELEASE_KEY)
    used = set()

    known = set()
    for entry in apkcerts:
        apk, cert = entry.get("name"), entry.get("certificate", "")
        # The data partition's APKs are tests, which the image does not hold.
        if not apk or entry.get("partition") == "data":
            continue
        known.add(apk)
        base = cert[:-len(".x509.pem")] if cert.endswith(".x509.pem") else None
        if apk in app_keys:
            key = app_keys[apk]
            options += ["-e", f"{apk}={keys / key}"]
        elif cert == PRESIGNED:
            key = PRESIGNED
        elif base and os.path.dirname(base) == dev_dir and os.path.basename(base) in platform:
            key = platform[os.path.basename(base)]
            used.add(os.path.basename(base))
        else:
            # A module's own key, whose private half is in the tree.
            key = RELEASE_KEY
            options += ["-e", f"{apk}={keys / key}"]
        assigned[apk] = key
        if key != PRESIGNED:
            needed.update({f"{key}.pk8", f"{key}.x509.pem"})
    for apk in sorted(set(app_keys) - known):
        die(f"--app-key {apk}: the build has no APK {apk}")

    # Every platform key the release has a key for is mapped, whether an APK uses it or not: the
    # SELinux signers of mac_permissions.xml name them too.
    for name, key in sorted(platform.items()):
        if name in used or ((keys / f"{key}.pk8").is_file() and (keys / f"{key}.x509.pem").is_file()):
            options += ["-k", f"{dev_dir}/{name}={keys / key}"]

    held = {apex_name(n) for n in names
            if n.endswith((".apex", ".capex")) and not n.startswith(("META/", "IMAGES/"))}
    for entry in apexkeys:
        apex = entry.get("name")
        if apex not in held:
            continue
        if entry.get("container_certificate") == PRESIGNED:
            assigned[apex] = PRESIGNED
            continue
        stem = apex[:-len(".apex")]
        assigned[apex] = stem
        needed.update({f"{stem}.pk8", f"{stem}.x509.pem", f"{stem}.pem"})
        options += ["-e", f"{apex}={keys / stem}",
                    "--extra_apex_payload_key", f"{apex}={keys / stem}.pem"]

    for key in sorted(k for k in misc if re.fullmatch(r"avb_\w+_key_path", k)):
        partition = key[len("avb_"):-len("_key_path")]
        needed.add(AVB_KEY)
        if partition in AVB_OPTIONS:
            options += [f"--avb_{partition}_key", str(keys / AVB_KEY),
                        f"--avb_{partition}_algorithm", AVB_ALGORITHM]
        else:
            options += ["--avb_extra_custom_image_key", f"{partition}={keys / AVB_KEY}",
                        "--avb_extra_custom_image_algorithm", f"{partition}={AVB_ALGORITHM}"]
    return options, assigned, needed


def run(tools, env, work, name, *args):
    """Runs one of the tree's release tools, its output into WORK/<name>.log."""
    logfile = work / f"{name}.log"
    log(f"{name}; log {logfile}")
    with open(logfile, "w") as f:
        status = subprocess.run([str(tools / "bin" / name), "-p", str(tools), *args],
                                stdout=f, stderr=subprocess.STDOUT, env=env).returncode
    if status:
        print("\n".join(logfile.read_text(errors="replace").split("\n")[-40:]), file=sys.stderr)
        die(f"{name} failed (exit {status}); log {logfile}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--tree", required=True, type=Path)
    parser.add_argument("--keys", required=True, type=Path)
    parser.add_argument("--app-key", action="append", default=[], metavar="APK=KEY",
                        help="sign this APK with this key of KEYS")
    parser.add_argument("target_files", type=Path)
    parser.add_argument("work", type=Path)
    args = parser.parse_args()
    tree, keys, work = args.tree.resolve(), args.keys.resolve(), args.work.resolve()
    target_files = args.target_files.resolve()
    app_keys = dict(a.split("=", 1) for a in args.app_key)
    tmp = work / "tmp"
    tmp.mkdir(parents=True, exist_ok=True)

    options, assigned, needed = key_plan(tree, keys, target_files, app_keys)
    missing = sorted(f for f in needed if not (keys / f).is_file())
    if missing:
        die(f"the keys directory {keys} has no " + ", ".join(missing))

    tools = tree / "out" / "host" / "linux-x86"
    jdk = tree / "prebuilts" / "jdk" / "jdk21" / "linux-x86" / "bin"
    # The target files name the build's keys by their paths in the tree.
    env = dict(os.environ, TMPDIR=str(tmp),
               PATH=os.pathsep.join([str(jdk), str(tools / "bin"), os.environ.get("PATH", "")]))
    os.chdir(tree)
    signed = work / "signed-target_files.zip"
    run(tools, env, work, "sign_target_files_apks", "-o", *options, str(target_files), str(signed))
    run(tools, env, work, "ota_from_target_files", "-k", str(keys / RELEASE_KEY), str(signed),
        str(work / "ota.zip"))
    run(tools, env, work, "img_from_target_files", str(signed), str(work / "images.zip"))
    (work / "keys.txt").write_text("".join(f"{name}: {key}\n" for name, key in sorted(assigned.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
