import Foundation
import Metal
import SyncrCore

/// Monotonic nanoseconds (the clock `drawable.presentedTime` is on). Used for every receive and
/// present timestamp, so differences are meaningful; the absolute value is not.
func monotonicNs() -> UInt64 { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) }

extension Viewport {
    /// View-relative units are the 4800 x 3000 reference window at this zoom, anchored at the
    /// viewport's top-left. Independent of the actual pixel size of the view. The centred document
    /// view shows space left of the canvas (`left` below zero); that space is not paper, so a point
    /// there lands on the canvas's left edge and ink never leaves the 0...4800 the phone can show.
    func canvasPoint(_ x: Float, _ y: Float) -> (Float, Float) { (max(0, left + x / zoom), top + y / zoom) }
}

/// What a live-ink frame is allowed to draw into: the note open in the active tab, if writable.
/// The future session layer will tell the phone this note's ID and sheet ID; until then frames are
/// matched against it here and everything else is dropped and counted.
struct LiveTarget: Equatable, Sendable {
    var noteId: String?
    var writable: Bool
    /// Mirror mode: the note is a read-only phone copy and canvas-space previews for it are drawn (never
    /// saved). Stroke-commits and view-relative frames (Tablet Stream) still need a writable note.
    var mirror = false
}

enum PresentMode: String {
    /// CAMetalDisplayLink, one callback per refresh.
    case link
    /// Draw when a wet frame is published; no vsync wait on the layer.
    case arrival
    case arrivalSync = "arrival-sync"
    /// Arrival with presentsWithTransaction: presented in step with the window's Core Animation commit.
    case arrivalTransaction = "arrival-transaction"
    /// `arrival` while the view's transform is stable, transaction presentation only while it moves (`PresentPolicy`).
    case arrivalAdaptive = "arrival-adaptive"
    static var current: PresentMode {
        // The editor combines AppKit text with Metal ink. Transaction presentation keeps both on the same viewport
        // during rapid pan/zoom direction changes; it costs about 20 ms, so the default pays it only while moving.
        // `SYNCR_MAC_PRESENT_MODE=arrival-transaction` restores transaction presentation for every frame.
        ProcessInfo.processInfo.environment["SYNCR_MAC_PRESENT_MODE"].flatMap(PresentMode.init(rawValue:)) ?? .arrivalAdaptive
    }
}

/// Decides, per presented frame, whether `arrival-adaptive` presents with the window's Core Animation transaction.
/// A frame is "moving" when its viewport or the canvas size differs from the previous frame's, or an animation (slide,
/// live resize) is running. Moving frames and the one frame after them go through the transaction, so text, ink, margin
/// content and the pad outline always change viewport together; a stable view presents like `arrival`. It only looks at
/// frames already being drawn: nothing here schedules a frame.
struct PresentPolicy {
    private var last: Viewport?
    private var lastSize = CGSize.zero
    private var settling = false
    mutating func transaction(viewport: Viewport, size: CGSize, animating: Bool) -> Bool {
        let moving = animating || (last.map { $0 != viewport } ?? false) || (lastSize != .zero && lastSize != size)
        defer { last = viewport; lastSize = size; settling = moving }
        return moving || settling
    }
}

enum LiveRefusal: String, Equatable, Sendable { case noNote, readOnly, wrongNote }

struct LiveStats: Equatable, Sendable {
    var framesReceived = 0
    var malformed = 0
    var refusedNoNote = 0, refusedReadOnly = 0, refusedWrongNote = 0
    var droppedCancelled = 0, droppedExpired = 0, droppedGap = 0, droppedReset = 0, droppedOther = 0
    var commitsReassembled = 0, commitsSaved = 0, commitsRefused = 0, commitsFailed = 0, duplicateCommits = 0
    var tessellationErrors = 0
    var resets = 0
    var lastRefusal: LiveRefusal?
    var lastRefusalNs: UInt64 = 0
    var lastError: String?
    var refused: Int { refusedNoNote + refusedReadOnly + refusedWrongNote }
}

/// Rolling window of frame-received to presented times. Software side only.
final class LatencyStats: @unchecked Sendable {
    struct Summary: Equatable, Sendable { var count = 0, p50 = 0.0, p95 = 0.0, max = 0.0 }
    static let scope = "software side only: frame received to drawable presented; excludes pen, Wi-Fi and display scan-out"
    private let lock = NSLock()
    private var ring: [Double] = []
    private var next = 0
    private let capacity = 1024

    func record(ms: Double) {
        lock.lock(); defer { lock.unlock() }
        if ring.count < capacity { ring.append(ms) } else { ring[next] = ms; next = (next + 1) % capacity }
    }
    func reset() { lock.lock(); ring = []; next = 0; lock.unlock() }
    func summary() -> Summary {
        lock.lock(); let values = ring.sorted(); lock.unlock()
        guard !values.isEmpty else { return Summary() }
        func percentile(_ p: Double) -> Double { values[min(values.count - 1, Int((Double(values.count - 1) * p).rounded()))] }
        return Summary(count: values.count, p50: percentile(0.5), p95: percentile(0.95), max: values[values.count - 1])
    }
    func describe() -> String {
        let s = summary()
        guard s.count > 0 else { return "no frames measured" }
        return String(format: "p50 %.1f / p95 %.1f / max %.1f ms over %d frames (%@)", s.p50, s.p95, s.max, s.count, Self.scope)
    }
}

/// One immutable chunk of a live stroke, already in GPU buffers (built off the main thread).
final class WetPiece: @unchecked Sendable {
    let vertices: MTLBuffer, indices: MTLBuffer
    let indexCount: Int
    let minX: Float, minY: Float, maxX: Float, maxY: Float
    init?(mesh: StrokeMesh, device: MTLDevice) {
        guard !mesh.vertices.isEmpty, !mesh.indices.isEmpty else { return nil }
        let v = mesh.vertices.withUnsafeBytes { device.makeBuffer(bytes: $0.baseAddress!, length: $0.count) }
        let i = mesh.indices.withUnsafeBytes { device.makeBuffer(bytes: $0.baseAddress!, length: $0.count) }
        guard let v, let i else { return nil }
        vertices = v; indices = i; indexCount = mesh.indices.count / 4
        minX = mesh.minX; minY = mesh.minY; maxX = mesh.maxX; maxY = mesh.maxY
    }
}

struct WetStroke: @unchecked Sendable {
    let id: String
    let colour: InkColour
    let highlighter: Bool
    let pieces: [WetPiece]
    var custom: Oklch? = nil
}

struct HoverDot: Equatable, Sendable { var x: Float, y: Float }

/// What the renderer draws over the committed strokes. Replaced as a whole; newest wins.
struct WetFrame: @unchecked Sendable {
    var strokes: [WetStroke] = []
    var hover: HoverDot?
    var version: UInt64 = 0
    var isEmpty: Bool { strokes.isEmpty && hover == nil }
}

/// Incremental tessellation of one live stroke. The core tessellates a whole stroke from a blob, so
/// the stroke is cut into windows: a tail window is re-tessellated when samples arrive, and once it
/// reaches `maxTail` samples it is frozen as a finished piece and a new tail starts `overlap`
/// samples back. The overlap spans the start and end tapers, so each piece's tapered ends lie under
/// its neighbour's full-width body and the union looks like one stroke. Cost per update is bounded
/// by the window size plus the new samples, not by the stroke length. The committed stroke is later
/// tessellated whole from disk, as when the note is reopened; the live joins are only a preview.
final class WetBuilder {
    static let maxTail = 64
    static let maxOverlap = 32
    let id: String
    let brush: Brush
    let highlighter: Bool
    private let view: Viewport?
    private let style: StrokeStyle
    private let device: MTLDevice
    private let overlapDistance: Float
    private var x: [Float] = [], y: [Float] = [], pressure: [Float] = [], elapsed: [UInt32] = []
    private var frozen: [WetPiece] = []
    private var tail: WetPiece?
    private var anchor = 0
    private var work = 0
    var ended = false
    private(set) var lastWork = 0
    private(set) var totalWork = 0
    var count: Int { x.count }

    init(id: String, brush: Brush, space: CoordinateSpace, view: Viewport, device: MTLDevice) {
        self.id = id; self.brush = brush; self.device = device
        self.view = space == .viewRelative ? view : nil
        style = defaultStrokeStyle(brush: brush)
        highlighter = style.discardSelfOverlap
        overlapDistance = style.size * (style.taperStartSizes + style.taperEndSizes) * 1.5 + style.size * 2
    }

    func append(_ c: SampleChannels) {
        let n = min(c.x.count, c.y.count, c.pressure.count, c.elapsedMs.count)
        for i in 0..<n {
            if let view {
                let (px, py) = view.canvasPoint(c.x[i], c.y[i]); x.append(px); y.append(py)
            } else { x.append(c.x[i]); y.append(c.y[i]) }
            pressure.append(c.pressure[i]); elapsed.append(c.elapsedMs[i])
        }
    }

    private func tessellate(_ range: Range<Int>) throws -> WetPiece? {
        let channels = SampleChannels(x: Array(x[range]), y: Array(y[range]), pressure: Array(pressure[range]),
                                      elapsedMs: Array(elapsed[range]), tilt: nil, orientation: nil)
        let mesh = try tessellateStroke(samples: encodeSamples(channels: channels), style: style,
                                        transform: Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0))
        work += range.count
        return WetPiece(mesh: mesh, device: device)
    }

    private func lookBack(from end: Int) -> Int {
        var i = end - 1, distance: Float = 0, steps = 0
        while i > 0, steps < Self.maxOverlap, distance < overlapDistance {
            distance += hypot(x[i] - x[i - 1], y[i] - y[i - 1]); i -= 1; steps += 1
        }
        return max(0, i)
    }

    /// Tessellates whatever is new. Throws on a core error; the caller drops the stroke.
    func rebuild() throws {
        guard !x.isEmpty else { return }
        work = 0
        while x.count - anchor > Self.maxTail {
            let end = anchor + Self.maxTail
            if let piece = try tessellate(anchor..<end) { frozen.append(piece) }
            anchor = lookBack(from: end)
        }
        tail = try tessellate(anchor..<x.count)
        lastWork = work; totalWork += work
    }

    var stroke: WetStroke {
        WetStroke(id: id, colour: brush.colour, highlighter: highlighter, pieces: frozen + (tail.map { [$0] } ?? []),
                  custom: brush.customInk)
    }
}

/// The live-ink engine: encoded frame bytes in, a `WetFrame` and committed strokes out. No sockets
/// and no UI. `receive` may be called from any queue. Everything else of the pipeline (decode,
/// receiver, reassembly, tessellation, GPU buffer creation) runs on one serial queue, so neither the
/// main thread nor the display callback ever waits on it. The display side takes the latest frame
/// with `take()`: frames that arrive faster than the display refreshes are merged, because the
/// queue drains everything pending and tessellates once per stroke.
final class LiveInk: @unchecked Sendable {
    static let hoverIdleNs: UInt64 = 1_000_000_000
    static let tickNs: Double = 0.1
    let device: MTLDevice
    let latency = LatencyStats()
    /// Per-stage times, each measured from the frame's receive time. Names in `stageOrder`.
    static let stageOrder = ["ingest start", "published", "draw start", "encoded", "scheduled", "gpu done",
                             "presented", "presented (handler time)", "first frame after idle: presented"]
    let stages: [String: LatencyStats] = Dictionary(uniqueKeysWithValues: LiveInk.stageOrder.map { ($0, LatencyStats()) })
    /// Set before the first frame. Called on the ingest queue after a new frame is published.
    var onWake: (@Sendable () -> Void)?
    /// A complete stroke commit for the active writable note, with the viewport frozen at its Begin.
    var onCommit: (@Sendable (StrokeCommit, Viewport?) -> Void)?
    /// Counters or refusal reasons changed. Called on the ingest queue.
    var onStatus: (@Sendable () -> Void)?

    private struct Item { let bytes: Data; let ns: UInt64; let view: Viewport }
    private let ingest = DispatchQueue(label: "dev.syncr.mac.liveink", qos: .userInteractive)
    private let lock = NSLock()
    // Guarded by `lock`.
    private var inbox: [Item] = []
    private var draining = false
    private var lastReceiveNs: UInt64 = 0
    /// True while the phone's pen is writing or hovering: a live frame arrived within `ns`. The link holds its bulk
    /// traffic (note copies) back meanwhile, because it shares the phone's radio and its one connection with the ink.
    func penActive(within ns: UInt64 = 400_000_000) -> Bool {
        lock.lock(); let last = lastReceiveNs; lock.unlock()
        return last != 0 && monotonicNs() &- last < ns
    }
    private var target = LiveTarget(noteId: nil, writable: false)
    private var view = Viewport()
    private var viewsByRev: [UInt32: Viewport] = [:]
    private var viewRevOrder: [UInt32] = []
    private var clockOffsetMs: Double?
    private var hoverCanvas = false
    private var openStrokes = 0
    private var sentTimes: [UInt64] = []
    private var statsValue = LiveStats()
    private var frame = WetFrame()
    private var times: [UInt64] = []
    private var removed: [String] = []
    private var epochValue: UInt32 = 1
    private var workValue = (last: 0, max: 0)
    // Confined to `ingest`.
    private var receiver = PreviewReceiver(epoch: 1)
    private var reassembler = StrokeReassembler()
    private var builders: [String: WetBuilder] = [:]
    private var order: [String] = []
    private var hover: (x: Float, y: Float, ns: UInt64)?
    private var begunViews: [String: Viewport] = [:]
    private var begunOrder: [String] = []
    private var commitSeen: Set<String> = []
    private var commitOrder: [String] = []
    private var tickScheduled = false
    private var pendingSent: [UInt64] = []

    /// Phone socket write to Mac presented, when a clock offset is known (software and Wi-Fi side only).
    let phoneLatency = LatencyStats()
    static let phoneScope = "phone socket write to Mac drawable presented, clock offset from the lowest-round-trip ping; excludes pen sampling, the phone's own queueing before the write, and display scan-out"
    /// A stroke's End arrived (ingest queue). For the debug latency log.
    var onStrokeEnded: (@Sendable () -> Void)?
    /// A stroke began with this brush (ingest queue): the pen the phone is writing with now. For the status bar's pen glyph.
    var onPenSeen: (@Sendable (Brush) -> Void)?

    init(device: MTLDevice) { self.device = device }

    // MARK: Thread-safe inputs and outputs

    var stats: LiveStats { lock.lock(); defer { lock.unlock() }; return statsValue }
    var epoch: UInt32 { lock.lock(); defer { lock.unlock() }; return epochValue }
    var currentFrame: WetFrame { lock.lock(); defer { lock.unlock() }; return frame }
    /// Tessellation work (samples) of the last update and the largest update so far.
    var work: (last: Int, max: Int) { lock.lock(); defer { lock.unlock() }; return workValue }

    func setTarget(noteId: String?, writable: Bool, mirror: Bool = false) {
        let new = LiveTarget(noteId: noteId, writable: writable, mirror: mirror && !writable)
        lock.lock(); let old = target; target = new; lock.unlock()
        guard old.noteId != new.noteId else { return }
        ingest.async { [self] in
            builders.removeAll(); order.removeAll(); hover = nil
            publish(times: [])
        }
    }

    func setView(_ v: Viewport) { lock.lock(); view = v; lock.unlock() }

    /// The pad is in Local mode: its hover positions are canvas units, not view-relative ones.
    func setHoverCanvas(_ on: Bool) { lock.lock(); hoverCanvas = on; lock.unlock() }

    /// True while a stroke is being written (its Begin arrived, its End or drop has not).
    var strokeOpen: Bool { lock.lock(); defer { lock.unlock() }; return openStrokes > 0 }

    /// Remembers the viewport the phone was told about as `rev` (Tablet Stream), so a Begin that names it
    /// is placed with that viewport even if the view has scrolled on since. A bounded history.
    func recordView(_ v: Viewport, rev: UInt32) {
        lock.lock()
        if viewsByRev.updateValue(v, forKey: rev) == nil { viewRevOrder.append(rev) }
        while viewRevOrder.count > 128 { viewsByRev[viewRevOrder.removeFirst()] = nil }
        lock.unlock()
    }

    func clearRecordedViews() { lock.lock(); viewsByRev = [:]; viewRevOrder = []; lock.unlock() }

    /// Offset of the phone's monotonic clock from ours (phone minus Mac, ms), nil when unknown.
    func setClockOffset(_ ms: Double?) { lock.lock(); clockOffsetMs = ms; lock.unlock() }
    var clockOffset: Double? { lock.lock(); defer { lock.unlock() }; return clockOffsetMs }

    private func resolvedView(rev: UInt32, fallback: Viewport) -> Viewport {
        lock.lock(); defer { lock.unlock() }
        return viewsByRev[rev] ?? fallback
    }

    /// The only input: one encoded frame and when it arrived (`monotonicNs()` clock).
    func receive(_ bytes: Data, at ns: UInt64) {
        lock.lock()
        lastReceiveNs = ns
        inbox.append(Item(bytes: bytes, ns: ns, view: view))
        let schedule = !draining
        draining = true
        lock.unlock()
        if schedule { ingest.async { [self] in drain() } }
    }

    /// Simulated disconnect: previews, hover, reassembly and wet ink are cleared; the next epoch is adopted.
    /// `epoch`: adopt this one (a new link connection) instead of the next number; `wait`: return only once
    /// it has taken effect (never from the ingest queue), so frames sent right after are read in the new epoch.
    func reset(epoch: UInt32? = nil, wait: Bool = false) {
        let work: @Sendable () -> Void = { [self] in
            lock.lock(); epochValue = epoch ?? epochValue &+ 1; let next = epochValue; removed.removeAll(); statsValue.resets += 1; lock.unlock()
            _ = receiver.reset(epoch: next)
            reassembler.reset()
            builders.removeAll(); order.removeAll(); hover = nil
            begunViews.removeAll(); begunOrder.removeAll()
            publish(times: [])
            notifyStatus()
        }
        if wait { ingest.sync(execute: work) } else { ingest.async(execute: work) }
    }

    /// Expiry and hover idle at an explicit time. A timer calls this with the real clock while anything is live.
    func tick(atNs ns: UInt64) { ingest.async { [self] in runTick(ns) } }

    /// Blocks until everything received so far has been processed. For checks and replay teardown only.
    func waitIdle() { ingest.sync {} }

    /// The newest frame and the receive times of every frame it merges. Never blocks on tessellation.
    func take() -> (frame: WetFrame, times: [UInt64], sent: [UInt64]) {
        lock.lock(); defer { lock.unlock() }
        let t = times; times = []
        let sent = sentTimes; sentTimes = []
        let now = monotonicNs()
        for r in t where now >= r { stages["draw start"]!.record(ms: Double(now - r) / 1e6) }
        return (frame, t, sent)
    }

    func recordPresented(_ receiveTimes: [UInt64], at ns: UInt64) {
        for t in receiveTimes where ns >= t { latency.record(ms: Double(ns - t) / 1e6) }
    }

    func recordStage(_ name: String, _ receiveTimes: [UInt64], at ns: UInt64) {
        guard let stat = stages[name] else { return }
        for t in receiveTimes where ns >= t { stat.record(ms: Double(ns - t) / 1e6) }
    }

    /// A renderer stage callback for one drawn frame: records every stage, and the presented ones as the
    /// headline latency. `cold`: the frame had to wake an idle display link.
    func stageSink(_ receiveTimes: [UInt64], cold: Bool = false, sent: [UInt64] = []) -> @Sendable (String, UInt64) -> Void {
        { [self] name, ns in
            recordStage(name, receiveTimes, at: ns)
            if name.hasPrefix("presented") {
                recordPresented(receiveTimes, at: ns)
                for t in sent { phoneLatency.record(ms: (Double(ns) - Double(t)) / 1e6) }
                if cold { recordStage("first frame after idle: presented", receiveTimes, at: ns) }
            }
        }
    }

    func resetLatency() { latency.reset(); phoneLatency.reset(); stages.values.forEach { $0.reset() } }

    /// Drops previews whose strokes are now part of the shown note, because its newer copy was just swapped
    /// in. Call in the same main-thread block that swaps the drawing, so no draw sees a stroke twice or
    /// in neither place. `ids` includes strokes the copy holds as deleted, so an erased stroke's preview goes
    /// too. Strokes not in `ids` (still being written, or saved after that copy) stay.
    func finishMirror(strokeIds ids: Set<String>) {
        lock.lock()
        let published = frame.strokes.map(\.id).filter(ids.contains)
        removed.append(contentsOf: published); if removed.count > 256 { removed.removeFirst(removed.count - 256) }
        if !published.isEmpty { frame.strokes.removeAll { ids.contains($0.id) }; frame.version += 1 }
        lock.unlock()
        ingest.async { [self] in
            var dropped: [String] = []
            for id in order where ids.contains(id) { dropped.append(id) }
            guard !dropped.isEmpty || !published.isEmpty else { return }
            lock.lock(); removed.append(contentsOf: dropped); if removed.count > 256 { removed.removeFirst(removed.count - 256) }; lock.unlock()
            for id in dropped { builders.removeValue(forKey: id); order.removeAll { $0 == id } }
            for id in Set(dropped + published) { _ = try? receiver.commit(strokeId: id) }
            publish(times: [])
        }
    }

    func describeStages() -> String {
        Self.stageOrder.compactMap { name -> String? in
            let s = stages[name]!.summary()
            return s.count == 0 ? nil : String(format: "  %-34@ p50 %6.1f  p95 %6.1f  max %6.1f ms  (n=%d)", name as NSString, s.p50, s.p95, s.max, s.count)
        }.joined(separator: "\n")
    }

    enum CommitOutcome: Equatable, Sendable { case saved, failed(String), refused }

    /// The Mac wrote, failed to write or refused a committed stroke. Removes its preview from the
    /// published frame immediately, under the same lock `take()` uses, so the caller can add the
    /// committed stroke to the drawing and call this in one main-thread block: the next draw has the
    /// one and not the other. A failed or refused stroke leaves no ghost ink either.
    func finishCommit(strokeId: String, _ outcome: CommitOutcome) {
        lock.lock()
        removed.append(strokeId); if removed.count > 256 { removed.removeFirst(removed.count - 256) }
        if frame.strokes.contains(where: { $0.id == strokeId }) {
            frame.strokes.removeAll { $0.id == strokeId }; frame.version += 1
        }
        switch outcome {
        case .saved: statsValue.commitsSaved += 1; statsValue.lastError = nil
        case .failed(let message): statsValue.commitsFailed += 1; statsValue.lastError = message
        case .refused: statsValue.commitsRefused += 1
        }
        lock.unlock()
        notifyStatus()
        ingest.async { [self] in
            if builders.removeValue(forKey: strokeId) != nil { order.removeAll { $0 == strokeId } }
            if outcome != .saved { commitSeen.remove(strokeId) }
            _ = try? receiver.commit(strokeId: strokeId)
        }
    }

    // MARK: Ingest queue

    private func syncOpen() {
        let open = builders.values.reduce(0) { $0 + ($1.ended ? 0 : 1) }
        lock.lock(); openStrokes = open; lock.unlock()
    }

    private func bump(_ change: (inout LiveStats) -> Void) { lock.lock(); change(&statsValue); lock.unlock() }
    private func notifyStatus() { onStatus?() }

    /// `mirrorSpace`: set for a preview frame's space; a read-only copy shows canvas previews in mirror mode.
    private func refusal(for noteId: String, mirrorSpace: CoordinateSpace? = nil) -> LiveRefusal? {
        lock.lock(); let t = target; lock.unlock()
        guard let active = t.noteId else { return .noNote }
        if active != noteId { return .wrongNote }
        if t.writable { return nil }
        return t.mirror && mirrorSpace == .canvas ? nil : .readOnly
    }

    private func refuse(_ why: LiveRefusal, commit: Bool = false) {
        bump {
            switch why {
            case .noNote: $0.refusedNoNote += 1
            case .readOnly: $0.refusedReadOnly += 1
            case .wrongNote: $0.refusedWrongNote += 1
            }
            if commit { $0.commitsRefused += 1 }
            $0.lastRefusal = why; $0.lastRefusalNs = monotonicNs()
        }
        notifyStatus()
    }

    private func drain() {
        while true {
            lock.lock()
            let items = inbox; inbox = []
            if items.isEmpty { draining = false; lock.unlock(); return }
            lock.unlock()
            let started = monotonicNs()
            var dirty: [String] = [], changed = false, visible: [UInt64] = []
            for item in items { process(item, dirty: &dirty, changed: &changed, visible: &visible) }
            var rebuilt = false
            for id in Set(dirty) {
                guard let builder = builders[id] else { continue }
                do {
                    try builder.rebuild(); rebuilt = true
                    lock.lock(); workValue.last = builder.lastWork; workValue.max = max(workValue.max, builder.lastWork); lock.unlock()
                } catch {
                    builders.removeValue(forKey: id); order.removeAll { $0 == id }
                    bump { $0.tessellationErrors += 1; $0.lastError = error.localizedDescription }
                    changed = true
                }
            }
            if rebuilt || changed {
                for t in visible where started >= t { stages["ingest start"]!.record(ms: Double(started - t) / 1e6) }
                publish(times: visible)
                let now = monotonicNs()
                for t in visible where now >= t { stages["published"]!.record(ms: Double(now - t) / 1e6) }
            } else { syncOpen() }
            scheduleTick()
        }
    }

    private func process(_ item: Item, dirty: inout [String], changed: inout Bool, visible: inout [UInt64]) {
        bump { $0.framesReceived += 1 }
        let limit = Int(previewLimits().maxFrameBytes)
        guard item.bytes.count <= limit, let kind = try? frameKind(bytes: item.bytes) else {
            bump { $0.malformed += 1 }; notifyStatus(); return
        }
        let ms = item.ns / 1_000_000
        do {
            switch kind {
            case .preview:
                apply(try receiver.accept(frame: item.bytes, nowMs: ms), item, &dirty, &changed, &visible)
            case .strokeChunk:
                if let commit = try reassembler.accept(frame: item.bytes, nowMs: ms) { handle(commit, item) }
            }
        } catch {
            bump { $0.malformed += 1 }; notifyStatus()
        }
    }

    private func apply(_ events: [PreviewEvent], _ item: Item, _ dirty: inout [String], _ changed: inout Bool, _ visible: inout [UInt64]) {
        for event in events {
            switch event {
            case .strokeStarted(let id, let noteId, _, let brush, let rev, let space):
                if hover != nil { hover = nil; changed = true }
                // The viewport the phone was told about as `rev` when it is still known, else the one at Begin.
                let view = space == .viewRelative ? resolvedView(rev: rev, fallback: item.view) : item.view
                begunViews[id] = view; begunOrder.append(id)
                while begunOrder.count > 64 { begunViews[begunOrder.removeFirst()] = nil }
                if let why = refusal(for: noteId, mirrorSpace: space) { refuse(why); continue }
                lock.lock(); let blocked = removed.contains(id); lock.unlock()
                if blocked || builders[id] != nil { continue }
                builders[id] = WetBuilder(id: id, brush: brush, space: space, view: view, device: device)
                order.append(id)
                onPenSeen?(brush)
            case .samplesAppended(let id, _, let channels, let sentMs):
                guard let builder = builders[id] else { continue }
                builder.append(channels); dirty.append(id); visible.append(item.ns)
                lock.lock(); let offset = clockOffsetMs; lock.unlock()
                if let offset, sentMs > 0 { pendingSent.append(UInt64(max(0, (Double(sentMs) - offset) * 1e6))) }
            case .strokeEnded(let id, _):
                builders[id]?.ended = true
                if builders[id] != nil { onStrokeEnded?() }
            case .dropped(let id, let reason):
                bump {
                    switch reason {
                    case .cancelled: $0.droppedCancelled += 1
                    case .expired: $0.droppedExpired += 1
                    case .gap: $0.droppedGap += 1
                    case .reset: $0.droppedReset += 1
                    default: $0.droppedOther += 1
                    }
                }
                if builders.removeValue(forKey: id) != nil { order.removeAll { $0 == id }; changed = true }
            case .hoverMoved(let noteId, _, let x, let y, _):
                if builders.values.contains(where: { !$0.ended }) { continue }
                if let why = refusal(for: noteId) { refuse(why); continue }
                lock.lock(); let canvas = hoverCanvas; lock.unlock()
                let (cx, cy) = canvas ? (x, y) : item.view.canvasPoint(x, y)
                hover = (cx, cy, item.ns); changed = true; visible.append(item.ns)
            }
        }
    }

    private func handle(_ commit: StrokeCommit, _ item: Item) {
        bump { $0.commitsReassembled += 1 }
        if commitSeen.contains(commit.strokeId) { bump { $0.duplicateCommits += 1 }; notifyStatus(); return }
        if let why = refusal(for: commit.noteId) { refuse(why, commit: true); return }
        commitSeen.insert(commit.strokeId); commitOrder.append(commit.strokeId)
        while commitOrder.count > 1024 { commitSeen.remove(commitOrder.removeFirst()) }
        onCommit?(commit, begunViews[commit.strokeId] ?? item.view)
    }

    private func runTick(_ ns: UInt64) {
        var changed = false
        let events = receiver.tick(nowMs: ns / 1_000_000)
        var dirty: [String] = [], visible: [UInt64] = []
        apply(events, Item(bytes: Data(), ns: ns, view: Viewport()), &dirty, &changed, &visible)
        if let h = hover, ns >= h.ns, ns - h.ns >= Self.hoverIdleNs { hover = nil; changed = true }
        if changed { publish(times: []) }
        scheduleTick()
    }

    /// Only while something is live: no timer runs for an idle view.
    private func scheduleTick() {
        guard !tickScheduled, !builders.isEmpty || hover != nil else { return }
        tickScheduled = true
        ingest.asyncAfter(deadline: .now() + Self.tickNs) { [self] in tickScheduled = false; runTick(monotonicNs()) }
    }

    private func publish(times visible: [UInt64]) {
        syncOpen()
        lock.lock()
        let blocked = Set(removed)
        var next = WetFrame(strokes: order.compactMap { id in blocked.contains(id) ? nil : builders[id]?.stroke },
                            hover: hover.map { HoverDot(x: $0.x, y: $0.y) }, version: frame.version + 1)
        if next.strokes.isEmpty && next.hover == nil && frame.isEmpty { next.version = frame.version }
        frame = next
        times.append(contentsOf: visible)
        sentTimes.append(contentsOf: pendingSent); pendingSent = []
        lock.unlock()
        onWake?()
    }
}
