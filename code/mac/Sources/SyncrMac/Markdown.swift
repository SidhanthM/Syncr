import Foundation

struct TextSplice: Equatable {
    var offset: UInt64
    var delete: UInt64
    var insert: String
    static func between(_ old: String, _ new: String) -> TextSplice? {
        guard !old.utf8.elementsEqual(new.utf8) else { return nil }
        let a = Array(old), b = Array(new)
        var p = 0, s = 0
        while p < min(a.count, b.count), String(a[p]).utf8.elementsEqual(String(b[p]).utf8) { p += 1 }
        while s < min(a.count, b.count) - p, String(a[a.count - s - 1]).utf8.elementsEqual(String(b[b.count - s - 1]).utf8) { s += 1 }
        return TextSplice(offset: UInt64(String(a[..<p]).utf8.count),
                          delete: UInt64(String(a[p..<(a.count - s)]).utf8.count), insert: String(b[p..<(b.count - s)]))
    }
}

struct MarkdownSpan: Equatable {
    enum Kind: Equatable { case bold, italic, strike, highlight, code, link(String), wiki(String), tag(String) }
    var kind: Kind
    var range: NSRange
    var syntax: [NSRange]
}
struct MarkdownLine: Equatable {
    enum Kind: Equatable { case plain, heading(Int), bullet, numbered(Int), task(Bool), quote, fence(String), code(String), rule(String) }
    var kind: Kind
    var range: NSRange
    var prefix: NSRange
    var text: String
    var spans: [MarkdownSpan]
}

enum Markdown {
    static func matches(_ pattern: String, _ text: String) -> [NSTextCheckingResult] {
        (try? NSRegularExpression(pattern: pattern).matches(in: text, range: NSRange(location: 0, length: (text as NSString).length))) ?? []
    }
    static func lines(_ text: String) -> [MarkdownLine] {
        var result: [MarkdownLine] = [], offset = 0, fence: String?
        let sourceLines = text.components(separatedBy: "\n")
        let frontMatterEnd = sourceLines.first == "---" ? sourceLines.dropFirst().firstIndex(of: "---") : nil
        for (lineIndex, raw) in sourceLines.enumerated() {
            let ns = raw as NSString
            let length = ns.length
            var kind: MarkdownLine.Kind = .plain, prefix = 0
            func first(_ p: String) -> NSTextCheckingResult? { matches(p, raw).first }
            if let frontMatterEnd, lineIndex <= frontMatterEnd {
                result.append(MarkdownLine(kind: .plain, range: NSRange(location: offset, length: length), prefix: NSRange(location: offset, length: 0), text: raw, spans: []))
                offset += length + 1; continue
            }
            if let m = first("^\\s*```(.*)$") {
                let language = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
                kind = .fence(language); prefix = length
                fence = fence == nil ? language : nil
            } else if let language = fence { kind = .code(language) }
            else if ["---", "- - -", "..."].contains(raw.trimmingCharacters(in: .whitespaces)) { kind = .rule(raw.trimmingCharacters(in: .whitespaces)); prefix = length }
            else if let m = first("^(#{1,6})\\s+") { kind = .heading(m.range(at: 1).length); prefix = m.range.length }
            else if let m = first("^\\s*[-*] \\[([ xX])\\] ") { kind = .task(ns.substring(with: m.range(at: 1)) != " "); prefix = m.range.length }
            else if let m = first("^\\s*[-*] ") { kind = .bullet; prefix = m.range.length }
            else if let m = first("^\\s*([0-9]+)\\. ") { kind = .numbered(Int(ns.substring(with: m.range(at: 1))) ?? 1); prefix = m.range.length }
            else if let m = first("^\\s*> ") { kind = .quote; prefix = m.range.length }
            let isCode: Bool
            switch kind { case .code, .fence: isCode = true; default: isCode = false }
            result.append(MarkdownLine(kind: kind, range: NSRange(location: offset, length: length),
                                       prefix: NSRange(location: offset, length: prefix), text: raw,
                                       spans: isCode ? [] : inline(raw, offset: offset)))
            offset += length + 1
        }
        return result
    }
    static func inline(_ text: String, offset: Int = 0) -> [MarkdownSpan] {
        let ns = text as NSString
        var out: [MarkdownSpan] = [], occupied: [NSRange] = []
        func add(_ pattern: String, kind: (NSTextCheckingResult) -> MarkdownSpan.Kind, syntax: (NSTextCheckingResult) -> [NSRange]) {
            for m in matches(pattern, text) where !occupied.contains(where: { NSIntersectionRange($0, m.range).length > 0 }) {
                let r = m.range
                out.append(MarkdownSpan(kind: kind(m), range: NSRange(location: offset + r.location, length: r.length), syntax: syntax(m).map { NSRange(location: offset + $0.location, length: $0.length) }))
                occupied.append(r)
            }
        }
        add("`[^`]+`", kind: { _ in .code }, syntax: { [NSRange(location: $0.range.location, length: 1), NSRange(location: NSMaxRange($0.range) - 1, length: 1)] })
        // Embeds are deliberately plain in v1.
        add("(?<!!)\\[\\[([^]\\n|]+)(?:\\|([^]\\n]+))?\\]\\]", kind: { .wiki(ns.substring(with: $0.range(at: 1))) }, syntax: {
            let shown = $0.range(at: 2).location != NSNotFound ? $0.range(at: 2) : $0.range(at: 1)
            return [NSRange(location: $0.range.location, length: shown.location - $0.range.location), NSRange(location: NSMaxRange(shown), length: NSMaxRange($0.range) - NSMaxRange(shown))]
        })
        add("(?<!!)\\[([^]\\n]+)\\]\\(([^)\\n]+)\\)", kind: { .link(ns.substring(with: $0.range(at: 2))) }, syntax: {
            [NSRange(location: $0.range.location, length: 1), NSRange(location: NSMaxRange($0.range(at: 1)), length: NSMaxRange($0.range) - NSMaxRange($0.range(at: 1)))]
        })
        for (pattern, kind, n) in [("\\*\\*[^*]+\\*\\*", MarkdownSpan.Kind.bold, 2), ("(?<!\\*)\\*(?!\\*)[^*]+\\*(?!\\*)", .italic, 1), ("~~[^~]+~~", .strike, 2), ("==[^=]+==", .highlight, 2)] {
            add(pattern, kind: { _ in kind }, syntax: { [NSRange(location: $0.range.location, length: n), NSRange(location: NSMaxRange($0.range) - n, length: n)] })
        }
        add("https?://[^\\s<>]+", kind: { .link(ns.substring(with: $0.range)) }, syntax: { _ in [] })
        add("(?<![\\w/#])#[\\p{L}\\p{N}_-]+(?:/[\\p{L}\\p{N}_-]+)*", kind: { .tag(String(ns.substring(with: $0.range).dropFirst())) }, syntax: { _ in [] })
        return out.sorted { $0.range.location < $1.range.location }
    }
    static func rewriteLinks(_ text: String, from old: String, to new: String) -> String {
        let result = NSMutableString(string: text)
        for span in lines(text).flatMap(\.spans).reversed() {
            guard case .wiki(let title) = span.kind, title.caseInsensitiveCompare(old) == .orderedSame else { continue }
            let raw = (text as NSString).substring(with: span.range)
            let alias = raw.range(of: "|").map { String(raw[$0.lowerBound...].dropLast(2)) } ?? ""
            result.replaceCharacters(in: span.range, with: "[[\(new)\(alias)]]")
        }
        return result as String
    }
}

struct EditorEdit: Equatable { var text: String; var selection: NSRange }
enum MarkdownEditing {
    static func replace(_ text: String, _ range: NSRange, _ insert: String, selection: NSRange? = nil) -> EditorEdit {
        EditorEdit(text: (text as NSString).replacingCharacters(in: range, with: insert), selection: selection ?? NSRange(location: range.location + (insert as NSString).length, length: 0))
    }
    static func lineRange(_ text: String, _ selection: NSRange) -> NSRange {
        (text as NSString).lineRange(for: NSRange(location: min(selection.location, (text as NSString).length), length: 0))
    }
    static func enter(_ text: String, selection: NSRange) -> EditorEdit {
        let range = lineRange(text, selection), ns = text as NSString
        let raw = ns.substring(with: range).trimmingCharacters(in: .newlines)
        guard let line = Markdown.lines(raw).first else { return replace(text, selection, "\n") }
        let prefix = (raw as NSString).substring(with: line.prefix)
        let rest = (raw as NSString).substring(from: line.prefix.length)
        var next = ""
        switch line.kind {
        case .bullet, .quote: next = prefix
        case .task: next = prefix.replacingOccurrences(of: "[x]", with: "[ ]").replacingOccurrences(of: "[X]", with: "[ ]")
        case .numbered(let n): next = prefix.replacingOccurrences(of: "\(n).", with: "\(n + 1).")
        default: break
        }
        if !next.isEmpty && rest.trimmingCharacters(in: .whitespaces).isEmpty { return replace(text, NSRange(location: range.location, length: raw.utf16.count), "") }
        return renumber(replace(text, selection, "\n" + next))
    }
    static func indent(_ text: String, selection: NSRange, outdent: Bool) -> EditorEdit {
        let ns = text as NSString, range = ns.lineRange(for: selection)
        let raw = ns.substring(with: range)
        let lines = raw.components(separatedBy: "\n")
        let changed = lines.enumerated().map { i, s in
            if s.isEmpty && i == lines.count - 1 { return s }
            if outdent { if s.hasPrefix("\t") { return String(s.dropFirst()) }; return String(s.dropFirst(s.prefix(while: { $0 == " " }).prefix(4).count)) }
            return "    " + s
        }.joined(separator: "\n")
        let edit = replace(text, range, changed, selection: NSRange(location: range.location, length: changed.utf16.count))
        return renumber(edit)
    }
    private static func renumber(_ edit: EditorEdit) -> EditorEdit {
        let ns = edit.text as NSString
        let start = renumber(ns.substring(to: edit.selection.location)).utf16.count
        let end = renumber(ns.substring(to: NSMaxRange(edit.selection))).utf16.count
        return EditorEdit(text: renumber(edit.text), selection: NSRange(location: start, length: end - start))
    }
    static func renumber(_ text: String) -> String {
        var counts: [Int: Int] = [:]
        return text.components(separatedBy: "\n").map { raw in
            guard let m = Markdown.matches("^(\\s*)([0-9]+)\\. ", raw).first else {
                if raw.trimmingCharacters(in: .whitespaces).isEmpty || (!raw.hasPrefix(" ") && !raw.hasPrefix("\t")) { counts = [:] }
                return raw
            }
            let indent = m.range(at: 1).length
            counts = counts.filter { $0.key <= indent }
            let n = (counts[indent].map { $0 + 1 }) ?? Int((raw as NSString).substring(with: m.range(at: 2))) ?? 1
            counts[indent] = n
            return (raw as NSString).replacingCharacters(in: m.range(at: 2), with: String(n))
        }.joined(separator: "\n")
    }
    static func wrap(_ text: String, selection: NSRange, left: String, right: String) -> EditorEdit {
        let raw = (text as NSString).substring(with: selection)
        return replace(text, selection, left + raw + right, selection: NSRange(location: selection.location + left.utf16.count, length: selection.length))
    }
    static func task(_ text: String, selection: NSRange) -> EditorEdit {
        let range = lineRange(text, selection), raw = (text as NSString).substring(with: range)
        let value: String
        if raw.contains("[ ]") { value = raw.replacingOccurrences(of: "[ ]", with: "[x]") }
        else if raw.contains("[x]") { value = raw.replacingOccurrences(of: "[x]", with: "[ ]") }
        else { value = "- [ ] " + raw.replacingOccurrences(of: "^\\s*[-*] ", with: "", options: .regularExpression) }
        return replace(text, range, value)
    }
}

struct SlashCommand: Codable, Equatable {
    var name: String
    var insertion: String
    static let defaults: [SlashCommand] = [
        ("h1", "# "), ("h2", "## "), ("h3", "### "), ("todo", "- [ ] "), ("bullet", "- "),
        ("number", "1. "), ("quote", "> "), ("code", "```\n\n```"), ("divider", "---\n"), ("line", "---\n"),
        ("dashedline", "- - -\n"), ("dots", "...\n"), ("date", "{date}"), ("time", "{time}"), ("link", "[text](url)")
    ].map { SlashCommand(name: $0.0, insertion: $0.1) }
    static func filter(_ query: String, commands: [SlashCommand] = defaults) -> [SlashCommand] {
        commands.filter { $0.name.lowercased().hasPrefix(query.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "/"))) }
    }
    func apply(_ text: String, selection: NSRange, now: Date = Date()) -> EditorEdit {
        let range = MarkdownEditing.lineRange(text, selection)
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"
        var value = insertion.replacingOccurrences(of: "{date}", with: f.string(from: now))
        f.dateFormat = "HH:mm"
        value = value.replacingOccurrences(of: "{time}", with: f.string(from: now))
        let caret = name == "code" ? 4 : value.utf16.count
        if (text as NSString).substring(with: range).hasSuffix("\n") && !value.hasSuffix("\n") { value += "\n" }
        return MarkdownEditing.replace(text, range, value, selection: NSRange(location: range.location + caret, length: 0))
    }
}
