import CryptoKit
import Foundation

// The protocol logic of one phone connection: hello and pairing (3.1), clock (3.2), note transfer
// (3.3) and the pass-through of live frames, viewing and pad messages. No sockets and no UI: bytes
// arrive as decoded frames, replies leave through `IO`, and anything that needs the library or the
// window goes through `LinkSessionHost`. All of it runs on one serial executor (the connection's
// queue in the app, the caller's thread in checks), so none of this state is locked.

enum InstallOutcome: Equatable, Sendable {
    case installed(seq: UInt64)
    /// The Mac already holds this sequence or a newer one.
    case current(seq: UInt64)
    case refused(String)
}

protocol LinkSessionHost: AnyObject {
    /// Four digits to show for `phoneName`. Called on the session executor; hop to main to draw it.
    func sessionNeedsCode(_ session: LinkSession, phoneName: String, code: String)
    func sessionCodeEnded(_ session: LinkSession)
    func sessionWelcomed(_ session: LinkSession)
    /// The phone's note list. Reply (any thread) with the IDs to fetch.
    func sessionNotes(_ session: LinkSession, notes: [NoteAnnounce], reply: @escaping @Sendable ([String]) -> Void)
    /// A verified file is staged at `file`. Install it and reply (any thread). The session deletes the file afterwards.
    func sessionInstall(_ session: LinkSession, offer: FileOffer, file: URL, reply: @escaping @Sendable (InstallOutcome) -> Void)
    func sessionViewing(_ session: LinkSession, id: String?)
    func sessionPad(_ session: LinkSession, on: Bool)
    func sessionClock(_ session: LinkSession, clock: ClockSync)
    func sessionClosed(_ session: LinkSession)
    func sessionLog(_ line: String)
}

final class LinkSession: @unchecked Sendable {
    enum Phase: Equatable { case awaitingHello, pairing, ready, closed }

    struct Config: Sendable {
        var macName: String
        var macId: String
        var epoch: UInt32
        /// Hidden folder inside the library for partial transfers.
        var stagingDir: URL
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
    }

    private struct Transfer {
        let offer: FileOffer
        let file: URL
        let handle: FileHandle
        var hasher = SHA256()
        var received: UInt64 = 0
        var nextIndex: UInt32 = 0
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
        lastInboundNs = ns
        switch frame {
        case .preview(let bytes), .strokeCommit(let bytes):
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
        case .hello(let v, let device, let id, let token): receiveHello(v: v, device: device, phoneId: id, token: token, at: ns)
        case .code(let code): receiveCode(code, at: ns)
        case .bye: shutdown(bye: nil)
        default:
            // Before welcome the Mac accepts only hello and code.
            guard phase == .ready else { return }
            switch control {
            case .ping(let id, let at): send(.pong(id: id, at: at, now: ns / 1_000_000))
            case .pong(let id, _, let now): receivePong(id: id, remoteNow: now, at: ns)
            case .notes(let list): receiveNotes(list)
            case .file(let offer): receiveFile(offer)
            case .fileEnd(let xfer): receiveFileEnd(xfer: xfer)
            case .viewing(let id):
                if let id, !LinkIds.valid(id) { return }
                host?.sessionViewing(self, id: id)
            case .pad(let on): host?.sessionPad(self, on: on)
            default: break  // unknown, or sent in the wrong direction: ignored
            }
        }
    }

    // MARK: Hello and pairing (3.1)

    private func receiveHello(v: Int, device: String?, phoneId id: String?, token: String?, at ns: UInt64) {
        guard phase == .awaitingHello else { return }
        guard v == LinkLimits.version else { shutdown(bye: "version"); return }
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
        send(.welcome(mac: config.macName, macId: config.macId, epoch: config.epoch))
        nextPingNs = ns
        host?.sessionWelcomed(self)
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
            if ns &- lastInboundNs > LinkLimits.idleTimeoutNs { log("idle"); shutdown(bye: nil) }
            else if ns >= nextPingNs { sendPing(at: ns) }
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

    private func receiveNotes(_ list: [NoteAnnounce]) {
        guard !list.isEmpty else { return }
        host?.sessionNotes(self, notes: list) { [self] ids in
            io.run { [self] in
                guard phase == .ready else { return }
                let seqs = Dictionary(list.map { ($0.id, $0.seq) }, uniquingKeysWith: max)
                // Do not ask twice for what is already on its way, unless a newer sequence was announced.
                let fresh = ids.filter { id in seqs[id].map { seq in requested[id].map { seq > $0 } ?? true } ?? false }
                for id in fresh { requested[id] = seqs[id] }
                for start in stride(from: 0, to: fresh.count, by: 400) {
                    send(.want(Array(fresh[start..<min(fresh.count, start + 400)])))
                }
            }
        }
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
        guard var t = transfer, t.offer.xfer == xfer else { return }  // late chunk of an abandoned transfer
        guard index == t.nextIndex, index < t.offer.chunks, t.received + UInt64(data.count) <= t.offer.size else {
            failTransfer("chunk"); return
        }
        do { try t.handle.write(contentsOf: data) } catch { failTransfer("write"); return }
        t.hasher.update(data: data)
        t.received += UInt64(data.count); t.nextIndex += 1
        transfer = t
    }

    private func receiveFileEnd(xfer: UInt32) {
        guard let t = transfer, t.offer.xfer == xfer else { return }
        transfer = nil
        try? t.handle.close()
        func reject(_ reason: String) {
            try? FileManager.default.removeItem(at: t.file)
            sendFileError(t.offer.xfer, t.offer.id, reason)
        }
        guard t.nextIndex == t.offer.chunks, t.received == t.offer.size else { reject("size"); return }
        guard t.hasher.finalize().map({ String(format: "%02x", $0) }).joined() == t.offer.sha256.lowercased() else { reject("hash"); return }
        let offer = t.offer, file = t.file
        guard let host else { try? FileManager.default.removeItem(at: file); return }
        host.sessionInstall(self, offer: offer, file: file) { [self] outcome in
            io.run { [self] in
                try? FileManager.default.removeItem(at: file)
                requested[offer.id] = nil
                guard phase == .ready else { return }
                switch outcome {
                case .installed(let seq), .current(let seq): send(.have(id: offer.id, seq: seq))
                case .refused(let why): sendFileError(offer.xfer, offer.id, why)
                }
            }
        }
    }

    private func failTransfer(_ reason: String) {
        guard let t = transfer else { return }
        transfer = nil
        try? t.handle.close()
        try? FileManager.default.removeItem(at: t.file)
        sendFileError(t.offer.xfer, t.offer.id, reason)
    }

    private func sendFileError(_ xfer: UInt32, _ id: String, _ reason: String) {
        counters.errors += 1
        requested[id] = nil
        log("file-error id=\(id) reason=\(reason)")
        send(.fileError(xfer: xfer, id: id, reason: String(reason.prefix(200))))
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
            try? t.handle.close(); try? FileManager.default.removeItem(at: t.file); transfer = nil
            log("transfer-aborted id=\(t.offer.id) received=\(t.received) of=\(t.offer.size) partial-file-removed=\(!FileManager.default.fileExists(atPath: t.file.path))")
        }
        phase = .closed
        host?.sessionClosed(self)
    }

    private func log(_ text: String) { host?.sessionLog("link: \(text)") }
}

/// What the Mac asks of the phone: notes it lacks, or holds at a lower sequence. A Mac-created note is
/// never fetched (the import would refuse it) and neither is one in Recently deleted (it would come
/// back from the trash every time the phone changed it).
enum LinkSync {
    static func want(announced: [NoteAnnounce], held: [String: UInt64], refused: Set<String>) -> [String] {
        var seen = Set<String>()
        return announced.compactMap { note in
            guard !refused.contains(note.id), held[note.id].map({ $0 < note.seq }) ?? true, seen.insert(note.id).inserted else { return nil }
            return note.id
        }
    }
}
