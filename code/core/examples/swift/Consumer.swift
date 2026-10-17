import Foundation

/// Binding consumer for the Mac, mirroring examples/kotlin/Consumer.kt.
/// Executed by scripts/smoke-swift-host.sh against the real SQLite-backed Rust implementation.
/// Blocking calls belong off the main thread in an app.

struct SmokeFailure: Error, CustomStringConvertible { let description: String }

func check(_ condition: @autoclosure () throws -> Bool, _ what: String, line: Int = #line) throws {
    if try !condition() { throw SmokeFailure(description: "line \(line): \(what)") }
}

func createAddSnapshotReopen(root: String) throws {
    let library = try openLibrary(rootPath: root)
    let request = CreateNoteRequest(
        operationId: newId(), noteId: newId(), firstSheetId: newId(),
        title: "Example", notebook: nil)
    _ = try library.createNote(request: request)
    if case .committed = try library.lookupCreation(operationId: request.operationId) {} else {
        throw SmokeFailure(description: "creation was not recorded")
    }
    try check(try library.createNote(request: request).duplicate, "repeated creation is a duplicate")
    try check(try library.listNotes().map(\.id) == [request.noteId], "library lists the note")

    let markId = newId()
    let addId = newId()
    do {
        let note = try library.openNote(noteId: request.noteId)
        let initial = try note.snapshot()
        try check(initial.sheets.map(\.creationOperationId) == [request.operationId], "sheet provenance")
        try check(initial.sheets.map(\.originX) == [0] && initial.sheets.map(\.originY) == [0], "one sheet at the origin")
        do {
            _ = try note.submit(command: Command(
                operationId: newId(), dependencies: [request.operationId],
                body: .addSheet(sheetId: newId(), base: .operation(operationId: request.operationId))))
            throw SmokeFailure(description: "a sheet was added to a canvas note")
        } catch CoreError.InvalidInput(_, let retryable) {
            try check(!retryable, "rejected sheet command is not retryable")
        }
        let samples = try encodeSamples(channels: SampleChannels(
            x: [10, 20], y: [30, 40], pressure: [0.5, 1], elapsedMs: [0, 10], tilt: nil, orientation: nil))
        let add = Command(
            operationId: addId, dependencies: [request.operationId],
            body: .marks(mutations: [.addStroke(
                markId: markId, sheetId: request.firstSheetId, samples: samples,
                brush: Brush(family: .pen, size: .medium, colour: .ink))]))
        let receipt = try note.submit(command: add)
        try check(!receipt.duplicate, "first submit is not a duplicate")
        try check(try note.submit(command: add).duplicate, "retry with the same ID is a duplicate")
        do {
            _ = try note.submit(command: Command(
                operationId: addId, dependencies: [request.operationId],
                body: .renameNote(title: "Collision", base: .absent)))
            throw SmokeFailure(description: "different payload under the same ID was accepted")
        } catch CoreError.IdCollision(_, let retryable) {
            try check(!retryable, "ID collision is not retryable")
        }
        guard case .events(let items, _, _) = try note.changesSince(cursor: initial.cursor, limit: 256) else {
            throw SmokeFailure(description: "expected events, got resync")
        }
        try check(items.map(\.operationId) == [addId], "one change event for the add")
        try check(try decodeSamples(blob: try note.getSamples(markId: markId)).x == [10, 20], "samples round-trip")

        let erase = Command(
            operationId: newId(), dependencies: [addId],
            body: .marks(mutations: [.deleteStroke(markId: markId, base: .operation(operationId: addId))]))
        _ = try note.submit(command: erase)
        let erased = try note.snapshot()
        try check(erased.strokes.map(\.tombstone) == [true], "erase leaves a tombstone")
        guard let undoTarget = erased.undoTarget else { throw SmokeFailure(description: "no undo target") }
        _ = try note.submit(command: Command(
            operationId: newId(), dependencies: erased.undoDependencies, body: .undo(targetOperationId: undoTarget)))
        try check(try note.snapshot().strokes.map(\.tombstone) == [false], "undo restores the stroke")
        try note.closeNote()
    }

    // Reopen through a fresh handle: durable state, not memory.
    let reopened = try library.openNote(noteId: request.noteId)
    let snapshot = try reopened.snapshot()
    try check(snapshot.strokes.map(\.id) == [markId], "stroke survives reopen")
    try check(snapshot.strokes.map(\.tombstone) == [false], "undo survives reopen")
    try reopened.closeNote()
}

/// Canvas amendment: the canvas size, and a real v2 multi-sheet note written by the pre-canvas
/// core upgrading on open (after a backup) to fixed sheet origins without gaps.
func canvasUpgrade() throws {
    try check(canvasSize() == CanvasSize(width: 4800, viewHeight: 3000, phoneColumn: 1600), "canvas size")
    let fm = FileManager.default
    let root = fm.temporaryDirectory.appendingPathComponent("syncr-swift-canvas-\(UUID().uuidString)")
    defer { try? fm.removeItem(at: root) }
    try fm.createDirectory(at: root, withIntermediateDirectories: true)
    let fixtures = URL(fileURLWithPath: "fixtures/canvas-v2")
    for name in try fm.contentsOfDirectory(atPath: fixtures.path)
    where name.hasSuffix(".syncr") || name == "library.sqlite3" {
        try fm.copyItem(at: fixtures.appendingPathComponent(name), to: root.appendingPathComponent(name))
    }
    let library = try openLibrary(rootPath: root.path)
    let notes = try library.listNotes()
    try check(notes.count == 2, "both fixture notes listed")
    for summary in notes {
        let note = try library.openNote(noteId: summary.id)
        let s = try note.snapshot()
        let height: Float = summary.notebook == "Fixtures" ? 3200 : 1000
        try check(s.sheets.map(\.originX) == [0, 0, 0], "sheets start at the left edge")
        try check(s.sheets.map(\.originY) == [0, height, 2 * height], "pages stacked without gaps")
        try note.closeNote()
    }
    let backups = try fm.contentsOfDirectory(atPath: root.appendingPathComponent(".backups").path)
    try check(backups.count == 2 && backups.allSatisfy { $0.contains(".v2.") }, "one backup per upgraded note")
}

func littleEndianWords(_ data: Data) -> [UInt32] {
    let bytes = [UInt8](data)
    return stride(from: 0, to: bytes.count, by: 4).map { i in
        UInt32(bytes[i]) | UInt32(bytes[i + 1]) << 8 | UInt32(bytes[i + 2]) << 16 | UInt32(bytes[i + 3]) << 24
    }
}

/// Stroke mesh for the Metal renderer: default styles, triangle layout, bounds and errors.
func strokeMesh() throws {
    let style = defaultStrokeStyle(brush: Brush(family: .pen, size: .medium, colour: .ink))
    try check(style.size == 4 && style.pressureHigh > style.pressureLow && !style.discardSelfOverlap, "default pen style")
    let samples = try encodeSamples(channels: SampleChannels(
        x: [10, 30, 60], y: [10, 12, 20], pressure: [0.3, 0.5, 0.4], elapsedMs: [0, 40, 90], tilt: nil, orientation: nil))
    let identity = Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0)
    let mesh = try tessellateStroke(samples: samples, style: style, transform: identity)
    try check(mesh.rendererVersion == meshRendererVersion(), "renderer version")
    try check(mesh.vertices.count > 0 && mesh.vertices.count % 8 == 0, "vertices are f32 pairs")
    try check(mesh.indices.count > 0 && mesh.indices.count % 12 == 0, "indices are u32 triangles")
    let vertexCount = UInt32(mesh.vertices.count / 8)
    try check(littleEndianWords(mesh.indices).allSatisfy { $0 < vertexCount }, "indices in range")
    let coords = littleEndianWords(mesh.vertices).map { Float(bitPattern: $0) }
    try check(coords.allSatisfy(\.isFinite), "finite vertices")
    try check(mesh.minX < 10 && mesh.maxX > 60 && mesh.minY < 10 && mesh.maxY > 20, "bounds cover the stroke")
    try check(try tessellateStroke(samples: samples, style: style, transform: identity) == mesh, "deterministic")

    let tap = try tessellateStroke(samples: try encodeSamples(channels: SampleChannels(
        x: [100], y: [100], pressure: [0.3], elapsedMs: [0], tilt: nil, orientation: nil)), style: style, transform: identity)
    try check(tap.maxX - tap.minX > 2 && tap.maxX - tap.minX < 6, "tap is a dot")

    let highlighter = defaultStrokeStyle(brush: Brush(family: .highlighter, size: .medium, colour: .green))
    try check(try tessellateStroke(samples: samples, style: highlighter, transform: identity).discardSelfOverlap,
              "highlighter discards self overlap")
    do {
        _ = try tessellateStroke(samples: Data([1, 2, 3]), style: style, transform: identity)
        throw SmokeFailure(description: "invalid samples were accepted")
    } catch CoreError.InvalidInput(_, let retryable) {
        try check(!retryable, "invalid input is not retryable")
    }
}

@main
struct Smoke {
    static func main() {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-swift-smoke-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        do {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try createAddSnapshotReopen(root: root.path)
            try canvasUpgrade()
            try strokeMesh()
            print("Swift host smoke: OK")
        } catch {
            print("Swift host smoke: FAILED: \(error)")
            exit(1)
        }
    }
}
