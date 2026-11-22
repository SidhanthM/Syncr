import Foundation
import SyncrCore

/// Look-and-ink v1: the shared OKLCH vectors (`code/shared/colour/oklch-v1.json`) through the Swift binding, the Mac's
/// colour for a custom ink, and strokes keeping the look they were drawn with.
@MainActor func inkChecks() throws {
    let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../shared/colour/oklch-v1.json").standardizedFileURL
    let root = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
    func ink(_ v: [String: Any]) -> Oklch {
        Oklch(l: Float((v["l"] as! NSNumber).doubleValue), c: Float((v["c"] as! NSNumber).doubleValue), h: Float((v["h"] as! NSNumber).doubleValue))
    }
    let convert = root["convert"] as! [[String: Any]]
    try check(convert.count >= 20, "oklch vectors present")
    for v in convert {
        let want = UInt32(v["srgb"] as! String, radix: 16)!
        let got = oklchToSrgb(ink: ink(v))
        for shift in [UInt32(16), 8, 0] {
            let delta = abs(Int((got >> shift) & 255) - Int((want >> shift) & 255))
            try check(delta <= 1, "oklch \(v) -> \(String(got, radix: 16))")
        }
        let mapped = oklchGamutMap(ink: ink(v)).c
        try check(abs(Double(mapped) - (v["mapped_c"] as! NSNumber).doubleValue) < 2e-4, "mapped chroma \(v)")
    }
    for v in root["theme"] as! [[String: Any]] {
        try check(oklchUsesThemeInk(ink: ink(v)) == (v["theme_ink"] as! Bool), "theme rule \(v)")
    }

    // The Mac draws a custom ink as chosen in both themes; near-black and near-white greys take the window's ink.
    let teal = Oklch(l: 0.7, c: 0.12, h: 200)
    for dark in [false, true] {
        let want = NotePalette.rgba(oklchToSrgb(ink: teal))
        try check(MeshRenderer.colour(.ink, custom: teal, dark: dark, highlighter: false) == want, "custom ink as chosen (dark \(dark))")
        let grey = Oklch(l: 0.15, c: 0, h: 0)
        try check(MeshRenderer.colour(.ink, custom: grey, dark: dark, highlighter: false) == MeshRenderer.colour(.ink, dark: dark, highlighter: false),
                  "near-black takes the window ink (dark \(dark))")
        let marker = MeshRenderer.colour(.ink, custom: teal, dark: dark, highlighter: true)
        try check(marker.w == Float(NotePalette.highlighterAlpha) / 255 && marker.x == want.x, "highlighter keeps the custom ink at marker alpha")
    }
    try check(MeshRenderer.colour(.red, dark: false, highlighter: false) == NotePalette.colour(.red, dark: false, highlighter: false), "named inks unchanged")

    // Strokes keep their look: a stroke written in another pen tessellates differently, never changes, and the stored
    // brush (look, custom ink, feel) survives the note.
    let temp = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-ink-checks-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: temp) }
    let library = try openLibrary(rootPath: temp.path)
    let request = CreateNoteRequest(operationId: newId(), noteId: newId(), firstSheetId: newId(), title: "Ink", notebook: nil, kind: .note)
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
    let ballpoint = try add(Brush(family: .pen, size: .medium, colour: .ink, look: .ballpoint, customInk: teal, feel: PenFeel(pressure: 50, smoothing: 50, taper: true)))
    _ = try add(Brush(family: .pen, size: .medium, colour: .ink, look: .fineliner))
    try check(try mesh(plain) == before, "switching pens leaves an existing stroke's mesh byte-identical")
    try check(try mesh(ballpoint) != before, "a ballpoint stroke draws differently from a fountain stroke")
    let stored = try note.snapshot().strokes.first { $0.id == ballpoint }!.brush
    try check(stored.look == .ballpoint && stored.customInk == teal && stored.feel?.smoothing == 50, "look, ink and feel are stored with the stroke")
    try check(defaultStrokeStyle(brush: Brush(family: .pen, size: .medium, colour: .ink)).smoothingWindowMs == 20,
              "strokes without a look or feel keep the Mac's historical style")
    // Phone to Mac: the stroke's wire form carries the look.
    let commit = StrokeCommit(noteId: request.noteId, sheetId: request.firstSheetId, strokeId: newId(), brush: stored, space: .canvas, samples: samples)
    let reassembler = StrokeReassembler()
    var arrived: StrokeCommit?
    for frame in try encodeStrokeCommit(commit: commit) { arrived = try reassembler.accept(frame: frame, nowMs: 1) ?? arrived }
    try check(arrived?.brush == stored, "the commit wire carries look, ink and feel")
    print("Ink checks: OK (oklch vectors, theme rule, custom ink colour, strokes keep their look)")
}
