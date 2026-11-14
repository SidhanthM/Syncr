import CryptoKit
import Foundation
import SyncrCore

// Two-way sync on the Mac (tasks/contracts/sync-v1.md). The Mac library is the primary copy: a phone copy of a note the
// Mac already has is merged into it by operations, never replaced, and an operation the reducer rejects is saved under
// `<library>/.mac/sync-conflicts/<note>/<operation>.json`. Every note handle opened here is opened, used and closed on
// the model's serial `worker` queue, like every other note edit, so a merge never races an ink commit.

/// What a merge or an install left behind, for the `have` reply and the log.
struct SyncResult: Sendable, Equatable {
    var installed: Bool
    var applied: Int
    var duplicates: Int
    var rejected: Int
    var seq: UInt64
    var digest: String
    var ops: UInt64
    /// A delta that left the note holding fewer operations from some author than the phone has: ask for the whole note.
    var short: Bool = false
}

/// A copy of a note staged for sending to the phone.
struct SyncExport: Sendable {
    var file: URL
    var size: UInt64
    var sha256: String
    var seq: UInt64
    var digest: String
    var ops: UInt64
    /// `file` holds only operations (a delta), not a whole note.
    var delta: Bool = false
}

enum SyncError: Error, LocalizedError {
    case deletedNote, unavailable
    var errorDescription: String? {
        switch self {
        case .deletedNote: "The note is in Recently deleted"
        case .unavailable: "Library is unavailable"
        }
    }
}

extension LibraryModel {
    var conflictsFolder: URL { folder.appendingPathComponent(".mac/sync-conflicts", isDirectory: true) }
    var outgoingFolder: URL { folder.appendingPathComponent(".outgoing-link", isDirectory: true) }

    /// Files under `.mac/sync-conflicts`, newest layout `<note>/<operation>.json`.
    func conflictFiles() -> [URL] {
        let fm = FileManager.default
        guard let notes = try? fm.contentsOfDirectory(at: conflictsFolder, includingPropertiesForKeys: nil) else { return [] }
        return notes.flatMap { (try? fm.contentsOfDirectory(at: $0, includingPropertiesForKeys: nil)) ?? [] }
            .filter { $0.pathExtension == "json" }.sorted { $0.path < $1.path }
    }

    /// Operation-set digests of the given notes, read on the worker queue (closed notes are read-only).
    func syncDigests(_ ids: [String], completion: @escaping @MainActor ([String: SyncDigest]) -> Void) {
        guard let library else { completion([:]); return }
        worker.async {
            var out: [String: SyncDigest] = [:]
            for id in ids { if let d = try? library.noteSyncDigest(noteId: id) { out[id] = d } }
            DispatchQueue.main.async { MainActor.assumeIsolated { completion(out) } }
        }
    }

    /// The newest operation ID per author the Mac holds for a note, read on the worker queue. Empty when it has none.
    func syncHeads(_ id: String, completion: @escaping @MainActor ([String: String]) -> Void) {
        guard let library else { completion([:]); return }
        worker.async {
            let heads = (try? library.noteSyncHeads(noteId: id)) ?? []
            let map = Dictionary(heads.map { ($0.authorId, $0.newestId) }, uniquingKeysWith: { max($0, $1) })
            DispatchQueue.main.async { MainActor.assumeIsolated { completion(map) } }
        }
    }

    /// Every live note (archived included) as the Mac announces it to a v1 phone, minus `excluding`.
    func syncAnnouncements(only: Set<String>? = nil, excluding: Set<String>, completion: @escaping @MainActor ([NoteAnnounce]) -> Void) {
        guard let library else { completion([]); return }
        worker.async {
            let listed = (try? library.listNotes()) ?? []
            let out: [NoteAnnounce] = listed.compactMap { n in
                guard !excluding.contains(n.id), only.map({ $0.contains(n.id) }) ?? true,
                      let d = try? library.noteSyncDigest(noteId: n.id) else { return nil }
                return NoteAnnounce(id: n.id, seq: n.localSequence, title: String(n.title.prefix(100)), digest: d.digest, ops: d.operationCount)
            }
            DispatchQueue.main.async { MainActor.assumeIsolated { completion(out) } }
        }
    }

    /// A verified phone copy is staged at `file`: install it when the Mac lacks the note, otherwise merge its operations
    /// into the Mac's. `completion` runs on the main thread after the library was read again.
    func syncIncoming(file: URL, noteId: String, phone: String, completion: @escaping @MainActor (Result<SyncResult, Error>) -> Void) {
        guard let library else { completion(.failure(SyncError.unavailable)); return }
        if deleted.contains(where: { $0.id == noteId }) { completion(.failure(SyncError.deletedNote)); return }
        let finish: @Sendable (Result<SyncResult, Error>) -> Void = { [weak self] result in
            DispatchQueue.main.async { MainActor.assumeIsolated {
                guard let self else { return }
                if case .success(let r) = result {
                    _ = try? self.refresh()
                    if r.installed || r.applied > 0 { self.onCommit?(noteId) }
                }
                completion(result)
            } }
        }
        if liveIds.contains(noteId) {
            let conflicts = conflictsFolder
            worker.async {
                let result = Result { () -> SyncResult in
                    let note = try library.openNote(noteId: noteId)
                    defer { try? note.closeNote() }
                    let report = try note.mergeCopy(copyPath: file.path)
                    Self.saveConflicts(report.rejected, noteId: noteId, phone: phone, into: conflicts)
                    let d = try note.syncDigest()
                    return SyncResult(installed: false, applied: Int(report.applied), duplicates: Int(report.duplicates),
                                      rejected: report.rejected.count, seq: try note.snapshot().localSequence, digest: d.digest, ops: d.operationCount)
                }
                finish(result)
            }
        } else {
            importQueue.async {
                let result = Result { () -> SyncResult in
                    let summary = try library.installCopy(copyPath: file.path)
                    let d = try library.noteSyncDigest(noteId: summary.id)
                    return SyncResult(installed: true, applied: 0, duplicates: 0, rejected: 0, seq: summary.localSequence, digest: d.digest, ops: d.operationCount)
                }
                finish(result)
            }
        }
    }

    /// A verified delta from the phone is staged at `file`: merge it into the note. The note must exist and be live; a delta
    /// never installs. `short` in the result means ask the phone for the whole note.
    func syncIncomingDelta(file: URL, noteId: String, phone: String, completion: @escaping @MainActor (Result<SyncResult, Error>) -> Void) {
        guard let library else { completion(.failure(SyncError.unavailable)); return }
        if deleted.contains(where: { $0.id == noteId }) { completion(.failure(SyncError.deletedNote)); return }
        guard liveIds.contains(noteId) else { completion(.failure(SyncError.unavailable)); return }
        let conflicts = conflictsFolder
        worker.async { [weak self] in
            let result = Result { () -> SyncResult in
                let note = try library.openNote(noteId: noteId)
                defer { try? note.closeNote() }
                let merged = try note.mergeDelta(deltaPath: file.path)
                Self.saveConflicts(merged.report.rejected, noteId: noteId, phone: phone, into: conflicts)
                let d = try note.syncDigest()
                return SyncResult(installed: false, applied: Int(merged.report.applied), duplicates: Int(merged.report.duplicates),
                                  rejected: merged.report.rejected.count, seq: try note.snapshot().localSequence, digest: d.digest,
                                  ops: d.operationCount, short: !merged.complete)
            }
            DispatchQueue.main.async { MainActor.assumeIsolated {
                guard let self else { return }
                if case .success(let r) = result {
                    _ = try? self.refresh()
                    if r.applied > 0 { self.onCommit?(noteId) }
                }
                completion(result)
            } }
        }
    }

    /// Merges a copy into a note the Mac has, synchronously (menu import). Returns the report.
    @discardableResult func mergeSynchronously(copy: URL, noteId: String) throws -> SyncResult {
        let library = try requireLibraryForSync()
        cancelLoad(); worker.sync {}
        let note = try library.openNote(noteId: noteId)
        defer { try? note.closeNote() }
        let report = try note.mergeCopy(copyPath: copy.path)
        Self.saveConflicts(report.rejected, noteId: noteId, phone: "import", into: conflictsFolder)
        let d = try note.syncDigest()
        let seq = try note.snapshot().localSequence
        try refresh()
        if report.applied > 0 { onCommit?(noteId) }
        return SyncResult(installed: false, applied: Int(report.applied), duplicates: Int(report.duplicates), rejected: report.rejected.count,
                          seq: seq, digest: d.digest, ops: d.operationCount)
    }

    private func requireLibraryForSync() throws -> SyncrLibrary {
        guard let library else { throw SyncError.unavailable }
        return library
    }

    /// Saves each rejected operation whole (author, payload JSON, canonical bytes as hex, reason).
    nonisolated static func saveConflicts(_ rejected: [RejectedOperation], noteId: String, phone: String, into root: URL) {
        guard !rejected.isEmpty else { return }
        let dir = root.appendingPathComponent(noteId, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for r in rejected where LinkIds.valid(r.operationId) {
            let object: [String: Any] = [
                "note_id": noteId, "operation_id": r.operationId, "author_id": r.authorId, "reason": r.reason,
                "payload_json": r.payloadJson, "canonical_hex": r.canonicalHex, "from": phone,
                "saved_at_ms": Int64(Date().timeIntervalSince1970 * 1000),
            ]
            guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { continue }
            try? data.write(to: dir.appendingPathComponent("\(r.operationId).json"), options: .atomic)
        }
    }

    /// A staged copy of a note for the phone: exported on the worker queue, hashed on the import queue.
    /// With `base` (the newest operation the phone holds per author) only the operations newer than it are exported, when the
    /// core finds that smaller than the note; otherwise, and without `base`, the whole note.
    func syncExport(noteId: String, base: [String: String]? = nil, completion: @escaping @MainActor (Result<SyncExport, Error>) -> Void) {
        guard let library else { completion(.failure(SyncError.unavailable)); return }
        let outgoing = outgoingFolder
        worker.async { [importQueue] in
            let exported = Result { () -> (URL, UInt64, SyncDigest, Bool) in
                try FileManager.default.createDirectory(at: outgoing, withIntermediateDirectories: true)
                let file = outgoing.appendingPathComponent("\(noteId)-\(UUID().uuidString).syncr")
                let note = try library.openNote(noteId: noteId)
                defer { try? note.closeNote() }
                if let base, !base.isEmpty {
                    let heads = base.map { AuthorHead(authorId: $0.key, newestId: $0.value) }
                    if let receipt = try? note.exportDelta(heads: heads, destinationPath: file.path), receipt.anchored {
                        return (file, receipt.exportedLocalSequence, try note.syncDigest(), true)
                    }
                    try? FileManager.default.removeItem(at: file)
                }
                let receipt = try note.exportCopy(destinationPath: file.path)
                return (file, receipt.exportedLocalSequence, try note.syncDigest(), false)
            }
            importQueue.async {
                let result = Result { () -> SyncExport in
                    let (file, seq, digest, delta) = try exported.get()
                    do {
                        let (size, sha) = try Self.hashFile(file)
                        return SyncExport(file: file, size: size, sha256: sha, seq: seq, digest: digest.digest, ops: digest.operationCount, delta: delta)
                    } catch { try? FileManager.default.removeItem(at: file); throw error }
                }
                DispatchQueue.main.async { MainActor.assumeIsolated { completion(result) } }
            }
        }
    }

    nonisolated static func hashFile(_ url: URL) throws -> (UInt64, String) {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        var size: UInt64 = 0
        while let data = try handle.read(upToCount: 1 << 20), !data.isEmpty {
            hasher.update(data: data); size += UInt64(data.count)
        }
        return (size, hasher.finalize().map { String(format: "%02x", $0) }.joined())
    }
}
