#!/usr/bin/env python3
"""The signing step of a release (process/release-flow.md), with the tree's own tools.

  sign.py --tree TREE --keys KEYS [--app-key APK=KEY ...] TARGET_FILES WORK
  sign.py --tree TREE --keys KEYS [--app-key APK=KEY ...] --apkcerts APKCERTS APK... WORK

Signs TARGET_FILES, a build's target files, with the keys in KEYS and writes into WORK:

  signed-target_files.zip  the target files, signed
  ota.zip                  the OTA package made from them, signed with releasekey
  images.zip               the images that install the system from scratch
  keys.txt                 the key that signs each APK and APEX, "<file>: <key>"; PRESIGNED for
                           those that keep the signatures they were built with

With --apkcerts, signs each APK of a module build, for a module test on a phone, with the key
the release gives it by APKCERTS, the product's apkcerts.txt, and writes it into WORK by its name.

This is the one step of a release or a module test that is given the keys' location. It passes it on to the
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
DEFAULT_DEV_CERTIFICATE = "build/make/target/product/security/testkey"


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


def platform_keys(tree, dev_dir):
    """The build's platform keys by name, each mapped to the release key of its name."""
    platform = {}
    for cert in sorted((tree / dev_dir).glob("*.x509.pem")):
        name = cert.name[:-len(".x509.pem")]
        platform[name] = DEFAULT_KEYS.get(name, name)
    for name in DEFAULT_KEYS:
        platform.setdefault(name, RELEASE_KEY)
    return platform


def apk_key(entry, dev_dir, platform, app_keys):
    """The release key of an APK of apkcerts.txt, and the platform key it replaces, if any."""
    apk, cert = entry.get("name"), entry.get("certificate", "")
    base = cert[:-len(".x509.pem")] if cert.endswith(".x509.pem") else None
    if apk in app_keys:
        return app_keys[apk], None
    if cert == PRESIGNED:
        return PRESIGNED, None
    if base and os.path.dirname(base) == dev_dir and os.path.basename(base) in platform:
        return platform[os.path.basename(base)], os.path.basename(base)
    # A module's own key, whose private half is in the tree.
    return RELEASE_KEY, None


def key_plan(tree, keys, target_files, app_keys):
    """sign_target_files_apks' key options, the key of each APK and APEX by file name, and the
    key files they need."""
    with zipfile.ZipFile(target_files) as z:
        names = z.namelist()
        misc = dict(line.split("=", 1) for line in z.read("META/misc_info.txt").decode().split("\n")
                    if "=" in line)
        apkcerts = [fields(line) for line in z.read("META/apkcerts.txt").decode().split("\n")]
        apexkeys = [fields(line) for line in z.read("META/apexkeys.txt").decode().split("\n")]
    dev_dir = os.path.dirname(misc.get("default_system_dev_certificate", DEFAULT_DEV_CERTIFICATE))
    options, assigned, needed = [], {}, set()
    platform = platform_keys(tree, dev_dir)
    used = set()

    known = set()
    for entry in apkcerts:
        apk = entry.get("name")
        # The data partition's APKs are tests, which the image does not hold.
        if not apk or entry.get("partition") == "data":
            continue
        known.add(apk)
        key, replaced = apk_key(entry, dev_dir, platform, app_keys)
        if replaced:
            used.add(replaced)
        elif key != PRESIGNED:
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


def sign_modules(tree, keys, apkcerts, apks, app_keys, work):
    """Signs each APK with the key the release gives it, into WORK by its name."""
    entries = {e["name"]: e for e in (fields(line) for line in apkcerts.read_text().split("\n"))
               if e.get("name")}
    dev_dir = os.path.dirname(DEFAULT_DEV_CERTIFICATE)
    platform = platform_keys(tree, dev_dir)
    plan = []
    for apk in apks:
        entry = entries.get(apk.name)
        if entry is None:
            die(f"{apkcerts} has no {apk.name}: a full build of the device lists it")
        key, _ = apk_key(entry, dev_dir, platform, app_keys)
        if key == PRESIGNED:
            die(f"{apk.name} is presigned and keeps its signature")
        plan.append((apk, key))
    missing = sorted({f"{key}.{ext}" for _, key in plan for ext in ("pk8", "x509.pem")
                      if not (keys / f"{key}.{ext}").is_file()})
    if missing:
        die(f"the keys directory {keys} has no " + ", ".join(missing))
    tools = tree / "out" / "host" / "linux-x86"
    jdk = tree / "prebuilts" / "jdk" / "jdk21" / "linux-x86" / "bin"
    env = dict(os.environ, PATH=os.pathsep.join([str(jdk), os.environ.get("PATH", "")]))
    work.mkdir(parents=True, exist_ok=True)
    for apk, key in plan:
        out = work / apk.name
        status = subprocess.run([str(tools / "bin" / "apksigner"), "sign",
                                 "--key", str(keys / f"{key}.pk8"),
                                 "--cert", str(keys / f"{key}.x509.pem"),
                                 "--in", str(apk), "--out", str(out)], env=env).returncode
        if status:
            die(f"apksigner failed on {apk.name} (exit {status})")
        log(f"{apk.name}: {key}, {out}")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--tree", required=True, type=Path)
    parser.add_argument("--keys", required=True, type=Path)
    parser.add_argument("--app-key", action="append", default=[], metavar="APK=KEY",
                        help="sign this APK with this key of KEYS")
    parser.add_argument("--apkcerts", type=Path, help="sign the APKs of a module build")
    parser.add_argument("inputs", nargs="+", type=Path, metavar="TARGET_FILES | APK...")
    parser.add_argument("work", type=Path)
    args = parser.parse_args()
    tree, keys, work = args.tree.resolve(), args.keys.resolve(), args.work.resolve()
    app_keys = dict(a.split("=", 1) for a in args.app_key)
    if args.apkcerts:
        return sign_modules(tree, keys, args.apkcerts.resolve(),
                            [a.resolve() for a in args.inputs], app_keys, work)
    if len(args.inputs) != 1:
        parser.error("a release signs one TARGET_FILES")
    target_files = args.inputs[0].resolve()
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
