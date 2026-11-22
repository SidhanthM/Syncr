import CoreGraphics
import Foundation
import Metal
import os
import SyncrCore

/// Main-thread glue between the live-ink engine, the open note and the library: what the target is,
/// how a commit is written and swapped in, the status line and the debug replay. No window needed.
@MainActor final class LiveInkCoordinator {
    let engine: LiveInk
    let debug: Bool
    private let model: LibraryModel
    private let workspace: Workspace
    private let driver: ReplayDriver
    private let wakePending = OSAllocatedUnfairLock(initialState: false)
    /// The current viewport of the note view (it owns scrolling and zoom).
    var viewport: () -> Viewport = { Viewport() }
    /// Something live to draw, or the wet layer changed: the view starts or keeps its display link.
    var onWake: (() -> Void)?
    /// The status line changed (nil clears it).
    var onStatus: ((String?) -> Void)?
    private var statusRefresh: DispatchWorkItem?
    /// The viewport changed (main thread). The phone link republishes its Stream target from it.
    var onViewport: ((Viewport) -> Void)?
    /// A live stroke was written into a Mac note (main thread).
    var onStrokeSaved: ((_ noteId: String, _ strokeId: String) -> Void)?
    /// The Mac is done with a pad stroke for good: written (or already in the note), or refused. The phone lets go of it (link K.4).
    var onStrokeResolved: ((_ strokeId: String, _ ok: Bool) -> Void)?
    /// Pad strokes known to be in a note, newest last, so a stroke sent again is confirmed without a read.
    private var saved: [String] = []
    private var savedSet: Set<String> = []
    /// The pad (rework-v1 §V): what the note view shows in canvas units, scrolling it by canvas units, the pen frame's
    /// outline (nil hides it), a picture of a canvas region at a pixel width, and the page's lowest content.
    var visibleRect: () -> CGRect = { .zero }
    var scrollBy: (CGFloat, CGFloat) -> Void = { _, _ in }
    /// Multiplies the view's zoom (Read on the pad).
    var zoomBy: (CGFloat) -> Void = { _ in }
    var showPadFrame: (CGRect?) -> Void = { _ in }
    var padPicture: (CGRect, Int) -> CGImage? = { _, _ in nil }
    var pageBottom: () -> CGFloat = { 0 }
    /// A phone is connected: previews for the shown read-only phone copy are drawn (mirror mode).
    var mirrorEnabled = false { didSet { if mirrorEnabled != oldValue { syncTarget() } } }

    /// `SYNCR_MAC_DEBUG_REPLAY=1` or `--debug-replay` on the command line.
    static var debugRequested: Bool {
        ProcessInfo.processInfo.environment["SYNCR_MAC_DEBUG_REPLAY"] == "1" || CommandLine.arguments.contains("--debug-replay")
    }

    init(model: LibraryModel, workspace: Workspace, device: MTLDevice, debug: Bool = LiveInkCoordinator.debugRequested) {
        self.model = model; self.workspace = workspace; self.debug = debug
        engine = LiveInk(device: device)
        driver = ReplayDriver(engine: engine)
        engine.onWake = { [weak self] in
            guard let self, !self.wakePending.withLock({ let was = $0; $0 = true; return was }) else { return }
            DispatchQueue.main.async { MainActor.assumeIsolated { self.wakePending.withLock { $0 = false }; self.onWake?() } }
        }
        engine.onCommit = { [weak self] commit, frozen in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.commit(commit, frozen) } }
        }
        engine.onStatus = { [weak self] in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.statusChanged() } }
        }
        engine.onCommitDropped = { [weak self] id in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.onStrokeResolved?(id, false) } }
        }
        engine.onCommitDuplicate = { [weak self] id in
            DispatchQueue.main.async { MainActor.assumeIsolated { if let self, self.savedSet.contains(id) { self.onStrokeResolved?(id, true) } } }
        }
        workspace.debugCommands = debug
        syncTarget()
    }

    /// Call whenever the active tab or its writability may have changed.
    func syncTarget() { engine.setTarget(noteId: workspace.active, writable: workspace.activeIsWritable, mirror: mirrorEnabled) }
    func viewChanged(_ v: Viewport) { engine.setView(v); onViewport?(v) }

    /// The transport-agnostic input, callable from any queue.
    nonisolated func receive(_ bytes: Data, at ns: UInt64) { engine.receive(bytes, at: ns) }

    // MARK: Commit

    private func commit(_ commit: StrokeCommit, _ frozen: Viewport?) {
        guard workspace.active == commit.noteId, workspace.activeIsWritable else {
            engine.finishCommit(strokeId: commit.strokeId, .refused); onWake?()
            onStrokeResolved?(commit.strokeId, false)
            return
        }
        let generation = model.generation
        model.appendLiveStroke(commit, view: frozen ?? viewport()) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let stroke):
                // One main-thread block: the committed stroke goes in and the preview comes out, so
                // no draw can show both or neither. A reload since the write already contains it.
                if self.model.generation == generation { self.model.insert(stroke) }
                self.engine.finishCommit(strokeId: commit.strokeId, .saved)
                self.remember(commit.strokeId)
                self.onStrokeSaved?(commit.noteId, commit.strokeId)
                self.onStrokeResolved?(commit.strokeId, true)
            case .failure(let error as ViewerError) where error == .alreadySaved:
                // Sent again after a drop that cost the phone the confirmation: it is in the note, once.
                self.engine.finishCommit(strokeId: commit.strokeId, .saved)
                self.remember(commit.strokeId)
                self.onStrokeResolved?(commit.strokeId, true)
            case .failure(let error):
                self.engine.finishCommit(strokeId: commit.strokeId, .failed(error.localizedDescription))
            }
            self.onWake?()
        }
    }

    private func remember(_ strokeId: String) {
        guard savedSet.insert(strokeId).inserted else { return }
        saved.append(strokeId)
        while saved.count > 1024 { savedSet.remove(saved.removeFirst()) }
    }

    // MARK: Status

    func statusText() -> String? {
        let s = engine.stats
        if let error = s.lastError { return "Live ink: could not save a stroke (\(error))" }
        if let why = s.lastRefusal, Double(monotonicNs() &- s.lastRefusalNs) / 1e9 < 8 {
            switch why {
            case .noNote: return "Live ink ignored: no note is open (\(s.refusedNoNote))"
            case .readOnly: return "Live ink ignored: this note is read-only (\(s.refusedReadOnly))"
            case .wrongNote: return "Live ink ignored: it is for a different note (\(s.refusedWrongNote))"
            }
        }
        guard debug else { return nil }
        let latency = engine.latency.summary()
        guard latency.count > 0 else { return "Live ink debug: ready" }
        return String(format: "Live ink debug: p50 %.1f / p95 %.1f / max %.1f ms, software side (received to presented), %d frames",
                      latency.p50, latency.p95, latency.max, latency.count)
    }

    func statusChanged() {
        onStatus?(statusText())
        // A refusal message fades by itself: look again once it has aged out.
        statusRefresh?.cancel()
        guard engine.stats.lastRefusal != nil else { return }
        let work = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.onStatus?(self?.statusText()) } }
        statusRefresh = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 8.2, execute: work)
    }

    // MARK: Debug replay

    func replay(_ kind: ReplayKind) {
        guard debug else { return }
        guard let note = workspace.active, let sheet = model.drawing?.sheetId else {
            onStatus?("Replay: open a note first"); return
        }
        engine.resetLatency()
        let plan: ReplayPlan
        do { plan = try ReplayGenerator.plan(kind: kind, noteId: note, sheetId: sheet, epoch: engine.epoch, view: viewport()) }
        catch { onStatus?("Replay failed: \(error.localizedDescription)"); return }
        driver.play(plan) { [weak self] failure in
            // Presented handlers trail the last frame by a refresh or two.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    let line = "live-ink replay \(kind.rawValue) [\(PresentMode.current.rawValue)]: \(failure ?? "ok"); \(self.engine.latency.describe())\n\(self.engine.describeStages())\n"
                    FileHandle.standardError.write(Data(line.utf8))
                    self.statusChanged()
                }
            }
        }
    }

    /// Simulated disconnect.
    func disconnect() { engine.reset() }
}
