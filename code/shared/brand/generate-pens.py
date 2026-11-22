#!/usr/bin/env python3
"""Syncr tool and action glyphs: feathered, sketch-like art from one source.

Every edge of a glyph is a few thin, tapered, nearly straight strands that pinch at one end, fan at the other and flick
past the corners, modelled on the owner's hand-drawn nib (design draft: tasks/design/feathered/feather.py, approved
2026-11-22). Twelve glyphs on a 24 x 24 sheet: seven tools heads (fountain, ballpoint, fineliner, highlighter, eraser, lasso,
caret) leaning on 45 degrees, tip at lower left, each centred by its visible bounds, and five bar actions (undo, redo, fit,
more, grip). Seeded per glyph, so the art is identical on every run. The order of the random draws is part of the art: do not
reorder loops.

A strand is kept as its centreline (x, y) and a half-width at every point; its outline is the left side going forward then the
right side coming back. The apps rebuild the outline exactly that way (tangent at a point = direction from the previous to the
next point, clamped at the ends), so a partial strand for the draw-on is a prefix of its points.

Data encoding (both apps): one base64 string per glyph, little-endian. u16 strand count; per strand u16 point count, u16
centreline length (1/256 unit), then per point three i16: x, y (1/1024 unit) and half-width (1/1024 unit). Centring and the
optical scale are baked in. The generator also writes each glyph's point count and a checksum (the sum of every i16 triple
value) next to the string; the apps' tests assert both after decoding.

Run: python3 code/shared/brand/generate-pens.py
Writes: pens/*.svg and pens/sheet-{light,dark}.png (sources and a preview), the phone's PenArtData.kt and the Mac's
PenArtData.swift.
"""
import base64, math, os, random, struct
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
PENS = os.path.join(HERE, "pens")
KOTLIN = os.path.join(ROOT, "code/android/app/src/main/java/dev/syncr/prototype/ui/PenArtData.kt")
SWIFT = os.path.join(ROOT, "code/mac/Sources/SyncrMac/PenArtData.swift")

SHEET = 24
BONE, INK, NIGHT, BONE_INK = "#FBFBFA", "#1E1E1E", "#141414", "#F0EFEA"
PARAMS = dict(weight=0.62, more=1, spread=1.15, reach=1.55)      # the approved look
POS_UNIT, LEN_UNIT = 1024, 256

def lerp(a, b, t): return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
def line(a, b, n=14): return [lerp(a, b, i / n) for i in range(n + 1)]
def cubic(p0, c1, c2, p1, n=22):
    out = []
    for i in range(n + 1):
        t = i / n; u = 1 - t
        out.append(tuple(u**3 * p0[k] + 3*u*u*t * c1[k] + 3*u*t*t * c2[k] + t**3 * p1[k] for k in (0, 1)))
    return out
def arc(cx, cy, rx, ry, a0, a1, tilt=0, n=40):
    out = []
    ct, st = math.cos(math.radians(tilt)), math.sin(math.radians(tilt))
    for i in range(n + 1):
        a = math.radians(a0 + (a1 - a0) * i / n)
        x, y = rx * math.cos(a), ry * math.sin(a)
        out.append((cx + x * ct - y * st, cy + x * st + y * ct))
    return out
def spline(points, closed=False, n=10):
    """Catmull-Rom through the points, densely sampled."""
    P = list(points); m = len(P); out = []
    last = m if closed else m - 1
    for i in range(last):
        g = (lambda k: P[k % m]) if closed else (lambda k: P[min(max(k, 0), m - 1)])
        p0, p1, p2, p3 = g(i - 1), g(i), g(i + 1), g(i + 2)
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        seg = cubic(p1, c1, c2, p2, n)
        out += seg if not out else seg[1:]
    return out
def dashes(pts, dash, gap):
    """Cuts a dense polyline into dash pieces by length."""
    out, cur, acc, on = [], [pts[0]], 0.0, True
    for a, b in zip(pts, pts[1:]):
        acc += math.hypot(b[0] - a[0], b[1] - a[1])
        if on: cur.append(b)
        if acc >= (dash if on else gap):
            if on and len(cur) > 2: out.append(cur)
            on = not on; acc = 0.0; cur = [b]
    if on and len(cur) > 3: out.append(cur)
    return out
def mirror(pts): return [(-x, y) for x, y in pts]

def tangents(pts):
    out = []
    for i in range(len(pts)):
        a, b = pts[max(i - 1, 0)], pts[min(i + 1, len(pts) - 1)]
        d = math.hypot(b[0] - a[0], b[1] - a[1]) or 1
        out.append(((b[0] - a[0]) / d, (b[1] - a[1]) / d))
    return out

def plen(pts): return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:]))

def strand(pts, rng, w, fan, over, closed=False, pinch=0, anchor=False, reach=1.0):
    """One tapered strand along pts: ends slide sideways (the fan), run past the ends (the flick) and the width
    swells then thins. Returns a filled outline."""
    tg = tangents(pts)
    n = len(pts) - 1
    length = plen(pts)
    tight, wide = rng.uniform(-fan, fan) * 0.22, rng.uniform(-fan, fan)       # strands pinch at one end and fan at the other
    j0, j1 = (tight, wide) if pinch == 0 else (wide, tight)
    bow = rng.uniform(-fan, fan) * 0.7 + rng.choice((-1, 1)) * min(0.3, length * 0.02)
    wave, phase = rng.uniform(-fan, fan) * 0.3 * min(1, length / 9), rng.uniform(0, math.pi)
    bow *= min(1, length / 6)                                         # short edges stay crisp
    cap = max(0.5, length * 0.38)
    o0, o1 = min(cap, rng.uniform(*over) * reach), min(cap, rng.uniform(*over) * reach)
    short = 0.25 if reach <= 1 else 0.05                                       # longer strands also start late less often
    if anchor: fan = fan  # the first strand of an edge always spans it, so corners meet
    if not anchor and length > 5 and rng.random() < short: o0 = -rng.uniform(0.08, 0.24) * length   # some strands start late
    if not anchor and length > 5 and rng.random() < short * 0.8: o1 = -rng.uniform(0.08, 0.22) * length   # or stop early
    core = []
    for i, (p, t) in enumerate(zip(pts, tg)):
        s = i / n
        off = j0 + (j1 - j0) * s + bow * math.sin(math.pi * s) + wave * math.sin(2 * math.pi * s + phase)
        core.append((p[0] - t[1] * off, p[1] + t[0] * off))
    def ext(end, t, o, sign):
        return [(end[0] + sign * t[0] * o * k / 3, end[1] + sign * t[1] * o * k / 3) for k in (1, 2, 3)]
    def trim(c, o, front):
        # drop length |o| from one end
        need = -o; acc = 0.0; c = c if front else c[::-1]
        while len(c) > 3 and acc < need:
            acc += math.hypot(c[1][0] - c[0][0], c[1][1] - c[0][1]); c = c[1:]
        return c if front else c[::-1]
    if not closed:
        t0, t1 = tangents(core)[0], tangents(core)[-1]
        core = (ext(core[0], t0, o0, -1)[::-1] + core) if o0 > 0 else trim(core, o0, True)
        core = (core + ext(core[-1], t1, o1, 1)) if o1 > 0 else trim(core, o1, False)
    if rng.random() < 0.5: core = core[::-1]
    tg = tangents(core); m = len(core) - 1
    wmax = rng.uniform(*w)
    head = rng.uniform(0.10, 0.22); tail = rng.uniform(0.35, 0.6)
    half = []
    for i, (p, t) in enumerate(zip(core, tg)):
        s = i / m
        k = min(1, s / head) ** 0.7 * min(1, (1 - s) / tail) ** 0.9
        h = max(0.04, wmax * k) / 2
        half.append(h)
    return core, half

def edge(pts, n=4, w=(0.30, 0.56), fan=0.58, over=(0.3, 1.6), pinch=None):
    return dict(pts=pts, n=n, w=w, fan=fan, over=over, pinch=pinch)
def detail(pts, n=2, w=(0.28, 0.50), fan=0.30, over=(0.0, 0.6), pinch=None):
    return dict(pts=pts, n=n, w=w, fan=fan, over=over, pinch=pinch)
def loop(cx, cy, rx, ry, n=2, tilt=0, w=(0.2, 0.36)):
    return dict(loop=(cx, cy, rx, ry, tilt), n=n, w=w)

def rot(pts, deg, cx, cy):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return [(cx + x * c - y * s, cy + x * s + y * c) for x, y in pts]


# ---- the heads, tip down about (0, 0); placed (angle, cx, cy) afterwards ----
def heads():
    L = lambda a, b: line(a, b)
    fountain = dict(place=(45, 12, 12), parts=[
        edge(spline([(-5.6, -9.5), (-4.75, -7.3), (-4.3, -4.4), (-3.6, 0.4), (-2.35, 3.8), (-1.1, 6.6), (0, 10.6)]), pinch=1, over=(0.3, 1.8)),   # the flanks: a flared wing, a soft belly, then in to the tip
        edge(spline([(5.6, -9.3), (4.5, -7.3), (3.95, -4.6), (3.3, 0.0), (2.2, 3.4), (1.0, 6.4), (0, 10.6)]), pinch=1, over=(0.3, 1.8)),
        edge(spline([(-5.6, -9.5), (-4.6, -8.7), (-2.4, -7.3), (0.2, -7.0), (2.8, -7.4), (5.6, -9.3)]), over=(0.3, 1.5)),     # the hollow heel
        loop(0, -0.3, 1.45, 1.25, n=3),                                               # breather hole
        detail(spline([(0, 0.9), (0.15, 5.4), (0, 10.0)]), over=(0.0, 0.4)),          # slit
    ])
    ballpoint = dict(place=(45, 12.6, 11.4), parts=[
        edge(L((-3.7, -9.6), (-3.7, -2.6))), edge(L((3.7, -9.6), (3.7, -2.6))),      # the section
        detail(L((-3.7, -9.6), (3.7, -9.6)), n=1),                                    # where the barrel is cut
        detail(L((-3.7, -2.6), (3.7, -2.6))),                                         # seam
        edge(L((-3.7, -2.6), (-1.25, 7.2)), over=(0.2, 0.7)), edge(L((3.7, -2.6), (1.25, 7.2)), over=(0.2, 0.7)),   # cone
        detail(L((-2.0, 4.2), (2.0, 4.2)), n=1),                                      # collar
        loop(0, 8.7, 1.5, 1.5, n=3),                                                  # the ball
    ])
    fineliner = dict(place=(45, 12.9, 11.1), parts=[
        edge(L((-3.2, -9.6), (-3.2, -2.2))), edge(L((3.2, -9.6), (3.2, -2.2))),      # the section
        detail(L((-3.2, -9.6), (3.2, -9.6)), n=1),
        detail(L((-3.2, -6.9), (3.2, -6.9))), detail(L((-3.2, -4.9), (3.2, -4.9))),  # grip band
        edge(L((-3.2, -2.2), (-1.15, 3.6)), over=(0.2, 0.6)), edge(L((3.2, -2.2), (1.15, 3.6)), over=(0.2, 0.6)),   # cone
        detail(L((-1.15, 3.6), (1.15, 3.6)), n=1),
        edge(L((-1.0, 3.6), (-0.8, 7.6)), n=2, over=(0.0, 0.4)), edge(L((1.0, 3.6), (0.8, 7.6)), n=2, over=(0.0, 0.4)),   # metal sleeve
        edge(L((-0.8, 7.6), (0, 11.2)), n=2, over=(0.0, 0.6)), edge(L((0.8, 7.6), (0, 11.2)), n=2, over=(0.0, 0.6)),     # the point
    ])
    highlighter = dict(place=(45, 13.4, 10.4), parts=[
        edge(L((-4.6, -9.0), (-4.6, -2.6))), edge(L((4.6, -9.0), (4.6, -2.6))),      # the body
        detail(L((-4.6, -9.0), (4.6, -9.0)), n=1),
        detail(L((-4.6, -2.6), (4.6, -2.6))),                                         # shoulder
        edge(L((-4.6, -2.6), (-3.2, 1.2)), n=2, over=(0.1, 0.5)), edge(L((4.6, -2.6), (3.2, 1.2)), n=2, over=(0.1, 0.5)),
        detail(L((-3.2, 1.2), (3.2, 1.2)), n=1),                                      # the chisel's back edge
        edge(L((-3.2, 1.2), (-3.2, 9.4))), edge(L((3.2, 1.2), (3.2, 3.0)), n=2, over=(0.0, 0.4)),
        edge(L((-3.2, 9.4), (3.2, 3.0))),                                             # the cut, flat on the mark
    ], flat=[edge(L((2.2, 20.4), (11.8, 20.4)), n=5, w=(0.34, 0.62), fan=0.5, over=(0.2, 1.4))])   # the mark it leaves
    eraser = dict(place=(45, 12.2, 10.6), parts=[
        edge(L((-4.3, -7.6), (-4.3, 7.0))), edge(L((4.3, -7.6), (4.3, 7.0))),
        edge(L((-4.3, -7.6), (4.3, -7.6))), edge(L((-4.3, 7.0), (4.3, 7.0))),
        detail(L((-4.3, 0.8), (4.3, 0.8))),                                           # the sleeve's edge
    ], flat=[edge(L((6.4, 19.4), (20.6, 19.4)), n=3, over=(0.2, 1.0))])              # the line it rubs on
    E = lambda pts, **k: dict(dict(pts=pts, n=3, w=(0.30, 0.56), fan=0.34, over=(0.0, 0.6)), **k)
    tail = cubic((7.4, 14.9), (5.6, 16.6), (6.4, 18.3), (8.4, 18.6)) + cubic((8.4, 18.6), (10.2, 18.9), (10.5, 20.6), (9.0, 22.0))[1:]
    # freeform: an uneven dashed outline that does not quite close, like a selection being drawn
    bean = spline([(4.2, 9.4), (7.6, 4.6), (14.2, 3.6), (19.8, 6.6), (20.4, 12.2), (16.2, 15.4), (12.2, 14.6), (9.4, 19.2), (5.0, 17.6), (3.6, 13.2)], closed=True)
    lasso_free = dict(place=None, parts=[], flat=[E(d, n=3, fan=0.34, over=(0.0, 0.6)) for d in dashes(bean[6:], 3.6, 2.0)])
    lasso = lasso_free
    # the bar's actions, upright
    hook = line((4, 9), (14.5, 9)) + arc(14.5, 14.5, 5.5, 5.5, -90, 90, n=24)[1:] + line((14.5, 20), (11, 20))[1:]
    flip = lambda pts: [(24 - x, y) for x, y in pts]
    undo = dict(place=None, parts=[], flat=[E(line((9.2, 14.2), (4, 9)), over=(0.1, 0.7)), E(line((4, 9), (9.2, 3.8)), over=(0.1, 0.7)), E(hook, n=4, fan=0.4)])
    redo = dict(place=None, parts=[], flat=[E(flip(line((9.2, 14.2), (4, 9))), over=(0.1, 0.7)), E(flip(line((4, 9), (9.2, 3.8))), over=(0.1, 0.7)), E(flip(hook), n=4, fan=0.4)])
    corners = []
    for cx, cy, sx, sy in ((4, 4, 1, 1), (20, 4, -1, 1), (20, 20, -1, -1), (4, 20, 1, -1)):
        corners += [E(line((cx, cy + 5 * sy), (cx, cy)), over=(0.1, 0.8)), E(line((cx, cy), (cx + 5 * sx, cy)), over=(0.1, 0.8))]
    fit = dict(place=None, parts=[], flat=corners)
    dot = lambda x, y, r: dict(loop=(x, y, r, r, 0), n=5, w=(0.5, 0.85), wild=0.12, dot=True)
    more = dict(place=None, parts=[], flat=[dot(6, 12, 0.95), dot(12, 12, 0.95), dot(18, 12, 0.95)])
    grip = dict(place=None, parts=[], flat=[dot(x, y, 0.7) for y in (7, 12, 17) for x in (9, 15)])
    caret = dict(place=(14, 12, 12), parts=[
        edge(L((0, -8.6), (0, 8.6)), over=(0.0, 0.5)),
        edge(L((-3.3, -8.6), (3.3, -8.6)), n=2, over=(0.1, 0.8)), edge(L((-3.3, 8.6), (3.3, 8.6)), n=2, over=(0.1, 0.8)),
    ])
    return dict(fountain=fountain, ballpoint=ballpoint, fineliner=fineliner, highlighter=highlighter, eraser=eraser, lasso=lasso, caret=caret,
                undo=undo, redo=redo, fit=fit, more=more, grip=grip)


ORDER = ["fountain", "ballpoint", "fineliner", "highlighter", "eraser", "lasso", "caret"]
ACTIONS = ["undo", "redo", "fit", "more", "grip"]
GLYPHS = ORDER + ACTIONS

OPTICAL = {"highlighter": 0.9, "eraser": 0.92}
CENTRE = {"fountain", "ballpoint", "fineliner", "highlighter", "eraser", "lasso", "caret"}


def outline(core, half):
    """The filled outline of a strand: left side going forward, right side coming back."""
    tg = tangents(core)
    left = [(p[0] - t[1] * h, p[1] + t[0] * h) for p, t, h in zip(core, tg, half)]
    right = [(p[0] + t[1] * h, p[1] - t[0] * h) for p, t, h in zip(core, tg, half)]
    return left + right[::-1]


def render(name, weight=1.0, more=0, spread=1.0, reach=1.0):
    """The strands of a glyph on the 24 grid, in drawing order: [(centreline, half-widths)], tools centred by their visual bounds."""
    h = heads()[name]
    rng = random.Random(sum(ord(c) * (i + 3) for i, c in enumerate(name)) * 7919)
    strands = []
    def do(part, place):
        w = (part["w"][0] * weight, part["w"][1] * weight)
        if "loop" in part:
            cx, cy, rx, ry, tilt = part["loop"]
            wild = part.get("wild", 1.0)
            for _ in range(part["n"] + (1 if more else 0)):
                k = 1 + rng.uniform(-0.18, 0.16) * wild
                if part.get("dot"): k = rng.uniform(0.2, 1.0)        # passes at every radius, so the scribble fills in
                a0 = rng.uniform(0, 360); turn = rng.uniform(1.05, 1.35)
                pts = arc(cx + rng.uniform(-.28, .28) * spread * wild, cy + rng.uniform(-.28, .28) * spread * wild, rx * k, ry * (1 + rng.uniform(-0.22, 0.1) * wild), a0, a0 + 360 * turn, tilt + rng.uniform(-25, 25) * min(1, wild * 2) * (0.3 if rx > 4 else 1), n=64)
                if place: pts = rot(pts, place[0], 0, 0); pts = [(x + place[1], y + place[2]) for x, y in pts]
                strands.append(strand(pts, rng, w, 0.0, (0, 0), closed=True))
        else:
            pts = part["pts"]
            if place: pts = rot(pts, place[0], 0, 0); pts = [(x + place[1], y + place[2]) for x, y in pts]
            pinch = part.get("pinch"); pinch = rng.randrange(2) if pinch is None else pinch
            fan = part["fan"] * (0.35 if part["n"] == 1 else spread)      # a lone strand stays on its line
            for i in range(part["n"] + (more if part["n"] > 1 else 0)):
                strands.append(strand(pts, rng, w, fan * (0.3 if i == 0 else 1.0), part["over"], pinch=pinch, anchor=i == 0, reach=reach))
    for part in h["parts"]: do(part, h["place"])
    for part in h.get("flat", []): do(part, None)
    dx = dy = 0.0
    if name in CENTRE:
        polys = [outline(c, hw) for c, hw in strands]
        xs = [x for p in polys for x, _ in p]; ys = [y for p in polys for _, y in p]
        dx, dy = 12 - (min(xs) + max(xs)) / 2, 12 - (min(ys) + max(ys)) / 2
    k = OPTICAL.get(name, 1.0)                                      # the two with a mark under them are drawn a little smaller, to stay inside the disc
    place = lambda p: (12 + (p[0] + dx - 12) * k, 12 + (p[1] + dy - 12) * k)
    return [([place(p) for p in c], [x * k for x in hw]) for c, hw in strands]


def art(name):
    return render(name, **PARAMS)


# ---- encoding ----
def q(v, unit): return int(round(v * unit))


def encode(strands):
    """(base64 text, point count, checksum) for one glyph's strands."""
    blob = struct.pack("<H", len(strands))
    total = checksum = 0
    for core, half in strands:
        blob += struct.pack("<HH", len(core), q(plen(core), LEN_UNIT))
        for (x, y), h in zip(core, half):
            t = (q(x, POS_UNIT), q(y, POS_UNIT), q(h, POS_UNIT))
            blob += struct.pack("<hhh", *t)
            checksum += sum(t)
        total += len(core)
    return base64.b64encode(blob).decode(), total, checksum


# ---- writers ----
def path_d(strands):
    return "".join("M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in outline(c, hw)) + "Z" for c, hw in strands)


def svg(strands, dark=False):
    paper, ink = (NIGHT, BONE_INK) if dark else (BONE, INK)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{SHEET * 8}" height="{SHEET * 8}" viewBox="0 0 {SHEET} {SHEET}">'
            f'<rect width="{SHEET}" height="{SHEET}" fill="{paper}"/><path d="{path_d(strands)}" fill="{ink}"/></svg>\n')


def write_kotlin(enc):
    rows = []
    for n in GLYPHS:
        b64, pts, chk = enc[n]
        assert len(b64) < 60000, f"{n} is too long for one JVM string constant"
        rows.append(f'    private const val {n.upper()}_DATA = "{b64}"')
    arts = "\n".join(f'    val {n.upper()} = PenArt("{n}", {n.upper()}_DATA, {enc[n][1]}, {enc[n][2]})' for n in GLYPHS)
    open(KOTLIN, "w").write(
        "package dev.syncr.prototype.ui\n\n"
        "// Generated by code/shared/brand/generate-pens.py; do not edit.\n"
        f"// Feathered tool heads and bar actions on a {SHEET} x {SHEET} sheet. One base64 string per glyph (format in the generator's header),\n"
        "// decoded once and lazily; a plain float-array literal this size would overrun the JVM's method limit.\n\n"
        "/** One strand: its centreline length and, per point, x, y and half-width on the sheet ([xyh] holds the triples). */\n"
        "class PenStrand(val length: Float, val xyh: FloatArray) {\n    val count: Int get() = xyh.size / 3\n}\n\n"
        "/** A glyph: its strands in drawing order, decoded on first use. [points] and [checksum] come from the generator, for the tests. */\n"
        "class PenArt(val name: String, private val data: String, val points: Int, val checksum: Int) {\n"
        "    val strands: List<PenStrand> by lazy(LazyThreadSafetyMode.PUBLICATION) { PenArtCodec.decode(data) }\n}\n\n"
        f"object PenArtData {{\n    const val WIDTH = {SHEET}f\n    const val HEIGHT = {SHEET}f\n\n"
        + "\n".join(rows) + "\n\n" + arts + "\n}\n")


def write_swift(enc):
    rows = []
    for n in GLYPHS:
        b64, pts, chk = enc[n]
        rows.append(f'    static let {n} = Art(name: "{n}", points: {pts}, checksum: {chk}, data: "{b64}")')
    open(SWIFT, "w").write(
        "// Generated by code/shared/brand/generate-pens.py; do not edit.\n"
        f"// Feathered tool heads and bar actions on a {SHEET} x {SHEET} sheet. One base64 string per glyph (format in the generator's header),\n"
        "// decoded when first used.\n\n"
        "import Foundation\n\n"
        "enum PenArtData {\n"
        f"    static let width = {float(SHEET)}, height = {float(SHEET)}\n\n"
        "    /// One strand: its centreline length and, per point, x, y and half-width on the sheet ([xyh] holds the triples).\n"
        "    struct Strand { let length: Double; let xyh: [Double]; var count: Int { xyh.count / 3 } }\n"
        "    /// A glyph: its strands in drawing order. `points` and `checksum` come from the generator, for the checks.\n"
        "    struct Art {\n"
        "        let name: String; let points: Int; let checksum: Int; let strands: [Strand]\n"
        "        init(name: String, points: Int, checksum: Int, data: String) {\n"
        "            self.name = name; self.points = points; self.checksum = checksum; strands = PenArtData.decode(data)\n"
        "        }\n    }\n\n" + "\n".join(rows) + "\n}\n")


def sheet(art_by_name, dark, name):
    cell, pad, ss, cols = 6, 2, 4, 6
    paper, ink = (NIGHT, BONE_INK) if dark else (BONE, INK)
    k = cell * ss
    rows = math.ceil(len(GLYPHS) / cols)
    im = Image.new("RGB", (((SHEET + pad) * cols + pad) * k, ((SHEET + pad) * rows + pad) * k), paper)
    d = ImageDraw.Draw(im)
    for n, nm in enumerate(GLYPHS):
        ox, oy = (pad + (n % cols) * (SHEET + pad)) * k, (pad + (n // cols) * (SHEET + pad)) * k
        for c, hw in art_by_name[nm]:
            d.polygon([(ox + x * k, oy + y * k) for x, y in outline(c, hw)], fill=ink)
    im.resize((im.width // ss, im.height // ss), Image.LANCZOS).save(os.path.join(PENS, name))


def main():
    os.makedirs(PENS, exist_ok=True)
    arts = {n: art(n) for n in GLYPHS}
    enc = {n: encode(arts[n]) for n in GLYPHS}
    for n in GLYPHS:
        open(os.path.join(PENS, f"{n}.svg"), "w").write(svg(arts[n]))
    write_kotlin(enc)
    write_swift(enc)
    sheet(arts, False, "sheet-light.png")
    sheet(arts, True, "sheet-dark.png")
    print("glyphs:", ", ".join(f"{n} {len(arts[n])} strands {enc[n][1]} points {len(enc[n][0])} chars" for n in GLYPHS))


if __name__ == "__main__":
    main()
