import AppKit
import SyncrCore
import SyncrCompanion

/// Locked box for results produced on the note worker queue.
final class WorkerBox<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock(); private var value: Value
    init(_ value: Value) { self.value = value }
    func set(_ new: Value) { lock.lock(); value = new; lock.unlock() }
    func get() -> Value { lock.lock(); defer { lock.unlock() }; return value }
}

/// Images stored in notes: placing a pasted or dropped image under the caret line through the core, reading the
/// immutable stored bytes on the note worker (never the main thread), and handing decoded images to the view.
@MainActor final class NoteImageStore {
    /// Canvas units an image is drawn at: at most this wide, in proportion.
    nonisolated static let displayWidth: CGFloat = 720
    private var cache: [String: NSImage] = [:]
    private var loading = Set<String>()
    private var failed = Set<String>()

    func forget() { cache = [:]; loading = []; failed = [] }

    /// The decoded image if stored bytes are ready; otherwise starts a read and calls `ready` when it lands.
    func image(_ id: String, note: String, model: LibraryModel, ready: @escaping () -> Void) -> NSImage? {
        if let image = cache[id] { return image }
        guard !loading.contains(id), !failed.contains(id) else { return nil }
        loading.insert(id)
        let box = WorkerBox<Data?>(nil)
        model.editNote(id: note, work: { box.set(try $0.getImageBytes(imageId: id)) }) { [weak self] result in
            guard let self else { return }
            self.loading.remove(id)
            if case .success = result, let data = box.get(), let image = NSImage(data: data) { self.cache[id] = image; ready() }
            else { self.failed.insert(id) }
        }
        return nil
    }

    /// Stored bytes of several images, each scaled to `longSide` for the provider. Off the main thread end to end.
    func providerCopies(_ ids: [String], note: String, model: LibraryModel, longSide: Int = 1280) async -> [NoteImageCopy] {
        guard !ids.isEmpty else { return [] }
        let box = WorkerBox<[String: Data]>([:])
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            model.editNote(id: note, work: { handle in
                var found: [String: Data] = [:]
                for id in ids { if let data = try? handle.getImageBytes(imageId: id) { found[id] = data } }
                box.set(found)
            }) { _ in done.resume() }
        }
        let bytes = box.get()
        return await Task.detached(priority: .utility) {
            ids.compactMap { id -> NoteImageCopy? in
                guard let data = bytes[id], let scaled = try? NoteImages.prepare(data, longSide: longSide) else { return nil }
                return NoteImageCopy(id: id, data: scaled.data, mime: scaled.mime)
            }
        }.value
    }

    /// Stores `data` (already prepared by NoteImages) under the paragraph holding byte offset `at`. The text anchor is
    /// created first with the ordinary companion API, then the image is added with a text placement on that anchor.
    nonisolated static func place(_ image: PreparedNoteImage, atUTF8 at: Int, in note: Note) throws {
        let snapshot = try note.snapshot()
        guard let block = snapshot.textBlocks.first(where: { $0.role == .document && !$0.tombstone }) else { throw NoteImages.ImageError.invalid }
        let bytes = Array(block.text.utf8), caret = max(0, min(at, bytes.count))
        let start = bytes[..<caret].lastIndex(of: 10).map { $0 + 1 } ?? 0
        let end = bytes[caret...].firstIndex(of: 10) ?? bytes.count
        // A blank line still needs a non-empty anchor range; fall back to the nearest text.
        let span = end > start ? (start, end) : (max(0, start - 1), max(start, min(bytes.count, start + 1)))
        let anchorID = newId()
        let anchor = CompanionMutation(id: anchorID, base: .absent, tombstone: false, body: .anchor(target: .text(blockId: block.id, start: UInt32(span.0), end: UInt32(span.1), detached: false)))
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [block.head.operationId], body: .companionMarks(mutations: [anchor])))
        guard let head = try note.snapshot().companionItems.first(where: { $0.id == anchorID })?.head else { throw NoteImages.ImageError.invalid }
        let width = Double(displayWidth), height = (width * Double(image.height) / Double(max(1, image.width))).rounded()
        let add = ImageMutation.add(imageId: newId(), bytes: image.data, mediaType: image.mime == "image/png" ? .png : .jpeg,
                                    pixelWidth: UInt32(image.width), pixelHeight: UInt32(image.height), placement: .text(anchorId: anchorID, width: width, height: height))
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [head.operationId], body: .imageMarks(mutations: [add])))
    }

    /// Agent route: the companion has looked at these images. Monotone, never touches placement or the user's undo.
    nonisolated static func markSeen(_ ids: [String], in note: Note) throws {
        let snapshot = try note.snapshot()
        let marks = snapshot.images.filter { ids.contains($0.id) && !$0.tombstone && !$0.seenByCompanion }
        guard !marks.isEmpty else { return }
        let mutations = marks.map { ImageMutation.markSeen(imageId: $0.id, base: .operation(operationId: $0.head.operationId)) }
        _ = try note.submitAgent(command: Command(operationId: newId(), dependencies: marks.map(\.head.operationId), body: .imageMarks(mutations: mutations)))
    }
}

struct NoteImageCopy: Sendable { let id: String; let data: Data; let mime: String }

/// Which stored images are near a passage: live images whose anchor starts within about two paragraphs of it, nearest
/// first, at most four (the engine takes four attachments, the page capture included).
enum NoteImagesNear {
    static func ids(_ snapshot: NoteSnapshot, text: String, passage: ByteSpan?, limit: Int = 3) -> [String] {
        let anchors = Dictionary(uniqueKeysWithValues: snapshot.companionItems.compactMap { item -> (String, Int)? in
            if case .anchor(.text(_, let start, _, let detached)) = item.body, !detached, !item.tombstone { return (item.id, Int(start)) }; return nil
        })
        let centre = passage.map { ($0.start + $0.end) / 2 } ?? text.utf8.count
        return snapshot.images.compactMap { image -> (String, Int)? in
            guard !image.tombstone, case .text(let anchor, _, _) = image.placement, let at = anchors[anchor] else { return nil }
            return (image.id, abs(at - centre))
        }.sorted { $0.1 < $1.1 }.prefix(limit).map(\.0)
    }
}
