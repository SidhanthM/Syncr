import AppKit

struct TabInfo: Equatable {
    let id: String
    let title: String
    let readOnly: Bool
}

/// Row of note tabs on the Well; the active tab opens into the page below it. Pure view: the window
/// feeds it `TabInfo`s and handles clicks.
final class TabBarView: ThemedView {
    var onSelect: ((String) -> Void)?
    var onClose: ((String) -> Void)?
    private let stack = NSStackView()

    override init(frame: NSRect) {
        super.init(frame: frame)
        fill = Theme.well
        bottomLine = true
        stack.orientation = .horizontal
        stack.spacing = 2
        stack.alignment = .bottom
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 8),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 5),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -8)
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func update(_ tabs: [TabInfo], active: String?) {
        stack.arrangedSubviews.forEach { stack.removeArrangedSubview($0); $0.removeFromSuperview() }
        for tab in tabs {
            let item = TabItemView(tab: tab, active: tab.id == active)
            item.onSelect = { [weak self] in self?.onSelect?(tab.id) }
            item.onClose = { [weak self] in self?.onClose?(tab.id) }
            stack.addArrangedSubview(item)
            item.heightAnchor.constraint(equalTo: stack.heightAnchor).isActive = true
        }
    }
}

private final class TabItemView: NSView {
    var onSelect: (() -> Void)?
    var onClose: (() -> Void)?
    private let active: Bool
    private let close: NSButton
    override var isFlipped: Bool { true }

    init(tab: TabInfo, active: Bool) {
        self.active = active
        close = NSButton(image: NSImage(systemSymbolName: "xmark", accessibilityDescription: "Close tab")!, target: nil, action: nil)
        super.init(frame: .zero)
        let label = NSTextField(labelWithString: tab.title)
        label.font = Theme.font
        label.textColor = active ? Theme.ink : Theme.muted
        label.lineBreakMode = .byTruncatingTail
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        close.target = self
        close.action = #selector(closeTapped)
        close.isBordered = false
        close.contentTintColor = Theme.muted
        close.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 9, weight: .medium)
        close.isHidden = !active
        close.widthAnchor.constraint(equalToConstant: 12).isActive = true
        var views: [NSView] = [label, close]
        if tab.readOnly {
            let mark = NSTextField(labelWithString: "phone")
            mark.font = .systemFont(ofSize: 10)
            mark.textColor = Theme.muted
            views.insert(mark, at: 1)
        }
        let row = NSStackView(views: views)
        row.spacing = 6
        row.edgeInsets = NSEdgeInsets(top: 0, left: 12, bottom: 0, right: 8)
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor), row.trailingAnchor.constraint(equalTo: trailingAnchor),
            row.topAnchor.constraint(equalTo: topAnchor), row.bottomAnchor.constraint(equalTo: bottomAnchor),
            widthAnchor.constraint(lessThanOrEqualToConstant: 220), widthAnchor.constraint(greaterThanOrEqualToConstant: 80)
        ])
        toolTip = tab.readOnly ? "\(tab.title) — from the phone, read-only until sync" : tab.title
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    override func draw(_ dirtyRect: NSRect) {
        guard active else { return }
        // Rounded top corners, open at the bottom so the tab runs into the page.
        let r: CGFloat = 8, b = bounds
        let path = NSBezierPath()
        path.move(to: CGPoint(x: b.minX + 0.5, y: b.maxY + 1))
        path.line(to: CGPoint(x: b.minX + 0.5, y: b.minY + r))
        path.appendArc(withCenter: CGPoint(x: b.minX + r, y: b.minY + r), radius: r - 0.5, startAngle: 180, endAngle: 270)
        path.line(to: CGPoint(x: b.maxX - r, y: b.minY + 0.5))
        path.appendArc(withCenter: CGPoint(x: b.maxX - r, y: b.minY + r), radius: r - 0.5, startAngle: 270, endAngle: 360)
        path.line(to: CGPoint(x: b.maxX - 0.5, y: b.maxY + 1))
        Theme.page.setFill()
        path.fill()
        Theme.hairline.setStroke()
        path.lineWidth = 1
        path.stroke()
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeInKeyWindow], owner: self))
    }
    override func mouseEntered(with event: NSEvent) { close.isHidden = false }
    override func mouseExited(with event: NSEvent) { close.isHidden = !active }
    override func mouseDown(with event: NSEvent) { onSelect?() }
    @objc private func closeTapped() { onClose?() }
}
