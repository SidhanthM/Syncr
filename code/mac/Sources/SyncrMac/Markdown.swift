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

/// Pure editing rules: (text, selection) in, (text, selection) out. The editor applies them; nothing here touches a view.
enum MarkdownEditing {
    static func replace(_ text: String, _ range: NSRange, _ insert: String, selection: NSRange? = nil) -> EditorEdit {
        EditorEdit(text: (text as NSString).replacingCharacters(in: range, with: insert), selection: selection ?? NSRange(location: range.location + (insert as NSString).length, length: 0))
    }
    static func lineRange(_ text: String, _ selection: NSRange) -> NSRange {
        (text as NSString).lineRange(for: NSRange(location: min(selection.location, (text as NSString).length), length: 0))
    }
    /// Start of the line holding `offset`, and the end of its words (before the line break).
    static func lineBounds(_ text: String, _ offset: Int) -> (start: Int, end: Int) {
        var start = 0, end = 0, contents = 0
        (text as NSString).getLineStart(&start, end: &end, contentsEnd: &contents, for: NSRange(location: min(offset, (text as NSString).length), length: 0))
        return (start, contents)
    }
    private static func sub(_ ns: NSString, _ location: Int, _ length: Int) -> String {
        location >= 0 && length >= 0 && location + length <= ns.length ? ns.substring(with: NSRange(location: location, length: length)) : ""
    }

    // MARK: Enter, Tab, Backspace

    static func enter(_ text: String, selection: NSRange) -> EditorEdit {
        let ns = text as NSString
        let (start, end) = lineBounds(text, selection.location)
        let raw = ns.substring(with: NSRange(location: start, length: end - start))
        let all = Markdown.lines(text)
        guard let line = all.first(where: { $0.range.location == start }) else { return replace(text, selection, "\n") }
        let atEnd = selection.location >= end
        // A fence opened with ``` and Enter: the block closes itself and the caret waits inside.
        if case .fence = line.kind, atEnd, raw.trimmingCharacters(in: .whitespaces).hasPrefix("```") {
            let before = all.filter { if case .fence = $0.kind { return $0.range.location < start }; return false }.count
            if before % 2 == 0 {
                return replace(text, selection, "\n\n```", selection: NSRange(location: selection.location + 1, length: 0))
            }
        }
        let prefixEnd = start + line.prefix.length
        let prefix = (raw as NSString).substring(to: line.prefix.length)
        let indentCount = prefix.prefix(while: { $0 == " " || $0 == "\t" }).count
        var next = ""
        switch line.kind {
        case .bullet, .quote: next = prefix
        case .task: next = prefix.replacingOccurrences(of: "[x]", with: "[ ]").replacingOccurrences(of: "[X]", with: "[ ]")
        case .numbered(let n):
            let lead = String(prefix.prefix(indentCount))
            next = lead + "\(n + 1). "
        default: break
        }
        guard !next.isEmpty, selection.location >= prefixEnd else { return replace(text, selection, "\n") }
        let words = (raw as NSString).substring(from: line.prefix.length)
        if words.trimmingCharacters(in: .whitespaces).isEmpty && selection.length == 0 {
            // An empty item ends the list; a nested one first steps out a level.
            if indentCount > 0 {
                let remove = prefix.hasPrefix("\t") ? 1 : min(4, prefix.prefix(while: { $0 == " " }).count)
                let edit = replace(text, NSRange(location: start, length: remove), "", selection: NSRange(location: selection.location - remove, length: 0))
                return renumberAround(edit)
            }
            return replace(text, NSRange(location: start, length: end - start), "", selection: NSRange(location: start, length: 0))
        }
        return renumberAround(replace(text, selection, "\n" + next))
    }

    /// Tab or Shift-Tab over the lines the selection touches; the caret or selection keeps its place in the words.
    static func indent(_ text: String, selection: NSRange, outdent: Bool) -> EditorEdit {
        let ns = text as NSString
        let first = lineBounds(text, selection.location).start
        let lastLine = lineBounds(text, max(selection.location, selection.location + selection.length - (selection.length > 0 && sub(ns, selection.location + selection.length - 1, 1) == "\n" ? 1 : 0))).end
        let range = NSRange(location: first, length: lastLine - first)
        let lines = ns.substring(with: range).components(separatedBy: "\n")
        var out: [String] = [], shifts: [(old: Int, delta: Int)] = [], at = first
        for s in lines {
            var delta = 4, changed = "    " + s
            if outdent {
                let remove = s.hasPrefix("\t") ? 1 : min(4, s.prefix(while: { $0 == " " }).count)
                delta = -remove; changed = String(s.dropFirst(remove))
            }
            out.append(changed); shifts.append((at, delta)); at += (s as NSString).length + 1
        }
        func map(_ p: Int, start: Bool = false) -> Int {
            var shift = 0
            for (i, entry) in shifts.enumerated() {
                let length = (lines[i] as NSString).length
                if p > entry.old + length { shift += entry.delta; continue }
                let off = p - entry.old
                if start, off == 0, selection.length > 0 { return entry.old + shift }
                return entry.old + shift + (entry.delta >= 0 ? off + entry.delta : max(0, off + entry.delta))
            }
            return p + shift
        }
        let edit = replace(text, range, out.joined(separator: "\n"))
        var result = edit
        result.selection = NSRange(location: map(selection.location, start: true), length: max(0, map(selection.location + selection.length) - map(selection.location, start: true)))
        return renumberAround(result)
    }

    /// Backspace right after a list or quote marker removes the marker; between an empty pair it removes both halves.
    static func backspace(_ text: String, selection: NSRange) -> EditorEdit? {
        guard selection.length == 0, selection.location > 0 else { return nil }
        let ns = text as NSString
        let (start, _) = lineBounds(text, selection.location)
        if let line = Markdown.lines(text).first(where: { $0.range.location == start }), line.prefix.length > 0, selection.location == start + line.prefix.length {
            switch line.kind {
            case .bullet, .numbered, .task, .quote:
                return renumberAround(replace(text, line.prefix, "", selection: NSRange(location: start, length: 0)))
            default: break
            }
        }
        let before = sub(ns, selection.location - 1, 1), after = sub(ns, selection.location, 1)
        if let right = pairs[before], right == after { return replace(text, NSRange(location: selection.location - 1, length: 2), "", selection: NSRange(location: selection.location - 1, length: 0)) }
        return nil
    }

    // MARK: Numbering

    private static func isListLine(_ raw: String) -> Bool {
        !Markdown.matches("^\\s*([-*] |[0-9]+\\. |> )", raw).isEmpty
    }
    /// Renumbers the numbered list that holds the caret's line, and no other text.
    static func renumberAround(_ edit: EditorEdit) -> EditorEdit {
        let ns = edit.text as NSString
        guard ns.length > 0 else { return edit }
        // Cheap test first (this runs on every keystroke): is a numbered item on the caret's line or next to it?
        let here0 = lineBounds(edit.text, edit.selection.location)
        var near = [here0]
        if here0.start > 0 { near.append(lineBounds(edit.text, here0.start - 1)) }
        if here0.end < ns.length { near.append(lineBounds(edit.text, here0.end + 1)) }
        guard near.contains(where: { Markdown.matches("^\\s*[0-9]+\\. ", ns.substring(with: NSRange(location: $0.start, length: $0.end - $0.start))).count > 0 }) else { return edit }
        var lines: [(start: Int, text: String)] = [], at = 0
        for raw in edit.text.components(separatedBy: "\n") { lines.append((at, raw)); at += (raw as NSString).length + 1 }
        guard let here = lines.lastIndex(where: { $0.start <= min(edit.selection.location, ns.length) }) else { return edit }
        func belongs(_ i: Int) -> Bool {
            let t = lines[i].text
            return !t.trimmingCharacters(in: .whitespaces).isEmpty && (isListLine(t) || t.hasPrefix(" ") || t.hasPrefix("\t"))
        }
        guard belongs(here) else { return edit }
        var a = here, b = here
        while a > 0, belongs(a - 1) { a -= 1 }
        while b + 1 < lines.count, belongs(b + 1) { b += 1 }
        let block = lines[a...b].map(\.text).joined(separator: "\n")
        let fixed = renumber(block)
        guard fixed != block else { return edit }
        let origin = lines[a].start
        func map(_ p: Int) -> Int {
            p <= origin ? p : p >= origin + (block as NSString).length ? p + (fixed as NSString).length - (block as NSString).length
                : origin + (renumber((block as NSString).substring(to: p - origin)) as NSString).length
        }
        let text = ns.replacingCharacters(in: NSRange(location: origin, length: (block as NSString).length), with: fixed)
        let from = map(edit.selection.location), to = map(NSMaxRange(edit.selection))
        return EditorEdit(text: text, selection: NSRange(location: from, length: to - from))
    }
    /// Within one list a number is the one before it plus one; the first number stands.
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

    // MARK: Typing pairs

    static let pairs = ["(": ")", "[": "]", "{": "}", "\"": "\"", "'": "'", "*": "*", "`": "`", "~": "~", "=": "="]
    /// What typing one character does, or nil where the text system should insert it as it would.
    static func type(_ text: String, selection: NSRange, character: String) -> EditorEdit? {
        guard character.utf16.count == 1 else { return nil }
        let ns = text as NSString, at = selection.location
        if ["*", "~", "="].contains(character), selection.length == 0, at > 0, at < ns.length,
           sub(ns, at - 1, 1) == character, sub(ns, at, 1) == character,
           at == 1 || sub(ns, at - 2, 1).rangeOfCharacter(from: .alphanumerics) == nil {
            return replace(text, NSRange(location: at, length: 0), character + character, selection: NSRange(location: at + 1, length: 0))
        }
        if selection.length == 0, at < ns.length, [")", "]", "}", "\"", "'", "*", "`", "~", "="].contains(character), sub(ns, at, 1) == character {
            return EditorEdit(text: text, selection: NSRange(location: at + 1, length: 0))
        }
        guard let right = pairs[character] else { return nil }
        // `~` and `=` only wrap what is selected: typed alone they are ordinary characters.
        if selection.length == 0, ["~", "="].contains(character) { return nil }
        // An apostrophe inside a word is not a pair.
        if selection.length == 0, character == "'", at > 0, sub(ns, at - 1, 1).rangeOfCharacter(from: .alphanumerics) != nil { return nil }
        // Nor is a closing quote after a word, or a quote before one.
        if selection.length == 0, character == "\"", at > 0, sub(ns, at - 1, 1).rangeOfCharacter(from: .alphanumerics) != nil { return nil }
        return wrap(text, selection: selection, left: character, right: right)
    }
    static func wrap(_ text: String, selection: NSRange, left: String, right: String) -> EditorEdit {
        let raw = (text as NSString).substring(with: selection)
        return replace(text, selection, left + raw + right, selection: NSRange(location: selection.location + left.utf16.count, length: selection.length))
    }

    // MARK: Formatting toggles

    private static func wordRange(_ ns: NSString, at p: Int) -> NSRange {
        func word(_ i: Int) -> Bool { i >= 0 && i < ns.length && (sub(ns, i, 1).rangeOfCharacter(from: .alphanumerics) != nil || sub(ns, i, 1) == "_") }
        var a = p, b = p
        while word(a - 1) { a -= 1 }
        while word(b) { b += 1 }
        return NSRange(location: a, length: b - a)
    }
    private static func run(_ ns: NSString, from p: Int, step: Int, marker: Character) -> Int {
        var n = 0, i = step > 0 ? p : p - 1
        while i >= 0, i < ns.length, sub(ns, i, 1) == String(marker) { n += 1; i += step }
        return n
    }
    /// Bold, italic, strike, highlight and inline code: wraps the selection (or the word at the caret), and takes the
    /// markers off again when they are already there. The caret keeps its place in the word.
    static func toggle(_ text: String, selection: NSRange, open: String, close: String) -> EditorEdit {
        let ns = text as NSString, o = open.utf16.count, c = close.utf16.count
        let caret = selection.length == 0 ? selection.location : nil
        if let p = caret, sub(ns, p - o, o) == open, sub(ns, p, c) == close, o > 0, p >= o {
            return replace(text, NSRange(location: p - o, length: o + c), "", selection: NSRange(location: p - o, length: 0))
        }
        var sel = selection
        if let p = caret {
            let w = wordRange(ns, at: p)
            if w.length == 0 { return replace(text, selection, open + close, selection: NSRange(location: p + o, length: 0)) }
            sel = w
        }
        let marker = open.first!
        let inner = ns.substring(with: sel)
        func odd(_ l: Int, _ r: Int) -> Bool { open == "*" ? min(l, r) % 2 == 1 : open == "**" ? min(l, r) >= 2 : true }
        let l = run(ns, from: sel.location, step: -1, marker: marker), r = run(ns, from: NSMaxRange(sel), step: 1, marker: marker)
        let wrapped = sub(ns, sel.location - o, o) == open && sub(ns, NSMaxRange(sel), c) == close && sel.location >= o
        var result: EditorEdit
        let ln = inner.prefix(while: { $0 == marker }).count, rn = inner.reversed().prefix(while: { $0 == marker }).count
        if wrapped && ((marker != "*") || odd(l, r)) {
            result = replace(text, NSRange(location: sel.location - o, length: sel.length + o + c), inner, selection: NSRange(location: sel.location - o, length: sel.length))
            if let p = caret { result.selection = NSRange(location: p - o, length: 0) }
        } else if inner.hasPrefix(open), inner.hasSuffix(close), sel.length >= o + c, (marker != "*") || odd(ln, rn) {
            result = replace(text, sel, String(inner.dropFirst(o).dropLast(c)), selection: NSRange(location: sel.location, length: sel.length - o - c))
        } else {
            result = replace(text, sel, open + inner + close, selection: NSRange(location: sel.location + o, length: sel.length))
            if let p = caret { result.selection = NSRange(location: p + o, length: 0) }
        }
        return result
    }
    /// Link: `[word](url)` with the address selected; again, back to the words. Nothing selected and no word:
    /// `[text](url)` with the placeholder selected.
    static func link(_ text: String, selection: NSRange) -> EditorEdit {
        let ns = text as NSString
        let (start, _) = lineBounds(text, selection.location)
        if let line = Markdown.lines(text).first(where: { $0.range.location == start }) {
            for span in line.spans {
                guard case .link(let url) = span.kind, !url.hasPrefix("http") || ns.substring(with: span.range).hasPrefix("["),
                      selection.location >= span.range.location, NSMaxRange(selection) <= NSMaxRange(span.range), let open = span.syntax.first, let close = span.syntax.last else { continue }
                let words = NSRange(location: NSMaxRange(open), length: close.location - NSMaxRange(open))
                return replace(text, span.range, ns.substring(with: words), selection: NSRange(location: span.range.location, length: words.length))
            }
        }
        var sel = selection
        if sel.length == 0 { sel = wordRange(ns, at: sel.location) }
        if sel.length == 0 {
            return replace(text, selection, "[text](url)", selection: NSRange(location: selection.location + 1, length: 4))
        }
        let words = ns.substring(with: sel)
        return replace(text, sel, "[\(words)](url)", selection: NSRange(location: sel.location + words.utf16.count + 3, length: 3))
    }
    /// A pasted address over selected words.
    static func pasteLink(_ text: String, selection: NSRange, pasted: String) -> EditorEdit? {
        let url = pasted.trimmingCharacters(in: .whitespacesAndNewlines)
        guard selection.length > 0, !url.contains(where: \.isWhitespace), let parsed = URL(string: url), ["http", "https", "mailto"].contains(parsed.scheme ?? "") else { return nil }
        let words = (text as NSString).substring(with: selection)
        guard !words.contains("\n"), !words.contains("[") , !words.contains("]") else { return nil }
        let insert = "[\(words)](\(url))"
        return replace(text, selection, insert)
    }

    // MARK: Tasks

    /// Toggle task: a plain line or bullet becomes `- [ ] `, then ticked, then unticked. The caret keeps its place.
    static func task(_ text: String, selection: NSRange) -> EditorEdit {
        let (start, end) = lineBounds(text, selection.location)
        let ns = text as NSString
        let raw = ns.substring(with: NSRange(location: start, length: end - start))
        guard let line = Markdown.lines(raw).first else { return EditorEdit(text: text, selection: selection) }
        let lead = String(raw.prefix(while: { $0 == " " || $0 == "\t" }))
        func finish(_ value: String) -> EditorEdit {
            let delta = (value as NSString).length - (raw as NSString).length
            let edit = replace(text, NSRange(location: start, length: end - start), value)
            func shift(_ p: Int) -> Int { p >= end ? p + delta : p >= start + line.prefix.length ? p + delta : min(p, start + (value as NSString).length) }
            return EditorEdit(text: edit.text, selection: NSRange(location: shift(selection.location), length: max(0, shift(NSMaxRange(selection)) - shift(selection.location))))
        }
        switch line.kind {
        case .task(let checked):
            let mark = Markdown.matches("\\[([ xX])\\]", raw).first!.range(at: 1)
            return finish((raw as NSString).replacingCharacters(in: mark, with: checked ? " " : "x"))
        case .bullet: return finish(lead + "- [ ] " + (raw as NSString).substring(from: line.prefix.length))
        case .numbered, .quote: return finish(lead + "- [ ] " + (raw as NSString).substring(from: line.prefix.length))
        case .heading, .fence, .code, .rule: return EditorEdit(text: text, selection: selection)
        case .plain: return finish(lead + "- [ ] " + (raw as NSString).substring(from: lead.utf16.count))
        }
    }
    /// A click on a rendered box: flips it in place. Same length, so no caret moves.
    static func flipBox(_ text: String, lineStart: Int) -> String? {
        let (start, end) = lineBounds(text, lineStart)
        let ns = text as NSString
        let raw = ns.substring(with: NSRange(location: start, length: end - start))
        guard let line = Markdown.lines(raw).first, case .task(let checked) = line.kind, let mark = Markdown.matches("\\[([ xX])\\]", raw).first?.range(at: 1) else { return nil }
        return ns.replacingCharacters(in: NSRange(location: start + mark.location, length: 1), with: checked ? " " : "x")
    }
}

/// A user command from `<library>/.mac/slash.json`: an inline insertion.
struct SlashCommand: Codable, Equatable {
    var name: String
    var insertion: String
    /// What the file held before 2026-11-16, when it was written out with the built-ins: that is "no commands of mine".
    static let legacyDefaults: [SlashCommand] = [
        ("h1", "# "), ("h2", "## "), ("h3", "### "), ("todo", "- [ ] "), ("bullet", "- "),
        ("number", "1. "), ("quote", "> "), ("code", "```\n\n```"), ("divider", "---\n"), ("line", "---\n"),
        ("dashedline", "- - -\n"), ("dots", "...\n"), ("date", "{date}"), ("time", "{time}"), ("link", "[text](url)")
    ].map { SlashCommand(name: $0.0, insertion: $0.1) }
}
