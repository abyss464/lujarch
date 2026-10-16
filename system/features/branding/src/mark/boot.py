"""The boot animation (MOT-1): one frame of it as an SVG, at any time t.

The arrow falls on its sounding line toward its own reflection and strikes the water at 0.85 s.
The mark's ten shards leave as a crown splash, flying in depth: toward the eye they grow and
brighten, away they shrink and dim. From 1.4 s they land on a ring lying on the water, which swings
up to face the eye by 2.5 s. Then the ring turns, sways a few degrees in 3D and bites every 1.3 s.
Each shard is two facets lit by one light from the upper left front (Lambert diffuse and a Blinn
highlight), so light slides over the facets as they turn; the strike's flash lights them for a
moment. The turn is 72 degrees in 5.2 s, one step of the mark's five-fold symmetry, and both sways
take 2.6 s, so from GATHER + 0.8 s the motion repeats every 5.2 s.

FRAME is a JavaScript program: frame(t) returns the values of the {{holes}} in MARKUP. The
artboard is 360 x 800 units with the origin at its centre and the waterline through it.
"""
import json
import math

import mark

SCALE = 1.15 * 200 / 280          # artboard units per mark unit
ARROW_LEFT = "M0 0 C-2 -18 -4.5 -35 -9 -50 C-6 -48.2 -3 -47.2 0 -47 Z"
ARROW_RIGHT = "M0 0 L0 -47 C3 -47.2 6 -48.2 9 -50 C4.5 -35 2 -18 0 0 Z"

FRAME = r'''
const S = __DATA__;
const FALL = 0.85, SPLASH = 1.4, GATHER = 2.5, BITE0 = 2.75, BITEP = 1.3;
const FOC = 520, BEVEL = 48 * Math.PI / 180;
const clamp = (x) => Math.max(0, Math.min(1, x));
const lerp = (a, b, u) => a + (b - a) * u;
const easeOut = (u) => 1 - Math.pow(1 - u, 3);
const easeIn = (u) => u * u;
const easeInOut = (u) => (u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2);
const easeOutBack = (u) => { const k = 1.35; const v = u - 1; return 1 + (k + 1) * v * v * v + k * v * v; };
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
const facetN = (n2, rz, tx, ty) => rotY(rotX(rotZ([n2[0] * Math.sin(BEVEL), n2[1] * Math.sin(BEVEL), -Math.cos(BEVEL)], rz), tx), ty);
// Each shard's launch from the strike: a crown of directions, fanned by its index.
const launch = (i, n) => {
  const j = (i * 7) % n;
  const a = -128 + 76 * j / (n - 1) + 5 * Math.sin(i * 2.9), v = 540 + 70 * Math.sin(i * 1.3 + 0.5);
  return { vx: v * Math.cos(rad(a)), vy: v * Math.sin(rad(a)), vz: 250 * Math.sin(i * 2.1 + 0.3), g: 1300 };
};
const bite = (tt) => {
  if (tt < BITE0) return 0;
  const p = (tt - BITE0) % BITEP;
  if (p < 0.14) return easeIn(p / 0.14);
  if (p < 0.20) return 1;
  if (p < 0.60) return 1 - easeOut((p - 0.2) / 0.4);
  return 0;
};

function frame(t) {
  const flashI = t >= FALL ? Math.exp(-(t - FALL) / 0.22) : 0;
  const shade = (n, dim, reflect) => {
    const d = Math.max(0, dot(n, LIGHT));
    const s = Math.pow(Math.max(0, dot(n, HALF)), 28);
    const k = (0.18 + 0.95 * d + 0.9 * flashI) * dim * (reflect ? 0.45 : 1);
    const sp = (0.8 * s + 0.5 * flashI * s) * (reflect ? 0.3 : 1);
    return hex(base.map((c, i) => c * k + [255, 236, 222][i] * sp));
  };
  const v = { color: '__COLOR__', light: mix(0.45, [255, 255, 255]), deep: mix(0.55, [0, 0, 0]) };
  const water = Math.min(clamp(t / 0.4), 1 - clamp((t - 2.0) / 0.6));
  v.water = (water * 0.6).toFixed(3);
  v.sheen = (water * 0.9).toFixed(3);
  v.reflOp = (water * 0.9).toFixed(3);
  // the arrow, its glowing tip, its reflection
  const ay = t < FALL ? -470 + 470 * Math.pow(t / FALL, 2) : (t - FALL) * 380;
  v.arrowTf = 'translate(0 ' + ay.toFixed(2) + ')';
  v.arrowRtf = 'scale(1 -1) translate(0 ' + ay.toFixed(2) + ')';
  v.arrowOp = t < FALL + 0.14 ? 1 : 0;
  v.arrowA = shade(nrm([-Math.sin(BEVEL), 0, -Math.cos(BEVEL)]), 1, false);
  v.arrowB = shade(nrm([Math.sin(BEVEL), 0, -Math.cos(BEVEL)]), 1, false);
  v.arrowRA = shade(nrm([-Math.sin(BEVEL), 0, -Math.cos(BEVEL)]), 1, true);
  v.arrowRB = shade(nrm([Math.sin(BEVEL), 0, -Math.cos(BEVEL)]), 1, true);
  v.tipY = ay.toFixed(2);
  v.tipOp = (t < FALL ? 0.25 + 0.6 * Math.pow(t / FALL, 3) : 0).toFixed(3);
  v.threadY2 = (ay - 50).toFixed(2);
  v.threadOp = (0.75 * (1 - clamp((t - FALL) / 0.25))).toFixed(3);
  // the strike's light
  const fu = clamp((t - FALL) / 0.28);
  v.flashR = (10 + 60 * easeOut(fu)).toFixed(2);
  v.flashOp = (t >= FALL && fu < 1 ? Math.pow(1 - fu, 1.6) : 0).toFixed(3);
  const su = clamp((t - FALL) / 0.5);
  v.stRx = (30 + 140 * easeOut(su)).toFixed(2);
  v.stRy = (4 + 5 * su).toFixed(2);
  v.stOp = (t >= FALL && su < 1 ? 0.95 * Math.pow(1 - su, 2) : 0).toFixed(3);
  [1, 2, 3].forEach((k) => {
    const u = clamp((t - FALL - 0.11 * (k - 1)) / 0.95);
    const rx = 8 + 165 * easeOut(u);
    v['r' + k + 'x'] = rx.toFixed(2); v['r' + k + 'y'] = (rx * 0.16).toFixed(2);
    v['r' + k + 'op'] = (u > 0 && u < 1 ? 0.75 * (1 - u) : 0).toFixed(3);
  });
  // the Worthington jet: two lit facets, and its reflection
  const jj = clamp((t - FALL - 0.1) / 0.55);
  const jh = (jj <= 0 || jj >= 1) ? 0 : 130 * Math.sin(Math.PI * jj);
  const jet = (s) => 'M0 0 L' + (4.5 * s) + ' 0 C' + (1.6 * s) + ' ' + (-jh * 0.5).toFixed(2) + ' ' + (0.6 * s) + ' ' + (-jh * 0.8).toFixed(2) + ' 0 ' + (-jh).toFixed(2) + ' Z';
  v.jetL = jet(-1); v.jetR = jet(1);
  v.jetA = v.arrowA; v.jetB = v.arrowB; v.jetRA = v.arrowRA; v.jetRB = v.arrowRB;
  // the shards
  const n = S.length;
  S.forEach((s, i) => {
    let px = 0, py = 0, r = 0, sc = 0, sqx = 1, sqy = 1, dim = 1, tx = 0, ty = 0, op = 0;
    if (t >= FALL) {
      op = 1;
      const L = launch(i, n);
      const fl = (tau) => { const X = L.vx * tau, Y = L.vy * tau + 0.5 * L.g * tau * tau, Z = L.vz * tau;
        return { X: X, Y: Y, Z: Z, r: Math.atan2(L.vy + L.g * tau, L.vx) * 180 / Math.PI - s.own, s: lerp(0.45, 0.72, clamp(tau / (SPLASH - FALL))) }; };
      let X, Y, Z, s0;
      if (t < SPLASH) {
        const f = fl(t - FALL); X = f.X; Y = f.Y; Z = f.Z; r = f.r; s0 = f.s;
      } else if (t < GATHER) {
        const f = fl(SPLASH - FALL);
        const u = clamp((t - SPLASH) / (GATHER - SPLASH));
        const e = easeOutBack(u), th = 74 * (1 - easeInOut(u));
        const r0 = ((f.r + 180) % 360 + 360) % 360 - 180;
        X = lerp(f.X, s.cx, e); Y = lerp(f.Y, s.cy * Math.cos(rad(th)), e); Z = lerp(f.Z, -s.cy * Math.sin(rad(th)), e);
        r = lerp(r0, 0, e); s0 = lerp(f.s, 1, e); sqy = lerp(1, Math.cos(rad(th)), clamp(e)); tx = th * clamp(e);
      } else {
        // the turn and the sways ramp in over 0.8 s, so the ring leaves its rest without a kick
        const b = bite(t), since = t - GATHER, amp = clamp(since / 0.8);
        const spin = 72 / 5.2 * (since < 0.8 ? since * since / 1.6 : since - 0.4);
        tx = 9 * amp * Math.sin(2 * Math.PI * since / 2.6);
        ty = 7 * amp * Math.sin(2 * Math.PI * since / 2.6 + 1.1);
        let p = rotZ([s.cx * (1 - 0.2 * b), s.cy * (1 - 0.2 * b), 0], spin);
        p = rotY(rotX(p, tx), ty);
        X = p[0]; Y = p[1]; Z = p[2]; r = spin; s0 = 1 + 0.06 * b;
        sqx = Math.cos(rad(ty)); sqy = Math.cos(rad(tx));
      }
      const k = FOC / (FOC + Z);
      px = X * k; py = Y * k; sc = s0 * k; dim = Math.max(0.4, Math.min(1.35, 1 - Z / 650));
    }
    const tf = 'translate(' + px.toFixed(2) + ' ' + py.toFixed(2) + ') scale(' + sqx.toFixed(4) + ' ' + sqy.toFixed(4) + ') rotate(' + r.toFixed(2) + ') scale(' + sc.toFixed(4) + ') translate(' + (-s.cx).toFixed(2) + ' ' + (-s.cy).toFixed(2) + ')';
    v['s' + i + 'tf'] = tf; v['s' + i + 'op'] = op;
    v['s' + i + 'rtf'] = 'scale(1 -1) ' + tf; v['s' + i + 'rop'] = op * (py < 0 ? 1 : 0);
    const nA = facetN(s.na, r, tx, ty), nB = facetN(s.nb, r, tx, ty);
    v['s' + i + 'a'] = shade(nA, dim, false); v['s' + i + 'b'] = shade(nB, dim, false);
    v['s' + i + 'ra'] = shade(nA, dim, true); v['s' + i + 'rb'] = shade(nB, dim, true);
  });
  return v;
}
'''


def shard_data(facets):
    """Each shard's centroid, own angle (centroid to tip) and facet directions, as the frame
    program reads them."""
    return [dict(cx=round(s["c"][0], 3), cy=round(s["c"][1], 3),
                 own=round(math.degrees(math.atan2(s["tip"][1] - s["c"][1],
                                                   s["tip"][0] - s["c"][0])), 3),
                 na=[round(s["na"][0], 4), round(s["na"][1], 4)],
                 nb=[round(s["nb"][0], 4), round(s["nb"][1], 4)])
            for s in facets]


def program():
    return (FRAME.replace("__DATA__", json.dumps(shard_data(mark.facets())))
            .replace("__COLOR__", mark.COLOR))


def markup():
    """The frame's SVG with {{holes}} for frame(t)'s values."""
    F = mark.facets()
    shards = "\n".join(
        f'<g transform="{{{{s{i}tf}}}}" opacity="{{{{s{i}op}}}}"><path d="{s["fa"]}" fill="{{{{s{i}a}}}}"></path>'
        f'<path d="{s["fb"]}" fill="{{{{s{i}b}}}}"></path></g>' for i, s in enumerate(F))
    reflections = "\n".join(
        f'<g transform="{{{{s{i}rtf}}}}" opacity="{{{{s{i}rop}}}}"><path d="{s["fa"]}" fill="{{{{s{i}ra}}}}"></path>'
        f'<path d="{s["fb"]}" fill="{{{{s{i}rb}}}}"></path></g>' for i, s in enumerate(F))
    AL, AR = ARROW_LEFT, ARROW_RIGHT
    return f'''<svg width="360" height="800" viewBox="-180 -400 360 800" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Lujarch boot animation" style="display: block">
<defs>
<clipPath id="above"><rect x="-180" y="-400" width="360" height="400"></rect></clipPath>
<radialGradient id="flash">
<stop offset="0" stop-color="#fff5ee"></stop>
<stop offset="0.3" stop-color="{{{{light}}}}" stop-opacity="0.9"></stop>
<stop offset="1" stop-color="{{{{color}}}}" stop-opacity="0"></stop>
</radialGradient>
<radialGradient id="streak">
<stop offset="0" stop-color="#fff5ee"></stop>
<stop offset="0.45" stop-color="{{{{light}}}}" stop-opacity="0.6"></stop>
<stop offset="1" stop-color="{{{{color}}}}" stop-opacity="0"></stop>
</radialGradient>
<radialGradient id="tip">
<stop offset="0" stop-color="{{{{light}}}}" stop-opacity="0.9"></stop>
<stop offset="1" stop-color="{{{{color}}}}" stop-opacity="0"></stop>
</radialGradient>
<linearGradient id="sheen" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="260">
<stop offset="0" stop-color="{{{{deep}}}}" stop-opacity="0.55"></stop>
<stop offset="0.22" stop-color="{{{{deep}}}}" stop-opacity="0.18"></stop>
<stop offset="1" stop-color="{{{{deep}}}}" stop-opacity="0"></stop>
</linearGradient>
<linearGradient id="reflFade" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="160">
<stop offset="0" stop-color="#ffffff"></stop>
<stop offset="1" stop-color="#000000"></stop>
</linearGradient>
<mask id="reflMask" maskUnits="userSpaceOnUse" x="-180" y="0" width="360" height="400">
<rect x="-180" y="0" width="360" height="400" fill="url(#reflFade)"></rect>
</mask>
</defs>
<rect x="-180" y="0" width="360" height="260" fill="url(#sheen)" opacity="{{{{sheen}}}}"></rect>
<line x1="-180" y1="0" x2="180" y2="0" stroke="{{{{deep}}}}" stroke-width="1" opacity="{{{{water}}}}"></line>
<g mask="url(#reflMask)" opacity="{{{{reflOp}}}}">
<g transform="{{{{arrowRtf}}}}" opacity="{{{{arrowOp}}}}"><path d="{AL}" fill="{{{{arrowRA}}}}"></path><path d="{AR}" fill="{{{{arrowRB}}}}"></path></g>
<g transform="scale(1 -1)"><path d="{{{{jetL}}}}" fill="{{{{jetRA}}}}"></path><path d="{{{{jetR}}}}" fill="{{{{jetRB}}}}"></path></g>
<g transform="scale({SCALE:.5f})">
{reflections}
</g>
</g>
<ellipse cx="0" cy="0" rx="{{{{r1x}}}}" ry="{{{{r1y}}}}" fill="none" stroke="{{{{color}}}}" stroke-width="1.3" opacity="{{{{r1op}}}}"></ellipse>
<ellipse cx="0" cy="0" rx="{{{{r2x}}}}" ry="{{{{r2y}}}}" fill="none" stroke="{{{{color}}}}" stroke-width="1.1" opacity="{{{{r2op}}}}"></ellipse>
<ellipse cx="0" cy="0" rx="{{{{r3x}}}}" ry="{{{{r3y}}}}" fill="none" stroke="{{{{color}}}}" stroke-width="0.9" opacity="{{{{r3op}}}}"></ellipse>
<line x1="0" y1="-400" x2="0" y2="{{{{threadY2}}}}" stroke="{{{{color}}}}" stroke-width="0.8" opacity="{{{{threadOp}}}}"></line>
<circle cx="0" cy="{{{{tipY}}}}" r="16" fill="url(#tip)" opacity="{{{{tipOp}}}}"></circle>
<g clip-path="url(#above)">
<g transform="{{{{arrowTf}}}}" opacity="{{{{arrowOp}}}}"><path d="{AL}" fill="{{{{arrowA}}}}"></path><path d="{AR}" fill="{{{{arrowB}}}}"></path></g>
<path d="{{{{jetL}}}}" fill="{{{{jetA}}}}"></path><path d="{{{{jetR}}}}" fill="{{{{jetB}}}}"></path>
</g>
<ellipse cx="0" cy="3" rx="{{{{stRx}}}}" ry="{{{{stRy}}}}" fill="url(#streak)" opacity="{{{{stOp}}}}"></ellipse>
<circle cx="0" cy="0" r="{{{{flashR}}}}" fill="url(#flash)" opacity="{{{{flashOp}}}}"></circle>
<g transform="scale({SCALE:.5f})">
{shards}
</g>
</svg>'''
