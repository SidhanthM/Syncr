import AppKit

struct TabInfo: Equatable {
    let id: String
    let title: String
    let readOnly: Bool
}

/// Row of note tabs. Pure view: the window feeds it `TabInfo`s and handles clicks.
final class TabBarView: ThemedView {
    var onSelect: ((String) -> Void)?
    var onClose: ((String) -> Void)?
    private let stack = NSStackView()

    override init(frame: NSRect) {
        super.init(frame: frame)
        fill = Theme.desk
        bottomLine = true
        stack.orientation = .horizontal
        stack.spacing = 0
        stack.alignment = .centerY
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -1),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor)
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
        }
    }
}

private final class TabItemView: ThemedView {
    var onSelect: (() -> Void)?
    var onClose: (() -> Void)?
    private let tabID: String

    init(tab: TabInfo, active: Bool) {
        tabID = tab.id
        super.init(frame: .zero)
        fill = active ? Theme.paper : nil
        let label = NSTextField(labelWithString: tab.title)
        label.font = Theme.smallFont
        label.textColor = active ? Theme.ink : Theme.muted
        label.lineBreakMode = .byTruncatingTail
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let close = NSButton(image: NSImage(systemSymbolName: "xmark", accessibilityDescription: "Close tab")!,
                             target: self, action: #selector(closeTapped))
        close.isBordered = false
        close.contentTintColor = Theme.muted
        close.imageScaling = .scaleProportionallyDown
        close.widthAnchor.constraint(equalToConstant: 14).isActive = true
        var views: [NSView] = [label, close]
        if tab.readOnly {
            let lock = NSImageView(image: NSImage(systemSymbolName: "lock", accessibilityDescription: "Read-only")!)
            lock.contentTintColor = Theme.muted
            views.insert(lock, at: 0)
        }
        let row = NSStackView(views: views)
        row.spacing = 6
        row.edgeInsets = NSEdgeInsets(top: 0, left: 12, bottom: 0, right: 8)
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor), row.trailingAnchor.constraint(equalTo: trailingAnchor),
            row.topAnchor.constraint(equalTo: topAnchor), row.bottomAnchor.constraint(equalTo: bottomAnchor),
            widthAnchor.constraint(lessThanOrEqualToConstant: 220), widthAnchor.constraint(greaterThanOrEqualToConstant: 90)
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    override func mouseDown(with event: NSEvent) { onSelect?() }
    @objc private func closeTapped() { onClose?() }
}
