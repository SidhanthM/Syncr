import Foundation
import Metal
import SyncrCore

@MainActor func newerCopiesAndCancelledLoads() throws {
    let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: temp) }
    let fm = FileManager.default
    let source = try openLibrary(rootPath: temp.appendingPathComponent("source").path)
    let id = newId(), first = newId()
    _ = try source.createNote(request: CreateNoteRequest(operationId: first, noteId: id,
        firstSheetId: newId(), title: "Before", notebook: nil, kind: .note))
    let note = try source.openNote(noteId: id)
    let old = temp.appendingPathComponent("old.syncr"), newer = temp.appendingPathComponent("new.syncr")
    _ = try note.exportCopy(destinationPath: old.path)
    _ = try note.submit(command: Command(operationId: newId(), dependencies: [first], body: .renameNote(title: "After", base: .operation(operationId: first))))
    _ = try note.exportCopy(destinationPath: newer.path)
    try note.closeNote()
    let original = try Data(contentsOf: newer)
    let libraryFolder = temp.appendingPathComponent("Mac")
    let model = LibraryModel(folder: libraryFolder)
    let before = try model.importCopy(from: old)
    let after = try model.importCopy(from: newer)
    try check(after.localSequence > before.localSequence && model.notes.count == 1, "newer copy replaces existing ID")
    try model.open(id: id)
    try check(model.drawing?.title == "After", "replacement reopens newer content")
    // Link v1: a copy of a held note is merged, never replaced; an older or identical copy has nothing the Mac lacks.
    _ = try model.importCopy(from: old); _ = try model.importCopy(from: newer)
    try check(model.notes.first?.localSequence == after.localSequence && model.notes.first?.title == "After", "older and identical copies merge to no change")
    let destination = libraryFolder.appendingPathComponent("\(id).syncr")
    let committed = try Data(contentsOf: destination)
    let bad = temp.appendingPathComponent("corrupt.syncr")
    try Data([1, 2, 3]).write(to: bad)
    try expectError({ _ = try model.importCopy(from: bad) }, "corrupt replacement refused")
    try check(try Data(contentsOf: destination) == committed, "invalid input leaves destination byte-identical")
    try check(try Data(contentsOf: newer) == original, "incoming source byte-identical")
    // A copy of a held note is merged, so nothing is replaced and no recovery copy is needed (link v1).
    try check(!fm.fileExists(atPath: libraryFolder.appendingPathComponent(".replaced").path), "merging replaces nothing")

    let wal = URL(fileURLWithPath: newer.path + "-wal")
    try Data([1]).write(to: wal)
    try expectError({ _ = try model.importCopy(from: newer) }, "active SQLite file is not a standalone export")
    try fm.removeItem(at: wal)

    // Existing pre-R7 silo remains untouched and safely copied on first load.
    let legacyParent = temp.appendingPathComponent("legacy")
    let silo = legacyParent.appendingPathComponent("Imported Copies")
    try fm.createDirectory(at: silo, withIntermediateDirectories: true)
    let legacy = silo.appendingPathComponent("random-name.syncr")
    try fm.copyItem(at: old, to: legacy)
    let legacyBytes = try Data(contentsOf: legacy)
    let upgraded = LibraryModel(folder: legacyParent)
    try check(upgraded.error == nil && upgraded.notes.count == 1, "legacy silo copied to main library")
    try check(try Data(contentsOf: legacy) == legacyBytes, "legacy source untouched")

    var changes = 0
    model.onChange = { changes += 1 }
    model.openAsync(id: newId()) // superseded job must not publish its missing-note error
    model.openAsync(id: id)
    let deadline = Date().addingTimeInterval(5)
    while model.loading && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.01)) }
    try check(!model.loading && model.error == nil && model.drawing?.title == "After" && changes >= 3, "background load completes on main")
    model.openAsync(id: id)
    model.setFolder(temp.appendingPathComponent("empty"))
    RunLoop.current.run(until: Date().addingTimeInterval(0.1))
    try check(model.drawing == nil && model.notes.isEmpty && !model.loading, "cancelled folder load cannot publish stale drawing")
}

func rendererCoverageOverlapAndReuse() throws {
    guard let device = MTLCreateSystemDefaultDevice() else { throw CheckFailure(description: "Metal unavailable") }
    let renderer = try MeshRenderer(device: device)
    let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 480, height: 300, mipmapped: false)
    descriptor.usage = [.renderTarget, .shaderRead]; descriptor.storageMode = .shared
    let target = device.makeTexture(descriptor: descriptor)!
    let positions: [Float] = [10.2, 10.2, 110.2, 10.2, 10.2, 110.2]
    let indices: [UInt32] = [0, 1, 2, 0, 1, 2] // duplicate triangles: one stroke must blend only once
    let mesh = StrokeMesh(vertices: positions.withUnsafeBytes { Data($0) }, indices: indices.withUnsafeBytes { Data($0) },
        minX: 10.2, minY: 10.2, maxX: 110.2, maxY: 110.2, discardSelfOverlap: true, rendererVersion: meshRendererVersion())
    func stroke(_ x: Float = 0, highlighter: Bool = true) -> DrawStroke {
        DrawStroke(mesh: mesh, originX: x, originY: 0, colour: .red, highlighter: highlighter)
    }
    func pixel(_ x: Int, _ y: Int) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: 4)
        target.getBytes(&bytes, bytesPerRow: 4, from: MTLRegionMake2D(x, y, 1, 1), mipmapLevel: 0)
        return bytes
    }
    for dark in [false, true] {
        let single = Drawing(title: "single", strokes: [stroke()], lowestInk: 120)
        try renderer.render(single, viewport: Viewport(zoom: 8), dark: dark, target: target)
        let once = pixel(20, 20)
        let paper = NotePalette.rgba(ThemeTokens.page(dark))
        let ink = NotePalette.colour(.red, dark: dark, highlighter: true)
        let expected = (SIMD3(ink.z, ink.y, ink.x) * ink.w + SIMD3(paper.z, paper.y, paper.x) * (1 - ink.w)) * 255
        for channel in 0..<3 { try check(abs(Float(once[channel]) - expected[channel]) <= 2, "self-overlap blends once, \(dark)") }
        let double = Drawing(title: "two", strokes: [stroke(), stroke()], lowestInk: 120)
        try renderer.render(double, viewport: Viewport(zoom: 8), dark: dark, target: target)
        let twice = pixel(20, 20)
        try check(once != twice, "distinct highlighter strokes blend independently")
        let wrapped = Drawing(title: "wrap", strokes: [stroke()] + (0..<254).map { _ in stroke(200) } + [stroke()], lowestInk: 120)
        try renderer.render(wrapped, viewport: Viewport(zoom: 8), dark: dark, target: target)
        try check(pixel(20, 20) == twice && renderer.lastStencilResetCount == 1 && renderer.lastPassCount == 1,
                  "stencil wrap clears in the same pass without altering colour")
        let count = renderer.bufferBuildCount, attachments = renderer.attachmentBuildCount
        try renderer.render(wrapped, viewport: Viewport(zoom: 8), dark: dark, target: target)
        try check(renderer.bufferBuildCount == count && renderer.attachmentBuildCount == attachments, "GPU buffers and attachments reused")
    }
    let opaque = Drawing(title: "edges", strokes: [stroke(highlighter: false)], lowestInk: 120)
    try renderer.render(opaque, viewport: Viewport(zoom: 8), dark: false, target: target)
    let inside = pixel(20, 20), outside = pixel(0, 0)
    var fractional = false
    for y in 5..<91 { for x in 5..<91 {
        let p = pixel(x, y)
        if p[0] > min(inside[0], outside[0]) + 2 && p[0] < max(inside[0], outside[0]) - 2 { fractional = true }
    } }
    try check(fractional, "4x MSAA has fractional edge coverage")
    let biggerDesc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 960, height: 600, mipmapped: false)
    biggerDesc.usage = [.renderTarget, .shaderRead]
    let bigger = device.makeTexture(descriptor: biggerDesc)!
    let previous = renderer.attachmentBuildCount
    try renderer.render(opaque, viewport: Viewport(), dark: false, target: bigger)
    try check(renderer.attachmentBuildCount == previous + 1, "attachments rebuilt exactly on resize")
}
