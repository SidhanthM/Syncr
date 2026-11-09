import Foundation

/// The slash menu's commands and what running one does (editor-v2 §3). Pure: text and selection in, text and selection out.
struct EditorCommand: Equatable {
    enum Line: Equatable { case text, heading(Int), bullet, number, todo, quote }
    enum Effect: Equatable {
        case line(Line), code, rule(String), inline(open: String, close: String), link, note, date, time, ask, draw, user(String)
    }
    var name: String
    var aliases: [String] = []
    var title: String
    var group: String
    var icon: String
    var effect: Effect
}

enum EditorAction: Equatable { case ask, draw, completeNote }

struct SlashToken: Equatable {
    /// The `/` and what follows it, up to the caret.
    var range: NSRange
    var query: String
}
struct SlashOutcome: Equatable { var edit: EditorEdit; var action: EditorAction? }

enum SlashCatalog {
    private static func c(_ name: String, _ aliases: [String], _ title: String, _ group: String, _ icon: String, _ effect: EditorCommand.Effect) -> EditorCommand {
        EditorCommand(name: name, aliases: aliases, title: title, group: group, icon: icon, effect: effect)
    }
    static let builtIn: [EditorCommand] = [
        c("text", ["p", "paragraph"], "Text", "Basic", "text.alignleft", .line(.text)),
        c("h1", ["heading1"], "Heading 1", "Basic", "textformat.size.larger", .line(.heading(1))),
        c("h2", ["heading2"], "Heading 2", "Basic", "textformat.size", .line(.heading(2))),
        c("h3", ["heading3"], "Heading 3", "Basic", "textformat.size.smaller", .line(.heading(3))),
        c("bullet", ["ul", "list"], "Bulleted list", "Basic", "list.bullet", .line(.bullet)),
        c("number", ["ol", "ordered"], "Numbered list", "Basic", "list.number", .line(.number)),
        c("todo", ["task", "checkbox", "check"], "To-do", "Basic", "checkmark.square", .line(.todo)),
        c("quote", ["blockquote"], "Quote", "Basic", "text.quote", .line(.quote)),
        c("code", ["codeblock"], "Code block", "Basic", "curlybraces", .code),
        c("divider", ["line", "hr", "rule"], "Divider", "Basic", "minus", .rule("---")),
        c("dashedline", ["dashed"], "Dashed line", "Basic", "line.3.horizontal", .rule("- - -")),
        c("dots", ["dotted"], "Dotted line", "Basic", "ellipsis", .rule("...")),
        c("bold", ["b", "strong"], "Bold", "Format", "bold", .inline(open: "**", close: "**")),
        c("italic", ["i", "em"], "Italic", "Format", "italic", .inline(open: "*", close: "*")),
        c("strike", ["strikethrough"], "Strikethrough", "Format", "strikethrough", .inline(open: "~~", close: "~~")),
        c("highlight", ["mark"], "Highlight", "Format", "highlighter", .inline(open: "==", close: "==")),
        c("inlinecode", ["mono"], "Inline code", "Format", "chevron.left.forwardslash.chevron.right", .inline(open: "`", close: "`")),
        c("link", ["url"], "Link", "Insert", "link", .link),
        c("note", ["wikilink"], "Link to a note", "Insert", "doc.text", .note),
        c("date", ["today"], "Date", "Insert", "calendar", .date),
        c("time", ["now"], "Time", "Insert", "clock", .time),
        c("ask", ["syncr", "explain"], "Ask Syncr", "Syncr", "sparkles", .ask),
        c("draw", ["picture", "sketch"], "Ask for a picture", "Syncr", "scribble", .draw)
    ]
    /// Built-ins, then the library's own in a last group. A user entry named like a built-in is ignored.
    static func all(user: [SlashCommand] = []) -> [EditorCommand] {
        let taken = Set(builtIn.flatMap { [$0.name] + $0.aliases })
        let mine = user.filter { !taken.contains($0.name.lowercased()) && !$0.name.isEmpty }
            .map { EditorCommand(name: $0.name, title: $0.name, group: "Yours", icon: "text.cursor", effect: .user($0.insertion)) }
        return builtIn + mine
    }
    /// What `slash.json` holds, without the old copy of the built-ins.
    static func userCommands(fromFile list: [SlashCommand]) -> [SlashCommand] { list == SlashCommand.legacyDefaults ? [] : list }
}

enum SlashEngine {
    private static func allowed(_ u: unichar) -> Bool {
        (u >= 48 && u <= 57) || (u >= 65 && u <= 90) || (u >= 97 && u <= 122) || u == 45
    }
    /// The token the caret ends, if the menu may be open for it (§3.1).
    static func token(_ text: String, selection: NSRange) -> SlashToken? {
        guard selection.length == 0 else { return nil }
        let ns = text as NSString
        let caret = min(selection.location, ns.length)
        var i = caret
        while i > 0, allowed(ns.character(at: i - 1)) { i -= 1 }
        guard i > 0, ns.character(at: i - 1) == 47 else { return nil }
        let slash = i - 1
        if slash > 0 {
            let before = ns.substring(with: NSRange(location: slash - 1, length: 1))
            guard before.rangeOfCharacter(from: .whitespacesAndNewlines) != nil else { return nil }
        }
        let (start, _) = MarkdownEditing.lineBounds(text, slash)
        let lines = Markdown.lines(text)
        guard let line = lines.first(where: { $0.range.location == start }) else { return nil }
        switch line.kind { case .code, .fence: return nil; default: break }
        for span in line.spans {
            switch span.kind {
            case .code, .link:
                if slash >= span.range.location && slash < NSMaxRange(span.range) { return nil }
            default: break
            }
        }
        return SlashToken(range: NSRange(location: slash, length: caret - slash), query: ns.substring(with: NSRange(location: slash + 1, length: caret - slash - 1)))
    }

    /// §3.3: name or alias starts with the token, then a title word does, then the letters of the token appear in
    /// the name in order; each rank keeps the catalog's order.
    static func match(_ query: String, in commands: [EditorCommand]) -> [EditorCommand] {
        let q = query.lowercased()
        guard !q.isEmpty else { return commands }
        func rank(_ cmd: EditorCommand) -> Int? {
            if ([cmd.name] + cmd.aliases).contains(where: { $0.lowercased().hasPrefix(q) }) { return 1 }
            if cmd.title.lowercased().split(separator: " ").contains(where: { $0.hasPrefix(q) }) { return 2 }
            var rest = q[...]
            for ch in cmd.name.lowercased() where ch == rest.first { rest = rest.dropFirst() }
            return rest.isEmpty ? 3 : nil
        }
        return commands.enumerated().compactMap { i, cmd in rank(cmd).map { ($0, i, cmd) } }.sorted { ($0.0, $0.1) < ($1.0, $1.1) }.map(\.2)
    }

    static func stamp(_ format: String, _ now: Date) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = format
        return f.string(from: now)
    }

    /// Runs `command` in place of `token` (an empty range at the caret when there is none). One edit.
    static func run(_ command: EditorCommand, text: String, token: SlashToken, now: Date = Date()) -> SlashOutcome {
        let ns = text as NSString
        let (start, end) = MarkdownEditing.lineBounds(text, token.range.location)
        func inline(_ insert: String, caret: NSRange? = nil, action: EditorAction? = nil) -> SlashOutcome {
            let edit = MarkdownEditing.replace(text, token.range, insert, selection: caret.map { NSRange(location: token.range.location + $0.location, length: $0.length) })
            return SlashOutcome(edit: edit, action: action)
        }
        switch command.effect {
        case .inline(let open, let close):
            return inline(open + close, caret: NSRange(location: open.utf16.count, length: 0))
        case .link: return inline("[text](url)", caret: NSRange(location: 1, length: 4))
        case .note: return inline("[[]]", caret: NSRange(location: 2, length: 0), action: .completeNote)
        case .date: return inline(stamp("yyyy-MM-dd", now))
        case .time: return inline(stamp("HH:mm", now))
        case .user(let value):
            return inline(value.replacingOccurrences(of: "{date}", with: stamp("yyyy-MM-dd", now)).replacingOccurrences(of: "{time}", with: stamp("HH:mm", now)))
        default: break
        }
        // The rest work on the line: the token comes out, and the line's words stay.
        let before = ns.substring(with: NSRange(location: start, length: token.range.location - start))
        let after = ns.substring(with: NSRange(location: NSMaxRange(token.range), length: max(0, end - NSMaxRange(token.range))))
        var kept = before + after
        if after.isEmpty { kept = before.replacingOccurrences(of: "[ \\t]+$", with: "", options: .regularExpression) }
        else if before.hasSuffix(" ") && after.hasPrefix(" ") { kept = String(before.dropLast()) + after }
        func put(_ line: String, caret: Int, tail: String = "") -> SlashOutcome {
            let range = NSRange(location: start, length: end - start)
            return SlashOutcome(edit: MarkdownEditing.replace(text, range, line + tail, selection: NSRange(location: start + caret, length: 0)), action: nil)
        }
        switch command.effect {
        case .ask, .draw:
            let out = put(kept, caret: (kept as NSString).length)
            return SlashOutcome(edit: out.edit, action: command.effect == .ask ? .ask : .draw)
        case .code, .rule:
            let block: String, caretIn: Int
            if case .rule(let mark) = command.effect { block = mark + "\n"; caretIn = block.utf16.count } else { block = "```\n\n```"; caretIn = 4 }
            if kept.trimmingCharacters(in: .whitespaces).isEmpty { return put(block, caret: caretIn) }
            let lead = (kept as NSString).length
            return put(kept, caret: lead + 1 + caretIn, tail: "\n" + block)
        case .line(let kind):
            let indent = String(kept.prefix(while: { $0 == " " || $0 == "\t" }))
            var words = String(kept.dropFirst(indent.count))
            if let m = Markdown.matches("^(#{1,6}\\s+|[-*] \\[[ xX]\\] |[-*] |[0-9]+\\. |> )", words).first { words = (words as NSString).substring(from: m.range.length) }
            let lead: String
            switch kind {
            case .text: lead = indent
            case .heading(let n): lead = String(repeating: "#", count: n) + " "
            case .bullet: lead = indent + "- "
            case .number: lead = indent + "1. "
            case .todo: lead = indent + "- [ ] "
            case .quote: lead = indent + "> "
            }
            let line = lead + words
            var out = put(line, caret: (line as NSString).length)
            if kind == .number {
                let numbered = MarkdownEditing.renumberAround(out.edit)
                let (_, e) = MarkdownEditing.lineBounds(numbered.text, start)
                out.edit = EditorEdit(text: numbered.text, selection: NSRange(location: e, length: 0))
            }
            return out
        default: return put(kept, caret: (kept as NSString).length)
        }
    }
}
