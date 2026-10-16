"""The mark (BRD-2, BRD-3): the one drawing every image of the symbol is made from.

Seed 106 of a random glyph generator lays two kinds of element under five-fold symmetry: five big
arches and five small lenses cut by a ring. Each element becomes a shard, a curved triangle that
keeps three of its points (the tip and two base corners): every side is a cubic Bezier bowing into
the shard, its end tangents are the chords turned inward so each corner's angle is the target
(9 degrees at the tip, 26 at the base), and its control points sit on those tangents at a fixed
fraction of the way to where they meet, so no side has an S-bend. A ridge from the tip to the
middle of the base splits each shard into two facets, lit separately.

Coordinates are the symbol's own: y down, centre at the origin. Its colour is red ochre, the
system palette's seed; its light and deep tones are that colour mixed towards white and black.
"""
import math
import random


COLOR = "#b5462b"
SPECULAR = "#fff5ee"


def mix(color, w, other):
    """color mixed by w (0..1) towards other, both #rrggbb."""
    a = [int(color[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(other[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(max(0, min(255, x + (y - x) * w))):02x}" for x, y in zip(a, b))


LIGHT = mix(COLOR, 0.45, "#ffffff")
DEEP = mix(COLOR, 0.55, "#000000")


def rot(x, y, deg):
    a = math.radians(deg)
    return x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)


def polar(r, deg):
    a = math.radians(deg)
    return r * math.cos(a), r * math.sin(a)


def fmt(p):
    return f"{p[0]:.3f} {p[1]:.3f}"


# The generator. Every draw is kept, in order: the seed's numbers depend on all of them.
KINDS = ["arch", "arch", "vesica", "vesica", "circle", "sector", "bar", "tri", "spike", "arcband",
         "chevron"]


def _element(rng, big=False):
    k = rng.choice(KINDS)
    r = rng.uniform(0, 60)          # distance of the element from the centre
    a = rng.uniform(0, 360)         # its own orientation
    sz = rng.uniform(0.5, 1.3) * (1.4 if big else 1.0)
    return dict(k=k, r=r, pos_ang=rng.uniform(-180, 180), ang=a, sz=sz,
                q=[rng.random() for _ in range(6)])


def glyph(seed):
    rng = random.Random(seed)
    sym = rng.choice(["mirror", "mirror", "none", 2, 3, 3, 4, 5, 6, 8])
    frame = rng.choice([None, None, None, "circle", "diamond", "triangle", "reuleaux", "octagon",
                        "ring"])
    invert = frame not in (None, "ring") and rng.random() < 0.6
    layers = []
    for i in range(rng.choice([2, 3, 3, 4])):
        e = _element(rng, big=(i == 0))
        op = "add" if i == 0 else rng.choice(["add", "sub", "sub"])
        layers.append(dict(e=e, op=op, gap=rng.choice([0, 0, 2.5])))
    center = rng.choice([None, None, "core", "hole", "void"])
    return dict(seed=seed, sym=sym, frame=frame, invert=invert, layers=layers, center=center,
                core=rng.uniform(8, 18), frot=rng.choice([-90, 90]))


def copies(e, sym):
    """(cx, cy, angle shift, mirrored) of each copy of an element under the symmetry."""
    x0, y0 = polar(e["r"], e["pos_ang"])
    if sym == "none":
        yield x0, y0, 0, False
    elif sym == "mirror":
        yield x0, y0, 0, False
        yield -x0, y0, 0, True
    else:
        for i in range(sym):
            s = 360 / sym * i
            x, y = rot(x0, y0, s)
            yield x, y, s, False


# Geometry.
def _frame(base, axis):
    ax = (math.cos(math.radians(axis)), math.sin(math.radians(axis)))
    pp = (-ax[1], ax[0])
    return (lambda u, v: (base[0] + u * pp[0] + v * ax[0], base[1] + u * pp[1] + v * ax[1])), ax, pp


def _circle_hits(c1, r1, c2, r2):
    dx, dy = c2[0] - c1[0], c2[1] - c1[1]
    d = math.hypot(dx, dy)
    a = (r1 * r1 - r2 * r2 + d * d) / (2 * d)
    hh = math.sqrt(max(r1 * r1 - a * a, 0))
    mx, my = c1[0] + a * dx / d, c1[1] + a * dy / d
    return [(mx + hh * dy / d, my - hh * dx / d), (mx - hh * dy / d, my + hh * dx / d)]


def _ang(v):
    return math.atan2(v[1], v[0])


def rot_vec(v, a):
    c, s = math.cos(a), math.sin(a)
    return (v[0] * c - v[1] * s, v[0] * s + v[1] * c)


def _corner_angle(p, a, b):
    va = (a[0] - p[0], a[1] - p[1])
    vb = (b[0] - p[0], b[1] - p[1])
    d = abs(_ang(va) - _ang(vb)) % (2 * math.pi)
    return math.degrees(min(d, 2 * math.pi - d))


def _ray_hit(a, da, b, db):
    """Where rays a + s da and b + t db meet (s, t > 0), or None."""
    det = da[0] * (-db[1]) - da[1] * (-db[0])
    if abs(det) < 1e-9:
        return None
    rx, ry = b[0] - a[0], b[1] - a[1]
    s = (rx * (-db[1]) - ry * (-db[0])) / det
    t = (da[0] * ry - da[1] * rx) / det
    return (s, t) if s > 0 and t > 0 else None


def _split_cubic(p0, p1, p2, p3, t=0.5):
    L = lambda a, b: (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    a, b, c = L(p0, p1), L(p1, p2), L(p2, p3)
    d, e = L(a, b), L(b, c)
    m = L(d, e)
    return (p0, a, d, m), (m, e, c, p3)


# The shards' points: an arch keeps its apex and its two feet; a lens cut by the ring keeps its
# outer tip and the two points where its sides cross the ring.
def _arch_points(e, cx, cy, sh):
    q = e["q"]
    sz = e["sz"]
    ang = e["ang"] + sh
    w = (8 + 30 * q[0]) * sz
    h = (30 + 60 * q[1]) * sz
    legs = (q[2] > 0.5) * 20 * q[3] * sz
    P, _, _ = _frame((cx, cy), ang)
    return P(0, h), P(-w, -legs), P(w, -legs)


def _lens_points(e, cx, cy, sh, rho):
    q = e["q"]
    sz = e["sz"]
    ang = e["ang"] + sh
    a = (18 + 45 * q[0]) * sz
    b = a * (0.15 + 0.55 * q[1])
    Rv = (a * a + b * b) / (2 * b)
    P, ax, pp = _frame((cx, cy), ang)
    t1, t2 = P(0, a), P(0, -a)
    tip = t1 if math.hypot(*t1) > math.hypot(*t2) else t2
    hits = []
    for s in (1, -1):
        centre = (cx - s * (Rv - b) * pp[0], cy - s * (Rv - b) * pp[1])
        side = lambda p: (p[0] - cx) * pp[0] + (p[1] - cy) * pp[1]
        hits.append(max(_circle_hits(centre, Rv, (0, 0), rho), key=lambda p: s * side(p)))
    return tip, hits[0], hits[1]


SEED = 106
TIP_DEG = 9
BASE_DEG = 26
# Where a side's control points sit, as the fraction of the way to where its end tangents meet:
# at the tip end, at a base end.
KFRAC = (0.55, 0.72)


def facets():
    """The ten shards in angular order, each with its two facets' paths (fa, fb), its centroid
    (c), its tip, and each facet's outward direction in the plane (na, nb) for the lighting."""
    s = glyph(SEED)
    L0, L1, L2, L3 = s["layers"]
    rho = (20 + 70 * L3["e"]["q"][0]) + (4 + 12 * L3["e"]["q"][2]) / 2 + L3["gap"]
    groups = [_arch_points(L0["e"], cx, cy, sh) for cx, cy, sh, _ in copies(L0["e"], s["sym"])]
    groups += [_lens_points(L2["e"], cx, cy, sh, rho) for cx, cy, sh, _ in copies(L2["e"], s["sym"])]
    out = []
    for V in groups:
        area = sum(V[i][0] * V[(i + 1) % 3][1] - V[(i + 1) % 3][0] * V[i][1] for i in range(3))
        order = [0, 1, 2] if area > 0 else [0, 2, 1]
        W = [V[i] for i in order]
        T = [(TIP_DEG, BASE_DEG, BASE_DEG)[i] for i in order]
        chord_ang = [_corner_angle(W[i], W[i - 1], W[(i + 1) % 3]) for i in range(3)]
        defl = [max((chord_ang[i] - T[i]) / 2, 2.0) for i in range(3)]
        sides = []
        for i in range(3):
            a, b = W[i], W[(i + 1) % 3]
            Lc = math.hypot(b[0] - a[0], b[1] - a[1])
            u = ((b[0] - a[0]) / Lc, (b[1] - a[1]) / Lc)
            ta = rot_vec(u, math.radians(defl[i]))
            tb = rot_vec((-u[0], -u[1]), -math.radians(defl[(i + 1) % 3]))
            ka = KFRAC[0] if order[i] == 0 else KFRAC[1]
            kb = KFRAC[0] if order[(i + 1) % 3] == 0 else KFRAC[1]
            sa, sb = _ray_hit(a, ta, b, tb)
            sides.append((a, (a[0] + ta[0] * ka * sa, a[1] + ta[1] * ka * sa),
                          (b[0] + tb[0] * kb * sb, b[1] + tb[1] * kb * sb), b))
        tip, b1, b2 = W[0], W[1], W[2]
        h1, h2 = _split_cubic(*sides[1])
        m = h1[3]
        fa = (f"M{fmt(tip)} C{fmt(sides[0][1])} {fmt(sides[0][2])} {fmt(b1)} "
              f"C{fmt(h1[1])} {fmt(h1[2])} {fmt(m)} Z")
        fb = (f"M{fmt(tip)} L{fmt(m)} C{fmt(h2[1])} {fmt(h2[2])} {fmt(b2)} "
              f"C{fmt(sides[2][1])} {fmt(sides[2][2])} {fmt(tip)} Z")
        ux, uy = tip[0] - m[0], tip[1] - m[1]
        ul = math.hypot(ux, uy)
        ux, uy = ux / ul, uy / ul
        px, py = -uy, ux
        sgn = 1 if (b1[0] - m[0]) * px + (b1[1] - m[1]) * py > 0 else -1
        c = (sum(p[0] for p in V) / 3, sum(p[1] for p in V) / 3)
        out.append(dict(fa=fa, fb=fb, c=c, tip=tip, na=(sgn * px, sgn * py),
                        nb=(-sgn * px, -sgn * py)))
    out.sort(key=lambda x: math.atan2(x["c"][1], x["c"][0]))
    return out
