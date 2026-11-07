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
    private let suggestions = NSTextField(labelWithString: "")
    private var document: (any TextDocument)?
    private var quiet = false
    private var styling = false
    private var pending: DispatchWorkItem?
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
    var slashCommands = SlashCommand.defaults
    private var choices: [(String, () -> Void)] = []
    private var choice = 0
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
        textView.onTask = { [weak self] offset in
            guard let self, self.document?.writable == true else { return }
            self.apply(MarkdownEditing.task(self.textView.string, selection: NSRange(location: offset, length: 0)))
        }
        suggestions.font = .systemFont(ofSize: 36); suggestions.textColor = Theme.muted
        suggestions.drawsBackground = true; suggestions.backgroundColor = .textBackgroundColor
        suggestions.isHidden = true
        addSubview(title); addSubview(textView); addSubview(suggestions)
        NotificationCenter.default.addObserver(self, selector: #selector(preferencesChanged), name: AppPreferences.didChange, object: AppPreferences.shared)
    }
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
            quiet = true; textView.string = document.text; quiet = false
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
    func refreshPreview() { style() }
    func represents(_ document: any TextDocument) -> Bool { self.document === document }
    func flush() { pending?.cancel(); pending = nil; document?.replace(with: textView.string); document?.flush() }
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
        suggestions.frame = CGRect(x: textLeft, y: choices.isEmpty ? min(height + 300, 700) : suggestions.frame.minY, width: width, height: 180)
    }
    private func style() {
        guard !styling, !textView.hasMarkedText() else { return }
        styling = true
        let selection = textView.selectedRange()
        textView.textStorage?.setAttributedString(DocumentLayout.attributed(textView.string, caret: document?.writable == true ? selection.location : nil, resolving: resolve, drawingHeights: drawingHeights, imageGaps: imageGaps))
        textView.setSelectedRange(selection)
        textView.typingAttributes = [.font: NSFont.systemFont(ofSize: DocumentLayout.bodySize), .foregroundColor: Theme.ink]
        styling = false
        resizeText()
    }
    func textDidEndEditing(_ notification: Notification) { flush(); onEndEditing?() }
    func textDidChange(_ notification: Notification) { if !quiet { changed() } }
    private func changed() {
        onActivity?()
        document?.replace(with: textView.string)
        pending?.cancel()
        let work = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.flush() } }
        pending = work; DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: work)
        style(); updateChoices()
    }
    func textViewDidChangeSelection(_ notification: Notification) { if !styling && !quiet { style(); updateChoices() } }
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
        quiet = true; textView.string = edit.text; textView.setSelectedRange(edit.selection); quiet = false; changed()
    }
    func insertSlash(_ command: SlashCommand) { apply(command.apply(textView.string, selection: textView.selectedRange())) }
    func shortcut(_ name: String) {
        let t = textView.string, s = textView.selectedRange()
        switch name {
        case "bold": apply(MarkdownEditing.wrap(t, selection: s, left: "**", right: "**"))
        case "italic": apply(MarkdownEditing.wrap(t, selection: s, left: "*", right: "*"))
        case "link": apply(MarkdownEditing.wrap(t, selection: s, left: "[", right: "](url)"))
        case "task": apply(MarkdownEditing.task(t, selection: s))
        case "find": find()
        default: break
        }
    }
    func textView(_ textView: NSTextView, doCommandBy commandSelector: Selector) -> Bool {
        guard document?.writable == true else { return false }
        let s = textView.selectedRange(), t = textView.string
        switch commandSelector {
        case #selector(NSResponder.insertNewline(_:)):
            if !choices.isEmpty { choices[choice].1(); choices = []; suggestions.isHidden = true }
            else { apply(MarkdownEditing.enter(t, selection: s)) }
        case #selector(NSResponder.insertTab(_:)): apply(MarkdownEditing.indent(t, selection: s, outdent: false))
        case #selector(NSResponder.insertBacktab(_:)): apply(MarkdownEditing.indent(t, selection: s, outdent: true))
        case #selector(NSResponder.moveDown(_:)) where !choices.isEmpty: choice = (choice + 1) % choices.count; showChoices()
        case #selector(NSResponder.moveUp(_:)) where !choices.isEmpty: choice = (choice + choices.count - 1) % choices.count; showChoices()
        case #selector(NSResponder.cancelOperation(_:)) where !choices.isEmpty: choices = []; suggestions.isHidden = true
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
        guard replacementString.utf16.count == 1 else { return true }
        let pairs = ["(": ")", "[": "]", "{": "}", "\"": "\"", "'": "'", "*": "*", "`": "`", "~": "~", "=": "="]
        let ns = textView.string as NSString
        let at = affectedCharRange.location
        if ["*", "~", "="].contains(replacementString), affectedCharRange.length == 0, at > 0, at < ns.length,
           ns.substring(with: NSRange(location: at - 1, length: 1)) == replacementString,
           ns.substring(with: NSRange(location: at, length: 1)) == replacementString,
           at == 1 || ns.substring(with: NSRange(location: at - 2, length: 1)).rangeOfCharacter(from: .alphanumerics) == nil {
            apply(MarkdownEditing.replace(textView.string, NSRange(location: at, length: 0), replacementString + replacementString, selection: NSRange(location: at + 1, length: 0))); return false
        }
        if affectedCharRange.length == 0, affectedCharRange.location < ns.length,
           [")", "]", "}", "\"", "'", "*", "`", "~", "="].contains(replacementString),
           ns.substring(with: NSRange(location: affectedCharRange.location, length: 1)) == replacementString {
            textView.setSelectedRange(NSRange(location: affectedCharRange.location + 1, length: 0)); return false
        }
        if let right = pairs[replacementString] {
            // Single apostrophes inside words are not Markdown pairs.
            if replacementString == "'", affectedCharRange.location > 0,
               (textView.string as NSString).substring(with: NSRange(location: affectedCharRange.location - 1, length: 1)).rangeOfCharacter(from: .letters) != nil { return true }
            apply(MarkdownEditing.wrap(textView.string, selection: affectedCharRange, left: replacementString, right: right)); return false
        }
        return true
    }
    private func updateChoices() {
        choices = []; choice = 0
        let s = textView.selectedRange(), t = textView.string as NSString
        let range = MarkdownEditing.lineRange(textView.string, s)
        let prefix = t.substring(with: NSRange(location: range.location, length: max(0, s.location - range.location)))
        if prefix.hasPrefix("/"), !prefix.contains(" ") {
            choices = SlashCommand.filter(prefix, commands: slashCommands).map { cmd in
                ("/" + cmd.name, { [weak self] in guard let self else { return }; self.apply(cmd.apply(self.textView.string, selection: self.textView.selectedRange())) })
            }
        } else if let start = prefix.range(of: "[[", options: .backwards), !prefix[start.upperBound...].contains("]") {
            let query = String(prefix[start.upperBound...])
            let location = range.location + prefix[..<start.lowerBound].utf16.count
            choices = (complete?(query) ?? []).map { name in
                (name, { [weak self] in guard let self else { return }
                    let ns = self.textView.string as NSString
                    var end = self.textView.selectedRange().location
                    while end < ns.length && ns.substring(with: NSRange(location: end, length: 1)) == "]" { end += 1 }
                    self.apply(MarkdownEditing.replace(self.textView.string, NSRange(location: location, length: end - location), "[[\(name)]]"))
                })
            }
        }
        showChoices()
    }
    private func showChoices() {
        suggestions.isHidden = choices.isEmpty
        suggestions.stringValue = choices.enumerated().prefix(5).map { ($0.offset == choice ? "› " : "  ") + $0.element.0 }.joined(separator: "\n")
        suggestions.maximumNumberOfLines = 5
        if !choices.isEmpty, let manager = textView.layoutManager, manager.numberOfGlyphs > 0 {
            let character = min(max(0, textView.selectedRange().location - 1), textView.string.utf16.count - 1)
            let glyph = manager.glyphIndexForCharacter(at: character)
            let rect = manager.lineFragmentRect(forGlyphAt: glyph, effectiveRange: nil)
            suggestions.frame.origin = CGPoint(x: textView.frame.minX, y: textView.frame.minY + rect.maxY + 16)
        }
    }
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
    override func paste(_ sender: Any?) {
        if isEditable, let data = NoteImages.pasted(.general), let onImage { onImage(data, selectedRange().location); return }
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
        DocumentLayout.decorations(text: string, manager: manager, width: bounds.width, caret: isEditable ? selectedRange().location : nil)
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
