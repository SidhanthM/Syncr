import AppKit
import SyncrCore

/// Core adapter and derived notebook data. TextKit buffers are main-confined; only LibraryModel's
/// serial worker opens notes. Neither this adapter nor the editor runs on live ink's ingest queue.
@MainActor final class NotebookController {
    let model: LibraryModel
    let workspace: Workspace
    private var sessions: [String: TextSession] = [:]
    private var blockIDs: [String: String] = [:]
    private lazy var indexUpdater = NotebookIndexUpdater(source: { [model] in
        .init(folder: model.folder, notes: model.notes.map {
            .init(id: $0.id, sequence: $0.localSequence, title: $0.title, writable: model.isWritable($0.id))
        })
    }, read: { [weak self] id, done in
        guard let self else { done(.failure(ViewerError.unavailable)); return }
        model.readSnapshot(id: id) { result in
            done(result.map { snapshot in
                IndexedNote(id: id, title: snapshot.title,
                            text: snapshot.textBlocks.first { $0.role == .document && !$0.tombstone }?.text ?? "", writable: true)
            })
        }
    }, publish: { [weak self] entries in
        guard let self else { return }
        model.notebookIndex = NotebookIndex(notes: entries); onChange?()
    })
    var onChange: (() -> Void)?
    var onState: ((String) -> Void)?
    var onHistory: (() -> Void)?
    var onError: ((Error) -> Void)?
    /// The library's own commands from `.mac/slash.json`; the built-ins live in `SlashCatalog`.
    private(set) var slashCommands: [SlashCommand] = []

    init(model: LibraryModel, workspace: Workspace) {
        self.model = model; self.workspace = workspace
        loadSlashCommands()
    }
    func resetForFolder() {
        sessions = [:]; blockIDs = [:]; indexUpdater.reset(); model.notebookIndex = NotebookIndex()
        loadSlashCommands(); refreshIndex(force: true)
    }
    private func loadSlashCommands() {
        slashCommands = []
        // The file is no longer written with the built-ins; one that holds exactly the old defaults counts as empty.
        if let data = model.readState("slash.json"), let value = try? JSONDecoder().decode([SlashCommand].self, from: data) { slashCommands = SlashCatalog.userCommands(fromFile: value) }
    }
    private func key(_ note: String, _ block: TextBlockView?) -> String { note + ":" + (block?.role == .free ? block!.id : "document") }
    func document(note: String, block: TextBlockView? = nil) -> TextSession {
        let key = key(note, block)
        if let block { blockIDs[key] = block.id }
        if let existing = sessions[key] {
            if let block { existing.adopt(block.text) }
            return existing
        }
        let session = TextSession(text: block?.text ?? "", writable: model.isWritable(note))
        sessions[key] = session
        session.onState = { [weak self] in self?.onState?(note) }
        session.save = { [weak self] value, done in
            guard let self else { done(.failure(ViewerError.unavailable)); return }
            let wantedBlock = self.blockIDs[key], free = block?.role == .free
            self.model.editNote(id: note, work: { handle in
                var snapshot = try handle.snapshot()
                var mark: TextBlockView?
                if let wantedBlock { mark = snapshot.textBlocks.first { $0.id == wantedBlock && !$0.tombstone } }
                else { mark = snapshot.textBlocks.first { $0.role == .document && !$0.tombstone } }
                if mark == nil {
                    // Empty virtual document becomes a real block only at its first saved edit.
                    guard !free, let sheet = snapshot.sheets.first else { throw ViewerError.unknownSheet }
                    let id = newId()
                    _ = try handle.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: [
                        .addText(markId: id, sheetId: sheet.id, role: .document, originX: 0, originY: 0, width: 1600, text: "", style: TextStyle(size: .medium, colour: .ink))
                    ])))
                    snapshot = try handle.snapshot(); mark = snapshot.textBlocks.first { $0.id == id }
                }
                guard let mark else { throw ViewerError.unavailable }
                if free && value.isEmpty {
                    _ = try handle.submit(command: Self.command(mark.head, .deleteText(markId: mark.id, base: Self.base(mark.head))))
                } else if let splice = TextSplice.between(mark.text, value) {
                    _ = try handle.submit(command: Self.command(mark.head, .spliceText(markId: mark.id, base: Self.base(mark.head), offset: UInt32(splice.offset), delete: UInt32(splice.delete), insert: splice.insert)))
                }
            }, completion: { [weak self] result in
                switch result {
                case .success(let snapshot):
                    self?.model.publishText(snapshot); self?.refreshIndex()
                    done(.success(()))
                case .failure(let error): done(.failure(error))
                }
            })
        }
        session.undo = { [weak self] redo in self?.history(note: note, redo: redo) }
        return session
    }
    nonisolated static func base(_ head: EntityHead) -> Base { head.confirmedRevision.map { .confirmed(revision: $0) } ?? .operation(operationId: head.operationId) }
    nonisolated static func command(_ head: EntityHead, _ mutation: MarkMutation) -> Command {
        Command(operationId: newId(), dependencies: [head.operationId], body: .marks(mutations: [mutation]))
    }
    func status(note: String) -> String {
        let values = sessions.filter { $0.key.hasPrefix(note + ":") }.map(\.value)
        return values.first(where: { $0.failure != nil })?.status ?? (values.contains(where: \.saving) ? "Saving…" : values.contains(where: \.dirty) ? "Unsaved" : "Saved")
    }
    func flushAll(done: (@MainActor (Bool) -> Void)? = nil) {
        let values = Array(sessions.values)
        guard !values.isEmpty else { done?(true); return }
        var left = values.count, ok = true
        for session in values { session.flush { success in ok = ok && success; left -= 1; if left == 0 { done?(ok) } } }
    }
    func history(note: String, redo: Bool) {
        model.editNote(id: note, work: { handle in
            let snapshot = try handle.snapshot()
            guard let target = redo ? snapshot.redoTarget : snapshot.undoTarget else { return }
            _ = try handle.submit(command: Command(operationId: newId(), dependencies: redo ? snapshot.redoDependencies : snapshot.undoDependencies,
                                                    body: redo ? .redo(targetOperationId: target) : .undo(targetOperationId: target)))
        }, completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let snapshot):
                for block in snapshot.textBlocks where !block.tombstone { self.sessions[self.key(note, block)]?.adopt(block.text) }
                if !snapshot.textBlocks.contains(where: { $0.role == .document && !$0.tombstone }) { self.sessions[note + ":document"]?.adopt("") }
                self.model.reloadInPlace(id: note); self.refreshIndex(); self.onHistory?()
            case .failure(let error): self.onError?(error)
            }
        })
    }
    func setWidth(note: String, width: CGFloat) {
        flushAll { [weak self] ok in
            guard let self, ok else { return }
            self.model.editNote(id: note, work: { handle in
                let snapshot = try handle.snapshot()
                guard let mark = snapshot.textBlocks.first(where: { $0.role == .document && !$0.tombstone }) else {
                    guard let sheet = snapshot.sheets.first else { throw ViewerError.unknownSheet }
                    _ = try handle.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: [.addText(markId: newId(), sheetId: sheet.id, role: .document, originX: 0, originY: 0, width: Float(width), text: "", style: TextStyle(size: .medium, colour: .ink))])))
                    return
                }
                _ = try handle.submit(command: Self.command(mark.head, .setTextWidth(markId: mark.id, base: Self.base(mark.head), width: Float(width))))
            }, completion: { [weak self] result in
                switch result { case .success(let snapshot): self?.model.publishText(snapshot); case .failure(let error): self?.onError?(error) }
            })
        }
    }
    func addFree(note: String, x: CGFloat, y: CGFloat, text: String = "") {
        model.editNote(id: note, work: { handle in
            guard let sheet = try handle.snapshot().sheets.first else { throw ViewerError.unknownSheet }
            _ = try handle.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: [
                .addText(markId: newId(), sheetId: sheet.id, role: .free, originX: Float(x), originY: Float(y), width: 1000, text: text, style: TextStyle(size: .medium, colour: .ink))
            ])))
        }, completion: { [weak self] result in
            switch result { case .success(let snapshot): self?.model.publishText(snapshot); case .failure(let error): self?.onError?(error) }
        })
    }
    func deleteEmptyFree(note: String, block: TextBlockView) {
        guard (sessions[key(note, block)]?.text ?? block.text).isEmpty else { return }
        model.editNote(id: note, work: { handle in
            guard let mark = try handle.snapshot().textBlocks.first(where: { $0.id == block.id && !$0.tombstone }), mark.text.isEmpty else { return }
            _ = try handle.submit(command: Self.command(mark.head, .deleteText(markId: mark.id, base: Self.base(mark.head))))
        }, completion: { [weak self] result in if case .success(let snapshot) = result { self?.model.publishText(snapshot) } })
    }
    func moveFree(note: String, block: TextBlockView, x: CGFloat, y: CGFloat) {
        model.editNote(id: note, work: { handle in
            guard let mark = try handle.snapshot().textBlocks.first(where: { $0.id == block.id }) else { return }
            _ = try handle.submit(command: Self.command(mark.head, .transformText(markId: mark.id, base: Self.base(mark.head), transform: Affine(a: 1, b: 0, c: 0, d: 1, tx: Double(x) - Double(mark.originX), ty: Double(y) - Double(mark.originY)))))
        }, completion: { [weak self] result in
            switch result { case .success(let snapshot): self?.model.publishText(snapshot); case .failure(let error): self?.onError?(error) }
        })
    }
    func refreshIndex(force: Bool = false) {
        indexUpdater.refresh(force: force)
    }
    func openLink(_ title: String, newTab: Bool) throws {
        if let found = model.notebookIndex.resolve(title) { workspace.open(found.id, newTab: newTab) }
        else if let found = model.notes.sorted(by: { $0.id < $1.id }).first(where: { $0.title.caseInsensitiveCompare(title) == .orderedSame }) { workspace.open(found.id, newTab: newTab) }
        else { workspace.open(try model.createNote(title: title, notebook: workspace.selectedNotebook), newTab: newTab) }
    }
    func daily() throws {
        let title = NotebookIndex.dailyTitle()
        if let note = model.notes.first(where: { $0.title == title && $0.notebook == "Daily" }) { workspace.open(note.id); return }
        if !model.notebooks.contains(where: { $0.name == "Daily" }) { try model.createNotebook(name: "Daily") }
        workspace.open(try model.createNote(title: title, notebook: "Daily"))
    }
    /// Metadata rename remains the workspace's operation. Every rewritten document is a normal core edit.
    func rename(_ id: String, to title: String, done: @escaping @MainActor (Bool) -> Void = { _ in }) {
        let old = workspace.title(of: id)
        flushAll { [weak self] ok in
            guard let self, ok else { done(false); return }
            do { try self.workspace.rename(id, to: title) } catch { self.onError?(error); done(false); return }
            let new = self.workspace.title(of: id), notes = self.model.notes.filter { self.model.isWritable($0.id) }
            @MainActor func next(_ i: Int) {
                guard i < notes.count else { self.refreshIndex(); done(true); return }
                let note = notes[i]
                self.model.editNote(id: note.id, work: { handle in
                    let snapshot = try handle.snapshot()
                    for block in snapshot.textBlocks where !block.tombstone && block.role == .document {
                        let text = Markdown.rewriteLinks(block.text, from: old, to: new)
                        if let s = TextSplice.between(block.text, text) {
                            _ = try handle.submit(command: Self.command(block.head, .spliceText(markId: block.id, base: Self.base(block.head), offset: UInt32(s.offset), delete: UInt32(s.delete), insert: s.insert)))
                        }
                    }
                }, completion: { result in
                    if case .success(let snapshot) = result {
                        if let block = snapshot.textBlocks.first(where: { $0.role == .document && !$0.tombstone }) { self.sessions[self.key(note.id, block)]?.adopt(block.text) }
                        self.model.publishText(snapshot)
                    } else if case .failure(let error) = result { self.onError?(error); done(false); return }
                    next(i + 1)
                })
            }
            next(0)
        }
    }
    func importMarkdown(_ source: URL, done: @escaping @MainActor (Result<MarkdownImport, Error>) -> Void) {
        // Reading a vault is independent of live ingest. Parsing happens off main; publishing notes
        // then uses the same serialized writer as normal typing and keeps the source untouched.
        DispatchQueue.global(qos: .utility).async {
            let mapped = Result { try MarkdownImport.map(source) }
            DispatchQueue.main.async { MainActor.assumeIsolated {
                switch mapped {
                case .failure(let error): done(.failure(error))
                case .success(let mapped):
                    @MainActor func next(_ i: Int) {
                        guard i < mapped.entries.count else { self.workspace.libraryChanged(); self.refreshIndex(); done(.success(mapped)); return }
                        let entry = mapped.entries[i]
                        do {
                            if let notebook = entry.notebook, !self.model.notebooks.contains(where: { $0.name == notebook }) { try self.model.createNotebook(name: notebook) }
                            let id = try self.model.createNote(title: entry.title, notebook: entry.notebook)
                            let doc = self.document(note: id); doc.replace(with: entry.text)
                            // An empty Markdown file still gets a document role via the adapter.
                            if entry.text.isEmpty { self.addEmptyDocument(id) { result in if case .failure(let error) = result { done(.failure(error)) } else { next(i + 1) } } }
                            else { doc.flush { ok in if ok { next(i + 1) } else { done(.failure(ViewerError.unavailable)) } } }
                        } catch { done(.failure(error)) }
                    }
                    next(0)
                }
            } }
        }
    }
    private func addEmptyDocument(_ id: String, done: @escaping @MainActor (Result<NoteSnapshot, Error>) -> Void) {
        model.editNote(id: id, work: { handle in
            guard let sheet = try handle.snapshot().sheets.first else { throw ViewerError.unknownSheet }
            _ = try handle.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: [.addText(markId: newId(), sheetId: sheet.id, role: .document, originX: 0, originY: 0, width: 1600, text: "", style: TextStyle(size: .medium, colour: .ink))])))
        }, completion: done)
    }
    func exportMarkdown(note: String, to file: URL, done: @escaping @MainActor (Result<Void, Error>) -> Void) {
        flushAll { [weak self] ok in
            guard let self, ok else { done(.failure(ViewerError.unavailable)); return }
            self.model.readSnapshot(id: note) { result in
                switch result {
                case .failure(let error): done(.failure(error))
                case .success(let snapshot):
                    let text = snapshot.textBlocks.first { $0.role == .document && !$0.tombstone }?.text ?? ""
                    DispatchQueue.global(qos: .utility).async {
                        let written = Result { try MarkdownImport.export(text, to: file) }
                        DispatchQueue.main.async { MainActor.assumeIsolated { done(written) } }
                    }
                }
            }
        }
    }
}
