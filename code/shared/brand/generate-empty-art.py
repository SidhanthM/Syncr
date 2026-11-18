#!/usr/bin/env python3
"""Syncr empty-state line art: three small hand-drawn illustrations, from one source.

Each drawing is clean geometry on a 120 x 90 sheet that is then "hand drawn" deterministically: points are nudged by
a fraction of a unit, closed shapes miss their start by a hair, and every stroke is drawn twice, once firm and once
a thin, faint, slightly offset second pass, the way a pencil retraces a line. Same seeds, same drawing, every run.
The result is plain cubic Bezier path data (M and C only), so Android and the Mac read it with a few lines each.

Run: python3 code/shared/brand/generate-empty-art.py
Writes: code/shared/brand/empty/empty-art.json, the Android table (ui/EmptyArtData.kt) and the Mac table (EmptyArtData.swift).
"""
import json, math, os, random

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
OUT_JSON = os.path.join(HERE, "empty", "empty-art.json")
OUT_KT = os.path.join(ROOT, "code/android/app/src/main/java/dev/syncr/prototype/ui/EmptyArtData.kt")
OUT_SWIFT = os.path.join(ROOT, "code/mac/Sources/SyncrMac/EmptyArtData.swift")
W, H = 120, 90
FIRM, FAINT = 2.2, 1.1


class Pen:
    def __init__(self, seed):
        self.r = random.Random(seed)
        self.strokes = []

    def wob(self, p, amount=0.8):
        return (p[0] + self.r.uniform(-amount, amount), p[1] + self.r.uniform(-amount, amount))

    def smooth(self, pts, closed=False):
        """Catmull-Rom through the points as cubic Beziers."""
        n = len(pts)
        get = (lambda i: pts[i % n]) if closed else (lambda i: pts[max(0, min(n - 1, i))])
        segs = range(n) if closed else range(n - 1)
        d = "M %.1f %.1f" % pts[0]
        for i in segs:
            p0, p1, p2, p3 = get(i - 1), get(i), get(i + 1), get(i + 2)
            c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
            c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
            d += " C %.1f %.1f %.1f %.1f %.1f %.1f" % (c1 + c2 + p2)
        return d

    def line(self, pts, closed=False, width=FIRM, wobble=0.8):
        firm = [self.wob(p, wobble) for p in pts]
        if closed:  # an imperfect close: the pen comes back a little short of where it began
            firm.append((firm[0][0] + self.r.uniform(0.6, 1.6), firm[0][1] + self.r.uniform(0.6, 1.6)))
            closed = False
        self.strokes.append({"d": self.smooth(firm, closed), "w": width, "faint": False})
        second = [self.wob((p[0] + 0.7, p[1] + 0.6), 1.0) for p in pts]
        self.strokes.append({"d": self.smooth(second, False), "w": FAINT, "faint": True})

    def circle(self, cx, cy, r, width=FIRM, steps=10):
        a0 = self.r.uniform(0, math.tau)
        pts = [(cx + r * math.cos(a0 + math.tau * k / steps) * (1 + self.r.uniform(-0.03, 0.03)),
                cy + r * math.sin(a0 + math.tau * k / steps) * (1 + self.r.uniform(-0.03, 0.03))) for k in range(steps + 1)]
        pts[-1] = (pts[-1][0] + 1.4, pts[-1][1] + 0.8)  # overshoot: the circle does not quite meet itself
        self.strokes.append({"d": self.smooth(pts), "w": width, "faint": False})
        pts2 = [(cx + 0.8 + (r - 1) * math.cos(a0 + 0.3 + math.tau * k / steps), cy + 0.6 + (r - 1) * math.sin(a0 + 0.3 + math.tau * k / steps))
                for k in range(steps)]
        self.strokes.append({"d": self.smooth(pts2), "w": FAINT, "faint": True})

    def dot(self, x, y, r=1.1):
        self.strokes.append({"d": self.smooth([(x - r, y), (x, y - r), (x + r, y), (x, y + r), (x - r + 0.3, y + 0.2)]), "w": 1.6, "faint": False})


def library():
    """An open notebook with a pencil line begun on the right page, and a few stipple dots under it."""
    p = Pen(11)
    p.line([(60, 18), (60, 72)])                                                       # spine
    p.line([(60, 20), (44, 16), (26, 18), (26, 68), (44, 66), (60, 72)], width=FIRM)  # left page
    p.line([(60, 20), (76, 16), (94, 18), (94, 68), (76, 66), (60, 72)], width=FIRM)  # right page
    for y in (30, 40, 50):
        p.line([(33, y), (52, y + 0.6)], width=1.2, wobble=0.5)                         # ruled lines, left
    p.line([(68, 30), (86, 29.4)], width=1.2, wobble=0.5)
    p.line([(68, 40), (80, 40.4)], width=1.2, wobble=0.5)
    for x, y in ((40, 79), (50, 81), (60, 79.5), (71, 81.5), (82, 79)):                  # stipple under the book
        p.dot(x, y, 0.9)
    return p.strokes


def search():
    """A magnifying glass with nothing in it and a small stipple spark."""
    p = Pen(23)
    p.circle(52, 38, 22)
    p.line([(68, 54), (90, 76)], width=3.0, wobble=0.6)                                   # handle
    p.line([(40, 28), (45, 23), (51, 21)], width=1.4, wobble=0.4)                         # glint
    for x, y in ((96, 22), (102, 30), (92, 32)):
        p.dot(x, y, 1.0)
    return p.strokes


def notebook():
    """A closed notebook with its ribbon and an empty label, for a notebook with nothing in it yet."""
    p = Pen(37)
    p.line([(34, 12), (86, 12), (88, 76), (36, 78)], closed=True)                        # cover
    p.line([(44, 13), (45, 77)], width=1.6, wobble=0.5)                                  # spine edge
    p.line([(56, 26), (78, 26), (78, 38), (56, 38)], closed=True, width=1.4, wobble=0.5)  # label
    p.line([(70, 76), (70, 86), (74, 82), (78, 86), (78, 76)], width=1.6, wobble=0.5)     # ribbon
    for x, y in ((57, 54), (63, 56), (69, 54), (75, 56)):
        p.dot(x, y, 0.9)
    return p.strokes


ART = {"library": library(), "search": search(), "notebook": notebook()}


def kotlin():
    s = ["// Generated by code/shared/brand/generate-empty-art.py. Do not edit by hand.",
         "package dev.syncr.prototype.ui", "",
         "/** One stroke of an empty-state drawing: cubic path data (M and C only) on a 120 x 90 sheet. */",
         "class ArtStroke(val d: String, val width: Float, val faint: Boolean)", "",
         "object EmptyArtData {", "    const val WIDTH = %d" % W + "f", "    const val HEIGHT = %d" % H + "f"]
    for name, strokes in ART.items():
        s.append("    val %s = listOf(" % name)
        s += ['        ArtStroke("%s", %sf, %s),' % (k["d"], k["w"], str(k["faint"]).lower()) for k in strokes]
        s.append("    )")
    s.append("}")
    return "\n".join(s) + "\n"


def swift():
    s = ["// Generated by code/shared/brand/generate-empty-art.py. Do not edit by hand.",
         "import CoreGraphics", "",
         "/// One stroke of an empty-state drawing: cubic path data (M and C only) on a 120 x 90 sheet.",
         "struct ArtStroke { let d: String; let width: CGFloat; let faint: Bool }", "",
         "enum EmptyArtData {", "    static let size = CGSize(width: %d, height: %d)" % (W, H)]
    for name, strokes in ART.items():
        s.append("    static let %s: [ArtStroke] = [" % name)
        s += ['        ArtStroke(d: "%s", width: %s, faint: %s),' % (k["d"], k["w"], str(k["faint"]).lower()) for k in strokes]
        s.append("    ]")
    s.append("}")
    return "\n".join(s) + "\n"


if __name__ == "__main__":
    os.makedirs(os.path.dirname(OUT_JSON), exist_ok=True)
    with open(OUT_JSON, "w") as f:
        json.dump({"width": W, "height": H, "art": ART}, f, indent=1)
    for path, text in ((OUT_KT, kotlin()), (OUT_SWIFT, swift())):
        with open(path, "w") as f:
            f.write(text)
    print("wrote", OUT_JSON, OUT_KT, OUT_SWIFT)
