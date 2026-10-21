import AppKit

/// Window chrome colours from the shared palette; they follow the system appearance.
@MainActor enum Theme {
    private static func dynamic(_ argb: @escaping @Sendable (Bool) -> UInt32) -> NSColor {
        NSColor(name: nil) { appearance in
            let dark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            return NSColor(argb: argb(dark))
        }
    }
    static let desk = dynamic(NotePalette.desk)
    static let paper = dynamic(NotePalette.paper)
    static let ink = dynamic(NotePalette.ink)
    static let accent = dynamic(NotePalette.blue)
    static let muted = dynamic { NotePalette.ink($0) & 0x00ffffff | 0x99000000 }
    static let hairline = dynamic { NotePalette.ink($0) & 0x00ffffff | 0x26000000 }
    static let hover = dynamic { NotePalette.ink($0) & 0x00ffffff | 0x14000000 }
    static let font = NSFont.systemFont(ofSize: 13)
    static let smallFont = NSFont.systemFont(ofSize: 12)
}

extension NSColor {
    convenience init(argb: UInt32) {
        self.init(srgbRed: CGFloat((argb >> 16) & 255) / 255, green: CGFloat((argb >> 8) & 255) / 255,
                  blue: CGFloat(argb & 255) / 255, alpha: CGFloat(argb >> 24) / 255)
    }
}

/// A view that paints a themed fill (and optional bottom hairline), refreshed on appearance change.
class ThemedView: NSView {
    var fill: NSColor? { didSet { needsDisplay = true } }
    var bottomLine = false
    var topLine = false
    var radius: CGFloat = 0
    override var isFlipped: Bool { true }
    override func draw(_ dirtyRect: NSRect) {
        if let fill { fill.setFill(); NSBezierPath(roundedRect: bounds, xRadius: radius, yRadius: radius).fill() }
        Theme.hairline.setFill()
        if bottomLine { NSRect(x: 0, y: bounds.height - 1, width: bounds.width, height: 1).fill() }
        if topLine { NSRect(x: 0, y: 0, width: bounds.width, height: 1).fill() }
    }
}
