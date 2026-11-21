import AppKit

/// The companion's formatted text: paragraphs, ordered and unordered lists, inline code and fenced code blocks.
/// Nothing else is interpreted, and an unterminated fence (a stream still arriving) is a code block to the end.
enum CompanionMarkup {
    enum Block: Equatable {
        case paragraph(String)
        case bullet(String)
        case numbered(Int, String)
        case code(language: String, text: String)
    }

    static func blocks(_ text: String) -> [Block] {
        var out: [Block] = []
        var paragraph: [String] = []
        var code: (language: String, lines: [String])?
        func flush() {
            if !paragraph.isEmpty { out.append(.paragraph(paragraph.joined(separator: " "))); paragraph = [] }
        }
        for raw in text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n") {
            if var open = code {
                if raw.trimmingCharacters(in: .whitespaces).hasPrefix("```") { out.append(.code(language: open.language, text: open.lines.joined(separator: "\n"))); code = nil }
                else { open.lines.append(raw); code = open }
                continue
            }
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("```") { flush(); code = (String(line.dropFirst(3)).trimmingCharacters(in: .whitespaces), []); continue }
            if line.isEmpty { flush(); continue }
            if let item = listItem(line) {
                flush()
                switch item { case .bullet, .numbered: out.append(item); default: break }
                continue
            }
            // A line indented under a list item continues it.
            if raw.hasPrefix("  "), let last = out.last {
                switch last {
                case .bullet(let t): out[out.count - 1] = .bullet(t + " " + line); continue
                case .numbered(let n, let t): out[out.count - 1] = .numbered(n, t + " " + line); continue
                default: break
                }
            }
            paragraph.append(line)
        }
        flush()
        if let open = code { out.append(.code(language: open.language, text: open.lines.joined(separator: "\n"))) }
        return out
    }

    private static func listItem(_ line: String) -> Block? {
        if line.hasPrefix("- ") || line.hasPrefix("* ") { return .bullet(String(line.dropFirst(2))) }
        var digits = ""
        for c in line { if c.isNumber, digits.count < 3 { digits.append(c) } else { break } }
        if !digits.isEmpty, let n = Int(digits) {
            let rest = line.dropFirst(digits.count)
            if rest.hasPrefix(". ") || rest.hasPrefix(") ") { return .numbered(n, String(rest.dropFirst(2))) }
        }
        return nil
    }

    static let codeBlock = NSAttributedString.Key("syncr.codeBlock")

    /// `size` is the body size in the caller's units; code is a little smaller, in the system monospace.
    @MainActor static func attributed(_ text: String, size: CGFloat, colour: NSColor = Theme.ink, font: NSFont? = nil) -> NSAttributedString {
        let body = font ?? Theme.serif(size)
        let mono = NSFont.monospacedSystemFont(ofSize: size * 0.86, weight: .regular)
        let out = NSMutableAttributedString()
        let gap = size * 0.55
        func paragraphStyle(indent: CGFloat = 0, hanging: CGFloat = 0, after: CGFloat, tabs: Bool = false) -> NSMutableParagraphStyle {
            let p = NSMutableParagraphStyle()
            p.firstLineHeadIndent = indent; p.headIndent = indent + hanging; p.paragraphSpacing = after; p.lineSpacing = size * 0.22
            if tabs { p.tabStops = [NSTextTab(textAlignment: .left, location: indent + hanging)]; p.defaultTabInterval = hanging }
            return p
        }
        func inline(_ s: String, _ attributes: [NSAttributedString.Key: Any]) -> NSAttributedString {
            let result = NSMutableAttributedString()
            let parts = s.components(separatedBy: "`")
            for (i, part) in parts.enumerated() where !part.isEmpty {
                // Odd parts sit between backticks; a final unmatched backtick leaves its text plain.
                if i % 2 == 1, i < parts.count - 1 {
                    var code = attributes; code[.font] = mono; code[.backgroundColor] = Theme.wash
                    result.append(NSAttributedString(string: part, attributes: code))
                } else { result.append(NSAttributedString(string: (i % 2 == 1 ? "`" : "") + part, attributes: attributes)) }
            }
            return result
        }
        let blocks = blocks(text)
        var codeID = 0
        for (index, block) in blocks.enumerated() {
            let last = index == blocks.count - 1
            switch block {
            case .paragraph(let t):
                out.append(inline(t + (last ? "" : "\n"), [.font: body, .foregroundColor: colour, .paragraphStyle: paragraphStyle(after: gap)]))
            case .bullet(let t):
                out.append(inline("•\t" + t + (last ? "" : "\n"), [.font: body, .foregroundColor: colour, .paragraphStyle: paragraphStyle(indent: size * 0.2, hanging: size * 1.0, after: gap * 0.45, tabs: true)]))
            case .numbered(let n, let t):
                out.append(inline("\(n).\t" + t + (last ? "" : "\n"), [.font: body, .foregroundColor: colour, .paragraphStyle: paragraphStyle(indent: size * 0.2, hanging: size * 1.3, after: gap * 0.45, tabs: true)]))
            case .code(let language, let t):
                codeID += 1
                let style = paragraphStyle(indent: size * 0.7, after: 0)
                style.tailIndent = -size * 0.7; style.lineSpacing = size * 0.12
                // The lines are one block: the last newline carries the spacing after it.
                let lines = t.components(separatedBy: "\n")
                // A reply's block is small, so it is highlighted in place; a huge one stays plain.
                let spans = t.utf8.count <= 16_384 ? CodeHighlight.spans(language: language, code: t) ?? [] : []
                var codeOffset = 0
                for (i, line) in lines.enumerated() {
                    let lineStart = out.length
                    let end = i == lines.count - 1
                    let p = style.mutableCopy() as! NSMutableParagraphStyle
                    if i == 0 { p.paragraphSpacingBefore = size * 0.5 }
                    if end { p.paragraphSpacing = size * 0.9 }
                    out.append(NSAttributedString(string: (line.isEmpty ? " " : line) + (end && last ? "" : "\n"), attributes: [.font: mono, .foregroundColor: colour, .paragraphStyle: p, codeBlock: codeID]))
                    let length = (line as NSString).length
                    for span in spans where Int(span.end) > codeOffset && Int(span.start) < codeOffset + length && !CodeHighlight.isPlain(span.token) {
                        let from = max(Int(span.start), codeOffset), to = min(Int(span.end), codeOffset + length)
                        let range = NSRange(location: lineStart + from - codeOffset, length: to - from)
                        out.addAttribute(.foregroundColor, value: CodeHighlight.colour(span.token), range: range)
                        if CodeColours.isItalic(span.token) { out.addAttribute(.obliqueness, value: 0.16, range: range) }
                    }
                    codeOffset += length + 1
                }
            }
        }
        // Trailing spacing is not wanted below the last block.
        return out
    }
}

/// Selectable, copyable companion text at a fixed width. Code blocks sit on a quiet rounded fill from the theme.
@MainActor final class RichTextView: NSTextView {
    private var codePad: CGFloat = 0
    override var isFlipped: Bool { true }

    convenience init() {
        let storage = NSTextStorage(), manager = NSLayoutManager(), container = NSTextContainer(size: CGSize(width: 100, height: CGFloat.greatestFiniteMagnitude))
        storage.addLayoutManager(manager); manager.addTextContainer(container)
        self.init(frame: .zero, textContainer: container)
        drawsBackground = false; isEditable = false; isSelectable = true
        textContainerInset = .zero; textContainer?.lineFragmentPadding = 0
        isVerticallyResizable = false; isHorizontallyResizable = false
        textContainer?.widthTracksTextView = false
    }
    /// Lays the text out at `width` and returns the height it needs, in the units of `size`.
    @discardableResult func show(_ text: String, size: CGFloat, width: CGFloat, colour: NSColor = Theme.ink, font: NSFont? = nil) -> CGFloat {
        codePad = size * 0.4
        textStorage?.setAttributedString(CompanionMarkup.attributed(text, size: size, colour: colour, font: font))
        textContainer?.containerSize = CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        guard let manager = layoutManager, let container = textContainer else { return 0 }
        manager.ensureLayout(for: container)
        let height = ceil(manager.usedRect(for: container).height + codePad)
        frame = CGRect(x: frame.minX, y: frame.minY, width: width, height: height)
        needsDisplay = true
        return height
    }
    /// One rectangle per fenced block, as laid out, in this view's coordinates.
    func codeRects() -> [CGRect] {
        guard let storage = textStorage, let manager = layoutManager, let container = textContainer else { return [] }
        var rects: [CGRect] = []
        storage.enumerateAttribute(CompanionMarkup.codeBlock, in: NSRange(location: 0, length: storage.length)) { value, range, _ in
            guard value != nil else { return }
            let glyphs = manager.glyphRange(forCharacterRange: range, actualCharacterRange: nil)
            let box = manager.boundingRect(forGlyphRange: glyphs, in: container)
            rects.append(CGRect(x: 0, y: box.minY - codePad, width: bounds.width, height: box.height + codePad * 2))
        }
        return rects
    }
    override func drawBackground(in rect: NSRect) {
        super.drawBackground(in: rect)
        for r in codeRects() {
            let path = NSBezierPath(roundedRect: r, xRadius: codePad * 0.8, yRadius: codePad * 0.8)
            Theme.wash.setFill(); path.fill()
            Theme.hairline.setStroke(); path.lineWidth = max(0.5, codePad * 0.04); path.stroke()
        }
    }
    override func viewDidChangeEffectiveAppearance() { super.viewDidChangeEffectiveAppearance(); needsDisplay = true }
    override func scrollWheel(with event: NSEvent) { nextResponder?.scrollWheel(with: event) }
}

/// The companion's answer to a direct question, set on the page directly under the asked passage in canvas units.
/// A left rule and the serif mark it as the companion's, not the note's own text. Long answers show their first part
/// and open larger on a click.
@MainActor final class AnswerCard: NSView {
    static let textSize: CGFloat = 42
    static let maxText: CGFloat = 1000
    private(set) var fittedHeight: CGFloat = 0
    private(set) var truncated = false
    let body = RichTextView()
    private let clip = FlippedView()
    private var head: NSTextField?
    private var popup: (() -> Void)?
    override var isFlipped: Bool { true }

    /// `step` is the named step while the model works; nil once the answer is final.
    init(text: String, step: String?, width: CGFloat, caption: String = "Syncr", more: [(String, String)] = [],
         writeBack: (() -> Void)? = nil, dismiss: (() -> Void)? = nil, read: (() -> Void)? = nil, open: (() -> Void)? = nil) {
        super.init(frame: .zero)
        popup = open
        let inset: CGFloat = 40, inner = max(200, width - inset)
        let head = NSTextField(labelWithString: step.map { caption + " · " + $0 } ?? caption)
        head.font = .systemFont(ofSize: 32); head.textColor = step == nil ? Theme.muted : Theme.ink
        head.frame = CGRect(x: inset, y: 0, width: inner, height: 44)
        addSubview(head); self.head = head
        var y: CGFloat = 52
        if text.isEmpty, step != nil {
            // Nothing written yet: stipple lines hold the place where the words will be.
            let waiting = DitherLines(width: min(inner, 900), lines: 3, lineHeight: 26, gap: 32)
            waiting.frame.origin = CGPoint(x: inset, y: y + 16)
            addSubview(waiting); y += waiting.frame.height + 36
        }
        let shown = text.isEmpty ? "" : text
        var height = shown.isEmpty ? 0 : body.show(shown, size: Self.textSize, width: inner)
        // Follow-ups from the owner and further replies, after the answer proper.
        var extras: [(RichTextView, CGFloat)] = []
        for (who, message) in more {
            let view = RichTextView()
            let h = view.show(message, size: Self.textSize * 0.9, width: inner, colour: who == "You" ? Theme.soft : Theme.ink)
            extras.append((view, h))
        }
        truncated = height > Self.maxText && extras.isEmpty
        let visible = truncated ? Self.maxText : height
        clip.frame = CGRect(x: inset, y: y, width: inner, height: visible)
        clip.wantsLayer = true; clip.layer?.masksToBounds = true
        if !shown.isEmpty { body.frame.origin = .zero; clip.addSubview(body) }
        addSubview(clip)
        y += visible
        if truncated { height = visible }
        for (view, h) in extras {
            y += 22
            view.frame = CGRect(x: inset, y: y, width: inner, height: h); addSubview(view); y += h
        }
        y += 18
        var actions: [NSView] = []
        if truncated, let open { actions.append(ActionButton(link: "Open larger", size: 32, action: open)) }
        if let writeBack { actions.append(ActionButton(link: "Write back…", size: 32, action: writeBack)) }
        if let read { actions.append(ActionButton(link: "Read aloud", size: 32, action: read)) }
        if let dismiss { actions.append(ActionButton(link: "Dismiss", size: 32, action: dismiss)) }
        if !actions.isEmpty {
            let row = NSStackView(views: actions); row.spacing = 36
            row.frame = CGRect(x: inset, y: y, width: inner, height: 40); addSubview(row); y += 44
        }
        fittedHeight = y + 8
        frame.size = CGSize(width: width, height: fittedHeight)
        if truncated {
            let click = NSClickGestureRecognizer(target: self, action: #selector(clicked))
            clip.addGestureRecognizer(click)
        }
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    @objc private func clicked() { popup?() }
    /// The first words of the answer dissolve in.
    func revealWords(elapsed: CFTimeInterval = 0) { Dither.reveal(clip, elapsed: elapsed) }
    /// The step's name changed.
    func fadeStep() { if let head { Dither.fade(head) } }
    override func draw(_ dirtyRect: NSRect) {
        Theme.hairlineStrong.setFill()
        NSBezierPath(roundedRect: CGRect(x: 8, y: 6, width: 6, height: max(0, bounds.height - 12)), xRadius: 3, yRadius: 3).fill()
    }
    override func viewDidChangeEffectiveAppearance() { super.viewDidChangeEffectiveAppearance(); needsDisplay = true }
}

/// A long answer, larger, in a window of its own. Text stays selectable; "Write back…" continues the thread.
@MainActor enum AnswerPopup {
    private static var windows: [String: NSWindow] = [:]
    static func show(id: String, title: String, messages: [(String, String)], writeBack: (() -> Void)?) {
        let window = windows[id] ?? NSWindow(contentRect: CGRect(x: 0, y: 0, width: 720, height: 680), styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = title; window.isReleasedWhenClosed = false; window.backgroundColor = Theme.page; window.minSize = CGSize(width: 420, height: 320)
        let scroll = NSScrollView(); scroll.hasVerticalScroller = true; scroll.drawsBackground = false
        let width: CGFloat = 640
        let document = FlippedView(frame: .zero)
        var y: CGFloat = 20
        for (who, text) in messages {
            let label = NSTextField(labelWithString: who); label.font = .systemFont(ofSize: 12, weight: .medium); label.textColor = Theme.muted
            label.frame = CGRect(x: 32, y: y, width: width, height: 16); document.addSubview(label); y += 22
            let view = RichTextView()
            let h = view.show(text, size: 16, width: width, colour: who == "You" ? Theme.soft : Theme.ink)
            view.frame.origin = CGPoint(x: 32, y: y); document.addSubview(view); y += h + 22
        }
        if let writeBack {
            let button = ActionButton("Write back…", action: writeBack); button.frame = CGRect(x: 32, y: y, width: 120, height: 24)
            document.addSubview(button); y += 40
        }
        document.frame = CGRect(x: 0, y: 0, width: width + 64, height: y + 20)
        scroll.documentView = document
        window.contentView = scroll
        if !window.isVisible { window.center() }
        window.makeKeyAndOrderFront(nil); windows[id] = window
    }
}

final class FlippedView: NSView { override var isFlipped: Bool { true } }
