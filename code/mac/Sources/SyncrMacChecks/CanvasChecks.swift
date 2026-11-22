import Foundation
import SyncrCore

/// Canvas maths and the numbers behind the contract's P3/M: the camera, the xnotes background levels, the minimap, waypoints,
/// card placement and the culling index measured with 5,000 strokes. Rendering checks that need the renderer are in
/// `canvasRenderChecks`.
@MainActor func canvasChecks() throws {
    let view = CGSize(width: 1000, height: 700)
    let xn = CanvasCamera.xn

    // Zoom limits and steps.
    try check(abs(xn - 1.2902) < 1e-3, "XN is 1600/1240.157")
    try check(CanvasCamera.clamp(0.0001) == 0.02 && CanvasCamera.clamp(1000) == 64 && CanvasCamera.clamp(.nan) == 1, "zoom clamps to 2%..6400%")
    var cam = CanvasCamera(left: 0, top: 0, zoom: 1)
    for _ in 0..<80 { cam = cam.stepped(-1, in: view) }
    try check(cam.zoom == 0.02, "Cmd - stops at 2%")
    for _ in 0..<80 { cam = cam.stepped(1, in: view) }
    try check(cam.zoom == 64, "Cmd + stops at 6400%")
    let one = CanvasCamera(left: 10, top: 20, zoom: 1).stepped(1, in: view)
    try check(abs(one.zoom - 1.25) < 1e-12, "one step is x1.25")

    // The point under the pinch stays put, at every zoom, far from the origin too.
    for (l, t) in [(0.0, 0.0), (-3e6, 8e6), (123456.7, -98765.4)] {
        var c = CanvasCamera(left: l, top: t, zoom: 0.5)
        let anchor = CGPoint(x: 300, y: 200)
        let before = c.unit(at: anchor)
        for z in [0.02, 0.3, 1, 7.5, 64] {
            c = c.zoomed(to: z, around: anchor)
            let after = c.unit(at: anchor)
            try check(abs(after.x - before.x) < 1e-6 * max(1, abs(before.x)) && abs(after.y - before.y) < 1e-6 * max(1, abs(before.y)), "pinch anchor holds at zoom \(z) near \(l)")
        }
    }
    // No clamp: a pan a billion points out is still a pan.
    let far = CanvasCamera(left: 0, top: 0, zoom: 1).panned(byPoints: -1e9, 1e9)
    try check(far.left < -1e9 && far.top > 1e9, "pan has no clamp")

    // Fit all.
    let empty = CanvasCamera.fitAll(content: nil, in: view)
    try check(empty.zoom == 1 && abs(empty.centre(in: view).x) < 1e-9 && abs(empty.centre(in: view).y) < 1e-9, "empty canvas: 100% at the origin")
    let box = CGRect(x: 1000, y: -500, width: 4000, height: 2000)
    let fit = CanvasCamera.fitAll(content: box, in: view)
    let seen = fit.visibleRect(in: view)
    let pad = CanvasCamera.fitPadding
    try check(abs(pad - 48 * xn) < 1e-9, "fit padding is 48 xnotes px")
    try check(seen.contains(box.insetBy(dx: -pad + 0.01, dy: -pad + 0.01)), "fit shows content plus padding")
    try check(abs(seen.width - (box.width + 2 * pad)) < 1e-6 || abs(seen.height - (box.height + 2 * pad)) < 1e-6, "fit is tight on one axis")
    try check(abs(fit.centre(in: view).x - box.midX) < 1e-6 && abs(fit.centre(in: view).y - box.midY) < 1e-6, "fit centres the content")
    let tiny = CanvasCamera.fitAll(content: CGRect(x: 5, y: 5, width: 0, height: 0), in: view)
    try check(abs(tiny.centre(in: view).x - 5) < 1e-9 && tiny.zoom > 1 && tiny.zoom <= 64, "a lone point fits on its padding, centred")

    // The last view is a centre, so a different window size keeps what you were looking at.
    var last = CanvasLastViews()
    last.set(CanvasCamera(left: 300, top: -200, zoom: 2.5), in: view, for: "n1")
    let again = CanvasLastViews.load(last.encoded())
    let restored = again.camera(for: "n1", in: CGSize(width: 1400, height: 900))
    let was = CanvasCamera(left: 300, top: -200, zoom: 2.5).centre(in: view)
    try check(restored.map { abs($0.centre(in: CGSize(width: 1400, height: 900)).x - was.x) < 1e-6 && $0.zoom == 2.5 } == true, "last view restores its centre and zoom")
    try check(again.camera(for: "other", in: view) == nil, "an unseen canvas has no last view")

    // Minimap.
    let content = CGRect(x: 0, y: 0, width: 6000, height: 2000)
    let viewRect = CGRect(x: -500, y: 100, width: 1000, height: 700)
    let map = CanvasMinimapMap(content: content, viewport: viewRect)
    try check(map.extent == content.union(viewRect), "minimap maps the union of content and viewport")
    let want = min((168 - 16) / map.extent.width, (120 - 16) / map.extent.height)
    try check(abs(map.scale - want) < 1e-12, "minimap scale is min((W-16)/extW, (H-16)/extH)")
    let mapped = map.rect(map.extent)
    try check(mapped.minX >= 8 - 1e-9 && mapped.maxX <= 160 + 1e-9 && mapped.minY >= 8 - 1e-9 && mapped.maxY <= 112 + 1e-9, "the extent fits inside the padding")
    let tapped = map.unit(at: CGPoint(x: map.rect(content).midX, y: map.rect(content).midY))
    try check(abs(tapped.x - content.midX) < 1e-6 && abs(tapped.y - content.midY) < 1e-6, "a tap maps back to a note unit")
    try check(CanvasMinimapMap.size == CGSize(width: 168, height: 120) && CanvasMinimapMap.margin == 14, "minimap size and margin")
    try check(CanvasMinimapMap(content: nil, viewport: viewRect).extent == viewRect, "an empty canvas maps the viewport")

    // Waypoints.
    var list: [Waypoint] = []
    list = CanvasWaypoints.saving(list, name: "  Plan ", camera: CanvasCamera(left: 0, top: 0, zoom: 2), in: view)!
    list = CanvasWaypoints.saving(list, name: "Other", camera: .home, in: view)!
    list = CanvasWaypoints.saving(list, name: "PLAN", camera: CanvasCamera(left: 100, top: 0, zoom: 4), in: view)!
    try check(list.count == 2 && list[0].name == "PLAN" && list[0].zoom == 4, "same name in any case replaces in place")
    try check(CanvasWaypoints.saving(list, name: "   ", camera: .home, in: view) == nil, "empty name is refused")
    try check(CanvasWaypoints.cleanName(String(repeating: "x", count: 80)).count == 48, "names are at most 48 characters")
    let jumped = CanvasWaypoints.camera(list[0], in: view)
    try check(abs(jumped.centre(in: view).x - Double(list[0].cx)) < 1e-3 && jumped.zoom == 4, "jumping restores the centre and zoom")
    try check(CanvasWaypoints.removing(list, name: "plan").count == 1, "removal ignores case")
    var full: [Waypoint] = (0..<100).map { Waypoint(name: "w\($0)", cx: 0, cy: 0, zoom: 1) }
    try check(CanvasWaypoints.saving(full, name: "new", camera: .home, in: view) == nil, "a full list refuses a new name")
    full = CanvasWaypoints.saving(full, name: "W5", camera: .home, in: view)!
    try check(full.count == 100, "a full list still replaces")
    try check(CanvasWaypoints.nextName([Waypoint(name: "waypoint 2", cx: 0, cy: 0, zoom: 1)]) == "Waypoint 3", "next name skips taken ones")

    // Cards: right of the anchor at the gap, pushed down past earlier cards.
    let placed = CanvasCards.place(anchors: ["a": CGRect(x: 100, y: 200, width: 300, height: 80), "b": CGRect(x: 120, y: 220, width: 100, height: 100)],
                                   cards: [.init(id: "c1", anchor: "a", width: 280, height: 120), .init(id: "c2", anchor: "b", width: 280, height: 90)])
    try check(abs(placed["c1"]!.x - (400 + 20.6)) < 1e-3 && placed["c1"]!.y == 200, "a card sits right of its anchor at the gap")
    try check(placed["c2"]!.y >= placed["c1"]!.y + 120, "a later card is pushed below an earlier one it would overlap")

    // The index returns what a linear scan finds, in order, and is measured with 5,000 strokes.
    var seed: UInt64 = 0x9E3779B97F4A7C15
    func rnd() -> Double { seed = seed &* 6364136223846793005 &+ 1442695040888963407; return Double(seed >> 11) / Double(1 << 53) }
    var strokes: [CGRect] = []
    for _ in 0..<5000 {
        let w = 20 + rnd() * 500, h = 20 + rnd() * 300
        strokes.append(CGRect(x: (rnd() - 0.5) * 60000, y: (rnd() - 0.5) * 40000, width: w, height: h))
    }
    strokes.append(CGRect(x: -30000, y: -20000, width: 60000, height: 40000))      // one huge stroke: the oversized list
    var index = CanvasIndex(); for s in strokes { index.append(s) }
    var queries: [CGRect] = []
    for _ in 0..<200 { let z = 0.05 + rnd() * 10; queries.append(CGRect(x: (rnd() - 0.5) * 60000, y: (rnd() - 0.5) * 40000, width: 1200 / z, height: 800 / z)) }
    for q in queries {
        let linear = strokes.indices.filter { strokes[$0].intersects(q) }
        let hit = Set(index.query(q).map(Int.init))
        try check(linear.allSatisfy(hit.contains), "the index never misses a visible stroke")
    }
    func time(_ body: () -> Int) -> (ms: Double, sink: Int) {
        let t = monotonicNs(); var sink = 0
        for _ in 0..<20 { sink &+= body() }
        return (Double(monotonicNs() - t) / 1e6 / 20, sink)
    }
    let linear = time { queries.reduce(0) { n, q in n &+ strokes.reduce(0) { $0 &+ ($1.intersects(q) ? 1 : 0) } } }
    let grid = time { queries.reduce(0) { $0 &+ index.query($1).count } }
    print(String(format: "canvas culling, 5000 strokes, 200 views: linear bounds test %.3f ms/view, 512-unit grid %.3f ms/view", linear.ms / 200, grid.ms / 200))
}

import Metal

/// Plain paper through the real renderer, the dry cache on a canvas viewport, and 5,000 strokes timed.
@MainActor func canvasRenderChecks() throws {
    guard let device = MTLCreateSystemDefaultDevice() else { throw CheckFailure(description: "Metal device unavailable") }
    let renderer = try MeshRenderer(device: device, dryCache: true)
    let view = CGSize(width: 800, height: 560)
    func texture(_ w: Int, _ h: Int) throws -> MTLTexture {
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
        d.usage = [.renderTarget, .shaderRead]; d.storageMode = .shared
        guard let t = device.makeTexture(descriptor: d) else { throw CheckFailure(description: "Metal texture unavailable") }
        return t
    }
    func pixels(_ t: MTLTexture) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: t.width * t.height * 4)
        t.getBytes(&bytes, bytesPerRow: t.width * 4, from: MTLRegionMake2D(0, 0, t.width, t.height), mipmapLevel: 0)
        return bytes
    }
    let target = try texture(800, 560)
    let empty = Drawing(title: "Canvas", strokes: [], lowestInk: 0)
    func render(_ camera: CanvasCamera, cached: Bool = false, dark: Bool = false) throws -> [UInt8] {
        try renderer.render(empty, viewport: camera.viewport(in: view), dark: dark, target: target, cacheDry: cached)
        return pixels(target)
    }
    // Plain paper: every pixel is the theme's paper, light and dark, at any zoom and far from the origin.
    for dark in [false, true] {
        let paper = NotePalette.rgba(ThemeTokens.page(dark))
        let want = [UInt8(paper.z * 255 + 0.5), UInt8(paper.y * 255 + 0.5), UInt8(paper.x * 255 + 0.5)]
        for cam in [CanvasCamera(left: 10, top: 10, zoom: 1), CanvasCamera(left: -9e5, top: 3e5, zoom: 0.02), CanvasCamera(left: 5, top: 5, zoom: 64)] {
            let p = try render(cam, dark: dark)
            try check(stride(from: 0, to: p.count, by: 4).allSatisfy { Array(p[$0..<$0 + 3]) == want }, "a canvas is plain theme paper (dark \(dark), zoom \(cam.zoom))")
        }
    }
    // The dry cache keyed on a canvas viewport: still frames from the cache match a plain draw, and a new viewport rebuilds it.
    let camera = CanvasCamera(left: 10, top: 10, zoom: 1)
    let builds = renderer.dryCacheBuilds
    let plain = try render(camera)
    let first = try render(camera, cached: true), second = try render(camera, cached: true)
    try check(first == plain && second == plain, "cached frames equal an uncached one")
    try check(renderer.dryCacheBuilds == builds + 1 && renderer.dryCacheHits >= 1, "same viewport hits the cache")
    _ = try render(CanvasCamera(left: 11, top: 10, zoom: 1), cached: true)
    try check(renderer.dryCacheBuilds == builds + 2, "a moved canvas viewport rebuilds it")
    _ = try render(CanvasCamera(left: 11, top: 10, zoom: 1.25), cached: true)
    try check(renderer.dryCacheBuilds == builds + 3, "a zoomed canvas viewport rebuilds it")

    // 5,000 strokes spread over 60,000 x 40,000 note units, timed at three zooms.
    let sample = try encodeSamples(channels: SampleChannels(x: (0..<24).map { Float($0) * 9 }, y: (0..<24).map { 40 * sinf(Float($0) / 3) }, pressure: [Float](repeating: 0.6, count: 24),
                                                           elapsedMs: (0..<24).map { UInt32($0 * 8) }, tilt: nil, orientation: nil))
    let mesh = try tessellateStroke(samples: sample, style: defaultStrokeStyle(brush: Brush(family: .pen, size: .medium, colour: .ink)),
                                    transform: Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0))
    var seed: UInt64 = 42
    func rnd() -> Float { seed = seed &* 6364136223846793005 &+ 1442695040888963407; return Float(seed >> 40) / Float(1 << 24) }
    let strokes = (0..<5000).map { _ in DrawStroke(mesh: mesh, originX: (rnd() - 0.5) * 60000, originY: (rnd() - 0.5) * 40000, colour: .ink, highlighter: false) }
    let big = Drawing(title: "5000", strokes: strokes, lowestInk: 0)
    let screen = try texture(2400, 1600)       // a 1200 x 800 point view at 2x
    let bigView = CGSize(width: 1200, height: 800)
    var report: [String] = []
    var identical = true
    for (zoom, label) in [(0.02, "2%"), (0.25, "25%"), (1.0, "100%"), (8.0, "800%")] {
        let cam = CanvasCamera.centred(on: .zero, zoom: zoom, in: bigView)
        var cells: [String] = []
        var images: [[UInt8]] = []
        for grid in [false, true] {
            renderer.gridCulling = grid
            try renderer.render(big, viewport: cam.viewport(in: bigView), dark: false, target: screen)   // upload and warm up
            var total: [Double] = []
            for i in 0..<20 {
                let moved = cam.panned(byPoints: Double(i) * 7, 0)
                let started = monotonicNs()
                try renderer.render(big, viewport: moved.viewport(in: bigView), dark: false, target: screen)
                total.append(Double(monotonicNs() - started) / 1e6)
            }
            images.append(pixels(screen))
            cells.append(String(format: "%@ %.2f", grid ? "grid" : "linear", total.sorted()[10]))
        }
        identical = identical && images[0] == images[1]
        let visible = strokes.filter { cam.visibleRect(in: bigView).intersects(CGRect(x: CGFloat($0.originX + $0.mesh.minX), y: CGFloat($0.originY + $0.mesh.minY), width: CGFloat($0.mesh.maxX - $0.mesh.minX), height: CGFloat($0.mesh.maxY - $0.mesh.minY))) }.count
        report.append("\(label) (\(visible) in view): " + cells.joined(separator: ", ") + " ms")
    }
    renderer.gridCulling = true
    try check(identical, "grid culling draws exactly what the linear pass draws")
    print("canvas render, 5000 strokes, 2400x1600 target, frame ms (debug build, waited on GPU): " + report.joined(separator: "; "))
}

/// Creating a canvas (Shift-Cmd-N's path), opening it, and its commands on a temporary library.
@MainActor func canvasWorkspaceChecks() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-canvas-checks-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: dir) }
    let model = LibraryModel(folder: dir.appendingPathComponent("lib"))
    let w = Workspace(model: model)
    var activations: [String?] = []
    w.onActivate = { activations.append($0) }
    w.restoreSession()
    try check(w.availableCommands().contains(.newCanvas) && AppCommand.newCanvas.title == "New canvas…", "the palette lists New canvas…")
    try check(KeyBinding.defaultBinding(for: .newCanvas)?.compact == "⇧⌘N", "Shift-Cmd-N is New canvas (\(KeyBinding.defaultBinding(for: .newCanvas)?.compact ?? "-"))")
    try check(KeyBinding.defaultBinding(for: .newNotebook)?.compact != KeyBinding.defaultBinding(for: .newCanvas)?.compact, "New notebook gave the shortcut up")
    try check(model.nextUntitledCanvas() == "Untitled canvas", "first name offered")
    let note = try w.newNote()
    let id = try w.newCanvas(title: model.nextUntitledCanvas())
    try check(w.tabs == [note, id] && w.active == id && activations.last == id, "a canvas opens in a new tab")
    try check(model.notes.first { $0.id == id }?.kind == .canvas && model.notes.first { $0.id == note }?.kind == .note, "the library knows its kind")
    try check(model.nextUntitledCanvas() == "Untitled canvas 2", "next name skips taken ones")
    let blank = try w.newCanvas(title: "   ")
    try check(w.title(of: blank) == "Untitled canvas", "a blank name falls back")
    try check(try w.newCanvas(title: "Plan") != id && w.title(of: w.active ?? "") == "Plan", "named canvas")

    try model.open(id: id)
    let drawing = try require(model.drawing, "canvas loads")
    try check(drawing.geometry.isCanvas && drawing.geometry.pages.isEmpty, "geometry is a canvas")
    let state = try require(drawing.canvas, "canvas state")
    try check(state.waypoints.isEmpty, "no waypoints yet")

    // Commands: waypoints, through the note, each read back.
    let library = try require(model.library, "library")
    let open = try library.openNote(noteId: id)
    let list = [Waypoint(name: "Plan", cx: 100, cy: -50, zoom: 2), Waypoint(name: "Far", cx: 9e6, cy: -9e6, zoom: 0.02)]
    try CanvasCommands.setWaypoints(open, list, newId: { newId() })
    try CanvasCommands.setWaypoints(open, CanvasWaypoints.removing(list, name: "far"), newId: { newId() })
    let snap = try open.snapshot()
    try open.closeNote()
    let back = try require(CanvasState(snapshot: snap), "canvas state after commands")
    try check(back.waypoints == [list[0]], "waypoints round-trip, with the removal")
    // A note refuses them.
    let plain = try library.openNote(noteId: note)
    var refused = false
    do { try CanvasCommands.setWaypoints(plain, list, newId: { newId() }) } catch { refused = true }
    try plain.closeNote()
    try check(refused, "a note refuses waypoints")

    // Live ink has no left edge on a canvas, and keeps it on a note.
    var bounded = Viewport(left: -300, top: 0, zoom: 2)
    try check(bounded.canvasPoint(0, 0).0 == 0, "a note's ink stops at the left edge")
    bounded.unbounded = true
    try check(bounded.canvasPoint(0, 0).0 == -300 && bounded.canvasPoint(100, 50).0 == -250, "a canvas's ink does not")
    let camera = CanvasCamera(left: -9e5, top: 4e5, zoom: 3.5)
    let seen = camera.viewport(in: CGSize(width: 1000, height: 700))
    try check(seen.unbounded && seen.left == -9e5 && seen.top == 4e5, "the camera makes an unbounded viewport")
    let units = seen.visibleSize(CGSize(width: 1000, height: 700))
    try check(abs(Double(units.x) - 1000 / camera.pointsPerUnit) < 0.01 * Double(units.x) && abs(Double(units.y) - 700 / camera.pointsPerUnit) < 1e-3 * Double(units.y) + 0.01, "its visible size is the camera's (\(units.x) x \(units.y))")
    let resized = camera.viewport(in: CGSize(width: 1400, height: 900)).visibleSize(CGSize(width: 1400, height: 900))
    try check(abs(Double(resized.x) - 1400 / camera.pointsPerUnit) < 0.01 * Double(resized.x), "resizing the window does not change the scale")
}

private func require<T>(_ value: T?, _ message: String) throws -> T {
    guard let value else { throw CheckFailure(description: message) }
    return value
}
