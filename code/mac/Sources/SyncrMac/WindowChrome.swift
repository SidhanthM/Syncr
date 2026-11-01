import AppKit

/// The narrow strip at the far left: files, search, daily note, phone. Stays when the file tree collapses.
@MainActor final class RibbonView: ThemedView {
    var onFiles: (() -> Void)?
    var onSearch: (() -> Void)?
    var onDaily: (() -> Void)?
    var onPhone: ((NSView) -> Void)?
    var onSettings: (() -> Void)?
    private var phone: NSButton!
    private var commandButtons: [AppCommand: NSButton] = [:]

    override init(frame: NSRect) {
        super.init(frame: frame)
        fill = Theme.well
        rightLine = true
        func button(_ symbol: String, _ tip: String, _ action: @escaping (NSButton) -> Void) -> NSButton {
            let b = ChromeIconButton(symbol: symbol, label: tip)
            b.onClick = { [unowned b] in action(b) }
            b.widthAnchor.constraint(equalToConstant: 32).isActive = true
            b.heightAnchor.constraint(equalToConstant: 32).isActive = true
            return b
        }
        phone = button("iphone", "Phone") { [weak self] in self?.onPhone?($0) }
        let files = button("line.3.horizontal", "Files") { [weak self] _ in self?.onFiles?() }
        let search = button("magnifyingglass", "Search all notes") { [weak self] _ in self?.onSearch?() }
        let daily = button("calendar", "Daily note") { [weak self] _ in self?.onDaily?() }
        let settings = button("gearshape", "Settings") { [weak self] _ in self?.onSettings?() }
        commandButtons = [.toggleSidebar: files, .searchNotes: search, .dailyNote: daily, .settings: settings]
        let stack = NSStackView(views: [files, search, daily, phone!])
        stack.orientation = .vertical
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        settings.translatesAutoresizingMaskIntoConstraints = false
        addSubview(settings)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            settings.centerXAnchor.constraint(equalTo: centerXAnchor), settings.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -10),
            widthAnchor.constraint(equalToConstant: 44)
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func updateHotkeys(_ store: HotkeyStore) {
        for (command, button) in commandButtons { button.toolTip = store.hint(command) }
    }

    func showPhone(colour: NSColor, tip: String) {
        phone.contentTintColor = colour
        phone.toolTip = tip
    }
}

/// One line of status at the bottom of the window: save state, word count, the companion's budget,
/// and the phone's connection with a coloured dot.
@MainActor final class StatusBarView: ThemedView {
    enum Phone { case off, waiting, connected, needsYou }
    var onPhone: ((NSView) -> Void)?
    private let save = NSTextField(labelWithString: "")
    private let words = NSTextField(labelWithString: "")
    private let note = NSTextField(labelWithString: "")
    private let live = NSTextField(labelWithString: "")
    private let budget = NSTextField(labelWithString: "")
    private let pill = PhonePill()

    override init(frame: NSRect) {
        super.init(frame: frame)
        fill = Theme.panel
        topLine = true
        for label in [save, words, note, live, budget] {
            label.font = Theme.smallFont
            label.textColor = Theme.muted
            label.lineBreakMode = .byTruncatingTail
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
        words.font = .monospacedDigitSystemFont(ofSize: 11.5, weight: .regular)
        pill.onClick = { [weak self] in guard let self else { return }; self.onPhone?(self.pill) }
        let spacer = NSView()
        spacer.setContentHuggingPriority(.init(1), for: .horizontal)
        let row = NSStackView(views: [save, words, note, live, spacer, budget, pill])
        row.spacing = 14
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            row.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10),
            row.centerYAnchor.constraint(equalTo: centerYAnchor),
            heightAnchor.constraint(equalToConstant: 26)
        ])
        show(save: "", words: nil)
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func show(save text: String, words count: Int?) {
        save.stringValue = text
        save.textColor = text.hasPrefix("Not saved") || text.hasPrefix("Failed") ? Theme.needsYou : Theme.muted
        save.isHidden = text.isEmpty
        words.stringValue = count.map { $0 == 1 ? "1 word" : "\($0) words" } ?? ""
        words.isHidden = count == nil
    }
    /// A note-level remark, such as "Read-only until sync".
    func show(note text: String?) { note.stringValue = text ?? ""; note.isHidden = text == nil }
    /// Live-ink state from the phone (debug figures or a dropped stroke).
    func show(live text: String?) { live.stringValue = text ?? ""; live.isHidden = text == nil }
    func show(budget text: String, tooltip: String, paused: Bool) {
        budget.stringValue = text
        budget.toolTip = tooltip
        budget.textColor = paused ? Theme.needsYou : Theme.muted
        budget.isHidden = text.isEmpty
    }
    func show(phone text: String, state: Phone) { pill.show(text, state: state) }
}

private final class PhonePill: NSView {
    var onClick: (() -> Void)?
    private let dot = ThemedView()
    private let label = NSTextField(labelWithString: "")
    override var isFlipped: Bool { true }

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.cornerRadius = 9
        layer?.borderWidth = 1
        dot.radius = 3
        label.font = Theme.smallFont
        label.textColor = Theme.ink
        let row = NSStackView(views: [dot, label])
        row.spacing = 6
        row.edgeInsets = NSEdgeInsets(top: 0, left: 8, bottom: 0, right: 9)
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            dot.widthAnchor.constraint(equalToConstant: 6), dot.heightAnchor.constraint(equalToConstant: 6),
            row.leadingAnchor.constraint(equalTo: leadingAnchor), row.trailingAnchor.constraint(equalTo: trailingAnchor),
            row.topAnchor.constraint(equalTo: topAnchor), row.bottomAnchor.constraint(equalTo: bottomAnchor),
            heightAnchor.constraint(equalToConstant: 18)
        ])
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func show(_ text: String, state: StatusBarView.Phone) {
        label.stringValue = text
        dot.fill = switch state {
        case .off: Theme.muted.withAlphaComponent(0.5)
        case .waiting: Theme.muted
        case .connected: Theme.connected
        case .needsYou: Theme.needsYou
        }
        label.textColor = state == .off ? Theme.muted : Theme.ink
        needsDisplay = true
    }
    override func updateLayer() { layer?.borderColor = Theme.hairlineStrong.cgColor }
    override var wantsUpdateLayer: Bool { true }
    override func mouseDown(with event: NSEvent) { onClick?() }
}
