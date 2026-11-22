import AppKit
import Foundation
import SyncrCore

/// The pen heads' strand table is well formed and decodes to what the generator wrote, the heads are centred, and the status-bar
/// glyph really paints them filled.
@MainActor func penGlyphChecks() throws {
    let tools = [PenArtData.fountain, PenArtData.ballpoint, PenArtData.fineliner, PenArtData.highlighter, PenArtData.eraser, PenArtData.lasso, PenArtData.caret]
    let all = tools + [PenArtData.undo, PenArtData.redo, PenArtData.fit, PenArtData.more, PenArtData.grip]
    try check(PenArtData.width == PenArtData.height, "the sheet is square")
    for art in all {
        try check(!art.strands.isEmpty, "\(art.name) has strands")
        try check(art.strands.reduce(0) { $0 + $1.count } == art.points, "\(art.name) decodes to the generator's point count (\(art.points))")
        try check(PenArtData.checksum(of: art) == art.checksum, "\(art.name) decodes to the generator's checksum")
        for s in art.strands {
            try check(s.xyh.count % 3 == 0 && s.count >= 4 && s.length > 0.5, "\(art.name) strand is whole triples with a length")
            let outline = PenGlyphView.outline(s, scale: 1)
            try check(outline.count == s.count * 2, "\(art.name) strand outline is left then right")
            try check(outline.allSatisfy { $0.x >= 0 && $0.x <= PenArtData.width && $0.y >= 0 && $0.y <= PenArtData.height }, "\(art.name) stays on the sheet")
        }
    }
    // The tools are centred by their visible bounds, within a quarter unit, on both axes.
    for art in tools {
        let pts = art.strands.flatMap { PenGlyphView.outline($0, scale: 1) }
        let x0 = pts.map(\.x).min()!, x1 = pts.map(\.x).max()!, y0 = pts.map(\.y).min()!, y1 = pts.map(\.y).max()!
        try check(abs((x0 + x1) / 2 - PenArtData.width / 2) <= 0.25, "\(art.name) is centred horizontally")
        try check(abs((y0 + y1) / 2 - PenArtData.height / 2) <= 0.25, "\(art.name) is centred vertically")
    }
    // The hairline floor: at 16 pt the widest strand is lifted to about 0.9 pt, and never thinned.
    let k = 16 / CGFloat(PenArtData.height)
    for art in all {
        let widen = PenGlyphView.hairlineWiden(art, scale: k)
        let widest = art.strands.flatMap { s in stride(from: 2, to: s.xyh.count, by: 3).map { s.xyh[$0] } }.max() ?? 0
        try check(widen >= 1 && (widen == 1 || abs(CGFloat(widest) * 2 * k * widen - 0.9) < 0.001), "\(art.name) hairline floor")
    }

    // The brush picks its head: the stored look or the highlighter's chisel.
    let plain = Brush(family: .pen, size: .medium, colour: .ink)
    try check(PenGlyphView.art(for: plain).name == "fountain", "a pen with no look is the fountain nib")
    try check(PenGlyphView.art(for: Brush(family: .pen, size: .medium, colour: .ink, look: .ballpoint, customInk: nil, feel: nil)).name == "ballpoint", "ballpoint look")
    try check(PenGlyphView.art(for: Brush(family: .pen, size: .medium, colour: .ink, look: .fineliner, customInk: nil, feel: nil)).name == "fineliner", "fineliner look")
    try check(PenGlyphView.art(for: Brush(family: .highlighter, size: .large, colour: .orange)).name == "highlighter", "highlighter head")

    let view = PenGlyphView(height: 16)
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
    try check(painted > 20, "the glyph paints the filled strands (\(painted) pixels)")
    view.show(nil)
    try check(view.isHidden, "hiding the head hides the glyph")
}
