import AppKit
import Foundation
import SyncrCore

@MainActor private func pump(_ name: String, until condition: () -> Bool) throws {
    let end = Date().addingTimeInterval(15)
    while !condition() {
        if Date() > end { throw CheckFailure(description: "notebook timed out: " + name) }
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.002))
    }
}
@MainActor private func snapshot(_ model: LibraryModel, _ id: String) throws -> NoteSnapshot {
    var result: Result<NoteSnapshot, Error>?
    model.readSnapshot(id: id) { result = $0 }
    try pump("snapshot") { result != nil }
    return try result!.get()
}
@MainActor private func save(_ session: TextSession, _ text: String) throws {
    session.replace(with: text)
    var ok: Bool?
    session.flush { ok = $0 }
    try pump("save") { ok != nil }
    try check(ok == true, "save failed: \(session.status)")
}

@MainActor func notebookCoreN1Checks() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-notebook-n1-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: dir) }
    var id = "", exact = ""
    do {
        let model = LibraryModel(folder: dir), workspace = Workspace(model: model)
        let notebook = NotebookController(model: model, workspace: workspace)
        id = try workspace.newNote(); try model.open(id: id)
        let doc = notebook.document(note: id)
        try check(try snapshot(model, id).textBlocks.isEmpty, "opening empty note does not migrate/create text")
        try save(doc, "# Café 🦉\nhello")
        var s = try snapshot(model, id)
        try check(s.textBlocks.count == 1 && s.textBlocks[0].role == .document && s.textBlocks[0].text == doc.text, "real core document persisted")
        let seq = s.localSequence
        try save(doc, "# Café 🦉\nhello world")
        s = try snapshot(model, id)
        try check(s.localSequence == seq + 1, "one pause is one splice operation")
        var undone = false
        notebook.onHistory = { undone = true }
        doc.history(redo: false); try pump("text undo") { undone }
        try check(try snapshot(model, id).textBlocks[0].text == "# Café 🦉\nhello", "core text undo")
        undone = false; doc.history(redo: true); try pump("text redo") { undone }
        try check(try snapshot(model, id).textBlocks[0].text == "# Café 🦉\nhello world", "core text redo")
        // The same undo stack includes an ink mark between two text edits.
        let sheet = s.sheets[0]
        var inkDone = false
        let channels = SampleChannels(x: [200, 250], y: [200, 260], pressure: [0.5, 0.5], elapsedMs: [0, 8], tilt: nil, orientation: nil)
        let commit = StrokeCommit(noteId: id, sheetId: sheet.id, strokeId: newId(), brush: Brush(family: .pen, size: .medium, colour: .blue), space: .canvas, samples: try encodeSamples(channels: channels))
        model.appendLiveStroke(commit, view: Viewport()) { result in if case .success = result { inkDone = true } }
        try pump("ink commit") { inkDone }
        try save(doc, doc.text + "!")
        undone = false; doc.history(redo: false); try pump("undo after ink") { undone }
        try check(try snapshot(model, id).strokes.filter { !$0.tombstone }.count == 1, "first undo changes text, not ink")
        undone = false; doc.history(redo: false); try pump("undo ink") { undone }
        try check(try snapshot(model, id).strokes.filter { !$0.tombstone }.isEmpty, "second undo crosses to ink")
        undone = false; doc.history(redo: true); try pump("redo ink") { undone }
        undone = false; doc.history(redo: true); try pump("redo text") { undone }
        s = try snapshot(model, id)
        try check(s.strokes.filter { !$0.tombstone }.count == 1 && s.textBlocks[0].text.hasSuffix("!"), "redo crosses ink and text")
        exact = s.textBlocks[0].text
        notebook.setWidth(note: id, width: 1250)
        var drained = false; model.drainNotebookWork { drained = true }; try pump("width") { drained }
        try check(try snapshot(model, id).textBlocks[0].width == 1250, "document width persisted")
        notebook.addFree(note: id, x: 1800, y: 300, text: "free")
        drained = false; model.drainNotebookWork { drained = true }; try pump("free add") { drained }
        s = try snapshot(model, id)
        let free = s.textBlocks.first { $0.role == .free }!
        notebook.moveFree(note: id, block: free, x: 1900, y: 450)
        drained = false; model.drainNotebookWork { drained = true }; try pump("free move") { drained }
        s = try snapshot(model, id)
        try check(s.textBlocks[1].transform.tx == 100 && s.textBlocks[1].transform.ty == 150, "free handle move uses core transform")
        try save(notebook.document(note: id, block: free), "")
        try check(try snapshot(model, id).textBlocks[1].tombstone, "empty free text deleted")
        notebook.addFree(note: id, x: 3400, y: 100)
        drained = false; model.drainNotebookWork { drained = true }; try pump("empty free add") { drained }
        let emptyFree = try snapshot(model, id).textBlocks.last!
        notebook.deleteEmptyFree(note: id, block: emptyFree)
        drained = false; model.drainNotebookWork { drained = true }; try pump("empty free left") { drained }
        try check(try snapshot(model, id).textBlocks.last!.tombstone, "leaving a never-typed free block deletes it")
        let editor = DocumentEditor()
        editor.show(document: doc, title: "Pause", width: 1250)
        let beforePause = try snapshot(model, id).localSequence, started = Date()
        editor.debugSetText(doc.text + "\ndebounced")
        try pump("half-second pause save") { !doc.dirty && !doc.saving }
        let milliseconds = Date().timeIntervalSince(started) * 1000
        try check(milliseconds < 500, "pause saved within half a second (\(milliseconds) ms)")
        try check(try snapshot(model, id).localSequence == beforePause + 1, "editor pause stores exactly one splice")
        exact = doc.text
        print(String(format: "Notebook debounce: %.1f ms from input to core acknowledgement", milliseconds))
        workspace.saveSession()
        try check(model.readState("slash.json") != nil, "slash defaults persisted")
    }
    do {
        let model = LibraryModel(folder: dir), workspace = Workspace(model: model)
        workspace.restoreSession(); try model.open(id: id)
        try check(workspace.active == id && model.drawing?.textBlocks.first?.text == exact, "restart restores session and exact saved text")
    }
    // Exported copy in another Mac's ownership domain is read-only, including the text API seam.
    let copyFolder = dir.appendingPathComponent("read-only")
    let lib = try openLibrary(rootPath: dir.path)
    let note = try lib.openNote(noteId: id), export = dir.appendingPathComponent("export.syncr")
    _ = try note.exportCopy(destinationPath: export.path); try note.closeNote()
    let copyModel = LibraryModel(folder: copyFolder), w = Workspace(model: copyModel)
    let imported = try copyModel.importCopy(from: export)
    try copyModel.open(id: imported.id)
    let adapter = NotebookController(model: copyModel, workspace: w)
    let session = adapter.document(note: imported.id, block: copyModel.drawing?.textBlocks.first)
    session.replace(with: "refused"); session.flush()
    try check(!session.writable && session.text == exact, "phone copy refuses text edits")
    var refused = false
    copyModel.editNote(id: imported.id, work: { _ in }) { if case .failure = $0 { refused = true } }
    try check(refused, "read-only core adapter refuses mutation")
    print("Notebook N1 core checks: OK (pause splice, restart, mixed ink/text undo/redo, width, free move/delete, read-only)")
}

@MainActor func notebookCoreN2N3Checks() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-notebook-n23-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: dir) }
    let vault = dir.appendingPathComponent("Vault"), nested = vault.appendingPathComponent("Projects/Work")
    try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)
    let text = "# Outline\n\n[[Target|shown]] #project/work\nbytes é e\u{301} 🦉\n"
    try Data(text.utf8).write(to: vault.appendingPathComponent("Source.md"))
    try Data("# Target\nbody\n".utf8).write(to: nested.appendingPathComponent("Target.md"))
    try Data().write(to: vault.appendingPathComponent("Empty.md"))
    try Data([1, 2, 3]).write(to: vault.appendingPathComponent("picture.jpg"))
    let model = LibraryModel(folder: dir.appendingPathComponent("lib")), workspace = Workspace(model: model)
    let notebook = NotebookController(model: model, workspace: workspace)
    var imported: Result<MarkdownImport, Error>?
    notebook.importMarkdown(vault) { imported = $0 }
    try pump("vault import") { imported != nil }
    let mapped = try imported!.get()
    try check(mapped.entries.count == 3 && mapped.skipped == ["picture.jpg"], "vault import counts/skips")
    let source = model.notes.first { $0.title == "Source" }!, target = model.notes.first { $0.title == "Target" }!
    try check(target.notebook == "Vault › Projects › Work" && source.notebook == "Vault", "core notebooks match source paths")
    try check(try snapshot(model, source.id).textBlocks[0].text.utf8.elementsEqual(text.utf8), "vault core exact bytes")
    try check(try snapshot(model, model.notes.first { $0.title == "Empty" }!.id).textBlocks.count == 1, "empty file has document")
    notebook.refreshIndex(force: true)
    try pump("index rebuilt") { model.notebookIndex.notes.count == 3 }
    try check(model.notebookIndex.backlinks(to: "target").first?.id == source.id && model.notebookIndex.tags.first?.0 == "project/work", "derived backlinks and tags")
    var renamed: Bool?
    notebook.rename(target.id, to: "Renamed") { renamed = $0 }
    try pump("rename links") { renamed != nil }
    try check(renamed == true && snapshot(model, source.id).textBlocks[0].text.contains("[[Renamed|shown]]"), "rename rewrites links via undoable splice")
    var undone = false; notebook.onHistory = { undone = true }; notebook.history(note: source.id, redo: false)
    try pump("undo rename rewrite") { undone }
    try check(try snapshot(model, source.id).textBlocks[0].text.utf8.elementsEqual(text.utf8), "rewrite has ordinary core undo")
    try notebook.openLink("Missing", newTab: true)
    try check(workspace.title(of: workspace.active!) == "Missing", "missing wikilink creates and opens")
    try notebook.daily(); let daily = workspace.active
    try notebook.daily()
    try check(workspace.active == daily && workspace.note(daily!)?.notebook == "Daily", "daily creates once in Daily notebook")
    model.notebookIndex = NotebookIndex(); notebook.refreshIndex(force: true)
    try pump("index rebuild from nothing") { model.notebookIndex.notes.count == model.notes.count }
    try check(model.notebookIndex.search("bytes").first?.id == source.id, "full text search rebuilt")
    let out = dir.appendingPathComponent("out.md")
    var exported: Result<Void, Error>?
    notebook.exportMarkdown(note: source.id, to: out) { exported = $0 }
    try pump("export") { exported != nil }; try exported!.get()
    try check(try Data(contentsOf: out) == Data(text.utf8), "core export byte for byte")
    imported = nil; notebook.importMarkdown(out) { imported = $0 }; try pump("reimport") { imported != nil }; _ = try imported!.get()
    let again = model.notes.first { $0.title == "out" }!
    try check(try snapshot(model, again.id).textBlocks[0].text.utf8.elementsEqual(text.utf8), "export reimport core byte roundtrip")
    try check(try Data(contentsOf: vault.appendingPathComponent("Source.md")) == Data(text.utf8), "vault source unchanged")
    print("Notebook N2/N3 core checks: OK (wikilinks, rename/undo, tags/backlinks/search rebuild, daily, vault/core/export roundtrip)")
}
