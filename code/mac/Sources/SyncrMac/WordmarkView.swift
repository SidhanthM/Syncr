import AppKit

/// The dithered "Syncr" mark, drawn from the generated dot table (`BrandWordmarkData`): ink dots, the last letter dissolving
/// into one salmon dot. Quiet by design; it follows the appearance through `Theme.ink`.
@MainActor final class WordmarkView: NSView {
    private static let salmon = NSColor(srgbRed: 0xd3 / 255, green: 0x99 / 255, blue: 0x99 / 255, alpha: 1)
    private let height: CGFloat

    init(height: CGFloat = 16) {
        self.height = height
        super.init(frame: .zero)
        setAccessibilityRole(.image)
        setAccessibilityLabel("Syncr")
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    private var pitch: CGFloat { height / CGFloat(BrandWordmarkData.rows) }
    override var intrinsicContentSize: NSSize { NSSize(width: pitch * CGFloat(BrandWordmarkData.columns), height: height) }
    override var isFlipped: Bool { true }

    override func draw(_ dirtyRect: NSRect) {
        let p = pitch
        func dot(_ x: Int, _ y: Int, _ r: Int) {
            let d = p * CGFloat(r) / 50
            NSBezierPath(ovalIn: NSRect(x: (CGFloat(x) + 0.5) * p - d / 2, y: (CGFloat(y) + 0.5) * p - d / 2, width: d, height: d)).fill()
        }
        NSAppearance.current = effectiveAppearance
        Theme.ink.setFill()
        let d = BrandWordmarkData.dots
        for i in stride(from: 0, to: d.count, by: 3) { dot(d[i], d[i + 1], d[i + 2]) }
        Self.salmon.setFill()
        dot(BrandWordmarkData.accent.x, BrandWordmarkData.accent.y, BrandWordmarkData.accent.r)
    }
}
