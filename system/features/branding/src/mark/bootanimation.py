"""The boot animation's zip (MOT-1, BRD-3), rendered from the mark.

    python3 bootanimation.py OUT.zip WORKDIR [--jobs N]

boot.py's frame program runs in Node at every frame time and fills the frame's SVG, rsvg-convert
draws it at the panel's full 1080 x 2400 (the artboard is 360 x 800, 3 px a unit), and each frame
is:
- dithered to RGB_565's levels with a blue-noise tile (bluenoise.py): the player's surface is
  RGB_565 and draws with GL_DITHER off, so undithered gradients band; a value on a 565 level is
  stored as round(q * 255 / L), which both truncation and rounding map back to q;
- trimmed to its lit pixels (trim.txt), since the player decodes every frame of a `c 1` part
  while it plays and keeps every frame of the loop as a texture;
- written as an 8-bit paletted PNG (the colours are one hue, its light and white); a strike
  frame whose dither needs more than 256 colours is dithered to a palette instead (quantize).

Parts (desc.txt): part0 `c 1`, the intro (0-3.3 s: fall, strike, splash, gather until 2.5 s, then
the 0.8 s in which the sway and turn ramp in); part1 `f 0 0 part1 24`, the loop (5.2 s, one 72
degree symmetry step of the mark, so its last frame leads into its first), which the player fades
out over 0.4 s while it keeps turning when the start is done.

The same inputs and the same Node, rsvg-convert, numpy and Pillow give the same zip, byte for byte.
"""
import argparse
import concurrent.futures
import json
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile

import numpy as np
from PIL import Image

import boot
from bluenoise import blue_noise

W, H = 1080, 2400
FPS = 60
INTRO = 3.3        # s: to GATHER (2.5) and the 0.8 s ramp into the turn
LOOP = 5.2         # s: 72 deg at 72/5.2 deg/s, both sways 2.6 s, bites every 1.3 s
FADE = 0.4         # s: the player's fade of the loop when the start is done
LEVELS = np.array([31, 63, 31])


def times(fps):
    """(part, index, t) for every frame; t is exact in frames (k / fps)."""
    out = [("part0", k, k / fps) for k in range(round(INTRO * fps))]
    n0 = len(out)
    out += [("part1", k, (n0 + k) / fps) for k in range(round(LOOP * fps))]
    return out


def run_frames(jobs, workdir):
    """Runs the frame program in Node. jobs: [(t, svg path)]. Writes the filled SVGs."""
    workdir.mkdir(parents=True, exist_ok=True)
    js = (boot.program() + "\n"
          "const fs = require('fs');\n"
          f"const markup = {json.dumps(boot.markup())};\n"
          f"const jobs = {json.dumps(jobs)};\n"
          """
for (const [t, path] of jobs) {
  const v = frame(t);
  const out = markup.replace(/\\{\\{(\\w+)\\}\\}/g, (m, k) => (k in v ? String(v[k]) : m));
  const miss = out.match(/\\{\\{\\w+\\}\\}/g);
  if (miss) { console.error('unfilled holes at t=' + t + ': ' + [...new Set(miss)].join(' ')); process.exit(1); }
  if (/NaN|undefined/.test(out)) { console.error('NaN or undefined in the frame at t=' + t); process.exit(1); }
  fs.writeFileSync(path, out);
}
""")
    prog = workdir / "frames.js"
    prog.write_text(js)
    r = subprocess.run(["node", str(prog)], capture_output=True, text=True, timeout=600)
    if r.returncode != 0:
        sys.exit(f"bootanimation: the frame program failed in Node:\n{r.stderr}")


def rasterize(svg, png):
    subprocess.run(["rsvg-convert", "-w", str(W), "-h", str(H), "-b", "#000000", str(svg), "-o", str(png)],
                   check=True, timeout=120)


def load_rgb(png):
    return np.asarray(Image.open(png).convert("RGB"))


# The blue-noise tile's side. Tiles of 16, 32 and 64 px have the same spectrum (no energy below
# 0.13 cycles/px, so the repeat does not show), but deflate matches a dithered row one tile back:
# at 60 fps the zip is 8.3 MB with 16 px, 10.2 MB with 32, 11.6 MB with 64 (2026-09-30).
TILE_SIDE = 16
_TILE = None


def tile():
    global _TILE
    if _TILE is None:
        _TILE = blue_noise(TILE_SIDE)
    return _TILE


def dither565(rgb):
    """Blue-noise dither to RGB_565 levels, one threshold for the three channels (so the noise
    runs along the colour's own ray: lighter and darker, not other hues). Stored as 8-bit values
    round(q * 255 / L)."""
    h, w = rgb.shape[:2]
    n = tile().shape[0]
    thr = np.tile(tile(), (-(-h // n), -(-w // n)))[:h, :w, None]
    q = np.minimum(np.floor(rgb.astype(np.float64) / 255 * LEVELS + thr), LEVELS)
    return np.round(q * 255 / LEVELS).astype(np.uint8)


def trim_box(img):
    lit = img.any(axis=2)
    rows, cols = np.flatnonzero(lit.any(axis=1)), np.flatnonzero(lit.any(axis=0))
    if rows.size == 0:
        return 0, 0, 1, 1              # an all-black frame: one black pixel
    return int(cols[0]), int(rows[0]), int(cols[-1] - cols[0] + 1), int(rows[-1] - rows[0] + 1)


WEIGHT = np.array([2.0, 4.0, 3.0])     # colour distance: green weighs most, as the eye does


def snap565(c):
    q = np.clip(np.round(c / 255 * LEVELS), 0, LEVELS)
    return np.round(q * 255 / LEVELS)


def _nearest(x, pal):
    return ((((x[:, None, :] - pal[None, :, :]) ** 2) * WEIGHT).sum(-1)).argmin(1)


KEEP = 200    # of 256: the most frequent 565 colours, kept as they are


def palette_256(cols, counts):
    """256 of a frame's RGB_565 dither colours, for a frame that has more (the strike: the flash,
    the jet and the flash-lit facets). palette_dither mixes two palette colours, so the palette
    has to surround every colour rather than follow where pixels are dense: the KEEP most
    frequent colours (black, the water's levels, the large facets) stay, and the rest are chosen
    farthest point first (each the colour farthest from the palette so far), which spreads them
    evenly over what is left, out to the flash's white."""
    c = cols.astype(np.float64)
    order = np.argsort(-counts, kind="stable")
    chosen = list(order[:KEEP])
    d = (((c[:, None, :] - c[None, chosen, :]) ** 2) * WEIGHT).sum(-1).min(1)
    while len(chosen) < 256:
        j = int(d.argmax())
        chosen.append(j)
        d = np.minimum(d, (((c - c[j]) ** 2) * WEIGHT).sum(1))
    return cols[np.array(chosen)].astype(np.uint8)


def _key(a):
    return (a[..., 0].astype(np.uint32) << 16) | (a[..., 1].astype(np.uint32) << 8) | a[..., 2]


def palette_dither(rgb, d, pal):
    """The frame in the palette's colours. A render colour whose 565 dither d uses only palette
    colours keeps that dither (the water, the shards: per channel, up to four colours nested).
    The others (the flash's core) are dithered between two palette colours with the same tile:
    each lies between its nearest palette colour c1 and the nearest c2 to its mirror image
    2x - c1, and the threshold picks c2 with the colour's share t of the way from c1 to c2, so
    the local mean is the colour projected on c1-c2."""
    h, w = rgb.shape[:2]
    colours, inverse = np.unique(_key(rgb).ravel(), return_inverse=True)
    in_pal = np.isin(_key(d).ravel(), _key(pal))
    missing = np.bincount(inverse, weights=~in_pal, minlength=len(colours)) > 0
    x = np.stack([(colours >> 16) & 255, (colours >> 8) & 255, colours & 255], 1).astype(np.float64)
    p = pal.astype(np.float64)
    i1 = _nearest(x, p)
    i2 = _nearest(2 * x - p[i1], p)
    seg = p[i2] - p[i1]
    den = (seg * seg * WEIGHT).sum(1)
    t = np.where(den > 0, ((x - p[i1]) * seg * WEIGHT).sum(1) / np.where(den > 0, den, 1), 0).clip(0, 1)
    n = tile().shape[0]
    thr = np.tile(tile(), (-(-h // n), -(-w // n)))[:h, :w].ravel()
    mix = pal[np.where(thr < t[inverse], i2[inverse], i1[inverse])]
    out = np.where(missing[inverse][:, None], mix, d.reshape(-1, 3))
    return out.reshape(h, w, 3).astype(np.uint8)


def paletted(img):
    """An 8-bit palette, most frequent colour first, of a frame whose colours fit in 256."""
    key = _key(img)
    colours, inverse, counts = np.unique(key.ravel(), return_inverse=True, return_counts=True)
    if len(colours) > 256:
        raise ValueError(f"{len(colours)} colours: more than a palette holds")
    order = np.lexsort((colours, -counts))
    remap = np.zeros(len(colours), np.uint8); remap[order] = np.arange(len(colours), dtype=np.uint8)
    p = Image.fromarray(remap[inverse].reshape(key.shape), "P")
    pal = colours[order]
    p.putpalette(np.stack([(pal >> 16) & 255, (pal >> 8) & 255, pal & 255], 1).astype(np.uint8).ravel().tolist())
    return p


def quantize(rgb):
    """The frame as it is stored, in 565 colours that fit a palette: the 565 dither (every loop
    frame and most of the intro), or where that needs more than 256 colours, palette_dither with
    palette_256. Returns (frame, colours of the 565 dither)."""
    d = dither565(rgb)
    colours, counts = np.unique(_key(d).ravel(), return_counts=True)
    if len(colours) <= 256:
        return d, len(colours)
    cols = np.stack([(colours >> 16) & 255, (colours >> 8) & 255, colours & 255], 1)
    return palette_dither(rgb, d, palette_256(cols, counts)), len(colours)


def finish(src_png, dst_png):
    """Dither, trim, palette: returns the trim line and the colour count."""
    d, ncol = quantize(load_rgb(src_png))
    x, y, w, h = trim_box(d)
    paletted(d[y:y + h, x:x + w]).save(dst_png, optimize=False, compress_level=9)
    return f"{w}x{h}+{x}+{y}", ncol


def write_zip(path, fps, frames_dir, trims):
    fade = round(FADE * fps)
    desc = f"{W} {H} {fps}\nc 1 0 part0\nf 0 0 part1 {fade}\n"
    entries = [("desc.txt", desc.encode())]
    for part in ("part0", "part1"):
        names = sorted(trims[part])
        entries += [(f"{part}/{n}.png", (frames_dir / part / f"{n}.png").read_bytes()) for n in names]
        entries.append((f"{part}/trim.txt", "".join(trims[part][n] + "\n" for n in names).encode()))
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            info.create_system = 3
            z.writestr(info, data)


def render(out, work, jobs):
    svg_dir, raw_dir, frames_dir = work / "svg", work / "raw", work / "frames"
    for d in (svg_dir, raw_dir, frames_dir / "part0", frames_dir / "part1"):
        d.mkdir(parents=True, exist_ok=True)
    frames = times(FPS)
    print(f"bootanimation: {len(frames)} frames at {FPS} fps (intro {INTRO} s, loop {LOOP} s)", flush=True)
    run_frames([[t, str(svg_dir / f"{p}_{k:05d}.svg")] for p, k, t in frames], work)
    with concurrent.futures.ThreadPoolExecutor(jobs) as ex:
        list(ex.map(lambda f: rasterize(svg_dir / f"{f[0]}_{f[1]:05d}.svg", raw_dir / f"{f[0]}_{f[1]:05d}.png"), frames))
    trims = {"part0": {}, "part1": {}}
    with concurrent.futures.ProcessPoolExecutor(jobs) as ex:
        futs = {(p, k): ex.submit(finish, raw_dir / f"{p}_{k:05d}.png", frames_dir / p / f"{k:05d}.png")
                for p, k, _ in frames}
        for (p, k), fut in futs.items():
            trims[p][f"{k:05d}"], _ = fut.result()
    tmp = out.with_name(out.name + ".tmp")
    write_zip(tmp, FPS, frames_dir, trims)
    os.replace(tmp, out)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("out", type=pathlib.Path)
    ap.add_argument("work", type=pathlib.Path)
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) // 2))
    a = ap.parse_args()
    for tool in ("node", "rsvg-convert"):
        if not shutil.which(tool):
            sys.exit(f"bootanimation: {tool} is missing")
    render(a.out, a.work, a.jobs)


if __name__ == "__main__":
    main()
