import Foundation
import SyncrCore

/// Look-and-ink v1 part 1: strokes keep the look they were drawn with.
@MainActor func inkChecks() throws {
    // Strokes keep their look: a stroke written in another pen tessellates differently, never changes, and the stored
    // brush (look, custom ink, feel) survives the note.
    let temp = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-ink-checks-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: temp) }
    let library = try openLibrary(rootPath: temp.path)
    let request = CreateNoteRequest(operationId: newId(), noteId: newId(), firstSheetId: newId(), title: "Ink", notebook: nil)
    _ = try library.createNote(request: request)
    let note = try library.openNote(noteId: request.noteId)
    let n = 40
    let samples = try encodeSamples(channels: SampleChannels(
        x: (0..<n).map { 100 + Float($0) * 9 }, y: (0..<n).map { 300 + sin(Float($0) * 0.25) * 60 },
        pressure: (0..<n).map { 0.2 + 0.6 * Float($0) / Float(n) }, elapsedMs: (0..<n).map { UInt32($0) * 8 }, tilt: nil, orientation: nil))
    func add(_ brush: Brush) throws -> String {
        let id = newId()
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [request.operationId],
            body: .marks(mutations: [.addStroke(markId: id, sheetId: request.firstSheetId, samples: samples, brush: brush)])))
        return id
    }
    let identity = Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0)
    func mesh(_ id: String) throws -> StrokeMesh {
        let stroke = try note.snapshot().strokes.first { $0.id == id }!
        return try tessellateStroke(samples: note.getSamples(markId: id), style: defaultStrokeStyle(brush: stroke.brush), transform: identity)
    }
    let plain = try add(Brush(family: .pen, size: .medium, colour: .ink))
    let before = try mesh(plain)
    let ballpoint = try add(Brush(family: .pen, size: .medium, colour: .ink, look: .ballpoint, feel: PenFeel(pressure: 50, smoothing: 50, taper: true)))
    _ = try add(Brush(family: .pen, size: .medium, colour: .ink, look: .fineliner))
    try check(try mesh(plain) == before, "switching pens leaves an existing stroke's mesh byte-identical")
    try check(try mesh(ballpoint) != before, "a ballpoint stroke draws differently from a fountain stroke")
    let stored = try note.snapshot().strokes.first { $0.id == ballpoint }!.brush
    try check(stored.look == .ballpoint && stored.feel?.smoothing == 50, "look and feel are stored with the stroke")
    try check(defaultStrokeStyle(brush: Brush(family: .pen, size: .medium, colour: .ink)).smoothingWindowMs == 20,
              "strokes without a look or feel keep the Mac's historical style")
    // Phone to Mac: the stroke's wire form carries the look.
    let commit = StrokeCommit(noteId: request.noteId, sheetId: request.firstSheetId, strokeId: newId(), brush: stored, space: .canvas, samples: samples)
    let reassembler = StrokeReassembler()
    var arrived: StrokeCommit?
    for frame in try encodeStrokeCommit(commit: commit) { arrived = try reassembler.accept(frame: frame, nowMs: 1) ?? arrived }
    try check(arrived?.brush == stored, "the commit wire carries look and feel")
    print("Ink checks: OK (strokes keep their look)")
}
