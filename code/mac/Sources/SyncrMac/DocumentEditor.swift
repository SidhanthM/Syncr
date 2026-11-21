import AppKit

@MainActor protocol TextDocument: AnyObject {
    var text: String { get }
    var writable: Bool { get }
    func replace(with text: String)
    func flush()
    func history(redo: Bool)
}
@MainActor final class MemoryTextDocument: TextDocument {
    var text: String
    var writable = true
    init(_ text: String = "") { self.text = text }
    func replace(with text: String) { if writable { self.text = text } }
    func flush() {}
    func history(redo: Bool) {}
}

/// A normal TextKit editor in canvas coordinates. Its parent supplies the viewport transform.
@MainActor final class DocumentEditor: NSView, NSTextViewDelegate, NSTextFieldDelegate {
    let textView = MarkdownTextView()
    let title = DragTextField()
    var isFree = false
    var onActivity: (() -> Void)?
    var drawingHeights: [String: CGFloat] = [:] {
        didSet { if drawingHeights != oldValue { refreshPreview() } }
    }
    var imageGaps: [Int: CGFloat] = [:] {
        didSet { if imageGaps != oldValue { refreshPreview() } }
    }
    var onMove: ((CGFloat, CGFloat) -> Void)?
    var onEndEditing: (() -> Void)?
    private let panel = CommandPanel(frame: .zero)
    private var menuToken: SlashToken?
    private var menuNote: Int?
    private var dismissedSlash: Int?
    private var document: (any TextDocument)?
    private var quiet = false
    private var styling = false
    private var pending: DispatchWorkItem?
    /// When the oldest keystroke not yet committed arrived, and when the text was last committed (seconds, `uptime`); 0 for none.
    private var dirtySince = 0.0, lastCommit = 0.0
    var onRename: ((String) -> Void)?
    var onLink: ((String, Bool) -> Void)?
    var onReveal: ((Int) -> Void)?
    var onFind: (() -> Void)?
    /// The owner released a border: the new left and right edge in canvas units, nil where reset to the default.
    var onColumn: ((CGFloat?, CGFloat?) -> Void)?
    var onWidthPreview: (() -> Void)?
    var onScroll: ((Float, Float) -> Void)?
    var resolve: ((String) -> Bool)?
    var complete: ((String) -> [String])?
    /// The library's own commands (`slash.json`); the built-ins are in `SlashCatalog`.
    var slashCommands: [SlashCommand] = []
    /// `/ask` and `/draw`: the companion asks about the paragraph the caret is in.
    var onAsk: ((Bool) -> Void)?
    private var resizing = false
    private var resizingLeft = false
    private var resizeStartX: CGFloat = 0
    private var resizeStartEdge: CGFloat = 0
    /// The text column in canvas units. Defaults are today's layout (100 and the block width less 100); the
    /// owner's dragged edges are Mac preferences and never part of a note.
    static let defaultLeft: CGFloat = 100
    private(set) var columnLeft: CGFloat = 100
    private(set) var columnRight: CGFloat = 1500
    private var blockWidth: CGFloat = 1600
    var pageWidth: CGFloat = 1600
    var textLeft: CGFloat { isFree ? 0 : columnLeft }
    var textWidth: CGFloat { isFree ? max(200, pageWidth) : max(200, columnRight - columnLeft) }
    private func loadColumn() {
        guard !isFree else { pageWidth = blockWidth; return }
        let values = AppPreferences.shared.values
        let right = min(3000, max(800, values.columnRight.map { CGFloat($0) } ?? blockWidth - 100))
        let left = min(max(Self.defaultLeft, values.columnLeft.map { CGFloat($0) } ?? Self.defaultLeft), right - 600)
        columnLeft = left; columnRight = right; pageWidth = right + 100
    }
    override var isFlipped: Bool { true }

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        // A separate sibling layer; never rasterize the Metal layer or an ancestor of it.
        layer?.masksToBounds = false
        title.isBordered = false; title.drawsBackground = false
        title.font = DocumentLayout.titleFont; title.textColor = Theme.ink; title.delegate = self
        title.focusRingType = .none
        title.onDrag = { [weak self] dx, dy in self?.onMove?(dx, dy) }
        textView.isRichText = false; textView.importsGraphics = false
        textView.drawsBackground = false; textView.textContainerInset = .zero
        textView.textContainer?.lineFragmentPadding = 0
        textView.isVerticallyResizable = true; textView.isHorizontallyResizable = false
        textView.autoresizingMask = []; textView.allowsUndo = false
        textView.isAutomaticQuoteSubstitutionEnabled = false
        textView.isAutomaticDashSubstitutionEnabled = false
        textView.isAutomaticTextReplacementEnabled = true
        textView.isContinuousSpellCheckingEnabled = AppPreferences.shared.values.spellCheck
        textView.delegate = self
        textView.registerForDraggedTypes([.png, .tiff, .fileURL])
        textView.linkTextAttributes = [.cursor: NSCursor.pointingHand]
        textView.insertionPointColor = Theme.ink
        textView.onScroll = { [weak self] x, y in self?.onScroll?(x, y) }
        textView.onReveal = { [weak self] in self?.onReveal?($0) }
        textView.onLinkClick = { [weak self] link, newTab in self?.followLink(link, newTab: newTab) ?? false }
        textView.onHistory = { [weak self] redo in self?.flush(); self?.document?.history(redo: redo) }
        textView.onShortcut = { [weak self] name in self?.shortcut(name) }
        textView.onTask = { [weak self] offset in self?.flipBox(at: offset) }
        textView.onPasteLink = { [weak self] pasted in
            guard let self, self.document?.writable == true, let edit = MarkdownEditing.pasteLink(self.textView.string, selection: self.textView.selectedRange(), pasted: pasted) else { return false }
            self.apply(edit); return true
        }
        panel.isHidden = true
        panel.onChange = { [weak self] in self?.placeMenu() }
        addSubview(title); addSubview(textView)
        postsFrameChangedNotifications = true; postsBoundsChangedNotifications = true
        NotificationCenter.default.addObserver(self, selector: #selector(geometryChanged), name: NSView.frameDidChangeNotification, object: self)
        NotificationCenter.default.addObserver(self, selector: #selector(geometryChanged), name: NSView.boundsDidChangeNotification, object: self)
        NotificationCenter.default.addObserver(self, selector: #selector(preferencesChanged), name: AppPreferences.didChange, object: AppPreferences.shared)
    }
    @objc private func geometryChanged() { if menuOpen { placeMenu() } }
    @objc private func preferencesChanged() {
        textView.isContinuousSpellCheckingEnabled = AppPreferences.shared.values.spellCheck
        textView.insertionPointColor = Theme.ink
        if !resizing { loadColumn() }
        style(); onWidthPreview?()
    }
    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        textView.insertionPointColor = Theme.ink
        style()
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    /// Debug control only: told (with the new UTF-16 length) whenever the shown text is replaced from outside the field.
    nonisolated(unsafe) static var onTextReplaced: ((Int) -> Void)?

    func show(document: any TextDocument, title name: String, width: CGFloat) {
        let changedDocument = self.document !== document
        if changedDocument { flush() }
        self.document = document; if !resizing { blockWidth = width; loadColumn() }
        if title.currentEditor() == nil { title.stringValue = name }
        let permissionChanged = textView.isEditable != document.writable
        title.isEditable = document.writable
        textView.isEditable = document.writable
        textView.isSelectable = true
        if !textView.string.utf8.elementsEqual(document.text.utf8) {
            let old = textView.string, was = textView.selectedRange()
            quiet = true; textView.string = document.text; quiet = false
            Self.onTextReplaced?(textView.string.utf16.count)
            if textView.window?.firstResponder === textView || !old.isEmpty { textView.setSelectedRange(Self.carry(old, document.text, was)) }
            style()
        } else if changedDocument || permissionChanged || textView.textStorage?.length == 0 { style() }
        resizeText()
    }
    /// Drags a border to canvas x `edge` the way the mouse would.
    func debugDragWidth(_ edge: CGFloat, left: Bool = false) {
        guard let window else { return }
        func event(_ type: NSEvent.EventType, x: CGFloat) -> NSEvent? {
            NSEvent.mouseEvent(with: type, location: convert(CGPoint(x: x, y: 400), to: nil), modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, eventNumber: 1, clickCount: 1, pressure: 1)
        }
        let from = left ? columnLeft - 90 : columnRight + 90
        if let e = event(.leftMouseDown, x: from) { mouseDown(with: e) }
        if let e = event(.leftMouseDragged, x: from + (edge - (left ? columnLeft : columnRight))) { mouseDragged(with: e) }
        if let e = event(.leftMouseUp, x: from + (edge - (left ? columnLeft : columnRight))) { mouseUp(with: e) }
    }
    func debugDragFree(dx: CGFloat, dy: CGFloat) {
        guard let window else { return }
        func event(_ type: NSEvent.EventType, x: CGFloat, y: CGFloat) -> NSEvent? {
            NSEvent.mouseEvent(with: type, location: title.convert(CGPoint(x: x, y: y), to: nil), modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, eventNumber: 1, clickCount: 1, pressure: 1)
        }
        if let e = event(.leftMouseDown, x: 20, y: 20) { title.mouseDown(with: e) }
        if let e = event(.leftMouseDragged, x: 20 + dx, y: 20 + dy) { title.mouseDragged(with: e) }
        if let e = event(.leftMouseUp, x: 20 + dx, y: 20 + dy) { title.mouseUp(with: e) }
    }
    /// Where a selection goes when the text under it is replaced from outside (undo, sync): left alone before the
    /// change, shifted after it, at the end of the new words when it was inside them.
    static func carry(_ old: String, _ new: String, _ selection: NSRange) -> NSRange {
        let a = old as NSString, b = new as NSString
        var p = 0
        while p < min(a.length, b.length), a.character(at: p) == b.character(at: p) { p += 1 }
        var s = 0
        while s < min(a.length, b.length) - p, a.character(at: a.length - s - 1) == b.character(at: b.length - s - 1) { s += 1 }
        func map(_ x: Int) -> Int { x <= p ? x : x >= a.length - s ? x + b.length - a.length : b.length - s }
        let from = min(b.length, map(selection.location)), to = min(b.length, map(NSMaxRange(selection)))
        return NSRange(location: from, length: max(0, to - from))
    }
    func refreshPreview() { style() }
    func represents(_ document: any TextDocument) -> Bool { self.document === document }
    func flush() { pending?.cancel(); pending = nil; if dirtySince != 0 { dirtySince = 0; lastCommit = Self.uptime() }; document?.replace(with: textView.string); document?.flush() }
    private static func uptime() -> Double { Double(DispatchTime.now().uptimeNanoseconds) / 1e9 }
    func debugSetText(_ text: String) {
        guard document?.writable == true else { return }
        textView.string = text; textView.setSelectedRange(NSRange(location: text.utf16.count, length: 0)); changed()
    }
    func reveal(_ offset: Int) {
        let range = NSRange(location: min(offset, textView.string.utf16.count), length: 0)
        textView.setSelectedRange(range); window?.makeFirstResponder(textView); style()
    }
    func find() { onFind?() }
    override func layout() { super.layout(); resizeText() }
    private func resizeText() {
        let width = textWidth
        title.frame = isFree ? CGRect(x: 0, y: 0, width: 60, height: 42) : CGRect(x: columnLeft, y: 70, width: width, height: 110 * AppPreferences.shared.textScale)
        title.font = isFree ? .systemFont(ofSize: 32, weight: .medium) : DocumentLayout.titleFont
        textView.textContainer?.containerSize = CGSize(width: width, height: .greatestFiniteMagnitude)
        textView.layoutManager?.ensureLayout(for: textView.textContainer!)
        let height = max(1200, (textView.layoutManager?.usedRect(for: textView.textContainer!).height ?? 0) + 180)
        textView.frame = CGRect(x: textLeft, y: isFree ? 45 : 260, width: width, height: height)
    }
    private func style() {
        guard !styling, !textView.hasMarkedText() else { return }
        styling = true
        let selection = textView.selectedRange()
        textView.textStorage?.setAttributedString(DocumentLayout.attributed(textView.string, selection: document?.writable == true ? selection : nil, resolving: resolve, drawingHeights: drawingHeights, imageGaps: imageGaps, onCodeReady: { [weak self] in self?.style() }))
        textView.setSelectedRange(selection)
        textView.typingAttributes = [.font: NSFont.systemFont(ofSize: DocumentLayout.bodySize), .foregroundColor: Theme.ink]
        styling = false
        resizeText()
    }
    func textDidEndEditing(_ notification: Notification) { closeMenu(); flush(); onEndEditing?() }
    func textDidChange(_ notification: Notification) {
        guard !quiet else { return }
        // A numbered list counts on from its first number after any edit in it.
        if document?.writable == true, !textView.hasMarkedText() {
            let before = EditorEdit(text: textView.string, selection: textView.selectedRange())
            let fixed = MarkdownEditing.renumberAround(before)
            if fixed.text != before.text { quiet = true; textView.string = fixed.text; textView.setSelectedRange(fixed.selection); quiet = false }
        }
        changed()
    }
    private func changed() {
        onActivity?()
        document?.replace(with: textView.string)
        // Throttled, not debounced: a commit already waiting keeps its time, so a long burst still commits every ~150 ms.
        let now = Self.uptime()
        if dirtySince == 0 { dirtySince = now }
        if pending == nil {
            let work = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.flush() } }
            pending = work
            StrictTimer.run(after: TypingThrottle.delay(now: now, dirtySince: dirtySince, lastCommit: lastCommit), work)
        }
        style(); updateMenu(typing: true)
    }
    func textViewDidChangeSelection(_ notification: Notification) { if !styling && !quiet { style(); updateMenu(typing: false) } }
    func controlTextDidEndEditing(_ obj: Notification) { if document?.writable == true { onRename?(title.stringValue) } }
    func textView(_ textView: NSTextView, clickedOnLink link: Any, at charIndex: Int) -> Bool {
        followLink(link, newTab: NSEvent.modifierFlags.contains(.command))
    }
    private func followLink(_ link: Any, newTab: Bool) -> Bool {
        let raw = String(describing: link)
        let value = raw.removingPercentEncoding ?? raw
        if value.hasPrefix("syncr-wiki:") { onLink?(String(value.dropFirst(11)), newTab); return true }
        if let url = URL(string: value), ["https", "http", "mailto"].contains(url.scheme ?? "") { NSWorkspace.shared.open(url); return true }
        return false
    }
    private func apply(_ edit: EditorEdit) {
        guard document?.writable == true else { return }
        if edit.text == textView.string { textView.setSelectedRange(edit.selection); return }
        quiet = true; textView.string = edit.text; textView.setSelectedRange(edit.selection); quiet = false; changed()
    }
    /// A click on a rendered task box: the box flips, the caret stays where it was, no keyboard or focus change.
    private func flipBox(at offset: Int) {
        guard document?.writable == true, let flipped = MarkdownEditing.flipBox(textView.string, lineStart: offset) else { return }
        let selection = textView.selectedRange()
        quiet = true; textView.string = flipped; textView.setSelectedRange(selection); quiet = false; changed()
    }
    /// The palette's "Insert slash command": the command runs at the caret, with no token to remove.
    func insertSlash(_ command: EditorCommand) { run(command, token: SlashToken(range: NSRange(location: textView.selectedRange().location, length: 0), query: "")) }
    func shortcut(_ name: String) {
        let t = textView.string, s = textView.selectedRange()
        switch name {
        case "bold": apply(MarkdownEditing.toggle(t, selection: s, open: "**", close: "**"))
        case "italic": apply(MarkdownEditing.toggle(t, selection: s, open: "*", close: "*"))
        case "strike": apply(MarkdownEditing.toggle(t, selection: s, open: "~~", close: "~~"))
        case "highlight": apply(MarkdownEditing.toggle(t, selection: s, open: "==", close: "=="))
        case "inlinecode": apply(MarkdownEditing.toggle(t, selection: s, open: "`", close: "`"))
        case "link": apply(MarkdownEditing.link(t, selection: s))
        case "task": apply(MarkdownEditing.task(t, selection: s))
        case "find": find()
        default: break
        }
    }
    func textView(_ textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
        guard document?.writable == true else { return false }
        let s = textView.selectedRange(), t = textView.string
        switch commandSelector {
        case #selector(NSResponder.insertNewline(_:)) where menuOpen: panel.runSelected()
        case #selector(NSResponder.insertTab(_:)) where menuOpen: panel.runSelected()
        case #selector(NSResponder.insertNewline(_:)): apply(MarkdownEditing.enter(t, selection: s))
        case #selector(NSResponder.insertTab(_:)): apply(MarkdownEditing.indent(t, selection: s, outdent: false))
        case #selector(NSResponder.insertBacktab(_:)): apply(MarkdownEditing.indent(t, selection: s, outdent: true))
        case #selector(NSResponder.moveDown(_:)) where menuOpen: panel.move(1)
        case #selector(NSResponder.moveUp(_:)) where menuOpen: panel.move(-1)
        case #selector(NSResponder.cancelOperation(_:)) where menuOpen:
            if let token = menuToken { dismissedSlash = token.range.location }
            closeMenu()
        case #selector(NSResponder.deleteBackward(_:)):
            guard !textView.hasMarkedText(), let edit = MarkdownEditing.backspace(t, selection: s) else { return false }
            apply(edit)
        default: return false
        }
        return true
    }
    func textView(_ textView: NSTextView, shouldChangeTextIn affectedCharRange: NSRange, replacementString: String?) -> Bool {
        guard !quiet, let replacementString else { return true }
        let normalized = replacementString.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        if !normalized.utf8.elementsEqual(replacementString.utf8) {
            apply(MarkdownEditing.replace(textView.string, affectedCharRange, normalized)); return false
        }
        // Nothing is paired while the keyboard is composing a word.
        guard replacementString.utf16.count == 1, !textView.hasMarkedText(), let edit = MarkdownEditing.type(textView.string, selection: affectedCharRange, character: replacementString) else { return true }
        apply(edit); return false
    }

    // MARK: The command panel (editor-v2 §3.2)

    private var menuOpen: Bool { !panel.isHidden && (menuToken != nil || menuNote != nil) }
    private func closeMenu() {
        let wasOpen = !panel.isHidden
        menuToken = nil; menuNote = nil
        if wasOpen { panel.fade(in: false) { [weak self] in if self?.menuToken == nil && self?.menuNote == nil { self?.panel.isHidden = true } } }
    }
    /// Typing opens the menu; moving the caret only keeps it open or closes it.
    private func updateMenu(typing: Bool) {
        guard document?.writable == true, !textView.hasMarkedText(), !resizing else { closeMenu(); return }
        let t = textView.string, s = textView.selectedRange()
        if let d = dismissedSlash, (t as NSString).length <= d || (t as NSString).substring(with: NSRange(location: d, length: 1)) != "/" { dismissedSlash = nil }
        if let token = SlashEngine.token(t, selection: s) {
            if dismissedSlash == token.range.location || (!typing && !menuOpen) { closeMenu(); return }
            let found = SlashEngine.match(token.query, in: SlashCatalog.all(user: slashCommands))
            guard !found.isEmpty else { closeMenu(); return }
            let sameToken = menuToken?.range.location == token.range.location
            menuNote = nil; menuToken = token
            let rows = found.map { cmd in PanelRow(icon: cmd.icon, title: cmd.title, trailing: "/" + cmd.name, group: cmd.group, run: { [weak self] in self?.run(cmd, token: token) }) }
            present(rows, groups: token.query.isEmpty, keepSelection: sameToken)
            return
        }
        // [[ completion shares the panel: rows are note titles.
        let range = MarkdownEditing.lineRange(t, s)
        let prefix = (t as NSString).substring(with: NSRange(location: range.location, length: max(0, s.location - range.location)))
        if s.length == 0, let start = prefix.range(of: "[[", options: .backwards), !prefix[start.upperBound...].contains("]") {
            if !typing && !menuOpen { closeMenu(); return }
            let query = String(prefix[start.upperBound...])
            let location = range.location + prefix[..<start.lowerBound].utf16.count
            let titles = Array((complete?(query) ?? []).prefix(40))
            guard !titles.isEmpty else { closeMenu(); return }
            let same = menuNote == location
            menuToken = nil; menuNote = location
            let rows = titles.map { name in PanelRow(icon: "doc.text", title: name, trailing: "", group: "", run: { [weak self] in
                guard let self else { return }
                let ns = self.textView.string as NSString
                var end = self.textView.selectedRange().location
                while end < ns.length && ns.substring(with: NSRange(location: end, length: 1)) == "]" { end += 1 }
                self.closeMenu()
                self.apply(MarkdownEditing.replace(self.textView.string, NSRange(location: location, length: end - location), "[[\(name)]]"))
            }) }
            present(rows, groups: false, keepSelection: same)
            return
        }
        closeMenu()
    }
    private func present(_ rows: [PanelRow], groups: Bool, keepSelection: Bool) {
        if !keepSelection { panel.resetSelection() }
        panel.set(rows: rows, showGroups: groups)
        let wasHidden = panel.isHidden
        if panel.superview == nil || panel.superview !== superview { superview?.addSubview(panel) }
        panel.frame.size = CGSize(width: CommandPanel.width, height: panel.fittedHeight)
        placeMenu()
        if wasHidden { panel.fade(in: true) } else { panel.layer?.removeAnimation(forKey: "fade"); panel.layer?.opacity = 1; panel.isHidden = false }
    }
    /// Under the caret line with its left edge at the token's `/`; above the line when there is no room below.
    private func placeMenu() {
        guard let host = superview, let manager = textView.layoutManager, manager.numberOfGlyphs > 0 else { return }
        let at = menuToken?.range.location ?? menuNote ?? textView.selectedRange().location
        let character = min(max(0, at), textView.string.utf16.count - 1)
        let glyph = manager.glyphIndexForCharacter(at: character)
        let line = manager.lineFragmentRect(forGlyphAt: glyph, effectiveRange: nil)
        let location = manager.location(forGlyphAt: glyph)
        let topLeft = textView.convert(CGPoint(x: line.minX + location.x, y: line.minY), to: host)
        let bottom = textView.convert(CGPoint(x: line.minX, y: line.maxY), to: host).y
        let top = topLeft.y
        let size = CGSize(width: CommandPanel.width, height: panel.fittedHeight)
        let hostIsFlipped = host.isFlipped
        var y = hostIsFlipped ? bottom + 6 : bottom - 6 - size.height
        if hostIsFlipped ? y + size.height > host.bounds.height - 8 : y < 8 { y = hostIsFlipped ? top - 6 - size.height : top + 6 }
        let x = min(max(8, topLeft.x), max(8, host.bounds.width - size.width - 8))
        panel.frame = CGRect(x: x, y: y, width: size.width, height: size.height)
    }
    /// Removes the token and runs the command: one edit, saved on its own so undo gives the token back.
    func run(_ command: EditorCommand, token: SlashToken) {
        guard document?.writable == true else { return }
        flush()
        let outcome = SlashEngine.run(command, text: textView.string, token: token)
        closeMenu(); dismissedSlash = nil
        apply(outcome.edit)
        flush()
        window?.makeFirstResponder(textView)
        switch outcome.action {
        case .ask?: onAsk?(false)
        case .draw?: onAsk?(true)
        case .completeNote?: updateMenu(typing: true)
        case nil: break
        }
    }
    /// Debug and checks: what the panel lists right now, and a way to run a row.
    func debugMenu() -> [String] { menuOpen ? panel.debugRowTitles() : [] }
    func debugRunSelected() { panel.runSelected() }
    var debugPanel: CommandPanel { panel }
    override func draw(_ dirtyRect: NSRect) {
        guard !isFree else { return }
        NSColor.separatorColor.setStroke()
        let path = NSBezierPath()
        let top: CGFloat = resizing ? 0 : 40, bottom: CGFloat = resizing ? bounds.height : 100
        for x in [columnLeft - 90, columnRight + 90] { path.move(to: CGPoint(x: x, y: top)); path.line(to: CGPoint(x: x, y: bottom)) }
        path.lineWidth = 6; path.stroke()
    }
    /// Left of the left border mark and right of the right one: the strips that move the column's edges.
    private func border(at p: CGPoint) -> Bool? {
        guard !isFree else { return nil }
        if p.x < columnLeft - 50 { return true }
        if p.x > columnRight + 50 { return false }
        return nil
    }
    override func resetCursorRects() {
        guard !isFree else { return }
        addCursorRect(CGRect(x: 0, y: 0, width: max(0, columnLeft - 50), height: bounds.height), cursor: .resizeLeftRight)
        addCursorRect(CGRect(x: columnRight + 50, y: 0, width: 50, height: bounds.height), cursor: .resizeLeftRight)
    }
    override func mouseDown(with event: NSEvent) {
        let p = convert(event.locationInWindow, from: nil)
        if let left = border(at: p) {
            if event.clickCount == 2 { resetBorder(left: left) }
            else { resizing = true; resizingLeft = left; resizeStartX = p.x; resizeStartEdge = left ? columnLeft : columnRight; needsDisplay = true }
        } else if p.y > 180 { window?.makeFirstResponder(textView) }
    }
    private func resetBorder(left: Bool) {
        // Back to the default edge, saved as "no preference" for that edge only.
        try? AppPreferences.shared.update { if left { $0.columnLeft = nil } else { $0.columnRight = nil } }
    }
    override func mouseDragged(with event: NSEvent) {
        guard resizing else { return }
        let x = convert(event.locationInWindow, from: nil).x, moved = resizeStartEdge + (x - resizeStartX)
        if resizingLeft { columnLeft = min(max(Self.defaultLeft, moved), columnRight - 600) }
        else { columnRight = min(3000, max(columnLeft + 600, moved)) }
        pageWidth = columnRight + 100
        resizeText(); needsDisplay = true; window?.invalidateCursorRects(for: self); onWidthPreview?()
    }
    override func mouseUp(with event: NSEvent) {
        guard resizing else { return }
        resizing = false; needsDisplay = true
        onColumn?(resizingLeft ? columnLeft : nil, resizingLeft ? nil : columnRight)
    }
    override func scrollWheel(with event: NSEvent) {
        let (x, y) = AppPreferences.shared.canvasScroll(event)
        onScroll?(x, y)
    }
}

@MainActor final class MarkdownTextView: NSTextView {
    var onImage: ((Data, Int) -> Void)?
    var onPasteLink: ((String) -> Bool)?
    /// Where Paste reads from (the checks use a private one).
    var pasteboard = NSPasteboard.general
    override func paste(_ sender: Any?) {
        if isEditable, let data = NoteImages.pasted(pasteboard), let onImage { onImage(data, selectedRange().location); return }
        if isEditable, let string = pasteboard.string(forType: .string), onPasteLink?(string) == true { return }
        // NSTextView's own paste always reads the general pasteboard; honour the seam so a private one is read too.
        if pasteboard !== NSPasteboard.general { if isEditable { _ = readSelection(from: pasteboard) }; return }
        super.paste(sender)
    }
    override func draggingEntered(_ sender: any NSDraggingInfo) -> NSDragOperation {
        isEditable && onImage != nil && sender.draggingPasteboard.canReadItem(withDataConformingToTypes: ["public.image", "public.file-url"]) ? .copy : []
    }
    override func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        guard isEditable, let onImage, let data = NoteImages.pasted(sender.draggingPasteboard) else { return false }
        let point = convert(sender.draggingLocation, from: nil)
        let offset = characterIndexForInsertion(at: point)
        onImage(data, offset); return true
    }
    /// Passages a margin note answers: drawn with a thin muted underline, the only link to the margin.
    var passages: [NSRange] = [] { didSet { if passages != oldValue { needsDisplay = true } } }
    var onScroll: ((Float, Float) -> Void)?
    var onHistory: ((Bool) -> Void)?
    var onShortcut: ((String) -> Void)?
    var onTask: ((Int) -> Void)?
    var onLinkClick: ((Any, Bool) -> Bool)?
    func debugClick(_ offset: Int, newTab: Bool) {
        guard offset < string.utf16.count, let manager = layoutManager, let container = textContainer, let window else { return }
        let glyph = manager.glyphIndexForCharacter(at: offset)
        let rect = manager.boundingRect(forGlyphRange: NSRange(location: glyph, length: 1), in: container)
        if let event = NSEvent.mouseEvent(with: .leftMouseDown, location: convert(CGPoint(x: rect.midX, y: rect.midY), to: nil), modifierFlags: newTab ? .command : [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, eventNumber: 1, clickCount: 1, pressure: 1) { mouseDown(with: event) }
    }
    var onReveal: ((Int) -> Void)?
    override func scrollRangeToVisible(_ range: NSRange) { onReveal?(range.location) }
    override func scrollWheel(with event: NSEvent) {
        let (x, y) = AppPreferences.shared.canvasScroll(event)
        onScroll?(x, y)
    }
    // App shortcuts are dispatched by the window's binding registry before TextKit sees them.
    // Do not duplicate defaults here: doing so would keep old shortcuts alive after rebinding.
    @objc func undo(_ sender: Any?) { onHistory?(false) }
    @objc func redo(_ sender: Any?) { onHistory?(true) }
    override func drawBackground(in rect: NSRect) {
        super.drawBackground(in: rect)
        guard let manager = layoutManager else { return }
        DocumentLayout.backgrounds(text: string, manager: manager, width: bounds.width)
    }
    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        guard let manager = layoutManager else { return }
        DocumentLayout.decorations(text: string, manager: manager, width: bounds.width, selection: isEditable ? selectedRange() : nil)
        guard let container = textContainer else { return }
        Theme.muted.setFill()
        for passage in passages where NSMaxRange(passage) <= string.utf16.count {
            let glyphs = manager.glyphRange(forCharacterRange: passage, actualCharacterRange: nil)
            // One rule per visual line, just under the text (line spacing sits below the used rect's text).
            manager.enumerateEnclosingRects(forGlyphRange: glyphs, withinSelectedGlyphRange: NSRange(location: NSNotFound, length: 0), in: container) { rect, _ in
                NSRect(x: rect.minX, y: rect.maxY - 14, width: rect.width, height: 3).fill()
            }
        }
    }
    override func mouseDown(with event: NSEvent) {
        let p = convert(event.locationInWindow, from: nil)
        if let manager = layoutManager, let container = textContainer {
            let glyph = manager.glyphIndex(for: p, in: container)
            if glyph < manager.numberOfGlyphs {
                let offset = manager.characterIndexForGlyph(at: glyph)
                let rect = manager.boundingRect(forGlyphRange: NSRange(location: glyph, length: 1), in: container)
                if rect.contains(p), let storage = textStorage, offset < storage.length, let link = storage.attribute(.link, at: offset, effectiveRange: nil), onLinkClick?(link, event.modifierFlags.contains(.command)) == true { return }
                if let line = Markdown.lines(string).first(where: { NSLocationInRange(offset, $0.range) }), case .task = line.kind {
                    let indent = CGFloat(line.text.prefix(while: { $0 == " " || $0 == "\t" }).count) * 12
                    if p.x >= indent && p.x <= indent + 50 { onTask?(line.range.location); return }
                }
            }
        }
        super.mouseDown(with: event)
    }
}
@MainActor final class DragTextField: NSTextField {
    var onDrag: ((CGFloat, CGFloat) -> Void)?
    private var start: CGPoint?
    override func mouseDown(with event: NSEvent) {
        if !isEditable { start = superview?.convert(event.locationInWindow, from: nil) }
        else { super.mouseDown(with: event) }
    }
    override func mouseDragged(with event: NSEvent) {
        guard let start, let now = superview?.convert(event.locationInWindow, from: nil) else { return }
        // Only commit the movement on release; the editor stays responsive without disk work per pixel.
        toolTip = "Move \(Int(now.x - start.x)), \(Int(now.y - start.y))"
    }
    override func mouseUp(with event: NSEvent) {
        if let start, let now = superview?.convert(event.locationInWindow, from: nil) { onDrag?(now.x - start.x, now.y - start.y) }
        start = nil
    }
}
