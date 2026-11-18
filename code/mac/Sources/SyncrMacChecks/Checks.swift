import AppKit
import Foundation
import Metal
import SyncrCore

struct CheckFailure: Error, CustomStringConvertible {
    let description: String
}
func check(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
    if try !condition() { throw CheckFailure(description: message) }
}
func expectError(_ action: () throws -> Void, _ message: String) throws {
    do { try action() } catch { return }
    throw CheckFailure(description: message)
}

@MainActor func importMigratesOnlyLocalCopy() throws {
    let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../../core/fixtures/canvas-v2").standardizedFileURL
    let sources = try FileManager.default.contentsOfDirectory(at: fixture, includingPropertiesForKeys: nil)
        .filter { $0.pathExtension == "syncr" }.sorted { $0.path < $1.path }
    try check(sources.count == 2, "fixture count")
    let original = try sources.map { try Data(contentsOf: $0) }
    let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: temp) }
    let model = LibraryModel(folder: temp)
    try check(model.error == nil, "open library")
    for source in sources {
        let added = try model.importCopy(from: source)
        try model.open(id: added.id)
        try check(model.drawing?.title == added.title, "imported title")
        let origins = Set(model.drawing?.strokes.map(\.originY) ?? [])
        let height: Float = added.notebook == "Fixtures" ? 3200 : 1000
        try check(origins.contains(height), "second sheet origin")
        try check(origins.contains(2 * height), "third sheet origin")
        try check(model.drawing?.lowestInk ?? 0 > 2 * height, "canvas extends through old sheets")
    }
    try check(model.notes.count == 2, "indexed notes")
    try check(try sources.map { try Data(contentsOf: $0) } == original, "sources unchanged")
    let root = temp
    let copies = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
    try check(copies.filter { $0.pathExtension == "syncr" }.count == 2, "two local copies")
    try check(try FileManager.default.contentsOfDirectory(at: root.appendingPathComponent(".backups"),
                                                           includingPropertiesForKeys: nil).count == 2, "migration backups")
    // The same copy again is merged into the note the Mac holds (link v1): every operation is a duplicate, nothing changes.
    let beforeAgain = model.notes.map { "\($0.id)@\($0.localSequence)" }
    _ = try model.importCopy(from: sources[0])
    try check(model.notes.map { "\($0.id)@\($0.localSequence)" } == beforeAgain, "importing the same copy again merges to no change")
    let bad = temp.appendingPathComponent("bad.syncr")
    try Data([0, 1, 2]).write(to: bad)
    try expectError({ _ = try model.importCopy(from: bad) }, "invalid import must fail")
    try check(model.notes.count == 2, "failed imports did not add notes")
    try check(try sources.map { try Data(contentsOf: $0) } == original, "sources unchanged after failed imports")
}

/// A note arriving over the link is imported without holding the main thread: the heavy step runs elsewhere
/// (slowed here to 0.4 s to make a stall visible), the main run loop keeps turning meanwhile, the library
/// changes only in the main-thread completion, and the failure and refusal paths still report.
@MainActor func asyncImportKeepsMainThreadFree() throws {
    let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../../core/fixtures/canvas-v2").standardizedFileURL
    let sources = try FileManager.default.contentsOfDirectory(at: fixture, includingPropertiesForKeys: nil)
        .filter { $0.pathExtension == "syncr" }.sorted { $0.path < $1.path }
    let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: temp); LibraryModel.prepareHook = nil }
    let model = LibraryModel(folder: temp)
    final class Seen: @unchecked Sendable { let lock = NSLock(); var onMain: Bool?; var done = false
        func set(_ v: Bool) { lock.lock(); onMain = v; lock.unlock() } }
    let seen = Seen()
    LibraryModel.prepareHook = { seen.set(Thread.isMainThread); Thread.sleep(forTimeInterval: 0.4) }
    var result: Result<NoteSummary, Error>?
    var completedOnMain = false
    model.importCopyAsync(from: sources[0]) { r in completedOnMain = Thread.isMainThread; result = r }
    try check(result == nil && model.notes.isEmpty, "the import does not finish inside the call")
    var ticks = 0, longest = 0.0, last = Date()
    let end = Date().addingTimeInterval(15)
    while result == nil {
        if Date() > end { throw CheckFailure(description: "async import never completed") }
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.005))
        let now = Date(); longest = max(longest, now.timeIntervalSince(last)); last = now; ticks += 1
        if model.notes.isEmpty == false && result == nil { throw CheckFailure(description: "library changed before the completion ran") }
    }
    try check(seen.onMain == false, "validation ran off the main thread")
    try check(completedOnMain, "completion runs on the main thread")
    // On battery the system coalesces 5 ms waits to ~30-45 ms, so ticks only shows the loop turned;
    // the longest gap is what proves the main thread was never held.
    try check(ticks > 8 && longest < 0.2, "main run loop stayed free during the import (ticks \(ticks), longest \(longest) s)")
    guard case .success(let added)? = result else { throw CheckFailure(description: "async import failed: \(String(describing: result))") }
    try check(model.notes.map(\.id) == [added.id], "note installed after completion")
    try check(!FileManager.default.fileExists(atPath: temp.path + "/.incoming-" ) && (try FileManager.default.contentsOfDirectory(atPath: temp.path)).allSatisfy { !$0.hasPrefix(".incoming-") }, "staging removed")
    try model.open(id: added.id)
    try check(model.drawing?.strokes.isEmpty == false, "installed note reads")
    // The same copy again is not newer; garbage is refused; both leave the library as it was.
    LibraryModel.prepareHook = nil
    for (name, source) in [("duplicate", sources[0]), ("garbage", temp.appendingPathComponent("bad.syncr"))] {
        if name == "garbage" { try Data([1, 2, 3]).write(to: source) }
        var r: Result<NoteSummary, Error>?
        model.importCopyAsync(from: source) { r = $0 }
        try pump(name) { r != nil }
        if case .success? = r { throw CheckFailure(description: "\(name) import must fail") }
        try check(model.notes.count == 1, "\(name) import changed nothing")
    }
}

@MainActor private func pump(_ what: String, until condition: () -> Bool) throws {
    let end = Date().addingTimeInterval(15)
    while !condition() {
        if Date() > end { throw CheckFailure(description: "timed out waiting for \(what)") }
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.002))
    }
}

func viewportAndOffscreenMeshPixels() throws {
    try check(canvasSize().width == 4800, "canvas width")
    try check(canvasSize().viewHeight == 3000, "canvas view height")
    var viewport = Viewport(left: 9000, top: 500, zoom: 2)
    viewport.clamp(pixels: CGSize(width: 480, height: 300), bottom: 400)
    try check(viewport.left == 2400 && viewport.top == 400, "viewport clamp")
    let original = Viewport(left: 500, top: 300, zoom: 2)
    let anchor = SIMD2<Float>(0.25, 0.75)
    let pixels = CGSize(width: 480, height: 300)
    let before = original.visibleSize(pixels)
    let enlarged = original.zoomed(to: 4, around: anchor, pixels: pixels)
    let after = enlarged.visibleSize(pixels)
    try check(abs((original.left + anchor.x * before.x) - (enlarged.left + anchor.x * after.x)) < 0.01 &&
              abs((original.top + anchor.y * before.y) - (enlarged.top + anchor.y * after.y)) < 0.01,
              "pinch keeps its anchor stationary")
    try check(original.zoomed(to: 0, around: anchor, pixels: pixels).zoom == 1 &&
              original.zoomed(to: 100, around: anchor, pixels: pixels).zoom == 8,
              "pinch respects zoom limits")
    guard let device = MTLCreateSystemDefaultDevice() else { throw CheckFailure(description: "Metal device unavailable") }
    let renderer = try MeshRenderer(device: device)
    let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm,
                                                         width: 480, height: 300, mipmapped: false)
    desc.usage = [.renderTarget, .shaderRead]
    desc.storageMode = .shared
    guard let texture = device.makeTexture(descriptor: desc) else { throw CheckFailure(description: "Metal texture unavailable") }
    let samples = try encodeSamples(channels: SampleChannels(x: [100], y: [100],
                             pressure: [0.5], elapsedMs: [0], tilt: nil, orientation: nil))
    let brush = Brush(family: .pen, size: .large, colour: .red)
    let mesh = try tessellateStroke(samples: samples, style: defaultStrokeStyle(brush: brush),
                                   transform: Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0))
    func pixel(_ x: Int, _ y: Int) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: 4)
        texture.getBytes(&bytes, bytesPerRow: 4, from: MTLRegionMake2D(x, y, 1, 1), mipmapLevel: 0)
        return bytes
    }
    let drawing = Drawing(title: "Pixel", strokes: [DrawStroke(mesh: mesh, originX: 0,
                       originY: 0, colour: .red, highlighter: false)], lowestInk: mesh.maxY)
    try renderer.render(drawing, viewport: Viewport(zoom: 8), dark: false, target: texture)
    try check(pixel(80, 80)[2] > pixel(80, 80)[0], "light stroke is red")
    try check(pixel(0, 0)[2] > pixel(80, 80)[2], "light paper differs from stroke")
    let shifted = Drawing(title: "Shifted", strokes: [DrawStroke(mesh: mesh, originX: 0,
                         originY: 3200, colour: .red, highlighter: false)], lowestInk: 3300)
    try renderer.render(shifted, viewport: Viewport(left: 0, top: 3200, zoom: 8), dark: true, target: texture)
    try check(pixel(80, 80)[2] > pixel(0, 0)[2], "shifted stroke on dark paper")
    try check(pixel(100, 100) == pixel(0, 0), "no unshifted stroke")
}

@main struct Checks {
    @MainActor static func main() {
        let isolatedSettings = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-checks-presentation-\(UUID().uuidString)")
        setenv("SYNCR_MAC_SETTINGS_FOLDER", isolatedSettings.path, 1)
        defer { try? FileManager.default.removeItem(at: isolatedSettings) }
        do {
            _ = NSApplication.shared
            if CommandLine.arguments.contains("--hotkeys") { try hotkeyChecks(); return }
            if CommandLine.arguments.contains("--settings") { try settingsChecks(); return }
            if CommandLine.arguments.contains("--companion-v2") { try companionV2Checks(); return }
            if CommandLine.arguments.contains("--round2") { try notebookRound2Checks(); return }
            if CommandLine.arguments.contains("--link") { try linkChecks(); print("Phone link checks: OK"); return }
            if CommandLine.arguments.contains("--pointer") { try pointerScrollChecks(); print("Pointer scroll checks: OK (phases, remainders, direction, idle end, event fields, wire)"); return }
            if CommandLine.arguments.contains("--editor") { try editorRuleChecks(); return }
            if CommandLine.arguments.contains("--ink") { try inkChecks(); return }
            try notebookLogicChecks()
            try markdownMappingChecks()
            try notebookCoreN1Checks()
            try notebookRunChecks()
            try notebookCoreN2N3Checks()
            try companionCoreChecks()
            try companionDrawingChecks()
            try pictureSurfaceChecks()
            try companionV2Checks()
            try notebookRound2Checks()
            try editorRuleChecks()
            try inkChecks()
            try importMigratesOnlyLocalCopy()
            try asyncImportKeepsMainThreadFree()
            try viewportAndOffscreenMeshPixels()
            try newerCopiesAndCancelledLoads()
            try rendererCoverageOverlapAndReuse()
            try notebookWorkspaceChecks()
            try hotkeyChecks()
            try settingsChecks()
            try switcherAndCommandChecks()
            try readOnlyAndSessionChecks()
            try liveInkChecks()
            try linkChecks()
            try pointerScrollChecks()
            try penGlyphChecks()
            print("Mac viewer checks: OK (migration, newer copies, cancelled loads, MSAA, overlap/wrap, GPU reuse, notebooks, switcher, read-only vs Mac notes, session, live ink, phone link, pen heads)")
        } catch {
            fputs("Mac viewer checks: FAILED: \(error)\n", stderr)
            exit(1)
        }
    }
}
