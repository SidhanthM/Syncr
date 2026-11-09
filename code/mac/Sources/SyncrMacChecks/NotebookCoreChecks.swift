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

/// The same reader seam used by NotebookController; completions are held to exercise interleavings
/// without opening a real note or depending on worker timing.
@MainActor private func incrementalIndexChecks() throws {
    typealias Revision = NotebookIndexUpdater.Revision
    let a = Revision(id: "a", sequence: 1, title: "Alpha", writable: true)
    let b = Revision(id: "b", sequence: 1, title: "Beta", writable: true)
    let c = Revision(id: "c", sequence: 1, title: "Locked", writable: false)
    var source = NotebookIndexUpdater.Source(folder: URL(fileURLWithPath: "/disposable-A"), notes: [a, b, c])
    var reads: [String] = [], waiting: [(String, @MainActor (Result<IndexedNote, Error>) -> Void)] = []
    var publications: [[IndexedNote]] = []
    let updater = NotebookIndexUpdater(source: { source }, read: { id, done in
        reads.append(id); waiting.append((id, done))
    }, publish: { publications.append($0) })
    func finish(_ title: String, _ text: String = "") {
        let (id, done) = waiting.removeFirst()
        done(.success(IndexedNote(id: id, title: title, text: text, writable: true)))
    }
    updater.refresh(); finish("Alpha", "[[Beta]] #tag\nneedle"); finish("Beta")
    try check(reads == ["a", "b"] && publications.last?.count == 3, "initial index reads only writable notes")
    let initial = reads.count
    updater.refresh(); updater.refresh()
    try check(reads.count == initial, "unchanged index performs zero snapshot reads")
    source.notes[0].sequence += 1
    updater.refresh(); finish("Alpha", "[[Beta]] #updated\nnew needle")
    let index = NotebookIndex(notes: publications.last!)
    try check(reads.count == initial + 1 && reads.last == "a", "one-note update reads exactly one snapshot")
    try check(index.tags.first?.0 == "updated" && index.backlinks(to: "Beta").first?.id == "a" && index.search("new needle").first?.id == "a", "incremental tags, backlinks and search")
    source.notes[1].title = "Renamed" // Include title even if a metadata producer did not bump sequence.
    updater.refresh(); finish("Renamed")
    try check(reads.last == "b" && NotebookIndex(notes: publications.last!).resolve("Renamed")?.id == "b", "rename invalidates just its note")
    let beforeDelete = reads.count
    source.notes.remove(at: 1); updater.refresh()
    try check(reads.count == beforeDelete && publications.last?.contains { $0.id == "b" } == false, "delete removes entry without rereads")
    source.notes[0].writable = false; updater.refresh()
    try check(reads.count == beforeDelete && publications.last?.first?.text == "" && publications.last?.first?.writable == false, "writability loss removes indexed text without reading")
    source.notes[0].writable = true; updater.refresh(); finish("Alpha", "restored")
    try check(reads.count == beforeDelete + 1, "writability gain reads the document")

    // Two changes to the same note while reading: old data never publishes, next pass reads it once.
    source.notes[0].sequence += 1; updater.refresh()
    let beforeStale = publications.count
    source.notes[0].sequence += 1; updater.refresh(); updater.refresh()
    finish("Alpha", "stale")
    try check(publications.count == beforeStale && waiting.count == 1, "stale pass is not published; in-flight refreshes coalesce")
    finish("Alpha", "latest")
    try check(publications.last?.first?.text == "latest", "edit during pass is reindexed")
    // Also reconcile live metadata with no refresh notification.
    source.notes[0].sequence += 1; updater.refresh(); source.notes.removeFirst()
    finish("Alpha", "deleted during read")
    try check(publications.last?.map(\.id) == ["c"], "delete during pass cannot resurrect a note")

    source.notes = [a, b]; updater.refresh()
    let beforeSwitch = publications.count
    source.folder = URL(fileURLWithPath: "/disposable-B"); source.notes = [a]
    updater.reset(); updater.refresh()
    finish("Old library", "stale")
    try check(publications.count == beforeSwitch, "old library result ignored after reset")
    finish("New library", "new body")
    try check(publications.last?.first?.title == "New library", "same ID/sequence in a new library rereads")
    source.notes[0].sequence += 1; updater.refresh()
    // Return to A while a B read is in flight, with the same metadata as the first A load.
    source.folder = URL(fileURLWithPath: "/disposable-A"); source.notes = [a]
    updater.reset(); updater.refresh(); finish("Late B"); finish("Back to A")
    try check(publications.last?.first?.title == "Back to A", "A-B-A switch rejects stale result by generation")

    // Failed changed-note reads must not keep old searchable text; explicit recovery retries.
    source.notes[0].sequence += 1; updater.refresh()
    let (_, failed) = waiting.removeFirst(); failed(.failure(ViewerError.unavailable))
    try check(publications.last?.isEmpty == true, "read failure does not retain stale entry")
    updater.refresh(force: true); finish("Recovered", "recovered")
    try check(publications.last?.first?.text == "recovered", "explicit recovery rebuilds")
    source.notes = [a, b]; updater.refresh()
    let beforeRecovery = reads.count
    updater.refresh(force: true); updater.refresh(force: true)
    finish("Alpha"); finish("Beta")
    // Exactly one pending recovery, not one full pass per refresh.
    finish("Alpha"); finish("Beta")
    try check(reads.count == beforeRecovery + 3 && waiting.isEmpty, "forced recovery during a pass is coalesced")
    source.notes = (0..<64).map { Revision(id: "bulk-\($0)", sequence: 1, title: "Note \($0)", writable: true) }
    let beforeBulk = reads.count
    updater.refresh()
    for i in 0..<64 { finish("Note \(i)", "body \(i)") }
    try check(reads.count == beforeBulk + 64 && waiting.isEmpty, "64-note initial load reads 64 snapshots")
    source.notes[17].sequence += 1
    updater.refresh(); finish("Note 17", "changed")
    try check(reads.count == beforeBulk + 65 && publications.last?[17].text == "changed", "64-note library: one-note change reads 1, not 64 snapshots")
    source.notes.reverse(); updater.refresh()
    try check(reads.count == beforeBulk + 65 && publications.last?.first?.id == "bulk-63", "enumeration reorder reuses all snapshots")
    print("Incremental index: OK (64-note load 64 reads; unchanged 0; one-note change 1; rename/delete/writability, stale/in-flight, library generations, failure/recovery)")
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
        try check(model.readState("slash.json") == nil, "the built-in slash commands are no longer written to slash.json")
    }
    do {
        let model = LibraryModel(folder: dir), workspace = Workspace(model: model)
        workspace.restoreSession(); try model.open(id: id)
        try check(workspace.active == id && model.drawing?.textBlocks.first?.text == exact, "restart restores session and exact saved text")
    }
    // An exported copy imported on another Mac is writable there (link v1), including the text API seam.
    let copyFolder = dir.appendingPathComponent("read-only")
    let lib = try openLibrary(rootPath: dir.path)
    let note = try lib.openNote(noteId: id), export = dir.appendingPathComponent("export.syncr")
    _ = try note.exportCopy(destinationPath: export.path); try note.closeNote()
    let copyModel = LibraryModel(folder: copyFolder), w = Workspace(model: copyModel)
    let imported = try copyModel.importCopy(from: export)
    try copyModel.open(id: imported.id)
    let adapter = NotebookController(model: copyModel, workspace: w)
    let session = adapter.document(note: imported.id, block: copyModel.drawing?.textBlocks.first)
    try check(session.writable && session.text == exact, "a phone copy is writable on the Mac (link v1: the Mac is primary)")
    var refused = false
    copyModel.editNote(id: newId(), work: { _ in }) { if case .failure = $0 { refused = true } }
    try check(refused, "the core adapter refuses a note that is not in the library")
    print("Notebook N1 core checks: OK (pause splice, restart, mixed ink/text undo/redo, width, free move/delete, imported copy writable)")
}

@MainActor func notebookCoreN2N3Checks() throws {
    try incrementalIndexChecks()
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
