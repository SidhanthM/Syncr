import CryptoKit
import Foundation
import os

// The protocol logic of one phone connection: hello and pairing (3.1), clock (3.2), note transfer
// (3.3) and the pass-through of live frames, viewing and pad messages. No sockets and no UI: bytes
// arrive as decoded frames, replies leave through `IO`, and anything that needs the library or the
// window goes through `LinkSessionHost`. All of it runs on one serial executor (the connection's
// queue in the app, the caller's thread in checks), so none of this state is locked.

enum InstallOutcome: Equatable, Sendable {
    /// A delta merged but left the note short of the phone's (rejected operations, or fewer from some author): ask for the whole note.
    case deltaShort
    /// `digest` and `ops` describe the Mac's operation set afterwards; they go to v1 phones only.
    case installed(seq: UInt64, digest: String? = nil, ops: UInt64? = nil)
    /// The Mac already holds this sequence or a newer one.
    case current(seq: UInt64)
    case refused(String)
}

/// What the Mac has ready to send when the phone wants a note (link v1).
enum ExportOutcome: Sendable {
    /// A staged copy. The session deletes `file` when the transfer ends.
    /// `heads` is what the Mac held per author when it exported: the phone holds exactly that once it reports the same digest.
    case file(url: URL, size: UInt64, sha256: String, seq: UInt64, digest: String, ops: UInt64, delta: Bool, heads: [String: String] = [:])
    case refused(String)
}

protocol LinkSessionHost: AnyObject {
    /// Four digits to show for `phoneName`. Called on the session executor; hop to main to draw it.
    func sessionNeedsCode(_ session: LinkSession, phoneName: String, code: String)
    func sessionCodeEnded(_ session: LinkSession)
    func sessionWelcomed(_ session: LinkSession)
    /// The phone's note list. Reply (any thread) with the IDs to fetch.
    /// `trash` is the phone's Recently deleted (v1 phones), already stored in `session.peerTrash`.
    func sessionNotes(_ session: LinkSession, notes: [NoteAnnounce], trash: [String]?, reply: @escaping @Sendable ([String]) -> Void)
    /// v1: the phone wants this note. Stage a copy of it, or just the operations newer than `base` (the newest operation the
    /// phone holds per author) when that is smaller, and reply (any thread).
    func sessionExport(_ session: LinkSession, id: String, base: [String: String]?, reply: @escaping @Sendable (ExportOutcome) -> Void)
    /// Delta sync: the newest operation ID per author the Mac holds for this note (empty when it has none). Reply on any thread.
    func sessionHeads(_ session: LinkSession, id: String, reply: @escaping @Sendable ([String: String]) -> Void)
    /// v1: the phone installed a note and reports its operation set. Reply true if the Mac still differs and should ask for it.
    func sessionHave(_ session: LinkSession, id: String, digest: String, reply: @escaping @Sendable (Bool) -> Void)
    /// A verified file is staged at `file`. Install it and reply (any thread). The session deletes the file afterwards.
    func sessionInstall(_ session: LinkSession, offer: FileOffer, file: URL, reply: @escaping @Sendable (InstallOutcome) -> Void)
    func sessionViewing(_ session: LinkSession, id: String?)
    func sessionPad(_ session: LinkSession, on: Bool)
    /// The pad's mapping and whether it wants pictures (rework-v1 §V).
    func sessionPadMode(_ session: LinkSession, local: Bool, pics: Bool, aspect: Double)
    /// The phone asks for this pen frame, in canvas units.
    func sessionFrame(_ session: LinkSession, rect: CGRect)
    func sessionPointerMode(_ session: LinkSession, version: Int, on: Bool)
    func sessionPointer(_ session: LinkSession, packet: PointerPacket)
    func sessionClock(_ session: LinkSession, clock: ClockSync)
    func sessionClosed(_ session: LinkSession)
    /// A delta the Mac pushed unasked was refused: the note must be announced the ordinary way. Called on the session executor.
    func sessionPushRefused(_ session: LinkSession, id: String)
    func sessionLog(_ line: String)
}

extension LinkSessionHost {
    func sessionPushRefused(_ session: LinkSession, id: String) {}
    func sessionPointerMode(_ session: LinkSession, version: Int, on: Bool) {}
    func sessionPointer(_ session: LinkSession, packet: PointerPacket) {}
    func sessionHeads(_ session: LinkSession, id: String, reply: @escaping @Sendable ([String: String]) -> Void) { reply([:]) }
    func sessionPadMode(_ session: LinkSession, local: Bool, pics: Bool, aspect: Double) {}
    func sessionFrame(_ session: LinkSession, rect: CGRect) {}
}

final class LinkSession: @unchecked Sendable {
    enum Phase: Equatable { case awaitingHello, pairing, ready, closed }

    struct Config: Sendable {
        var macName: String
        var macId: String
        var epoch: UInt32
        /// Hidden folder inside the library for partial transfers.
        var stagingDir: URL
        /// Where this connection's preview frames may also arrive as datagrams, and the key they start with. nil: none.
        var udpPort: UInt16? = nil
        var udpKey: UInt64 = 0
    }

    struct IO: @unchecked Sendable {
        /// Framed bytes for the socket.
        var send: (Data) -> Void
        /// Flush what was sent, then close.
        var close: () -> Void
        /// A preview or stroke-commit message (kind byte included) and its receive time.
        var liveReceive: (Data, UInt64) -> Void
        /// A new connection starts: new epoch, nothing left over from before. Must have taken effect when it returns.
        var liveReset: (UInt32) -> Void
        /// Runs a block on the session's executor (for replies from other threads).
        var run: (@escaping @Sendable () -> Void) -> Void
        /// Sends one file chunk and calls back when it has left, so control and preview messages queue behind at most one chunk.
        /// nil: no pacing (checks); every chunk is sent at once.
        var sendBulk: ((Data, @escaping @Sendable () -> Void) -> Void)? = nil
        /// True while the phone's pen is writing or hovering. File chunks wait meanwhile; `tick` resumes them.
        var penActive: (() -> Bool)? = nil
        /// Where an incoming file's bytes are written and hashed, in order, off the executor that reads live frames
        /// (remaining-v1 L.5). nil: on the executor (checks).
        var files: ((@escaping @Sendable () -> Void) -> Void)? = nil
        /// Writes one chunk of an incoming file (checks slow it down).
        var diskWrite: @Sendable (FileHandle, Data) throws -> Void = { try $0.write(contentsOf: $1) }
    }

    /// The same note and operation set is not transferred more than this many times a minute in one direction.
    static let transferLimit = 3
    static let transferWindowNs: UInt64 = 60_000_000_000
    static let outgoingTimeoutNs: UInt64 = 60_000_000_000

    /// A transfer small enough to go while the pen is writing (latency-v1 T.3); a larger one waits for the pen to rest.
    static let smallTransfer: UInt64 = 64 * 1024

    private struct Outgoing {
        let offer: FileOffer
        let file: URL
        let handle: FileHandle
        /// What the Mac held when it exported this (see `ExportOutcome.file`), and whether the phone did not ask for it.
        var heads: [String: String] = [:]
        var pushed = false
        var nextIndex: UInt32 = 0
        var endedNs: UInt64?
    }

    /// An incoming file. The executor keeps the count; `hasher`, `handle` writes and `failed` belong to `IO.files`.
    private final class Transfer: @unchecked Sendable {
        let offer: FileOffer
        let file: URL
        let handle: FileHandle
        var hasher = SHA256()
        var failed = false
        var received: UInt64 = 0
        var nextIndex: UInt32 = 0
        init(offer: FileOffer, file: URL, handle: FileHandle) { self.offer = offer; self.file = file; self.handle = handle }
    }

    /// Bytes of incoming chunks handed to `IO.files` and not yet written. The connection stops reading while it is over
    /// `maxFileBacklog`, so a slow disk slows the phone down instead of filling memory.
    private let backlog = OSAllocatedUnfairLock(initialState: UInt64(0))
    var fileBacklog: UInt64 { backlog.withLock { $0 } }
    static let maxFileBacklog: UInt64 = 4 << 20

    /// Runs `block` where file bytes go: `IO.files` in order, or right here.
    private func onFiles(_ block: @escaping @Sendable () -> Void) {
        if let files = io.files { files(block) } else { block() }
    }

    private struct Pairing { var code: String; var expiresNs: UInt64; var failures = 0 }

    let config: Config
    private let store: PairedPhoneStore
    private let io: IO
    weak var host: LinkSessionHost?

    private(set) var phase: Phase = .awaitingHello
    private(set) var phoneId: String?
    private(set) var phoneName = "Phone"
    private(set) var clock = ClockSync()
    private var pairing: Pairing?
    private var startedNs: UInt64 = 0
    private var lastInboundNs: UInt64 = 0
    private var nextPingNs: UInt64 = 0
    private var nextPingId: UInt64 = 1
    private var pendingPings: [UInt64: UInt64] = [:]
    private var requested: [String: UInt64] = [:]
    private var transfer: Transfer?
    private var counters = (files: 0, errors: 0)
    private(set) var peerVersion = 0
    /// The phone said it can send and merge deltas (link v1 only).
    private(set) var peerDelta = false
    /// The phone also takes a delta nobody asked for (latency-v1 T.3).
    private(set) var peerPush = false
    /// What the phone is known to hold, per note: the newest operation per author. Learned from a `want`'s base and, once the phone
    /// confirms a transfer with `have`, from what the Mac held when it exported. Dropped at any refusal; a wrong one only makes a
    /// push fall short.
    private var peerHeads: [String: [String: String]] = [:]
    /// Notes to push whose previous transfer is still in flight (sent when its `have` arrives).
    private var pushLater: Set<String> = []
    private var pushed: Set<String> = []
    /// What the phone said it holds, per note it wants; used once, so a retry always sends a whole copy.
    private var wantBase: [String: [String: String]] = [:]
    /// Notes whose next request must be for a whole copy, because a delta fell short.
    private var fullOnly: Set<String> = []
    /// The phone's Recently deleted, from its last `notes` message (v1).
    private(set) var peerTrash: Set<String> = []
    private var wantQueue: [String] = []
    private var outgoing: Outgoing?
    private var exporting = false
    private var chunkInFlight = false
    private var race = PreviewRace()
    private var pictureInFlight = false
    private var pictureWaiting: Data?
    private var nextXfer: UInt32 = 1
    private var nowNs: UInt64 = 0
    /// Transfer times per "note|operation set", for the loop guard, in each direction.
    private var inStamps: [String: [UInt64]] = [:]
    private var outStamps: [String: [UInt64]] = [:]
    /// Wants the loop guard held back, retried on tick.
    private var deferred: [String: NoteAnnounce] = [:]

    init(config: Config, store: PairedPhoneStore, io: IO) {
        self.config = config; self.store = store; self.io = io
    }

    var isReady: Bool { phase == .ready }
    var transferInProgress: Bool { transfer != nil }

    func start(atNs ns: UInt64) { startedNs = ns; lastInboundNs = ns }

    // MARK: Input

    /// A framing or parsing error: the connection cannot be trusted any further.
    func protocolError(_ error: Error) { log("protocol error: \(error)"); shutdown(bye: nil) }

    func receive(_ frame: LinkFrame, at ns: UInt64) {
        guard phase != .closed else { return }
        lastInboundNs = ns; nowNs = ns
        switch frame {
        case .preview(let bytes):
            guard phase == .ready else { log("live frame before welcome"); shutdown(bye: nil); return }
            for frame in race.stream(bytes) { io.liveReceive(frame, ns) }
        case .strokeCommit(let bytes):
            guard phase == .ready else { log("live frame before welcome"); shutdown(bye: nil); return }
            io.liveReceive(bytes, ns)
        case .fileChunk(let xfer, let index, let data):
            guard phase == .ready else { log("file chunk before welcome"); shutdown(bye: nil); return }
            receiveChunk(xfer: xfer, index: index, data: data)
        case .control(let control):
            handle(control, at: ns)
        }
    }

    private func handle(_ control: LinkControl, at ns: UInt64) {
        switch control {
        case .hello(let v, let device, let id, let token, let delta, let push): receiveHello(v: v, device: device, phoneId: id, token: token, delta: delta, push: push, at: ns)
        case .code(let code): receiveCode(code, at: ns)
        case .bye: shutdown(bye: nil)
        default:
            // Before welcome the Mac accepts only hello and code.
            guard phase == .ready else { return }
            switch control {
            case .ping(let id, let at): send(.pong(id: id, at: at, now: ns / 1_000_000))
            case .pong(let id, _, let now): receivePong(id: id, remoteNow: now, at: ns)
            case .notes(let list, let trash): receiveNotes(list, trash: trash)
            case .want(let ids, let base): receiveWant(ids, base: base)
            case .have(let id, _, let digest, _): receiveHave(id: id, digest: digest)
            case .fileError(let xfer, let id, let reason): receiveFileError(xfer: xfer, id: id, reason: reason)
            case .file(let offer): receiveFile(offer)
            case .fileEnd(let xfer): receiveFileEnd(xfer: xfer)
            case .viewing(let id):
                if let id, !LinkIds.valid(id) { return }
                host?.sessionViewing(self, id: id)
            case .pointerMode(let v, let on): if peerVersion >= 1 { host?.sessionPointerMode(self, version: v, on: on) }
            case .pointer(let packet): if peerVersion >= 1 { host?.sessionPointer(self, packet: packet) }
            case .pad(let on): host?.sessionPad(self, on: on)
            case .padMode(let local, let pics, let aspect): host?.sessionPadMode(self, local: local, pics: pics, aspect: aspect)
            case .frame(let rect): host?.sessionFrame(self, rect: rect)
            default: break  // unknown, or sent in the wrong direction: ignored
            }
        }
    }

    // MARK: Hello and pairing (3.1)

    private func receiveHello(v: Int, device: String?, phoneId id: String?, token: String?, delta: Bool, push: Bool, at ns: UInt64) {
        guard phase == .awaitingHello else { return }
        guard (LinkLimits.oldestVersion...LinkLimits.version).contains(v) else { shutdown(bye: "version"); return }
        peerVersion = v
        peerDelta = delta && v >= 1
        peerPush = peerDelta && push
        guard let id, LinkIds.validHex(id, count: 32) else { log("hello without a valid phone_id"); shutdown(bye: nil); return }
        phoneId = id.lowercased()
        phoneName = Self.cleanName(device)
        if let token, let known = store.phone(id: phoneId!), PairedPhoneStore.sameToken(known.token, token) {
            if known.name != phoneName { store.add(PairedPhone(phoneId: known.phoneId, name: phoneName, token: known.token)) }
            welcome(at: ns)
        } else {
            pairing = Pairing(code: PairedPhoneStore.newCode(), expiresNs: ns + LinkLimits.codeLifetimeNs)
            phase = .pairing
            send(.pair)
            host?.sessionNeedsCode(self, phoneName: phoneName, code: pairing!.code)
        }
    }

    private func receiveCode(_ code: String, at ns: UInt64) {
        guard phase == .pairing, var p = pairing else { return }
        if ns >= p.expiresNs { endPairing(); shutdown(bye: "expired"); return }
        if code == p.code {
            let token = PairedPhoneStore.newToken()
            store.add(PairedPhone(phoneId: phoneId!, name: phoneName, token: token))
            endPairing()
            send(.paired(token: token))
            log("paired phone=\"\(phoneName)\" id=\(phoneId!)")
            welcome(at: ns)
        } else {
            p.failures += 1
            pairing = p
            let left = max(0, LinkLimits.codeTries - p.failures)
            send(.denied(left: left))
            if left == 0 { endPairing(); shutdown(bye: nil) }
        }
    }

    private func endPairing() {
        guard pairing != nil else { return }
        pairing = nil
        host?.sessionCodeEnded(self)
    }

    private func welcome(at ns: UInt64) {
        phase = .ready
        io.liveReset(config.epoch)
        race = PreviewRace()
        send(.welcome(mac: config.macName, macId: config.macId, epoch: config.epoch, v: min(peerVersion, LinkLimits.version), delta: peerVersion >= 1, push: peerVersion >= 1))
        if peerVersion >= 1, let port = config.udpPort, config.udpKey != 0 { send(.udp(port: port, key: String(format: "%016llx", config.udpKey))) }
        nextPingNs = ns
        host?.sessionWelcomed(self)
        if peerVersion >= 1 {
            send(.pointerState(version: 1, ready: false, message: "Choose Trackpad on the phone to request Mac control."))
        }
        sendPing(at: ns)
    }

    /// Display names come from the phone: keep them short and printable.
    static func cleanName(_ raw: String?) -> String {
        let printable = (raw ?? "").unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }
        let text = String(String.UnicodeScalarView(printable)).trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? "Phone" : String(text.prefix(64))
    }

    // MARK: Clock (3.2)

    func tick(atNs ns: UInt64) {
        switch phase {
        case .awaitingHello:
            if ns &- startedNs > LinkLimits.helloTimeoutNs { log("no hello"); shutdown(bye: nil) }
        case .pairing:
            if let p = pairing, ns >= p.expiresNs { endPairing(); shutdown(bye: "expired") }
        case .ready:
            nowNs = ns
            if ns &- lastInboundNs > LinkLimits.idleTimeoutNs { log("idle"); shutdown(bye: nil); return }
            if ns >= nextPingNs { sendPing(at: ns) }
            retryDeferred(at: ns)
            pumpChunks()
            if let out = outgoing, let ended = out.endedNs, ns &- ended > Self.outgoingTimeoutNs {
                log("out-timeout id=\(out.offer.id)"); finishOutgoing(); pumpWanted()
            }
        case .closed: break
        }
    }

    private func sendPing(at ns: UInt64) {
        let id = nextPingId; nextPingId += 1
        pendingPings[id] = ns
        if pendingPings.count > 16, let oldest = pendingPings.min(by: { $0.value < $1.value })?.key { pendingPings[oldest] = nil }
        nextPingNs = ns + LinkLimits.pingIntervalNs
        send(.ping(id: id, at: ns / 1_000_000))
    }

    private func receivePong(id: UInt64, remoteNow: UInt64, at ns: UInt64) {
        guard let sent = pendingPings.removeValue(forKey: id), ns >= sent else { return }
        clock.add(sentMs: Double(sent) / 1e6, rttMs: Double(ns - sent) / 1e6, remoteNowMs: Double(remoteNow))
        host?.sessionClock(self, clock: clock)
    }

    // MARK: Note sync (3.3)

    private func receiveNotes(_ list: [NoteAnnounce], trash: [String]?) {
        if peerVersion >= 1, let trash { peerTrash = Set(trash) }
        guard !list.isEmpty || peerVersion >= 1 else { return }
        host?.sessionNotes(self, notes: list, trash: peerVersion >= 1 ? trash : nil) { [self] ids in
            io.run { [self] in
                guard phase == .ready else { return }
                let by = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { $1 })
                for id in ids { if let n = by[id] { requestNote(n) } }
            }
        }
    }

    /// Asks the phone for a note unless one is on its way for this sequence, or the loop guard says this exact state
    /// was fetched three times in the last minute (then it waits and is retried on tick).
    private func requestNote(_ note: NoteAnnounce, force: Bool = false) {
        if !force, let asked = requested[note.id], note.seq <= asked { return }
        let key = "\(note.id)|\(note.digest ?? String(note.seq))"
        guard allowed(&inStamps, key, nowNs) else {
            if deferred[note.id] == nil { log("guard id=\(note.id) direction=in held-back") }
            deferred[note.id] = note; return
        }
        deferred[note.id] = nil
        requested[note.id] = note.seq
        guard peerDelta, !fullOnly.contains(note.id), let host else { send(.want([note.id])); return }
        // Say what the Mac already holds so the phone can send just the rest. Nothing held asks for it whole.
        let id = note.id
        host.sessionHeads(self, id: id) { [self] heads in
            io.run { [self] in
                guard phase == .ready else { return }
                send(.want([id], base: heads.isEmpty ? nil : [id: heads]))
            }
        }
    }

    private func retryDeferred(at ns: UInt64) {
        for note in deferred.values.sorted(by: { $0.id < $1.id }) {
            let key = "\(note.id)|\(note.digest ?? String(note.seq))"
            if allowed(&inStamps, key, ns, record: false) { deferred[note.id] = nil; requestNote(note, force: true) }
        }
    }

    /// True (and records the attempt) when fewer than `transferLimit` transfers of `key` happened in the last minute.
    private func allowed(_ stamps: inout [String: [UInt64]], _ key: String, _ ns: UInt64, record: Bool = true) -> Bool {
        var list = (stamps[key] ?? []).filter { ns &- $0 < Self.transferWindowNs }
        guard list.count < Self.transferLimit else { stamps[key] = list; return false }
        if record { list.append(ns) }
        stamps[key] = list
        return true
    }

    /// Link v1: the Mac's own notes list (split to fit the message limit). Call on the executor.
    func announce(_ list: [NoteAnnounce]) {
        guard phase == .ready, peerVersion >= 1 else { return }
        for start in stride(from: 0, to: list.count, by: LinkLimits.notesPerMessage) {
            send(.notes(Array(list[start..<min(list.count, start + LinkLimits.notesPerMessage)])))
        }
    }

    // MARK: Mac to phone transfers (link v1)

    /// A note changed on the Mac and the phone is known to hold everything older: queue just the newer operations as if the
    /// phone had asked (latency-v1 T.3). False when it cannot be pushed (no push peer, heads not known): announce instead.
    /// Call on the executor.
    func push(_ id: String) -> Bool {
        guard phase == .ready, peerPush, LinkIds.valid(id), let heads = peerHeads[id] else { return false }
        if outgoing?.offer.id == id { pushLater.insert(id); return true }
        if wantBase[id] == nil { wantBase[id] = heads }
        pushed.insert(id)
        if !wantQueue.contains(id) && wantQueue.count < 2000 { wantQueue.append(id) }
        pumpWanted()
        return true
    }

    private func receiveWant(_ ids: [String], base: [String: [String: String]]?) {
        guard peerVersion >= 1 else { return }
        if peerDelta, let base { for id in ids { if let heads = base[id] { wantBase[id] = heads; peerHeads[id] = heads } } }
        for id in ids where LinkIds.valid(id) && !wantQueue.contains(id) && outgoing?.offer.id != id && wantQueue.count < 2000 { wantQueue.append(id) }
        pumpWanted()
    }

    private func pumpWanted() {
        guard phase == .ready, outgoing == nil, !exporting, !wantQueue.isEmpty, let host else { return }
        let id = wantQueue.removeFirst()
        exporting = true
        host.sessionExport(self, id: id, base: wantBase.removeValue(forKey: id)) { [self] outcome in
            io.run { [self] in
                exporting = false
                guard phase == .ready else {
                    if case .file(let url, _, _, _, _, _, _, _) = outcome { try? FileManager.default.removeItem(at: url) }
                    return
                }
                switch outcome {
                case .refused(let why): log("export-refused id=\(id) reason=\(why)"); pumpWanted()
                case .file(let url, let size, let sha, let seq, let digest, _, let delta, let heads):
                    let key = "\(id)|\(digest)"
                    guard allowed(&outStamps, key, nowNs) else {
                        log("guard id=\(id) direction=out refused")
                        try? FileManager.default.removeItem(at: url); pumpWanted(); return
                    }
                    let chunks = UInt32((size + UInt64(LinkLimits.maxFileChunk) - 1) / UInt64(LinkLimits.maxFileChunk))
                    guard size >= 1, size <= LinkLimits.maxFileBytes, let handle = try? FileHandle(forReadingFrom: url) else {
                        log("export-unusable id=\(id)"); try? FileManager.default.removeItem(at: url); pumpWanted(); return
                    }
                    let offer = FileOffer(xfer: nextXfer, id: id, seq: seq, size: size, sha256: sha, chunks: chunks, delta: delta,
                                          heads: peerDelta && !heads.isEmpty ? heads : nil)
                    nextXfer &+= 1
                    outgoing = Outgoing(offer: offer, file: url, handle: handle, heads: heads, pushed: pushed.remove(id) != nil)
                    counters.files += 1
                    log("out-file id=\(id) xfer=\(offer.xfer) size=\(size) chunks=\(chunks) delta=\(delta) pushed=\(pushed.contains(id) || outgoing?.pushed == true) epoch=\(Int64(Date().timeIntervalSince1970 * 1000))")
                    send(.file(offer))
                    pumpChunks()
                }
            }
        }
    }

    /// One chunk at a time when the socket can say it has sent it; then `file_end`.
    private func pumpChunks() {
        while phase == .ready, var out = outgoing, out.endedNs == nil, !chunkInFlight {
            // A large note copy must not compete with live ink for the phone's radio; the next tick looks again. A small
            // delta (a few typed words) is cheap and goes at once, behind the live frames, which queue ahead of it.
            if io.penActive?() == true, out.offer.size > Self.smallTransfer { return }
            if out.nextIndex >= out.offer.chunks {
                send(.fileEnd(xfer: out.offer.xfer))
                out.endedNs = nowNs; outgoing = out
                try? out.handle.close()
                return
            }
            guard let data = try? out.handle.read(upToCount: LinkLimits.maxFileChunk), !data.isEmpty else {
                log("out-read-failed id=\(out.offer.id)"); finishOutgoing(); pumpWanted(); return
            }
            let framed = LinkFrameDecoder.fileChunk(xfer: out.offer.xfer, index: out.nextIndex, data: data)
            out.nextIndex += 1; outgoing = out
            if let bulk = io.sendBulk {
                chunkInFlight = true
                bulk(framed) { [self] in io.run { [self] in chunkInFlight = false; pumpChunks() } }
                return
            }
            io.send(framed)
        }
    }

    private func finishOutgoing() {
        guard let out = outgoing else { return }
        try? out.handle.close()
        try? FileManager.default.removeItem(at: out.file)
        outgoing = nil
    }

    private func receiveHave(id: String, digest: String?) {
        guard peerVersion >= 1 else { return }
        log("have-in id=\(id) epoch=\(Int64(Date().timeIntervalSince1970 * 1000))")
        if let out = outgoing, out.offer.id == id {
            // The phone applied the whole file, so it holds everything the Mac held when it exported (read in the same step).
            if !out.heads.isEmpty { peerHeads[id] = out.heads }
            finishOutgoing()
        }
        if pushLater.remove(id) != nil { _ = push(id) }
        guard let digest, let host else { pumpWanted(); return }
        host.sessionHave(self, id: id, digest: digest) { [self] differs in
            io.run { [self] in
                guard phase == .ready, differs else { return }
                requestNote(NoteAnnounce(id: id, seq: .max, title: "", digest: digest), force: true)
            }
        }
        pumpWanted()
    }

    private func receiveFileError(xfer: UInt32, id: String, reason: String) {
        guard peerVersion >= 1, outgoing?.offer.xfer == xfer else { return }
        log("out-file-error id=\(id) reason=\(reason)")
        let wasPush = outgoing?.pushed == true
        peerHeads[id] = nil
        finishOutgoing()
        // The phone never asked for a pushed file and has now said no: tell it the note changed, the ordinary way.
        if wasPush { host?.sessionPushRefused(self, id: id) }
        pumpWanted()
    }

    private func receiveFile(_ offer: FileOffer) {
        guard transfer == nil else { sendFileError(offer.xfer, offer.id, "busy"); return }
        let minimumChunks = (offer.size + UInt64(LinkLimits.maxFileChunk) - 1) / UInt64(LinkLimits.maxFileChunk)
        guard LinkIds.valid(offer.id), LinkIds.validHex(offer.sha256, count: 64), offer.size >= 1, offer.size <= LinkLimits.maxFileBytes,
              UInt64(offer.chunks) >= minimumChunks, UInt64(offer.chunks) <= offer.size else {
            sendFileError(offer.xfer, offer.id, "bad offer"); return
        }
        let fm = FileManager.default
        let file = config.stagingDir.appendingPathComponent("xfer-\(offer.xfer)-\(UUID().uuidString).syncr")
        do {
            try fm.createDirectory(at: config.stagingDir, withIntermediateDirectories: true)
            guard fm.createFile(atPath: file.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
            transfer = Transfer(offer: offer, file: file, handle: try FileHandle(forWritingTo: file))
        } catch {
            try? fm.removeItem(at: file)
            sendFileError(offer.xfer, offer.id, "staging")
        }
    }

    private func receiveChunk(xfer: UInt32, index: UInt32, data: Data) {
        guard let t = transfer, t.offer.xfer == xfer else { return }  // late chunk of an abandoned transfer
        guard index == t.nextIndex, index < t.offer.chunks, t.received + UInt64(data.count) <= t.offer.size else {
            failTransfer("chunk"); return
        }
        t.received += UInt64(data.count); t.nextIndex += 1
        let size = UInt64(data.count), write = io.diskWrite, backlog = backlog
        backlog.withLock { $0 += size }
        onFiles {
            if !t.failed {
                do { try write(t.handle, data); t.hasher.update(data: data) } catch { t.failed = true }
            }
            backlog.withLock { $0 -= size }
        }
    }

    private func receiveFileEnd(xfer: UInt32) {
        guard let t = transfer, t.offer.xfer == xfer else { return }
        transfer = nil
        guard t.nextIndex == t.offer.chunks, t.received == t.offer.size else { reject(t, "size"); return }
        // Once every chunk of it is written: the files queue runs in order.
        onFiles { [self] in
            try? t.handle.close()
            let sha = t.failed ? nil : t.hasher.finalize().map({ String(format: "%02x", $0) }).joined()
            io.run { [self] in
                guard phase == .ready else { try? FileManager.default.removeItem(at: t.file); return }
                guard let sha else { reject(t, "write"); return }
                guard sha == t.offer.sha256.lowercased() else { reject(t, "hash"); return }
                install(t)
            }
        }
    }

    private func reject(_ t: Transfer, _ reason: String) {
        discard(t)
        sendFileError(t.offer.xfer, t.offer.id, reason)
    }

    /// Closes and deletes an incoming file once any write of it still queued is done.
    private func discard(_ t: Transfer) {
        onFiles { t.failed = true; try? t.handle.close(); try? FileManager.default.removeItem(at: t.file) }
    }

    private func install(_ t: Transfer) {
        let offer = t.offer, file = t.file
        guard let host else { try? FileManager.default.removeItem(at: file); return }
        host.sessionInstall(self, offer: offer, file: file) { [self] outcome in
            io.run { [self] in
                try? FileManager.default.removeItem(at: file)
                requested[offer.id] = nil
                guard phase == .ready else { return }
                if !offer.delta { fullOnly.remove(offer.id) }
                switch outcome {
                case .installed(let seq, let digest, let ops):
                    // The phone holds at least what it said it held: the next push leaves that out instead of sending it back.
                    if peerPush, let heads = offer.heads { peerHeads[offer.id] = heads }
                    send(peerVersion >= 1 ? .have(id: offer.id, seq: seq, digest: digest, ops: ops) : .have(id: offer.id, seq: seq))
                case .current(let seq): send(.have(id: offer.id, seq: seq))
                case .refused(let why):
                    sendFileError(offer.xfer, offer.id, why)
                    if offer.delta { askForWhole(offer.id) }
                case .deltaShort:
                    log("delta-short id=\(offer.id); asking for the whole note")
                    sendFileError(offer.xfer, offer.id, "delta short")  // frees the phone's transfer slot
                    askForWhole(offer.id)
                }
            }
        }
    }

    /// A delta is never trusted past what it proved: the next request for this note carries no base.
    private func askForWhole(_ id: String) {
        fullOnly.insert(id)
        requestNote(NoteAnnounce(id: id, seq: .max, title: "", digest: nil), force: true)
    }

    private func failTransfer(_ reason: String) {
        guard let t = transfer else { return }
        transfer = nil
        discard(t)
        sendFileError(t.offer.xfer, t.offer.id, reason)
    }

    private func sendFileError(_ xfer: UInt32, _ id: String, _ reason: String) {
        counters.errors += 1
        requested[id] = nil
        log("file-error id=\(id) reason=\(reason)")
        send(.fileError(xfer: xfer, id: id, reason: String(reason.prefix(200))))
    }

    // MARK: The pad's second path and its pictures

    /// A datagram for this connection, without its key. Frames the connection has not delivered yet are drawn now;
    /// the connection's own copies are then skipped. Anything malformed is ignored: the connection still has it all.
    func receiveDatagram(_ payload: Data, at ns: UInt64) {
        guard phase == .ready, let parsed = PreviewRace.parse(payload) else { return }
        for frame in race.datagram(first: parsed.first, frames: parsed.frames) where frame.first == 0x50 { io.liveReceive(frame, ns) }
    }

    /// Frames the datagrams brought before the connection did, for the latency log.
    var datagramFrames: Int { race.fromDatagrams }

    /// A framed picture (`PadPicture.framed`). One travels at a time; a newer one replaces one still waiting.
    func sendPicture(_ framed: Data) {
        guard phase == .ready, peerVersion >= 1 else { return }
        guard !pictureInFlight, let bulk = io.sendBulk else {
            if io.sendBulk == nil { io.send(framed) } else { pictureWaiting = framed }
            return
        }
        pictureInFlight = true
        bulk(framed) { [self] in
            io.run { [self] in
                pictureInFlight = false
                if let next = pictureWaiting { pictureWaiting = nil; sendPicture(next) }
            }
        }
    }

    // MARK: Output and teardown

    func send(_ control: LinkControl) { guard phase != .closed else { return }; io.send(control.framed()) }

    /// Closes, telling the phone why when `bye` is given ("link off", "unpaired"). Call on the executor.
    func close(bye: String?) { shutdown(bye: bye) }

    /// Runs a block on this session's executor; the way other threads reach it.
    func onExecutor(_ block: @escaping @Sendable () -> Void) { io.run(block) }

    private func shutdown(bye: String?) {
        guard phase != .closed else { return }
        if let bye { io.send(LinkControl.bye(reason: bye).framed()) }
        finish()
        io.close()
    }

    /// The socket is gone.
    func connectionClosed() { guard phase != .closed else { return }; finish() }

    private func finish() {
        endPairing()
        if let t = transfer {
            transfer = nil
            nonisolated(unsafe) let host = host  // sessionLog is safe from any thread (the bridge only writes stderr)
            // The partial file goes after any write of it still queued; the line says so once it is gone.
            onFiles {
                t.failed = true; try? t.handle.close(); try? FileManager.default.removeItem(at: t.file)
                host?.sessionLog("link: transfer-aborted id=\(t.offer.id) received=\(t.received) of=\(t.offer.size) partial-file-removed=\(!FileManager.default.fileExists(atPath: t.file.path))")
            }
        }
        finishOutgoing()
        phase = .closed
        host?.sessionClosed(self)
    }

    private func log(_ text: String) { host?.sessionLog("link: \(text)") }
}

/// What the Mac asks of the phone. A v1 phone announces each note's operation-set digest: the Mac asks for any note
/// it lacks or holds a different set of, except one in its own Recently deleted (it would come back from the trash).
/// A v0 phone has only sequences: the Mac asks when the sequence differs from the last one it merged (or, with no
/// record, from the one it holds, which for a note it only ever copied is the same number).
enum LinkSync {
    static func want(announced: [NoteAnnounce], digests: [String: String], refused: Set<String>) -> [String] {
        var seen = Set<String>()
        return announced.compactMap { note in
            guard !refused.contains(note.id), seen.insert(note.id).inserted else { return nil }
            guard let theirs = note.digest else { return nil }
            return digests[note.id] == theirs ? nil : note.id
        }
    }

    static func wantV0(announced: [NoteAnnounce], merged: [String: UInt64], held: [String: UInt64], refused: Set<String>) -> [String] {
        var seen = Set<String>()
        return announced.compactMap { note in
            guard !refused.contains(note.id), seen.insert(note.id).inserted else { return nil }
            return (merged[note.id] ?? held[note.id]) == note.seq ? nil : note.id
        }
    }
}
