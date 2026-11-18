import AppKit
import Foundation

/// editor-v2 §3.6 (S1...S20) and §4 (T1...T18), one check per number, over (text, selection) in the contract's
/// notation: `|` the caret, `«…»` a selection. `swift run SyncrMacChecks --editor` runs only these.
private struct Marked {
    var text: String
    var selection: NSRange
    init(_ source: String) {
        var out = "", from: Int?, to: Int?, caret: Int?
        for ch in source {
            switch ch {
            case "|": caret = out.utf16.count
            case "«": from = out.utf16.count
            case "»": to = out.utf16.count
            default: out.append(ch)
            }
        }
        text = out
        if let from, let to { selection = NSRange(location: from, length: to - from) }
        else { selection = NSRange(location: caret ?? out.utf16.count, length: 0) }
    }
    init(_ edit: EditorEdit) { text = edit.text; selection = edit.selection }
    var edit: EditorEdit { EditorEdit(text: text, selection: selection) }
    var shown: String {
        let ns = text as NSString
        if selection.length == 0 { return ns.replacingCharacters(in: selection, with: "|") }
        return ns.substring(to: selection.location) + "«" + ns.substring(with: selection) + "»" + ns.substring(from: NSMaxRange(selection))
    }
}

private func expect(_ got: String, _ want: String, _ name: String) throws {
    try check(got == want, "\(name): got \(got.debugDescription), wanted \(want.debugDescription)")
}

private let noon = Date(timeIntervalSince1970: 1_763_298_300)   // 2025-11-16 in UTC-ish; formatted in the local zone below
private func local(_ format: String) -> String { SlashEngine.stamp(format, noon) }

/// Runs the first row the menu would list for the token at the caret, as Enter does.
private func slash(_ before: String, row: Int = 0) throws -> SlashOutcome {
    let m = Marked(before)
    guard let token = SlashEngine.token(m.text, selection: m.selection) else { throw CheckFailure(description: "no token in \(before)") }
    let found = SlashEngine.match(token.query, in: SlashCatalog.all())
    guard found.indices.contains(row) else { throw CheckFailure(description: "no row \(row) for \(before)") }
    return SlashEngine.run(found[row], text: m.text, token: token, now: noon)
}

@MainActor func editorRuleChecks() throws {
    let everything = SlashCatalog.all()
    // §3.1 to §3.3
    do {
        let one = Marked("/")
        let t = SlashEngine.token(one.text, selection: NSRange(location: 1, length: 0))
        try check(t?.query == "" && SlashEngine.match("", in: everything).count == everything.count, "S1: a lone slash opens the menu with every command")
        try check(Set(everything.map(\.group)) == ["Basic", "Format", "Insert", "Syncr"] && everything.count == 23, "S1: the groups and 23 commands of §3.5")
    }
    try expect(Marked(try slash("Buy milk /to|").edit).shown, "- [ ] Buy milk|", "S2")
    try expect(Marked(try slash("/h2|").edit).shown, "## |", "S3")
    try expect(Marked(try slash("## Title /h1|").edit).shown, "# Title|", "S4")
    try expect(Marked(try slash("- item /text|").edit).shown, "item|", "S5")
    try expect(Marked(try slash("3. step /bullet|").edit).shown, "- step|", "S6")
    try expect(Marked(try slash("    - nested /todo|").edit).shown, "    - [ ] nested|", "S7")
    try expect(Marked(try slash("> quoted /h3|").edit).shown, "### quoted|", "S8")
    try expect(Marked(try slash("Some text /code|").edit).shown, "Some text\n```\n|\n```", "S9")
    try expect(Marked(try slash("/divider|").edit).shown, "---\n|", "S10")
    try expect(Marked(try slash("Text /divider|").edit).shown, "Text\n---\n|", "S11")
    try expect(Marked(try slash("Meet on /date|").edit).shown, "Meet on \(local("yyyy-MM-dd"))|", "S12")
    do {
        let out = try slash("see /link|")
        try expect(Marked(out.edit).shown, "see [«text»](url)", "S13")
    }
    do {
        for blocked in ["word/h1|", "http://a.b/h|", "```\n/h|\n```", "`a /h|`", "[x](/h|)", "a [b](c /h|)"] {
            let m = Marked(blocked)
            try check(SlashEngine.token(m.text, selection: m.selection) == nil, "S14: no menu in \(blocked.debugDescription)")
        }
        let m = Marked("a\n/h|"); try check(SlashEngine.token(m.text, selection: m.selection) != nil, "S14: but a new line opens it")
    }
    do {
        let m = Marked("/zz|")
        let t = SlashEngine.token(m.text, selection: m.selection)!
        try check(SlashEngine.match(t.query, in: everything).isEmpty && m.text == "/zz", "S15: nothing matches, the menu closes, the text stays")
    }
    try expect(Marked(try slash("a /bold|").edit).shown, "a **|**", "S17")
    do {
        let out = try slash("Why is V negative? /ask|")
        try expect(Marked(out.edit).shown, "Why is V negative?|", "S18"); try check(out.action == .ask, "S18: the ask starts")
        try check(try slash("x /draw|").action == .draw && (try slash("x /note|").action == .completeNote), "S18: /draw and /note actions")
    }
    do {
        func names(_ q: String) -> [String] { SlashEngine.match(q, in: everything).map(\.title) }
        try check(names("to").first == "To-do", "S19: /to lists To-do first")
        try check(names("ch").first == "To-do", "S19: /ch lists To-do (alias)")
        try check(Array(names("h").prefix(3)) == ["Heading 1", "Heading 2", "Heading 3"], "S19: /h lists Heading 1, 2, 3 first")
        try check(names("ol").first == "Numbered list" && names("bu").first == "Bulleted list", "matching: aliases and names")
        try check(names("lnk").contains("Link"), "matching: letters in order (rank 3)")
        try check(names("syn").first == "Ask Syncr", "matching: alias rank 1")
        try check(names("dr").first == "Ask for a picture" && names("pic").first == "Ask for a picture", "matching: name and alias")
        try check(names("lis").first == "Bulleted list", "matching: alias list")
    }
    // User commands: a last group, built-in names win, the old file counts as empty.
    do {
        let mine = SlashCatalog.all(user: [SlashCommand(name: "sig", insertion: "-- {date}"), SlashCommand(name: "h1", insertion: "no")])
        try check(mine.count == everything.count + 1 && mine.last?.group == "Yours" && mine.filter { $0.name == "h1" }.count == 1, "user commands: last group, built-in names win")
        try check(SlashCatalog.userCommands(fromFile: SlashCommand.legacyDefaults).isEmpty, "user commands: the old defaults file is empty")
        let token = SlashToken(range: NSRange(location: 0, length: 4), query: "sig")
        try expect(SlashEngine.run(mine.last!, text: "/sig", token: token, now: noon).edit.text, "-- \(local("yyyy-MM-dd"))", "user command insertion")
    }
    // Every command leaves no token and no placeholder behind.
    for cmd in everything {
        let out = SlashEngine.run(cmd, text: "x /\(cmd.name)", token: SlashToken(range: NSRange(location: 2, length: cmd.name.utf16.count + 1), query: cmd.name), now: noon)
        try check(!out.edit.text.contains("/\(cmd.name)") && !out.edit.text.contains("{"), "command \(cmd.name) removes its token")
    }
    try expect(Marked(try slash("1. a\n2. b\nc /number|").edit).shown, "1. a\n2. b\n3. c|", "number continues the list")

    // §4
    func enter(_ s: String) -> String { Marked(MarkdownEditing.enter(Marked(s).text, selection: Marked(s).selection)).shown }
    try expect(enter("- one|"), "- one\n- |", "T1")
    try expect(enter("- [x] done|"), "- [x] done\n- [ ] |", "T2")
    try expect(enter("3. three|"), "3. three\n4. |", "T3")
    try expect(enter("- on|e"), "- on\n- |e", "T4")
    try expect(enter("- one\n- |"), "- one\n|", "T5")
    try expect(enter("- one\n    - |"), "- one\n- |", "T6")
    try expect(enter("> quote|"), "> quote\n> |", "T7"); try expect(enter("> |"), "|", "T7 empty quote")
    func tab(_ s: String, out: Bool) -> String { let m = Marked(s); return Marked(MarkdownEditing.indent(m.text, selection: m.selection, outdent: out)).shown }
    try expect(tab("- one|", out: false), "    - one|", "T8 indent"); try expect(tab("    - one|", out: true), "- one|", "T8 outdent")
    try expect(tab("- a\n- b|", out: false), "- a\n    - b|", "T8 only the caret's line")
    try expect(tab("«- a\n- b»", out: false), "«    - a\n    - b»", "T8 selection")
    do {
        let m = Marked("1. a\n1. b\n5. c!")
        try expect(Marked(MarkdownEditing.renumberAround(m.edit)).shown, "1. a\n2. b\n3. c!|", "T9")
        let other = Marked("5. x\n9. y\n\ntext|")
        try check(MarkdownEditing.renumberAround(other.edit).text == other.text, "T9: other lists are left alone")
        try expect(Marked(MarkdownEditing.renumberAround(Marked("4. a|\n4. b").edit)).shown, "4. a|\n5. b", "T9: counts on from its first number")
    }
    func back(_ s: String) -> String? { let m = Marked(s); return MarkdownEditing.backspace(m.text, selection: m.selection).map { Marked($0).shown } }
    try expect(back("- |text") ?? "nil", "|text", "T10")
    try expect(back("> |q") ?? "nil", "|q", "T10 quote"); try check(back("a|b") == nil, "T10: plain backspace is left to the text system")
    func bold(_ s: String) -> String { let m = Marked(s); return Marked(MarkdownEditing.toggle(m.text, selection: m.selection, open: "**", close: "**")).shown }
    try expect(bold("«word»"), "**«word»**", "T11"); try expect(bold("**«word»**"), "«word»", "T11 again")
    try expect(bold("wo|rd"), "**wo|rd**", "T12"); try expect(bold("**wo|rd**"), "wo|rd", "T12 again")
    func toggled(_ s: String, _ o: String, _ c: String) -> String { let m = Marked(s); return Marked(MarkdownEditing.toggle(m.text, selection: m.selection, open: o, close: c)).shown }
    for (o, c, name) in [("*", "*", "italic"), ("~~", "~~", "strike"), ("==", "==", "highlight"), ("`", "`", "inline code")] {
        let on = toggled("«word»", o, c)
        try expect(on, o + "«word»" + c, "T11 \(name)"); try expect(toggled(on, o, c), "«word»", "T11 \(name) again")
    }
    try expect(toggled("**«word»**", "*", "*"), "***«word»***", "italic over bold wraps (a bold word is not italic)")
    try expect(toggled("***«word»***", "*", "*"), "**«word»**", "italic off a bold-italic word")
    func link(_ s: String) -> String { let m = Marked(s); return Marked(MarkdownEditing.link(m.text, selection: m.selection)).shown }
    try expect(link("«word»"), "[word](«url»)", "T11 link"); try expect(link("[«word»](url)"), "«word»", "T11 link again")
    for (typed, close) in [("(", ")"), ("[", "]"), ("{", "}"), ("\"", "\""), ("`", "`"), ("*", "*"), ("~", "~"), ("=", "=")] {
        let m = Marked("«word»")
        guard let edit = MarkdownEditing.type(m.text, selection: m.selection, character: typed) else { throw CheckFailure(description: "T13: \(typed) does nothing") }
        try expect(Marked(edit).shown, typed + "«word»" + close, "T13 \(typed)")
    }
    do {
        let m = Marked("|")
        try expect(Marked(MarkdownEditing.type(m.text, selection: m.selection, character: "(")!).shown, "(|)", "T13 empty pair")
        try check(MarkdownEditing.type("a ", selection: NSRange(location: 2, length: 0), character: "=") == nil, "T13: a lone = is an ordinary character")
        try check(MarkdownEditing.type("don", selection: NSRange(location: 3, length: 0), character: "'") == nil, "T13: an apostrophe in a word is not a pair")
        let c = Marked("(|)")
        try expect(back("(|)") ?? "nil", "|", "T14 backspace")
        try expect(Marked(MarkdownEditing.type(c.text, selection: c.selection, character: ")")!).shown, "()|", "T14 steps over")
    }
    do {
        let m = Marked("«word»")
        try expect(Marked(MarkdownEditing.pasteLink(m.text, selection: m.selection, pasted: "https://a.b/c")!).shown, "[word](https://a.b/c)|", "T15")
        try check(MarkdownEditing.pasteLink(m.text, selection: m.selection, pasted: "not a url") == nil && MarkdownEditing.pasteLink("x|", selection: NSRange(location: 1, length: 0), pasted: "https://a.b") == nil, "T15: only an address over selected words")
    }
    try expect(enter("```|"), "```\n|\n```", "T16")
    try expect(enter("```swift|"), "```swift\n|\n```", "T16 language")
    try expect(enter("```\nx\n```|"), "```\nx\n```\n|", "T16: a closing fence just breaks the line")
    do {
        func cycle(_ s: String) -> String { let m = Marked(s); return Marked(MarkdownEditing.task(m.text, selection: m.selection)).shown }
        let a = cycle("- a|"); let b = cycle(a); let c = cycle(b)
        try check([a, b, c] == ["- [ ] a|", "- [x] a|", "- [ ] a|"], "T17: \([a, b, c])")
        try expect(cycle("    - nest|"), "    - [ ] nest|", "T17 keeps the indent"); try expect(cycle("plain|"), "- [ ] plain|", "T17 plain line")
    }
    do {
        let text = "- [ ] a\nmore", flipped = MarkdownEditing.flipBox(text, lineStart: 0)
        try check(flipped == "- [x] a\nmore" && flipped?.utf16.count == text.utf16.count, "T18: the box flips in place, same length")
    }
    // The pieces the editor lives on: where a selection goes when text is replaced from outside (undo, sync).
    try check(DocumentEditor.carry("- [ ] Buy milk", "Buy milk /to", NSRange(location: 14, length: 0)) == NSRange(location: 12, length: 0), "S20: after undo the caret is at the end of the restored token")
    try check(DocumentEditor.carry("a b c", "a X b c", NSRange(location: 5, length: 0)) == NSRange(location: 7, length: 0) && DocumentEditor.carry("a b c", "a X b c", NSRange(location: 1, length: 0)).location == 1, "carry: before and after a change")

    try editorSurfaceChecks()
    try motionChecks()
    try penRuleChecks()
    try pasteChecks()
    try panelPointerChecks()
    print("Editor v2 checks: S1-S20 and T1-T18 OK")
}

/// The same rules through a real `DocumentEditor` (headless): keys, panel and undo steps.
@MainActor private func editorSurfaceChecks() throws {
    let memory = MemoryTextDocument("")
    let editor = DocumentEditor()
    let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 1000, height: 800), styleMask: [.titled], backing: .buffered, defer: true)
    let host = NSView(frame: CGRect(x: 0, y: 0, width: 1000, height: 800)); window.contentView = host
    editor.frame = host.bounds; host.addSubview(editor)
    editor.show(document: memory, title: "T", width: 1600)
    let view = editor.textView
    window.makeFirstResponder(view)
    func set(_ s: String) { let m = Marked(s); editor.debugSetText(m.text); view.setSelectedRange(m.selection) }
    func shown() -> String { Marked(EditorEdit(text: view.string, selection: view.selectedRange())).shown }
    func key(_ s: Selector) { _ = editor.textView(view, doCommandBy: s) }
    set("Buy milk |")
    view.insertText("/", replacementRange: view.selectedRange())
    try check(!editor.debugMenu().isEmpty, "S1 (editor): typing / opens the menu; text=\(view.string.debugDescription) sel=\(view.selectedRange())")
    view.insertText("to", replacementRange: view.selectedRange())
    try check(editor.debugMenu().first == "To-do", "S19 (editor): To-do first")
    key(#selector(NSResponder.cancelOperation(_:)))
    try check(editor.debugMenu().isEmpty && shown() == "Buy milk /to|", "Escape closes and leaves the token")
    view.insertText("d", replacementRange: view.selectedRange())
    try check(editor.debugMenu().isEmpty, "S16 (editor): after Escape it does not reopen for that token")
    view.insertText("o", replacementRange: view.selectedRange())
    try expect(shown(), "Buy milk /todo|", "S16 text")
    set("x |")
    view.insertText("/", replacementRange: view.selectedRange()); view.insertText("t", replacementRange: view.selectedRange()); view.insertText("o", replacementRange: view.selectedRange())
    key(#selector(NSResponder.insertNewline(_:)))
    try expect(shown(), "- [ ] x|", "S2 (editor): Enter runs the first row")
    try check(editor.debugMenu().isEmpty, "the menu closes after running")
    set("/zz|")
    view.insertText("z", replacementRange: view.selectedRange())
    try check(editor.debugMenu().isEmpty && view.string == "/zzz", "S15 (editor)")
    set("- one|"); key(#selector(NSResponder.insertNewline(_:)))
    try expect(shown(), "- one\n- |", "T1 (editor)")
    key(#selector(NSResponder.insertNewline(_:)))
    try expect(shown(), "- one\n|", "T5 (editor)")
    set("1. a\n1. b\n5. c|"); view.insertText("x", replacementRange: view.selectedRange())
    try expect(shown(), "1. a\n2. b\n3. cx|", "T9 (editor)")
    set("«word»"); view.insertText("(", replacementRange: view.selectedRange())
    try expect(shown(), "(«word»)", "T13 (editor)")
    set("(|)"); key(#selector(NSResponder.deleteBackward(_:)))
    try expect(shown(), "|", "T14 (editor)")
    set("```|"); key(#selector(NSResponder.insertNewline(_:)))
    try expect(shown(), "```\n|\n```", "T16 (editor)")
    // A task box click: flips, and the caret stays.
    set("- [ ] a\nmore|")
    view.onTask?(0)
    try expect(shown(), "- [x] a\nmore|", "T18 (editor): the caret does not move")
    // Menu rows are chosen with the arrow keys, which wrap.
    set("|"); view.insertText("/", replacementRange: view.selectedRange())
    let first = editor.debugMenu()
    key(#selector(NSResponder.moveUp(_:)))
    key(#selector(NSResponder.moveDown(_:)))
    try check(first.count == 23 && first.first == "Text", "menu lists every command with an empty token (\(first.count))")
    key(#selector(NSResponder.cancelOperation(_:)))
}

/// Part R: one table of durations, and Reduce motion: no dissolve, no wait.
@MainActor private func motionChecks() throws {
    try check(abs(Motion.leave - Motion.reveal * 2 / 3) < 1e-9 && Motion.menu == 0.15 && Motion.reveal == Dither.revealSeconds, "Motion: leaving takes two thirds of arriving; the menu fade is 150 ms")
    let saved = AppPreferences.shared.values.reduceMotion
    try AppPreferences.shared.update { $0.reduceMotion = true }
    defer { try? AppPreferences.shared.update { $0.reduceMotion = saved } }
    let view = NSView(frame: CGRect(x: 0, y: 0, width: 200, height: 100)); view.wantsLayer = true
    var done = false
    Dither.dissolveOut(view) { done = true }
    try check(done && view.layer?.mask == nil, "Reduce motion: a card leaves at once, with no mask and no animation")
    Dither.reveal(view)
    try check(view.layer?.mask == nil && view.layer?.animationKeys() == nil, "Reduce motion: a card arrives at once")
    let panel = CommandPanel(frame: CGRect(x: 0, y: 0, width: 300, height: 100))
    panel.fade(in: true)
    try check(!panel.isHidden && panel.layer?.animationKeys() == nil, "Reduce motion: the menu appears without a fade")
}

/// §8: nothing dissolves on the page while the pen is down or was active in the last 150 ms.
@MainActor private func penRuleChecks() throws {
    try check(Dither.pen(strokeOpen: true, idleNs: nil) && Dither.pen(strokeOpen: false, idleNs: 100_000_000)
              && !Dither.pen(strokeOpen: false, idleNs: 200_000_000) && !Dither.pen(strokeOpen: false, idleNs: nil), "pen rule: open stroke, or active within 150 ms")
    let view = NSView(frame: CGRect(x: 0, y: 0, width: 200, height: 100)); view.wantsLayer = true
    Dither.penBusy = { false }
    Dither.reveal(view)
    try check(view.layer?.mask != nil, "pen rule: with the pen up a card dissolves in (mask on)")
    view.layer?.mask = nil; view.layer?.removeAllAnimations()
    Dither.penBusy = { true }
    defer { Dither.penBusy = { false } }
    Dither.reveal(view)
    try check(view.layer?.mask == nil && view.layer?.animationKeys() == nil, "pen rule: with the pen down a card lands finished")
    var done = false
    Dither.dissolveOut(view) { done = true }
    try check(done && view.layer?.mask == nil, "pen rule: with the pen down a card leaves at once")
    Dither.fade(view)
    try check(view.layer?.animationKeys() == nil, "pen rule: no text fade under the pen either")
    // The real wiring: a frame from the phone makes the pen busy, and it quiets again.
    if let device = MTLCreateSystemDefaultDevice() {
        let engine = LiveInk(device: device)
        Dither.attach(pen: engine)
        try check(!Dither.penBusy(), "pen rule: idle engine is not busy")
        engine.receive(Data([0]), at: monotonicNs())
        try check(Dither.penBusy(), "pen rule: a live frame just arrived is busy")
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.25))
        try check(!Dither.penBusy(), "pen rule: quiet again after 150 ms")
    }
}

/// T15 through the real Paste path (a private pasteboard, the general one is never touched).
@MainActor private func pasteChecks() throws {
    let memory = MemoryTextDocument("")
    let editor = DocumentEditor()
    let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 1000, height: 800), styleMask: [.titled], backing: .buffered, defer: true)
    let host = NSView(frame: CGRect(x: 0, y: 0, width: 1000, height: 800)); window.contentView = host
    editor.frame = host.bounds; host.addSubview(editor)
    editor.show(document: memory, title: "T", width: 1600)
    let view = editor.textView
    window.makeFirstResponder(view)
    let board = NSPasteboard(name: NSPasteboard.Name("dev.syncr.mac.checks.paste"))
    defer { board.clearContents() }
    view.pasteboard = board
    func set(_ s: String) { let m = Marked(s); editor.debugSetText(m.text); view.setSelectedRange(m.selection) }
    func shown() -> String { Marked(EditorEdit(text: view.string, selection: view.selectedRange())).shown }
    board.clearContents(); board.setString("https://a.b/c", forType: .string)
    set("«word»"); view.paste(nil)
    try expect(shown(), "[word](https://a.b/c)|", "T15 (editor, real paste)")
    try check(memory.text == "[word](https://a.b/c)" || view.string == "[word](https://a.b/c)", "T15: the text is the link")
    set("see |")
    board.clearContents(); board.setString("https://a.b/c", forType: .string)
    view.paste(nil)
    try check(view.string == "see https://a.b/c", "T15: with nothing selected the address is pasted as it is (from the same pasteboard)")
}

/// The panel under a pointer: hover, click, scroll past eight rows, and flipping above near the bottom.
@MainActor private func panelPointerChecks() throws {
    let memory = MemoryTextDocument("")
    let editor = DocumentEditor()
    let window = NSWindow(contentRect: CGRect(x: 0, y: 0, width: 1000, height: 800), styleMask: [.titled], backing: .buffered, defer: true)
    let host = NSView(frame: CGRect(x: 0, y: 0, width: 1000, height: 800)); window.contentView = host
    editor.frame = host.bounds; host.addSubview(editor)
    editor.show(document: memory, title: "T", width: 1600)
    let view = editor.textView
    window.makeFirstResponder(view)
    func set(_ s: String) { let m = Marked(s); editor.debugSetText(m.text); view.setSelectedRange(m.selection) }
    func lineRect(_ char: Int) -> CGRect {
        let manager = view.layoutManager!
        let glyph = manager.glyphIndexForCharacter(at: char)
        return view.convert(manager.lineFragmentRect(forGlyphAt: glyph, effectiveRange: nil), to: host)
    }
    func event(_ type: NSEvent.EventType, at point: CGPoint) -> NSEvent {
        NSEvent.mouseEvent(with: type, location: point, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, eventNumber: 1, clickCount: 1, pressure: 1)!
    }
    func inWindow(_ panel: CommandPanel, _ rect: CGRect) -> CGPoint { panel.convert(CGPoint(x: rect.midX, y: rect.midY), to: nil) }
    func key(_ s: Selector) { _ = editor.textView(view, doCommandBy: s) }

    // Below the line, with room.
    set("|"); view.insertText("/", replacementRange: view.selectedRange())
    let panel = editor.debugPanel
    try check(!panel.isHidden && panel.superview === host, "panel: open, in the unscaled host")
    var line = lineRect(0)
    let below = host.isFlipped ? panel.frame.minY >= line.maxY : panel.frame.maxY <= line.minY
    try check(below, "panel: under the caret line when there is room (panel \(panel.frame), line \(line))")
    try check(abs(panel.frame.width - CommandPanel.width) < 0.5 && abs(panel.frame.minX - line.minX) < 2, "panel: 320 wide, left edge at the slash")

    // Hover selects.
    let r3 = panel.debugRowRect(3)
    panel.mouseMoved(with: event(.mouseMoved, at: inWindow(panel, r3)))
    try check(panel.debugSelected == 3, "panel: hovering row 3 selects it (selected \(panel.debugSelected))")

    // Scroll with the wheel: the list moves, the selection does not.
    let before = panel.debugScroll, selected = panel.debugSelected
    if let cg = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 1, wheel1: -60, wheel2: 0, wheel3: 0), let wheel = NSEvent(cgEvent: cg) {
        panel.scrollWheel(with: wheel)
        try check(panel.debugScroll != before && panel.debugSelected == selected, "panel: the wheel scrolls the list, not the selection (scroll \(before) -> \(panel.debugScroll))")
        if let up = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 1, wheel1: 5000, wheel2: 0, wheel3: 0), let e = NSEvent(cgEvent: up) {
            panel.scrollWheel(with: e)
            try check(panel.debugScroll == 0, "panel: scrolling past the top stops at the top")
        }
    } else { throw CheckFailure(description: "no scroll event") }

    // Arrow keys past eight rows: the chosen row stays in view, and it wraps.
    for _ in 0..<14 { key(#selector(NSResponder.moveDown(_:))) }
    var rect = panel.debugRowRect(panel.debugSelected)
    try check(panel.debugSelected == 17 && rect.minY >= 0 && rect.maxY <= panel.bounds.height, "panel: row 17 of 23 is in view after 14 Downs (\(rect), height \(panel.bounds.height))")
    for _ in 0..<7 { key(#selector(NSResponder.moveDown(_:))) }
    try check(panel.debugSelected == 24 % 23 && panel.debugRowRect(panel.debugSelected).minY >= 0, "panel: wraps to the first row and scrolls back to it")
    for _ in 0..<2 { key(#selector(NSResponder.moveUp(_:))) }
    rect = panel.debugRowRect(panel.debugSelected)
    try check(panel.debugSelected == 22 && rect.maxY <= panel.bounds.height && rect.minY >= 0, "panel: Up from the top wraps to the last row, in view")

    // A real click on a row runs it (the list is scrolled back to the top first, by wrapping Down).
    key(#selector(NSResponder.moveDown(_:)))
    let h1 = panel.debugRowRect(1)
    let point = inWindow(panel, h1)
    // An offscreen window does not route events, so the window's own hit test finds the view and the event goes to it.
    guard let frameView = window.contentView?.superview, let hit = frameView.hitTest(point) else { throw CheckFailure(description: "panel: nothing under the pointer: point \(point) panel in window \(panel.convert(panel.bounds, to: nil)) hidden \(panel.isHidden) frameView \(String(describing: window.contentView?.superview?.frame))") }
    try check(hit === panel, "panel: the pointer over a row hits the panel, not the page (\(type(of: hit)))")
    hit.mouseDown(with: event(.leftMouseDown, at: point))
    hit.mouseUp(with: event(.leftMouseUp, at: point))
    try check(view.string == "# ", "panel: clicking Heading 1 ran it (text \(view.string.debugDescription))")
    try check(editor.debugMenu().isEmpty, "panel: closed after the click")

    // Near the bottom of the view it flips above the line.
    set("\n\n\n\n\n|"); view.insertText("/", replacementRange: view.selectedRange())
    line = lineRect(5)
    let above = host.isFlipped ? panel.frame.maxY <= line.minY : panel.frame.minY >= line.maxY
    try check(!panel.isHidden && above, "panel: above the line near the bottom (panel \(panel.frame), line \(line))")
    try check(panel.frame.minY >= 0 && panel.frame.maxY <= host.bounds.height, "panel: stays inside the view")
    key(#selector(NSResponder.cancelOperation(_:)))
}
