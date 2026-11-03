import AppKit

/// Window colours and type (rework-v1 §T). Colours come from `ThemeTokens` (code/shared/mac-theme.json)
/// and follow the system appearance. Colour means state only: thinking, connected, needs you.
@MainActor enum Theme {
    private static func dynamic(_ argb: @escaping @Sendable (Bool) -> UInt32) -> NSColor {
        NSColor(name: nil) { appearance in
            let dark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            return NSColor(argb: argb(dark))
        }
    }
    /// The note's background.
    static let page = dynamic(ThemeTokens.page)
    /// Right pane, status bar, title bar.
    static let panel = dynamic(ThemeTokens.panel)
    /// Ribbon, file tree, tab strip.
    static let well = dynamic(ThemeTokens.well)
    /// The selected row in the file tree.
    static let raise = dynamic(ThemeTokens.raise)
    /// Your text and handwriting, active items.
    static let ink = dynamic(ThemeTokens.ink)
    static let soft = dynamic(ThemeTokens.soft)
    /// Labels, hints, inactive controls.
    static let muted = dynamic(ThemeTokens.muted)
    static let hairline = dynamic { ThemeTokens.ink($0) & 0x00ffffff | 0x17000000 }
    static let hairlineStrong = dynamic { ThemeTokens.ink($0) & 0x00ffffff | 0x29000000 }
    /// Picture fills and the companion's thinner pen share the existing quiet palette.
    static let wash = dynamic { ThemeTokens.ink($0) & 0x00ffffff | 0x12000000 }
    static let voice = dynamic(ThemeTokens.soft)
    static let hover = dynamic { ThemeTokens.ink($0) & 0x00ffffff | 0x0f000000 }
    /// The companion is working, and its mode mark.
    static let thinking = dynamic(ThemeTokens.thinking)
    static let connected = dynamic(ThemeTokens.connected)
    /// Errors, a pairing code wanted.
    static let needsYou = dynamic(ThemeTokens.needsYou)

    static let font = NSFont.systemFont(ofSize: 12.5)
    static let smallFont = NSFont.systemFont(ofSize: 11.5)
    /// The companion's voice: the system serif (New York).
    static func serif(_ size: CGFloat, weight: NSFont.Weight = .regular) -> NSFont {
        let base = NSFont.systemFont(ofSize: size, weight: weight)
        return base.fontDescriptor.withDesign(.serif).flatMap { NSFont(descriptor: $0, size: size) } ?? base
    }
    /// Group labels in the tree and the right pane: small, uppercase, tracked.
    static func groupLabel(_ text: String) -> NSAttributedString {
        NSAttributedString(string: text.uppercased(), attributes: [.font: NSFont.systemFont(ofSize: 11, weight: .medium), .kern: 0.9, .foregroundColor: muted])
    }
}

extension NSColor {
    convenience init(argb: UInt32) {
        self.init(srgbRed: CGFloat((argb >> 16) & 255) / 255, green: CGFloat((argb >> 8) & 255) / 255,
                  blue: CGFloat(argb & 255) / 255, alpha: CGFloat(argb >> 24) / 255)
    }
}

/// A view that paints a themed fill and optional hairlines, refreshed on appearance change.
class ThemedView: NSView {
    var fill: NSColor? { didSet { needsDisplay = true } }
    var bottomLine = false
    var topLine = false
    var leftLine = false
    var rightLine = false
    var radius: CGFloat = 0
    override var isFlipped: Bool { true }
    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance(); needsDisplay = true
    }
    override func draw(_ dirtyRect: NSRect) {
        if let fill { fill.setFill(); NSBezierPath(roundedRect: bounds, xRadius: radius, yRadius: radius).fill() }
        Theme.hairline.setFill()
        if bottomLine { NSRect(x: 0, y: bounds.height - 1, width: bounds.width, height: 1).fill() }
        if topLine { NSRect(x: 0, y: 0, width: bounds.width, height: 1).fill() }
        if leftLine { NSRect(x: 0, y: 0, width: 1, height: bounds.height).fill() }
        if rightLine { NSRect(x: bounds.width - 1, y: 0, width: 1, height: bounds.height).fill() }
    }
}
