import Foundation
import os
import SyncrCore

// Main-thread glue between the phone link and the app: the listener's lifetime, who is connected,
// the pairing code on screen, installing synced notes, Follow phone, the Tablet Stream target, and
// the debug event lines. The protocol itself is `LinkSession`; sockets are `LinkServer`.

/// What the Tablet Stream target says, without the revision: the thing whose change makes the Mac send it again.
struct TargetSnapshot: Equatable {
    var note: String?
    var sheet: String?
    var writable = false
    var view = Viewport()
}

/// Decides when `target` goes to the phone and with which `rev`. A burst of changes (scrolling) is
/// coalesced: the first goes out at once, later ones wait for the minimum interval, and whatever the
/// state is when the wait ends is sent, so the final state always arrives. `rev` grows whenever the
/// note or viewport the phone is told about changes, and the viewport for each rev is recorded so a
/// stroke that names it is placed with it.
@MainActor final class TargetPublisher {
    var minIntervalMs = 50.0
    var current: () -> TargetSnapshot = { TargetSnapshot() }
    var send: (LinkControl) -> Void = { _ in }
    var recordView: (UInt32, Viewport) -> Void = { _, _ in }
    var now: () -> Double = { Double(monotonicNs()) / 1e6 }
    /// Runs the block after the given milliseconds.
    var schedule: (Double, @escaping @MainActor () -> Void) -> Void = { ms, block in
        DispatchQueue.main.asyncAfter(deadline: .now() + ms / 1000) { MainActor.assumeIsolated(block) }
    }
    private(set) var rev: UInt32 = 0
    private(set) var sent: TargetSnapshot?
    private var lastSentAt: Double?
    private var waiting = false

    /// A new connection: the phone knows nothing yet. (`rev` keeps counting so a stale stroke never matches.)
    func reset() { sent = nil; lastSentAt = nil }

    func changed() {
        let want = current()
        guard want != sent else { return }
        let elapsed = lastSentAt.map { now() - $0 } ?? .infinity
        if elapsed >= minIntervalMs { flush(); return }
        guard !waiting else { return }
        waiting = true
        schedule(minIntervalMs - elapsed) { [self] in waiting = false; flush() }
    }

    private func flush() {
        let want = current()
        guard want != sent else { return }
        guard let note = want.note, let sheet = want.sheet else {
            sent = want; lastSentAt = now(); send(.target(nil)); return
        }
        if sent?.note != want.note || sent?.view != want.view || sent?.sheet != want.sheet {
            rev &+= 1
            recordView(rev, want.view)
        }
        sent = want; lastSentAt = now()
        send(.target(TargetInfo(note: note, sheet: sheet, writable: want.writable, rev: rev)))
    }
}

/// One session per phone: a new connection from a phone that is already connected replaces the old one.
final class LinkRegistry<Session: AnyObject> {
    private(set) var byPhone: [String: Session] = [:]
    /// Registers `session`; returns the one it displaced, if any (the caller closes it).
    func register(phoneId: String, _ session: Session) -> Session? {
        let old = byPhone[phoneId]
        byPhone[phoneId] = session
        return old === session ? nil : old
    }
    func remove(_ session: Session) { for (id, s) in byPhone where s === session { byPhone[id] = nil } }
    func phoneId(of session: Session) -> String? { byPhone.first { $0.value === session }?.key }
    var sessions: [Session] { Array(byPhone.values) }
    var isEmpty: Bool { byPhone.isEmpty }
}

/// Receives session callbacks on connection queues and moves them to the main thread.
final class LinkBridge: LinkSessionHost, @unchecked Sendable {
    weak var controller: LinkController?
    private let all = OSAllocatedUnfairLock(initialState: [ObjectIdentifier: LinkSession]())

    var sessions: [LinkSession] { all.withLock { Array($0.values) } }
    func track(_ s: LinkSession) { all.withLock { $0[ObjectIdentifier(s)] = s } }

    private func main(_ work: @escaping @MainActor @Sendable (LinkController) -> Void) {
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { if let c = self?.controller { work(c) } }
        }
    }

    func sessionNeedsCode(_ session: LinkSession, phoneName: String, code: String) {
        main { [s = session] in $0.showCode(s, phone: phoneName, code: code) }
    }
    func sessionCodeEnded(_ session: LinkSession) {
        let s = session
        main { $0.hideCode(s) }
    }
    func sessionWelcomed(_ session: LinkSession) {
        let s = session
        main { $0.welcomed(s) }
    }
    func sessionNotes(_ session: LinkSession, notes: [NoteAnnounce], reply: @escaping @Sendable ([String]) -> Void) {
        main { reply($0.want(for: notes)) }
    }
    func sessionInstall(_ session: LinkSession, offer: FileOffer, file: URL, reply: @escaping @Sendable (InstallOutcome) -> Void) {
        main { $0.install(offer, file: file, reply: reply) }
    }
    func sessionViewing(_ session: LinkSession, id: String?) { main { $0.phoneViewing(id) } }
    func sessionPad(_ session: LinkSession, on: Bool) { main { $0.phonePad(on) } }
    func sessionClock(_ session: LinkSession, clock: ClockSync) { main { $0.clockUpdated(clock) } }
    func sessionClosed(_ session: LinkSession) {
        all.withLock { $0[ObjectIdentifier(session)] = nil }
        let s = session
        main { $0.closed(s) }
    }
    func sessionLog(_ line: String) { controller?.log(line) }
}

@MainActor final class LinkController {
    struct Options {
        /// nil: any free port.
        var port: UInt16?
        var loopbackOnly = false
        var advertise = true
        @MainActor static func fromEnvironment() -> Options {
            var o = Options()
            o.port = ProcessInfo.processInfo.environment["SYNCR_MAC_LINK_PORT"].flatMap { UInt16($0) }
            if LiveInkCoordinator.debugRequested && ProcessInfo.processInfo.environment["SYNCR_MAC_LINK_LOOPBACK"] == "1" { o.loopbackOnly = true; o.advertise = false }
            return o
        }
    }

    enum Status: Equatable {
        case off, starting, waiting(port: UInt16), pairing(phone: String), connected(phone: String), failed(String)
    }

    let model: LibraryModel
    let workspace: Workspace
    let live: LiveInkCoordinator
    let options: Options
    private let debug: Bool
    let publisher = TargetPublisher()
    private(set) var settings = LinkSettings()
    private(set) var store: PairedPhoneStore
    private(set) var port: UInt16?
    private(set) var failure: String?
    private(set) var pairingCode: (phone: String, code: String)?
    private(set) var viewing: String?
    private(set) var padOn = false
    /// The note the phone is writing in right now: the pad's target while it is open, else the note it views.
    var liveNote: String? { registry.sessions.isEmpty ? nil : (padOn ? workspace.active : viewing) }
    private let bridge = LinkBridge()
    private let registry = LinkRegistry<LinkSession>()
    private var server: LinkServer?
    private var pairingSession: LinkSession?
    private var pendingFollow: String?
    private var latestClock = ClockSync()
    private var latencyLog: DispatchWorkItem?
    private let epochs = OSAllocatedUnfairLock(initialState: UInt32(0))
    private var macName: String { Host.current().localizedName ?? ProcessInfo.processInfo.hostName }

    /// The status indicator or a command's state changed.
    var onStatus: (() -> Void)?
    /// The four digits to show (phone name, code), or nil to hide them.
    var onPairing: (((phone: String, code: String)?) -> Void)?

    init(model: LibraryModel, workspace: Workspace, live: LiveInkCoordinator, options: Options = .fromEnvironment()) {
        self.model = model; self.workspace = workspace; self.live = live; self.options = options; debug = live.debug
        settings = LinkSettings.load(model.readState)
        store = PairedPhoneStore(folder: model.folder.appendingPathComponent(".mac", isDirectory: true))
        if model.readState(LinkSettings.fileName) == nil { saveSettings() }  // this Mac's ID is generated once and kept
        bridge.controller = self
        publisher.current = { [unowned self] in desiredTarget() }
        publisher.send = { [unowned self] in sendTarget($0) }
        publisher.recordView = { [engine = live.engine] rev, view in engine.recordView(view, rev: rev) }
        live.onViewport = { [weak self] _ in self?.publishTarget() }
        live.onStrokeSaved = { [weak self] note, stroke in self?.log("link: stroke-saved note=\(note) stroke=\(stroke)") }
        live.engine.onStrokeEnded = { [weak self] in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.scheduleLatencyLog() } }
        }
    }

    // MARK: Switches

    var enabled: Bool { settings.enabled }
    var follow: Bool { settings.follow }
    var hasPairedPhones: Bool { !store.all.isEmpty }
    var connectedPhones: [String] { registry.sessions.map(\.phoneName) }

    func start() { if settings.enabled { startServer() } }

    func setEnabled(_ on: Bool) {
        guard on != settings.enabled || (on && server == nil) else { return }
        settings.enabled = on; saveSettings()
        if on { startServer() } else { stopServer(bye: "link off") }
        onStatus?()
    }

    func setFollow(_ on: Bool) { settings.follow = on; saveSettings(); onStatus?() }

    private func saveSettings() {
        if let data = try? JSONEncoder().encode(settings) { model.writeState(LinkSettings.fileName, data) }
    }

    /// The library folder changed: the paired phones, identity and staging area belong to the old one.
    func libraryFolderChanged() {
        let was = server != nil
        stopServer(bye: "link off")
        settings = LinkSettings.load(model.readState)
        if model.readState(LinkSettings.fileName) == nil { saveSettings() }
        store = PairedPhoneStore(folder: model.folder.appendingPathComponent(".mac", isDirectory: true))
        if was && settings.enabled { startServer() }
        onStatus?()
    }

    func stop() { stopServer(bye: "link off") }

    // MARK: Listener

    private var stagingDir: URL { model.folder.appendingPathComponent(".incoming-link", isDirectory: true) }

    private func startServer() {
        guard server == nil else { return }
        failure = nil
        try? FileManager.default.removeItem(at: stagingDir)  // leftovers of a crash; no session exists yet
        let name = macName, macId = settings.macId
        let server = LinkServer(config: .init(port: options.port, loopbackOnly: options.loopbackOnly, advertise: options.advertise,
                                              macName: name, macId: macId))
        let bridge = bridge, store = store, engine = live.engine, staging = stagingDir, epochs = epochs
        server.makeSession = { connection in
            let epoch = epochs.withLock { n -> UInt32 in n &+= 1; if n == 0 { n = 1 }; return n }
            let io = LinkSession.IO(
                send: { connection.send($0) }, close: { connection.close() },
                liveReceive: { engine.receive($0, at: $1) },
                liveReset: { engine.reset(epoch: $0, wait: true) },
                run: { block in connection.queue.async(execute: block) })
            let session = LinkSession(config: .init(macName: name, macId: macId, epoch: epoch, stagingDir: staging), store: store, io: io)
            session.host = bridge
            bridge.track(session)
            return session
        }
        server.onListening = { [weak self] port in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.listening(port) } }
        }
        server.onFailed = { [weak self] message in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.listenFailed(message) } }
        }
        self.server = server
        do { try server.start() } catch { self.server = nil; listenFailed(error.localizedDescription) }
        onStatus?()
    }

    private func listening(_ port: UInt16) {
        self.port = port
        log("link: listening port=\(port)")
        onStatus?()
    }

    private func listenFailed(_ message: String) {
        failure = message; server?.stop(); server = nil; port = nil
        log("link: listen-failed reason=\(message)")
        onStatus?()
    }

    private func stopServer(bye: String) {
        server?.stop(); server = nil; port = nil
        for s in bridge.sessions { let s = s; s.onExecutor { s.close(bye: bye) } }
        pairingSession = nil; setPairing(nil)
        endMirror()
        onStatus?()
    }

    // MARK: Status

    var status: Status {
        if !settings.enabled { return .off }
        if let failure { return .failed(failure) }
        if let first = registry.sessions.first { return .connected(phone: first.phoneName) }
        if let p = pairingCode { return .pairing(phone: p.phone) }
        if let port { return .waiting(port: port) }
        return .starting
    }

    var statusText: String {
        switch status {
        case .off: "Phone link: off"
        case .starting: "Phone link: starting"
        case .waiting: "Phone link: waiting for phone"
        case .pairing(let phone): "Phone link: pairing with \(phone)"
        case .connected(let phone): "Phone link: \(phone) connected"
        case .failed: "Phone link: could not listen"
        }
    }

    private func setPairing(_ value: (phone: String, code: String)?) {
        pairingCode = value
        onPairing?(value)
        onStatus?()
    }

    // MARK: Forget

    func forget(phoneId: String) {
        store.forget(id: phoneId)
        if let s = registry.byPhone[phoneId] { let s = s; s.onExecutor { s.close(bye: "unpaired") } }
        for s in bridge.sessions where s.phoneId == phoneId { let s = s; s.onExecutor { s.close(bye: "unpaired") } }
        onStatus?()
    }

    // MARK: Session callbacks (main thread)

    fileprivate func showCode(_ session: LinkSession, phone: String, code: String) {
        pairingSession = session
        log("link: pairing-code \(code) phone=\"\(phone)\"")
        setPairing((phone, code))
    }

    fileprivate func hideCode(_ session: LinkSession) {
        guard pairingSession === session else { return }
        pairingSession = nil
        setPairing(nil)
    }

    fileprivate func welcomed(_ session: LinkSession) {
        guard let id = session.phoneId else { return }
        if let old = registry.register(phoneId: id, session) {
            let old = old
            old.onExecutor { old.close(bye: nil) }
        }
        live.engine.clearRecordedViews()
        live.mirrorEnabled = true
        log("link: connected phone=\"\(session.phoneName)\" id=\(id) epoch=\(session.config.epoch)")
        publisher.reset()
        publishTarget()
        onStatus?()
    }

    fileprivate func closed(_ session: LinkSession) {
        let wasRegistered = registry.phoneId(of: session) != nil
        registry.remove(session)
        hideCode(session)
        guard wasRegistered else { return }
        log("link: disconnected phone=\"\(session.phoneName)\"")
        logLatency("session end")
        if registry.isEmpty { endMirror() }
        onStatus?()
    }

    /// No phone is connected: previews, offsets and revisions of the old connection are gone.
    private func endMirror() {
        live.mirrorEnabled = false
        live.engine.reset()
        live.engine.setClockOffset(nil)
        live.engine.clearRecordedViews()
        latestClock = ClockSync()
        padOn = false; viewing = nil; pendingFollow = nil
    }

    fileprivate func clockUpdated(_ clock: ClockSync) {
        latestClock = clock
        live.engine.setClockOffset(clock.offsetMs)
    }

    // MARK: Note sync

    fileprivate func want(for notes: [NoteAnnounce]) -> [String] {
        let held = Dictionary(model.notes.map { ($0.id, $0.localSequence) }, uniquingKeysWith: max)
        let refused = model.owned.union(model.deleted.map(\.id))
        return LinkSync.want(announced: notes, held: held, refused: refused)
    }

    /// The copy is staged, validated and published off the main thread; only the decision and the final
    /// swap of the model run here, so a large note arriving does not stall live ink.
    fileprivate func install(_ offer: FileOffer, file: URL, reply: @escaping @Sendable (InstallOutcome) -> Void) {
        model.importCopyAsync(from: file) { [self] result in
            switch result {
            case .success(let summary):
                workspace.libraryChanged()
                log("link: note-installed id=\(summary.id) seq=\(summary.localSequence)")
                installed(summary.id)
                reply(.installed(seq: summary.localSequence))
            case .failure(ViewerError.notNewer):
                let seq = model.notes.first { $0.id == offer.id }?.localSequence ?? offer.seq
                log("link: note-current id=\(offer.id) seq=\(seq)")
                reply(.current(seq: seq))
            case .failure(let error):
                log("link: note-refused id=\(offer.id) reason=\(error.localizedDescription)")
                reply(.refused("import: \(error.localizedDescription)"))
            }
        }
    }

    /// A copy of `id` is now in the library. The shown note is reloaded in place (viewport kept) and its
    /// previews are dropped in the same main-thread block as the swap; a note the phone is viewing and
    /// that was waiting for its first copy opens now.
    private func installed(_ id: String) {
        if workspace.active == id {
            model.reloadInPlace(id: id) { [engine = live.engine] ids in engine.finishMirror(strokeIds: ids) }
        } else if pendingFollow == id {
            pendingFollow = nil
            followViewing()
        }
    }

    // MARK: Live mirror: Follow phone

    fileprivate func phoneViewing(_ id: String?) {
        viewing = id
        onStatus?()
        log("link: viewing id=\(id ?? "none")")
        pendingFollow = nil
        followViewing()
    }

    private func followViewing() {
        guard settings.follow, !padOn, let id = viewing else { return }
        if model.notes.contains(where: { $0.id == id }) { workspace.open(id, newTab: true) }
        else { pendingFollow = id }
    }

    // MARK: Tablet Stream

    fileprivate func phonePad(_ on: Bool) {
        padOn = on
        log("link: pad on=\(on)")
        defer { onStatus?() }
        if on, !(workspace.active != nil && workspace.activeIsWritable) {
            do { _ = try workspace.newNote() } catch { log("link: pad-note-failed reason=\(error.localizedDescription)") }
        }
        publishTarget()
    }

    /// Call when the active tab, its writability, the shown note or the viewport may have changed.
    func stateChanged() { live.syncTarget(); publishTarget() }

    /// Only while a phone is connected: nothing is recorded or logged for an empty room.
    private func publishTarget() { if !registry.isEmpty { publisher.changed() } }

    private func desiredTarget() -> TargetSnapshot {
        guard let id = workspace.active, model.shownNote == id, let sheet = model.drawing?.sheetId else { return TargetSnapshot() }
        return TargetSnapshot(note: id, sheet: sheet, writable: workspace.activeIsWritable, view: live.viewport())
    }

    private func sendTarget(_ control: LinkControl) {
        if case .target(let info) = control {
            log("link: target note=\(info?.note ?? "none") sheet=\(info?.sheet ?? "none") writable=\(info?.writable ?? false) rev=\(info?.rev ?? 0) view=\(publisher.sent?.view.left ?? 0),\(publisher.sent?.view.top ?? 0),\(publisher.sent?.view.zoom ?? 1)")
        }
        for s in registry.sessions { let s = s; s.onExecutor { s.send(control) } }
    }

    // MARK: Latency log (debug flag)

    private func scheduleLatencyLog() {
        guard live.debug else { return }
        latencyLog?.cancel()
        // Presented handlers trail the last frame by a refresh or two.
        let work = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.logLatency("stroke end") } }
        latencyLog = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
    }

    /// p50/p95/max of phone socket write to Mac presented, with the round trip and what the figure leaves out.
    func logLatency(_ when: String) {
        let s = live.engine.phoneLatency.summary()
        guard s.count > 0 else { return }
        let rtt = latestClock.latestRttMs.map { String(format: "%.1f", $0) } ?? "n/a"
        let best = latestClock.best.map { String(format: "%.1f", $0.rttMs) } ?? "n/a"
        log(String(format: "link: latency [%@] phone-send-to-mac-presented p50=%.1f p95=%.1f max=%.1f ms n=%d rtt-last=%@ ms rtt-best=%@ ms (%@)",
                   when, s.p50, s.p95, s.max, s.count, rtt, best, LiveInk.phoneScope))
    }

    // MARK: Logging

    /// Single greppable lines on stderr, only with the debug flag (the one the replay uses). Never tokens.
    nonisolated func log(_ line: String) {
        guard debug else { return }
        FileHandle.standardError.write(Data((line + "\n").utf8))
    }
}
