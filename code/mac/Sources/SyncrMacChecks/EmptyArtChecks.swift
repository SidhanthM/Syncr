import AppKit
import Foundation

/// The empty-state drawings and the shared motion table (look-and-ink v1 §5).
@MainActor func emptyArtChecks() throws {
    for (name, art) in [("library", EmptyArtData.library), ("search", EmptyArtData.search), ("notebook", EmptyArtData.notebook)] {
        try check(art.count > 6, "\(name) has strokes")
        let bounds = art.map { emptyArtPath($0.d).boundingBoxOfPath }.reduce(CGRect.null) { $0.union($1) }
        try check(CGRect(x: -5, y: -5, width: 130, height: 100).contains(bounds), "\(name) stays on the sheet")
        try check(art.contains { $0.faint } && art.contains { !$0.faint }, "\(name) has a firm pass and a faint one")
        let lengths = art.map { emptyArtLength(emptyArtPath($0.d)) }
        let plan = emptyArtSchedule(lengths)
        try check(abs(plan.map(\.share).reduce(0, +) - 1) < 1e-9 && plan.first?.start == 0, "\(name) is drawn on over exactly one pass, in order")
        try check(zip(plan, plan.dropFirst()).allSatisfy { abs($0.start + $0.share - $1.start) < 1e-9 }, "\(name) strokes follow each other without a gap")
    }
    // The table: every ordinary duration sits between 150 and 280 ms, on one easing.
    for (name, seconds) in [("quick", Motion.quick), ("standard", Motion.standard), ("slow", Motion.slow), ("drawOn", Motion.drawOn),
                            ("dissolve", Motion.dissolve), ("title", Motion.title), ("menu", Motion.menu), ("hover", Motion.hover), ("textFade", Motion.textFade)] {
        try check(seconds >= 0.15 && seconds <= 0.28, "Motion.\(name) is within 150 to 280 ms")
    }
}
