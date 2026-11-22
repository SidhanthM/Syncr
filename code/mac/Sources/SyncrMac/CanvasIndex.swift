import Foundation
import CoreGraphics

/// A uniform grid over stroke bounds (xnotes: 512-unit cells; an item that would fill more than 64 cells goes in an
/// oversized list instead). `query` returns candidate indices for a rect, ordered, without duplicates, so draw order
/// (and the highlighter's stencil references) stay what they were. The renderer measures it against its linear bounds
/// test; see the checks for the numbers.
struct CanvasIndex {
    static let cell: CGFloat = 512
    static let oversizedCells = 64
    private var cells: [Int64: [Int32]] = [:]
    private var oversized: [Int32] = []
    private(set) var count = 0

    private static func key(_ x: Int, _ y: Int) -> Int64 { (Int64(x) << 32) ^ Int64(UInt32(truncatingIfNeeded: y)) }
    private static func span(_ r: CGRect) -> (x0: Int, x1: Int, y0: Int, y1: Int) {
        (Int(floor(r.minX / cell)), Int(floor(r.maxX / cell)), Int(floor(r.minY / cell)), Int(floor(r.maxY / cell)))
    }

    init(_ bounds: [CGRect] = []) { for b in bounds { append(b) } }

    mutating func append(_ bounds: CGRect) {
        let i = Int32(count); count += 1
        guard bounds.minX.isFinite, bounds.maxX.isFinite, bounds.minY.isFinite, bounds.maxY.isFinite else { oversized.append(i); return }
        let s = Self.span(bounds)
        if (s.x1 - s.x0 + 1) * (s.y1 - s.y0 + 1) > Self.oversizedCells { oversized.append(i); return }
        for x in s.x0...s.x1 { for y in s.y0...s.y1 { cells[Self.key(x, y), default: []].append(i) } }
    }

    /// Indices of every item whose cells touch `rect`, ascending.
    func query(_ rect: CGRect) -> [Int32] {
        let s = Self.span(rect)
        var seen = Set<Int32>(oversized)
        if (s.x1 - s.x0 + 1) * (s.y1 - s.y0 + 1) > cells.count {
            for (k, list) in cells {   // a view wider than the content: walk the occupied cells instead
                let x = Int(k >> 32), y = Int(Int32(truncatingIfNeeded: k))
                if x >= s.x0 && x <= s.x1 && y >= s.y0 && y <= s.y1 { seen.formUnion(list) }
            }
        } else {
            for x in s.x0...s.x1 { for y in s.y0...s.y1 { if let list = cells[Self.key(x, y)] { seen.formUnion(list) } } }
        }
        return seen.sorted()
    }
}
