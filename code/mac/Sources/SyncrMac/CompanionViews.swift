import AppKit
import SyncrCore
import SyncrCompanion

/// Canvas-space layer for margin notes and drawings. Clicks that miss them fall through to the canvas.
@MainActor final class CompanionOverlay: NSView {
    override var isFlipped: Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? {
        let hit = super.hitTest(point)
        return hit === self ? nil : hit
    }
}

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

/// A quiet text menu, "Reader: Staff engineer ▾": muted text that opens a menu of choices.
@MainActor final class QuietMenu: NSButton {
    var prefix = ""
    var mark: NSColor? { didSet { refresh() } }
    var onChange: (() -> Void)?
    private(set) var itemTitles: [String] = []
    private(set) var indexOfSelectedItem = -1

    convenience init(prefix: String = "") {
        self.init(title: "", target: nil, action: nil)
        self.prefix = prefix
        isBordered = false
        setButtonType(.momentaryChange)
        (cell as? NSButtonCell)?.highlightsBy = []
        focusRingType = .none
        target = self
        action = #selector(open)
        setContentCompressionResistancePriority(.defaultHigh, for: .horizontal)
    }
    func addItems(withTitles titles: [String]) { itemTitles += titles; if indexOfSelectedItem < 0, !titles.isEmpty { indexOfSelectedItem = 0 }; refresh() }
    func removeAllItems() { itemTitles = []; indexOfSelectedItem = -1; refresh() }
    func selectItem(at index: Int) { if itemTitles.indices.contains(index) { indexOfSelectedItem = index; refresh() } }
    override var isEnabled: Bool { didSet { refresh() } }

    private func refresh() {
        let text = NSMutableAttributedString()
        let colour = isEnabled ? Theme.muted : Theme.muted.withAlphaComponent(0.5)
        if let mark { text.append(NSAttributedString(string: "● ", attributes: [.font: NSFont.systemFont(ofSize: 9), .foregroundColor: mark, .baselineOffset: 1])) }
        let value = itemTitles.indices.contains(indexOfSelectedItem) ? itemTitles[indexOfSelectedItem] : ""
        text.append(NSAttributedString(string: prefix + value + " ▾", attributes: [.font: Theme.font, .foregroundColor: colour]))
        attributedTitle = text
    }
    @objc private func open() {
        let menu = NSMenu()
        for (index, title) in itemTitles.enumerated() {
            let item = NSMenuItem(title: title, action: #selector(pick(_:)), keyEquivalent: "")
            item.target = self
            item.tag = index
            item.state = index == indexOfSelectedItem ? .on : .off
            menu.addItem(item)
        }
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: bounds.height + 4), in: self)
    }
    @objc private func pick(_ item: NSMenuItem) {
        guard item.tag != indexOfSelectedItem else { return }
        selectItem(at: item.tag)
        onChange?()
    }
    override var isFlipped: Bool { true }
}

/// The companion's two quiet rows above the note: its name and menu, the mode with its amber mark, the Web
/// switch and the reader; then "Tell Syncr" with the line in the serif. Hidden (zero height) for read-only notes.
@MainActor final class CompanionStrip: ThemedView {
    static let colours: [InkColour] = [.ink, .red, .green, .blue, .orange]
    static let colourNames = ["Ink", "Red", "Green", "Blue", "Orange"]
    static func colour(_ value: InkColour) -> NSColor {
        // Plain ink is the quiet default: a muted mark, since colour means state.
        switch value { case .ink: Theme.muted; case .red: .systemRed; case .green: .systemGreen; case .blue: .systemBlue; case .orange: .systemOrange }
    }
    static let height: CGFloat = 62

    let tell = NSTextField(string: "")
    let mode = QuietMenu(prefix: "")
    let reader = QuietMenu(prefix: "Reader: ")
    let web = NSSwitch()
    let voice = NSSwitch()
    var voiceChanged: ((Bool) -> Void)?
    private let name = NSButton(title: "", target: nil, action: nil)
    private let status = NSTextField(labelWithString: "")
    private var askLink: ActionButton!
    private weak var hotkeys: HotkeyStore?
    private var colourIndex = 0
    private var writable = true
    var changed: (() -> Void)?
    var ask: (() -> Void)?
    var askAll: (() -> Void)?
    var draw: (() -> Void)?
    var settings: (() -> Void)?
    var lastRequest: (() -> Void)?
    /// The day's budget, for the window's status bar: text, tooltip, paused.
    var onBudget: ((String, String, Bool) -> Void)?
    /// The companion is working: the mode mark pulses.
    var thinking = false { didSet { if thinking != oldValue { pulse() } } }
    private(set) lazy var height = heightAnchor.constraint(equalToConstant: Self.height)
    override var isHidden: Bool { didSet { height.constant = isHidden ? 0 : Self.height } }

    override init(frame: NSRect) {
        super.init(frame: frame)
        fill = Theme.page
        wantsLayer = true
        name.isBordered = false
        name.target = self
        name.action = #selector(openMenu)
        name.attributedTitle = {
            let text = NSMutableAttributedString(string: "● ", attributes: [.font: NSFont.systemFont(ofSize: 9), .foregroundColor: Theme.ink, .baselineOffset: 1])
            text.append(NSAttributedString(string: "Syncr ▾", attributes: [.font: Theme.font, .foregroundColor: Theme.soft]))
            return text
        }()
        name.toolTip = "Ask, readers, colour, settings"
        mode.addItems(withTitles: ["Off", "When you ask", "Thinks with you"])
        mode.toolTip = "When Syncr speaks"
        reader.toolTip = "Who reads this note"
        for menu in [mode, reader] { menu.onChange = { [weak self] in self?.changed?() } }
        web.controlSize = .mini
        web.target = self
        web.action = #selector(change)
        web.toolTip = "Let Syncr search the web"
        voice.controlSize = .mini
        voice.target = self; voice.action = #selector(changeVoice)
        voice.toolTip = "Read new companion notes and replies aloud for this note"
        let voiceLabel = NSTextField(labelWithString: "Voice")
        voiceLabel.font = Theme.font; voiceLabel.textColor = Theme.muted
        let webLabel = NSTextField(labelWithString: "Web")
        webLabel.font = Theme.font
        webLabel.textColor = Theme.muted
        status.font = Theme.smallFont
        status.textColor = Theme.muted
        status.lineBreakMode = .byTruncatingTail
        status.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        askLink = ActionButton(link: "Ask", size: 12) { [weak self] in self?.ask?() }
        askLink.toolTip = "Ask about the selection"
        let spacer = NSView()
        spacer.setContentHuggingPriority(.init(1), for: .horizontal)
        let top = NSStackView(views: [name, mode, NSStackView(views: [webLabel, web]), NSStackView(views: [voiceLabel, voice]), reader, spacer, status, askLink])
        top.spacing = 16

        let pen = NSTextField(labelWithString: "✎ Tell Syncr")
        pen.font = Theme.font
        pen.textColor = Theme.muted
        pen.setContentHuggingPriority(.required, for: .horizontal)
        tell.isBordered = false
        tell.drawsBackground = false
        tell.focusRingType = .none
        tell.font = Theme.serif(15.5)
        tell.textColor = Theme.soft
        tell.placeholderAttributedString = NSAttributedString(string: "what is this note for?", attributes: [.font: Theme.serif(15.5), .foregroundColor: Theme.muted])
        tell.lineBreakMode = .byTruncatingTail
        tell.cell?.usesSingleLineMode = true
        tell.target = self
        tell.action = #selector(change)
        let bottom = NSStackView(views: [pen, tell])
        bottom.spacing = 16
        bottom.alignment = .firstBaseline

        for row in [top, bottom] {
            row.translatesAutoresizingMaskIntoConstraints = false
            addSubview(row)
            NSLayoutConstraint.activate([
                row.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 28),
                row.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -20),
                row.heightAnchor.constraint(equalToConstant: 24)
            ])
        }
        NSLayoutConstraint.activate([
            top.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            bottom.topAnchor.constraint(equalTo: top.bottomAnchor)
        ])
        clipsToBounds = true
        pulse()
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func updateHotkeys(_ store: HotkeyStore) {
        hotkeys = store
        let title = "Ask" + (store.binding(for: .askSyncr).map { "  \($0.compact)" } ?? "")
        askLink.attributedTitle = NSAttributedString(string: title, attributes: [.font: NSFont.systemFont(ofSize: 12), .foregroundColor: Theme.muted])
        askLink.toolTip = store.hint(.askSyncr)
    }

    @objc private func change(_ sender: Any?) { changed?() }
    @objc private func changeVoice() { voiceChanged?(voice.state == .on) }

    @objc private func openMenu() {
        let menu = NSMenu()
        func item(_ title: String, _ key: String = "", _ run: (() -> Void)?) -> NSMenuItem {
            let entry = NSMenuItem(title: title, action: #selector(ClosureTarget.fire), keyEquivalent: key)
            entry.target = ClosureTarget.attach(to: entry) { run?() }
            return entry
        }
        let askItem = item("Ask Syncr", hotkeys?.binding(for: .askSyncr)?.key ?? "", ask)
        askItem.keyEquivalentModifierMask = hotkeys?.binding(for: .askSyncr)?.flags ?? []
        menu.addItem(askItem)
        menu.addItem(item("Ask All Readers", "", askAll))
        menu.addItem(item("Draw This Selection (Experimental)", "", draw))
        menu.addItem(.separator())
        let presets = NSMenuItem(title: "Tell Presets", action: nil, keyEquivalent: "")
        presets.submenu = NSMenu()
        for preset in Reader.tellPresets {
            presets.submenu?.addItem(item(preset, "") { [weak self] in self?.tell.stringValue = preset; self?.changed?() })
        }
        presets.isEnabled = writable
        menu.addItem(presets)
        let colours = NSMenuItem(title: "Margin Colour", action: nil, keyEquivalent: "")
        colours.submenu = NSMenu()
        for (index, title) in Self.colourNames.enumerated() {
            let entry = item(title, "") { [weak self] in self?.colourIndex = index; self?.changed?() }
            entry.state = index == colourIndex ? .on : .off
            entry.image = Self.swatch(Self.colour(Self.colours[index]))
            colours.submenu?.addItem(entry)
        }
        colours.isEnabled = writable
        menu.addItem(colours)
        menu.addItem(.separator())
        menu.addItem(item("Companion Settings…", "", settings))
        menu.addItem(item("Last Companion Request…", "", lastRequest))
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: name.bounds.height + 4), in: name)
    }

    private static func swatch(_ colour: NSColor) -> NSImage {
        NSImage(size: NSSize(width: 10, height: 10), flipped: false) { rect in
            colour.setFill()
            NSBezierPath(ovalIn: rect.insetBy(dx: 1, dy: 1)).fill()
            return true
        }
    }

    private var pulseTimer: Timer?
    private func pulse() {
        let base = mode.indexOfSelectedItem == 0 ? Theme.muted : Theme.thinking
        mode.mark = base
        pulseTimer?.invalidate()
        pulseTimer = nil
        guard thinking else { return }
        pulseTimer = Timer.scheduledTimer(withTimeInterval: 0.6, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.mode.mark = AppPreferences.shared.reducedMotion ? base : (self.mode.mark == base ? base.withAlphaComponent(0.35) : base)
            }
        }
    }

    /// The settings the controls currently show.
    func settings(readers: [Reader]) -> CompanionSettings {
        let modes: [SyncrCore.CompanionMode] = [.off, .whenAsked, .thinksWithYou]
        let readerID = readers.indices.contains(reader.indexOfSelectedItem) ? readers[reader.indexOfSelectedItem].id : "staff-engineer"
        return CompanionSettings(tell: tell.stringValue, mode: modes[max(0, min(2, mode.indexOfSelectedItem))], web: web.state == .on,
                                 activeReader: readerID, colour: Self.colours[max(0, min(4, colourIndex))])
    }

    func show(_ value: CompanionSettings, readers: [Reader], writable: Bool) {
        self.writable = writable
        if tell.currentEditor() == nil { tell.stringValue = value.tell }
        mode.selectItem(at: value.mode == .off ? 0 : value.mode == .whenAsked ? 1 : 2)
        web.state = value.web ? .on : .off
        colourIndex = Self.colours.firstIndex(of: value.colour) ?? 0
        let names = readers.map(\.name)
        if reader.itemTitles != names {
            reader.removeAllItems()
            reader.addItems(withTitles: names)
        }
        if let index = readers.firstIndex(where: { $0.id == value.activeReader }) { reader.selectItem(at: index) }
        for control in [tell, mode, web, reader] as [NSControl] { control.isEnabled = writable }
        pulse()
    }

    /// The status goes on the strip (it is what changed); the budget goes to the status bar.
    func show(meter value: BudgetMeter, status text: String) {
        status.stringValue = text == "Ready" ? "" : text
        status.textColor = text.hasPrefix("Choose") || text.hasPrefix("Turn on") ? Theme.needsYou : Theme.muted
        let share = Int((Double(value.tokens) / Double(max(1, value.tokenLimit)) * 100).rounded())
        let budget = value.reached ? "Syncr paused: today's budget is used" : "Syncr \(share)% of today's budget"
        onBudget?(budget, "\(value.tokens) of \(value.tokenLimit) tokens today\n\(value.unmeteredRequests) of \(value.requestLimit) unmetered requests", value.reached)
    }
}

/// A margin note beside its passage, in canvas units: one small dot, then the companion's words in large
/// serif. No card and no border. Its actions appear in quiet sans beneath on hover.
@MainActor final class MarginCard: NSView {
    static let width: CGFloat = 1000
    var hover: ((Bool) -> Void)?
    private(set) var fittedHeight: CGFloat = 0
    private let actions: NSStackView
    override var isFlipped: Bool { true }

    init(text: String, caption: String, sources: [CompanionSource], colour: NSColor, pin: @escaping () -> Void,
         pinned: Bool, dismiss: @escaping () -> Void, add: @escaping () -> Void, read: @escaping () -> Void, discuss: @escaping () -> Void, footnote: String? = nil) {
        actions = NSStackView(views: [
            ActionButton(link: pinned ? "Unpin" : "Pin", size: 34, action: pin),
            ActionButton(link: "Add to note", size: 34, action: add),
            ActionButton(link: "Dismiss", size: 34, action: dismiss),
            ActionButton(link: "Read aloud", size: 34, action: read),
            ActionButton(link: "Discuss", size: 34, action: discuss)
        ])
        super.init(frame: .zero)
        let inner = Self.width - 56
        let dot = ThemedView()
        dot.fill = colour
        dot.radius = 10
        dot.frame = CGRect(x: 0, y: 26, width: 20, height: 20)
        addSubview(dot)
        let label = NSTextField(wrappingLabelWithString: text)
        label.font = Theme.serif(50)
        label.textColor = Theme.ink
        label.preferredMaxLayoutWidth = inner
        label.isSelectable = true
        var views: [NSView] = [label]
        if !sources.isEmpty {
            let list = NSTextField(wrappingLabelWithString: sources.map { "↗ \($0.title) — \(URL(string: $0.url)?.host ?? $0.url)" }.joined(separator: "\n"))
            list.font = .systemFont(ofSize: 32)
            list.textColor = Theme.muted
            list.isSelectable = true
            list.preferredMaxLayoutWidth = inner
            list.toolTip = sources.map(\.url).joined(separator: "\n")
            views.append(list)
        }
        if let footnote {
            let line = NSTextField(wrappingLabelWithString: footnote)
            line.font = .systemFont(ofSize: 30); line.textColor = Theme.muted; line.preferredMaxLayoutWidth = inner
            views.append(line)
        }
        if !caption.isEmpty {
            let who = NSTextField(labelWithString: caption)
            who.font = .systemFont(ofSize: 34)
            who.textColor = Theme.muted
            actions.insertArrangedSubview(who, at: 0)
        }
        actions.spacing = 40
        actions.alphaValue = 0
        views.append(actions)
        let stack = NSStackView(views: views)
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 16
        stack.setCustomSpacing(26, after: views[views.count - 2])
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 56), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor)
        ])
        fittedHeight = ceil(stack.fittingSize.height)
        toolTip = caption
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeInKeyWindow], owner: self))
    }
    override func mouseEntered(with event: NSEvent) {
        if AppPreferences.shared.reducedMotion { actions.alphaValue = 1 } else { actions.animator().alphaValue = 1 }
        hover?(true)
    }
    override func mouseExited(with event: NSEvent) {
        if AppPreferences.shared.reducedMotion { actions.alphaValue = 0 } else { actions.animator().alphaValue = 0 }
        hover?(false)
    }
}

/// Holds an action closure for controls that only offer target/action.
@MainActor final class ClosureTarget: NSObject {
    private static var key = 0
    private let run: () -> Void
    private init(_ run: @escaping () -> Void) { self.run = run }
    static func attach(to object: NSObject, _ run: @escaping () -> Void) -> ClosureTarget {
        let target = ClosureTarget(run)
        objc_setAssociatedObject(object, &key, target, .OBJC_ASSOCIATION_RETAIN)
        return target
    }
    @objc func fire() { run() }
}

final class FlippedStack: NSStackView { override var isFlipped: Bool { true } }

/// A step-by-step companion drawing set on the page under its passage, in canvas units: thin ink lines,
/// serif labels, a serif caption for the current step left of a quiet stepper, "See it larger" beneath.
@MainActor final class DrawingCard: NSView {
    let drawing: SyncrCompanion.Drawing
    private let image = NSImageView()
    private let caption = NSTextField(labelWithString: "")
    private let counter = NSTextField(labelWithString: "")
    private var step = 0
    private var timer: Timer?
    private var larger: NSWindow?
    override var isFlipped: Bool { true }
    /// Extra height under the picture for the caption, stepper and "See it larger".
    static let chrome: CGFloat = 180

    init(_ drawing: SyncrCompanion.Drawing) {
        self.drawing = drawing
        super.init(frame: .zero)
        caption.font = Theme.serif(38)
        caption.textColor = Theme.soft
        caption.lineBreakMode = .byTruncatingTail
        caption.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        counter.font = .monospacedDigitSystemFont(ofSize: 32, weight: .regular)
        counter.textColor = Theme.muted
        let stepper = NSStackView(views: [
            caption,
            ActionButton(link: "‹", size: 36) { [weak self] in self?.advance(-1) },
            counter,
            ActionButton(link: "›", size: 36) { [weak self] in self?.advance(1) },
            ActionButton(link: "↻", size: 34) { [weak self] in self?.replay() }
        ])
        stepper.spacing = 36
        let larger = ActionButton(link: "See it larger", size: 32) { [weak self] in self?.enlarge() }
        image.imageScaling = .scaleProportionallyUpOrDown
        image.imageAlignment = .alignTopLeft
        let stack = NSStackView(views: [image, stepper, larger])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 18
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor),
            image.widthAnchor.constraint(equalTo: widthAnchor), image.heightAnchor.constraint(equalToConstant: drawing.height),
            stepper.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor)
        ])
        step = drawing.steps.count
        render()
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidChangeEffectiveAppearance() { super.viewDidChangeEffectiveAppearance(); render() }

    func debug(_ action: String) {
        switch action {
        case "previous": advance(-1)
        case "next": advance(1)
        case "replay": replay()
        case "larger": enlarge()
        default: break
        }
    }
    private func advance(_ n: Int) {
        timer?.invalidate()
        step = max(0, min(drawing.steps.count, step + n))
        render()
    }
    /// Thinner than handwriting, in the Ink token on the page itself.
    private var style: SketchRenderer.Style {
        var ink = Theme.ink.cgColor, muted = Theme.muted.cgColor
        effectiveAppearance.performAsCurrentDrawingAppearance { ink = Theme.ink.cgColor; muted = Theme.muted.cgColor }
        return SketchRenderer.Style(background: nil, ink: ink, secondary: muted, lineWidth: 1.1,
                                    labelFont: { Theme.serif($0) }, serifLabelSize: 13)
    }
    private func render() {
        if let cg = SketchRenderer.image(drawing, through: step, scale: 2, style: style) {
            image.image = NSImage(cgImage: cg, size: NSSize(width: drawing.width, height: drawing.height))
        }
        counter.stringValue = "\(step)/\(drawing.steps.count)"
        caption.stringValue = step > 0 ? drawing.steps[step - 1].caption : drawing.title
    }
    private func replay() {
        timer?.invalidate()
        step = 0
        render()
        timer = Timer.scheduledTimer(withTimeInterval: 0.8, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.step += 1
                self.render()
                if self.step >= self.drawing.steps.count { self.timer?.invalidate() }
            }
        }
    }
    private func enlarge() {
        let view = DrawingCard(drawing)
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: min(1100, drawing.width), height: min(850, drawing.height + 160)),
                              styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = drawing.title
        window.backgroundColor = Theme.page
        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.hasHorizontalScroller = true
        scroll.drawsBackground = false
        view.frame = CGRect(x: 0, y: 0, width: max(1100, drawing.width), height: drawing.height + Self.chrome)
        scroll.documentView = view
        window.contentView = scroll
        window.isReleasedWhenClosed = false
        window.center()
        window.makeKeyAndOrderFront(nil)
        larger = window
    }
}
