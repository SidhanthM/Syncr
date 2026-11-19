import AppKit
import Foundation
import SyncrCore

/// The pen heads' path table is well formed, the heads are centred monochrome line art, and the status-bar glyph really paints it.
@MainActor func penGlyphChecks() throws {
    let all = [PenArtData.fountain, PenArtData.ballpoint, PenArtData.fineliner, PenArtData.highlighter, PenArtData.eraser, PenArtData.lasso, PenArtData.caret]
    for art in all {
        try check(!art.strokes.isEmpty, "\(art.name) has strokes")
        for s in art.strokes {
            try check(s.curve.count >= 8 && (s.curve.count - 2) % 6 == 0, "\(art.name) curve is a start and whole cubics")
            try check(s.length > 0.5 && s.width == 2.0, "\(art.name) stroke length and the one constant weight")
            try check(s.curve.enumerated().allSatisfy { i, v in v > -0.5 && v < (i % 2 == 0 ? PenArtData.width : PenArtData.height) + 0.5 },
                      "\(art.name) stays on the sheet")
        }
        // Centred by the visual bounds (strokes included), within half a unit, on both axes.
        var x0 = Double.infinity, y0 = Double.infinity, x1 = -Double.infinity, y1 = -Double.infinity
        for s in art.strokes {
            let c = s.curve
            var px = c[0], py = c[1], i = 2
            while i + 5 < c.count {
                for k in 0...32 {
                    let t = Double(k) / 32, u = 1 - t
                    let x = u*u*u*px + 3*u*u*t*c[i] + 3*u*t*t*c[i+2] + t*t*t*c[i+4]
                    let y = u*u*u*py + 3*u*u*t*c[i+1] + 3*u*t*t*c[i+3] + t*t*t*c[i+5]
                    x0 = min(x0, x - s.width / 2); x1 = max(x1, x + s.width / 2); y0 = min(y0, y - s.width / 2); y1 = max(y1, y + s.width / 2)
                }
                px = c[i+4]; py = c[i+5]; i += 6
            }
        }
        try check(abs((x0 + x1) / 2 - PenArtData.width / 2) <= 0.5, "\(art.name) is centred horizontally")
        try check(abs((y0 + y1) / 2 - PenArtData.height / 2) <= 0.5, "\(art.name) is centred vertically")
    }

    // The brush picks its head: the stored look or the highlighter's chisel.
    let plain = Brush(family: .pen, size: .medium, colour: .ink)
    try check(PenGlyphView.art(for: plain).name == "fountain", "a pen with no look is the fountain nib")
    try check(PenGlyphView.art(for: Brush(family: .pen, size: .medium, colour: .ink, look: .ballpoint, customInk: nil, feel: nil)).name == "ballpoint", "ballpoint look")
    try check(PenGlyphView.art(for: Brush(family: .pen, size: .medium, colour: .ink, look: .fineliner, customInk: nil, feel: nil)).name == "fineliner", "fineliner look")
    try check(PenGlyphView.art(for: Brush(family: .highlighter, size: .large, colour: .orange)).name == "highlighter", "highlighter head")

    let view = PenGlyphView(height: 28)
    view.frame = NSRect(origin: .zero, size: view.intrinsicContentSize)
    try check(view.isHidden, "no head, no glyph")
    view.show(PenArtData.highlighter, spoken: "Highlighter")
    try check(!view.isHidden, "a head shows the glyph")
    guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { throw CheckFailure(description: "no bitmap") }
    view.cacheDisplay(in: view.bounds, to: rep)
    var painted = 0
    for y in 0..<rep.pixelsHigh { for x in 0..<rep.pixelsWide {
        guard let c = rep.colorAt(x: x, y: y)?.usingColorSpace(.sRGB), c.alphaComponent > 0.5 else { continue }
        painted += 1
    } }
    try check(painted > 80, "the glyph paints the line art (\(painted) pixels)")
    view.show(nil)
    try check(view.isHidden, "hiding the head hides the glyph")
}
