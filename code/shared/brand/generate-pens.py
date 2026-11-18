#!/usr/bin/env python3
"""Syncr pen heads: hand-drawn line art for the tools, from one source.

Each head is drawn tip-down on a 32 x 48 sheet as a few pen strokes (a smooth chain of cubic curves through
hand-placed points). Every stroke is nudged by a seeded wobble, drawn with its own slightly different width, and
closed shapes stop a little short of or run past where they began, so the art looks drawn and never looks
traced. The nudges are seeded per head, so the output is identical on every run.

A head has `strokes` (the line art, ink-black on bone, in drawing order) and `ink` shapes (the part that carries the
pen's remembered ink: nib tip, ball, needle, chisel face), filled with that pen's ink. Eraser, lasso and caret
have no ink part.

Run: python3 code/shared/brand/generate-pens.py
Writes: pens/*.svg and pens/sheet-{light,dark}.png (sources and a preview), Android vector drawables
(pen_head_<name>.xml outline, pen_head_<name>_ink.xml ink part), the phone's PenArtData.kt and the Mac's
PenArtData.swift (strokes as cubic-curve numbers with their lengths, for the draw-on).
"""
import math, os, random
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
RES = os.path.join(ROOT, "code/android/app/src/main/res")
PENS = os.path.join(HERE, "pens")
KOTLIN = os.path.join(ROOT, "code/android/app/src/main/java/dev/syncr/prototype/ui/PenArtData.kt")
SWIFT = os.path.join(ROOT, "code/mac/Sources/SyncrMac/PenArtData.swift")

W, H = 32, 48
BONE, INK, SALMON, NIGHT, BONE_INK = "#FBFBFA", "#1E1E1E", "#D39999", "#141414", "#F0EFEA"
WOBBLE = 0.32            # sheet units a point may drift
WIDTHS = (1.35, 1.55, 1.75, 1.95)


# ---- the drawings: points (x, y) per stroke, tip down. `c` = closed shape (left slightly open or overlapping) ----
HEADS = {
    # Fountain nib: shoulders, a long taper to the tip, the slit and the breather hole.
    "fountain": dict(
        strokes=[
            [(9.6, 5.2), (8.4, 12), (9.4, 21), (12.4, 32), (15.6, 44.4)],            # left flank
            [(22.4, 5.4), (23.6, 12.2), (22.6, 21), (19.6, 32), (16.4, 44.2)],       # right flank
            [(9.2, 5.0), (13, 4.2), (19, 4.4), (22.8, 5.2)],                          # the top edge
            [(16, 24.2), (16.1, 32), (16, 43)],                                       # the slit
            [(16, 18.6), (17.7, 20.2), (16.6, 22.4), (14.8, 21), (15.6, 18.7)],       # the breather hole
            [(10.4, 12.6), (13.4, 13.6), (16, 17), (18.6, 13.6), (21.6, 12.6)],       # the shoulder swoop
        ],
        ink=[[(12.0, 30.0), (14.4, 29.4), (17.8, 29.7), (20.0, 30.2), (16.4, 44.2), (15.6, 44.3)]]),
    # Ballpoint: a barrel, its clicker and clip, a cone and the ball.
    "ballpoint": dict(
        strokes=[
            [(11.2, 4.4), (11.5, 14), (11.3, 26), (11.8, 29)],                        # barrel, left
            [(20.8, 4.2), (20.5, 14), (20.9, 26), (20.2, 29)],                        # barrel, right
            [(11, 4.6), (14, 3.6), (18, 3.7), (21, 4.4)],                             # the clicker
            [(23.4, 7), (23.9, 12), (23.5, 20), (22.6, 22.4)],                        # the clip
            [(11.8, 29), (13.6, 36), (13.9, 39.4)],                                   # cone, left
            [(20.2, 29), (18.5, 36), (18.1, 39.4)],                                   # cone, right
            [(11.4, 29.2), (16, 30.2), (20.6, 29.1)],                                 # where the cone meets the barrel
            [(13.8, 39.6), (16, 40.2), (18.3, 39.5)],                                 # the collar
            [(16, 41), (17.7, 42.4), (16.9, 44.6), (14.8, 44.3), (14.5, 42), (16.2, 41)],  # the ball's outline
        ],
        ink=[[(16, 41.4), (17.4, 42.6), (16.7, 44.3), (15.1, 44.1), (14.9, 42.3), (16, 41.4)]]),
    # Fineliner: a slim barrel with a grip band and a needle tip.
    "fineliner": dict(
        strokes=[
            [(12.6, 4.6), (12.4, 14), (12.8, 29), (13.6, 33)],                        # barrel, left
            [(19.6, 4.4), (19.8, 14), (19.4, 29), (18.6, 33)],                        # barrel, right
            [(12.4, 4.8), (16, 3.8), (19.9, 4.6)],                                    # the end
            [(12.6, 21.8), (16, 22.6), (19.5, 21.7)],                                 # grip band, top
            [(12.7, 25.4), (16, 26.2), (19.4, 25.3)],                                 # grip band, bottom
            [(13.6, 33), (15, 38), (15.4, 42)],                                       # taper, left
            [(18.6, 33), (17.2, 38), (16.8, 42)],                                     # taper, right
            [(13.3, 33.2), (16, 34), (18.9, 33.1)],                                   # the shoulder
            [(15.3, 42), (16, 46.4), (16.8, 42)],                                     # the needle
        ],
        ink=[[(14.9, 35.6), (17.2, 35.4), (17.0, 40.5), (16, 46.5), (15.1, 40.5)]]),
    # Highlighter: a wide barrel, a cap line and a chisel with its slanted face.
    "highlighter": dict(
        strokes=[
            [(8.8, 4.6), (8.4, 16), (8.9, 29.4)],                                     # barrel, left
            [(23.2, 4.4), (23.6, 16), (23.1, 29.6)],                                  # barrel, right
            [(8.6, 4.8), (12, 3.6), (20, 3.8), (23.4, 4.6)],                          # the end
            [(8.8, 10.4), (16, 11.4), (23.2, 10.2)],                                  # the cap line
            [(8.9, 29.4), (10.2, 33.6), (10.6, 37.2)],                                # taper, left
            [(23.1, 29.6), (21.9, 33.6), (21.6, 37.4)],                               # taper, right
            [(8.7, 29.6), (16, 30.6), (23.3, 29.5)],                                  # the shoulder
            [(10.5, 37.2), (16, 36.4), (21.7, 37.2)],                                 # the chisel's back edge
            [(10.6, 37.4), (11.6, 42), (12.6, 45.4)],                                 # the chisel face, left
            [(21.6, 37.4), (20.6, 42), (19.6, 45.4)],                                 # the chisel face, right
            [(12.4, 45.5), (16, 46.1), (19.8, 45.3)],                                 # the chisel's tip
        ],
        ink=[[(10.8, 37.6), (16, 37), (21.4, 37.6), (19.8, 45.1), (16, 45.7), (12.5, 45.2)]]),
    # Eraser: a block with a paper sleeve and crumbs.
    "eraser": dict(
        strokes=[
            [(8.4, 13.4), (8.1, 24), (8.6, 36.6)],                                    # left
            [(23.6, 13), (23.9, 24), (23.4, 36.8)],                                   # right
            [(8.3, 13.6), (12, 11.8), (20, 12), (23.7, 13.2)],                        # top
            [(8.6, 36.6), (16, 38.2), (23.4, 36.9)],                                  # bottom
            [(8.2, 25), (16, 26.4), (23.8, 24.8)],                                    # the sleeve's edge
            [(12.2, 19.2), (16, 20.2), (19.8, 19)],                                   # a line on the sleeve
            [(11, 42.6), (12.6, 43.4)], [(16, 42.2), (16.4, 44.2)], [(20.2, 42.8), (19.4, 44.4)],   # crumbs
        ], ink=[]),
    # Lasso: a loop that does not quite close, and its tail.
    "lasso": dict(
        strokes=[
            [(10.4, 28.2), (6.4, 22), (7.8, 13), (15, 8.4), (23.6, 10.4), (26.4, 18.4), (22, 26.8), (14.2, 28.6), (9.8, 24.6)],   # the loop, running past its start
            [(11.2, 28), (11.8, 33.4), (16.2, 36), (17.6, 41), (14.6, 44.8)],        # the tail
        ], ink=[]),
    # Text caret: an I-beam, a little uneven.
    "caret": dict(
        strokes=[
            [(16, 7.6), (15.8, 16), (16.2, 26), (16, 40.4)],                          # the stem
            [(10.6, 7.8), (13.4, 7.2), (16, 7.8), (18.8, 7.3), (21.4, 7.9)],          # top serif
            [(10.4, 40.6), (13.4, 40), (16, 40.6), (18.6, 40.1), (21.6, 40.5)],       # bottom serif
        ], ink=[]),
}
ORDER = ["fountain", "ballpoint", "fineliner", "highlighter", "eraser", "lasso", "caret"]


# ---- maths ----
def bezier(points):
    """Catmull-Rom through the points as cubic segments: [(p0, c1, c2, p1), ...]."""
    if len(points) == 2:
        (x0, y0), (x1, y1) = points
        return [((x0, y0), (x0 + (x1 - x0) / 3, y0 + (y1 - y0) / 3), (x0 + 2 * (x1 - x0) / 3, y0 + 2 * (y1 - y0) / 3), (x1, y1))]
    out = []
    for i in range(len(points) - 1):
        p0 = points[max(i - 1, 0)]; p1 = points[i]; p2 = points[i + 1]; p3 = points[min(i + 2, len(points) - 1)]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        out.append((p1, c1, c2, p2))
    return out


def cubic_at(seg, t):
    p0, c1, c2, p1 = seg
    u = 1 - t
    return tuple(u ** 3 * p0[k] + 3 * u * u * t * c1[k] + 3 * u * t * t * c2[k] + t ** 3 * p1[k] for k in (0, 1))


def length(segs, steps=24):
    total = 0.0
    for seg in segs:
        prev = seg[0]
        for s in range(1, steps + 1):
            cur = cubic_at(seg, s / steps)
            total += math.hypot(cur[0] - prev[0], cur[1] - prev[1])
            prev = cur
    return total


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def path_d(segs, close=False):
    d = f"M{fmt(segs[0][0][0])},{fmt(segs[0][0][1])}"
    for _, c1, c2, p in segs:
        d += f"C{fmt(c1[0])},{fmt(c1[1])} {fmt(c2[0])},{fmt(c2[1])} {fmt(p[0])},{fmt(p[1])}"
    return d + ("Z" if close else "")


def draw_head(name):
    """Returns ([(segs, width, length)], [segs]) with the seeded wobble and widths applied."""
    rng = random.Random(sum(ord(ch) * (i + 3) for i, ch in enumerate(name)) * 7919)
    head = HEADS[name]

    def wobble(points):
        return [(x + rng.uniform(-WOBBLE, WOBBLE), y + rng.uniform(-WOBBLE, WOBBLE)) for x, y in points]
    strokes = []
    for pts in head["strokes"]:
        segs = bezier(wobble(pts))
        strokes.append((segs, rng.choice(WIDTHS), length(segs)))
    ink = [bezier(wobble(pts)) for pts in head["ink"]]
    return strokes, ink


# ---- writers ----
def svg(name, strokes, ink, dark=False, bg=True):
    line, paper = (BONE_INK, NIGHT) if dark else (INK, BONE)
    body = "".join(f'<path d="{path_d(s, True)}" fill="{SALMON}" stroke="none"/>' for s in ink)
    body += "".join(f'<path d="{path_d(s)}" fill="none" stroke="{line}" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round"/>' for s, w, _ in strokes)
    rect = f'<rect width="{W}" height="{H}" fill="{paper}"/>' if bg else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W * 4}" height="{H * 4}" viewBox="0 0 {W} {H}">{rect}{body}</svg>\n'


def vector(path, comment, layers, width_dp=W * 0.75, height_dp=H * 0.75):
    body = "\n".join(layers)
    open(path, "w").write(
        f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} Generated by code/shared/brand/generate-pens.py; do not edit. -->\n'
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="{fmt(width_dp)}dp"\n    android:height="{fmt(height_dp)}dp"\n'
        f'    android:viewportWidth="{W}"\n    android:viewportHeight="{H}">\n{body}\n</vector>\n')


def write_android(art):
    for name, (strokes, ink) in art.items():
        lines = [f'    <path\n        android:pathData="{path_d(s)}"\n        android:strokeColor="#FF000000"\n        android:strokeWidth="{w}"\n'
                 f'        android:strokeLineCap="round"\n        android:strokeLineJoin="round" />' for s, w, _ in strokes]
        vector(os.path.join(RES, "drawable", f"pen_head_{name}.xml"), f"The {name} head's line art in one colour, tinted in code.", lines)
        if ink:
            fills = [f'    <path\n        android:pathData="{path_d(s, True)}"\n        android:fillColor="#FF000000" />' for s in ink]
            vector(os.path.join(RES, "drawable", f"pen_head_{name}_ink.xml"), f"The {name} head's ink-bearing part, tinted with the pen's ink in code.", fills)


def write_kotlin(art):
    rows = []
    for name in ORDER:
        strokes, ink = art[name]
        st = ",\n".join(f'            PenStroke({w}f, {length_:.2f}f, floatArrayOf({", ".join(nums(s))}))' for s, w, length_ in strokes)
        ik = ",\n".join(f'            floatArrayOf({", ".join(nums(s))})' for s in ink)
        rows.append(f'    val {name.upper()} = PenArt("{name}",\n        listOf(\n{st}),\n        listOf({chr(10) + ik + chr(10) + "        " if ink else ""}))')
    open(KOTLIN, "w").write(
        "package dev.syncr.prototype.ui\n\n"
        "// Generated by code/shared/brand/generate-pens.py; do not edit.\n"
        f"// Pen heads drawn tip-down on a {W} x {H} sheet. Each curve is x0, y0 then cubic triples (x1, y1, x2, y2, x, y).\n\n"
        "/** One pen stroke of a head: its [width] on the sheet, its [length] (for the draw-on) and its cubic curves. */\n"
        "class PenStroke(val width: Float, val length: Float, val curve: FloatArray)\n\n"
        "/** A head: the line art in drawing order and the shapes that carry the pen's ink (closed curves). */\n"
        "class PenArt(val name: String, val strokes: List<PenStroke>, val ink: List<FloatArray>)\n\n"
        f"object PenArtData {{\n    const val WIDTH = {W}f\n    const val HEIGHT = {H}f\n\n" + "\n\n".join(rows) + "\n}\n")


def nums(segs):
    out = [fmt(segs[0][0][0]) + "f", fmt(segs[0][0][1]) + "f"]
    for _, c1, c2, p in segs:
        out += [fmt(v) + "f" for v in (*c1, *c2, *p)]
    return out


def write_swift(art):
    def arr(segs):
        flat = [fmt(segs[0][0][0]), fmt(segs[0][0][1])]
        for _, c1, c2, p in segs:
            flat += [fmt(v) for v in (*c1, *c2, *p)]
        return "[" + ", ".join(flat) + "]"
    rows = []
    for name in ORDER:
        strokes, ink = art[name]
        st = ",\n".join(f"            .init(width: {w}, length: {length_:.2f}, curve: {arr(s)})" for s, w, length_ in strokes)
        ik = ",\n".join(f"            {arr(s)}" for s in ink)
        rows.append(f'    static let {name} = Art(name: "{name}",\n        strokes: [\n{st}],\n        ink: [{chr(10) + ik + chr(10) + "        " if ink else ""}])')
    open(SWIFT, "w").write(
        "// Generated by code/shared/brand/generate-pens.py; do not edit.\n"
        f"// Pen heads drawn tip-down on a {W} x {H} sheet. Each curve is x0, y0 then cubic triples (x1, y1, x2, y2, x, y).\n\n"
        "enum PenArtData {\n"
        f"    static let width = {W.__float__()}, height = {H.__float__()}\n\n"
        "    struct Stroke { let width: Double; let length: Double; let curve: [Double] }\n"
        "    struct Art { let name: String; let strokes: [Stroke]; let ink: [[Double]] }\n\n" + "\n\n".join(rows) + "\n}\n")


def sheet(art, dark, name):
    k, pad = 8, 3
    paper = NIGHT if dark else BONE
    line = BONE_INK if dark else INK
    cols = len(ORDER)
    im = Image.new("RGB", ((W + pad) * cols * k + pad * k, (H + 2 * pad) * k), paper)
    d = ImageDraw.Draw(im)
    for n, nm in enumerate(ORDER):
        strokes, ink = art[nm]
        ox, oy = pad * k + n * (W + pad) * k, pad * k
        for segs in ink:
            pts = [cubic_at(sg, t / 12) for sg in segs for t in range(12)]
            d.polygon([(ox + x * k, oy + y * k) for x, y in pts], fill=SALMON)
        for segs, w, _ in strokes:
            pts = [cubic_at(sg, t / 16) for sg in segs for t in range(17)]
            d.line([(ox + x * k, oy + y * k) for x, y in pts], fill=line, width=max(2, round(w * k)), joint="curve")
            r = w * k / 2
            for x, y in (pts[0], pts[-1]):
                d.ellipse([ox + x * k - r, oy + y * k - r, ox + x * k + r, oy + y * k + r], fill=line)
    im.save(os.path.join(PENS, name))


def main():
    os.makedirs(PENS, exist_ok=True)
    art = {n: draw_head(n) for n in ORDER}
    for n in ORDER:
        open(os.path.join(PENS, f"{n}.svg"), "w").write(svg(n, *art[n]))
    write_android(art)
    write_kotlin(art)
    write_swift(art)
    sheet(art, False, "sheet-light.png")
    sheet(art, True, "sheet-dark.png")
    print("heads:", ", ".join(f"{n} {len(art[n][0])} strokes" for n in ORDER))


if __name__ == "__main__":
    main()
