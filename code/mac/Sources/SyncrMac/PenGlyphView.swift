import AppKit
import SyncrCore

/// A pen head drawn from the generated path table (`PenArtData`): straight monochrome line art in the theme's ink, centred by its
/// visual bounds. Still, small and quiet; it sits in the status bar's device pill while the pad is on.
@MainActor final class PenGlyphView: NSView {
    private(set) var art: PenArtData.Art?
    private let height: CGFloat

    init(height: CGFloat = 14) {
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

    /// The path of a curve: its first two numbers are the start, then cubic triples.
    static func path(_ curve: [Double], scale k: CGFloat) -> NSBezierPath {
        let p = NSBezierPath()
        guard curve.count >= 2 else { return p }
        p.move(to: NSPoint(x: curve[0] * k, y: curve[1] * k))
        var i = 2
        while i + 5 < curve.count {
            p.curve(to: NSPoint(x: curve[i + 4] * k, y: curve[i + 5] * k),
                    controlPoint1: NSPoint(x: curve[i] * k, y: curve[i + 1] * k),
                    controlPoint2: NSPoint(x: curve[i + 2] * k, y: curve[i + 3] * k))
            i += 6
        }
        return p
    }

    override func draw(_ dirtyRect: NSRect) {
        guard let art else { return }
        NSAppearance.current = effectiveAppearance
        let k = bounds.height / CGFloat(PenArtData.height)
        Theme.ink.setStroke()
        for s in art.strokes {
            let p = Self.path(s.curve, scale: k)
            // The sheet's width is for a 48-unit head; at this size it thins out, so keep a hairline floor.
            p.lineWidth = max(0.9, CGFloat(s.width) * k)
            p.lineCapStyle = .round; p.lineJoinStyle = .round
            p.stroke()
        }
    }
}
