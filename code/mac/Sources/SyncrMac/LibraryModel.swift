import Foundation
import SyncrCore

struct DrawStroke {
    let mesh: StrokeMesh
    let originX: Float
    let originY: Float
    let colour: InkColour
    let highlighter: Bool
}

struct Drawing {
    let title: String
    let strokes: [DrawStroke]
    let lowestInk: Float
}

/// All core handles and migration writes are confined to the app's private copy directory.
final class LibraryModel {
    private(set) var notes: [NoteSummary] = []
    private(set) var drawing: Drawing?
    var error: String?
    var folder: URL
    private var library: SyncrLibrary?

    init(folder: URL? = nil) {
        let parent = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Documents/Syncr", isDirectory: true)
        self.folder = folder ?? ProcessInfo.processInfo.environment["SYNCR_MAC_LIBRARY_FOLDER"].map {
            URL(fileURLWithPath: $0, isDirectory: true)
        } ?? parent
        do { try loadLibrary() } catch { self.error = String(describing: error) }
    }

    private var root: URL { folder.appendingPathComponent("Imported Copies", isDirectory: true) }

    func setFolder(_ url: URL) {
        library = nil
        drawing = nil
        folder = url
        do { try loadLibrary() } catch { self.error = String(describing: error) }
    }

    private func loadLibrary() throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        library = try openLibrary(rootPath: root.path)
        try refresh()
    }

    func refresh() throws {
        notes = try (library?.listNotes() ?? []).filter { !$0.archived }
        error = nil
    }

    /// Source is only read. Copy to a hidden temporary path before publication; core never
    /// receives a path into the source's directory. A failed validation removes the copy.
    @discardableResult
    func importCopy(from source: URL) throws -> NoteSummary {
        guard let library else { throw ViewerError.unavailable }
        let fm = FileManager.default
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true && values.isSymbolicLink != true,
              source.pathExtension.lowercased() == "syncr" else { throw ViewerError.invalidSource }
        let before = try library.listNotes()
        let temporary = root.appendingPathComponent(".incoming-\(UUID().uuidString)")
        let published = root.appendingPathComponent("\(UUID().uuidString).syncr")
        try fm.copyItem(at: source, to: temporary)
        defer { try? fm.removeItem(at: temporary) }
        do {
            try fm.moveItem(at: temporary, to: published)
            let after = try library.listNotes()
            guard after.count == before.count + 1,
                  let added = after.first(where: { entry in !before.contains(where: { $0.id == entry.id }) }) else {
                throw ViewerError.invalidSource
            }
            // Upgrades, if needed, are applied only to this imported copy.
            try open(id: added.id)
            try refresh()
            return added
        } catch {
            try? fm.removeItem(at: published)
            throw error
        }
    }

    func open(id: String) throws {
        guard let library else { throw ViewerError.unavailable }
        let note = try library.openNote(noteId: id)
        defer { try? note.closeNote() }
        let snapshot = try note.snapshot()
        let sheets = Dictionary(uniqueKeysWithValues: snapshot.sheets.map { ($0.id, $0) })
        var strokes: [DrawStroke] = []
        var bottom: Float = 0
        for mark in snapshot.strokes where !mark.tombstone {
            guard let sheet = sheets[mark.sheetId] else { throw ViewerError.invalidSource }
            let mesh = try tessellateStroke(samples: note.getSamples(markId: mark.id),
                                            style: defaultStrokeStyle(brush: mark.brush),
                                            transform: mark.transform)
            guard mesh.rendererVersion == meshRendererVersion() else { throw ViewerError.invalidSource }
            bottom = max(bottom, sheet.originY + mesh.maxY)
            strokes.append(DrawStroke(mesh: mesh, originX: sheet.originX, originY: sheet.originY,
                                      colour: mark.brush.colour, highlighter: mesh.discardSelfOverlap))
        }
        drawing = Drawing(title: snapshot.title, strokes: strokes, lowestInk: bottom)
        error = nil
    }
}

enum ViewerError: Error, LocalizedError {
    case unavailable, invalidSource
    var errorDescription: String? {
        switch self {
        case .unavailable: "Library is unavailable"
        case .invalidSource: "Not a distinct, readable Syncr note export"
        }
    }
}
