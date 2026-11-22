import Foundation
import SyncrCore
import Darwin

struct DrawStroke: Sendable {
    let mesh: StrokeMesh
    let originX: Float
    let originY: Float
    let colour: InkColour
    let highlighter: Bool
    /// The writer's own OKLCH ink, when the stroke has one (`colour` is then only a fallback).
    var custom: Oklch? = nil
    /// The stroke this was built from and the operation it was built at, when known: a reload keeps the mesh while both match.
    var markId: String? = nil
    var head: String? = nil
}

/// Mutated only on the main thread; stable ID allows progressive GPU uploads.
final class Drawing {
    let id = UUID()
    let title: String
    var strokes: [DrawStroke]
    var lowestInk: Float
    /// The sheet live ink lands on (the first sheet), once known.
    var sheetId: String?
    var textBlocks: [TextBlockView] = []
    init(title: String, strokes: [DrawStroke], lowestInk: Float) {
        self.title = title; self.strokes = strokes; self.lowestInk = lowestInk
    }
}

private final class LoadToken: @unchecked Sendable {
    private let lock = NSLock()
    private var stopped = false
    func cancel() { lock.lock(); stopped = true; lock.unlock() }
    var cancelled: Bool { lock.lock(); defer { lock.unlock() }; return stopped }
}

/// UI-owned model. Tessellation is serial background work; callbacks are main-thread only.
/// Originals are never opened by core: copy, validate/migrate in staging, then publish.
@MainActor final class LibraryModel {
    var notebookIndex = NotebookIndex()
    private(set) var notes: [NoteSummary] = []
    private(set) var notebooks: [NotebookSummary] = []
    private(set) var deleted: [DeletedNoteSummary] = []
    /// IDs of notes created on this Mac. Anything else in the library is an imported phone copy.
    private(set) var owned: Set<String> = []
    /// Every live note, archived ones included (`notes` leaves archived ones out). Sync and writability use this.
    private(set) var liveIds: Set<String> = []
    /// A note was committed on this Mac (typing, ink, companion, pad, rename, merge). Main thread.
    var onCommit: ((String) -> Void)?
    /// Operations from the phone were merged into this note (the phone already holds them, so nothing is announced back).
    var onPeerMerge: ((String) -> Void)?
    private(set) var drawing: Drawing?
    private(set) var loading = false
    /// Bumped whenever the shown note is (re)opened or cleared; a live commit written under an older
    /// generation is already part of whatever reload followed it.
    private(set) var generation = 0
    /// The note `drawing` belongs to (set as soon as it is opened, before its strokes arrive).
    private(set) var shownNote: String?
    var onChange: (() -> Void)?
    var error: String?
    private(set) var folder: URL
    private(set) var library: SyncrLibrary?
    let worker = DispatchQueue(label: "dev.syncr.mac.tessellation", qos: .userInitiated)
    /// Heavy import work (copy and validate a note) that must not wait behind, or hold up, live ink.
    let importQueue = DispatchQueue(label: "dev.syncr.mac.import", qos: .utility)
    private var loadToken: LoadToken?

    init(folder: URL? = nil) {
        self.folder = folder ?? ProcessInfo.processInfo.environment["SYNCR_MAC_LIBRARY_FOLDER"].map {
            URL(fileURLWithPath: $0, isDirectory: true)
        } ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Documents/Syncr", isDirectory: true)
        do { try loadLibrary() } catch { self.error = error.localizedDescription }
    }

    func setFolder(_ url: URL) {
        generation += 1
        cancelLoad(); worker.sync {} // drain closed handles before switching roots
        library = nil; drawing = nil; shownNote = nil; folder = url
        do { try loadLibrary() } catch { self.error = error.localizedDescription }
    }

    private func loadLibrary() throws {
        let fm = FileManager.default
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        library = try openLibrary(rootPath: folder.path)
        owned = Self.loadOwned(appFolder)
        try refresh()
        // Pre-R7 viewers used a child silo. Copy its notes into this library, never
        // move/delete originals. Identical/newer local copies simply remain in place.
        let legacy = folder.appendingPathComponent("Imported Copies")
        if fm.fileExists(atPath: legacy.path) {
            for source in try fm.contentsOfDirectory(at: legacy, includingPropertiesForKeys: nil)
                where source.pathExtension == "syncr" {
                do { _ = try importCopy(from: source) }
                catch ViewerError.notNewer { continue }
            }
        }
    }

    func refresh() throws {
        let all = try library?.listNotes() ?? []
        liveIds = Set(all.map(\.id))
        notes = all.filter { !$0.archived }
        notebooks = try library?.listNotebooks() ?? []
        deleted = try library?.listDeletedNotes() ?? []
        error = nil
    }

    // MARK: Ownership. Core exposes no creating author for a note (empty notes have no strokes),
    // so the Mac keeps its own record of the IDs it created. Not recorded means a phone copy.

    private var appFolder: URL { folder.appendingPathComponent(".mac", isDirectory: true) }
    var ownedFile: URL { appFolder.appendingPathComponent("owned.json") }
    nonisolated private static func loadOwned(_ dir: URL) -> Set<String> {
        guard let data = try? Data(contentsOf: dir.appendingPathComponent("owned.json")),
              let ids = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return Set(ids)
    }
    private func saveOwned() throws {
        try FileManager.default.createDirectory(at: appFolder, withIntermediateDirectories: true)
        try JSONEncoder().encode(owned.sorted()).write(to: ownedFile, options: .atomic)
    }
    /// Link v1: the Mac holds the primary copy of every note, so every live note is writable. `owned` stays as the
    /// record of which notes were created here.
    func isWritable(_ id: String) -> Bool { liveIds.contains(id) }

    /// App-owned small state (session) lives beside the owned record.
    func readState(_ name: String) -> Data? { try? Data(contentsOf: appFolder.appendingPathComponent(name)) }
    func writeState(_ name: String, _ data: Data) {
        try? FileManager.default.createDirectory(at: appFolder, withIntermediateDirectories: true)
        try? data.write(to: appFolder.appendingPathComponent(name), options: .atomic)
    }

    // MARK: Note and notebook operations. All reopen nothing: callers reload what they display.

    private func requireLibrary() throws -> SyncrLibrary {
        guard let library else { throw ViewerError.unavailable }
        return library
    }

    @discardableResult func createNote(title: String = "Untitled", notebook: String?) throws -> String {
        let library = try requireLibrary()
        let id = newId()
        // Record ownership first: a crash between the two leaves a harmless unused ID.
        owned.insert(id)
        do {
            try saveOwned()
            _ = try library.createNote(request: CreateNoteRequest(operationId: newId(), noteId: id,
                firstSheetId: newId(), title: Self.clean(title, fallback: "Untitled"), notebook: notebook, kind: .note))
        } catch { owned.remove(id); try? saveOwned(); throw error }
        try refresh()
        onCommit?(id)
        return id
    }

    private static func clean(_ text: String, fallback: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }

    /// Metadata command on a note: base and dependency are the note's current metadata head.
    private func submitMetadata(_ id: String, _ body: (Base) -> CommandBody) throws {
        let library = try requireLibrary()
        cancelLoad(); worker.sync {}
        let note = try library.openNote(noteId: id)
        defer { try? note.closeNote() }
        let head = try note.snapshot().metadataHead
        let base: Base = head.confirmedRevision.map { .confirmed(revision: $0) } ?? .operation(operationId: head.operationId)
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [head.operationId], body: body(base)))
        try refresh()
        onCommit?(id)
    }

    func renameNote(id: String, title: String) throws {
        guard isWritable(id) else { throw ViewerError.readOnly }
        let name = Self.clean(title, fallback: "Untitled")
        try submitMetadata(id) { .renameNote(title: name, base: $0) }
    }

    func moveNote(id: String, to notebook: String?) throws {
        guard isWritable(id) else { throw ViewerError.readOnly }
        try submitMetadata(id) { .setNotebook(notebook: notebook, base: $0) }
    }

    /// Imported copies may be deleted from this Mac; the phone's original is not touched.
    func deleteNote(id: String) throws {
        let library = try requireLibrary()
        cancelLoad(); worker.sync {}
        try library.deleteNote(noteId: id)
        try refresh()
    }

    func restoreNote(id: String) throws {
        try requireLibrary().restoreNote(noteId: id)
        try refresh()
        onCommit?(id)
    }

    func purgeNote(id: String) throws {
        try requireLibrary().purgeNote(noteId: id)
        if owned.remove(id) != nil { try saveOwned() }
        try refresh()
    }

    func createNotebook(name: String) throws {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { throw ViewerError.badName }
        try requireLibrary().createNotebook(name: name)
        try refresh()
    }

    private func members(of notebook: String) -> [NoteSummary] { notes.filter { $0.notebook == notebook } }

    /// Core rewrites every member note; imported copies are not ours to change, so refuse then.
    func renameNotebook(_ old: String, to new: String) throws {
        let new = new.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !new.isEmpty else { throw ViewerError.badName }
        guard members(of: old).allSatisfy({ isWritable($0.id) }) else { throw ViewerError.readOnlyMembers }
        let affected = members(of: old).map(\.id)
        cancelLoad(); worker.sync {}
        try requireLibrary().renameNotebook(oldName: old, newName: new)
        try refresh()
        affected.forEach { onCommit?($0) }
    }

    /// Notes move to the Inbox first (core only removes an empty registry entry).
    func deleteNotebook(_ name: String) throws {
        let library = try requireLibrary()
        let inside = members(of: name)
        guard inside.allSatisfy({ isWritable($0.id) }) else { throw ViewerError.readOnlyMembers }
        for note in inside { try moveNote(id: note.id, to: nil) }
        try library.deleteNotebook(name: name)
        try refresh()
    }

    /// Stop showing the current note (last tab closed).
    func clearDrawing() { generation += 1; cancelLoad(); drawing = nil; shownNote = nil; error = nil; onChange?() }

    /// Validate a standalone export in an isolated library. Handles/SQLite WAL are closed
    /// before publication; unsupported/invalid input never replaces a visible note.
    nonisolated private static func validate(_ file: URL) throws -> NoteSummary {
        let staging = try openLibrary(rootPath: file.deletingLastPathComponent().path)
        let summaries = try staging.listNotes()
        guard summaries.count == 1, let summary = summaries.first else { throw ViewerError.invalidSource }
        let note = try staging.openNote(noteId: summary.id)
        do {
            let snapshot = try note.snapshot()
            for mark in snapshot.strokes { _ = try decodeSamples(blob: note.getSamples(markId: mark.id)) }
            try note.closeNote()
        } catch { try? note.closeNote(); throw error }
        return summary
    }

    /// A copy staged in its own hidden folder and validated there. Touches nothing visible.
    struct PreparedImport: Sendable {
        let staging: URL
        let file: URL
        let summary: NoteSummary
    }

    /// Check hook for the off-main-thread check: runs first in the heavy step, on whatever thread runs it.
    nonisolated(unsafe) static var prepareHook: (@Sendable () -> Void)?

    /// The heavy part of an import (copy, open in an isolated library, decode every stroke). Touches no
    /// model state, so it can run on any thread, and it never opens the library the user is looking at.
    nonisolated private static func prepareImport(from source: URL, in folder: URL) throws -> PreparedImport {
        prepareHook?()
        let fm = FileManager.default
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              source.pathExtension.lowercased() == "syncr" else { throw ViewerError.invalidSource }
        for suffix in ["-wal", "-journal"] {
            let sidecar = URL(fileURLWithPath: source.path + suffix)
            if fm.fileExists(atPath: sidecar.path), (try sidecar.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) > 0 {
                throw ViewerError.invalidSource // an active SQLite main file alone is not an export
            }
        }
        let staging = folder.appendingPathComponent(".incoming-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            let file = staging.appendingPathComponent("note.syncr")
            try fm.copyItem(at: source, to: file)
            return PreparedImport(staging: staging, file: file, summary: try validate(file))
        } catch { try? fm.removeItem(at: staging); throw error }
    }

    /// Main thread: may this copy replace what the library holds? Returns the held note it would replace.
    private func admit(_ prepared: PreparedImport) throws -> NoteSummary? {
        guard let library else { throw ViewerError.unavailable }
        if owned.contains(prepared.summary.id) { throw ViewerError.macNote }
        let existing = try library.listNotes().first { $0.id == prepared.summary.id }
        if let existing, prepared.summary.localSequence <= existing.localSequence { throw ViewerError.notNewer }
        return existing
    }

    /// The file operations of an import: backups, then one atomic rename. Every note handle must be closed
    /// first (the caller is on the worker queue, or has drained it). Returns the previous copy's backup.
    nonisolated private static func publish(_ prepared: PreparedImport, in folder: URL, replacing existing: NoteSummary?) throws -> URL? {
        let fm = FileManager.default
        let incoming = prepared.summary
        let destination = folder.appendingPathComponent("\(incoming.id).syncr")
        if existing != nil && !fm.fileExists(atPath: destination.path) {
            // Never overwrite an unmanaged filename merely because its internal ID matches.
            throw ViewerError.unmanagedDestination
        }
        // Preserve migration backups *before* publishing, so a backup-copy failure
        // cannot report failure after replacing the visible note.
        let upgrades = prepared.staging.appendingPathComponent(".backups")
        if fm.fileExists(atPath: upgrades.path) {
            let keep = folder.appendingPathComponent(".backups", isDirectory: true)
            try fm.createDirectory(at: keep, withIntermediateDirectories: true)
            for item in try fm.contentsOfDirectory(at: upgrades, includingPropertiesForKeys: nil) {
                try fm.copyItem(at: item, to: keep.appendingPathComponent("\(UUID().uuidString)-\(item.lastPathComponent)"))
            }
        }
        // Every note handle is closed. A nonempty destination WAL means this is not
        // standalone and must not be replaced; never discard journalled source data.
        let wal = URL(fileURLWithPath: destination.path + "-wal")
        if fm.fileExists(atPath: wal.path), (try wal.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) > 0 {
            throw ViewerError.unmanagedDestination
        }
        for suffix in ["-wal", "-shm"] {
            let sidecar = URL(fileURLWithPath: destination.path + suffix)
            if fm.fileExists(atPath: sidecar.path) { try fm.removeItem(at: sidecar) }
        }
        var backup: URL?
        if let existing {
            let archive = folder.appendingPathComponent(".replaced", isDirectory: true)
            try fm.createDirectory(at: archive, withIntermediateDirectories: true)
            backup = archive.appendingPathComponent("\(incoming.id).\(existing.localSequence).\(UUID().uuidString).syncr")
            try fm.copyItem(at: destination, to: backup!)
        }
        // rename(2) on the same filesystem atomically replaces an existing pathname.
        // Keep the previous standalone note under .replaced for explicit recovery.
        guard Darwin.rename(prepared.file.path, destination.path) == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        return backup
    }

    /// Main thread, after `publish`: reads the library again; if that fails the previous copy is put back.
    private func finishImport(_ summary: NoteSummary, backup: URL?) throws {
        do { try refresh() } catch {
            let destination = folder.appendingPathComponent("\(summary.id).syncr")
            if let backup { _ = Darwin.rename(backup.path, destination.path) }
            else { try? FileManager.default.removeItem(at: destination) }
            try? refresh()
            throw error
        }
    }

    @discardableResult func importCopy(from source: URL) throws -> NoteSummary {
        guard library != nil else { throw ViewerError.unavailable }
        let prepared = try Self.prepareImport(from: source, in: folder)
        defer { try? FileManager.default.removeItem(at: prepared.staging) }
        // A copy of a note the Mac already holds is merged into it by operations, never put over it.
        if liveIds.contains(prepared.summary.id) {
            try mergeSynchronously(copy: prepared.file, noteId: prepared.summary.id)
            return notes.first { $0.id == prepared.summary.id } ?? prepared.summary
        }
        let existing = try admit(prepared)
        // Stop reads of the destination and close all handles before replacing its inode.
        cancelLoad(); worker.sync {}
        let backup = try Self.publish(prepared, in: folder, replacing: existing)
        try finishImport(prepared.summary, backup: backup)
        return prepared.summary
    }

    /// The same import without stalling the main thread: the heavy copy and validation run on a queue of
    /// their own (never the tessellation queue, so a live stroke being saved does not wait behind them),
    /// the decision and the final `refresh` run on main, and the file swap runs on the tessellation queue
    /// behind any running load, which is what the synchronous path waits for. `completion` runs on main.
    func importCopyAsync(from source: URL, completion: @escaping @MainActor (Result<NoteSummary, Error>) -> Void) {
        guard library != nil else { completion(.failure(ViewerError.unavailable)); return }
        let folder = folder
        importQueue.async { [weak self] in
            let prepared: PreparedImport
            do { prepared = try Self.prepareImport(from: source, in: folder) }
            catch { DispatchQueue.main.async { MainActor.assumeIsolated { completion(.failure(error)) } }; return }
            DispatchQueue.main.async { MainActor.assumeIsolated {
                let end: @MainActor @Sendable (Result<NoteSummary, Error>) -> Void = { result in
                    try? FileManager.default.removeItem(at: prepared.staging)
                    completion(result)
                }
                guard let self, self.folder == folder else { end(.failure(ViewerError.unavailable)); return }
                let existing: NoteSummary?
                do { existing = try self.admit(prepared) } catch { end(.failure(error)); return }
                self.cancelLoad()
                self.worker.async {
                    let published = Result { try Self.publish(prepared, in: folder, replacing: existing) }
                    DispatchQueue.main.async { MainActor.assumeIsolated {
                        switch published {
                        case .failure(let error): end(.failure(error))
                        case .success(let backup):
                            do { try self.finishImport(prepared.summary, backup: backup); end(.success(prepared.summary)) }
                            catch { end(.failure(error)) }
                        }
                    } }
                }
            } }
        }
    }

    func cancelLoad() { loadToken?.cancel(); loadToken = nil; loading = false }

    nonisolated private static func stroke(_ mark: StrokeView, note: Note, sheets: [String: SheetView]) throws -> DrawStroke {
        guard let sheet = sheets[mark.sheetId] else { throw ViewerError.invalidSource }
        let mesh = try tessellateStroke(samples: note.getSamples(markId: mark.id),
            style: defaultStrokeStyle(brush: mark.brush), transform: mark.transform)
        guard mesh.rendererVersion == meshRendererVersion() else { throw ViewerError.invalidSource }
        return DrawStroke(mesh: mesh, originX: sheet.originX, originY: sheet.originY,
                          colour: mark.brush.colour, highlighter: mesh.discardSelfOverlap, custom: mark.brush.customInk,
                          markId: mark.id, head: mark.head.operationId)
    }

    /// Synchronous path for disposable fixture checks only; the GUI uses openAsync.
    func open(id: String) throws {
        guard let library else { throw ViewerError.unavailable }
        generation += 1
        cancelLoad(); worker.sync {}
        let note = try library.openNote(noteId: id)
        defer { try? note.closeNote() }
        let snapshot = try note.snapshot()
        let sheets = Dictionary(uniqueKeysWithValues: snapshot.sheets.map { ($0.id, $0) })
        let strokes = try snapshot.strokes.filter { !$0.tombstone }.map { try Self.stroke($0, note: note, sheets: sheets) }
        shownNote = id
        drawing = Drawing(title: snapshot.title, strokes: strokes,
                          lowestInk: strokes.map { $0.originY + $0.mesh.maxY }.max() ?? 0)
        drawing?.sheetId = snapshot.sheets.first?.id
        drawing?.textBlocks = snapshot.textBlocks
        error = nil
    }

    func openAsync(id: String) {
        generation += 1
        cancelLoad(); drawing = nil; shownNote = id; error = nil
        guard let library else { error = ViewerError.unavailable.localizedDescription; onChange?(); return }
        let token = LoadToken(); loadToken = token; loading = true
        onChange?()
        worker.async { [weak self] in
            do {
                if token.cancelled { return }
                let note = try library.openNote(noteId: id)
                defer { try? note.closeNote() }
                let snapshot = try note.snapshot()
                let sheets = Dictionary(uniqueKeysWithValues: snapshot.sheets.map { ($0.id, $0) })
                DispatchQueue.main.async { [weak self] in
                    guard !token.cancelled else { return }
                    self?.drawing = Drawing(title: snapshot.title, strokes: [], lowestInk: 0)
                    self?.drawing?.sheetId = snapshot.sheets.first?.id
                    self?.drawing?.textBlocks = snapshot.textBlocks
                    self?.onChange?()
                }
                var batch: [DrawStroke] = []
                var lastDelivery = Date()
                for mark in snapshot.strokes where !mark.tombstone {
                    if token.cancelled { return }
                    batch.append(try Self.stroke(mark, note: note, sheets: sheets))
                    if batch.count >= 32 || Date().timeIntervalSince(lastDelivery) >= 0.025 {
                        let completed = batch; batch = []; lastDelivery = Date()
                        DispatchQueue.main.async { [weak self] in self?.append(completed, token: token) }
                    }
                }
                let completed = batch
                DispatchQueue.main.async { [weak self] in
                    guard !token.cancelled else { return }
                    self?.append(completed, token: token); self?.loading = false; self?.onChange?()
                }
            } catch {
                let message = error.localizedDescription
                DispatchQueue.main.async { [weak self] in
                    guard !token.cancelled else { return }
                    self?.error = message; self?.loading = false; self?.onChange?()
                }
            }
        }
    }

    // MARK: Reload in place

    /// Reads the note again (after a newer copy was installed) on the tessellation queue and swaps the
    /// new drawing in at once, with no empty frame in between; the old drawing stays visible until then.
    /// `swapped` runs on the main thread in the same block as the swap, with the IDs of every stroke the
    /// copy contains, live or deleted, so the caller can drop their previews before the next draw. Nothing happens if the
    /// shown note changed meanwhile. The caller keeps the viewport: this does not touch it.
    func reloadInPlace(id: String, swapped: @escaping @MainActor (Set<String>) -> Void = { _ in }) {
        guard let library, shownNote == id else { return }
        cancelLoad()
        let started = generation
        let token = LoadToken(); loadToken = token
        // A merge of text (or anything but ink) leaves the strokes as they were: their meshes are kept, not rebuilt from samples.
        let kept = Dictionary((drawing?.strokes ?? []).compactMap { s in s.markId.map { ($0, s) } }, uniquingKeysWith: { a, _ in a })
        worker.async { [weak self] in
            let built = Result { () -> (strokes: [DrawStroke], title: String, sheet: String?, ids: Set<String>, text: [TextBlockView]) in
                let note = try library.openNote(noteId: id)
                defer { try? note.closeNote() }
                let snapshot = try note.snapshot()
                let sheets = Dictionary(uniqueKeysWithValues: snapshot.sheets.map { ($0.id, $0) })
                let marks = snapshot.strokes.filter { !$0.tombstone }
                // Every stroke the copy knows, deleted ones included: a stroke the phone wrote and then erased
                // before this copy is tombstoned here, and its live preview must go too.
                return (try marks.map { mark in
                    if let old = kept[mark.id], old.head == mark.head.operationId { return old }
                    return try Self.stroke(mark, note: note, sheets: sheets)
                }, snapshot.title,
                        snapshot.sheets.first?.id, Set(snapshot.strokes.map(\.id)), snapshot.textBlocks)
            }
            DispatchQueue.main.async { MainActor.assumeIsolated {
                guard let self, !token.cancelled, self.generation == started, self.shownNote == id else { return }
                switch built {
                case .success(let b):
                    let new = Drawing(title: b.title, strokes: b.strokes, lowestInk: b.strokes.map { $0.originY + $0.mesh.maxY }.max() ?? 0)
                    new.sheetId = b.sheet; new.textBlocks = b.text
                    self.generation += 1
                    self.drawing = new; self.loading = false; self.error = nil
                    swapped(b.ids)
                    self.onChange?()
                case .failure(let error):
                    self.error = error.localizedDescription; self.onChange?()
                }
            } }
        }
    }

    // MARK: Notebook text work

    /// All note handles stay serialized with existing loads and ink commits. TextKit never waits here.
    /// A save is queued immediately; tab changes queue their load behind it. Failures return to the
    /// retained editor session instead of replacing its buffer with a stale snapshot.
    func editNote(id: String, work: @escaping @Sendable (Note) throws -> Void,
                  completion: @escaping @MainActor (Result<NoteSnapshot, Error>) -> Void) {
        guard let library, isWritable(id) else { completion(.failure(isWritable(id) ? ViewerError.unavailable : ViewerError.readOnly)); return }
        worker.async {
            let result = Result { () -> NoteSnapshot in
                let note = try library.openNote(noteId: id)
                defer { try? note.closeNote() }
                try work(note)
                return try note.snapshot()
            }
            DispatchQueue.main.async { MainActor.assumeIsolated { if case .success = result { self.onCommit?(id) }; completion(result) } }
        }
    }

    func readSnapshot(id: String, completion: @escaping @MainActor (Result<NoteSnapshot, Error>) -> Void) {
        guard let library else { completion(.failure(ViewerError.unavailable)); return }
        worker.async {
            let result = Result { () -> NoteSnapshot in
                let note = try library.openNote(noteId: id)
                defer { try? note.closeNote() }
                return try note.snapshot()
            }
            DispatchQueue.main.async { MainActor.assumeIsolated { completion(result) } }
        }
    }

    func publishText(_ snapshot: NoteSnapshot) {
        if shownNote == snapshot.noteId { drawing?.textBlocks = snapshot.textBlocks }
        if let i = notes.firstIndex(where: { $0.id == snapshot.noteId }) { notes[i].localSequence = snapshot.localSequence }
        onChange?()
    }

    func drainNotebookWork(_ done: @escaping @MainActor () -> Void) {
        worker.async { DispatchQueue.main.async { MainActor.assumeIsolated { done() } } }
    }

    // MARK: Live ink commit

    /// Writes a reassembled live stroke into a writable note on the tessellation queue (serial with
    /// loads and metadata edits, so the note is never opened twice), then reads it back and tessellates
    /// exactly as a reopen would. `completion` runs on the main thread; on failure the note is unchanged.
    func appendLiveStroke(_ commit: StrokeCommit, view: Viewport, completion: @escaping @MainActor (Result<DrawStroke, Error>) -> Void) {
        guard let library, isWritable(commit.noteId) else {
            completion(.failure(library == nil ? ViewerError.unavailable : ViewerError.readOnly)); return
        }
        worker.async {
            let result = Result { try Self.write(commit, view: view, library: library) }
            DispatchQueue.main.async { MainActor.assumeIsolated { if case .success = result { self.onCommit?(commit.noteId) }; completion(result) } }
        }
    }

    nonisolated private static func write(_ commit: StrokeCommit, view: Viewport, library: SyncrLibrary) throws -> DrawStroke {
        let note = try library.openNote(noteId: commit.noteId)
        defer { try? note.closeNote() }
        let snapshot = try note.snapshot()
        guard let sheet = snapshot.sheets.first(where: { $0.id == commit.sheetId }) else { throw ViewerError.unknownSheet }
        // Stored samples are local to the sheet: canvas coordinates minus its origin.
        var channels = try decodeSamples(blob: commit.samples)
        for i in 0..<channels.x.count {
            let (x, y) = commit.space == .viewRelative ? view.canvasPoint(channels.x[i], channels.y[i]) : (channels.x[i], channels.y[i])
            channels.x[i] = x - sheet.originX; channels.y[i] = y - sheet.originY
        }
        let blob = commit.space == .canvas && sheet.originX == 0 && sheet.originY == 0
            ? commit.samples : try encodeSamples(channels: channels)
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId],
            body: .marks(mutations: [.addStroke(markId: commit.strokeId, sheetId: sheet.id, samples: blob, brush: commit.brush)])))
        let mesh = try tessellateStroke(samples: note.getSamples(markId: commit.strokeId),
            style: defaultStrokeStyle(brush: commit.brush), transform: Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0))
        guard mesh.rendererVersion == meshRendererVersion() else { throw ViewerError.invalidSource }
        return DrawStroke(mesh: mesh, originX: sheet.originX, originY: sheet.originY,
                          colour: commit.brush.colour, highlighter: mesh.discardSelfOverlap, custom: commit.brush.customInk)
    }

    /// Adds a freshly written stroke to the shown note (main thread, same block as removing its preview).
    func insert(_ stroke: DrawStroke) {
        guard let drawing else { return }
        drawing.strokes.append(stroke)
        drawing.lowestInk = max(drawing.lowestInk, stroke.originY + stroke.mesh.maxY)
        onChange?()
    }

    private func append(_ strokes: [DrawStroke], token: LoadToken) {
        guard !token.cancelled, let drawing else { return }
        drawing.strokes.append(contentsOf: strokes)
        drawing.lowestInk = max(drawing.lowestInk, strokes.map { $0.originY + $0.mesh.maxY }.max() ?? 0)
        onChange?()
    }
}

enum ViewerError: Error, LocalizedError {
    case unavailable, unknownSheet, invalidSource, notNewer, unmanagedDestination, readOnly, readOnlyMembers, badName, macNote
    var errorDescription: String? {
        switch self {
        case .unavailable: "Library is unavailable"
        case .unknownSheet: "The note has no such sheet"
        case .invalidSource: "Not a readable standalone Syncr note export"
        case .notNewer: "This note is older than or identical to the Mac's read-only copy"
        case .unmanagedDestination: "Existing note has an unmanaged filename; refusing replacement"
        case .readOnly: "This note is not in the library"
        case .readOnlyMembers: "This notebook contains read-only imported copies"
        case .badName: "Name cannot be empty"
        case .macNote: "A note with this ID was created on this Mac; refusing to replace it with a copy"
        }
    }
}
