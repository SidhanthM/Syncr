import Foundation
import SyncrCore

/// The Mac's side of the shared margin layout (core `layoutMargins`, vectors in `code/shared/layout`): this measures
/// the text lines and the cards, the core decides lanes, spacing, anchoring and hit rectangles, the view draws them.
/// Canvas-space lanes, independent of the viewport: centering must not strand the left margin.
struct CompanionMarginLayout {
    /// One measured visual line of the document: its range in UTF-16 units and its top.
    struct Line { let range: NSRange; let top: CGFloat }

    static let gap: CGFloat = 140, laneTop: CGFloat = 240, spacing: CGFloat = 24, defaultTop: CGFloat = 260

    let columnLeft: CGFloat, columnRight: CGFloat, width: CGFloat
    private(set) var items: [MarginItem] = []

    init(columnLeft: CGFloat, columnRight: CGFloat, width: CGFloat) {
        self.columnLeft = columnLeft; self.columnRight = columnRight; self.width = width
    }

    /// A margin note hanging from `anchor`, `height` tall.
    mutating func addNote(_ id: String, anchor: MarginAnchor, height: CGFloat) {
        items.append(MarginItem(id: id, kind: .note, anchor: anchor, height: Float(height)))
    }

    /// A picture with no embed line: placed after every note, in the order added.
    mutating func addPicture(_ id: String, anchor: MarginAnchor, height: CGFloat) {
        items.append(MarginItem(id: id, kind: .picture, anchor: anchor, height: Float(height)))
    }

    /// One core call for everything added. Layout passes happen when content or geometry changes, never per frame.
    func place(lines: [Line]) -> MarginLayout {
        layoutMargins(input: Self.input(policy: .desktop, columnLeft: columnLeft, columnRight: columnRight, width: width,
                                        lines: lines, items: items))
    }

    static func input(policy: MarginPolicy, columnLeft: CGFloat, columnRight: CGFloat, width: CGFloat,
                      lines: [Line], items: [MarginItem]) -> MarginInput {
        MarginInput(policy: policy, columnLeft: Float(columnLeft), columnRight: Float(columnRight), cardWidth: Float(width),
                    gap: Float(gap), laneTop: Float(laneTop), spacing: Float(spacing), defaultTop: Float(defaultTop),
                    lines: lines.map { MarginLine(start: UInt32($0.range.location), end: UInt32(NSMaxRange($0.range)), top: Float($0.top)) },
                    items: items)
    }

    static func centeredLeft(columnLeft: CGFloat, columnRight: CGFloat, visibleWidth: CGFloat) -> CGFloat {
        (columnLeft + columnRight - visibleWidth) / 2
    }
}
