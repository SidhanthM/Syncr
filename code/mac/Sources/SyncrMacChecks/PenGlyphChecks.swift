import AppKit
import Foundation
import SyncrCore

/// The pen heads' path table is well formed, and the status-bar glyph really paints the line art and the pen's ink.
@MainActor func penGlyphChecks() throws {
    let all = [PenArtData.fountain, PenArtData.ballpoint, PenArtData.fineliner, PenArtData.highlighter, PenArtData.eraser, PenArtData.lasso, PenArtData.caret]
    for art in all {
        try check(!art.strokes.isEmpty, "\(art.name) has strokes")
        for s in art.strokes {
            try check(s.curve.count >= 8 && (s.curve.count - 2) % 6 == 0, "\(art.name) curve is a start and whole cubics")
            try check(s.length > 0.5 && s.width > 0.9 && s.width < 2.6, "\(art.name) stroke length and width")
            try check(s.curve.enumerated().allSatisfy { i, v in v > -0.5 && v < (i % 2 == 0 ? PenArtData.width : PenArtData.height) + 0.5 },
                      "\(art.name) stays on the sheet")
        }
        for shape in art.ink { try check((shape.count - 2) % 6 == 0, "\(art.name) ink shape is whole cubics") }
    }
    try check(!PenArtData.fountain.ink.isEmpty && PenArtData.eraser.ink.isEmpty, "ink parts only on the pens")

    // The brush picks its head and tip ink: the stored look, the highlighter's chisel, a custom OKLCH ink as chosen.
    let plain = Brush(family: .pen, size: .medium, colour: .ink)
    try check(PenGlyphView.art(for: plain).name == "fountain", "a pen with no look is the fountain nib")
    try check(PenGlyphView.art(for: Brush(family: .pen, size: .medium, colour: .ink, look: .ballpoint, customInk: nil, feel: nil)).name == "ballpoint", "ballpoint look")
    try check(PenGlyphView.art(for: Brush(family: .pen, size: .medium, colour: .ink, look: .fineliner, customInk: nil, feel: nil)).name == "fineliner", "fineliner look")
    try check(PenGlyphView.art(for: Brush(family: .highlighter, size: .large, colour: .orange)).name == "highlighter", "highlighter head")
    let teal = Brush(family: .pen, size: .medium, colour: .ink, look: nil, customInk: Oklch(l: 0.62, c: 0.12, h: 190), feel: nil)
    let tip = PenGlyphView.inkColour(for: teal, dark: false).usingColorSpace(.sRGB)!
    try check(tip.greenComponent > tip.redComponent + 0.15 && tip.blueComponent > tip.redComponent + 0.15, "a teal ink draws teal")
    let tipDark = PenGlyphView.inkColour(for: teal, dark: true).usingColorSpace(.sRGB)!
    try check(abs(tip.redComponent - tipDark.redComponent) < 0.01, "a custom ink looks the same in both themes")

    let view = PenGlyphView(height: 28)
    view.frame = NSRect(origin: .zero, size: view.intrinsicContentSize)
    try check(view.isHidden, "no head, no glyph")
    let inkColour = NSColor(srgbRed: 0.1, green: 0.4, blue: 0.9, alpha: 1)
    view.show(PenArtData.highlighter, ink: inkColour, spoken: "Highlighter")
    try check(!view.isHidden, "a head shows the glyph")
    guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { throw CheckFailure(description: "no bitmap") }
    view.cacheDisplay(in: view.bounds, to: rep)
    var painted = 0, bluish = 0
    for y in 0..<rep.pixelsHigh { for x in 0..<rep.pixelsWide {
        guard let c = rep.colorAt(x: x, y: y)?.usingColorSpace(.sRGB), c.alphaComponent > 0.5 else { continue }
        painted += 1
        if c.blueComponent > 0.7 && c.redComponent < 0.35 { bluish += 1 }
    } }
    try check(painted > 80, "the glyph paints the line art (\(painted) pixels)")
    try check(bluish > 15, "the chisel face carries the pen's ink (\(bluish) pixels)")
    view.show(nil, ink: nil)
    try check(view.isHidden, "hiding the head hides the glyph")
}
