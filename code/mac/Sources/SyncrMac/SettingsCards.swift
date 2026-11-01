import AppKit

@MainActor final class SettingsNavButton: NSButton {
    let category: SettingsCategory
    var selected = false { didSet { state = selected ? .on : .off; needsDisplay = true } }
    var onClick: (() -> Void)?
    init(_ category: SettingsCategory) {
        self.category = category
        super.init(frame: .zero)
        title = category.title; isBordered = false; target = self; action = #selector(clicked)
        setAccessibilityLabel(category.title)
        heightAnchor.constraint(equalToConstant: 36).isActive = true
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func draw(_ dirtyRect: NSRect) {
        if selected || isHighlighted {
            (selected ? Theme.raise : Theme.hover).setFill()
            NSBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1), xRadius: 8, yRadius: 8).fill()
        }
        let colour = selected ? Theme.ink : Theme.soft
        let image = NSImage(systemSymbolName: category.symbol, accessibilityDescription: nil)?
            .withSymbolConfiguration(.init(pointSize: 14, weight: .regular).applying(.init(paletteColors: [colour])))
        image?.draw(in: NSRect(x: 12, y: bounds.midY - 8, width: 16, height: 16))
        let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 13, weight: selected ? .medium : .regular), .foregroundColor: colour]
        let height = (title as NSString).size(withAttributes: attrs).height
        (title as NSString).draw(at: NSPoint(x: 39, y: bounds.midY - height / 2), withAttributes: attrs)
    }
    @objc private func clicked() { onClick?() }
}

@MainActor final class SettingsSwitch: NSSwitch {
    var onChange: ((Bool) -> Void)?
    init(value: Bool, label: String, action: @escaping (Bool) -> Void) {
        super.init(frame: .zero)
        state = value ? .on : .off; target = self; self.action = #selector(changed)
        controlSize = .small; onChange = action; setAccessibilityLabel(label)
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    @objc private func changed() { onChange?(state == .on) }
}

@MainActor final class SettingsPopup: NSPopUpButton {
    var onChange: ((Int) -> Void)?
    init(titles: [String], selected: Int, label: String, action: @escaping (Int) -> Void) {
        super.init(frame: .zero, pullsDown: false)
        addItems(withTitles: titles); selectItem(at: selected)
        target = self; self.action = #selector(changed); onChange = action
        font = Theme.font; controlSize = .regular; setAccessibilityLabel(label)
        widthAnchor.constraint(equalToConstant: 158).isActive = true
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    @objc private func changed() { onChange?(indexOfSelectedItem) }
}

@MainActor final class SettingsCard: ThemedView {
    init(rows: [NSView]) {
        super.init(frame: .zero)
        fill = Theme.well; radius = 12
        let stack = NSStackView(views: rows); stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false; addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor), stack.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
        for row in rows { row.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true }
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        Theme.hairline.setStroke()
        NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: 12, yRadius: 12).stroke()
    }
}

@MainActor enum SettingsUI {
    static func label(_ text: String, size: CGFloat = 13, colour: NSColor = Theme.ink, weight: NSFont.Weight = .regular) -> NSTextField {
        let field = NSTextField(wrappingLabelWithString: text)
        field.font = .systemFont(ofSize: size, weight: weight); field.textColor = colour
        field.setContentCompressionResistancePriority(.required, for: .vertical)
        return field
    }
    static func row(title: String, detail: String, control: NSView, separator: Bool) -> NSView {
        let row = ThemedView(); row.bottomLine = separator
        let name = label(title, weight: .medium)
        let hint = label(detail, size: 11.5, colour: Theme.muted)
        let labels = NSStackView(views: [name, hint]); labels.orientation = .vertical; labels.alignment = .leading; labels.spacing = 5
        for view in [labels, control] { view.translatesAutoresizingMaskIntoConstraints = false; row.addSubview(view) }
        control.setContentHuggingPriority(.required, for: .horizontal)
        control.setContentCompressionResistancePriority(.required, for: .horizontal)
        NSLayoutConstraint.activate([
            labels.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 18),
            labels.topAnchor.constraint(equalTo: row.topAnchor, constant: 17), labels.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -17),
            labels.trailingAnchor.constraint(equalTo: control.leadingAnchor, constant: -20),
            name.widthAnchor.constraint(equalTo: labels.widthAnchor), hint.widthAnchor.constraint(equalTo: labels.widthAnchor),
            control.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -18), control.centerYAnchor.constraint(equalTo: row.centerYAnchor),
            control.topAnchor.constraint(greaterThanOrEqualTo: row.topAnchor, constant: 12), control.bottomAnchor.constraint(lessThanOrEqualTo: row.bottomAnchor, constant: -12)
        ])
        return row
    }
}
