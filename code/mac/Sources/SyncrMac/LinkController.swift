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
    /// Pad additions, sent to a phone that asked for them (`padmode`): the pen frame, the visible canvas, the page's end.
    var frame: CGRect?
    var visible: CGRect?
    var pageHeight: Double?
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
        send(.target(TargetInfo(note: note, sheet: sheet, writable: want.writable, rev: rev,
                                frame: want.frame, view: want.visible, pageHeight: want.pageHeight)))
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
    func sessionNotes(_ session: LinkSession, notes: [NoteAnnounce], trash: [String]?, reply: @escaping @Sendable ([String]) -> Void) {
        let s = session
        main { $0.want(for: notes, from: s, reply: reply) }
    }
    func sessionInstall(_ session: LinkSession, offer: FileOffer, file: URL, reply: @escaping @Sendable (InstallOutcome) -> Void) {
        let s = session
        main { $0.install(offer, file: file, from: s, reply: reply) }
    }
    func sessionExport(_ session: LinkSession, id: String, base: [String: String]?, reply: @escaping @Sendable (ExportOutcome) -> Void) {
        main { $0.export(id, base: base, reply: reply) }
    }
    func sessionHeads(_ session: LinkSession, id: String, reply: @escaping @Sendable ([String: String]) -> Void) {
        main { $0.model.syncHeads(id) { reply($0) } }
    }
    func sessionHave(_ session: LinkSession, id: String, digest: String, reply: @escaping @Sendable (Bool) -> Void) {
        main { $0.peerHave(id, digest: digest, reply: reply) }
    }
    func sessionViewing(_ session: LinkSession, id: String?) { main { $0.phoneViewing(id) } }
    func sessionPad(_ session: LinkSession, on: Bool) { main { $0.phonePad(on) } }
    func sessionPadMode(_ session: LinkSession, local: Bool, pics: Bool, aspect: Double) { main { $0.phonePadMode(local: local, pics: pics, aspect: aspect) } }
    func sessionFrame(_ session: LinkSession, rect: CGRect) { main { $0.phoneFrame(rect) } }
    func sessionPointerMode(_ session: LinkSession, version: Int, on: Bool) {
        main { $0.pointerMode(session, version: version, on: on) }
    }
    func sessionPointer(_ session: LinkSession, packet: PointerPacket) {
        let received = monotonicNs()
        main { controller in
            if monotonicNs() &- received > 200_000_000 { controller.remotePointer.request(session, version: 1, on: false) }
            else { controller.pointerInput(session, packet: packet) }
        }
    }
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
    /// What the pad asked for with `padmode`; nil until it does (an older phone never does, and gets no pad additions).
    private(set) var padMode: (local: Bool, pics: Bool, aspect: CGFloat)?
    /// The pen frame while the pad is in Local mode.
    private(set) var padFrame: PadFrame?
    /// Pad strokes saved since this phone connected: the phone drops its own drawing of them once a picture shows them.
    private var padSaved: UInt32 = 0
    private var pictureWork: DispatchWorkItem?
    private var pictureDirtySince: Double?
    private var lastPictureAt = 0.0
    private var lastPictureHash = 0
    private let pictureQueue = DispatchQueue(label: "dev.syncr.mac.link.picture", qos: .utility)
    static let pictureIntervalMs = 200.0, pictureWidth = 720
    /// The note the phone is writing in right now: the pad's target while it is open, else the note it views.
    var liveNote: String? { registry.sessions.isEmpty ? nil : (padOn ? workspace.active : viewing) }
    private let bridge = LinkBridge()
    private let registry = LinkRegistry<LinkSession>()
    fileprivate let remotePointer = RemotePointer()

    fileprivate func pointerMode(_ session: LinkSession, version: Int, on: Bool) {
        guard registry.phoneId(of: session) != nil else { return }
        remotePointer.request(session, version: version, on: on)
    }
    fileprivate func pointerInput(_ session: LinkSession, packet: PointerPacket) {
        guard registry.phoneId(of: session) != nil else { return }
        remotePointer.receive(packet, from: session)
    }
    private var server: LinkServer?
    private var pairingSession: LinkSession?
    private var pendingFollow: String?
    private var latestClock = ClockSync()
    private var latencyLog: DispatchWorkItem?
    /// Link v1: sessions the Mac already sent its full notes list to, and the digest it last announced per note.
    private var announced: [ObjectIdentifier: [String: String]] = [:]
    private var dirty: Set<String> = []
    private var dirtySince: Double?
    private var announceWork: DispatchWorkItem?
    /// v0 phones: per phone, the last sequence of each note the Mac merged (they announce no digests).
    private var seen: [String: [String: UInt64]] = [:]
    /// v1 phones: the digest each note was last announced with, and digests whose copy merged nothing new because its
    /// only extra operations were rejected (saved as conflicts). Asking for those again would loop forever.
    private var phoneDigests: [String: String] = [:]
    private var fruitless: [String: String] = [:]
    static let seenFile = "link-seen.json"
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
        model.onCommit = { [weak self] id in self?.noteCommitted(id) }
        if let data = model.readState(Self.seenFile), let loaded = try? JSONDecoder().decode([String: [String: UInt64]].self, from: data) { seen = loaded }
        live.onStrokeSaved = { [weak self] note, stroke in
            self?.log("link: stroke-saved note=\(note) stroke=\(stroke)")
            self?.padSaved &+= 1
            self?.pictureChanged()
        }
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
        try? FileManager.default.removeItem(at: model.outgoingFolder)
        let name = macName, macId = settings.macId
        let server = LinkServer(config: .init(port: options.port, loopbackOnly: options.loopbackOnly, advertise: options.advertise,
                                              macName: name, macId: macId))
        let bridge = bridge, store = store, engine = live.engine, staging = stagingDir, epochs = epochs
        server.makeSession = { [weak server] connection in
            let epoch = epochs.withLock { n -> UInt32 in n &+= 1; if n == 0 { n = 1 }; return n }
            let io = LinkSession.IO(
                send: { connection.send($0) }, close: { connection.close() },
                liveReceive: { engine.receive($0, at: $1) },
                liveReset: { engine.reset(epoch: $0, wait: true) },
                run: { block in connection.queue.async(execute: block) },
                sendBulk: { connection.sendBulk($0, done: $1) },
                penActive: { engine.penActive() })
            let session = LinkSession(config: .init(macName: name, macId: macId, epoch: epoch, stagingDir: staging,
                                                    udpPort: server?.udpPort, udpKey: UInt64.random(in: 1...UInt64.max)), store: store, io: io)
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
        remotePointer.stopAll()
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
        if let session = registry.byPhone[phoneId] { remotePointer.stop(session) }
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
            remotePointer.stop(old)
            let old = old
            old.onExecutor { old.close(bye: nil) }
        }
        live.engine.clearRecordedViews()
        live.mirrorEnabled = true
        clearPad()
        log("link: connected phone=\"\(session.phoneName)\" id=\(id) epoch=\(session.config.epoch)")
        publisher.reset()
        publishTarget()
        announced[ObjectIdentifier(session)] = nil
        onStatus?()
    }

    fileprivate func closed(_ session: LinkSession) {
        remotePointer.stop(session)
        let wasRegistered = registry.phoneId(of: session) != nil
        registry.remove(session)
        announced[ObjectIdentifier(session)] = nil
        hideCode(session)
        guard wasRegistered else { return }
        log("link: disconnected phone=\"\(session.phoneName)\"")
        log("link: second-path frames=\(session.datagramFrames)")
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
        clearPad()
    }

    fileprivate func clockUpdated(_ clock: ClockSync) {
        latestClock = clock
        live.engine.setClockOffset(clock.offsetMs)
    }

    // MARK: Note sync (link v1: two-way, the Mac is primary)

    /// What to ask the phone for. A v1 phone announces digests: ask for every note whose operation set differs from the
    /// Mac's. A v0 phone announces sequences only: ask when the sequence differs from the last one merged.
    fileprivate func want(for notes: [NoteAnnounce], from session: LinkSession, reply: @escaping @Sendable ([String]) -> Void) {
        let refused = Set(model.deleted.map(\.id))
        if session.peerVersion >= 1 {
            let ids = notes.map(\.id).filter { model.liveIds.contains($0) }
            for n in notes { phoneDigests[n.id] = n.digest }
            let settled = notes.filter { n in n.digest != nil && fruitless[n.id] == n.digest }.map(\.id)
            model.syncDigests(ids) { [self] digests in
                reply(LinkSync.want(announced: notes, digests: digests.mapValues(\.digest), refused: refused.union(settled)))
                announceAll(to: session)
            }
        } else {
            let held = Dictionary(model.notes.map { ($0.id, $0.localSequence) }, uniquingKeysWith: max)
            reply(LinkSync.wantV0(announced: notes, merged: seen[session.phoneId ?? ""] ?? [:], held: held, refused: refused))
        }
    }

    /// A verified copy is staged. A note the Mac lacks is installed; one it has is merged (never replaced); the heavy work
    /// runs off the main thread, and only the library refresh and the in-place reload run here.
    fileprivate func install(_ offer: FileOffer, file: URL, from session: LinkSession, reply: @escaping @Sendable (InstallOutcome) -> Void) {
        let phone = session.phoneId ?? ""
        let incoming = offer.delta ? model.syncIncomingDelta : model.syncIncoming
        incoming(file, offer.id, session.phoneName) { [self] result in
            switch result {
            case .success(let r):
                if offer.delta {
                    log("link: delta-merged id=\(offer.id) applied=\(r.applied) duplicates=\(r.duplicates) rejected=\(r.rejected) short=\(r.short) seq=\(r.seq)")
                    if r.rejected > 0 { log("link: sync-conflict note=\(offer.id) rejected=\(r.rejected) saved-in=.mac/sync-conflicts/\(offer.id)") }
                    if r.applied > 0 { installed(offer.id) }
                    reply(r.short ? .deltaShort : .installed(seq: r.seq, digest: r.digest, ops: r.ops))
                    return
                }
                if session.peerVersion < 1 { seen[phone, default: [:]][offer.id] = offer.seq; saveSeen() }
                if r.installed {
                    workspace.libraryChanged()
                    log("link: note-installed id=\(offer.id) seq=\(r.seq)")
                } else {
                    log("link: note-merged id=\(offer.id) applied=\(r.applied) duplicates=\(r.duplicates) rejected=\(r.rejected) seq=\(r.seq)")
                    log("link: note-installed id=\(offer.id) seq=\(r.seq)")  // the v0 line, kept for old readers of the log
                }
                if r.rejected > 0 { log("link: sync-conflict note=\(offer.id) rejected=\(r.rejected) saved-in=.mac/sync-conflicts/\(offer.id)") }
                if !r.installed, r.applied == 0, r.rejected > 0, let digest = phoneDigests[offer.id] { fruitless[offer.id] = digest }
                if r.installed || r.applied > 0 { installed(offer.id) }
                reply(.installed(seq: r.seq, digest: r.digest, ops: r.ops))
            case .failure(let error):
                log("link: note-refused id=\(offer.id) reason=\(error.localizedDescription)")
                reply(.refused("import: \(error.localizedDescription)"))
            }
        }
    }

    private func saveSeen() {
        if let data = try? JSONEncoder().encode(seen) { model.writeState(Self.seenFile, data) }
    }

    /// The phone wants a note: stage a copy of it for the session to send.
    fileprivate func export(_ id: String, base: [String: String]?, reply: @escaping @Sendable (ExportOutcome) -> Void) {
        guard model.liveIds.contains(id) else { reply(.refused("no such note")); return }
        model.syncExport(noteId: id, base: base) { result in
            switch result {
            case .success(let e): reply(.file(url: e.file, size: e.size, sha256: e.sha256, seq: e.seq, digest: e.digest, ops: e.ops, delta: e.delta))
            case .failure(let error): reply(.refused(error.localizedDescription))
            }
        }
    }

    /// The phone installed a Mac copy and reports its operation set: ask for it back if it holds something the Mac lacks.
    fileprivate func peerHave(_ id: String, digest: String, reply: @escaping @Sendable (Bool) -> Void) {
        model.syncDigests([id]) { [self] digests in
            phoneDigests[id] = digest
            let differs = digests[id].map { $0.digest != digest } ?? false && fruitless[id] != digest
            log("link: phone-have id=\(id) equal=\(!differs)")
            reply(differs)
        }
    }

    /// The full notes list to a v1 phone, once per connection, without the notes in its Recently deleted.
    private func announceAll(to session: LinkSession) {
        let key = ObjectIdentifier(session)
        guard announced[key] == nil else { return }
        announced[key] = [:]
        model.syncAnnouncements(excluding: session.peerTrash) { [self] list in
            announced[key] = Dictionary(list.compactMap { n in n.digest.map { (n.id, $0) } }, uniquingKeysWith: { $1 })
            log("link: announce-all count=\(list.count)")
            let s = session
            s.onExecutor { s.announce(list) }
        }
    }

    /// A commit on the Mac (typing, ink, companion, pad strokes, renames, merges): tell v1 phones within about 400 ms,
    /// later commits pushing the announcement back while the note keeps changing, but never past one second.
    private func noteCommitted(_ id: String) {
        if id == workspace.active { pictureChanged() }
        guard !announced.isEmpty else { return }
        dirty.insert(id)
        let now = Double(monotonicNs()) / 1e6
        if dirtySince == nil { dirtySince = now }
        announceWork?.cancel()
        let wait = max(0, min(400, 1000 - (now - (dirtySince ?? now))))
        let work = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.flushAnnouncements() } }
        announceWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + wait / 1000, execute: work)
    }

    /// While the phone's pen is on the pad (or was, this recently) an announcement waits: each one costs the phone a
    /// whole-note transfer and merge, which stalls the ink it is sending. Never held longer than the cap.
    static let penQuietMs = 2500.0, penHoldCapMs = 30_000.0
    /// While the pad is open the phone shows none of its notes, so the Mac's copy can wait for a real pause: nothing is
    /// announced until the pen has been away this long (or the pad closes), however long the writing goes on.
    static let padQuietMs = 15_000.0, padHoldCapMs = 600_000.0

    private func flushAnnouncements() {
        let now = Double(monotonicNs()) / 1e6
        let quiet = padOn ? Self.padQuietMs : Self.penQuietMs, cap = padOn ? Self.padHoldCapMs : Self.penHoldCapMs
        if live.engine.penActive(within: UInt64(quiet * 1e6)), let since = dirtySince, now - since < cap {
            announceWork?.cancel()
            let work = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.flushAnnouncements() } }
            announceWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: work)
            return
        }
        let ids = dirty
        dirty = []; dirtySince = nil; announceWork = nil
        guard !ids.isEmpty else { return }
        let targets = registry.sessions.filter { $0.peerVersion >= 1 && announced[ObjectIdentifier($0)] != nil }
        guard !targets.isEmpty else { return }
        model.syncAnnouncements(only: ids, excluding: []) { [self] list in
            for s in targets {
                let key = ObjectIdentifier(s)
                let fresh = list.filter { !s.peerTrash.contains($0.id) && announced[key]?[$0.id] != $0.digest }
                guard !fresh.isEmpty else { continue }
                for n in fresh { announced[key]?[n.id] = n.digest }
                log("link: announce ids=\(fresh.map(\.id).joined(separator: ","))")
                let s = s
                s.onExecutor { s.announce(fresh) }
            }
        }
    }

    /// A copy of `id` changed in the library. The shown note is reloaded in place (viewport kept) and its previews are
    /// dropped in the same main-thread block as the swap; a note the phone is viewing and that was waiting for its first
    /// copy opens now.
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
        if !on { padFrame = nil; live.engine.setHoverCanvas(false) }
        else if let mode = padMode { phonePadMode(local: mode.local, pics: mode.pics, aspect: Double(mode.aspect)); return }
        publishTarget()
        pictureChanged()
    }

    /// The pad says how it maps. Local: the pen writes inside a pen frame the Mac draws as an outline; otherwise the
    /// pad is the whole view, as before.
    fileprivate func phonePadMode(local: Bool, pics: Bool, aspect: Double) {
        padMode = (local, pics, PadFrame.cleanAspect(CGFloat(aspect)))
        if local && padOn {
            var frame = padFrame ?? PadFrame(aspect: CGFloat(aspect))
            frame.aspect = PadFrame.cleanAspect(CGFloat(aspect))
            padFrame = frame
        } else { padFrame = nil }
        live.engine.setHoverCanvas(padFrame != nil)
        log("link: padmode local=\(local) pics=\(pics) aspect=\(String(format: "%.3f", aspect))")
        publishTarget()
        pictureChanged()
    }

    /// The phone moved or resized the pen frame. It is sized to fit, the view scrolls so the frame stays in sight, and
    /// the answer is the next `target`.
    fileprivate func phoneFrame(_ wanted: CGRect) {
        guard padOn, var frame = padFrame else { return }
        let sized = frame.sized(wanted, view: live.visibleRect())
        let scroll = PadFrame.scroll(toShow: sized, view: live.visibleRect())
        if abs(scroll.dx) > 0.5 || abs(scroll.dy) > 0.5 { live.scrollBy(scroll.dx, scroll.dy) }
        frame.place(sized, view: live.visibleRect())
        padFrame = frame
        publishTarget()
        pictureChanged()
    }

    private func clearPad() {
        padMode = nil; padFrame = nil; padSaved = 0
        pictureWork?.cancel(); pictureWork = nil; pictureDirtySince = nil; lastPictureHash = 0
        live.engine.setHoverCanvas(false)
        live.showPadFrame(nil)
    }

    /// Call when the active tab, its writability, the shown note or the viewport may have changed.
    func stateChanged() { live.syncTarget(); publishTarget() }

    /// Only while a phone is connected: nothing is recorded or logged for an empty room.
    private func publishTarget() {
        guard !registry.isEmpty else { return }
        publisher.changed()
        live.showPadFrame(padOn ? padFrame?.rect(in: live.visibleRect()) : nil)
        pictureChanged()
    }

    private func desiredTarget() -> TargetSnapshot {
        guard let id = workspace.active, model.shownNote == id, let sheet = model.drawing?.sheetId else { return TargetSnapshot() }
        var target = TargetSnapshot(note: id, sheet: sheet, writable: workspace.activeIsWritable, view: live.viewport())
        if padOn, padMode != nil {
            let visible = live.visibleRect()
            target.visible = visible
            target.frame = padFrame?.rect(in: visible)
            target.pageHeight = Double(live.pageBottom() + visible.height)
        }
        return target
    }

    // MARK: Pictures of what the pad covers

    /// What the pad covers changed, or may have: a picture goes to the phone soon, at most five a second, and waits
    /// while the pen is down (the phone draws its own stroke meanwhile) unless it has been stale for a second and a half.
    func pictureChanged() {
        guard padOn, padMode?.pics == true, !registry.isEmpty else { return }
        if pictureDirtySince == nil { pictureDirtySince = Double(monotonicNs()) / 1e6 }
        guard pictureWork == nil else { return }
        schedulePicture(after: 60)
    }

    private func schedulePicture(after ms: Double) {
        let work = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.pictureWork = nil; self?.sendPicture() } }
        pictureWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + ms / 1000, execute: work)
    }

    private func sendPicture() {
        guard padOn, padMode?.pics == true, let since = pictureDirtySince,
              let session = registry.sessions.first(where: { $0.peerVersion >= 1 }) else { pictureDirtySince = nil; return }
        let now = Double(monotonicNs()) / 1e6
        if now - lastPictureAt < Self.pictureIntervalMs { schedulePicture(after: Self.pictureIntervalMs - (now - lastPictureAt)); return }
        if now - since < 1500, live.engine.strokeOpen || live.engine.penActive(within: 120_000_000) { schedulePicture(after: 100); return }
        guard let region = padRegion(), let image = live.padPicture(region, Self.pictureWidth) else { pictureDirtySince = nil; return }
        pictureDirtySince = nil; lastPictureAt = now
        let rev = publisher.rev, saved = padSaved
        pictureQueue.async { [weak self] in
            guard let jpeg = PadPicture.jpeg(image) else { return }
            var hasher = Hasher()
            hasher.combine(jpeg); hasher.combine(region.minX); hasher.combine(region.minY); hasher.combine(region.width); hasher.combine(saved)
            let hash = hasher.finalize()
            DispatchQueue.main.async { MainActor.assumeIsolated {
                guard let self, self.lastPictureHash != hash, let framed = PadPicture.framed(rev: rev, saved: saved, region: region, image: jpeg) else { return }
                self.lastPictureHash = hash
                self.log("link: picture rev=\(rev) saved=\(saved) bytes=\(jpeg.count) region=\(Int(region.minX)),\(Int(region.minY)),\(Int(region.width)),\(Int(region.height))")
                session.onExecutor { session.sendPicture(framed) }
            } }
        }
    }

    /// The canvas the pad covers: the pen frame in Local mode, else the 4800 by 3000 reference window of the view.
    private func padRegion() -> CGRect? {
        guard workspace.active != nil, model.drawing != nil else { return nil }
        if let frame = padFrame { return frame.rect(in: live.visibleRect()) }
        let v = live.viewport()
        return CGRect(x: CGFloat(v.left), y: CGFloat(v.top), width: 4800 / CGFloat(v.zoom), height: 3000 / CGFloat(v.zoom))
    }

    /// For the debug control: writes the picture the phone would get now to `path` (a JPEG); the region it shows.
    func debugPicture(to path: String) -> CGRect? {
        guard let region = padRegion() ?? Optional(live.visibleRect()), let image = live.padPicture(region, Self.pictureWidth),
              let jpeg = PadPicture.jpeg(image) else { return nil }
        try? jpeg.write(to: URL(fileURLWithPath: path))
        return region
    }

    /// For the debug control: what a `padmode` and a `frame` from the phone do, without a phone.
    func debugPad(local: Bool, aspect: Double, frame: CGRect?) {
        padOn = true
        phonePadMode(local: local, pics: true, aspect: aspect)
        if let frame { phoneFrame(frame) }
        live.showPadFrame(padFrame?.rect(in: live.visibleRect()))
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
