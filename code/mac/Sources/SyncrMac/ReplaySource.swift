import Foundation
import os
import SyncrCore

/// In-process stand-in for the phone: generates realistic pen strokes, encodes them with the core,
/// pushes them through a real `PreviewSendQueue`, pops the bytes and feeds the engine as a socket
/// reader would. This is the only source of live ink until a transport exists. No network code.
enum ReplayKind: String, CaseIterable, Sendable {
    case pen, highlighter, cancelled, viewRelative
}

struct ReplayStep: @unchecked Sendable {
    let atMs: Double
    let preview: PreviewFrame?
    let commit: StrokeCommit?
}

struct ReplayPlan: @unchecked Sendable {
    let kind: ReplayKind
    let strokeId: String
    let steps: [ReplayStep]
    /// What the phone sent, in the stroke's own coordinate space.
    let channels: SampleChannels
    var durationMs: Double { steps.last?.atMs ?? 0 }
}

enum ReplayGenerator {
    static let hz = 240.0
    static let batch = 4  // samples per frame: a 60 Hz input batch of a 240 Hz pen
    /// Hover has one counter for the whole session, as on the phone, so replays can follow each other.
    private static let hoverCounter = OSAllocatedUnfairLock(initialState: UInt32(0))

    /// A handwriting-like path: a rightward run with loops, pressure rising and falling with a
    /// slow wobble. `scale` shrinks canvas strokes so they fit a zoomed viewport. The highlighter
    /// doubles back across itself so self-overlap is exercised.
    static func channels(kind: ReplayKind, origin: (Float, Float), scale: Float, samples: Int? = nil) throws -> SampleChannels {
        let n = samples ?? (kind == .highlighter ? 480 : 432)
        var c = SampleChannels(x: [], y: [], pressure: [], elapsedMs: [], tilt: nil, orientation: nil)
        for i in 0..<n {
            let t = Float(i) / Float(max(1, n - 1)), tau = 2 * Float.pi
            var x: Float, y: Float, p: Float
            if kind == .highlighter {
                x = origin.0 + scale * (1800 * (t < 0.5 ? t * 2 : 2 - t * 2))
                y = origin.1 + scale * (260 * sin(tau * 2 * t) + (t < 0.5 ? 0 : 70))
                p = 0.5
            } else {
                x = origin.0 + scale * (2200 * t + 260 * sin(tau * 4 * t))
                y = origin.1 + scale * (500 * sin(tau * 1.5 * t + 0.6) + 140 * sin(tau * 7 * t))
                p = 0.12 + 0.8 * pow(sin(Float.pi * t), 0.7) * (0.8 + 0.2 * sin(tau * 5 * t))
            }
            c.x.append(x); c.y.append(y); c.pressure.append(min(1, max(0, p)))
            c.elapsedMs.append(UInt32((Double(i) * 1000 / hz).rounded()))
        }
        return c
    }

    /// Hover frames, Begin, sample batches at pen pace, then End and the durable commit (or Cancel).
    /// View-relative strokes are in units of the 4800 x 3000 reference window; others in canvas units.
    static func plan(kind: ReplayKind, noteId: String, sheetId: String, epoch: UInt32, view: Viewport,
                     strokeId: String = newId(), samples: Int? = nil,
                     custom: (brush: Brush, channels: SampleChannels)? = nil) throws -> ReplayPlan {
        let relative = kind == .viewRelative
        let brush = custom?.brush ?? Brush(family: kind == .highlighter ? .highlighter : .pen, size: kind == .highlighter ? .large : .medium,
                          colour: kind == .highlighter ? .orange : (kind == .cancelled ? .red : (relative ? .green : .blue)))
        let scale: Float = relative ? 1 : 1 / view.zoom
        let origin: (Float, Float) = relative ? (700, 1100) : (view.left + 700 * scale, view.top + 1100 * scale)
        let c = try custom?.channels ?? channels(kind: kind, origin: origin, scale: scale, samples: samples)
        let space: CoordinateSpace = relative ? .viewRelative : .canvas
        var steps: [ReplayStep] = []
        var seq: UInt32 = 0
        func frame(_ at: Double, _ id: String?, _ body: PreviewBody, hover: Bool = false) {
            let number = hover ? hoverCounter.withLock { $0 += 1; return $0 } : { seq += 1; return seq }()
            steps.append(ReplayStep(atMs: at, preview: PreviewFrame(epoch: epoch, noteId: noteId, sheetId: sheetId,
                strokeId: id, sequence: number, sentMs: 0, body: body), commit: nil))
        }
        // The pen approaches: hover in view-relative units (the Mac's window), 120 ms of it.
        let (sx, sy) = relative ? (c.x[0], c.y[0]) : ((c.x[0] - view.left) * view.zoom, (c.y[0] - view.top) * view.zoom)
        for k in 0..<8 { frame(Double(k) * 16, nil, .hover(x: sx - Float(8 - k) * 20, y: sy - Float(8 - k) * 12), hover: true) }
        var at = 140.0
        frame(at, strokeId, .begin(brush: brush, viewportRevision: 1, space: space))
        let period = 1000.0 * Double(batch) / hz
        var start = 0
        while start < c.x.count {
            let end = min(c.x.count, start + batch)
            at += period
            frame(at, strokeId, .samples(firstIndex: UInt32(start), channels: SampleChannels(
                x: Array(c.x[start..<end]), y: Array(c.y[start..<end]), pressure: Array(c.pressure[start..<end]),
                elapsedMs: Array(c.elapsedMs[start..<end]), tilt: nil, orientation: nil)))
            start = end
            if kind == .cancelled && start >= c.x.count / 2 { break }
        }
        if kind == .cancelled {
            frame(at + 8, strokeId, .cancel)
        } else {
            frame(at + 8, strokeId, .end(totalSamples: UInt32(c.x.count)))
            steps.append(ReplayStep(atMs: at + 30, preview: nil, commit: StrokeCommit(noteId: noteId, sheetId: sheetId,
                strokeId: strokeId, brush: brush, space: space, samples: try encodeSamples(channels: c))))
        }
        return ReplayPlan(kind: kind, strokeId: strokeId, steps: steps, channels: c)
    }
}

/// Pushes a plan through a real `PreviewSendQueue` and feeds the engine what comes out.
final class ReplayDriver: @unchecked Sendable {
    private let engine: LiveInk
    private let queue = PreviewSendQueue()
    private let runner = DispatchQueue(label: "dev.syncr.mac.replay", qos: .userInteractive)
    init(engine: LiveInk) { self.engine = engine }

    /// Pushes one step and delivers every frame the queue pops, stamped with `ns`.
    func feed(_ step: ReplayStep, at ns: UInt64) throws {
        if var frame = step.preview {
            frame.sentMs = ns / 1_000_000
            try queue.pushPreview(frame: frame)
        }
        if let commit = step.commit { try queue.pushCommit(commit: commit) }
        while let bytes = queue.pop() { engine.receive(bytes, at: ns) }
    }

    /// Plays at the plan's own pace on a background queue. `done` runs there with an error text, if any.
    func play(_ plan: ReplayPlan, done: @escaping @Sendable (String?) -> Void) {
        runner.async { [self] in
            let start = monotonicNs()
            do {
                for step in plan.steps {
                    let due = start + UInt64(step.atMs * 1e6)
                    let now = monotonicNs()
                    if due > now { Thread.sleep(forTimeInterval: Double(due - now) / 1e9) }
                    try feed(step, at: monotonicNs())
                }
                done(nil)
            } catch { done(error.localizedDescription) }
        }
    }
}
