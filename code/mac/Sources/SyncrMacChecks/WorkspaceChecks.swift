import Foundation
import SyncrCore

private func temp() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }

/// A standalone export of a one-note library, as the phone would hand over.
private func phoneExport(into dir: URL, title: String) throws -> (id: String, file: URL) {
    let source = try openLibrary(rootPath: dir.appendingPathComponent("phone-\(UUID().uuidString)").path)
    let id = newId()
    _ = try source.createNote(request: CreateNoteRequest(operationId: newId(), noteId: id, firstSheetId: newId(), title: title, notebook: "Phone"))
    let note = try source.openNote(noteId: id)
    let file = dir.appendingPathComponent("\(id)-export.syncr")
    _ = try note.exportCopy(destinationPath: file.path)
    try note.closeNote()
    return (id, file)
}

@MainActor private func names(_ w: Workspace) -> [String: [String]] {
    Dictionary(uniqueKeysWithValues: w.sidebar.map { ($0.title, $0.notes.map(\.title)) })
}

@MainActor func notebookWorkspaceChecks() throws {
    let dir = temp()
    defer { try? FileManager.default.removeItem(at: dir) }
    let folder = dir.appendingPathComponent("lib")
    let model = LibraryModel(folder: folder)
    let w = Workspace(model: model)
    var activations: [String?] = []
    w.onActivate = { activations.append($0) }
    var changes = 0
    w.onChange = { changes += 1 }
    w.restoreSession()

    // Empty library: Inbox only, no trash section.
    try check(w.sidebar.map(\.title) == ["Inbox"], "fresh sidebar is just the Inbox")

    // Notebooks: create / rename / delete, with the sidebar following each step.
    try w.createNotebook("Work")
    try check(names(w)["Work"] == [] && w.selectedNotebook == "Work", "created notebook appears empty and is selected")
    try expectError({ try w.createNotebook("   ") }, "blank notebook name refused")

    // Notes: create goes to the selected notebook, opens in a tab.
    let a = try w.newNote()
    try check(names(w)["Work"] == ["Untitled"] && w.tabs == [a] && w.active == a && activations.last == a, "new note lands in selected notebook and opens")
    try check(w.activeIsWritable && model.isWritable(a), "Mac-created note is writable")
    try w.rename(a, to: "Plan")
    try check(names(w)["Work"] == ["Plan"] && w.title(of: a) == "Plan", "rename updates sidebar and tab title")
    try w.rename(a, to: "  ")
    try check(w.title(of: a) == "Untitled", "blank rename falls back to Untitled")
    try w.rename(a, to: "Plan")
    w.selectNotebook(nil)
    let b = try w.newNote()
    try check(names(w)["Inbox"] == ["Untitled"] && w.tabs == [a, b], "note created with Inbox selected goes to the Inbox, new tab")
    try w.rename(b, to: "Alpha")
    try w.move(b, to: "Work")
    try check(names(w)["Work"] == ["Alpha", "Plan"] && names(w)["Inbox"] == [], "move updates both sections, alphabetical")
    try check(model.notes.first { $0.id == b }?.notebook == "Work", "move persisted in core")
    try w.move(b, to: nil)
    try check(names(w)["Inbox"] == ["Alpha"], "move back to Inbox")

    // Notebook rename keeps notes, selection and persists.
    w.selectNotebook("Work")
    try w.renameNotebook("Work", to: "Projects")
    try check(names(w)["Projects"] == ["Plan"] && names(w)["Work"] == nil && w.selectedNotebook == "Projects", "notebook rename moves notes and selection")

    // Delete to trash closes the tab; restore brings it back.
    try w.delete(a)
    try check(!w.tabs.contains(a) && names(w)["Projects"] == [] && names(w)["Recently deleted"] == ["Plan"], "delete closes tab and fills trash")
    try check(w.active == b, "active moves to the surviving tab")
    try w.restore(a, open: false)
    try check(names(w)["Projects"] == ["Plan"] && w.sidebar.last?.kind != .trash, "restore returns note to its notebook; trash hides when empty")
    try w.delete(a); try w.delete(b)
    try check(w.tabs.isEmpty && w.active == nil && activations.last == .some(nil), "closing last tab clears the open note")
    try w.purge(a)
    try check(!model.isWritable(a) && model.deleted.map(\.id) == [b], "purge removes note and its ownership record")
    try w.restore(b)
    try check(w.tabs == [b], "restore with open reopens in a tab")

    // Deleting a notebook moves its notes to the Inbox first.
    try w.move(b, to: "Projects")
    try w.deleteNotebook("Projects")
    try check(names(w)["Projects"] == nil && names(w)["Inbox"] == ["Alpha"] && w.selectedNotebook == nil, "notebook delete moves notes to Inbox")
    try check(changes > 10, "sidebar change notifications fire")

    // Sidebar filter.
    _ = try w.newNote(); try w.rename(w.active!, to: "Budget")
    w.filter = "bud"
    try check(w.sidebar.map(\.title) == ["Inbox"] && w.sidebar[0].notes.map(\.title) == ["Budget"], "filter keeps only matching notes and hides empty folders")
    w.filter = ""
}

@MainActor func switcherAndCommandChecks() throws {
    let dir = temp()
    defer { try? FileManager.default.removeItem(at: dir) }
    let model = LibraryModel(folder: dir.appendingPathComponent("lib"))
    let w = Workspace(model: model)
    w.restoreSession()
    var ids: [String: String] = [:]
    for title in ["Reading list", "Meeting notes", "Notes on reading", "Budget", "meet"] {
        let id = try model.createNote(title: title, notebook: nil)
        ids[title] = id
        usleep(2_000) // distinct modified times
    }
    func titles(_ q: String) -> [String] { w.switcherResults(q).map(\.title) }
    try check(titles("")[0] == "meet" && titles("").count == 5, "empty query lists most recently modified first")
    try check(titles("meet") == ["meet", "Meeting notes"], "exact then prefix match")
    try check(titles("read") == ["Reading list", "Notes on reading"], "prefix before substring")
    try check(titles("NOTES") == ["Notes on reading", "Meeting notes"], "case-insensitive; prefix before word-start match")
    try check(titles("bgt") == ["Budget"], "in-order letters still match")
    try check(titles("zzz").isEmpty, "no match is empty")

    // Open in current tab versus new tab.
    w.open(ids["Budget"]!)
    w.open(ids["meet"]!)
    try check(w.tabs == [ids["meet"]!], "Enter replaces the current tab")
    w.open(ids["Budget"]!, newTab: true)
    try check(w.tabs == [ids["meet"]!, ids["Budget"]!] && w.active == ids["Budget"]!, "Cmd-Enter opens a new tab")
    w.open(ids["meet"]!, newTab: true)
    try check(w.tabs.count == 2 && w.active == ids["meet"]!, "already open note is only activated")

    // Command palette: filtering honours availability.
    let none = AppCommand.matching("", available: Workspace(model: LibraryModel(folder: dir.appendingPathComponent("empty"))).availableCommands())
    try check(!none.contains(.renameNote) && !none.contains(.closeTab) && none.contains(.newNote), "no note open: only global commands")
    let open = AppCommand.matching("", available: w.availableCommands())
    try check(open.contains(.renameNote) && open.contains(.nextTab) && !open.contains(.restoreNote), "note open: note commands; no restore without trash")
    try check(AppCommand.matching("new", available: w.availableCommands()).prefix(2) == [.newNote, .newNotebook], "palette orders prefix matches by declaration")
    try check(Set(AppCommand.matching("import", available: w.availableCommands())) == [.importExport, .importMarkdown], "import lives in the palette")
    try check(AppCommand.matching("sidebar", available: w.availableCommands()) == [.toggleSidebar], "substring command match")
}

@MainActor func readOnlyAndSessionChecks() throws {
    let dir = temp()
    defer { try? FileManager.default.removeItem(at: dir) }
    let folder = dir.appendingPathComponent("lib")
    // The first model is scoped so its library handle (one writer per folder) closes before reopening.
    var mac = "", beforeTabs: [String] = []
    var phoneID = ""
    do {
    let model = LibraryModel(folder: folder)
    let w = Workspace(model: model)
    w.restoreSession()
    mac = try w.newNote()
    try w.rename(mac, to: "Mine")

    let phone = try phoneExport(into: dir, title: "From phone")
    let added = try model.importCopy(from: phone.file)
    phoneID = phone.id
    w.libraryChanged()
    try check(added.id == phone.id && !model.isWritable(phone.id) && model.isWritable(mac), "imported copy is read-only, Mac note writable")
    try check(w.sidebar.first { $0.title == "Phone" }?.notes.first?.readOnly == true, "sidebar marks imported copy read-only")
    try expectError({ try w.rename(phone.id, to: "x") }, "rename of imported copy refused")
    try expectError({ try w.move(phone.id, to: nil) }, "move of imported copy refused")
    try expectError({ try w.renameNotebook("Phone", to: "Elsewhere") }, "notebook of read-only copies cannot be renamed")
    try expectError({ try w.deleteNotebook("Phone") }, "notebook of read-only copies cannot be deleted")
    try check(model.notes.first { $0.id == phone.id }?.title == "From phone", "refused operations leave the copy untouched")
    w.open(phone.id, newTab: true)
    try check(!w.availableCommands().contains(.renameNote) && w.availableCommands().contains(.deleteNote), "read-only tab: no rename/move, delete allowed")

    // A copy of a Mac-created note's ID must not replace it.
    let source = try openLibrary(rootPath: dir.appendingPathComponent("clash").path)
    _ = try source.createNote(request: CreateNoteRequest(operationId: newId(), noteId: mac, firstSheetId: newId(), title: "Impostor", notebook: nil))
    let clashNote = try source.openNote(noteId: mac)
    let clash = dir.appendingPathComponent("clash.syncr")
    _ = try clashNote.exportCopy(destinationPath: clash.path); try clashNote.closeNote()
    try expectError({ _ = try model.importCopy(from: clash) }, "import never replaces a Mac-created note")

    // Ownership and session survive a restart; stale IDs drop out.
    w.open(mac, newTab: true)
    try w.createNotebook("Ideas")
    w.setExpanded("nb:Ideas", false)
    beforeTabs = w.tabs
    }
    // A library has one writer, so each "restart" is a scope that releases its handle on return.
    @MainActor func restarted(_ body: (Workspace, LibraryModel) throws -> Void) throws {
        let model = LibraryModel(folder: folder)
        try check(model.error == nil, "reopen: \(model.error ?? "")")
        let workspace = Workspace(model: model)
        workspace.restoreSession()
        try body(workspace, model)
    }
    try restarted { again, reopened in
        try check(reopened.isWritable(mac) && !reopened.isWritable(phoneID), "ownership record survives restart")
        try check(again.tabs == beforeTabs && again.active == mac && again.selectedNotebook == "Ideas", "tabs, active tab and selected notebook restored")
        try check(again.sidebar.first { $0.title == "Ideas" }?.expanded == false && again.sidebar.first { $0.title == "Inbox" }?.expanded == true, "collapsed folders restored")
        try again.delete(phoneID)
    }
    try restarted { third, _ in
        try check(!third.tabs.contains(phoneID) && third.tabs.contains(mac), "tab for a deleted note is dropped on restore")
    }
    try Data("not json".utf8).write(to: folder.appendingPathComponent(".mac/session.json"))
    try restarted { fourth, _ in
        try check(fourth.tabs.isEmpty && fourth.active == nil, "corrupt session file starts empty")
    }
    try check(FileManager.default.fileExists(atPath: folder.appendingPathComponent(".mac/owned.json").path), "owned record is a file in the library folder")
}
