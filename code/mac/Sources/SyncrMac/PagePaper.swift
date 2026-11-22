import Foundation

/// Page paper as triangles in page-local note units: the sheet itself, then its ruling. The renderer draws them under the
/// ink with the page's origin as the shader offset, so one mesh serves every page of the same size and style.
enum PagePaper {
    struct Mesh {
        var positions: [SIMD2<Float>] = []
        var indices: [UInt32] = []
        mutating func quad(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float) {
            let base = UInt32(positions.count)
            positions += [SIMD2(x0, y0), SIMD2(x1, y0), SIMD2(x1, y1), SIMD2(x0, y1)]
            indices += [base, base + 1, base + 2, base, base + 2, base + 3]
        }
        mutating func disc(_ cx: Float, _ cy: Float, _ r: Float) {
            let base = UInt32(positions.count)
            positions.append(SIMD2(cx, cy))
            for i in 0..<8 { let a = Float(i) * .pi / 4; positions.append(SIMD2(cx + r * cos(a), cy + r * sin(a))) }
            for i in 0..<8 { indices += [base, base + 1 + UInt32(i), base + 1 + UInt32((i + 1) % 8)] }
        }
    }

    /// An xnotes ruling is 1.5 px thick and a dot 2.0 px in radius; a px is 1.2902 note units.
    static let ruling: Float = 1.5 * 1.2902
    static let dotRadius: Float = 2.0 * 1.2902

    /// The page rectangle: six indices.
    static func sheet(width: Float, height: Float) -> Mesh {
        var mesh = Mesh(); mesh.quad(0, 0, width, height); return mesh
    }

    /// Lines, dots or grid inside a `width` by `height` page, spaced `spacing` units from the top left. Nil for no ruling.
    static func ruling(pattern: UInt8, width: Float, height: Float, spacing: Float) -> Mesh? {
        guard pattern != 0, spacing >= 1, width > 0, height > 0 else { return nil }
        var mesh = Mesh()
        let half = ruling / 2
        switch pattern {
        case 1, 3:
            var y = spacing
            while y < height { mesh.quad(0, y - half, width, y + half); y += spacing }
            if pattern == 3 { var x = spacing; while x < width { mesh.quad(x - half, 0, x + half, height); x += spacing } }
        default:
            var y = spacing
            while y < height {
                var x = spacing
                while x < width { mesh.disc(x, y, dotRadius); x += spacing }
                y += spacing
            }
        }
        return mesh.indices.isEmpty ? nil : mesh
    }
}
