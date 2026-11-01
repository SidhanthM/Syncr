import AppKit

/// Drawn keycaps stay crisp at any display scale and expose one readable accessibility label.
@MainActor final class BindingButton: NSButton {
    var binding: KeyBinding? { didSet { invalidateIntrinsicContentSize(); needsDisplay = true; setAccessibilityLabel(binding?.display ?? "Unassigned. Click to bind.") } }
    private let keycapFont = NSFont.systemFont(ofSize: 11.5, weight: .medium)
    private var keycapWidths: [CGFloat] {
        binding?.keycaps.map { max(25, ($0 as NSString).size(withAttributes: [.font: keycapFont]).width + 14) } ?? []
    }
    var onClick: (() -> Void)?
    init(binding: KeyBinding?) {
        self.binding = binding
        super.init(frame: .zero)
        isBordered = false
        title = ""
        target = self
        action = #selector(clicked)
        setAccessibilityLabel(binding?.display ?? "Unassigned. Click to bind.")
        toolTip = "Click to record a new shortcut"
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override var isFlipped: Bool { true }
    override var intrinsicContentSize: NSSize {
        let widths = keycapWidths
        let width = binding == nil
            ? ("Click to bind" as NSString).size(withAttributes: [.font: keycapFont]).width
            : widths.reduce(0, +) + CGFloat(max(0, widths.count - 1)) * 5
        return NSSize(width: ceil(width) + 8, height: 36)
    }
    override func draw(_ dirtyRect: NSRect) {
        let font = keycapFont
        guard let binding else {
            let text = "Click to bind" as NSString
            let size = text.size(withAttributes: [.font: font])
            text.draw(at: NSPoint(x: bounds.midX - size.width / 2, y: bounds.midY - size.height / 2), withAttributes: [.font: font, .foregroundColor: Theme.muted])
            return
        }
        let widths = keycapWidths
        var x = max(4, (bounds.width - widths.reduce(0, +) - CGFloat(widths.count - 1) * 5) / 2)
        for (index, key) in binding.keycaps.enumerated() {
            let rect = NSRect(x: x, y: (bounds.height - 24) / 2, width: widths[index], height: 24)
            let cap = NSBezierPath(roundedRect: rect, xRadius: 4, yRadius: 4)
            Theme.raise.setFill(); cap.fill()
            Theme.hairlineStrong.setStroke(); cap.stroke()
            let size = (key as NSString).size(withAttributes: [.font: font])
            (key as NSString).draw(at: NSPoint(x: rect.midX - size.width / 2, y: rect.midY - size.height / 2), withAttributes: [.font: font, .foregroundColor: Theme.ink])
            x += widths[index] + 5
        }
    }
    @objc private func clicked() { onClick?() }
}

/// A local monitor consumes recording events before application menus or text views see them.
/// It exists only for the sheet's lifetime; there is no global keyboard monitoring.
@MainActor final class HotkeyRecorder: NSObject, NSWindowDelegate {
    private let panel: NSPanel
    private let store: HotkeyStore
    private let command: AppCommand
    private let preview = BindingButton(binding: nil)
    private let message = NSTextField(wrappingLabelWithString: "Press a shortcut. Escape cancels.")
    private let save = ActionButton("Save binding") {}
    private var candidate: KeyBinding?
    private var monitor: Any?
    private var focusObserver: NSObjectProtocol?
    var onFinish: (() -> Void)?

    init(command: AppCommand, store: HotkeyStore) {
        self.command = command; self.store = store
        panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 520, height: 260), styleMask: [.titled], backing: .buffered, defer: false)
        super.init()
        panel.title = "Record Hotkey"
        panel.isReleasedWhenClosed = false
        panel.delegate = self
        let content = ThemedView(); content.fill = Theme.panel
        let title = NSTextField(labelWithString: command.title)
        title.font = .systemFont(ofSize: 19, weight: .semibold); title.textColor = Theme.ink
        let hint = NSTextField(wrappingLabelWithString: "Hold your modifiers and press a key. You can try another combination before saving.")
        hint.font = Theme.font; hint.textColor = Theme.muted
        preview.isEnabled = false
        message.font = Theme.font; message.textColor = Theme.muted
        save.isEnabled = false
        save.run = { [weak self] in self?.commit() }
        let cancel = ActionButton("Cancel") { [weak self] in self?.finish() }
        let buttons = NSStackView(views: [NSView(), cancel, save]); buttons.spacing = 10
        let stack = NSStackView(views: [title, hint, preview, message, buttons])
        stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(stack); panel.contentView = content
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -24),
            stack.topAnchor.constraint(equalTo: content.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: content.bottomAnchor, constant: -24),
            hint.widthAnchor.constraint(equalTo: stack.widthAnchor), message.widthAnchor.constraint(equalTo: stack.widthAnchor),
            preview.widthAnchor.constraint(equalTo: stack.widthAnchor), preview.heightAnchor.constraint(equalToConstant: 44),
            buttons.widthAnchor.constraint(equalTo: stack.widthAnchor)
        ])
    }
    func show(over window: NSWindow) {
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown]) { [weak self] event in
            let handled = MainActor.assumeIsolated {
                guard let self, self.panel.isKeyWindow else { return false }
                self.record(event)
                return true
            }
            return handled ? nil : event
        }
        // Never keep swallowing shortcuts after the user switches away from Syncr.
        focusObserver = NotificationCenter.default.addObserver(forName: NSApplication.didResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.finish() }
        }
        window.beginSheet(panel)
    }
    func record(_ event: NSEvent) {
        guard !event.isARepeat else { return }
        if event.keyCode == 53 { finish(); return }
        guard let binding = KeyBinding(event: event) else { return }
        candidate = binding; preview.binding = binding
        let error = binding.validationError ?? store.conflict(for: binding, excluding: command).map { "Already assigned to “\($0.title)”. Clear or rebind it first." }
        message.stringValue = error ?? "Ready to save. Press another combination to change it."
        message.textColor = error == nil ? Theme.muted : Theme.needsYou
        save.isEnabled = error == nil
    }
    private func commit() {
        guard let candidate else { return }
        do { try store.set(candidate, for: command); finish() }
        catch { message.stringValue = error.localizedDescription; message.textColor = Theme.needsYou }
    }
    func finish() {
        if let monitor { NSEvent.removeMonitor(monitor); self.monitor = nil }
        if let focusObserver { NotificationCenter.default.removeObserver(focusObserver); self.focusObserver = nil }
        panel.sheetParent?.endSheet(panel)
        panel.orderOut(nil)
        onFinish?()
    }
}
