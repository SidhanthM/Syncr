import AppKit

/// A quiet, comfortably sized icon target shared by the ribbon and title-bar controls.
@MainActor final class ChromeIconButton: NSButton {
    var onClick: (() -> Void)?
    private var hovering = false

    init(symbol: String, label: String) {
        super.init(frame: NSRect(x: 0, y: 0, width: 32, height: 28))
        image = NSImage(systemSymbolName: symbol, accessibilityDescription: label)
        imagePosition = .imageOnly
        symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 16, weight: .regular)
        isBordered = false
        focusRingType = .none
        contentTintColor = Theme.muted
        setButtonType(.momentaryChange)
        (cell as? NSButtonCell)?.highlightsBy = []
        target = self
        action = #selector(clicked)
        toolTip = label
        setAccessibilityLabel(label)
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect], owner: self))
    }
    override func mouseEntered(with event: NSEvent) { hovering = true; needsDisplay = true }
    override func mouseExited(with event: NSEvent) { hovering = false; needsDisplay = true }
    override func draw(_ dirtyRect: NSRect) {
        if hovering || isHighlighted {
            Theme.hover.setFill()
            NSBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1), xRadius: 5, yRadius: 5).fill()
        }
        super.draw(dirtyRect)
    }
    @objc private func clicked() { onClick?() }
}

/// Lives outside the left sidebar, so its expand control remains visible when collapsed.
@MainActor final class SidebarToggle: NSTitlebarAccessoryViewController {
    private let button: ChromeIconButton
    var shortcut = "⌘\\"

    init(action: @escaping () -> Void) {
        button = ChromeIconButton(symbol: "sidebar.left", label: "")
        super.init(nibName: nil, bundle: nil)
        layoutAttribute = .left
        let container = NSView(frame: NSRect(x: 0, y: 0, width: 44, height: 30))
        button.frame = NSRect(x: 6, y: 1, width: 32, height: 28)
        button.autoresizingMask = [.minYMargin, .maxYMargin]
        button.onClick = action
        container.addSubview(button)
        view = container
        show(collapsed: false)
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func show(collapsed: Bool) {
        let label = "\(collapsed ? "Expand" : "Collapse") left sidebar"
        button.toolTip = shortcut.isEmpty ? label : "\(label) (\(shortcut))"
        button.setAccessibilityLabel(label)
        button.setAccessibilityValue(collapsed ? "Collapsed" : "Expanded")
    }
}
