import AppKit
import CryptoKit
import SyncrCore
import SyncrCompanion

@MainActor private func v2Wait(_ done: () -> Bool) throws {
    let end = Date().addingTimeInterval(15)
    while !done() {
        guard Date() < end else { throw CheckFailure(description: "v2 worker timed out") }
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.002))
    }
}
@MainActor private func v2Edit(_ model: LibraryModel, _ id: String, _ work: @escaping @Sendable (Note) throws -> Void) throws -> NoteSnapshot {
    var result: Result<NoteSnapshot, Error>?
    model.editNote(id: id, work: work) { result = $0 }
    try v2Wait { result != nil }; return try result!.get()
}

private let pictureSVG = """
<svg viewBox="0 0 320 120"><defs><marker id="tip" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto"><path d="M0 0 L6 3 L0 6" stroke="var(--ink)" fill="none"/></marker></defs>
<text x="16" y="24" font-size="16" font-family="var(--font-voice)" fill="var(--ink)">Two steps</text>
<g data-step="1" data-caption="A line with an arrow."><line x1="30" y1="70" x2="250" y2="70" stroke="var(--voice)" stroke-width="1.5" marker-end="url(#tip)"/><text x="150" y="60" font-size="12" font-family="var(--font-sans)" fill="var(--muted)" transform="rotate(-8 150 60)">slope</text></g>
<g data-step="2" data-caption="A box." data-hide="1"><rect x="40" y="50" width="120" height="40" stroke="var(--ink)" fill="none"/></g></svg>
"""

/// Round 2 of mac-companion-2: adapter fields, renderer determinism and metrics, picture storage, image storage and "seen".
@MainActor func companionV2Checks() throws {
    // Adapter: every core field the engine's check needs is copied; a marker without viewBox no longer panics the core.
    let inspector = CorePictureInspector()
    let clean = try inspector.filter(svg: pictureSVG)
    try check(clean.contains("<marker"), "marker without viewBox no longer panics the core")
    let list = try inspector.displayList(svg: clean)
    let items = list.steps.flatMap(\.items)
    let text = items.first { $0.text == "slope" }, rect = items.first { $0.closed == true }
    try check(text?.bounds != nil && text?.font == "font-sans" && text?.baseline == "alphabetic", "text keeps bounds, font, baseline")
    try check((text?.transform?.b ?? 0) != 0 && (text?.transform?.a ?? 1) != 0, "rotated text keeps its linear transform")
    try check(rect?.strokeWidth != nil && rect?.bounds != nil && rect?.closed == true, "polyline keeps stroke width, bounds, closed")
    try check(list.steps.count == 2 && list.steps[1].hide == [1], "hide survives")

    // Renderer: deterministic hand-drawn pen, different from the clean pen; baselines from real metrics.
    let picture = try CorePictureInspector.mac(svg: clean)
    func png(_ hand: Bool) -> Data { PictureRenderer.image(picture, through: 1, handDrawn: hand)!.representation(using: .png, properties: [:])! }
    try check(png(true) == png(true), "hand-drawn pen is deterministic")
    try check(png(true) != png(false), "hand-drawn pen differs from clean")
    let size = 16.0
    func shift(_ b: String) -> CGFloat { PictureRenderer.layout("Hg", token: "font-sans", size: size, anchor: "start", baseline: b).shift }
    try check(shift("text-before-edge") > shift("hanging") && shift("hanging") > shift("middle") && shift("middle") > shift("alphabetic") && shift("alphabetic") == shift("auto") && shift("text-after-edge") < 0, "all seven baselines use the font's metrics in order")
    let one = PictureRenderer.strokes([.init(x: 0, y: 0), .init(x: 100, y: 0)], closed: false, width: 1, salt: 3, hand: true)
    try check(one.faint.count == 1 && one.main[0].first!.x < 0 && one.main[0].last!.x > 100, "straight line overshoots and gets a faint second pass")
    try check(one.faint[0].allSatisfy { abs($0.y) < 0.6 + 0.5 }, "second pass stays within a wobble of the line")
    // A finished step draws its last item whole. From the second trial (prompt 15): every board ends in a fill-only
    // queen, and the running length subtraction left some a hair short, so their fill was skipped and boards lagged
    // their captions by one queen.
    let queens = try CorePictureInspector.mac(svg: try inspector.filter(svg: queensSVG))
    let bitmap = PictureRenderer.image(queens, through: 6, width: 640, handDrawn: false)!
    let fit = PictureRenderer.bounds(queens), scale = 640 / fit.width
    // Queen centres in canvas units: (1,2) on boards 1, 4 and 5; (2,4) on 4; (3,1) on 5; (4,3) on 6.
    for (x, y) in [(74.0, 100.0), (218, 252), (274, 280), (74, 404), (46, 460), (246, 488)] {
        let colour = bitmap.colorAt(x: Int((x - fit.minX) * scale), y: Int((y - fit.minY) * scale))!
        try check(colour.brightnessComponent > 0.5, "queen at (\(x), \(y)) is drawn when its step is finished")
    }
    let box = PictureRenderer.strokes([.init(x: 0, y: 0), .init(x: 60, y: 0), .init(x: 60, y: 40), .init(x: 0, y: 40)], closed: true, width: 1, salt: 4, hand: true)
    try check(box.main.count == 4, "box edges are four separate strokes")

    // Storage: picture + embed line, image bytes immutable, seen monotone, relaunch keeps both.
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-v2-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: dir) }
    let original = "A passage to explain.\nSecond line.\n"
    var id = "", imageID = "", seenHead = ""
    var sourceBytes = Data()
    do {
        let model = LibraryModel(folder: dir)
        id = try model.createNote(notebook: nil)
        _ = try v2Edit(model, id) { note in
            let sheet = try note.snapshot().sheets[0]
            _ = try note.submit(command: Command(operationId: newId(), dependencies: [sheet.creationOperationId], body: .marks(mutations: [.addText(markId: newId(), sheetId: sheet.id, role: .document, originX: 0, originY: 0, width: 1600, text: original, style: TextStyle(size: .medium, colour: .ink))])))
        }
        var s = try v2Edit(model, id) { note in
            try CompanionStore.apply([], note: note, reader: "Editor", expectedText: original, drawings: false, pictures: [CompanionStore.PictureAddition(quote: "A passage to explain.", title: "Two steps", svg: clean)])
        }
        let pictures = s.companionItems.filter { if case .picture = $0.body { return true }; return false }
        try check(pictures.count == 1, "picture stored as CompanionBody.picture")
        if case .picture(_, _, let stored) = pictures[0].body { try check(stored == clean, "stored SVG is exactly the filtered source") }
        let block = s.textBlocks.first { $0.role == .document }!
        try check(block.text.contains("![[syncr-drawing:\(pictures[0].id)]]") && block.text.hasPrefix("A passage to explain.\n![[syncr-drawing:"), "embed line under the passage; user's words untouched")
        try check(block.text.replacingOccurrences(of: "![[syncr-drawing:\(pictures[0].id)]]\n", with: "").replacingOccurrences(of: "\n\n", with: "\n") == original || block.text.replacingOccurrences(of: "\n![[syncr-drawing:\(pictures[0].id)]]\n", with: "\n") == original, "removing the embed line restores the note exactly")

        let canvas = NSImage(size: CGSize(width: 3000, height: 900), flipped: false) { rect in NSColor.systemTeal.setFill(); rect.fill(); return true }
        let source = NSBitmapImageRep(data: canvas.tiffRepresentation!)!.representation(using: .png, properties: [:])!
        let prepared = try NoteImages.prepare(source)
        sourceBytes = prepared.data
        s = try v2Edit(model, id) { note in try NoteImageStore.place(prepared, atUTF8: 5, in: note) }
        let marks = s.images.filter { !$0.tombstone }
        try check(marks.count == 1 && marks[0].pixelWidth == 2400 && !marks[0].seenByCompanion, "pasted image stored through the core, unseen")
        if case .text = marks[0].placement {} else { throw CheckFailure(description: "image has a text placement") }
        imageID = marks[0].id; seenHead = marks[0].head.operationId
        let target = imageID
        s = try v2Edit(model, id) { note in try NoteImageStore.markSeen([target], in: note) }
        let seen = s.images.first { $0.id == imageID }!
        try check(seen.seenByCompanion && seen.head.operationId == seenHead, "MarkSeen sets seen without moving the placement head")
        _ = try v2Edit(model, id) { note in try NoteImageStore.markSeen([target], in: note) }
    }
    do {
        let model = LibraryModel(folder: dir)
        try model.open(id: id)
        let box = WorkerBox<Data>(Data()), wanted = imageID
        let s = try v2Edit(model, id) { note in box.set(try note.getImageBytes(imageId: wanted)) }
        let bytes = box.get()
        try check(s.images.first?.seenByCompanion == true && s.images.first?.id == imageID, "image and seen mark survive relaunch")
        try check(Data(SHA256.hash(data: bytes)) == Data(SHA256.hash(data: sourceBytes)), "stored image bytes unchanged after seen and reopen")
    }
    // The engine's request carries the scaled copy, not the stored original.
    let copy = try NoteImages.prepare(sourceBytes, longSide: 1280)
    try check(copy.width == 1280 && copy.data != sourceBytes, "provider copy is scaled, original untouched")
    print("Companion v2 checks: OK (adapter fields, marker without viewBox, deterministic hand-drawn pen, baselines, picture + embed, image bytes immutable, seen monotone, relaunch)")
}

private let queensSVG = "<svg viewBox=\"0 0 320 530\"><defs><g fill=\"none\" id=\"board\" stroke=\"var(--soft)\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-width=\"1\"><rect height=\"112\" width=\"112\" x=\"0\" y=\"0\"/><path d=\"M 28 0 V 112 M 56 0 V 112 M 84 0 V 112 M 0 28 H 112 M 0 56 H 112 M 0 84 H 112\" stroke=\"var(--hairline)\"/></g><g id=\"q\"><circle cx=\"14\" cy=\"14\" fill=\"var(--ink)\" r=\"7\"/></g></defs><text fill=\"var(--ink)\" font-family=\"var(--font-voice)\" font-size=\"16\" x=\"18\" y=\"28\">Try, undo, complete</text><circle cx=\"24\" cy=\"47\" fill=\"var(--ink)\" r=\"5\"/><text fill=\"var(--ink)\" font-family=\"var(--font-sans)\" font-size=\"12\" x=\"36\" y=\"51\">Queen; rows down, columns right</text><g data-caption=\"Place: queens at (1,2).\" data-step=\"1\"><text fill=\"var(--ink)\" font-family=\"var(--font-sans)\" font-size=\"12\" x=\"32\" y=\"76\">1. Place</text><g transform=\"translate(32 86)\"><use href=\"#board\"/><use href=\"#q\" x=\"28\"/></g></g><g data-caption=\"Failed trial: queens at (1,2), (2,2); they share column 2.\" data-step=\"2\"><text fill=\"var(--voice)\" font-family=\"var(--font-sans)\" font-size=\"12\" x=\"176\" y=\"76\">2. Conflict</text><g transform=\"translate(176 86)\"><use href=\"#board\"/><use href=\"#q\" x=\"28\"/><use href=\"#q\" x=\"28\" y=\"28\"/></g></g><g data-caption=\"Undo (2,2): queens at (1,2) only.\" data-step=\"3\"><text fill=\"var(--voice)\" font-family=\"var(--font-sans)\" font-size=\"12\" x=\"32\" y=\"228\">3. Undo trial</text><g transform=\"translate(32 238)\"><use href=\"#board\"/><use href=\"#q\" x=\"28\"/></g></g><g data-caption=\"Try another column: queens at (1,2), (2,4).\" data-step=\"4\"><text fill=\"var(--ink)\" font-family=\"var(--font-sans)\" font-size=\"12\" x=\"176\" y=\"228\">4. Try column 4</text><g transform=\"translate(176 238)\"><use href=\"#board\"/><use href=\"#q\" x=\"28\"/><use href=\"#q\" x=\"84\" y=\"28\"/></g></g><g data-caption=\"Extend: queens at (1,2), (2,4), (3,1).\" data-step=\"5\"><text fill=\"var(--ink)\" font-family=\"var(--font-sans)\" font-size=\"12\" x=\"32\" y=\"380\">5. Next row</text><g transform=\"translate(32 390)\"><use href=\"#board\"/><use href=\"#q\" x=\"28\"/><use href=\"#q\" x=\"84\" y=\"28\"/><use href=\"#q\" y=\"56\"/></g></g><g data-caption=\"Solution: queens at (1,2), (2,4), (3,1), (4,3).\" data-step=\"6\"><text fill=\"var(--voice)\" font-family=\"var(--font-sans)\" font-size=\"12\" x=\"176\" y=\"380\">6. Complete</text><g transform=\"translate(176 390)\"><use href=\"#board\"/><use href=\"#q\" x=\"28\"/><use href=\"#q\" x=\"84\" y=\"28\"/><use href=\"#q\" y=\"56\"/><use href=\"#q\" x=\"56\" y=\"84\"/></g></g></svg>"
