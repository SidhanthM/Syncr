import AppKit
import SyncrCore

/// Canvas units throughout; no view or core handle is required.
struct DocumentPage: Equatable {
    var title: String
    var text: String
    var x: CGFloat = 100
    var y: CGFloat = 260
    var width: CGFloat = 1400
    var drawingHeights: [String: CGFloat] = [:]
    /// Space left after the paragraph holding each UTF-16 offset, for stored images placed under it.
    var imageGaps: [Int: CGFloat] = [:]
}
struct DocumentLayoutLine: Equatable {
    var range: NSRange
    var text: String
    var rect: CGRect
}

@MainActor enum DocumentLayout {
    static var bodySize: CGFloat { 48 * AppPreferences.shared.textScale }
    /// Headings are only a little larger than the body, medium weight and tightly tracked (rework-v1 §T).
    static func headingFont(_ level: Int) -> NSFont {
        let scale: CGFloat = [1.5, 1.3, 1.15][safe: level - 1] ?? 1.05
        return NSFont.systemFont(ofSize: (bodySize * scale).rounded(), weight: .medium)
    }
    static var titleFont: NSFont { .systemFont(ofSize: 76 * AppPreferences.shared.textScale, weight: .medium) }
    static func page(note: NoteSnapshot, width: CGFloat) -> DocumentPage? {
        guard let block = note.textBlocks.first(where: { $0.role == .document && !$0.tombstone }) else { return nil }
        let sheet = note.sheets.first { $0.id == block.sheetId }
        return DocumentPage(title: note.title, text: block.text,
                            x: CGFloat(sheet?.originX ?? 0) + CGFloat(block.originX) + CGFloat(block.transform.tx) + 100,
                            y: CGFloat(sheet?.originY ?? 0) + CGFloat(block.originY) + CGFloat(block.transform.ty) + 260,
                            width: max(1, width - 200))
    }
    static func lines(note: NoteSnapshot, width: CGFloat) -> [DocumentLayoutLine] {
        guard let page = page(note: note, width: width) else { return [] }
        return lines(note: page, width: page.width)
    }
    static func image(note: NoteSnapshot, width: CGFloat, scale: CGFloat = 0.5) -> NSImage? {
        guard let page = page(note: note, width: width) else { return nil }
        return image(note: page, width: page.width, scale: scale)
    }
    /// Which lines show their syntax: every line the selection (or the caret) touches, and the whole of a fenced block
    /// the selection is inside. Indices into `lines`.
    static func activeLines(_ lines: [MarkdownLine], selection: NSRange?) -> Set<Int> {
        guard let s = selection else { return [] }
        func touches(_ r: NSRange) -> Bool {
            s.length == 0 ? (s.location >= r.location && s.location <= NSMaxRange(r))
                : (NSIntersectionRange(s, NSRange(location: r.location, length: r.length + 1)).length > 0)
        }
        var active = Set<Int>(), openAt: Int?
        for (i, line) in lines.enumerated() {
            if touches(line.range) { active.insert(i) }
            if case .fence = line.kind {
                if let from = openAt {
                    if (from...i).contains(where: { active.contains($0) }) { (from...i).forEach { active.insert($0) } }
                    openAt = nil
                } else { openAt = i }
            }
        }
        if let from = openAt, (from..<lines.count).contains(where: { active.contains($0) }) { (from..<lines.count).forEach { active.insert($0) } }
        return active
    }
    static func attributed(_ text: String, caret: Int? = nil, selection: NSRange? = nil, resolving: ((String) -> Bool)? = nil, drawingHeights: [String: CGFloat] = [:], imageGaps: [Int: CGFloat] = [:]) -> NSAttributedString {
        let out = NSMutableAttributedString(string: text, attributes: [.font: NSFont.systemFont(ofSize: bodySize), .foregroundColor: Theme.ink])
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = bodySize * AppPreferences.shared.values.lineSpacing.multiplier
        paragraph.paragraphSpacing = 10 * AppPreferences.shared.textScale
        out.addAttribute(.paragraphStyle, value: paragraph, range: NSRange(location: 0, length: out.length))
        for (offset, gap) in imageGaps where offset >= 0 && offset <= out.length && out.length > 0 {
            let range = (out.string as NSString).paragraphRange(for: NSRange(location: min(offset, out.length - 1), length: 0))
            let spaced = (paragraph.mutableCopy() as! NSMutableParagraphStyle); spaced.paragraphSpacing = paragraph.paragraphSpacing + gap
            out.addAttribute(.paragraphStyle, value: spaced, range: range)
        }
        func hide(_ r: NSRange) { if r.length > 0 { out.addAttributes([.font: NSFont.systemFont(ofSize: 0.01), .foregroundColor: NSColor.clear], range: r) } }
        let parsed = Markdown.lines(text)
        let activeSet = activeLines(parsed, selection: selection ?? caret.map { NSRange(location: $0, length: 0) })
        for (lineIndex, line) in parsed.enumerated() {
            let active = activeSet.contains(lineIndex)
            let raw = line.text.trimmingCharacters(in: .whitespacesAndNewlines)
            // A line keeps the height of its own type whether its syntax shows or not.
            let lineFont: NSFont
            switch line.kind {
            case .heading(let level): lineFont = headingFont(level)
            case .fence: lineFont = NSFont.monospacedSystemFont(ofSize: 34 * AppPreferences.shared.textScale, weight: .regular)
            case .code: lineFont = NSFont.monospacedSystemFont(ofSize: 42 * AppPreferences.shared.textScale, weight: .regular)
            default: lineFont = NSFont.systemFont(ofSize: bodySize)
            }
            if line.range.length > 0, !(raw.hasPrefix("![[syncr-drawing:") && raw.hasSuffix("]]")) {
                let current = (out.attribute(.paragraphStyle, at: line.range.location, effectiveRange: nil) as? NSParagraphStyle) ?? paragraph
                let fixed = (current.mutableCopy() as! NSMutableParagraphStyle)
                fixed.minimumLineHeight = (lineFont.ascender - lineFont.descender + lineFont.leading).rounded(.up)
                out.addAttribute(.paragraphStyle, value: fixed, range: line.range)
            }
            if raw.hasPrefix("![[syncr-drawing:"), raw.hasSuffix("]]"), let height = drawingHeights[String(raw.dropFirst(17).dropLast(2))] {
                let embed = NSMutableParagraphStyle(); embed.minimumLineHeight = height; embed.maximumLineHeight = height
                out.addAttributes([.paragraphStyle: embed, .font: NSFont.systemFont(ofSize: active ? 14 : 0.01), .foregroundColor: active ? Theme.muted : NSColor.clear], range: line.range)
            }
            var hiddenPrefix = false
            switch line.kind {
            case .heading(let level):
                let font = headingFont(level)
                out.addAttributes([.font: font, .kern: -0.02 * font.pointSize], range: line.range); hiddenPrefix = true
            case .quote: out.addAttribute(.foregroundColor, value: Theme.soft, range: line.range); hiddenPrefix = true
            case .task, .bullet, .numbered: hiddenPrefix = true
            case .fence:
                // Hidden fences keep their font (and so the line's height); only the ink goes.
                out.addAttributes([.font: lineFont, .foregroundColor: active ? Theme.muted : NSColor.clear], range: line.range)
            case .code(let language):
                out.addAttribute(.font, value: NSFont.monospacedSystemFont(ofSize: 42 * AppPreferences.shared.textScale, weight: .regular), range: line.range)
                colourCode(line, language: language, into: out)
            case .rule: out.addAttribute(.foregroundColor, value: active ? Theme.muted : NSColor.clear, range: line.range)
            default: break
            }
            if active, line.prefix.length > 0 {
                switch line.kind {
                case .heading, .quote, .task, .bullet, .numbered: out.addAttribute(.foregroundColor, value: Theme.muted, range: line.prefix)
                default: break
                }
            }
            if hiddenPrefix && !active {
                switch line.kind {
                case .task:
                    // Monospaced so "[ ]" and "[x]" take the same width and checked text lines up.
                    out.addAttributes([.foregroundColor: NSColor.clear, .font: NSFont.monospacedSystemFont(ofSize: 16, weight: .regular)], range: line.prefix)
                case .bullet, .numbered, .quote:
                    out.addAttribute(.foregroundColor, value: NSColor.clear, range: line.prefix)
                default: hide(line.prefix)
                }
            }
            for span in line.spans {
                switch span.kind {
                case .bold: out.addAttribute(.font, value: NSFont.boldSystemFont(ofSize: bodySize), range: span.range)
                case .italic: out.addAttribute(.font, value: NSFontManager.shared.convert(.systemFont(ofSize: bodySize), toHaveTrait: .italicFontMask), range: span.range)
                case .strike: out.addAttribute(.strikethroughStyle, value: NSUnderlineStyle.single.rawValue, range: span.range)
                case .highlight: out.addAttribute(.backgroundColor, value: NSColor.systemYellow.withAlphaComponent(0.25), range: span.range)
                case .code: out.addAttributes([.font: NSFont.monospacedSystemFont(ofSize: 44 * AppPreferences.shared.textScale, weight: .regular), .backgroundColor: NSColor.quaternaryLabelColor], range: span.range)
                // Colour means state only: links and tags are ink with an underline.
                case .link(let url): out.addAttributes([.link: url, .foregroundColor: Theme.ink, .underlineStyle: NSUnderlineStyle.single.rawValue, .underlineColor: Theme.muted], range: span.range)
                case .wiki(let title):
                    let missing = resolving?(title) == false
                    out.addAttributes([.link: "syncr-wiki:" + title, .foregroundColor: missing ? Theme.muted : Theme.ink,
                                       .underlineStyle: NSUnderlineStyle.single.rawValue | (missing ? NSUnderlineStyle.patternDot.rawValue : 0), .underlineColor: Theme.muted], range: span.range)
                case .tag: out.addAttributes([.foregroundColor: Theme.ink, .underlineStyle: NSUnderlineStyle.single.rawValue, .underlineColor: Theme.hairlineStrong], range: span.range)
                }
                if !active { span.syntax.forEach(hide) } else { span.syntax.forEach { if $0.length > 0 { out.addAttribute(.foregroundColor, value: Theme.muted, range: $0) } } }
            }
        }
        return out
    }
    private static func colourCode(_ line: MarkdownLine, language: String, into out: NSMutableAttributedString) {
        guard ["swift", "rust", "rs", "python", "py", "javascript", "js", "typescript", "ts", "json", "sh", "bash", "kotlin"].contains(language.lowercased()) else { return }
        for (pattern, colour) in [("\\b(let|var|func|fn|class|struct|enum|if|else|return|for|while|in|import|from|def|const|true|false|null|nil|pub|use|val|fun)\\b", NSColor.systemPurple), ("\"[^\"]*\"|'[^']*'", .systemGreen), ("\\b[0-9]+(?:\\.[0-9]+)?\\b", .systemOrange), ("//.*$|#.*$", .secondaryLabelColor)] {
            for m in Markdown.matches(pattern, line.text) { out.addAttribute(.foregroundColor, value: colour, range: NSRange(location: line.range.location + m.range.location, length: m.range.length)) }
        }
    }
    static func decorations(text: String, manager: NSLayoutManager, origin: CGPoint = .zero, width: CGFloat, caret: Int? = nil, selection: NSRange? = nil) {
        let parsed = Markdown.lines(text)
        let activeSet = activeLines(parsed, selection: selection ?? caret.map { NSRange(location: $0, length: 0) })
        for (lineIndex, line) in parsed.enumerated() where line.range.length > 0 {
            let glyphs = manager.glyphRange(forCharacterRange: line.range, actualCharacterRange: nil)
            guard glyphs.length > 0 else { continue }
            let rect = manager.lineFragmentRect(forGlyphAt: glyphs.location, effectiveRange: nil).offsetBy(dx: origin.x, dy: origin.y)
            let active = activeSet.contains(lineIndex)
            let indent = CGFloat(line.text.prefix(while: { $0 == " " || $0 == "\t" }).count) * 12
            func label(_ text: String, size: CGFloat = 48, x: CGFloat = 0) {
                (text as NSString).draw(at: CGPoint(x: origin.x + x, y: rect.minY), withAttributes: [.font: NSFont.systemFont(ofSize: size), .foregroundColor: Theme.muted])
            }
            switch line.kind {
            case .fence(let language) where !language.isEmpty && !active:
                let size = (language as NSString).size(withAttributes: [.font: NSFont.systemFont(ofSize: 30)])
                label(language, size: 30, x: max(0, width - size.width))
            case .bullet where !active: label("•", x: indent)
            case .numbered(let n) where !active: label("\(n).", x: indent)
            case .quote: NSColor.separatorColor.setFill(); CGRect(x: origin.x, y: rect.minY + 4, width: 5, height: rect.height - 8).fill()
            case .task(let checked) where !active:
                let box = CGRect(x: origin.x + indent, y: rect.minY + 10, width: 34, height: 34)
                Theme.muted.setStroke(); NSBezierPath(roundedRect: box, xRadius: 6, yRadius: 6).stroke()
                if checked { ("✓" as NSString).draw(in: box.offsetBy(dx: 5, dy: -2), withAttributes: [.font: NSFont.systemFont(ofSize: 30, weight: .medium), .foregroundColor: Theme.ink]) }
            case .rule(let type) where !active:
                let path = NSBezierPath(); path.move(to: CGPoint(x: origin.x, y: rect.midY)); path.line(to: CGPoint(x: origin.x + width, y: rect.midY)); path.lineWidth = 3
                if type != "---" { path.setLineDash(type == "..." ? [3, 10] : [24, 15], count: 2, phase: 0) }
                NSColor.separatorColor.setStroke(); path.stroke()
            default: break
            }
        }
    }
    /// Code block fills, drawn behind the glyphs: one rounded block per fence, slightly wider than the text column.
    static func backgrounds(text: String, manager: NSLayoutManager, origin: CGPoint = .zero, width: CGFloat) {
        var block: CGRect?
        func flush() {
            guard let rect = block else { return }
            NSColor.quaternaryLabelColor.withAlphaComponent(0.12).setFill()
            NSBezierPath(roundedRect: CGRect(x: origin.x - 24, y: rect.minY, width: width + 48, height: rect.height), xRadius: 14, yRadius: 14).fill()
            block = nil
        }
        for line in Markdown.lines(text) {
            switch line.kind {
            case .code, .fence:
                let glyphs = manager.glyphRange(forCharacterRange: NSRange(location: line.range.location, length: max(1, line.range.length)), actualCharacterRange: nil)
                guard glyphs.location < manager.numberOfGlyphs else { continue }
                let rect = manager.lineFragmentRect(forGlyphAt: glyphs.location, effectiveRange: nil).offsetBy(dx: origin.x, dy: origin.y)
                let last = manager.lineFragmentRect(forGlyphAt: max(glyphs.location, NSMaxRange(glyphs) - 1), effectiveRange: nil).offsetBy(dx: origin.x, dy: origin.y)
                block = (block ?? rect).union(rect).union(last)
                if case .fence = line.kind, block != rect.union(last) { flush() }
            default: flush()
            }
        }
        flush()
    }
    private static func engine(_ page: DocumentPage, width: CGFloat) -> (NSTextStorage, NSLayoutManager, NSTextContainer) {
        let storage = NSTextStorage(attributedString: attributed(page.text, drawingHeights: page.drawingHeights, imageGaps: page.imageGaps))
        let manager = NSLayoutManager(), container = NSTextContainer(size: CGSize(width: max(1, width), height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        manager.addTextContainer(container); storage.addLayoutManager(manager); manager.ensureLayout(for: container)
        return (storage, manager, container)
    }
    /// note + wrap width -> visual lines, UTF-16 source ranges and top-left canvas rectangles.
    static func lines(note: DocumentPage, width: CGFloat) -> [DocumentLayoutLine] {
        let (storage, manager, _) = engine(note, width: width)
        var lines: [DocumentLayoutLine] = []
        manager.enumerateLineFragments(forGlyphRange: NSRange(location: 0, length: manager.numberOfGlyphs)) { _, used, _, glyphs, _ in
            let range = manager.characterRange(forGlyphRange: glyphs, actualGlyphRange: nil)
            lines.append(DocumentLayoutLine(range: range, text: (storage.string as NSString).substring(with: range), rect: used.offsetBy(dx: note.x, dy: note.y)))
        }
        return lines
    }
    /// Same TextKit engine as the editor; document only, not ink or the reserved margin.
    static func image(note: DocumentPage, width: CGFloat, scale: CGFloat = 0.5, region: CGRect? = nil) -> NSImage {
        let (storage, manager, container) = engine(note, width: width)
        defer { withExtendedLifetime(storage) {} }
        let height = max(400, manager.usedRect(for: container).maxY + note.y + 100)
        let crop = region ?? CGRect(x:0,y:0,width:width + note.x * 2,height:height)
        let image = NSImage(size: CGSize(width: crop.width * scale, height: crop.height * scale))
        image.lockFocusFlipped(true)
        NSColor.textBackgroundColor.setFill(); CGRect(origin: .zero, size: image.size).fill()
        let transform = NSAffineTransform(); transform.scale(by: scale); transform.translateX(by:-crop.minX,yBy:-crop.minY); transform.concat()
        (note.title as NSString).draw(at: CGPoint(x: note.x, y: 80), withAttributes: [.font: titleFont, .kern: -1.5, .foregroundColor: Theme.ink])
        let range = NSRange(location: 0, length: manager.numberOfGlyphs)
        backgrounds(text: note.text, manager: manager, origin: CGPoint(x: note.x, y: note.y), width: width)
        manager.drawBackground(forGlyphRange: range, at: CGPoint(x: note.x, y: note.y))
        manager.drawGlyphs(forGlyphRange: range, at: CGPoint(x: note.x, y: note.y))
        decorations(text: note.text, manager: manager, origin: CGPoint(x: note.x, y: note.y), width: width)
        image.unlockFocus()
        return image
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
