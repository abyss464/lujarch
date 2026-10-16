"""Recovery's and fastbootd's images (BRD-3), drawn from the mark.

    python3 recovery.py OUT WORKDIR

Writes OUT/images/, which the build puts over recovery's own res/images:

  loop00000.png .. loop00059.png   600x600, the mark turning 72 degrees over the loop (2 s at 30 fps)
  progress_empty.png, progress_fill.png   252x7, the deep tone and the mark's colour
  stage_empty.png, stage_fill.png  50x50, one shard unlit and lit
  icon_error.png                   600x600, the mark unlit with one shard fallen out
  logo_image.png, logo_image_switch.png, fastbootd.png   768x480, the mark over the mode's name

Every shard is lit as in the mark's other images: one light from the upper left front over its two
facets. The mark turns 36 degrees a second; its five-fold symmetry repeats every 72 degrees, so the
loop is 60 frames at the 30 fps recovery plays them at. The words RECOVERY and FASTBOOTD are
LineageOS' own lettering (recovery-words.png: the coverage of the stock images' letters), drawn
in the mark's light tone. rsvg-convert draws onto black, since recovery blits its images opaque,
and minui reads 8-bit RGB without interlacing.
"""
import io
import json
import pathlib
import subprocess
import sys

import numpy as np
from PIL import Image

import boot
import kotlin
import mark

FPS = 30               # recovery's animation_fps
TURN_PER_S = 36.0      # degrees per second
PERIOD = 72.0          # degrees: the mark's five-fold symmetry
FRAMES = int(round(PERIOD / TURN_PER_S * FPS))
FRAME = 600            # px, the loop frame and the error icon
HEADER = (768, 480)
HEADER_CENTRE = (384, 240)   # where the stock header's ring sat
HEADER_RADIUS = 112          # px, the mark's outer radius in the header
ERROR_MARGIN = 30      # px kept clear round the error icon's picture
STAGE = 50
BAR = (252, 7)
WORDS = pathlib.Path(__file__).with_name("recovery-words.png")

# The mark at a state: maw(v, key, st) fills key<i>tf/op/a/b of the ten shards; shard(v, key, st)
# fills keytf/op/a/b of one canonical shard. st: x, y, ks (scale), rot, lit (0 unlit, 1 lit),
# per (rank: {dx, dy, drot}, the shards moved out of place, by their rank clockwise from the top).
PROGRAM = r'''
const S = __DATA__;
const CNA = __CNA__, CNB = __CNB__;
const BEVEL = 48 * Math.PI / 180;
const nrm = (v) => { const l = Math.hypot(v[0], v[1], v[2]); return [v[0] / l, v[1] / l, v[2] / l]; };
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const rad = (d) => d * Math.PI / 180;
const rotZ = (v, d) => { const c = Math.cos(rad(d)), s = Math.sin(rad(d)); return [v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]]; };
const rotX = (v, d) => { const c = Math.cos(rad(d)), s = Math.sin(rad(d)); return [v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c]; };
const rotY = (v, d) => { const c = Math.cos(rad(d)), s = Math.sin(rad(d)); return [v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c]; };
const LIGHT = nrm([-0.45, -0.72, -0.55]);
const HALF = nrm([LIGHT[0], LIGHT[1], LIGHT[2] - 1]);
const base = [1, 3, 5].map((i) => parseInt('__COLOR__'.slice(i, i + 2), 16));
const hex = (c) => '#' + c.map((x) => Math.round(Math.max(0, Math.min(255, x))).toString(16).padStart(2, '0')).join('');
const mix = (w, other) => hex(base.map((c, i) => c + (other[i] - c) * w));
const shade = (n) => {
  const d = Math.max(0, dot(n, LIGHT));
  const s = Math.pow(Math.max(0, dot(n, HALF)), 28);
  const k = 0.18 + 0.95 * d, sp = 0.8 * s;
  return hex(base.map((c, i) => c * k + [255, 236, 222][i] * sp));
};
const facetN = (n2, rz) => rotY(rotX(rotZ([n2[0] * Math.sin(BEVEL), n2[1] * Math.sin(BEVEL), -Math.cos(BEVEL)], rz), 0), 0);
const unlit = (n) => hex(base.map((c) => c * (0.16 + 0.22 * Math.max(0, dot(n, LIGHT)))));
const order = S.map((s, i) => [((Math.atan2(s.cy, s.cx) * 180 / Math.PI + 90 + 360) % 360), i]).sort((a, b) => a[0] - b[0]).map((x) => x[1]);
const rank = []; order.forEach((idx, k) => { rank[idx] = k; });
const maw = (v, key, st) => {
  S.forEach((s, i) => {
    const per = (st.per || {})[rank[i]] || {};
    const d = Math.hypot(s.cx, s.cy);
    const a0 = Math.atan2(s.cy, s.cx) + rad(st.rot || 0);
    const rr = d * st.ks;
    const px = st.x + Math.cos(a0) * rr + (per.dx || 0), py = st.y + Math.sin(a0) * rr + (per.dy || 0);
    const r = (st.rot || 0) + (per.drot || 0);
    v[key + i + 'tf'] = 'translate(' + px.toFixed(2) + ' ' + py.toFixed(2) + ') rotate(' + r.toFixed(2) + ') scale(' + st.ks.toFixed(4) + ') translate(' + (-s.cx).toFixed(2) + ' ' + (-s.cy).toFixed(2) + ')';
    v[key + i + 'op'] = (1).toFixed(3);
    const nA = facetN(s.na, r), nB = facetN(s.nb, r);
    v[key + i + 'a'] = st.lit === 0 ? unlit(nA) : shade(nA);
    v[key + i + 'b'] = st.lit === 0 ? unlit(nB) : shade(nB);
  });
};
const shard = (v, key, st) => {
  v[key + 'tf'] = 'translate(' + st.x.toFixed(2) + ' ' + st.y.toFixed(2) + ') rotate(' + (0).toFixed(2) + ') scale(' + st.len.toFixed(3) + ' ' + st.len.toFixed(3) + ')';
  v[key + 'op'] = (1).toFixed(3);
  const nA = facetN(CNA, 0), nB = facetN(CNB, 0);
  v[key + 'a'] = st.lit === 0 ? unlit(nA) : shade(nA);
  v[key + 'b'] = st.lit === 0 ? unlit(nB) : shade(nB);
};
const out = [];
for (const job of JSON.parse(require('fs').readFileSync(0, 'utf8'))) {
  const v = { color: '__COLOR__', light: mix(0.45, [255, 255, 255]), deep: mix(0.55, [0, 0, 0]) };
  for (const c of job) (c.fn === 'maw' ? maw : shard)(v, c.key, c.st);
  out.push(v);
}
process.stdout.write(JSON.stringify(out));
'''


def run(jobs):
    canon = kotlin.canonical(mark.facets())
    js = (PROGRAM.replace("__DATA__", json.dumps(boot.shard_data(mark.facets())))
          .replace("__CNA__", json.dumps(canon[2])).replace("__CNB__", json.dumps(canon[3]))
          .replace("__COLOR__", mark.COLOR))
    r = subprocess.run(["node", "-e", js], input=json.dumps(jobs), capture_output=True, text=True,
                       timeout=120)
    if r.returncode != 0:
        sys.exit("recovery: the program failed in Node: " + r.stderr.strip()[:2000])
    return json.loads(r.stdout)


def maw_markup(key):
    return "".join(
        f'<g transform="{{{{{key}{i}tf}}}}" opacity="{{{{{key}{i}op}}}}"><path d="{s["fa"]}" fill="{{{{{key}{i}a}}}}"></path>'
        f'<path d="{s["fb"]}" fill="{{{{{key}{i}b}}}}"></path></g>' for i, s in enumerate(mark.facets()))


def fill(template, v):
    import re
    return re.sub(r"\{\{(\w+)\}\}", lambda m: str(v[m.group(1)]), template)


def rasterize(body, box, w, h):
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="%s">%s</svg>'
           % (w, h, box, body))
    r = subprocess.run(["rsvg-convert", "-f", "png", "-b", "#000000", "-w", str(w), "-h", str(h)],
                       input=svg.encode(), capture_output=True, timeout=60)
    if r.returncode != 0:
        sys.exit("rsvg-convert: " + r.stderr.decode()[:500])
    return np.array(Image.open(io.BytesIO(r.stdout)).convert("RGB"))


def hex_rgb(c):
    return np.array([int(c[i:i + 2], 16) for i in (1, 3, 5)], dtype=np.float64)


SC = float(f"{boot.SCALE:.5f}")


def loop_frames():
    """Frame i turns the mark 36 i / 30 degrees, centred in a 200 x 200 unit box."""
    jobs = [[{"fn": "maw", "key": "m",
              "st": {"x": 0, "y": -50, "ks": SC, "rot": 36 * (int((i / FPS + 1e-9) * 30) / 30)}}]
            for i in range(FRAMES)]
    tpl = maw_markup("m")
    return [rasterize(fill(tpl, v), "-100 -150 200 200", FRAME, FRAME) for v in run(jobs)]


def bars(v):
    return (np.tile(hex_rgb(v["deep"]), (BAR[1], BAR[0], 1)),
            np.tile(hex_rgb(v["color"]), (BAR[1], BAR[0], 1)))


def outline(fa, fb):
    """The canonical shard's outline: facet a's two sides from the tip and facet b's from the base."""
    import re
    a = [float(x) for x in re.findall(r"-?\d+\.\d+", fa)]
    b = [float(x) for x in re.findall(r"-?\d+\.\d+", fb)]
    pa, pb = list(zip(a[0::2], a[1::2])), list(zip(b[0::2], b[1::2]))
    n = lambda p: " ".join(f"{x:.4f}".rstrip("0").rstrip(".") if x != 0 else "0" for x in p)
    cubic = lambda q: "C" + " ".join(n(p) for p in q)
    return f"M{n(pa[0])} {cubic(pa[1:4])} {cubic(pa[4:7])} {cubic(pb[2:5])} {cubic(pb[5:8])} Z"


def stage_markers():
    canon = kotlin.canonical(mark.facets())
    st = {"x": STAGE / 2, "y": 44, "len": 38}
    v = run([[{"fn": "shard", "key": "f", "st": dict(st, lit=1)},
              {"fn": "shard", "key": "e", "st": dict(st, lit=0)}]])[0]
    box = "0 0 %d %d" % (STAGE, STAGE)

    def one(k, lined):
        body = ('<g transform="%s"><path d="%s" fill="%s"/><path d="%s" fill="%s"/>'
                % (v[k + "tf"], canon[0], v[k + "a"], canon[1], v[k + "b"]))
        if lined:
            # a hairline in the deep tone keeps the unlit shard readable on black
            body += ('<path d="%s" fill="none" stroke="%s" stroke-width="%.4f" stroke-linejoin="round"/>'
                     % (outline(canon[0], canon[1]), v["deep"], 1.3 / st["len"]))
        return rasterize(body + "</g>", box, STAGE, STAGE)
    return one("e", True), one("f", False)


def error_icon():
    """The mark unlit, its fourth shard clockwise from the top fallen out below it, fitted to the
    600 px frame."""
    v = run([[{"fn": "maw", "key": "m",
               "st": {"x": 0, "y": -30, "ks": SC, "lit": 0, "per": {3: {"dx": 18, "dy": 150, "drot": 118}}}}]])[0]
    body = fill(maw_markup("m"), v)
    # the picture, measured on a probe at 2 px per unit
    probe = rasterize(body, "-300 -330 600 600", 1200, 1200)
    ys, xs = np.where(probe.sum(axis=2) > 8)
    x0, x1 = -300 + xs.min() / 2.0, -300 + (xs.max() + 1) / 2.0
    y0, y1 = -330 + ys.min() / 2.0, -330 + (ys.max() + 1) / 2.0
    side = max(x1 - x0, y1 - y0) * FRAME / (FRAME - 2 * ERROR_MARGIN)
    box = "%.3f %.3f %.3f %.3f" % ((x0 + x1) / 2 - side / 2, (y0 + y1) / 2 - side / 2, side, side)
    return rasterize(body, box, FRAME, FRAME)


def headers():
    v = run([[{"fn": "maw", "key": "h", "st": {"x": 0, "y": 0, "ks": HEADER_RADIUS / 99.323}}]])[0]
    w, h = HEADER
    box = "%.3f %.3f %d %d" % (-HEADER_CENTRE[0], -HEADER_CENTRE[1], w, h)
    ground = rasterize(fill(maw_markup("h"), v), box, w, h).astype(np.float64)
    light, red = hex_rgb(v["light"]), hex_rgb(v["color"])
    m = np.array(Image.open(WORDS).convert("L"), dtype=np.float64) / 255.0
    recovery, fastbootd, d_col = m[:34], m[34:68], 538
    rows = slice(393, 427)   # the stock lettering's rows

    def with_word(mask, d_from=None):
        img = ground.copy()
        colour = np.tile(light, (mask.shape[0], mask.shape[1], 1))
        if d_from is not None:
            colour[:, d_from:] = red   # the stock keeps fastbootd's "d" in the dark colour
        img[rows] = img[rows] * (1 - mask[..., None]) + colour * mask[..., None]
        return img
    rec = with_word(recovery)
    return rec, rec.copy(), with_word(fastbootd, d_col)


def main(out):
    v = run([[]])[0]
    images = {f"loop{i:05d}.png": a for i, a in enumerate(loop_frames())}
    images["progress_empty.png"], images["progress_fill.png"] = bars(v)
    images["stage_empty.png"], images["stage_fill.png"] = stage_markers()
    images["icon_error.png"] = error_icon()
    images["logo_image.png"], images["logo_image_switch.png"], images["fastbootd.png"] = headers()
    d = out / "images"
    d.mkdir(parents=True, exist_ok=True)
    for name, a in sorted(images.items()):
        Image.fromarray(np.ascontiguousarray(np.clip(np.rint(a), 0, 255), dtype=np.uint8), "RGB").save(
            d / name, format="PNG", optimize=True)


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
