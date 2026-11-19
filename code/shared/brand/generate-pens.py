#!/usr/bin/env python3
"""Syncr pen heads: straight, monochrome line art for the tools, from one source.

Each head is drawn tip-down on a 32 x 48 sheet as a few strokes of one constant weight (`WIDTH`, the weight of the bar's
line icons), with true straight edges, mirror-symmetric shapes and round caps and joins. The quiet hand touch is in
proportion and detail (the nib's slit and breather hole, the ballpoint's clip), never in jitter. Heads carry no colour:
the ink shows on the bubble's ring and in the tray. Every head is centred on the sheet by its visual bounding box
(strokes included), so a tall narrow pen and a round lasso both sit in the middle of their cell.

Run: python3 code/shared/brand/generate-pens.py
Writes: pens/*.svg and pens/sheet-{light,dark}.png (sources and a preview), Android vector drawables
(pen_head_<name>.xml), the phone's PenArtData.kt and the Mac's PenArtData.swift (strokes as cubic-curve numbers with
their lengths, for the draw-on).
"""
import math, os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
RES = os.path.join(ROOT, "code/android/app/src/main/res")
PENS = os.path.join(HERE, "pens")
KOTLIN = os.path.join(ROOT, "code/android/app/src/main/java/dev/syncr/prototype/ui/PenArtData.kt")
SWIFT = os.path.join(ROOT, "code/mac/Sources/SyncrMac/PenArtData.swift")

W, H = 32, 48
BONE, INK, NIGHT, BONE_INK = "#FBFBFA", "#1E1E1E", "#141414", "#F0EFEA"
WIDTH = 2.0              # sheet units; about 1.4 dp on a 32 dp head, the weight of the bar's line icons
CX, CY = W / 2, H / 2
K = 0.5522847498         # a quarter circle as one cubic


# ---- shapes: each returns cubic segments [(p0, c1, c2, p1), ...] ----
def line(*pts):
    """A polyline of straight edges (each a degenerate cubic, so it stays exactly straight)."""
    out = []
    for a, b in zip(pts, pts[1:]):
        out.append((a, (a[0] + (b[0] - a[0]) / 3, a[1] + (b[1] - a[1]) / 3),
                    (a[0] + 2 * (b[0] - a[0]) / 3, a[1] + 2 * (b[1] - a[1]) / 3), b))
    return out


def ellipse(cx, cy, rx, ry):
    """A full ellipse as four cubics, beginning and ending at the bottom point so the round caps overlap."""
    bottom, right, top, left = (cx, cy + ry), (cx + rx, cy), (cx, cy - ry), (cx - rx, cy)
    return [(bottom, (cx + K * rx, cy + ry), (cx + rx, cy + K * ry), right),
            (right, (cx + rx, cy - K * ry), (cx + K * rx, cy - ry), top),
            (top, (cx - K * rx, cy - ry), (cx - rx, cy - K * ry), left),
            (left, (cx - rx, cy + K * ry), (cx - K * rx, cy + ry), bottom)]


def cubic(a, c1, c2, b):
    return [(a, c1, c2, b)]


def join(*parts):
    return [seg for part in parts for seg in part]


# ---- the drawings, tip down, about the axis x = 16 and centred afterwards ----
def heads():
    fountain = [
        # the nib: flat shoulders, curved shoulders into straight flanks that run to the tip
        join(line((16, 6), (21, 6)), cubic((21, 6), (23, 9), (24, 12), (24, 16)), line((24, 16), (22.4, 24), (16, 42)),
             line((16, 42), (9.6, 24), (8, 16)), cubic((8, 16), (8, 12), (9, 9), (11, 6)), line((11, 6), (16, 6))),
        line((16, 27.4), (16, 38)),                                     # the slit
        ellipse(16, 23, 2.4, 2.4),                                      # the breather hole
    ]
    ballpoint = [
        line((16, 4), (19, 4), (21, 5.5), (21, 28), (17.5, 38.6), (16.7, 43), (15.3, 43), (14.5, 38.6), (11, 28), (11, 5.5), (13, 4), (16, 4)),
        line((11, 28), (21, 28)),                                       # the seam between barrel and cone
        line((14.4, 38.6), (17.6, 38.6)),                               # the collar
        line((21, 8), (24, 8), (24, 19)),                               # the clip
    ]
    fineliner = [
        line((16, 4), (18.5, 4), (19.5, 5), (19.5, 30), (17.5, 37), (16.8, 44), (15.2, 44), (14.5, 37), (12.5, 30), (12.5, 5), (13.5, 4), (16, 4)),
        line((12.5, 30), (19.5, 30)),                                   # the shoulder
        line((12.5, 20), (19.5, 20)), line((12.5, 24), (19.5, 24)),     # the grip band
    ]
    highlighter = [
        line((16, 4), (21, 4), (23, 6), (23, 28), (21, 36), (20, 43), (12, 43), (11, 36), (9, 28), (9, 6), (11, 4), (16, 4)),
        line((9, 10), (23, 10)),                                        # the cap line
        line((9, 28), (23, 28)),                                        # the shoulder
        line((11, 36), (21, 36)),                                       # the chisel's back edge
    ]
    eraser = [
        line((16, 13), (21, 13), (23, 15), (23, 34), (21, 36), (11, 36), (9, 34), (9, 15), (11, 13), (16, 13)),
        line((9, 25), (23, 25)),                                        # the sleeve's edge
    ]
    lasso = [
        ellipse(16, 18, 9.5, 8.5),                                      # the loop
        join(cubic((16, 26.5), (16, 30), (19, 31), (19, 34)), cubic((19, 34), (19, 37), (15.5, 38), (15.5, 41))),   # the tail, a gentle S
    ]
    caret = [
        line((16, 8), (16, 40)),                                        # the stem
        line((11, 8), (21, 8)), line((11, 40), (21, 40)),               # the serifs
    ]
    return {"fountain": fountain, "ballpoint": ballpoint, "fineliner": fineliner, "highlighter": highlighter,
            "eraser": eraser, "lasso": lasso, "caret": caret}


ORDER = ["fountain", "ballpoint", "fineliner", "highlighter", "eraser", "lasso", "caret"]


# ---- maths ----
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


def path_d(segs):
    d = f"M{fmt(segs[0][0][0])},{fmt(segs[0][0][1])}"
    for _, c1, c2, p in segs:
        d += f"C{fmt(c1[0])},{fmt(c1[1])} {fmt(c2[0])},{fmt(c2[1])} {fmt(p[0])},{fmt(p[1])}"
    return d


def bbox(strokes):
    """The visual bounding box of the strokes, half the stroke width included: (x0, y0, x1, y1)."""
    xs, ys = [], []
    for segs in strokes:
        for sg in segs:
            for t in range(33):
                x, y = cubic_at(sg, t / 32)
                xs.append(x); ys.append(y)
    r = WIDTH / 2
    return min(xs) - r, min(ys) - r, max(xs) + r, max(ys) + r


def centred(strokes):
    """Moves a head so the centre of its visual bounding box is the centre of the sheet."""
    x0, y0, x1, y1 = bbox(strokes)
    dx, dy = CX - (x0 + x1) / 2, CY - (y0 + y1) / 2
    return [[tuple((x + dx, y + dy) for x, y in sg) for sg in segs] for segs in strokes]


# Optical size: the eraser is a squat block and reads smaller than the pens and the lasso, so its drawing is scaled up (the stroke weight is not).
OPTICAL = {"eraser": 1.4}


def scaled(strokes, k):
    return [[tuple((CX + (x - CX) * k, CY + (y - CY) * k) for x, y in sg) for sg in segs] for segs in strokes]


def draw_head(name):
    """Returns [(segs, WIDTH, length)], optically sized and centred."""
    return [(segs, WIDTH, length(segs)) for segs in centred(scaled(heads()[name], OPTICAL.get(name, 1.0)))]


# ---- writers ----
def svg(name, strokes, dark=False, bg=True):
    line_colour, paper = (BONE_INK, NIGHT) if dark else (INK, BONE)
    body = "".join(f'<path d="{path_d(s)}" fill="none" stroke="{line_colour}" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round"/>' for s, w, _ in strokes)
    rect = f'<rect width="{W}" height="{H}" fill="{paper}"/>' if bg else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W * 4}" height="{H * 4}" viewBox="0 0 {W} {H}">{rect}{body}</svg>\n'


def vector(path, comment, layers, width_dp=W * 0.75, height_dp=H * 0.75):
    body = "\n".join(layers)
    open(path, "w").write(
        f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} Generated by code/shared/brand/generate-pens.py; do not edit. -->\n'
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="{fmt(width_dp)}dp"\n    android:height="{fmt(height_dp)}dp"\n'
        f'    android:viewportWidth="{W}"\n    android:viewportHeight="{H}">\n{body}\n</vector>\n')


def write_android(art):
    for name, strokes in art.items():
        lines = [f'    <path\n        android:pathData="{path_d(s)}"\n        android:strokeColor="#FF000000"\n        android:strokeWidth="{w}"\n'
                 f'        android:strokeLineCap="round"\n        android:strokeLineJoin="round" />' for s, w, _ in strokes]
        vector(os.path.join(RES, "drawable", f"pen_head_{name}.xml"), f"The {name} head's line art in one colour, tinted in code.", lines)


def nums(segs):
    out = [fmt(segs[0][0][0]) + "f", fmt(segs[0][0][1]) + "f"]
    for _, c1, c2, p in segs:
        out += [fmt(v) + "f" for v in (*c1, *c2, *p)]
    return out


def write_kotlin(art):
    rows = []
    for name in ORDER:
        st = ",\n".join(f'            PenStroke({w}f, {length_:.2f}f, floatArrayOf({", ".join(nums(s))}))' for s, w, length_ in art[name])
        rows.append(f'    val {name.upper()} = PenArt("{name}",\n        listOf(\n{st}))')
    open(KOTLIN, "w").write(
        "package dev.syncr.prototype.ui\n\n"
        "// Generated by code/shared/brand/generate-pens.py; do not edit.\n"
        f"// Pen heads drawn tip-down on a {W} x {H} sheet, centred by their visual bounding box. Each curve is x0, y0 then cubic triples (x1, y1, x2, y2, x, y).\n\n"
        "/** One pen stroke of a head: its [width] on the sheet, its [length] (for the draw-on) and its cubic curves. */\n"
        "class PenStroke(val width: Float, val length: Float, val curve: FloatArray)\n\n"
        "/** A head: its line art in drawing order. Heads are monochrome; the ink shows on the bubble's ring and in the tray. */\n"
        "class PenArt(val name: String, val strokes: List<PenStroke>)\n\n"
        f"object PenArtData {{\n    const val WIDTH = {W}f\n    const val HEIGHT = {H}f\n\n" + "\n\n".join(rows) + "\n}\n")


def write_swift(art):
    def arr(segs):
        flat = [fmt(segs[0][0][0]), fmt(segs[0][0][1])]
        for _, c1, c2, p in segs:
            flat += [fmt(v) for v in (*c1, *c2, *p)]
        return "[" + ", ".join(flat) + "]"
    rows = []
    for name in ORDER:
        st = ",\n".join(f"            .init(width: {w}, length: {length_:.2f}, curve: {arr(s)})" for s, w, length_ in art[name])
        rows.append(f'    static let {name} = Art(name: "{name}",\n        strokes: [\n{st}])')
    open(SWIFT, "w").write(
        "// Generated by code/shared/brand/generate-pens.py; do not edit.\n"
        f"// Pen heads drawn tip-down on a {W} x {H} sheet, centred by their visual bounding box. Each curve is x0, y0 then cubic triples (x1, y1, x2, y2, x, y).\n\n"
        "enum PenArtData {\n"
        f"    static let width = {float(W)}, height = {float(H)}\n\n"
        "    struct Stroke { let width: Double; let length: Double; let curve: [Double] }\n"
        "    struct Art { let name: String; let strokes: [Stroke] }\n\n" + "\n\n".join(rows) + "\n}\n")


def sheet(art, dark, name):
    k, pad = 8, 3
    paper = NIGHT if dark else BONE
    line_colour = BONE_INK if dark else INK
    cols = len(ORDER)
    im = Image.new("RGB", ((W + pad) * cols * k + pad * k, (H + 2 * pad) * k), paper)
    d = ImageDraw.Draw(im)
    for n, nm in enumerate(ORDER):
        ox, oy = pad * k + n * (W + pad) * k, pad * k
        for segs, w, _ in art[nm]:
            pts = [cubic_at(sg, t / 16) for sg in segs for t in range(17)]
            d.line([(ox + x * k, oy + y * k) for x, y in pts], fill=line_colour, width=max(2, round(w * k)), joint="curve")
            r = w * k / 2
            for x, y in (pts[0], pts[-1]):
                d.ellipse([ox + x * k - r, oy + y * k - r, ox + x * k + r, oy + y * k + r], fill=line_colour)
    im.save(os.path.join(PENS, name))


def main():
    os.makedirs(PENS, exist_ok=True)
    art = {n: draw_head(n) for n in ORDER}
    for n in ORDER:
        open(os.path.join(PENS, f"{n}.svg"), "w").write(svg(n, art[n]))
    write_android(art)
    write_kotlin(art)
    write_swift(art)
    sheet(art, False, "sheet-light.png")
    sheet(art, True, "sheet-dark.png")
    print("heads:", ", ".join(f"{n} {len(art[n])} strokes" for n in ORDER))


if __name__ == "__main__":
    main()
