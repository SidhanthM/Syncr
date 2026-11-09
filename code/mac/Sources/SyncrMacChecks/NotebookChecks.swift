import AppKit
import Foundation
import SyncrCore

@MainActor func notebookLogicChecks() throws {
    let text = "# One\n## Two\n### Three\n#### Four\n##### Five\n###### Six\n**bold** *italic* ~~strike~~ ==highlight== `code`\n- item\n* item\n1. item\n    - nested\n- [ ] task\n- [x] done\n> quote\n```swift\nlet answer = 42\n```\n---\n- - -\n...\n[text](https://example.com) https://example.com [[Note]] [[Note|alias]] #tag #nested/tag"
    let lines = Markdown.lines(text)
    for i in 0..<6 { try check(lines[i].kind == .heading(i + 1), "heading \(i + 1)") }
    try check(lines[6].spans.count == 5, "all inline styles")
    try check(lines[7].kind == .bullet && lines[8].kind == .bullet && lines[9].kind == .numbered(1), "lists")
    try check(lines[11].kind == .task(false) && lines[12].kind == .task(true), "tasks")
    try check(lines[13].kind == .quote && lines[14].kind == .fence("swift") && lines[15].kind == .code("swift"), "quote/fence/code")
    for i in 17...19 { if case .rule = lines[i].kind {} else { throw CheckFailure(description: "rule \(i)") } }
    try check(lines[20].spans.count == 6, "links, wiki and tags")
    try check(Markdown.lines("```\n[[not a link]] #notatag\n```")[1].spans.isEmpty, "code not indexed")
    try check(Markdown.inline("![[embed]]").isEmpty, "embeds plain")
    try check(Markdown.lines("---\ntags: #plain\n---\n# Heading").prefix(3).allSatisfy { $0.kind == .plain && $0.spans.isEmpty }, "front matter stays plain")
    let hidden = DocumentLayout.attributed("# Heading\n**bold**", caret: 12)
    let revealed = DocumentLayout.attributed("# Heading\n**bold**", caret: 0)
    try check((hidden.attribute(.font, at: 0, effectiveRange: nil) as? NSFont)?.pointSize == 0.01 && (revealed.attribute(.font, at: 0, effectiveRange: nil) as? NSFont)?.pointSize ?? 0 > 1, "caret line syntax revealed, other syntax hidden")
    for (old, new) in [("abc", "axc"), ("", "é🦉"), ("a👨‍👩‍👧‍👦éz", "a👨‍👩‍👧‍👦ëz"), ("abc", ""), ("é", "e\u{301}")] {
        if let s = TextSplice.between(old, new) {
            var bytes = Data(old.utf8); bytes.replaceSubrange(Int(s.offset)..<Int(s.offset + s.delete), with: s.insert.utf8)
            try check(bytes == Data(new.utf8), "UTF-8 splice \(old) -> \(new)")
        } else { try check(Data(old.utf8) == Data(new.utf8), "byte-distinct Unicode must splice") }
    }
    func enter(_ t: String) -> String { MarkdownEditing.enter(t, selection: NSRange(location: t.utf16.count, length: 0)).text }
    try check(enter("- item") == "- item\n- " && enter("- ") == "", "continue/end bullet")
    try check(enter("- [x] done") == "- [x] done\n- [ ] " && enter("- [ ] ") == "", "continue/end task")
    try check(enter("1. one") == "1. one\n2. ", "continue numbers")
    try check(MarkdownEditing.renumber("1. a\n8. b\n    1. c\n    9. d\n7. e") == "1. a\n2. b\n    1. c\n    2. d\n3. e", "nested renumber")
    let indented = MarkdownEditing.indent("- a\n- b", selection: NSRange(location: 0, length: 7), outdent: false)
    try check(indented.text == "    - a\n    - b", "indent selection")
    try check(MarkdownEditing.indent(indented.text, selection: indented.selection, outdent: true).text == "- a\n- b", "outdent selection")
    let wrapped = MarkdownEditing.wrap("hello", selection: NSRange(location: 0, length: 5), left: "**", right: "**")
    try check(wrapped.text == "**hello**" && wrapped.selection == NSRange(location: 2, length: 5), "pair around selection")
    try check(MarkdownEditing.task("- item", selection: NSRange(location: 0, length: 0)).text == "- [ ] item", "task transform")
    let index = NotebookIndex(notes: [IndexedNote(id: "b", title: "Note", text: "# Start\n[[Other|shown]] #nested/tag\n```\n#hidden\n```", writable: true), IndexedNote(id: "a", title: "Other", text: "hello #nested/tag", writable: true), IndexedNote(id: "c", title: "Phone", text: "hidden", writable: false)])
    try check(index.resolve("nOtE")?.id == "b" && index.completions("ot").count == 1, "title resolution and completion")
    try check(index.backlinks(to: "OTHER").first?.line == "[[Other|shown]] #nested/tag", "backlink with linking line")
    try check(index.tags.count == 1 && index.tags[0].1 == 2 && index.tagged("nested/tag").count == 2, "tag counts per note")
    try check(index.search("hidden").count == 1 && index.search("Phone").first?.id == "c", "phone title-only search")
    try check(NotebookIndex.outline(index.notes[0].text) == [OutlineHeading(title: "Start", level: 1, offset: 0)], "outline excludes fences")
    try check(Markdown.rewriteLinks("[[Note]] [[NOTE|shown]] ![[Note]] `[[Note]]`", from: "note", to: "Renamed") == "[[Renamed]] [[Renamed|shown]] ![[Note]] `[[Note]]`", "rename preserves aliases and plain code/embeds")
    try check(NotebookIndex.dailyTitle(Date(timeIntervalSince1970: 0), timeZone: TimeZone(secondsFromGMT: 0)!) == "1970-01-01", "daily naming")
    let page = DocumentPage(title: "Title", text: text)
    let layout = DocumentLayout.lines(note: page, width: 1400)
    try check(layout.count >= lines.count - 1 && layout[0].rect.minX == 100 && layout[0].rect.minY >= 260, "offscreen layout canvas coordinates")
    let image = DocumentLayout.image(note: page, width: 1400)
    try check(image.size.width == 800 && image.tiffRepresentation != nil, "offscreen page image")
    let memory = MemoryTextDocument(text), editor = DocumentEditor()
    editor.show(document: memory, title: "Title", width: 1600)
    editor.debugSetText("typed 🦉"); editor.flush()
    try check(memory.text == "typed 🦉", "TextKit seam writes memory document")
    editor.debugSetText("")
    for c in ["*", "*", "word", "*", "*"] { editor.textView.insertText(c, replacementRange: editor.textView.selectedRange()) }
    try check(editor.textView.string == "**word**", "system input closes a double Markdown pair")
    editor.debugSetText("typed 🦉")
    memory.writable = false; editor.show(document: memory, title: "Locked", width: 1600); editor.debugSetText("refused")
    try check(!editor.textView.isEditable && memory.text == "typed 🦉", "read-only no edits")
    let session = TextSession(text: "old", writable: true)
    var writes: [String] = [], callbacks: [@MainActor (Result<Void, Error>) -> Void] = []
    session.save = { text, done in writes.append(text); callbacks.append(done) }
    session.replace(with: "first"); session.flush()
    session.replace(with: "second"); var flushed: Bool?
    session.flush { flushed = $0 }
    try check(writes == ["first"] && session.saving && flushed == nil, "in-flight save never blocks editor")
    callbacks.removeFirst()(.success(()))
    try check(writes == ["first", "second"] && flushed == nil, "flush serializes latest buffer")
    callbacks.removeFirst()(.failure(CocoaError(.fileWriteNoPermission)))
    try check(flushed == false && session.text == "second" && session.dirty && session.status.hasPrefix("Not saved:"), "failed write keeps editor text")
    session.flush(); callbacks.removeFirst()(.success(()))
    try check(session.status == "Saved" && !session.dirty, "failed write can retry")
    print("Notebook logic checks: OK (Markdown rows, editing, slash, Unicode splice, index, offscreen TextKit, memory seam)")
}

@MainActor func markdownMappingChecks() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("notebook-vault-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: dir) }
    let vault = dir.appendingPathComponent("Vault"), nested = vault.appendingPathComponent("Work/Deep")
    try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)
    let text = "# Café 🦉\n\n[[Second|alias]] #work/project\n- [ ] task\n```swift\nlet n = 1\n```\n"
    try Data(text.utf8).write(to: vault.appendingPathComponent("First.md"))
    try Data("nested\n".utf8).write(to: nested.appendingPathComponent("Second.md"))
    try Data([1, 2]).write(to: vault.appendingPathComponent("attachment.png"))
    let before = try Data(contentsOf: vault.appendingPathComponent("First.md"))
    let mapped = try MarkdownImport.map(vault)
    try check(mapped.entries.map(\.title) == ["First", "Second"], "vault titles")
    try check(mapped.entries.map(\.notebook) == ["Vault", "Vault › Work › Deep"], "vault notebook paths: \(mapped.entries.map(\.notebook))")
    try check(mapped.skipped == ["attachment.png"], "attachment skipped and listed")
    let export = dir.appendingPathComponent("roundtrip.md")
    try MarkdownImport.export(mapped.entries[0].text, to: export)
    try check(try Data(contentsOf: export) == before && MarkdownImport.map(export).entries[0].text == text, "byte-identical Markdown roundtrip")
    try check(try Data(contentsOf: vault.appendingPathComponent("First.md")) == before, "source never modified")
    let bomText = "\u{FEFF}Unicode e\u{301}\n"
    try MarkdownImport.export(bomText, to: export)
    try check(try MarkdownImport.map(export).entries[0].text.utf8.elementsEqual(bomText.utf8), "leading BOM scalar survives export/import")
    try FileManager.default.createSymbolicLink(at: vault.appendingPathComponent("linked.md"), withDestinationURL: export)
    try check(try MarkdownImport.map(vault).skipped.contains("linked.md"), "symlink skipped without reading target")
    print("Markdown mapping checks: OK (vault, nested notebooks, skipped attachment, byte roundtrip)")
}
