import AppKit

/// Shared button styles for document controls and inline settings.
@MainActor final class ActionButton: NSButton {
    var run: (() -> Void)?
    convenience init(_ title: String, action: @escaping () -> Void) {
        self.init(title: title, target: nil, action: nil)
        target = self
        self.action = #selector(clicked)
        run = action
        bezelStyle = .rounded
        controlSize = .small
    }
    /// A borderless text action in quiet sans.
    convenience init(link title: String, size: CGFloat, colour: NSColor = Theme.muted, action: @escaping () -> Void) {
        self.init(title, action: action)
        isBordered = false
        attributedTitle = NSAttributedString(string: title, attributes: [.font: NSFont.systemFont(ofSize: size), .foregroundColor: colour])
    }
    convenience init(symbol: String, label: String, action: @escaping () -> Void) {
        self.init("", action: action)
        image = NSImage(systemSymbolName: symbol, accessibilityDescription: label)
        isBordered = false
        contentTintColor = Theme.muted
        toolTip = label
    }
    @objc private func clicked() { run?() }
}
