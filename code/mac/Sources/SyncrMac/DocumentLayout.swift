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
}
struct DocumentLayoutLine: Equatable {
    var range: NSRange
    var text: String
    var rect: CGRect
}

@MainActor enum DocumentLayout {
    static let bodySize: CGFloat = 48
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
    static func attributed(_ text: String, caret: Int? = nil, resolving: ((String) -> Bool)? = nil, drawingHeights: [String: CGFloat] = [:]) -> NSAttributedString {
        let out = NSMutableAttributedString(string: text, attributes: [.font: NSFont.systemFont(ofSize: bodySize), .foregroundColor: NSColor.labelColor])
        let paragraph = NSMutableParagraphStyle(); paragraph.lineSpacing = 12; paragraph.paragraphSpacing = 8
        out.addAttribute(.paragraphStyle, value: paragraph, range: NSRange(location: 0, length: out.length))
        func hide(_ r: NSRange) { if r.length > 0 { out.addAttributes([.font: NSFont.systemFont(ofSize: 0.01), .foregroundColor: NSColor.clear], range: r) } }
        for line in Markdown.lines(text) {
            let active = caret.map { $0 >= line.range.location && $0 <= NSMaxRange(line.range) } ?? false
            let raw = line.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if raw.hasPrefix("![[syncr-drawing:"), raw.hasSuffix("]]"), let height = drawingHeights[String(raw.dropFirst(17).dropLast(2))] {
                let embed = NSMutableParagraphStyle(); embed.minimumLineHeight = height; embed.maximumLineHeight = height
                out.addAttributes([.paragraphStyle: embed, .font: NSFont.systemFont(ofSize: active ? 14 : 0.01), .foregroundColor: active ? NSColor.secondaryLabelColor : NSColor.clear], range: line.range)
            }
            var hiddenPrefix = false
            switch line.kind {
            case .heading(let level):
                out.addAttribute(.font, value: NSFont.systemFont(ofSize: bodySize + CGFloat(7 - level) * 8, weight: .semibold), range: line.range); hiddenPrefix = true
            case .quote: out.addAttribute(.foregroundColor, value: NSColor.secondaryLabelColor, range: line.range); hiddenPrefix = true
            case .task, .bullet, .numbered: hiddenPrefix = true
            case .fence:
                out.addAttributes([.font: NSFont.monospacedSystemFont(ofSize: 34, weight: .regular), .foregroundColor: NSColor.secondaryLabelColor], range: line.range)
                hiddenPrefix = !active
            case .code(let language):
                out.addAttributes([.font: NSFont.monospacedSystemFont(ofSize: 42, weight: .regular), .backgroundColor: NSColor.quaternaryLabelColor], range: line.range)
                colourCode(line, language: language, into: out)
            case .rule: out.addAttribute(.foregroundColor, value: active ? NSColor.secondaryLabelColor : NSColor.clear, range: line.range)
            default: break
            }
            if hiddenPrefix && !active {
                switch line.kind {
                case .task, .bullet, .numbered, .quote:
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
                case .code: out.addAttributes([.font: NSFont.monospacedSystemFont(ofSize: 44, weight: .regular), .backgroundColor: NSColor.quaternaryLabelColor], range: span.range)
                case .link(let url): out.addAttributes([.link: url, .foregroundColor: NSColor.linkColor], range: span.range)
                case .wiki(let title):
                    out.addAttributes([.link: "syncr-wiki:" + title, .foregroundColor: NSColor.systemBlue.withAlphaComponent(resolving?(title) == false ? 0.45 : 1)], range: span.range)
                case .tag: out.addAttribute(.foregroundColor, value: NSColor.systemPurple, range: span.range)
                }
                if !active { span.syntax.forEach(hide) }
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
    static func decorations(text: String, manager: NSLayoutManager, origin: CGPoint = .zero, width: CGFloat, caret: Int? = nil) {
        for line in Markdown.lines(text) where line.range.length > 0 {
            let glyphs = manager.glyphRange(forCharacterRange: line.range, actualCharacterRange: nil)
            guard glyphs.length > 0 else { continue }
            let rect = manager.lineFragmentRect(forGlyphAt: glyphs.location, effectiveRange: nil).offsetBy(dx: origin.x, dy: origin.y)
            let active = caret.map { $0 >= line.range.location && $0 <= NSMaxRange(line.range) } ?? false
            let indent = CGFloat(line.text.prefix(while: { $0 == " " || $0 == "\t" }).count) * 12
            func label(_ text: String, size: CGFloat = 48, x: CGFloat = 0) {
                (text as NSString).draw(at: CGPoint(x: origin.x + x, y: rect.minY), withAttributes: [.font: NSFont.systemFont(ofSize: size), .foregroundColor: NSColor.secondaryLabelColor])
            }
            switch line.kind {
            case .fence(let language) where !language.isEmpty && !active: label(language, size: 30, x: max(0, width - 240))
            case .bullet where !active: label("•", x: indent)
            case .numbered(let n) where !active: label("\(n).", x: indent)
            case .quote: NSColor.separatorColor.setFill(); CGRect(x: origin.x, y: rect.minY + 4, width: 5, height: rect.height - 8).fill()
            case .task(let checked) where !active:
                let box = CGRect(x: origin.x + indent, y: rect.minY + 10, width: 34, height: 34)
                NSColor.secondaryLabelColor.setStroke(); NSBezierPath(rect: box).stroke()
                if checked { ("✓" as NSString).draw(in: box, withAttributes: [.font: NSFont.systemFont(ofSize: 30), .foregroundColor: NSColor.systemBlue]) }
            case .rule(let type) where !active:
                let path = NSBezierPath(); path.move(to: CGPoint(x: origin.x, y: rect.midY)); path.line(to: CGPoint(x: origin.x + width, y: rect.midY)); path.lineWidth = 3
                if type != "---" { path.setLineDash(type == "..." ? [3, 10] : [24, 15], count: 2, phase: 0) }
                NSColor.separatorColor.setStroke(); path.stroke()
            default: break
            }
        }
    }
    private static func engine(_ page: DocumentPage, width: CGFloat) -> (NSTextStorage, NSLayoutManager, NSTextContainer) {
        let storage = NSTextStorage(attributedString: attributed(page.text, drawingHeights: page.drawingHeights))
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
        (note.title as NSString).draw(at: CGPoint(x: note.x, y: 80), withAttributes: [.font: NSFont.boldSystemFont(ofSize: 76), .foregroundColor: NSColor.labelColor])
        let range = NSRange(location: 0, length: manager.numberOfGlyphs)
        manager.drawBackground(forGlyphRange: range, at: CGPoint(x: note.x, y: note.y))
        manager.drawGlyphs(forGlyphRange: range, at: CGPoint(x: note.x, y: note.y))
        decorations(text: note.text, manager: manager, origin: CGPoint(x: note.x, y: note.y), width: width)
        image.unlockFocus()
        return image
    }
}
