#!/usr/bin/env python3
"""Writes the largest PNG image of an icon group of a PE file, the first group (the icon Explorer shows
for the file) or the one with the given resource id; or of an .ico file.

The desktop's install gives Wine's menu entries Wine's own icons with it (winecfg.exe, appwiz.cpl) without
an icon tool in Linux. Wine's icons carry their 256 px image as PNG, as Windows icons do since
Vista, so no bitmap conversion is needed.

    pe-icon.py <file.exe|dll> <out.png> [group id]
    pe-icon.py <file.ico> <out.png>
"""
import struct
import sys

RT_ICON, RT_GROUP_ICON = 3, 14


def sections(pe, off):
    nsec, = struct.unpack_from("<H", pe, off + 6)
    opt_size, = struct.unpack_from("<H", pe, off + 20)
    magic, = struct.unpack_from("<H", pe, off + 24)
    dirs = off + 24 + (96 if magic == 0x10B else 112)
    rsrc_rva, _ = struct.unpack_from("<II", pe, dirs + 2 * 8)
    secs = []
    for i in range(nsec):
        s = off + 24 + opt_size + 40 * i
        vsize, va, rawsize, rawptr = struct.unpack_from("<IIII", pe, s + 8)
        secs.append((va, max(vsize, rawsize), rawptr))
    return rsrc_rva, secs


def rva2off(rva, secs):
    for va, size, raw in secs:
        if va <= rva < va + size:
            return rva - va + raw
    raise ValueError(f"rva {rva:#x} outside the sections")


def entries(pe, base, dir_off):
    named, ids = struct.unpack_from("<HH", pe, dir_off + 12)
    for i in range(named + ids):
        name, target = struct.unpack_from("<II", pe, dir_off + 16 + 8 * i)
        yield name, target


def leaves(pe, base, secs, type_id):
    """{resource id: data} for one resource type, first language of each."""
    out = {}
    for name, target in entries(pe, base, base):
        if name != type_id:
            continue
        for rid, t2 in entries(pe, base, base + (target & 0x7FFFFFFF)):
            for _, t3 in entries(pe, base, base + (t2 & 0x7FFFFFFF)):
                rva, size = struct.unpack_from("<II", pe, base + t3)
                o = rva2off(rva, secs)
                out.setdefault(rid, pe[o:o + size])
    return out


def ico(data):
    """The largest PNG image of an .ico file, as (side, png) or None."""
    count, = struct.unpack_from("<H", data, 4)
    best = None
    for i in range(count):
        w, _, _, _, _, _, size, offset = struct.unpack_from("<BBBBHHII", data, 6 + 16 * i)
        png = data[offset:offset + size]
        if png.startswith(b"\x89PNG") and (best is None or (w or 256) > best[0]):
            best = (w or 256, png)
    return best


def main():
    src, dst = sys.argv[1], sys.argv[2]
    pe = open(src, "rb").read()
    if pe[:4] == b"\0\0\1\0":   # ICONDIR: reserved 0, type 1 (icon)
        best = ico(pe)
        if best is None:
            sys.exit(f"{src}: no PNG image")
        open(dst, "wb").write(best[1])
        print(f"{dst}: {best[0]} px from {src}")
        return
    off, = struct.unpack_from("<I", pe, 0x3C)
    rsrc_rva, secs = sections(pe, off)
    base = rva2off(rsrc_rva, secs)
    groups = leaves(pe, base, secs, RT_GROUP_ICON)
    icons = leaves(pe, base, secs, RT_ICON)
    group = groups[int(sys.argv[3])] if len(sys.argv) > 3 else groups[min(groups)]
    count, = struct.unpack_from("<H", group, 4)
    best = None
    for i in range(count):
        w, h, _, _, _, bpp, size, icon_id = struct.unpack_from("<BBBBHHIH", group, 6 + 14 * i)
        data = icons.get(icon_id, b"")
        if data.startswith(b"\x89PNG"):
            side = w or 256
            if best is None or side > best[0]:
                best = (side, data)
    if best is None:
        sys.exit(f"{src}: no PNG image in its first icon group")
    open(dst, "wb").write(best[1])
    print(f"{dst}: {best[0]} px from {src}")


if __name__ == "__main__":
    main()
