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

func importMigratesOnlyLocalCopy() throws {
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
        try check(model.drawing?.title == added.title, "imported title")
        let origins = Set(model.drawing?.strokes.map(\.originY) ?? [])
        let height: Float = added.notebook == "Fixtures" ? 3200 : 1000
        try check(origins.contains(height), "second sheet origin")
        try check(origins.contains(2 * height), "third sheet origin")
        try check(model.drawing?.lowestInk ?? 0 > 2 * height, "canvas extends through old sheets")
    }
    try check(model.notes.count == 2, "indexed notes")
    try check(try sources.map { try Data(contentsOf: $0) } == original, "sources unchanged")
    let root = temp.appendingPathComponent("Imported Copies")
    let copies = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
    try check(copies.filter { $0.pathExtension == "syncr" }.count == 2, "two local copies")
    try check(try FileManager.default.contentsOfDirectory(at: root.appendingPathComponent(".backups"),
                                                           includingPropertiesForKeys: nil).count == 2, "migration backups")
    try expectError({ _ = try model.importCopy(from: sources[0]) }, "duplicate must fail")
    let bad = temp.appendingPathComponent("bad.syncr")
    try Data([0, 1, 2]).write(to: bad)
    try expectError({ _ = try model.importCopy(from: bad) }, "invalid import must fail")
    try check(model.notes.count == 2, "failed imports did not add notes")
    try check(try sources.map { try Data(contentsOf: $0) } == original, "sources unchanged after failed imports")
}

func viewportAndOffscreenMeshPixels() throws {
    try check(canvasSize().width == 4800, "canvas width")
    try check(canvasSize().viewHeight == 3000, "canvas view height")
    var viewport = Viewport(left: 9000, top: 500, zoom: 2)
    viewport.clamp(pixels: CGSize(width: 480, height: 300), bottom: 400)
    try check(viewport.left == 2400 && viewport.top == 400, "viewport clamp")
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
    static func main() {
        do {
            try importMigratesOnlyLocalCopy()
            try viewportAndOffscreenMeshPixels()
            print("Mac viewer checks: OK")
        } catch {
            fputs("Mac viewer checks: FAILED: \(error)\n", stderr)
            exit(1)
        }
    }
}
