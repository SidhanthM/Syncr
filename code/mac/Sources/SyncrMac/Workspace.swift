import Foundation
import SyncrCore

struct SidebarNote: Equatable {
    let id: String
    let title: String
    let readOnly: Bool
    /// Shown small at the row's right, such as a tag's note count.
    var detail: String? = nil
}

struct SidebarSection: Equatable {
    enum Kind: Hashable { case inbox, notebook(String), tags, trash }
    let kind: Kind
    let title: String
    let notes: [SidebarNote]
    let expanded: Bool
    var key: String {
        switch kind {
        case .inbox: "inbox"
        case .notebook(let name): "nb:\(name)"
        case .tags: "tags"
        case .trash: "trash"
        }
    }
}

/// What is restored at next launch. Only IDs and names: titles and permissions are re-read.
struct SessionState: Codable, Equatable {
    var tabs: [String] = []
    var active: String?
    var notebook: String?
    var collapsed: [String] = ["trash"]
}

/// Notebook, tab and filter state over a `LibraryModel`; no window involved. The UI mirrors it
/// through `onChange` and reacts to the open note changing through `onActivate`.
@MainActor final class Workspace {
    let model: LibraryModel
    private(set) var tabs: [String] = []
    private(set) var active: String?
    /// Where Cmd-N puts a note; nil is the Inbox.
    private(set) var selectedNotebook: String?
    private(set) var collapsed: Set<String> = ["trash"]
    var filter = "" { didSet { if filter != oldValue { changed(save: false) } } }
    var onChange: (() -> Void)?
    /// The note to show changed (nil = nothing open).
    var onActivate: ((String?) -> Void)?
    /// Lists the debug live-ink replay commands. Set by the app only when launched with the debug flag.
    var debugCommands = false
    /// At least one phone is paired (the link controller sets it): Forget Phone is listed.
    var hasPairedPhones = false
    static let sessionFile = "session.json"

    init(model: LibraryModel) { self.model = model }

    // MARK: Lookup

    func note(_ id: String) -> NoteSummary? { model.notes.first { $0.id == id } }
    func title(of id: String) -> String { displayTitle(note(id)?.title ?? "") }
    func displayTitle(_ title: String) -> String { title.isEmpty ? "Untitled" : title }
    func isReadOnly(_ id: String) -> Bool { !model.isWritable(id) }
    var activeIndex: Int? { active.flatMap { tabs.firstIndex(of: $0) } }
    var activeIsWritable: Bool { active.map(model.isWritable) ?? false }

    // MARK: Sidebar

    var sidebar: [SidebarSection] {
        let filtering = !filter.trimmingCharacters(in: .whitespaces).isEmpty
        func rows(_ items: [(id: String, title: String, readOnly: Bool)]) -> [SidebarNote] {
            let scored = items.compactMap { item -> (Int, SidebarNote)? in
                let shown = displayTitle(item.title)
                guard let score = Matching.score(filter, in: shown) else { return nil }
                return (filtering ? score : 0, SidebarNote(id: item.id, title: shown, readOnly: item.readOnly))
            }
            return scored.sorted {
                $0.0 != $1.0 ? $0.0 < $1.0 : $0.1.title.localizedCaseInsensitiveCompare($1.1.title) == .orderedAscending
            }.map { $0.1 }
        }
        func section(_ kind: SidebarSection.Kind, _ title: String, _ items: [(id: String, title: String, readOnly: Bool)]) -> SidebarSection? {
            let notes = rows(items)
            if filtering && notes.isEmpty { return nil }
            let key = SidebarSection(kind: kind, title: title, notes: [], expanded: true).key
            return SidebarSection(kind: kind, title: title, notes: notes, expanded: filtering || !collapsed.contains(key))
        }
        func live(_ notebook: String?) -> [(id: String, title: String, readOnly: Bool)] {
            model.notes.filter { $0.notebook == notebook }.map { ($0.id, $0.title, !model.isWritable($0.id)) }
        }
        var result = [section(.inbox, "Inbox", live(nil))].compactMap { $0 }
        for notebook in model.notebooks {
            if let s = section(.notebook(notebook.name), notebook.name, live(notebook.name)) { result.append(s) }
        }
        if !model.notebookIndex.tags.isEmpty {
            result.append(SidebarSection(kind: .tags, title: "Tags", notes: model.notebookIndex.tags.map {
                SidebarNote(id: "tag:" + $0.0, title: "#" + $0.0, readOnly: false, detail: String($0.1))
            }, expanded: !collapsed.contains("tags")))
        }
        let trash = model.deleted.map { (id: $0.id, title: $0.title, readOnly: false) }
        if let s = section(.trash, "Recently deleted", trash), !(trash.isEmpty && !filtering) { result.append(s) }
        return result
    }

    func setExpanded(_ key: String, _ expanded: Bool) {
        if expanded { collapsed.remove(key) } else { collapsed.insert(key) }
        saveSession()
    }

    func selectNotebook(_ name: String?) { selectedNotebook = name; changed() }

    // MARK: Quick switcher

    /// Live notes matching the query. Empty query lists recently modified first.
    func switcherResults(_ query: String) -> [NoteSummary] {
        model.notes.compactMap { note -> (Int, NoteSummary)? in
            Matching.score(query, in: displayTitle(note.title)).map { ($0, note) }
        }.sorted {
            if $0.0 != $1.0 { return $0.0 < $1.0 }
            if $0.1.modifiedAtMs != $1.1.modifiedAtMs { return $0.1.modifiedAtMs > $1.1.modifiedAtMs }
            return displayTitle($0.1.title).localizedCaseInsensitiveCompare(displayTitle($1.1.title)) == .orderedAscending
        }.map { $0.1 }
    }

    func availableCommands() -> Set<AppCommand> {
        var set: Set<AppCommand> = [.companionSettings, .lastCompanionRequest, .newNote, .newNotebook, .quickSwitcher, .toggleSidebar, .importExport, .chooseFolder, .phoneLink, .followPhone]
        set.formUnion([.settings, .hotkeys, .commandPalette, .showLeftSidebar, .hideLeftSidebar,
                       .searchNotes, .dailyNote, .importMarkdown, .browseTags])
        if active != nil { set.formUnion([.findInNote, .exportMarkdown, .documentView, .outline]) }
        if activeIsWritable { set.formUnion([.askSyncr, .drawSelection, .askAllReaders, .undoCompanion, .redoCompanion, .undo, .redo, .bold, .italic, .insertLink, .toggleTask, .slashMenu, .freeText]) }
        if hasPairedPhones { set.insert(.forgetPhone) }
        if active != nil { set.formUnion([.deleteNote, .closeTab, .zoomIn, .zoomOut, .fit, .toggleThumbnails, .fitPage, .fitPageWidth, .fitPageHeight,
                                           .layoutSingle, .layoutDouble, .layoutCover, .toggleFlip, .nextPage, .previousPage]) }
        if activeIsWritable { set.insert(.addPage) }
        if activeIsWritable { set.formUnion([.renameNote, .moveNote]) }
        if debugCommands && active != nil { set.formUnion([.replayTestStroke, .replayHighlighter, .replayCancelled, .replayViewRelative, .simulateDisconnect]) }
        if tabs.count > 1 { set.formUnion([.nextTab, .previousTab]) }
        if !model.deleted.isEmpty { set.insert(.restoreNote) }
        return set
    }

    // MARK: Tabs

    /// Opens in the active tab, or in a new tab. An already open note is just activated.
    func open(_ id: String, newTab: Bool = false) {
        guard note(id) != nil else { return }
        if tabs.contains(id) { activate(id); return }
        if newTab || active == nil { tabs.append(id) }
        else if let i = activeIndex { tabs[i] = id }
        activate(id)
    }

    func activate(_ id: String) {
        guard tabs.contains(id) else { return }
        let changedNote = active != id
        active = id
        if let nb = note(id) { selectedNotebook = nb.notebook }
        changed()
        if changedNote { onActivate?(id) }
    }

    func closeTab(_ id: String) {
        guard let index = tabs.firstIndex(of: id) else { return }
        tabs.remove(at: index)
        guard active == id else { changed(); return }
        if tabs.isEmpty { active = nil; changed(); onActivate?(nil) }
        else { active = nil; activate(tabs[min(index, tabs.count - 1)]) }
    }

    func cycleTab(_ step: Int) {
        guard tabs.count > 1, let i = activeIndex else { return }
        activate(tabs[(i + step + tabs.count) % tabs.count])
    }

    // MARK: Operations (each keeps tabs, selection and sidebar consistent)

    @discardableResult func newNote() throws -> String {
        let id = try model.createNote(notebook: selectedNotebook)
        open(id, newTab: true)
        return id
    }

    /// A paged note, as the New note sheet chose it.
    @discardableResult func newNote(_ options: NewNoteOptions) throws -> String {
        let id = try model.createPagedNote(options, notebook: selectedNotebook)
        open(id, newTab: true)
        return id
    }

    func rename(_ id: String, to title: String) throws { try model.renameNote(id: id, title: title); changed() }

    func move(_ id: String, to notebook: String?) throws {
        try model.moveNote(id: id, to: notebook)
        if active == id { selectedNotebook = notebook }
        changed()
    }

    func delete(_ id: String) throws {
        let wasOpen = tabs.contains(id)
        try model.deleteNote(id: id)
        if wasOpen { closeTab(id) } else { changed() }
    }

    func restore(_ id: String, open reopen: Bool = true) throws {
        try model.restoreNote(id: id)
        if reopen { open(id, newTab: true) } else { changed() }
    }

    func purge(_ id: String) throws { try model.purgeNote(id: id); changed() }

    func createNotebook(_ name: String) throws {
        try model.createNotebook(name: name)
        selectedNotebook = name.trimmingCharacters(in: .whitespacesAndNewlines)
        changed()
    }

    func renameNotebook(_ old: String, to new: String) throws {
        try model.renameNotebook(old, to: new)
        let name = new.trimmingCharacters(in: .whitespacesAndNewlines)
        if selectedNotebook == old { selectedNotebook = name }
        if collapsed.remove("nb:\(old)") != nil { collapsed.insert("nb:\(name)") }
        changed()
    }

    func deleteNotebook(_ name: String) throws {
        try model.deleteNotebook(name)
        if selectedNotebook == name { selectedNotebook = nil }
        changed()
    }

    /// After an import: the library changed underneath the tabs.
    func libraryChanged() { changed() }

    // MARK: Session

    private func reconcile() {
        let live = Set(model.notes.map(\.id))
        let kept = tabs.filter(live.contains)
        guard kept != tabs || (active.map { !live.contains($0) } ?? false) else { return }
        let gone = active.map { !live.contains($0) } ?? false
        tabs = kept
        if gone { active = tabs.last; onActivate?(active) }
    }

    private func changed(save: Bool = true) {
        reconcile()
        if let nb = selectedNotebook, !model.notebooks.contains(where: { $0.name == nb }) { selectedNotebook = nil }
        if save { saveSession() }
        onChange?()
    }

    var session: SessionState {
        SessionState(tabs: tabs, active: active, notebook: selectedNotebook, collapsed: collapsed.sorted())
    }

    func saveSession() {
        if let data = try? JSONEncoder().encode(session) { model.writeState(Self.sessionFile, data) }
    }

    /// Applies the saved state, dropping notes that no longer exist. Call once after init, and
    /// after the library folder changes (resets first).
    func restoreSession() {
        tabs = []; active = nil; selectedNotebook = nil; collapsed = ["trash"]; filter = ""
        guard let data = model.readState(Self.sessionFile),
              let saved = try? JSONDecoder().decode(SessionState.self, from: data) else { changed(save: false); return }
        let live = Set(model.notes.map(\.id))
        var seen = Set<String>()
        tabs = saved.tabs.filter { live.contains($0) && seen.insert($0).inserted }
        collapsed = Set(saved.collapsed)
        selectedNotebook = saved.notebook
        let target = saved.active.flatMap { tabs.contains($0) ? $0 : nil } ?? tabs.last
        changed(save: false)
        if let target { activate(target) }
        selectedNotebook = saved.notebook
        changed(save: false)
    }
}
