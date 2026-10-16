#!/usr/bin/env python3
"""Verifies a signed release (process/release-flow.md), without the keys.

  verify.py --tree TREE --product-out DIR --source DIR --keys-of FILE
            --unsigned TARGET_FILES --signed TARGET_FILES --ota OTA

Checks the target files sign.py signed (--signed, from --unsigned) and the OTA package made from
them:

  signatures  every APK and APEX of the image, in its partitions and in its APEXes, is signed
              with the key --keys-of (sign.py's keys.txt) names for it, every file one key signs
              with the same certificate; a PRESIGNED one keeps its developer's signature and is
              named with it
  test keys   no signature, APEX payload key, AVB key or trusted OTA certificate is a key whose
              private half is in the tree: the keys the unsigned target files name, the build's
              platform keys and AVB's test keys
  anchors     releasekey's certificate is the one the system trusts for OTA packages and the one
              the OTA package's whole-file and payload signatures verify with; platform's is the
              SELinux signer of seinfo platform
  AVB         vbmeta and every image it chains are signed with one key
  APEX keys   each APEX's payload is signed with the payload key it carries, which is the key of
              its container's certificate unless it is PRESIGNED
  allowlists  every privileged permission of the platform that a privileged app requests is
              granted or denied in the privapp-permissions of its partition or APEX
  installed   every file of a partition is one the product installs there (the build's
              file_list.txt of the partition)
  tests       no file of the image has the content of a file of the repository's test/, and no
              module or package test/ defines is in the image

Prints each PRESIGNED APK and APEX and each failure; exits 1 on any failure.
"""

import argparse
import base64
import hashlib
import io
import json
import os
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

PRESIGNED = "PRESIGNED"
RELEASE_KEY = "releasekey"
PLATFORM_KEY = "platform"
# Directories of the target files that are not partitions.
NOT_PARTITIONS = {"META", "IMAGES", "OTA", "RADIO", "PREBUILT_IMAGES", "INSTALL", "VENDOR_BOOT",
                  "BOOT", "INIT_BOOT", "RECOVERY", "ROOT", "SYSTEM_OTHER", "DATA"}
PRIVILEGED_PARTITIONS = ("SYSTEM", "SYSTEM_EXT", "PRODUCT", "VENDOR", "ODM")
PROTECTION_PRIVILEGED = 0x10
APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
APK_SIG_SCHEMES = (0x7109871A, 0xF05368C0, 0x1B93AD61)
RSA_OID = bytes.fromhex("2a864886f70d010101")
SUBJECT_OIDS = {bytes.fromhex("550403"): "CN", bytes.fromhex("55040a"): "O",
                bytes.fromhex("55040b"): "OU", bytes.fromhex("550406"): "C"}

failures = []


def say(line):
    print(line, flush=True)


def fail(line):
    failures.append(line)
    say(f"fail: {line}")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def sha1(data):
    return hashlib.sha1(data).hexdigest()


def fields(line):
    return dict(re.findall(r'(\w+)="([^"]*)"', line))


def listed(items, most=20):
    return "".join(f"\n  {i}" for i in items[:most]) + (
        f"\n  and {len(items) - most} more" if len(items) > most else "")


# ---------------------------------------------------------------------------
# Certificates and keys


def der(data, pos):
    """The DER element at pos: (tag, start of its content, end)."""
    tag, size = data[pos], data[pos + 1]
    pos += 2
    if size & 0x80:
        count = size & 0x7F
        size = int.from_bytes(data[pos:pos + count], "big")
        pos += count
    return tag, pos, pos + size


def der_items(data, start, end):
    """The DER elements from start to end, as (tag, element start, content start, end)."""
    items = []
    while start < end:
        tag, content, stop = der(data, start)
        items.append((tag, start, content, stop))
        start = stop
    return items


def cert_parts(cert):
    """A certificate's subject and its RSA modulus (None for another kind of key)."""
    _, content, end = der(cert, 0)
    tbs = der_items(cert, content, end)[0]
    parts = der_items(cert, tbs[2], tbs[3])
    if parts[0][0] == 0xA0:
        parts = parts[1:]
    subject, spki = parts[4], parts[5]
    names = []
    for rdn in der_items(cert, subject[2], subject[3]):
        for attr in der_items(cert, rdn[2], rdn[3]):
            oid, value = der_items(cert, attr[2], attr[3])[:2]
            label = SUBJECT_OIDS.get(cert[oid[2]:oid[3]])
            if label:
                names.append(f"{label}={cert[value[2]:value[3]].decode(errors='replace')}")
    algorithm, bits = der_items(cert, spki[2], spki[3])[:2]
    oid = der_items(cert, algorithm[2], algorithm[3])[0]
    modulus = None
    if cert[oid[2]:oid[3]] == RSA_OID:
        key = cert[bits[2] + 1:bits[3]]
        _, start, stop = der(key, 0)
        n = der_items(key, start, stop)[0]
        modulus = int.from_bytes(key[n[2]:n[3]], "big")
    return ", ".join(names), modulus


def pkcs7_certs(data):
    """The certificates of a PKCS #7 SignedData."""
    _, content, end = der(data, 0)
    wrapped = der_items(data, content, end)[1]
    signed = der_items(data, wrapped[2], wrapped[3])[0]
    for tag, _, start, stop in der_items(data, signed[2], signed[3]):
        if tag == 0xA0:
            return [data[s:e] for _, s, _, e in der_items(data, start, stop)]
    return []


def pem_certs(text):
    return [base64.b64decode("".join(m.split())) for m in re.findall(
        r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", text, re.S)]


def avb_modulus(blob):
    """The RSA modulus of a public key in AVB's form."""
    bits = struct.unpack("!I", blob[:4])[0]
    return int.from_bytes(blob[8:8 + bits // 8], "big")


def apk_certs(data):
    """The signer certificates of an APK or APEX: its v2, v3 and v3.1 signers, else its v1."""
    eocd = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if eocd < 0:
        return []
    cd = struct.unpack_from("<I", data, eocd + 16)[0]
    if cd == 0xFFFFFFFF and data[eocd - 20:eocd - 16] == b"PK\x06\x07":
        cd = struct.unpack_from("<Q", data, struct.unpack_from("<Q", data, eocd - 12)[0] + 48)[0]
    certs = []

    def prefixed(buf, off):
        n = struct.unpack_from("<I", buf, off)[0]
        return buf[off + 4:off + 4 + n], off + 4 + n

    def sequence(buf):
        off, items = 0, []
        while off < len(buf):
            item, off = prefixed(buf, off)
            items.append(item)
        return items

    if data[cd - 16:cd] == APK_SIG_BLOCK_MAGIC:
        size = struct.unpack_from("<Q", data, cd - 24)[0]
        pos, end = cd - size, cd - 24
        while pos < end:
            length, scheme = struct.unpack_from("<QI", data, pos)
            if scheme in APK_SIG_SCHEMES:
                signers, _ = prefixed(data[pos + 12:pos + 8 + length], 0)
                for signer in sequence(signers):
                    signed, _ = prefixed(signer, 0)
                    _, off = prefixed(signed, 0)
                    encoded, _ = prefixed(signed, off)
                    certs += sequence(encoded)
            pos += 8 + length
    if not certs:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for name in z.namelist():
                if re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC)", name):
                    certs += pkcs7_certs(z.read(name))
    return list(dict.fromkeys(certs))


class Tools:
    def __init__(self, tree, tmp):
        self.bin = tree / "out" / "host" / "linux-x86" / "bin"
        self.tmp = tmp
        self.env = dict(os.environ, TMPDIR=str(tmp),
                        PATH=os.pathsep.join([str(self.bin), os.environ.get("PATH", "")]))

    def run(self, name, *args):
        return subprocess.run([str(self.bin / name), *map(str, args)], capture_output=True,
                              text=True, env=self.env)

    def avb_info(self, image):
        """(signing key sha1 or None, algorithm, [(chained partition, key sha1)]) of an image."""
        out = self.run("avbtool", "info_image", "--image", image)
        if out.returncode:
            return None, None, []
        key = re.search(r"^Public key \(sha1\):\s+(\S+)", out.stdout, re.M)
        algorithm = re.search(r"^Algorithm:\s+(\S+)", out.stdout, re.M)
        chains = re.findall(r"Chain Partition descriptor:.*?Partition Name:\s+(\S+).*?"
                            r"Public key \(sha1\):\s+(\S+)", out.stdout, re.S)
        return (key.group(1) if key else None), (algorithm.group(1) if algorithm else None), chains

    def avb_public_key(self, key):
        """The AVB public key of a private key, or None when it is none avbtool reads."""
        out = self.tmp / "key.avbpubkey"
        if self.run("avbtool", "extract_public_key", "--key", key, "--output", out).returncode:
            return None
        blob = out.read_bytes()
        out.unlink()
        return blob


def test_keys(tree, unsigned, tools):
    """The keys whose private halves are in the tree: certificates by SHA-256, AVB public keys by
    SHA-1, each with the path it was found at."""
    certs, avb = {}, {}

    def add_certs(path, label):
        try:
            text = path.read_text(errors="replace")
        except OSError:
            return
        for cert in pem_certs(text):
            certs.setdefault(sha256(cert), label)

    with zipfile.ZipFile(unsigned) as u:
        misc = dict(line.split("=", 1) for line in u.read("META/misc_info.txt").decode().split("\n")
                    if "=" in line)
        apkcerts = [fields(line) for line in u.read("META/apkcerts.txt").decode().split("\n")]
        apexkeys = [fields(line) for line in u.read("META/apexkeys.txt").decode().split("\n")]
    dev_dir = os.path.dirname(misc.get("default_system_dev_certificate",
                                       "build/make/target/product/security/testkey"))
    for path in sorted((tree / dev_dir).glob("*.x509.pem")):
        add_certs(path, os.path.relpath(path, tree))
    for entry in apkcerts:
        if entry.get("certificate", PRESIGNED) != PRESIGNED:
            add_certs(tree / entry["certificate"], entry["certificate"])
    for entry in apexkeys:
        if entry.get("container_certificate", PRESIGNED) != PRESIGNED:
            add_certs(tree / entry["container_certificate"], entry["container_certificate"])
        if entry.get("public_key", PRESIGNED) != PRESIGNED:
            try:
                avb.setdefault(sha1((tree / entry["public_key"]).read_bytes()), entry["public_key"])
            except OSError:
                pass
    avb_test = tree / "external" / "avb" / "test" / "data"
    private = sorted(avb_test.glob("*.pem")) + sorted(
        tree / v for k, v in misc.items() if re.fullmatch(r"avb_\w+_key_path", k))
    for path in private:
        blob = tools.avb_public_key(path)
        if blob:
            avb.setdefault(sha1(blob), os.path.relpath(path, tree))
    for path in sorted(avb_test.glob("*.avbpubkey")):
        avb.setdefault(sha1(path.read_bytes()), os.path.relpath(path, tree))
    return certs, avb


# ---------------------------------------------------------------------------
# The image


def image_path(entry):
    """Where a file of the target files is on the device."""
    part, _, rest = entry.partition("/")
    return f"/{part.lower()}/{rest}"


def partition_files(z):
    """{partition directory: {path in it: ZipInfo}} of the files and links of each partition."""
    out = {}
    for info in z.infolist():
        part, _, rest = info.filename.partition("/")
        if not rest or info.filename.endswith("/") or part in NOT_PARTITIONS or part != part.upper():
            continue
        out.setdefault(part, {})[rest] = info
    return out


def is_link(info):
    return stat.S_ISLNK(info.external_attr >> 16)


class Verify:
    def __init__(self, args, tmp):
        self.args = args
        self.tmp = tmp
        self.tools = Tools(args.tree, tmp)
        self.keys_of = {}
        for line in args.keys_of.read_text().split("\n"):
            name, _, key = line.partition(": ")
            if key:
                self.keys_of[name] = key
        self.test_certs, self.test_avb = test_keys(args.tree, args.unsigned, self.tools)
        self.test_sources, self.test_modules, self.test_packages = test_definitions(args.source)
        self.signers = {}       # key: {certificate sha256: [where]}
        self.der = {}           # certificate sha256: certificate
        self.third_party = []
        self.privileged = set()
        self.privileged_apps = 0

    # -- signatures

    def test_cert(self, cert):
        return self.test_certs.get(sha256(cert))

    def named(self, where, certs):
        """A PRESIGNED APK or APEX: named with its signers."""
        for cert in certs:
            test = self.test_cert(cert)
            if test:
                fail(f"{where} is signed with {test}, a key whose private half is in the tree")
            self.third_party.append(f"{where}: {cert_parts(cert)[0]}, certificate sha256 "
                                    f"{sha256(cert)[:16]}")
        if not certs:
            fail(f"{where} is not signed")

    def signed(self, where, name, certs, key=None):
        """An APK or APEX container the release signs: its certificate is its key's."""
        key = key or self.keys_of.get(name)
        if not certs:
            fail(f"{where} is not signed")
            return
        tests = [t for t in map(self.test_cert, certs) if t]
        if tests:
            fail(f"{where} is signed with {', '.join(tests)}, a key whose private half is in "
                 "the tree")
            return
        if key is None:
            fail(f"{where}: no key is named for {name}")
            return
        if len(certs) != 1:
            fail(f"{where} has {len(certs)} signers")
            return
        digest = sha256(certs[0])
        self.der[digest] = certs[0]
        self.signers.setdefault(key, {}).setdefault(digest, []).append(where)

    def apk(self, where, data, presigned=False):
        name = os.path.basename(where)
        certs = apk_certs(data)
        if presigned or self.keys_of.get(name) == PRESIGNED:
            self.named(where, certs)
        else:
            self.signed(where, name, certs)
        if self.test_packages:
            package = self.package(data)
            if package in self.test_packages:
                fail(f"{where} is the test package {package} of test/")

    def package(self, data):
        apk = self.tmp / "package.apk"
        apk.write_bytes(data)
        out = self.tools.run("aapt2", "dump", "packagename", apk).stdout.strip()
        apk.unlink()
        return out

    def apex(self, where, data):
        name = os.path.basename(where)
        name = name[:-len(".capex")] + ".apex" if name.endswith(".capex") else name
        presigned = self.keys_of.get(name) == PRESIGNED
        containers = [(where, data)]
        outer = zipfile.ZipFile(io.BytesIO(data))
        if "original_apex" in outer.namelist():
            inner = outer.read("original_apex")
            containers.append((f"{where}!original_apex", inner))
            payload_zip = zipfile.ZipFile(io.BytesIO(inner))
        else:
            payload_zip = outer
        moduli = set()
        for container, blob in containers:
            certs = apk_certs(blob)
            if presigned:
                self.named(container, certs)
            else:
                self.signed(container, name, certs, key=self.keys_of.get(name))
            moduli.update(cert_parts(c)[1] for c in certs)
        pubkey = payload_zip.read("apex_pubkey")
        payload = self.tmp / "apex_payload.img"
        payload.write_bytes(payload_zip.read("apex_payload.img"))
        signer, algorithm, _ = self.tools.avb_info(payload)
        payload.unlink()
        if signer != sha1(pubkey):
            fail(f"{where}: its payload is signed with {signer or 'no key'} ({algorithm}), not "
                 f"with the payload key it carries ({sha1(pubkey)})")
        test = self.test_avb.get(sha1(pubkey))
        if test:
            fail(f"{where}: its payload key is {test}, a key whose private half is in the tree")
        if not presigned and moduli != {avb_modulus(pubkey)}:
            fail(f"{where}: its payload key is not the key of its container's certificate")
        # The APKs and allowlists of its payload.
        apex_file = self.tmp / "payload.apex"
        apex_file.write_bytes(data)
        dest = self.tmp / "payload"
        out = self.tools.run("deapexer", "--debugfs_path", self.tools.bin / "debugfs_static",
                             "--fsckerofs_path", self.tools.bin / "fsck.erofs", "extract",
                             apex_file, dest)
        if out.returncode:
            fail(f"{where}: deapexer could not extract it: {out.stderr.strip()[-300:]}")
        else:
            allowlists = allowlists_of(dest / "etc" / "permissions")
            for path in sorted(dest.rglob("*.apk")):
                inner = f"{where}!/{path.relative_to(dest).as_posix()}"
                apk = path.read_bytes()
                self.apk(inner, apk, presigned=presigned)
                if path.relative_to(dest).parts[0] == "priv-app":
                    self.allowlisted(inner, apk, allowlists)
        apex_file.unlink()
        for root, dirs, _ in os.walk(dest):
            for d in dirs:
                os.chmod(os.path.join(root, d), 0o700)
        shutil.rmtree(dest, ignore_errors=True)

    def consistent(self):
        """Every key signs with one certificate."""
        for key, certs in sorted(self.signers.items()):
            if len(certs) > 1:
                fail(f"the files {key} signs carry {len(certs)} different certificates:" + listed(
                    [f"{d[:16]}: {len(w)} files, such as {w[0]}" for d, w in certs.items()]))

    def certificate(self, key):
        """The certificate the key signed with, when it signed with one."""
        certs = self.signers.get(key, {})
        return self.der[next(iter(certs))] if len(certs) == 1 else None

    # -- anchors

    def otacerts(self, z):
        release = self.certificate(RELEASE_KEY)
        if release is None:
            fail(f"no APK is signed with {RELEASE_KEY} alone")
            return None
        found = False
        for name in sorted(n for n in z.namelist() if n.endswith("/otacerts.zip")):
            found |= name == "SYSTEM/etc/security/otacerts.zip"
            with zipfile.ZipFile(io.BytesIO(z.read(name))) as certs_zip:
                certs = [c for e in certs_zip.namelist() for c in pem_certs(certs_zip.read(e).decode())]
            if release not in certs:
                fail(f"{image_path(name)} does not trust {RELEASE_KEY}")
            for cert in certs:
                test = self.test_cert(cert)
                if test:
                    fail(f"{image_path(name)} trusts {test}, a key whose private half is in the tree")
                elif cert != release:
                    say(f"note: {image_path(name)} also trusts {cert_parts(cert)[0]}, certificate "
                        f"sha256 {sha256(cert)[:16]}")
        if not found:
            fail("the system has no OTA certificates (/system/etc/security/otacerts.zip)")
        return release

    def ota(self, release):
        with zipfile.ZipFile(self.args.ota) as ota:
            certs = pem_certs(ota.read("META-INF/com/android/otacert").decode())
        if certs != [release]:
            fail(f"the OTA package names another certificate than {RELEASE_KEY}'s")
        pem = self.tmp / "release.x509.pem"
        pem.write_text("-----BEGIN CERTIFICATE-----\n" + "\n".join(
            re.findall(".{1,64}", base64.b64encode(release).decode())) + "\n-----END CERTIFICATE-----\n")
        out = self.tools.run("check_ota_package_signature", pem, self.args.ota)
        if out.returncode:
            fail(f"the OTA package's signatures do not verify with {RELEASE_KEY}'s certificate:"
                 + listed((out.stdout + out.stderr).strip().split("\n")[-8:]))
        else:
            say(f"OTA package: its whole-file and payload signatures verify with {RELEASE_KEY}'s "
                f"certificate, sha256 {sha256(release)[:16]}")

    def mac_permissions(self, z):
        ours = {sha256(self.der[d]): key for key, certs in self.signers.items() for d in certs}
        platform = self.certificate(PLATFORM_KEY)
        for name in sorted(n for n in z.namelist() if n.endswith("mac_permissions.xml")
                           and not n.startswith("IMAGES/")):
            text = z.read(name).decode()
            for signature, seinfo in re.findall(
                    r'<signer signature="([0-9a-fA-F]+)"\s*>\s*<seinfo value="([^"]+)"', text):
                cert = bytes.fromhex(signature)
                test = self.test_cert(cert)
                if test:
                    fail(f"{image_path(name)}: seinfo {seinfo} is given to {test}, a key whose "
                         "private half is in the tree")
                elif seinfo == "platform" and platform is not None and cert != platform:
                    fail(f"{image_path(name)}: seinfo platform is given to another certificate "
                         f"than {PLATFORM_KEY}'s")
                elif sha256(cert) not in ours:
                    say(f"note: {image_path(name)}: seinfo {seinfo} is given to "
                        f"{cert_parts(cert)[0]}, certificate sha256 {sha256(cert)[:16]}")

    def avb(self, z):
        names = sorted(n for n in z.namelist() if re.fullmatch(r"IMAGES/vbmeta(_\w+)?\.img", n))
        if "IMAGES/vbmeta.img" not in names:
            fail("the images have no vbmeta")
        keys, chained = {}, {}
        for name in names:
            image = Path(z.extract(name, self.tmp))
            part = image.stem
            key, algorithm, chains = self.tools.avb_info(image)
            image.unlink()
            if not key:
                fail(f"{part} is not signed ({algorithm})")
                continue
            keys.setdefault(key, []).append(part)
            for chained_part, chained_key in chains:
                keys.setdefault(chained_key, []).append(f"{part}'s chain to {chained_part}")
                chained[chained_part] = chained_key
        for part, key in sorted(chained.items()):
            name = f"IMAGES/{part}.img"
            if name in names:
                continue
            if name not in z.namelist():
                fail(f"vbmeta chains {part}, which the images do not have")
                continue
            image = Path(z.extract(name, self.tmp))
            own, algorithm, _ = self.tools.avb_info(image)
            image.unlink()
            keys.setdefault(own or "no key", []).append(part)
        for key, where in sorted(keys.items()):
            test = self.test_avb.get(key)
            if test:
                fail(f"AVB: {', '.join(where)} with {test}, a key whose private half is in the tree")
        if len(keys) > 1:
            fail("AVB: more than one key:" + listed(
                [f"{key}: {', '.join(where)}" for key, where in sorted(keys.items())]))
        for key, where in keys.items():
            say(f"AVB: {', '.join(where)}: key sha1 {key}")

    # -- allowlists

    def privileged_permissions(self, z):
        apk = Path(z.extract("SYSTEM/framework/framework-res.apk", self.tmp))
        out = self.tools.run("aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
        apk.unlink()
        perms, current = set(), None

        def close(entry):
            if entry and entry["name"] and entry["level"] is not None and \
                    entry["level"] & PROTECTION_PRIVILEGED and not entry["flag"]:
                perms.add(entry["name"])

        for line in out.stdout.split("\n"):
            s = line.strip()
            if s.startswith("E: "):
                close(current)
                current = ({"name": None, "level": None, "flag": None}
                           if s.startswith("E: permission ") else None)
            elif current is not None and s.startswith("A: "):
                attr = s[3:].split("(", 1)[0]
                if attr.endswith("android:name"):
                    current["name"] = re.search(r'="([^"]+)"', s).group(1)
                elif attr.endswith("android:protectionLevel"):
                    current["level"] = int(re.search(r"=(0x[0-9a-f]+)", s).group(1), 16)
                elif attr.endswith("android:featureFlag"):
                    current["flag"] = True
        close(current)
        if not perms:
            fail("framework-res defines no privileged permission that aapt2 shows")
        return perms

    def allowlisted(self, where, data, allowed):
        """A privileged app: each privileged permission of the platform it requests is granted or
        denied in allowed, the privapp-permissions of its partition or APEX."""
        self.privileged_apps += 1
        apk = self.tmp / "privileged.apk"
        apk.write_bytes(data)
        badging = self.tools.run("aapt2", "dump", "badging", apk).stdout
        apk.unlink()
        package = re.search(r"^package: name='([^']+)'", badging, re.M)
        if not package:
            fail(f"{where}: aapt2 shows no package")
            return
        requested = set(re.findall(r"^uses-permission(?:-sdk-23)?: name='([^']+)'", badging, re.M))
        for perm in sorted(requested & self.privileged - allowed.get(package.group(1), set())):
            fail(f"{where}: {package.group(1)} requests {perm}, which no privapp-permissions of "
                 "its partition grants or denies")

    # -- files

    def installed(self, parts):
        lists = Path(self.args.product_out) / "obj" / "PACKAGING"
        say(f"installed: {sum(len(f) for f in parts.values())} files in "
            + ", ".join(f"/{p.lower()}" for p in sorted(parts)))
        for part, files in sorted(parts.items()):
            listing = lists / f"{part.lower()}_intermediates" / "file_list.txt"
            if not listing.is_file():
                fail(f"/{part.lower()}: the build has no list of the files it installs there "
                     f"({listing})")
                continue
            known = set(listing.read_text().split("\n"))
            extra = sorted(f"/{part.lower()}/{p}" for p in files if p not in known)
            if extra:
                fail(f"/{part.lower()} holds {len(extra)} files the product does not install:"
                     + listed(extra))

    def tests(self, parts):
        say(f"tests: {len(self.test_sources)} files of test/")
        by_size = {}
        for path in self.test_sources:
            by_size.setdefault(path.stat().st_size, {})[sha256(path.read_bytes())] = path
        with zipfile.ZipFile(self.args.signed) as z:
            for part, files in sorted(parts.items()):
                for rel, info in sorted(files.items()):
                    same = by_size.get(info.file_size)
                    if same and not is_link(info):
                        source = same.get(sha256(z.read(info)))
                        if source:
                            fail(f"/{part.lower()}/{rel} is {source.relative_to(self.args.source)}")
        if self.test_modules:
            info = json.loads((Path(self.args.product_out) / "module-info.json").read_text())
            image = {f"{part.lower()}/{rel}" for part, files in parts.items() for rel in files}
            product = os.path.realpath(self.args.product_out)
            for module in sorted(self.test_modules & set(info)):
                for path in info[module].get("installed") or []:
                    rel = os.path.relpath(os.path.join(self.args.tree, path), product)
                    if rel in image:
                        fail(f"/{rel} is the test module {module} of test/")


def test_definitions(source):
    """The files of test/, and the modules and app packages they define."""
    test = Path(source) / "test"
    sources = [p for p in sorted(test.rglob("*")) if p.is_file() and p.stat().st_size] \
        if test.is_dir() else []
    modules, packages = set(), set()
    for path in sources:
        text = path.read_text(errors="replace")
        if path.name == "Android.bp":
            modules.update(re.findall(r'^\s*name:\s*"([^"]+)"', text, re.M))
        elif path.name == "AndroidManifest.xml":
            packages.update(re.findall(r'\bpackage="([^"]+)"', text))
        elif path.name.startswith("build.gradle"):
            packages.update(re.findall(r'\bapplicationId\s*=?\s*"([^"]+)"', text))
    return sources, modules, packages


def allowlists_of(directory):
    """{package: permissions granted or denied} of the privapp-permissions files of a directory."""
    out = {}
    for path in sorted(directory.glob("*.xml")) if directory.is_dir() else []:
        add_allowlist(out, path.name, path.read_bytes())
    return out


def add_allowlist(out, name, data):
    try:
        root = ET.fromstring(data)
    except ET.ParseError as e:
        fail(f"{name} is not XML: {e}")
        return
    for entry in root.iter("privapp-permissions"):
        granted = out.setdefault(entry.get("package"), set())
        granted.update(p.get("name") for p in entry
                       if p.tag in ("permission", "deny-permission") and p.get("name"))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    for option in ("--tree", "--product-out", "--source", "--keys-of", "--unsigned", "--signed",
                   "--ota"):
        parser.add_argument(option, required=True, type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="verify.") as tmp:
        check = Verify(args, Path(tmp))
        say(f"test keys: {len(check.test_certs)} certificates and {len(check.test_avb)} AVB keys "
            "whose private halves are in the tree")
        with zipfile.ZipFile(args.signed) as z:
            parts = partition_files(z)
            check.privileged = check.privileged_permissions(z)
            allowlists = {part: {} for part in PRIVILEGED_PARTITIONS}
            for part in PRIVILEGED_PARTITIONS:
                for rel, info in sorted(parts.get(part, {}).items()):
                    if rel.startswith("etc/permissions/") and rel.endswith(".xml"):
                        add_allowlist(allowlists[part], rel, z.read(info))
            for part, files in sorted(parts.items()):
                for rel, info in sorted(files.items()):
                    entry = f"{part}/{rel}"
                    if is_link(info):
                        continue
                    if rel.endswith(".apk"):
                        data = z.read(info)
                        check.apk(image_path(entry), data)
                        if part in PRIVILEGED_PARTITIONS and rel.startswith("priv-app/"):
                            check.allowlisted(image_path(entry), data, allowlists[part])
                    elif rel.endswith((".apex", ".capex")):
                        check.apex(image_path(entry), z.read(info))
            say(f"allowlists: {check.privileged_apps} privileged apps, {len(check.privileged)} "
                "privileged permissions of the platform")
            check.consistent()
            release = check.otacerts(z)
            if release is not None:
                check.ota(release)
            check.mac_permissions(z)
            check.avb(z)
        check.installed(parts)
        check.tests(parts)
        counts = ", ".join(f"{key} {sum(len(w) for w in certs.values())}"
                           for key, certs in sorted(check.signers.items()))
        say(f"signed with the release keys: {counts}")
        for line in check.third_party:
            say(f"third-party: {line}")
    if failures:
        say(f"{len(failures)} failure{'s' if len(failures) != 1 else ''}")
        return 1
    say("verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
