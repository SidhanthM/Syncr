import Foundation
import Metal
import SyncrCore

// Live-ink checks. Everything runs against disposable libraries in temp folders, through the same
// engine, coordinator, renderer and core calls as the app, with the in-process replay as the source.
// The main-thread callbacks (commit swap, status) are driven by spinning the main run loop.

private let rigWidth = 1200, rigHeight = 750
private let identity = Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0)

@MainActor private func pump(_ what: String, timeout: Double = 10, until condition: () -> Bool) throws {
    let end = Date().addingTimeInterval(timeout)
    while !condition() {
        if Date() > end { throw CheckFailure(description: "timed out waiting for \(what)") }
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.002))
    }
}

/// A writable note open in a workspace with a coordinator, an offscreen target and a replay driver.
@MainActor private final class LiveRig {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let model: LibraryModel
    let workspace: Workspace
    let live: LiveInkCoordinator
    let renderer: MeshRenderer
    let texture: MTLTexture
    let driver: ReplayDriver
    let noteId: String
    var sheetId: String { model.drawing?.sheetId ?? "" }
    var viewport = Viewport() { didSet { live.viewChanged(viewport) } }

    init(debug: Bool = false) throws {
        guard let device = MTLCreateSystemDefaultDevice() else { throw CheckFailure(description: "Metal device unavailable") }
        model = LibraryModel(folder: dir.appendingPathComponent("lib"))
        workspace = Workspace(model: model)
        workspace.restoreSession()
        live = LiveInkCoordinator(model: model, workspace: workspace, device: device, debug: debug)
        renderer = try MeshRenderer(device: device)
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: rigWidth, height: rigHeight, mipmapped: false)
        desc.usage = [.renderTarget, .shaderRead]; desc.storageMode = .shared
        guard let texture = device.makeTexture(descriptor: desc) else { throw CheckFailure(description: "Metal texture unavailable") }
        self.texture = texture
        driver = ReplayDriver(engine: live.engine)
        noteId = try workspace.newNote()
        try model.open(id: noteId)
        live.viewport = { [unowned self] in self.viewport }
        live.syncTarget(); live.viewChanged(viewport)
        live.engine.waitIdle()
    }
    var cleanup = true
    deinit { if cleanup { try? FileManager.default.removeItem(at: dir) } }

    var engine: LiveInk { live.engine }

    func plan(_ kind: ReplayKind, noteId: String? = nil, sheetId: String? = nil, samples: Int? = nil,
              custom: (brush: Brush, channels: SampleChannels)? = nil) throws -> ReplayPlan {
        try ReplayGenerator.plan(kind: kind, noteId: noteId ?? self.noteId, sheetId: sheetId ?? self.sheetId,
                                 epoch: engine.epoch, view: viewport, samples: samples, custom: custom)
    }

    /// Feeds steps and waits for the engine to process them.
    func feed(_ steps: some Sequence<ReplayStep>) throws {
        for step in steps { try driver.feed(step, at: monotonicNs()) }
        engine.waitIdle()
    }

    func render(wet: Bool = true) throws -> [UInt8] {
        try renderer.render(model.drawing, wet: wet ? engine.currentFrame : nil, viewport: viewport, dark: false, target: texture)
        var bytes = [UInt8](repeating: 0, count: rigWidth * rigHeight * 4)
        texture.getBytes(&bytes, bytesPerRow: rigWidth * 4, from: MTLRegionMake2D(0, 0, rigWidth, rigHeight), mipmapLevel: 0)
        return bytes
    }

    var strokesOnDisk: Int { get throws { try model.open(id: noteId); return model.drawing?.strokes.count ?? -1 } }
}

private func inkPixels(_ bytes: [UInt8]) -> Int {
    let paper = Array(bytes[0..<4]); var n = 0
    for i in stride(from: 0, to: bytes.count, by: 4) where Array(bytes[i..<i + 4]) != paper { n += 1 }
    return n
}

private func pixel(_ bytes: [UInt8], _ x: Int, _ y: Int) -> [UInt8] {
    let i = (y * rigWidth + x) * 4
    return Array(bytes[i..<i + 4])
}

private func previewSteps(_ plan: ReplayPlan) -> [ReplayStep] { plan.steps.filter { $0.preview != nil } }
private func commitStep(_ plan: ReplayPlan) -> ReplayStep { plan.steps.last! }

@MainActor private func liveInkEndToEnd() throws {
    let (folder, id, dir) = try liveInkEndToEndBody()   // the rig is gone: its library handle is closed
    defer { try? FileManager.default.removeItem(at: dir) }
    let reopened = LibraryModel(folder: folder)
    try reopened.open(id: id)
    try check(reopened.drawing?.strokes.count == 1, "a fresh library on the same folder reads one stroke")
}

@MainActor private func liveInkEndToEndBody() throws -> (URL, String, URL) {
    let rig = try LiveRig(debug: true)
    try check(rig.workspace.availableCommands().contains(.replayTestStroke), "debug flag lists replay commands")
    try check(AppCommand.matching("replay", available: rig.workspace.availableCommands()).count == 4, "four replay commands")
    try check(rig.sheetId != "", "sheet known")
    try check(inkPixels(try rig.render()) == 0, "empty note renders paper only")

    // Partial stroke is visible mid-replay, before any commit; the drawing is untouched.
    let plan = try rig.plan(.pen)
    let previews = previewSteps(plan)
    let half = previews.prefix(previews.count / 2)
    try rig.feed(half)
    try check(rig.engine.currentFrame.strokes.count == 1, "one wet stroke mid-replay")
    try check(rig.model.drawing?.strokes.isEmpty == true, "nothing committed mid-replay")
    let partial = inkPixels(try rig.render())
    try check(partial > 500, "partial stroke has ink offscreen (\(partial))")
    // The same frame, taken for display, records software-side latency per merged frame.
    let taken = rig.engine.take()
    try check(!taken.times.isEmpty, "take returns the receive times of the frames it merges")
    try rig.renderer.render(rig.model.drawing, wet: taken.frame, viewport: rig.viewport, dark: false, target: rig.texture,
                            stage: rig.engine.stageSink(taken.times))
    let summary = rig.engine.latency.summary()
    try check(summary.count == taken.times.count && summary.p50 >= 0 && summary.max >= summary.p50, "latency recorded per frame")
    // More samples extend the ink.
    try rig.feed(previews.dropFirst(previews.count / 2))
    let full = inkPixels(try rig.render())
    try check(full > partial, "full preview has more ink than the partial one")

    // Commit: observe the main thread between run-loop turns. At no turn may the stroke be in neither
    // the drawing nor the wet layer, or in both.
    var both = 0, neither = 0, sawBoth = false
    try rig.feed([commitStep(plan)])
    try pump("commit saved") {
        let inDrawing = !(rig.model.drawing?.strokes.isEmpty ?? true), inWet = !rig.engine.currentFrame.strokes.isEmpty
        if inDrawing && inWet { both += 1 }
        if !inDrawing && !inWet { neither += 1 }
        sawBoth = sawBoth || inWet
        return rig.engine.stats.commitsSaved == 1
    }
    try check(both == 0 && neither == 0, "swap has no frame with both or neither (both \(both), neither \(neither))")
    try check(rig.model.drawing?.strokes.count == 1 && rig.engine.currentFrame.strokes.isEmpty, "exactly one committed stroke, no wet ink")
    let committed = try rig.render()
    try check(inkPixels(committed) > 0, "committed stroke draws")
    try check(rig.engine.stats.commitsReassembled == 1, "one commit reassembled")
    // Late preview frames of a committed stroke cannot resurrect it.
    try rig.feed(previews.suffix(3))
    try check(rig.engine.currentFrame.strokes.isEmpty, "late preview does not resurrect a committed stroke")

    // Reopen from disk: one stroke, pixel-equal.
    try check(try rig.strokesOnDisk == 1, "one stroke on disk")
    try check(try rig.render() == committed, "committed stroke is pixel-equal after reopen")
    // Retransmitted commit is counted, not written twice.
    try rig.feed([commitStep(plan)])
    try pump("duplicate counted") { rig.engine.stats.duplicateCommits == 1 }
    try check(try rig.strokesOnDisk == 1, "duplicate commit wrote nothing")

    rig.cleanup = false
    return (rig.model.folder, rig.noteId, rig.dir)
}

@MainActor private func liveInkCancelExpiryReset() throws {
    let rig = try LiveRig()
    // Cancel leaves nothing, in the view or on disk.
    let cancelled = try rig.plan(.cancelled)
    try rig.feed(cancelled.steps.dropLast())
    try check(rig.engine.currentFrame.strokes.count == 1 && inkPixels(try rig.render()) > 0, "stroke visible before cancel")
    try rig.feed([cancelled.steps.last!])
    try check(rig.engine.currentFrame.strokes.isEmpty && inkPixels(try rig.render()) == 0, "cancel clears the wet layer")
    try check(rig.engine.stats.droppedCancelled == 1, "cancel counted")
    try check(try rig.strokesOnDisk == 0, "cancelled stroke saved nothing")

    // Expiry: a live stroke with no frame for more than 5 s goes away.
    let pen = try rig.plan(.pen)
    try rig.feed(previewSteps(pen).prefix(14))
    try check(rig.engine.currentFrame.strokes.count == 1, "live stroke present")
    rig.engine.tick(atNs: monotonicNs() + 4_000_000_000); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.strokes.count == 1, "not expired after 4 s")
    rig.engine.tick(atNs: monotonicNs() + 6_000_000_000); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.strokes.isEmpty && rig.engine.stats.droppedExpired == 1, "idle expiry clears the wet layer")
    try check(inkPixels(try rig.render()) == 0, "expired ink is gone from the pixels")

    // An ended preview is held until commit or 10 s.
    let ended = try rig.plan(.pen)
    try rig.feed(previewSteps(ended))
    rig.engine.tick(atNs: monotonicNs() + 6_000_000_000); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.strokes.count == 1, "ended preview held for 6 s")
    rig.engine.tick(atNs: monotonicNs() + 11_000_000_000); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.strokes.isEmpty, "ended preview expires after 10 s")

    // Reset (simulated disconnect) clears wet ink and hover, adopts a new epoch and ignores the old one.
    let before = rig.engine.epoch
    let third = try rig.plan(.pen)
    try rig.feed(third.steps.prefix(12))
    try check(!rig.engine.currentFrame.isEmpty, "live content before reset")
    rig.engine.reset(); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.isEmpty && rig.engine.epoch == before + 1 && rig.engine.stats.resets == 1, "reset clears everything")
    try rig.feed(third.steps.suffix(from: 12).prefix(6))
    try check(rig.engine.currentFrame.strokes.isEmpty, "frames from the old epoch are ignored")
    let next = try rig.plan(.pen)
    try rig.feed(previewSteps(next).prefix(10))
    try check(rig.engine.currentFrame.strokes.count == 1, "the new epoch draws")
    // A connection that ended must not reset what a newer one is drawing (the phone reconnects at once: the old close arrives late).
    let live = rig.engine.epoch
    rig.engine.reset(onlyIfEpoch: live &- 1); rig.engine.waitIdle()
    try check(rig.engine.epoch == live && rig.engine.currentFrame.strokes.count == 1, "a reset for an older epoch changes nothing")
    rig.engine.reset(onlyIfEpoch: live); rig.engine.waitIdle()
    try check(rig.engine.epoch == live &+ 1 && rig.engine.currentFrame.strokes.isEmpty, "a reset for the current epoch clears and moves on")
}

@MainActor private func liveInkRefusals() throws {
    let rig = try LiveRig()
    // No preview: a commit alone saves.
    let solo = try rig.plan(.pen)
    try rig.feed([commitStep(solo)])
    try pump("solo commit saved") { rig.engine.stats.commitsSaved == 1 }
    try check(rig.model.drawing?.strokes.count == 1 && inkPixels(try rig.render()) > 0, "commit with no preview saves and draws")
    try check(try rig.strokesOnDisk == 1, "commit with no preview is on disk")

    // A different note: dropped and counted, the note unchanged, the UI says why.
    let other = try rig.plan(.pen, noteId: newId())
    try rig.feed(other.steps)
    try pump("wrong-note commit refused") { rig.engine.stats.commitsRefused == 1 }
    try check(rig.engine.stats.refusedWrongNote > 0 && rig.engine.currentFrame.isEmpty, "wrong note counted, nothing drawn")
    try check(try rig.strokesOnDisk == 1, "wrong note left the note unchanged")
    try check(rig.live.statusText()?.contains("different note") == true, "status explains a wrong note")

    // A write that fails: unknown sheet. Note unchanged, no ghost ink, error surfaced.
    let broken = try rig.plan(.pen, sheetId: newId())
    try rig.feed(broken.steps)
    try pump("failed write reported") { rig.engine.stats.commitsFailed == 1 }
    try check(rig.engine.currentFrame.strokes.isEmpty, "a failed write leaves no ghost ink")
    try check(try rig.strokesOnDisk == 1, "a failed write left the note unchanged")
    try check(rig.live.statusText()?.contains("could not save") == true, "status surfaces the write error")
    // The failure is not sticky for the next good stroke.
    let good = try rig.plan(.pen)
    try rig.feed(good.steps)
    try pump("good stroke after failure") { rig.engine.stats.commitsSaved == 2 }
    try check(rig.live.statusText()?.contains("could not save") != true, "error clears after a successful save")

    // A read-only imported copy as the active note.
    let phone = try openLibrary(rootPath: rig.dir.appendingPathComponent("phone").path)
    let importedId = newId()
    _ = try phone.createNote(request: CreateNoteRequest(operationId: newId(), noteId: importedId, firstSheetId: newId(), title: "Phone", notebook: nil, kind: .note))
    let source = try phone.openNote(noteId: importedId)
    let file = rig.dir.appendingPathComponent("export.syncr")
    _ = try source.exportCopy(destinationPath: file.path)
    try source.closeNote()
    try rig.model.importCopy(from: file)
    rig.workspace.libraryChanged()
    rig.workspace.open(importedId, newTab: true)
    try rig.model.open(id: importedId)
    rig.live.syncTarget(); rig.engine.waitIdle()
    try check(rig.workspace.activeIsWritable, "an imported copy is writable on the Mac (link v1)")
    // The engine's refusal for a target the app marks non-writable still holds.
    rig.engine.setTarget(noteId: importedId, writable: false, mirror: false)
    let sheet = rig.sheetId
    let ro = try rig.plan(.pen, noteId: importedId, sheetId: sheet)
    try rig.feed(ro.steps)
    try pump("read-only commit refused") { rig.engine.stats.commitsRefused == 2 }
    try check(rig.engine.stats.refusedReadOnly > 0 && rig.engine.currentFrame.isEmpty, "read-only counted, nothing drawn")
    try check(rig.live.statusText()?.contains("read-only") == true, "status explains a read-only note")
    try rig.model.open(id: importedId)
    try check(rig.model.drawing?.strokes.isEmpty == true, "read-only note unchanged")

    // No note open at all.
    rig.workspace.closeTab(importedId); rig.workspace.closeTab(rig.noteId)
    rig.live.syncTarget(); rig.engine.waitIdle()
    let fresh = try rig.plan(.pen, noteId: importedId, sheetId: sheet)
    try rig.feed(previewSteps(fresh).prefix(10))
    try check(rig.engine.stats.refusedNoNote > 0 && rig.engine.currentFrame.isEmpty, "no open note: dropped and counted")
    try check(rig.live.statusText()?.contains("no note is open") == true, "status explains no open note")
}

@MainActor private func liveInkSwitchingNotes() throws {
    let rig = try LiveRig()
    let first = rig.noteId
    let pen = try rig.plan(.pen)
    try rig.feed(previewSteps(pen).prefix(14))
    try check(rig.engine.currentFrame.strokes.count == 1, "stroke live in the first note")
    let second = try rig.workspace.newNote()
    try rig.model.open(id: second); rig.live.syncTarget(); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.strokes.isEmpty, "switching notes drops wet ink")
    try rig.feed(previewSteps(pen).suffix(4))
    try check(rig.engine.currentFrame.strokes.isEmpty, "samples for the first note are not drawn into the second")
    try rig.feed([commitStep(pen)])
    try pump("commit for first note refused") { rig.engine.stats.commitsRefused == 1 }
    try rig.model.open(id: second)
    try check(rig.model.drawing?.strokes.isEmpty == true, "second note unchanged")
    try rig.model.open(id: first)
    try check(rig.model.drawing?.strokes.isEmpty == true, "first note unchanged")
}

@MainActor private func liveInkMalformed() throws {
    let rig = try LiveRig()
    let plan = try rig.plan(.pen)
    let frames: [Data] = {
        var out: [Data] = []
        let queue = PreviewSendQueue()
        for step in plan.steps {
            if let f = step.preview { try? queue.pushPreview(frame: f) }
            if let c = step.commit { try? queue.pushCommit(commit: c) }
        }
        while let b = queue.pop() { out.append(b) }
        return out
    }()
    var hostile: [Data] = [Data(), Data([0x50]), Data([0x43]), Data([0xff, 1, 2, 3]), Data(repeating: 0x50, count: 200_000),
                           Data(repeating: 0, count: 68), Data(frames[10].dropLast(5)), frames[10] + [0]]
    var wrongVersion = frames[10]; wrongVersion[1] = 9; hostile.append(wrongVersion)
    var rng = SystemRandomNumberGenerator()
    for _ in 0..<400 {
        // Mutate preview frames anywhere, and commit chunks only in their payload: the CRC covers
        // that, while a rewritten header can legitimately be a different, valid commit.
        var f = frames[Int.random(in: 0..<frames.count, using: &rng)]
        let low = f[0] == 0x43 ? 36 : 0
        for _ in 0..<Int.random(in: 1...4, using: &rng) { f[Int.random(in: low..<f.count, using: &rng)] = UInt8.random(in: 0...255, using: &rng) }
        hostile.append(f)
    }
    for bytes in hostile { rig.engine.receive(bytes, at: monotonicNs()) }
    rig.engine.waitIdle()
    try check(rig.engine.stats.malformed >= 8, "malformed frames counted (\(rig.engine.stats.malformed))")
    try check(rig.engine.stats.framesReceived == hostile.count, "every frame counted")
    try check(try rig.strokesOnDisk == 0, "hostile input wrote nothing")
    // The engine still works afterwards (after a reset, whatever the fuzz left behind is gone).
    rig.engine.reset(); rig.engine.waitIdle()
    let clean = try rig.plan(.pen)
    try rig.feed(clean.steps)
    try pump("clean stroke after fuzz") { rig.engine.stats.commitsSaved >= 1 }
    try check(try rig.strokesOnDisk == 1, "clean stroke saves after hostile input")
}

@MainActor private func liveInkViewRelative() throws {
    let rig = try LiveRig()
    rig.viewport = Viewport(left: 1000, top: 500, zoom: 2)
    let plan = try rig.plan(.viewRelative)
    // The wet layer maps with the viewport at Begin, and scrolling mid-stroke does not shear it.
    let previews = previewSteps(plan)
    let begin = previews.firstIndex { if case .begin = $0.preview!.body { return true } else { return false } }!
    try rig.feed(previews.prefix(begin + 1 + 20))
    rig.viewport = Viewport(left: 3000, top: 900, zoom: 4)   // scroll and zoom mid-stroke
    try rig.feed(previews.dropFirst(begin + 1 + 20))
    let pieces = rig.engine.currentFrame.strokes.first?.pieces ?? []
    try check(!pieces.isEmpty, "view-relative stroke is live")
    let minX = pieces.map(\.minX).min()!, maxX = pieces.map(\.maxX).max()!, minY = pieces.map(\.minY).min()!
    let expectedMin = 1000 + plan.channels.x.min()! / 2, expectedMax = 1000 + plan.channels.x.max()! / 2
    try check(abs(minX - expectedMin) < 80 && abs(maxX - expectedMax) < 80, "wet stroke uses the viewport frozen at Begin (\(minX)...\(maxX) vs \(expectedMin)...\(expectedMax))")
    try check(abs(minY - (500 + plan.channels.y.min()! / 2)) < 80, "wet y uses the frozen viewport")

    // The commit is written with the same mapping, and equals tessellating the mapped samples directly.
    try rig.feed([commitStep(plan)])
    try pump("view-relative commit") { rig.engine.stats.commitsSaved == 1 }
    var mapped = plan.channels
    for i in 0..<mapped.x.count { mapped.x[i] = 1000 + mapped.x[i] / 2; mapped.y[i] = 500 + mapped.y[i] / 2 }
    let expected = try tessellateStroke(samples: encodeSamples(channels: mapped),
        style: defaultStrokeStyle(brush: Brush(family: .pen, size: .medium, colour: .green)), transform: identity)
    try check(try rig.strokesOnDisk == 1, "one view-relative stroke on disk")
    let stored = rig.model.drawing!.strokes[0].mesh
    try check(stored.minX == expected.minX && stored.maxX == expected.maxX && stored.minY == expected.minY && stored.maxY == expected.maxY
              && stored.vertices == expected.vertices, "stored stroke is the frozen-viewport mapping")

    // A commit with no preview maps with the viewport at receipt.
    rig.viewport = Viewport(left: 200, top: 100, zoom: 1)
    let solo = try rig.plan(.viewRelative)
    try rig.feed([commitStep(solo)])
    try pump("solo view-relative commit") { rig.engine.stats.commitsSaved == 2 }
    try check(try rig.strokesOnDisk == 2, "second stroke saved")
    let second = rig.model.drawing!.strokes[1].mesh
    try check(abs(second.minX - (200 + solo.channels.x.min()!)) < 80, "commit without a preview maps with the viewport at receipt")

    // A canvas stroke is used as it is, whatever the viewport.
    rig.viewport = Viewport(left: 1500, top: 0, zoom: 1)
    let canvasStroke = try rig.plan(.pen)
    try rig.feed([commitStep(canvasStroke)])
    try pump("canvas commit") { rig.engine.stats.commitsSaved == 3 }
    try check(try rig.strokesOnDisk == 3, "canvas stroke saved")
    let c = rig.model.drawing!.strokes[2].mesh
    try check(abs(c.minX - canvasStroke.channels.x.min()!) < 80, "canvas samples are not remapped")
}

@MainActor private func liveInkHighlighter() throws {
    let rig = try LiveRig()
    rig.viewport = Viewport()
    // The same straight line drawn once, and out and back over itself: multiple wet pieces overlap at
    // their joins and the whole second stroke overlaps itself. The stencil keeps one coverage either way.
    func line(_ samples: Int, back: Bool) -> SampleChannels {
        var c = SampleChannels(x: [], y: [], pressure: [], elapsedMs: [], tilt: nil, orientation: nil)
        for i in 0..<samples {
            let t = Float(i) / Float(samples - 1)
            c.x.append(600 + 3600 * (back ? (t < 0.5 ? t * 2 : 2 - 2 * t) : t)); c.y.append(1500)
            c.pressure.append(0.5); c.elapsedMs.append(UInt32(i * 4))
        }
        return c
    }
    let brush = Brush(family: .highlighter, size: .large, colour: .orange)
    var centre: [[UInt8]] = []
    for back in [false, true] {
        let plan = try rig.plan(.highlighter, samples: nil, custom: (brush, line(back ? 1440 : 720, back: back)))
        try rig.feed(previewSteps(plan))
        let strokes = rig.engine.currentFrame.strokes
        try check(strokes.count == 1 && strokes[0].highlighter && strokes[0].pieces.count > 2, "highlighter is live in several pieces (\(strokes.first?.pieces.count ?? 0))")
        let pixels = try rig.render()
        try check(inkPixels(pixels) > 1000, "highlighter has ink")
        // Pixels along the line, in the middle of its width, including where pieces overlap.
        centre.append(contentsOf: stride(from: 250, to: 950, by: 50).map { pixel(pixels, $0, 375) })
        rig.engine.reset(); rig.engine.waitIdle()
    }
    let single = Set(centre[0..<14].map { $0 }), double = Set(centre[14...].map { $0 })
    try check(single.count == 1 && double.count == 1 && single == double, "live highlighter does not darken where it overlaps itself (\(single) vs \(double))")
    try check(single.first! != [255, 255, 255, 255], "highlighter pixel is not paper")
}

@MainActor private func liveInkIncrementalBounded() throws {
    let rig = try LiveRig()
    let n = 9000
    var c = SampleChannels(x: [], y: [], pressure: [], elapsedMs: [], tilt: nil, orientation: nil)
    for i in 0..<n {
        let t = Float(i) / Float(n)
        c.x.append(300 + 4200 * t + 150 * sin(t * 90)); c.y.append(1500 + 500 * sin(t * 40))
        c.pressure.append(0.3 + 0.5 * abs(sin(t * 25))); c.elapsedMs.append(UInt32(Double(i) * 1000 / 240))
    }
    let plan = try rig.plan(.pen, samples: n, custom: (Brush(family: .pen, size: .medium, colour: .ink), c))
    var worst: [Int] = Array(repeating: 0, count: 10)   // largest update per 1000 samples
    var updates = 0
    for step in previewSteps(plan) {
        try driverFeed(rig, step)
        guard case .samples(let first, _)? = step.preview?.body else { continue }
        let w = rig.engine.work.last
        worst[min(9, Int(first) / 1000)] = max(worst[min(9, Int(first) / 1000)], w)
        updates += 1
    }
    let bound = 2 * WetBuilder.maxTail + 8
    try check(worst.allSatisfy { $0 <= bound }, "work per update stays within a window (\(worst), bound \(bound))")
    try check(worst[8] <= worst[1] + 8, "work per update at 8000 samples is not above work at 1000 (\(worst[8]) vs \(worst[1]))")
    try check(rig.engine.currentFrame.strokes[0].pieces.count > 200, "long stroke is many pieces")
    // Newest data wins: a burst of frames sent without waiting is merged into fewer published frames.
    let burst = try rig.plan(.pen, samples: 2000)
    let before = rig.engine.currentFrame.version
    let frames = previewSteps(burst).count
    for step in previewSteps(burst) { try rig.driver.feed(step, at: monotonicNs()) }
    rig.engine.waitIdle()
    let published = rig.engine.currentFrame.version - before
    try check(published < UInt64(frames), "burst of \(frames) frames merged into \(published) published frames")
}

@MainActor private func driverFeed(_ rig: LiveRig, _ step: ReplayStep) throws {
    try rig.driver.feed(step, at: monotonicNs()); rig.engine.waitIdle()
}

@MainActor private func liveInkHover() throws {
    let rig = try LiveRig()
    let plan = try rig.plan(.pen)
    let hovers = plan.steps.filter { if case .hover? = $0.preview?.body { return true } else { return false } }
    try rig.feed(hovers.prefix(3))
    try check(rig.engine.currentFrame.hover != nil && rig.engine.currentFrame.strokes.isEmpty, "hover shows a dot")
    try check(inkPixels(try rig.render()) > 20, "hover dot has pixels")
    try rig.feed(previewSteps(plan).drop(while: { if case .begin = $0.preview!.body { return false } else { return true } }).prefix(1))
    try check(rig.engine.currentFrame.hover == nil, "hover disappears when a stroke starts")
    // A late hover frame during the stroke is ignored.
    try rig.feed([hovers[4]])
    try check(rig.engine.currentFrame.hover == nil, "no hover while the stroke is live")
    rig.engine.reset(); rig.engine.waitIdle()
    // Idle: gone after a short time.
    var next = try rig.plan(.pen)
    try rig.feed(next.steps.filter { if case .hover? = $0.preview?.body { return true } else { return false } }.prefix(2))
    try check(rig.engine.currentFrame.hover != nil, "hover again")
    rig.engine.tick(atNs: monotonicNs() + 500_000_000); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.hover != nil, "hover still shown after 0.5 s")
    rig.engine.tick(atNs: monotonicNs() + 1_500_000_000); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.hover == nil, "hover hides after a short idle")
    try rig.feed(next.steps.filter { if case .hover? = $0.preview?.body { return true } else { return false } }.prefix(2))
    rig.engine.reset(); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.hover == nil, "reset hides hover")
    // Hover for another note is refused.
    next = try rig.plan(.pen, noteId: newId())
    try rig.feed(next.steps.filter { if case .hover? = $0.preview?.body { return true } else { return false } }.prefix(2))
    try check(rig.engine.currentFrame.hover == nil && rig.engine.stats.refusedWrongNote > 0, "hover for another note is refused")
}

/// Stress: many strokes through the real pipeline, the replay driver in real time, then the logged numbers.
private final class DoneFlag: @unchecked Sendable {
    private let lock = NSLock(); private var done = false
    func set() { lock.lock(); done = true; lock.unlock() }
    var value: Bool { lock.lock(); defer { lock.unlock() }; return done }
}

@MainActor private func liveInkRealtimeReplay() throws {
    let rig = try LiveRig()
    let plan = try rig.plan(.pen)
    let finished = DoneFlag()
    rig.driver.play(plan) { _ in finished.set() }
    var frames = 0
    let deadline = Date().addingTimeInterval(15)
    while !(finished.value && rig.engine.stats.commitsSaved == 1) {
        if Date() > deadline { throw CheckFailure(description: "timed out waiting for real-time replay and commit") }
        // Take frames like a display callback would, presenting offscreen.
        let taken = rig.engine.take()
        if !taken.times.isEmpty || taken.frame.version > 0 {
            try rig.renderer.render(rig.model.drawing, wet: taken.frame, viewport: rig.viewport, dark: false,
                                    target: rig.texture, stage: taken.times.isEmpty ? nil : rig.engine.stageSink(taken.times))
            frames += 1
        }
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.001))
    }
    try check(rig.model.drawing?.strokes.count == 1, "real-time replay committed one stroke")
    let s = rig.engine.latency.summary()
    try check(s.count > 50, "latency measured for the replayed frames (\(s.count))")
    print("live-ink offscreen replay, received to GPU-complete: \(rig.engine.latency.describe()); rendered \(frames) frames")
    print("live-ink offscreen replay stages:\n\(rig.engine.describeStages())")
}

/// `arrival-adaptive`: which frames go through the Core Animation transaction. (The view-level pan trace is in the
/// app: `present-ab.sh` prints its `pan-sync` line from a release build.)
@MainActor private func presentPolicyChecks() throws {
    var policy = PresentPolicy()
    let size = CGSize(width: 1200, height: 800)
    var view = Viewport(left: 10, top: 20, zoom: 1.5)
    try check(!policy.transaction(viewport: view, size: size, animating: false), "adaptive: the first frame is an ordinary arrival")
    for i in 0..<5 { try check(!policy.transaction(viewport: view, size: size, animating: false), "adaptive: stable frame \(i) presents like arrival") }
    // A pan: every frame that changes the viewport is a transaction frame, and so is the first still one after it.
    for i in 1...8 {
        view.top += 7; view.left -= 3
        try check(policy.transaction(viewport: view, size: size, animating: false), "adaptive: pan frame \(i) goes through the transaction")
    }
    try check(policy.transaction(viewport: view, size: size, animating: false), "adaptive: one frame after the pan still does")
    try check(!policy.transaction(viewport: view, size: size, animating: false), "adaptive: then back to arrival")
    view.zoom = 1.8
    try check(policy.transaction(viewport: view, size: size, animating: false), "adaptive: a zoom step goes through the transaction")
    try check(policy.transaction(viewport: view, size: size, animating: false) && !policy.transaction(viewport: view, size: size, animating: false), "adaptive: settles after a zoom")
    try check(policy.transaction(viewport: view, size: CGSize(width: 1100, height: 800), animating: false), "adaptive: a resize goes through the transaction")
    _ = policy.transaction(viewport: view, size: CGSize(width: 1100, height: 800), animating: false)
    try check(!policy.transaction(viewport: view, size: CGSize(width: 1100, height: 800), animating: false), "adaptive: and settles")
    try check(policy.transaction(viewport: view, size: CGSize(width: 1100, height: 800), animating: true), "adaptive: an animation in flight counts as moving even when this frame did not move")
    try check(PresentMode.current == .arrivalAdaptive || ProcessInfo.processInfo.environment["SYNCR_MAC_PRESENT_MODE"] != nil, "adaptive is the default mode")
}

@MainActor func liveInkChecks() throws {
    try presentPolicyChecks()
    try liveInkEndToEnd()
    try liveInkCancelExpiryReset()
    try liveInkRefusals()
    try liveInkSwitchingNotes()
    try liveInkMalformed()
    try liveInkViewRelative()
    try liveInkHighlighter()
    try liveInkIncrementalBounded()
    try liveInkHover()
    try liveInkRealtimeReplay()
    try dryCachePixelChecks()
}


// MARK: - Dry-ink cache (contract mac-dry-cache-v1)

/// A drawing of `count` small pen scribbles (every 11th a highlighter) spread over the canvas, as a loaded note would hold them.
@MainActor private func dryDrawing(count: Int, samples: Int, scale: Float) throws -> Drawing {
    var strokes: [DrawStroke] = []
    var lowest: Float = 0
    for i in 0..<count {
        let highlighter = i % 11 == 10
        let brush = Brush(family: highlighter ? .highlighter : .pen, size: highlighter ? .large : .medium,
                          colour: [.ink, .blue, .red, .green][i % 4])
        let x = Float((i * 977) % 4300), y = Float((i * 613) % 2700)
        let c = try ReplayGenerator.channels(kind: highlighter ? .highlighter : .pen, origin: (x, y), scale: scale, samples: samples)
        let mesh = try tessellateStroke(samples: encodeSamples(channels: c), style: defaultStrokeStyle(brush: brush),
                                        transform: Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0))
        strokes.append(DrawStroke(mesh: mesh, originX: 0, originY: 0, colour: brush.colour, highlighter: highlighter))
        lowest = max(lowest, mesh.maxY)
    }
    return Drawing(title: "Dry cache", strokes: strokes, lowestInk: lowest)
}

private func readBack(_ texture: MTLTexture) -> [UInt8] {
    var bytes = [UInt8](repeating: 0, count: texture.width * texture.height * 4)
    texture.getBytes(&bytes, bytesPerRow: texture.width * 4, from: MTLRegionMake2D(0, 0, texture.width, texture.height), mipmapLevel: 0)
    return bytes
}

/// Largest per-channel difference, and how many pixels differ by more than 8/255 (about 3%).
private func compare(_ a: [UInt8], _ b: [UInt8]) -> (max: Int, over: Int) {
    var m = 0, over = 0
    for i in stride(from: 0, to: a.count, by: 4) {
        var d = 0
        for k in 0..<4 { d = max(d, abs(Int(a[i + k]) - Int(b[i + k]))) }
        m = max(m, d); if d > 8 { over += 1 }
    }
    return (m, over)
}

@MainActor func dryCachePixelChecks() throws {
    let rig = try LiveRig()
    let on = try MeshRenderer(device: rig.renderer.device, dryCache: true)
    let off = try MeshRenderer(device: rig.renderer.device, dryCache: false)
    try check(!off.dryCacheEnabled && on.dryCacheEnabled, "dry cache: kill switch is a renderer setting")
    try check(rig.renderer.dryCacheEnabled == (ProcessInfo.processInfo.environment["SYNCR_MAC_DRY_CACHE"] != "0"), "dry cache: SYNCR_MAC_DRY_CACHE=0 switches it off")
    let drawing = try dryDrawing(count: 60, samples: 60, scale: 0.2)
    let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: rigWidth, height: rigHeight, mipmapped: false)
    desc.usage = [.renderTarget, .shaderRead]; desc.storageMode = .shared
    guard let cached = rig.renderer.device.makeTexture(descriptor: desc), let plain = rig.renderer.device.makeTexture(descriptor: desc) else {
        throw CheckFailure(description: "Metal texture unavailable")
    }
    func pair(_ drawing: Drawing, wet: WetFrame?, dark: Bool = false, _ label: String) throws -> [UInt8] {
        try off.render(drawing, wet: wet, viewport: rig.viewport, dark: dark, target: plain)
        try on.render(drawing, wet: wet, viewport: rig.viewport, dark: dark, target: cached, cacheDry: true)
        let (m, over) = compare(readBack(cached), readBack(plain))
        print("dry cache pixels, \(label): max channel diff \(m), pixels over 8/255: \(over)")
        try check(over <= 200 && m <= 128, "dry cache: \(label) matches the uncached render (max \(m), \(over) pixels over 8/255)")
        return readBack(cached)
    }
    // Committed ink only, light and dark.
    let still = try pair(drawing, wet: nil, "committed only")
    try check(inkPixels(still) > 3000, "dry cache: the drawing has ink")
    _ = try pair(drawing, wet: nil, dark: true, "committed only, dark")
    // Wet pen, then a wet highlighter that crosses committed ink, mid-stroke so pieces are frozen and a tail is live.
    let pen = try rig.plan(.pen)
    try rig.feed(previewSteps(pen).prefix(60))
    let wetPen = rig.engine.currentFrame
    try check(wetPen.strokes.count == 1 && !wetPen.strokes[0].highlighter, "dry cache: a wet pen stroke is live")
    _ = try pair(drawing, wet: wetPen, "wet pen")
    let builds = on.dryCacheBuilds, hits = on.dryCacheHits
    rig.engine.reset(); rig.engine.waitIdle()
    let marker = try rig.plan(.highlighter)
    try rig.feed(previewSteps(marker).prefix(90))
    let wetMarker = rig.engine.currentFrame
    try check(wetMarker.strokes.count == 1 && wetMarker.strokes[0].highlighter, "dry cache: a wet highlighter is live")
    _ = try pair(drawing, wet: wetMarker, "wet highlighter over committed ink")
    try check(on.dryCacheHits > hits && on.dryCacheBuilds == builds, "dry cache: wet frames on a still view reuse the cache (builds \(on.dryCacheBuilds), hits \(on.dryCacheHits))")
    try check(on.lastPassCount == 2 && off.lastPassCount == 1, "dry cache: cached frame is build-free copy + wet pass; the switch keeps one pass")
    // Hover dot.
    rig.engine.reset(); rig.engine.waitIdle()
    try rig.feed(rig.plan(.pen).steps.prefix(3))
    _ = try pair(drawing, wet: rig.engine.currentFrame, "hover dot")

    // Pen-up: the stroke's mesh joins the drawing and the wet layer drops it, in the same frame. The very next frame must
    // show the stroke exactly once: equal to the uncached render, different from the frame before it.
    rig.engine.reset(); rig.engine.waitIdle()
    let up = try rig.plan(.pen)
    try rig.feed(previewSteps(up))
    let beforeUp = try pair(drawing, wet: rig.engine.currentFrame, "pen-up, last wet frame")
    let c = up.channels
    let mesh = try tessellateStroke(samples: encodeSamples(channels: c), style: defaultStrokeStyle(brush: Brush(family: .pen, size: .medium, colour: .blue)),
                                    transform: Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0))
    drawing.strokes.append(DrawStroke(mesh: mesh, originX: 0, originY: 0, colour: .blue, highlighter: false))
    let rebuilds = on.dryCacheBuilds
    let afterUp = try pair(drawing, wet: nil, "pen-up, first frame without the wet stroke")
    try check(on.dryCacheBuilds == rebuilds + 1, "dry cache: pen-up rebuilds the cache once")
    try check(inkPixels(afterUp) >= inkPixels(beforeUp) * 95 / 100, "dry cache: the stroke does not vanish at pen-up (\(inkPixels(afterUp)) vs \(inkPixels(beforeUp)) ink pixels)")
    _ = try pair(drawing, wet: nil, "pen-up, second frame")
    try check(on.dryCacheBuilds == rebuilds + 1, "dry cache: and then it is reused")

    // A moving view takes today's path and leaves the cache stale; the next still frame rebuilds it.
    let moved = Viewport(left: 40, top: 30, zoom: 1)
    try on.render(drawing, wet: nil, viewport: moved, dark: false, target: cached, cacheDry: false)
    try off.render(drawing, wet: nil, viewport: moved, dark: false, target: plain)
    try check(readBack(cached) == readBack(plain) && on.lastPassCount == 1, "dry cache: a moving frame is the uncached render, byte for byte")
    let stale = on.dryCacheBuilds
    _ = try pair(drawing, wet: nil, "first still frame after moving")
    try check(on.dryCacheBuilds == stale + 1, "dry cache: it is rebuilt once the view settles")
    // Kill switch: with the cache off, `cacheDry: true` changes nothing at all.
    try off.render(drawing, wet: nil, viewport: rig.viewport, dark: false, target: plain)
    let reference = readBack(plain)
    try off.render(drawing, wet: nil, viewport: rig.viewport, dark: false, target: cached, cacheDry: true)
    try check(readBack(cached) == reference && off.lastPassCount == 1, "dry cache: switched off, cacheDry changes nothing (one pass, same bytes)")
    try check(off.dryCacheBuilds == 0, "dry cache: switched off, no cache is ever built")
}

/// `swift run -c release SyncrMacChecks --dry-bench`: wet-frame GPU time, CPU encode time and received-to-GPU-complete time
/// on a light (about 50 strokes) and a heavy (about 5,000 strokes) note, dry cache on and off. About 2,000 pen samples per
/// run go through the real engine (ingest, tessellation, publish) and are drawn offscreen at the drawable's size, still view.
@MainActor func dryCacheBench() throws {
    let width = Int(ProcessInfo.processInfo.environment["BENCH_WIDTH"] ?? "") ?? 2880, height = Int(ProcessInfo.processInfo.environment["BENCH_HEIGHT"] ?? "") ?? 1800
    let rig = try LiveRig()
    let device = rig.renderer.device
    let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: width, height: height, mipmapped: false)
    desc.usage = [.renderTarget, .shaderRead]; desc.storageMode = .shared
    guard let target = device.makeTexture(descriptor: desc) else { throw CheckFailure(description: "Metal texture unavailable") }
    final class Samples: @unchecked Sendable {
        private let lock = NSLock(); private var gpu: [Double] = [], encode: [Double] = []
        func add(_ e: Double, _ g: Double) { lock.lock(); encode.append(e); gpu.append(g); lock.unlock() }
        var values: (gpu: [Double], encode: [Double]) { lock.lock(); defer { lock.unlock() }; return (gpu, encode) }
    }
    func pct(_ v: [Double], _ p: Double) -> Double { let s = v.sorted(); return s.isEmpty ? 0 : s[min(s.count - 1, Int((Double(s.count - 1) * p).rounded()))] }
    print("dry cache bench: target \(width)x\(height)")
    print("| note | strokes | cache | wet frames | GPU p50 ms | GPU p95 ms | encode p50 ms | encode p95 ms | input-to-presented p50 ms | input-to-presented p95 ms |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for (name, count) in [("light", 50), ("heavy", 5000)] {
        let drawing = try dryDrawing(count: count, samples: 40, scale: count > 500 ? 0.09 : 0.2)
        for cache in [false, true] {
            let renderer = try MeshRenderer(device: device, dryCache: cache)
            let samples = Samples()
            renderer.frameTimes = { e, g, _ in samples.add(e, g) }
            rig.engine.reset(); rig.engine.waitIdle(); rig.engine.resetLatency()
            var frames = 0, penSamples = 0
            // One warm-up stroke (not counted), then five strokes of about 430 samples.
            for round in 0..<6 {
                let plan = try rig.plan(.pen)
                if round == 1 { rig.engine.resetLatency(); _ = samples.values }
                for step in plan.steps where step.preview != nil {
                    try rig.driver.feed(step, at: monotonicNs()); rig.engine.waitIdle()
                    let taken = rig.engine.take()
                    guard !taken.times.isEmpty else { continue }
                    try renderer.render(drawing, wet: taken.frame, viewport: rig.viewport, dark: false, target: target,
                                        stage: rig.engine.stageSink(taken.times), cacheDry: true)
                    if round > 0 { frames += 1; penSamples += 4 }
                }
                rig.engine.reset(); rig.engine.waitIdle()
                if round == 0 { rig.engine.resetLatency() }
            }
            let v = samples.values
            let skip = max(0, v.gpu.count - frames)
            let gpu = Array(v.gpu[skip...]), enc = Array(v.encode[skip...])
            let lat = rig.engine.latency.summary()
            print(String(format: "| %@ | %d | %@ | %d (%d samples) | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |", name, count, cache ? "on" : "off", frames, penSamples,
                         pct(gpu, 0.5), pct(gpu, 0.95), pct(enc, 0.5), pct(enc, 0.95), lat.p50, lat.p95))
        }
    }
}
