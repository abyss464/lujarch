#!/usr/bin/env python3
"""Derivation of Xiaomi's camera from its stock release (Android.bp).

  apk      Disassembles every dex file that has patches under smali/<dex>/, applies them
           (smali), reassembles it and puts it back in place of the stock one; all other
           entries are copied unchanged. The result is unsigned: android_app_import signs it.
  smali    Applies unified diffs to a baksmali tree, strictly: every hunk's old lines
           (context and removals) must occur exactly once in the file, whatever the line
           numbers say.
  vtable   Rewrites the slot of one virtual call through the Surface vtable
           (ldr x8, [x8, #slot]; blr x8). The stock libgui must name --expect at the slot the
           instruction loads; the call then goes to --call's slot in the libgui this build
           links.
  unneed   Drops a DT_NEEDED entry for a library that no longer exists.

Only AArch64 little-endian ELF64 shared objects are handled.
"""

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys

DT_NULL, DT_NEEDED, DT_STRTAB, DT_SYMTAB, DT_RELA, DT_RELASZ = 0, 1, 5, 6, 7, 8
DT_STRSZ, DT_JMPREL, DT_PLTRELSZ, DT_RELRSZ, DT_RELR = 10, 23, 2, 35, 36
DT_ANDROID_RELA, DT_ANDROID_RELASZ = 0x60000011, 0x60000012
DT_ANDROID_RELR, DT_ANDROID_RELRSZ = 0x6FFFE000, 0x6FFFE001
PT_LOAD, PT_DYNAMIC = 1, 2
SHT_DYNSYM = 11
R_AARCH64_ABS64, R_AARCH64_GLOB_DAT, R_AARCH64_RELATIVE = 257, 1025, 1027
SURFACE_VTABLE = "_ZTVN7android7SurfaceE"
SURFACE_D1 = "_ZN7android7SurfaceD1Ev"


def fail(msg):
    sys.exit(f"derive: {msg}")


def sleb128(data, pos):
    result = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if byte < 0x80:
            if byte & 0x40:
                result -= 1 << shift
            return result, pos


class Elf:
    def __init__(self, data, name):
        self.data, self.name = data, name
        if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
            fail(f"{name}: not a little-endian ELF64 file")
        if struct.unpack_from("<H", data, 0x12)[0] != 183:
            fail(f"{name}: not AArch64")
        phoff, shoff = struct.unpack_from("<QQ", data, 0x20)
        phentsize, phnum, shentsize, shnum = struct.unpack_from("<HHHH", data, 0x36)
        self.loads, self.dynamic = [], None
        for i in range(phnum):
            p_type, _, p_off, p_vaddr, _, p_filesz, _, _ = struct.unpack_from(
                "<IIQQQQQQ", data, phoff + i * phentsize)
            if p_type == PT_LOAD:
                self.loads.append((p_vaddr, p_off, p_filesz))
            elif p_type == PT_DYNAMIC:
                self.dynamic = (p_off, p_filesz)
        if self.dynamic is None:
            fail(f"{name}: no PT_DYNAMIC")
        self.dyn = {}
        for off in range(self.dynamic[0], self.dynamic[0] + self.dynamic[1], 16):
            tag, val = struct.unpack_from("<qQ", data, off)
            if tag == DT_NULL:
                break
            self.dyn.setdefault(tag, val)
        # Symbol count from the section header of .dynsym (present in every
        # library handled here; DT_HASH/DT_GNU_HASH walking is not needed).
        self.dynsym = None
        for i in range(shnum):
            sh = struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize)
            if sh[1] == SHT_DYNSYM:
                self.dynsym = (sh[4], sh[5] // sh[9])
        if self.dynsym is None:
            fail(f"{name}: no .dynsym section header")

    def off(self, vaddr):
        for va, off, size in self.loads:
            if va <= vaddr < va + size:
                return vaddr - va + off
        fail(f"{self.name}: address {vaddr:#x} is not in the file")

    def cstr(self, off):
        return self.data[off:self.data.index(b"\0", off)].decode()

    def dynstr(self, index):
        return self.cstr(self.off(self.dyn[DT_STRTAB]) + index)

    def symbols(self):
        """(name, value, size) of every .dynsym entry, index order."""
        off, count = self.dynsym
        out = []
        for i in range(count):
            st_name, _, _, _, st_value, st_size = struct.unpack_from(
                "<IBBHQQ", self.data, off + i * 24)
            out.append((self.dynstr(st_name) if st_name else "", st_value, st_size))
        return out

    def relocations(self):
        """{r_offset: (type, symbol index, addend)} from RELA, APS2 and RELR."""
        rel = {}

        def add(r_offset, info, addend):
            rel[r_offset] = (info & 0xFFFFFFFF, info >> 32, addend)

        for tag, size_tag in ((DT_RELA, DT_RELASZ), (DT_JMPREL, DT_PLTRELSZ)):
            if tag in self.dyn:
                start = self.off(self.dyn[tag])
                for off in range(start, start + self.dyn[size_tag], 24):
                    add(*struct.unpack_from("<QQq", self.data, off))
        if DT_ANDROID_RELA in self.dyn:
            # Android packed relocations ("APS2"), decoded as bionic's
            # packed_reloc_iterator does.
            pos = self.off(self.dyn[DT_ANDROID_RELA])
            if self.data[pos:pos + 4] != b"APS2":
                fail(f"{self.name}: unknown packed relocation format")
            pos += 4
            count, pos = sleb128(self.data, pos)
            r_offset, pos = sleb128(self.data, pos)
            info = addend = done = 0
            while done < count:
                group_size, pos = sleb128(self.data, pos)
                flags, pos = sleb128(self.data, pos)
                by_info, by_delta, by_addend, has_addend = (
                    flags & 1, flags & 2, flags & 4, flags & 8)
                if by_delta:
                    delta, pos = sleb128(self.data, pos)
                if by_info:
                    info, pos = sleb128(self.data, pos)
                if has_addend and by_addend:
                    step, pos = sleb128(self.data, pos)
                    addend += step
                elif not has_addend:
                    addend = 0
                for _ in range(group_size):
                    if by_delta:
                        r_offset += delta
                    else:
                        step, pos = sleb128(self.data, pos)
                        r_offset += step
                    if not by_info:
                        info, pos = sleb128(self.data, pos)
                    if has_addend and not by_addend:
                        step, pos = sleb128(self.data, pos)
                        addend += step
                    add(r_offset, info, addend)
                done += group_size
        for tag, size_tag in ((DT_RELR, DT_RELRSZ), (DT_ANDROID_RELR, DT_ANDROID_RELRSZ)):
            if tag not in self.dyn:
                continue
            start, where = self.off(self.dyn[tag]), 0
            for off in range(start, start + self.dyn[size_tag], 8):
                entry = struct.unpack_from("<Q", self.data, off)[0]
                if entry & 1 == 0:
                    rel[entry] = (R_AARCH64_RELATIVE, 0, None)
                    where = entry + 8
                else:
                    for bit in range(63):
                        if entry >> (bit + 1) & 1:
                            rel[where + bit * 8] = (R_AARCH64_RELATIVE, 0, None)
                    where += 63 * 8
        return rel

    def surface_slots(self):
        """{slot offset: symbol} of the primary Surface vtable."""
        syms = self.symbols()
        found = [(v, s) for n, v, s in syms if n == SURFACE_VTABLE and v]
        if len(found) != 1:
            fail(f"{self.name}: {SURFACE_VTABLE} not defined exactly once")
        start, size = found[0]
        by_value = {}
        for n, v, _ in syms:
            if n and v:
                by_value.setdefault(v, n)
        rel = self.relocations()
        slots = {}
        # Address point after offset-to-top and RTTI (Itanium C++ ABI).
        for addr in range(start + 16, start + size, 8):
            if addr not in rel:
                continue
            rtype, symidx, addend = rel[addr]
            if rtype in (R_AARCH64_ABS64, R_AARCH64_GLOB_DAT):
                name = syms[symidx][0]
            elif rtype == R_AARCH64_RELATIVE:
                if addend is None:
                    addend = struct.unpack_from("<Q", self.data, self.off(addr))[0]
                name = by_value.get(addend, f"{addend:#x}")
            else:
                continue
            slots[addr - start - 16] = name
        if slots.get(0) != SURFACE_D1:
            fail(f"{self.name}: Surface vtable does not start with {SURFACE_D1}; "
                 "the address point assumption does not hold")
        return slots


def parse_diff(text, name):
    """[(path, [(old lines, new lines), ...]), ...] of a unified diff; text
    before the first file header (the patch description) is ignored."""
    files, lines, i = [], text.split("\n"), 0
    while i < len(lines):
        if not (lines[i].startswith("--- ") and i + 1 < len(lines)
                and lines[i + 1].startswith("+++ ")):
            i += 1
            continue
        path = lines[i + 1][4:].split("\t")[0]
        if not path.startswith("b/"):
            fail(f"{name}: expected b/<path> in {lines[i + 1]!r}")
        hunks, i = [], i + 2
        while i < len(lines) and lines[i].startswith("@@ "):
            m = re.match(r"@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@", lines[i])
            if not m:
                fail(f"{name}: bad hunk header {lines[i]!r}")
            want_old, want_new = int(m.group(1) or 1), int(m.group(2) or 1)
            old, new, i = [], [], i + 1
            while len(old) < want_old or len(new) < want_new:
                if i >= len(lines) or lines[i][:1] not in (" ", "-", "+"):
                    fail(f"{name}: hunk of {path} ends early (or a file lacks its final newline)")
                tag, body = lines[i][:1], lines[i][1:]
                if tag in " -":
                    old.append(body)
                if tag in " +":
                    new.append(body)
                i += 1
            hunks.append((old, new))
        if not hunks:
            fail(f"{name}: no hunks for {path}")
        files.append((path[2:], hunks))
    if not files:
        fail(f"{name}: not a unified diff")
    return files


def cmd_smali(args):
    for patch in args.patches:
        name = patch.rsplit("/", 1)[-1]
        for path, hunks in parse_diff(open(patch, encoding="utf-8").read(), name):
            target = f"{args.dir}/{path}"
            text = "\n" + open(target, encoding="utf-8").read()
            for n, (old, new) in enumerate(hunks, 1):
                block = "\n" + "".join(line + "\n" for line in old)
                count = text.count(block)
                if count != 1:
                    fail(f"{name}: hunk {n} of {path} matches {count} places, expected 1 "
                         "(a new APK version needs the patch made again)")
                text = text.replace(block, "\n" + "".join(line + "\n" for line in new))
            with open(target, "w", encoding="utf-8") as f:
                f.write(text[1:])


def dex_counts(path):
    """method_ids_size and class_defs_size of a dex header."""
    with open(path, "rb") as f:
        header = f.read(100)
    return struct.unpack_from("<I", header, 88)[0], struct.unpack_from("<I", header, 96)[0]


def run(*cmd):
    if subprocess.run(cmd).returncode:
        fail(f"{os.path.basename(cmd[0])} failed")


def cmd_apk(args):
    # baksmali names files after classes and smali assembles them in path order; non-ASCII
    # class names become file names through the JVM's locale, so the locale is fixed. One job:
    # smali's output is byte-reproducible only single-threaded. API 29 keeps dex format 039.
    os.environ["LC_ALL"] = "C"
    api, mem = "29", "-JXmx2g"
    work = args.work
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f"{work}/dex")
    by_dex = {}
    for patch in args.patches:
        by_dex.setdefault(os.path.basename(os.path.dirname(patch)), []).append(patch)
    if not by_dex:
        fail("no smali patches given")
    excludes = []
    for dex, patches in sorted(by_dex.items()):
        run("unzip", "-q", "-o", args.apk, f"{dex}.dex", "-d", work)
        run(args.baksmali, mem, "disassemble", "--api", api, "--jobs", "1",
            "-o", f"{work}/{dex}", f"{work}/{dex}.dex")
        cmd_smali(argparse.Namespace(dir=f"{work}/{dex}", patches=sorted(patches)))
        run(args.smali, mem, "assemble", "--api", api, "--jobs", "1",
            "-o", f"{work}/dex/{dex}.dex", f"{work}/{dex}")
        # Patches change code only: other counts mean baksmali or smali lost or merged something.
        if dex_counts(f"{work}/{dex}.dex") != dex_counts(f"{work}/dex/{dex}.dex"):
            fail(f"{dex}.dex: the number of methods or classes changed")
        excludes += ["-x", f"{dex}.dex"]
    # Stored like the stock dex files; soong_zip and merge_zips write fixed timestamps.
    run(args.soong_zip, "-o", f"{work}/dex.zip", "-L", "0", "-C", f"{work}/dex", "-D", f"{work}/dex")
    run(args.zip2zip, "-i", args.apk, "-o", f"{work}/rest.zip", *excludes)
    run(args.merge_zips, args.out, f"{work}/dex.zip", f"{work}/rest.zip")
    shutil.rmtree(work)


def cmd_vtable(args):
    data = open(args.input, "rb").read()
    stock = Elf(open(args.stock_libgui, "rb").read(), "stock libgui")
    ours = Elf(open(args.libgui, "rb").read(), "libgui")
    Elf(data, args.input)

    site = int(args.site, 0)
    word = struct.unpack_from("<I", data, site)[0]
    # LDR (immediate, unsigned offset), 64-bit: 0xF9400000 | imm12<<10 | Rn<<5 | Rt
    if word & 0xFFC00000 != 0xF9400000:
        fail(f"{args.input}: instruction at {site:#x} is not a 64-bit LDR: {word:#010x}")
    if data.count(struct.pack("<I", word)) != 1:
        fail(f"{args.input}: instruction {word:#010x} is not unique in the file")
    old_slot = (word >> 10 & 0xFFF) * 8
    method = stock.surface_slots().get(old_slot)
    if method != args.expect:
        fail(f"stock libgui: slot {old_slot:#x} is {method}, not {args.expect}")
    new = [slot for slot, name in ours.surface_slots().items() if name == args.call]
    if len(new) != 1:
        fail(f"libgui: {args.call} is in {len(new)} Surface vtable slots, expected 1")
    print(f"{args.input}: {site:#x} calls {method} at {old_slot:#x}; "
          f"now {args.call} at {new[0]:#x}")
    out = bytearray(data)
    struct.pack_into("<I", out, site, word & ~(0xFFF << 10) | (new[0] // 8) << 10)
    open(args.output, "wb").write(out)


def cmd_unneed(args):
    data = open(args.input, "rb").read()
    elf = Elf(data, args.input)
    start, size = elf.dynamic
    entries = [struct.unpack_from("<qQ", data, off) for off in range(start, start + size, 16)]
    hits = [i for i, (tag, val) in enumerate(entries)
            if tag == DT_NEEDED and elf.dynstr(val) == args.needed]
    if len(hits) != 1:
        fail(f"{args.input}: DT_NEEDED {args.needed} found {len(hits)} times, expected 1")
    if entries[-1][0] != DT_NULL:
        fail(f"{args.input}: dynamic section does not end with DT_NULL")
    # The following entries move up; the freed last one becomes DT_NULL.
    del entries[hits[0]]
    entries.append((DT_NULL, 0))
    out = bytearray(data)
    for i, (tag, val) in enumerate(entries):
        struct.pack_into("<qQ", out, start + i * 16, tag, val)
    open(args.output, "wb").write(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    apk = sub.add_parser("apk")
    for tool in ("baksmali", "smali", "soong-zip", "zip2zip", "merge-zips"):
        apk.add_argument(f"--{tool}", required=True)
    apk.add_argument("--work", required=True)
    apk.add_argument("--out", required=True)
    apk.add_argument("apk")
    apk.add_argument("patches", nargs="+")
    sm = sub.add_parser("smali")
    sm.add_argument("--dir", required=True, help="baksmali output directory")
    sm.add_argument("patches", nargs="+")
    vt = sub.add_parser("vtable")
    vt.add_argument("--site", required=True, help="file offset of the LDR")
    vt.add_argument("--expect", required=True, help="the method the stock slot holds")
    vt.add_argument("--call", required=True, help="the method to call instead")
    vt.add_argument("--stock-libgui", required=True)
    vt.add_argument("--libgui", required=True, help="libgui.so of this build")
    vt.add_argument("--in", dest="input", required=True)
    vt.add_argument("--out", dest="output", required=True)
    un = sub.add_parser("unneed")
    un.add_argument("--needed", required=True)
    un.add_argument("--in", dest="input", required=True)
    un.add_argument("--out", dest="output", required=True)
    args = parser.parse_args()
    {"apk": cmd_apk, "smali": cmd_smali, "vtable": cmd_vtable, "unneed": cmd_unneed}[args.cmd](args)


if __name__ == "__main__":
    main()
