import Foundation

/// Canvas-space lanes, independent of the viewport: centering must not strand the left margin.
struct CompanionMarginLayout {
    let left: CGFloat
    let right: CGFloat
    let width: CGFloat
    private(set) var bottoms: [CGFloat] = [240, 240]

    init(columnLeft: CGFloat, columnRight: CGFloat, width: CGFloat, gap: CGFloat = 140) {
        self.width = width
        left = columnLeft - gap - width
        right = columnRight + gap
    }

    mutating func place(top: CGFloat, height: CGFloat) -> CGPoint {
        // Prefer the lane that keeps the annotation closest to its passage; ties go left.
        let lane = bottoms[0] <= bottoms[1] ? 0 : 1
        let y = max(top, bottoms[lane] + 24)
        bottoms[lane] = y + height
        return CGPoint(x: lane == 0 ? left : right, y: y)
    }

    static func centeredLeft(columnLeft: CGFloat, columnRight: CGFloat, visibleWidth: CGFloat) -> CGFloat {
        (columnLeft + columnRight - visibleWidth) / 2
    }
}
