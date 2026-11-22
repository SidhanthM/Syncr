import AppKit
import SyncrCore

/// A pen head drawn from the generated strands (`PenArtData`): feathered, filled and monochrome in the theme's ink, centred by its
/// visual bounds. Still, small and quiet; it sits in the status bar's device pill while the pad is on.
@MainActor final class PenGlyphView: NSView {
    private(set) var art: PenArtData.Art?
    private let height: CGFloat

    init(height: CGFloat = 16) {
        self.height = height
        super.init(frame: .zero)
        setAccessibilityRole(.image)
        isHidden = true
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    override var isFlipped: Bool { true }
    override var intrinsicContentSize: NSSize { NSSize(width: height * CGFloat(PenArtData.width / PenArtData.height), height: height) }

    /// Shows [art], or hides the glyph when there is no head to show.
    func show(_ art: PenArtData.Art?, spoken: String = "") {
        self.art = art
        isHidden = art == nil
        setAccessibilityLabel(spoken)
        needsDisplay = true
    }

    func show(brush: Brush?) {
        show(brush.map(Self.art), spoken: brush.map { "Last used \(Self.spoken(for: $0))" } ?? "")
    }

    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        needsDisplay = true
    }

    /// The head that stands for a brush: the pen's look, or the highlighter's chisel.
    static func art(for brush: Brush) -> PenArtData.Art {
        if brush.family == .highlighter { return PenArtData.highlighter }
        switch brush.look {
        case .some(.ballpoint): return PenArtData.ballpoint
        case .some(.fineliner): return PenArtData.fineliner
        default: return PenArtData.fountain
        }
    }

    static func spoken(for brush: Brush) -> String {
        if brush.family == .highlighter { return "Highlighter" }
        switch brush.look {
        case .some(.ballpoint): return "Ballpoint pen"
        case .some(.fineliner): return "Fineliner pen"
        default: return "Fountain pen"
        }
    }

    /// The closed outline of a strand as points: left side going forward, right side coming back (tangent at a point = direction
    /// from the previous to the next point, clamped at the ends). [widen] scales every half-width.
    static func outline(_ strand: PenArtData.Strand, scale k: CGFloat, widen: CGFloat = 1) -> [NSPoint] {
        let v = strand.xyh, n = strand.count
        var left: [NSPoint] = [], right: [NSPoint] = []
        left.reserveCapacity(n); right.reserveCapacity(n)
        for i in 0..<n {
            let a = max(i - 1, 0), c = min(i + 1, n - 1)
            var dx = v[c * 3] - v[a * 3], dy = v[c * 3 + 1] - v[a * 3 + 1]
            let d = hypot(dx, dy)
            if d > 0 { dx /= d; dy /= d }
            let x = v[i * 3], y = v[i * 3 + 1], h = v[i * 3 + 2] * Double(widen)
            left.append(NSPoint(x: (x - dy * h) * k, y: (y + dx * h) * k))
            right.append(NSPoint(x: (x + dy * h) * k, y: (y - dx * h) * k))
        }
        return left + right.reversed()
    }

    /// The factor that makes the widest strand of [art] at least [floor] points across at [k] points per sheet unit: the sheet's
    /// strands are thinner than a pixel at this size, so all half-widths widen together and the look stays the same.
    static func hairlineWiden(_ art: PenArtData.Art, scale k: CGFloat, floor: CGFloat = 0.9) -> CGFloat {
        let widest = art.strands.map { s in stride(from: 2, to: s.xyh.count, by: 3).map { s.xyh[$0] }.max() ?? 0 }.max() ?? 0
        let across = CGFloat(widest) * 2 * k
        return across > 0 && across < floor ? floor / across : 1
    }

    override func draw(_ dirtyRect: NSRect) {
        guard let art else { return }
        NSAppearance.current = effectiveAppearance
        let k = bounds.height / CGFloat(PenArtData.height)
        let widen = Self.hairlineWiden(art, scale: k)
        Theme.ink.setFill()
        let all = NSBezierPath()
        for s in art.strands {
            let pts = Self.outline(s, scale: k, widen: widen)
            guard let first = pts.first else { continue }
            all.move(to: first)
            for p in pts.dropFirst() { all.line(to: p) }
            all.close()
        }
        all.windingRule = .nonZero
        all.fill()
    }
}

extension PenArtData {
    /// Decodes the generator's base64 strings (format in `generate-pens.py`). Runs once per glyph, when the glyph is first used.
    static func decode(_ text: String) -> [Strand] {
        guard let data = Data(base64Encoded: text) else { return [] }
        let bytes = [UInt8](data)
        var at = 0
        func u16() -> Int { defer { at += 2 }; return Int(bytes[at]) | Int(bytes[at + 1]) << 8 }
        func i16() -> Int { let u = u16(); return u >= 0x8000 ? u - 0x10000 : u }
        return (0..<u16()).map { _ in
            let n = u16(), length = Double(u16()) / 256
            return Strand(length: length, xyh: (0..<n * 3).map { _ in Double(i16()) / 1024 })
        }
    }

    /// The sum of every quantised x, y and half-width: what the generator wrote next to the string.
    static func checksum(of art: Art) -> Int {
        art.strands.reduce(0) { $0 + $1.xyh.reduce(0) { $0 + Int(($1 * 1024).rounded()) } }
    }
}
