import CoreGraphics
import CryptoKit
import os
import Foundation
import Metal
import Network
import SyncrCore

// Wi-Fi link v0, Mac side (tasks/contracts/wifi-link-v0.md section 6, item 1). Framing and hostile
// input, every shared vector, pairing, clock maths, the sync state machine, mirror mode, the Stream
// target and finally a real loopback socket through the Network.framework layer with a Swift fake
// phone. Everything runs on disposable libraries and binds to 127.0.0.1 only.

private let identity = Affine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0)
private func temp() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("syncr-link-" + UUID().uuidString) }

@MainActor private func pump(_ what: String, timeout: Double = 15, until condition: () -> Bool) throws {
    let end = Date().addingTimeInterval(timeout)
    while !condition() {
        if Date() > end { throw CheckFailure(description: "timed out waiting for \(what)") }
        RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.002))
    }
}

private func frames(_ data: Data) throws -> [LinkFrame] {
    var d = LinkFrameDecoder(); d.append(data)
    var out: [LinkFrame] = []
    while let f = try d.next() { out.append(f) }
    return out
}

private func controls(_ data: Data) -> [LinkControl] {
    ((try? frames(data)) ?? []).compactMap { if case .control(let c) = $0 { c } else { nil } }
}

/// A raw control frame with an arbitrary body.
private func rawFrame(kind: UInt8, _ body: Data) -> Data {
    var d = Data()
    let n = UInt32(body.count + 1)
    d.append(contentsOf: [UInt8(n >> 24 & 255), UInt8(n >> 16 & 255), UInt8(n >> 8 & 255), UInt8(n & 255), kind])
    d.append(body)
    return d
}

private func dictionary(_ c: LinkControl) -> NSDictionary { c.object as NSDictionary }

// MARK: - Framing and hostile input

private func framingChecks() throws {
    func decodeError(_ data: Data) -> LinkProtocolError? {
        do { _ = try frames(data) } catch { return error as? LinkProtocolError }
        return nil
    }
    try check(decodeError(Data([0, 0, 0, 0])) == .badLength(0), "length 0 closes")
    try check(decodeError(Data([0, 1, 0, 1, 0x4A])) == .badLength(65537), "length 65537 closes")
    try check(decodeError(Data([0xff, 0xff, 0xff, 0xff])) == .badLength(Int(UInt32.max)), "length 4 GiB closes before any buffering")
    try check(decodeError(rawFrame(kind: 0x99, Data([1, 2]))) == .unknownKind(0x99), "unknown kind closes")
    try check(decodeError(rawFrame(kind: 0x4A, Data("{".utf8))) != nil, "truncated JSON closes")
    try check(decodeError(rawFrame(kind: 0x4A, Data("[1]".utf8))) != nil, "non-object JSON closes")
    try check(decodeError(rawFrame(kind: 0x4A, Data("{\"x\":1}".utf8))) != nil, "missing t closes")
    try check(decodeError(rawFrame(kind: 0x4A, Data("{\"t\":5}".utf8))) != nil, "non-string t closes")
    try check(decodeError(rawFrame(kind: 0x4A, Data("{\"t\":\"ping\",\"id\":true,\"at\":1}".utf8))) != nil, "boolean where a number belongs closes")
    try check(decodeError(rawFrame(kind: 0x4A, Data("{\"t\":\"ping\",\"id\":-1,\"at\":1}".utf8))) != nil, "negative id closes")
    try check(decodeError(rawFrame(kind: 0x4A, Data("{\"t\":\"ping\",\"id\":1.5,\"at\":1}".utf8))) != nil, "fractional id closes")
    try check(decodeError(rawFrame(kind: 0x4A, Data("{\"t\":\"ping\",\"id\":1e999,\"at\":1}".utf8))) != nil, "overflowing number closes")
    try check(decodeError(rawFrame(kind: 0x4A, Data(String(repeating: "[", count: 60_000).utf8))) != nil, "deeply nested JSON closes, no crash")
    try check(decodeError(rawFrame(kind: 0x4A, Data("{\"t\":\"hello\",\"v\":0,\"phone_id\":\"x\",\"token\":5}".utf8))) != nil, "token of the wrong type closes")
    try check(decodeError(rawFrame(kind: 0x46, Data([0, 0, 0, 1, 0, 0, 0, 0]))) == .badFileChunk, "file chunk with no data closes")
    try check(decodeError(rawFrame(kind: 0x46, Data([0, 0, 0]))) == .badFileChunk, "short file chunk header closes")
    try check(decodeError(rawFrame(kind: 0x46, Data(repeating: 1, count: 8 + 65_001))) == .badFileChunk, "file chunk over 65000 data bytes closes")
    try check(try frames(rawFrame(kind: 0x46, Data(repeating: 1, count: 8 + 65_000))).count == 1, "file chunk of exactly 65000 bytes is fine")
    // Unknown control types are ignored, not fatal; extra fields are fine.
    try check(try frames(rawFrame(kind: 0x4A, Data("{\"t\":\"zzz\",\"extra\":[1,2]}".utf8))) == [.control(.unknown("zzz"))], "unknown t is passed on as unknown")
    // Largest legal message, preview and commit pass through whole, including the kind byte.
    let big = Data([0x50]) + Data(repeating: 7, count: 65_535)
    let parsed = try frames(LinkFrameDecoder.frame(core: big))
    try check(parsed == [.preview(big)], "a 65536-byte preview message is accepted unchanged")
    try check(try frames(LinkFrameDecoder.frame(core: Data([0x43, 1, 2]))) == [.strokeCommit(Data([0x43, 1, 2]))], "stroke-commit passes through")

    // Byte-at-a-time and many-in-one delivery.
    let a = LinkControl.ping(id: 1, at: 2).framed(), b = LinkControl.pair.framed(), c = LinkFrameDecoder.fileChunk(xfer: 9, index: 3, data: Data([1, 2, 3]))
    var slow = LinkFrameDecoder(); var got: [LinkFrame] = []
    for byte in a + b + c { slow.append(Data([byte])); while let f = try slow.next() { got.append(f) } }
    try check(got == [.control(.ping(id: 1, at: 2)), .control(.pair), .fileChunk(xfer: 9, index: 3, data: Data([1, 2, 3]))], "byte-at-a-time delivery")
    try check(try frames(a + b + c).count == 3, "three messages in one buffer")
    var partial = LinkFrameDecoder(); partial.append(Data([0, 0, 0]))
    try check(try partial.next() == nil, "partial header waits")

    // A hostile writer: random bytes in random slices never crash and never buffer without bound.
    var seed: UInt64 = 0x9E3779B97F4A7C15
    func rnd() -> UInt64 { seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17; return seed }
    var decoded = 0, closed = 0
    for round in 0..<3000 {
        var d = LinkFrameDecoder()
        var stream = Data((0..<Int(rnd() % 300)).map { _ in UInt8(truncatingIfNeeded: rnd()) })
        if round % 3 == 0 { stream = rawFrame(kind: [0x4A, 0x46, 0x50, 0x43][Int(rnd() % 4)], stream) + stream }
        var offset = 0
        do {
            while offset < stream.count {
                let n = min(stream.count - offset, 1 + Int(rnd() % 40))
                d.append(stream.subdata(in: offset..<offset + n)); offset += n
                while try d.next() != nil { decoded += 1 }
                if d.buffered > LinkLimits.maxMessage + 4 + 40 { throw CheckFailure(description: "decoder buffered \(d.buffered) bytes") }
            }
        } catch let error as CheckFailure { throw error } catch { closed += 1 }
    }
    try check(closed > 100, "fuzz reached the error paths (\(closed) closes, \(decoded) frames)")
}

// MARK: - Shared vectors

private func vectorChecks() throws {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../shared/link-v0/vectors.json").standardizedFileURL
    let root = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    let vectors = root["vectors"] as! [[String: Any]]
    try check(vectors.count >= 22, "vectors present (\(vectors.count))")
    var controlCount = 0
    for v in vectors {
        let name = v["name"] as! String
        guard let framed = Data(hex: v["framed_hex"] as! String) else { throw CheckFailure(description: "\(name): bad hex") }
        let parsedFrames = try frames(framed)
        try check(parsedFrames.count == 1, "\(name): exactly one frame")
        if name == "file_chunk" {
            let data = Data(hex: v["data_hex"] as! String)!
            try check(parsedFrames[0] == .fileChunk(xfer: UInt32(v["transfer"] as! Int), index: UInt32(v["index"] as! Int), data: data), "\(name): parsed")
            try check(LinkFrameDecoder.fileChunk(xfer: 1, index: 2, data: data) == framed, "\(name): produced bytes identical")
            continue
        }
        guard case .control(let fromBytes) = parsedFrames[0] else { throw CheckFailure(description: "\(name): not a control frame") }
        let json = v["json"] as! [String: Any]
        let fromJSON = try LinkControl.parse(json: JSONSerialization.data(withJSONObject: json))
        try check(fromBytes == fromJSON, "\(name): framed bytes and json agree")
        if case .unknown = fromBytes { throw CheckFailure(description: "\(name): parsed as unknown") }
        // What we produce parses to the same values, and carries the same fields and values as the vector.
        let produced = try frames(fromBytes.framed())
        try check(produced == [.control(fromBytes)], "\(name): round trip")
        try check(dictionary(fromBytes).isEqual(to: json), "\(name): produced JSON equals the vector's values")
        controlCount += 1
    }
    try check(controlCount == vectors.count - 1, "every control vector checked")
    // Link v1 vectors (sync-v1.md): the same rules.
    let v1File = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../shared/link-v0/vectors-v1.json").standardizedFileURL
    let v1 = (try JSONSerialization.jsonObject(with: Data(contentsOf: v1File)) as! [String: Any])["vectors"] as! [[String: Any]]
    for v in v1 {
        let name = v["name"] as! String
        let parsedFrames = try frames(Data(hex: v["framed_hex"] as! String)!)
        guard parsedFrames.count == 1, case .control(let c) = parsedFrames[0] else { throw CheckFailure(description: "\(name): one control frame") }
        try check(try frames(c.framed()) == [.control(c)], "\(name): round trip")
        try check(dictionary(c).isEqual(to: v["json"] as! [String: Any]), "\(name): produced JSON equals the vector's values")
    }
    // Spot values, so a symmetric mistake in parse and encode cannot hide.
    let welcome = try LinkControl.parse(json: Data("{\"t\":\"welcome\",\"v\":0,\"mac\":\"MacBook Air\",\"mac_id\":\"fedcba9876543210fedcba9876543210\",\"epoch\":7}".utf8))
    try check(welcome == .welcome(mac: "MacBook Air", macId: "fedcba9876543210fedcba9876543210", epoch: 7, v: 0), "welcome values")
    let notes = try LinkControl.parse(json: Data("{\"t\":\"notes\",\"notes\":[{\"id\":\"01JCN0TE000000000000000001\",\"seq\":42,\"title\":\"Ünïcode\"},{\"id\":5},{\"id\":\"bad id!\",\"seq\":1}]}".utf8))
    try check(notes == .notes([NoteAnnounce(id: "01JCN0TE000000000000000001", seq: 42, title: "Ünïcode")]), "notes keep good entries and skip bad ones")
    let big = try LinkControl.parse(json: Data("{\"t\":\"have\",\"id\":\"A\",\"seq\":18446744073709551615}".utf8))
    try check(big == .have(id: "A", seq: UInt64.max), "u64 sequence survives")
}

// MARK: - Clock

private func clockChecks() throws {
    var c = ClockSync()
    try check(c.offsetMs == nil && c.macMs(fromPhoneMs: 1) == nil, "no offset before any sample")
    // Phone clock is 5000 ms ahead. A clean 20 ms round trip gives it exactly.
    c.add(sentMs: 100, rttMs: 20, remoteNowMs: 100 + 10 + 5000)
    try check(c.offsetMs == 5000, "offset from a symmetric sample")
    // A slow, lopsided round trip is off by 30 ms; the lowest-RTT sample still wins.
    c.add(sentMs: 200, rttMs: 80, remoteNowMs: 200 + 70 + 5000)
    try check(c.offsetMs == 5000 && c.best?.rttMs == 20 && c.latestRttMs == 80, "lowest round trip chooses the offset")
    try check(c.macMs(fromPhoneMs: 6000) == 1000, "phone ms to Mac ms")
    c.add(sentMs: 300, rttMs: -1, remoteNowMs: 1); c.add(sentMs: 300, rttMs: .nan, remoteNowMs: 1)
    try check(c.samples.count == 2, "nonsense samples are ignored")
    // Only the recent window counts, so drift is followed.
    var d = ClockSync()
    d.add(sentMs: 0, rttMs: 1, remoteNowMs: 0.5 + 100)
    for i in 1...ClockSync.window { d.add(sentMs: Double(i) * 10, rttMs: 5, remoteNowMs: Double(i) * 10 + 2.5 + 140) }
    try check(d.offsetMs == 140, "an old low-RTT sample ages out")

    // Through a session: the Mac pings on welcome, the answer sets the offset.
    let rig = SessionRig()
    try rig.welcome()
    guard case .ping(let id, let at) = rig.controls.last(where: { if case .ping = $0 { true } else { false } }) ?? .pair else {
        throw CheckFailure(description: "Mac sends a ping when it welcomes")
    }
    // Phone clock = Mac clock + 7000 ms; the pong comes back 12 ms later, answered 6 ms in.
    rig.now += 12_000_000
    rig.feed(.pong(id: id, at: at, now: UInt64(Double(rig.now) / 1e6 - 6 + 7000)))
    try check(abs((rig.clocks.last?.offsetMs ?? 0) - 7000) < 1.01, "session derives the offset (\(rig.clocks.last?.offsetMs ?? -1))")
    rig.feed(.pong(id: id, at: at, now: 1))
    try check(rig.clocks.count == 1, "a repeated or unknown pong id is ignored")
    // Phone pings are answered with the Mac's clock.
    rig.feed(.ping(id: 77, at: 123))
    if case .pong(let pid, let pat, let now)? = rig.controls.last {
        try check(pid == 77 && pat == 123 && now == rig.now / 1_000_000, "pong echoes id and at, adds the Mac's clock")
    } else { throw CheckFailure(description: "ping is answered with a pong") }
    // The Mac pings every 2 s and drops a phone that has gone silent.
    let pings = rig.controls.filter { if case .ping = $0 { true } else { false } }.count
    rig.tick(after: 2.1); rig.feed(.ping(id: 1, at: 1))
    try check(rig.controls.filter { if case .ping = $0 { true } else { false } }.count == pings + 1, "Mac pings every 2 s")
    // Idle (no pad, pointer or transfer): quiet for 12 s is gone, as the watchdog's own timer sees it (a tick every half second).
    for _ in 0..<22 { rig.tick(after: 0.5) }
    try check(!rig.closed, "an idle phone silent for 11 s is not dropped")
    for _ in 0..<4 { rig.tick(after: 0.5) }
    try check(rig.closed, "an idle phone silent for 13 s is dropped")
    try check(rig.logs.contains { $0.contains("disconnect cause=watchdog since-inbound=") }, "the drop leaves one line with its cause")
}

/// Link K.1: a quiet link is caught in 5 s when something would be lost, a stalled Mac is never mistaken for a quiet phone.
private func watchdogChecks() throws {
    // A pad is open: 5 s of silence closes it, 4 s does not.
    let pad = SessionRig(); try pad.welcome(v: 1)
    pad.feed(.pad(on: true))
    for _ in 0..<8 { pad.tick(after: 0.5) }
    try check(!pad.closed, "a pad session silent for 4 s stays open")
    for _ in 0..<3 { pad.tick(after: 0.5) }
    try check(pad.closed, "a pad session silent for 5.5 s is closed (watchdog)")
    try check(pad.logs.contains { $0.contains("disconnect cause=watchdog") && $0.contains("active=true") }, "the line says the session was active")

    // Any inbound byte is life, even half a message: a slow note copy never looks dead.
    let slow = SessionRig(); try slow.welcome(v: 1); slow.feed(.pad(on: true))
    for i in 0..<40 { slow.tick(after: 0.5); if i % 6 == 5 { slow.session.noteInbound(at: slow.now) } }
    try check(!slow.closed, "bytes every 3 s keep a pad session alive for 20 s")

    // The Mac itself stalled for 4 s (a long main-thread hop, a debugger): one late tick says nothing about the phone.
    let stalled = SessionRig(); try stalled.welcome(v: 1); stalled.feed(.pad(on: true))
    stalled.tick(after: 0.5); stalled.tick(after: 4.0)
    try check(!stalled.closed, "a tick that came 4 s late does not close the session")
    for _ in 0..<11 { stalled.tick(after: 0.5) }
    try check(stalled.closed, "a phone that stays silent after the stall is still closed")

    // The Mac pings once a second while a pad is open, every two seconds otherwise.
    let busy = SessionRig(); try busy.welcome(v: 1); busy.feed(.pad(on: true))
    let before = busy.controls.filter { if case .ping = $0 { true } else { false } }.count
    for _ in 0..<8 { busy.tick(after: 0.5); busy.session.noteInbound(at: busy.now) }
    try check(busy.controls.filter { if case .ping = $0 { true } else { false } }.count >= before + 2, "pings every second with a pad open")
}

/// Link K.4: the phone's stroke keeping is negotiated, confirmed by ID and placed by the revision it began at.
private func strokeKeepChecks() throws {
    let keep = SessionRig(); keep.feed(.hello(v: 1, device: "Phone", phoneId: keep.phoneId, token: nil, delta: true, push: true, keep: true))
    keep.feed(.code(keep.codes.last?.code ?? ""))
    guard case .welcome(_, _, _, _, _, _, let kept)? = keep.controls.first(where: { if case .welcome = $0 { true } else { false } }) else { throw CheckFailure(description: "no welcome") }
    try check(kept, "a v1 phone that asks to keep strokes is told it may")
    keep.session.sendStrokeAck(id: "S1", ok: true); keep.session.sendStrokeAck(id: "S2", ok: false)
    try check(keep.controls.suffix(2) == [.strokeAck(id: "S1", ok: true), .strokeAck(id: "S2", ok: false)], "the Mac confirms strokes by ID, saved or refused")
    keep.feed(.strokeRev(id: "S1", rev: 42))
    try check(keep.resends.count == 1 && keep.resends[0].0 == "S1" && keep.resends[0].1 == 42, "stroke_rev reaches the engine")

    let plain = SessionRig(); try plain.welcome(v: 1)
    guard case .welcome(_, _, _, _, _, _, let keptPlain)? = plain.controls.first(where: { if case .welcome = $0 { true } else { false } }) else { throw CheckFailure(description: "no welcome") }
    try check(!keptPlain, "a phone that did not ask is not offered it")
    let before = plain.controls.count
    plain.session.sendStrokeAck(id: "S1", ok: true); plain.feed(.strokeRev(id: "S1", rev: 1))
    try check(plain.controls.count == before && plain.resends.isEmpty, "an old phone gets no stroke_ack and its stroke_rev is ignored")

    // The wire forms.
    try check(try LinkControl.parse(json: Data("{\"t\":\"stroke_ack\",\"id\":\"ABC\",\"ok\":true}".utf8)) == .strokeAck(id: "ABC", ok: true), "stroke_ack parses")
    try check(try LinkControl.parse(json: Data("{\"t\":\"stroke_rev\",\"id\":\"ABC\",\"rev\":7}".utf8)) == .strokeRev(id: "ABC", rev: 7), "stroke_rev parses")
    try check((try? LinkControl.parse(json: Data("{\"t\":\"stroke_rev\",\"id\":\"ABC\",\"rev\":-1}".utf8))) == nil, "a negative revision is refused")
    try check((try? LinkControl.parse(json: Data("{\"t\":\"stroke_ack\",\"id\":\"a b\",\"ok\":true}".utf8))) == nil, "a bad stroke ID is refused")
}

// MARK: - Session rig (no socket)

/// Drives one `LinkSession` synchronously: a recording `IO`, a host with canned answers, a manual clock.
private final class SessionRig: LinkSessionHost, @unchecked Sendable {
    let dir: URL
    let store: PairedPhoneStore
    var session: LinkSession!
    var now: UInt64 = 5_000_000_000
    var sent = Data()
    var closed = false
    var live: [Data] = []
    var resetEpochs: [UInt32] = []
    var events: [String] = []
    var codes: [(phone: String, code: String)] = []
    var codeEnds = 0
    var clocks: [ClockSync] = []
    var viewing: [String?] = []
    var pad: [Bool] = []
    var notesSeen: [[NoteAnnounce]] = []
    var trashSeen: [[String]?] = []
    var exportRequests: [String] = []
    var exportBases: [[String: String]?] = []
    var headsAnswer: [String: String] = [:]
    var exportAnswer: (String) -> ExportOutcome = { _ in .refused("none") }
    var haveSeen: [(String, String)] = []
    var haveDiffers = false
    var pushRefused: [String] = []
    var penOn = false
    var logs: [String] = []
    var wantAnswer: ([NoteAnnounce]) -> [String] = { $0.map(\.id) }
    var installAnswer: (FileOffer, URL) -> InstallOutcome = { _, _ in .installed(seq: 1) }
    var installed: [(FileOffer, Int)] = []
    let phoneId = "0123456789abcdef0123456789abcdef"
    var macId = "fedcba9876543210fedcba9876543210"

    var controls: [LinkControl] { controlsList(sent) }

    var resends: [(String, UInt32)] = []
    var padModes: [(local: Bool, pics: Bool, aspect: Double)] = []
    var frames: [CGRect] = []
    var pointerModes: [(Int, Bool)] = []
    var pointerPackets: [PointerPacket] = []
    func sessionPointerMode(_ session: LinkSession, version: Int, on: Bool) { pointerModes.append((version, on)) }
    func sessionPointer(_ session: LinkSession, packet: PointerPacket) { pointerPackets.append(packet) }
    func sessionPadMode(_ session: LinkSession, local: Bool, pics: Bool, aspect: Double) { padModes.append((local, pics, aspect)) }
    func sessionFrame(_ session: LinkSession, rect: CGRect) { frames.append(rect) }

    /// Live frames go here too, on whatever thread `run` delivers them (the bulk check times them).
    var onLive: (@Sendable (Data) -> Void)?

    init(store: PairedPhoneStore? = nil, epoch: UInt32 = 7, dir: URL? = nil, udp: (port: UInt16, key: UInt64)? = nil,
         run: ((@escaping @Sendable () -> Void) -> Void)? = nil, files: ((@escaping @Sendable () -> Void) -> Void)? = nil,
         diskWrite: (@Sendable (FileHandle, Data) throws -> Void)? = nil) {
        let d = dir ?? temp()
        self.dir = d
        self.store = store ?? PairedPhoneStore(folder: d.appendingPathComponent("state"))
        session = LinkSession(config: .init(macName: "Test Mac", macId: macId, epoch: epoch, stagingDir: d.appendingPathComponent("staging"),
                                            udpPort: udp?.port, udpKey: udp?.key ?? 0),
                              store: self.store, io: LinkSession.IO(
            send: { [unowned self] in sent.append($0) },
            close: { [unowned self] in closed = true },
            liveReceive: { [unowned self] data, _ in live.append(data); onLive?(data) },
            liveReset: { [unowned self] epoch in resetEpochs.append(epoch); events.append("reset") },
            run: run ?? { $0() }, sendBulk: nil, penActive: { [unowned self] in penOn },
            expectResend: { [unowned self] id, rev in resends.append((id, rev)) }, files: files,
            diskWrite: diskWrite ?? { try $0.write(contentsOf: $1) }))
        session.host = self
        session.start(atNs: now)
    }
    deinit { try? FileManager.default.removeItem(at: dir) }

    var stagingFiles: [String] { (try? FileManager.default.contentsOfDirectory(atPath: dir.appendingPathComponent("staging").path)) ?? [] }

    func feed(_ c: LinkControl) { session.receive(.control(c), at: now) }
    func feed(_ f: LinkFrame) { session.receive(f, at: now) }
    func tick(after seconds: Double) { now += UInt64(seconds * 1e9); session.tick(atNs: now) }
    func hello(token: String? = nil, v: Int = 0, device: String? = "Galaxy S23 Ultra", delta: Bool = false, push: Bool = false, keep: Bool = false) {
        feed(.hello(v: v, device: device, phoneId: phoneId, token: token, delta: delta, push: push, keep: keep))
    }
    func clearSent() { sent = Data() }
    func controlsOffer() -> FileOffer? { for c in controls { if case .file(let o) = c { return o } }; return nil }

    /// Pairs and welcomes through the normal path. Returns the token.
    @discardableResult func welcome(v: Int = 0, delta: Bool = false, push: Bool = false) throws -> String {
        hello(v: v, delta: delta, push: push)
        let code = codes.last?.code ?? ""
        feed(.code(code))
        guard case .paired(let token)? = controls.first(where: { if case .paired = $0 { true } else { false } }) else {
            throw CheckFailure(description: "pairing did not complete")
        }
        return token
    }

    // LinkSessionHost
    func sessionNeedsCode(_ session: LinkSession, phoneName: String, code: String) { codes.append((phoneName, code)); events.append("code") }
    func sessionCodeEnded(_ session: LinkSession) { codeEnds += 1 }
    func sessionWelcomed(_ session: LinkSession) { events.append("welcomed") }
    func sessionNotes(_ session: LinkSession, notes: [NoteAnnounce], trash: [String]?, reply: @escaping @Sendable ([String]) -> Void) { notesSeen.append(notes); trashSeen.append(trash); reply(wantAnswer(notes)) }
    func sessionExport(_ session: LinkSession, id: String, base: [String: String]?, reply: @escaping @Sendable (ExportOutcome) -> Void) { exportRequests.append(id); exportBases.append(base); reply(exportAnswer(id)) }
    func sessionHeads(_ session: LinkSession, id: String, reply: @escaping @Sendable ([String: String]) -> Void) { reply(headsAnswer) }
    func sessionHave(_ session: LinkSession, id: String, digest: String, reply: @escaping @Sendable (Bool) -> Void) { haveSeen.append((id, digest)); reply(haveDiffers) }
    func sessionInstall(_ session: LinkSession, offer: FileOffer, file: URL, reply: @escaping @Sendable (InstallOutcome) -> Void) {
        installed.append((offer, (try? Data(contentsOf: file).count) ?? -1))
        reply(installAnswer(offer, file))
    }
    func sessionViewing(_ session: LinkSession, id: String?) { viewing.append(id) }
    func sessionPad(_ session: LinkSession, on: Bool) { pad.append(on) }
    func sessionClock(_ session: LinkSession, clock: ClockSync) { clocks.append(clock) }
    func sessionClosed(_ session: LinkSession) { events.append("closed") }
    func sessionLog(_ line: String) { logs.append(line) }
    func sessionPushRefused(_ session: LinkSession, id: String) { pushRefused.append(id) }
}

private func controlsList(_ data: Data) -> [LinkControl] { controls(data) }

// MARK: - Pairing

private func pairingChecks() throws {
    // Unknown phone: pair, a code, wrong digits, then the right ones.
    let rig = SessionRig()
    rig.hello()
    try check(rig.controls == [.pair], "an unpaired hello is answered with pair")
    try check(rig.codes.count == 1 && rig.codes[0].phone == "Galaxy S23 Ultra", "the phone's name goes with the code")
    let code = rig.codes[0].code
    try check(code.count == 4 && code.allSatisfy(\.isNumber), "four digits (\(code))")
    rig.feed(.ping(id: 1, at: 1)); rig.feed(.notes([NoteAnnounce(id: "A", seq: 1, title: "")])); rig.feed(.viewing("A")); rig.feed(.pad(on: true))
    try check(rig.controls == [.pair] && rig.notesSeen.isEmpty && rig.viewing.isEmpty && rig.pad.isEmpty, "before welcome only hello and code are accepted")
    let wrong = code == "0000" ? "0001" : "0000"
    rig.feed(.code(wrong))
    try check(rig.controls.last == .denied(left: 4) && !rig.closed, "a wrong code is denied with 4 left")
    rig.feed(.code(code))
    let kinds = rig.controls.map { c -> String in
        switch c { case .paired: "paired"; case .welcome: "welcome"; case .pair: "pair"; case .denied: "denied"; case .ping: "ping"; default: "other" }
    }
    try check(kinds == ["pair", "denied", "paired", "welcome", "ping"], "right code: paired, then welcome (\(kinds))")
    guard case .paired(let token) = rig.controls[2] else { throw CheckFailure(description: "paired") }
    try check(LinkIds.validHex(token, count: 64), "token is 64 hex characters")
    try check(rig.controls[3] == .welcome(mac: "Test Mac", macId: rig.macId, epoch: 7, v: 0), "welcome carries the epoch (and the phone's version, 0)")
    try check(rig.resetEpochs == [7] && rig.events == ["code", "reset", "welcomed"],
              "the live-ink epoch is set before welcome is announced (\(rig.events))")
    try check(rig.codeEnds == 1 && rig.session.isReady, "code hidden once paired")
    try check(rig.store.phone(id: rig.phoneId)?.token == token && rig.store.phone(id: rig.phoneId)?.name == "Galaxy S23 Ultra", "token and name stored")
    try check(!rig.logs.contains { $0.contains(token) }, "the token is never logged")
    // The store is on disk: a fresh store reads it back.
    try check(PairedPhoneStore(folder: rig.dir.appendingPathComponent("state")).phone(id: rig.phoneId)?.token == token, "pairing survives a restart")
    let mode = try FileManager.default.attributesOfItem(atPath: rig.dir.appendingPathComponent("state/paired-phones.json").path)[.posixPermissions] as? Int
    try check(mode == 0o600, "paired-phones file is private to the user")

    // The stored token welcomes the same phone straight away, on a new connection.
    let again = SessionRig(store: rig.store, epoch: 8)
    again.hello(token: token)
    try check(again.controls.first == .welcome(mac: "Test Mac", macId: again.macId, epoch: 8, v: 0) && again.codes.isEmpty, "stored token: welcome, no code")
    // Wrong, empty and other-phone tokens fall back to pairing.
    let wrongToken = SessionRig(store: rig.store); wrongToken.hello(token: String(repeating: "0", count: 64))
    try check(wrongToken.controls == [.pair], "a wrong token starts pairing")
    let none = SessionRig(store: rig.store); none.hello(token: nil)
    try check(none.controls == [.pair], "a null token starts pairing")
    // Forget: the old token no longer works.
    try check(rig.store.forget(id: rig.phoneId) && rig.store.all.isEmpty, "forget removes the phone")
    let forgotten = SessionRig(store: rig.store); forgotten.hello(token: token)
    try check(forgotten.controls == [.pair], "after forget the old token starts pairing")
    try check(PairedPhoneStore(folder: rig.dir.appendingPathComponent("state")).all.isEmpty, "forget is on disk")

    // Five tries, then the connection closes, and the right code is too late.
    let five = SessionRig(); five.hello()
    let good = five.codes[0].code, bad = good == "9999" ? "9998" : "9999"
    for left in [4, 3, 2, 1, 0] {
        try check(!five.closed, "still open with \(left + 1) tries left")
        five.feed(.code(bad))
        try check(five.controls.last == .denied(left: left), "denied, \(left) left")
    }
    try check(five.closed && five.codeEnds == 1, "five wrong codes close the connection and hide the code")
    five.feed(.code(good))
    try check(five.store.all.isEmpty && !five.controls.contains { if case .paired = $0 { true } else { false } }, "nothing pairs after the fifth miss")

    // Expiry: 120 s, by a late code or by the clock.
    let late = SessionRig(); late.hello()
    late.tick(after: 119)
    try check(!late.closed, "code valid at 119 s")
    late.now += 2_000_000_000; late.feed(.code(late.codes[0].code))
    try check(late.closed && late.controls.last == .bye(reason: "expired") && late.store.all.isEmpty && late.codeEnds == 1, "a code typed after 120 s is refused with bye")
    let clockExpiry = SessionRig(); clockExpiry.hello()
    clockExpiry.tick(after: 121)
    try check(clockExpiry.closed && clockExpiry.controls.last == .bye(reason: "expired") && clockExpiry.codeEnds == 1, "the code expires on the clock")

    // Version, bad identity, silence, hostile order.
    let version = SessionRig(); version.hello(v: 2)
    try check(version.controls == [.bye(reason: "version")] && version.closed, "version mismatch: bye and close")
    let badId = SessionRig(); badId.feed(.hello(v: 0, device: "x", phoneId: "nope", token: nil))
    try check(badId.closed && badId.controls.isEmpty, "a bad phone_id closes")
    let silent = SessionRig(); silent.tick(after: 11)
    try check(silent.closed, "no hello within 10 s closes")
    let early = SessionRig(); early.feed(.preview(Data([0x50, 1])))
    try check(early.closed && early.live.isEmpty, "a preview before welcome closes and reaches nothing")
    let earlyChunk = SessionRig(); earlyChunk.feed(.fileChunk(xfer: 1, index: 0, data: Data([1])))
    try check(earlyChunk.closed, "a file chunk before welcome closes")
    let twice = SessionRig(); twice.hello(); twice.hello()
    try check(twice.codes.count == 1 && twice.controls == [.pair], "a second hello does not restart pairing")
    let hostileName = SessionRig(); hostileName.hello(device: "\u{7}\u{1b}[2J  \(String(repeating: "x", count: 500))")
    try check(hostileName.codes[0].phone.count == 64 && !hostileName.codes[0].phone.unicodeScalars.contains { $0.value < 32 }, "device names are cleaned and bounded")
    let empty = SessionRig(); empty.hello(device: nil)
    try check(empty.codes[0].phone == "Phone", "a missing device name shows as Phone")

    // After welcome: live frames reach the engine untouched, viewing and pad are passed on, bye closes.
    let ready = SessionRig(); try ready.welcome()
    ready.feed(.preview(Data([0x50, 1, 2]))); ready.feed(.strokeCommit(Data([0x43, 9])))
    try check(ready.live == [Data([0x50, 1, 2]), Data([0x43, 9])], "live frames go straight through")
    ready.feed(.viewing("ABC")); ready.feed(.viewing(nil)); ready.feed(.viewing("bad id!")); ready.feed(.pad(on: true)); ready.feed(.pad(on: false))
    try check(ready.viewing == ["ABC", nil] && ready.pad == [true, false], "viewing and pad reach the host (bad IDs do not)")
    ready.feed(.unknown("future")); ready.feed(.welcome(mac: "x", macId: "y", epoch: 1)); ready.feed(.want(["A"]))
    try check(!ready.closed, "unknown and misdirected control messages are ignored")
    ready.feed(.bye(reason: "bye")); try check(ready.closed, "bye from the phone closes")
    ready.feed(.preview(Data([0x50]))); try check(ready.live.count == 2, "nothing is read after close")

    // One connection per phone: the registry hands back the one it displaces.
    final class Dummy {}
    let registry = LinkRegistry<Dummy>(); let first = Dummy(), second = Dummy(), other = Dummy()
    try check(registry.register(phoneId: "p", first) == nil, "first connection registers")
    try check(registry.register(phoneId: "q", other) == nil && registry.sessions.count == 2, "another phone is independent")
    try check(registry.register(phoneId: "p", second) === first, "a new connection from the same phone displaces the old")
    registry.remove(first)
    try check(registry.byPhone["p"] === second, "removing the displaced connection leaves the new one")
    registry.remove(second); registry.remove(other)
    try check(registry.isEmpty, "registry empties")
    // Codes and tokens are random.
    try check(Set((0..<40).map { _ in PairedPhoneStore.newToken() }).count == 40 && Set((0..<200).map { _ in PairedPhoneStore.newCode() }).count > 100, "random codes and tokens")
    try check(PairedPhoneStore.sameToken("ab", "ab") && !PairedPhoneStore.sameToken("ab", "ac") && !PairedPhoneStore.sameToken("ab", "abc"), "token comparison")
}

// MARK: - Delta sync (tasks/contracts/sync-delta.md)

private func bytesOf(_ url: URL) -> Int { ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int) ?? -1 }

@MainActor private func deltaChecks() throws {
    let heads = ["AUTHOR1": "01M7PEC69Z6M1T1WDDDTS8FRSZ"]
    // The wire: additions are written only when used, and read back.
    let hello = try LinkControl.parse(json: Data("{\"t\":\"hello\",\"v\":1,\"device\":\"x\",\"phone_id\":\"0123456789abcdef0123456789abcdef\",\"token\":null,\"delta\":true}".utf8))
    try check(hello == .hello(v: 1, device: "x", phoneId: "0123456789abcdef0123456789abcdef", token: nil, delta: true), "hello reads delta")
    try check(dictionary(.hello(v: 1, device: "x", phoneId: "p", token: nil))["delta"] == nil, "hello without delta writes no field")
    try check(dictionary(.welcome(mac: "m", macId: "i", epoch: 1, v: 1))["delta"] == nil, "welcome without delta writes no field")
    try check(dictionary(.want(["a"]))["base"] == nil && dictionary(.file(FileOffer(xfer: 1, id: "a", seq: 1, size: 1, sha256: "", chunks: 1)))["delta"] == nil, "want and file without additions are the old messages")
    for c: LinkControl in [.want(["A1"], base: ["A1": heads]), .file(FileOffer(xfer: 2, id: "A1", seq: 3, size: 756, sha256: String(repeating: "0", count: 64), chunks: 1, delta: true)),
                           .welcome(mac: "m", macId: "i", epoch: 1, v: 1, delta: true)] {
        try check(try frames(c.framed()) == [.control(c)], "\(c) round trips")
    }
    let hostile = try LinkControl.parse(json: Data("{\"t\":\"want\",\"ids\":[\"A1\"],\"base\":{\"A1\":{\"x\":5},\"bad id\":{\"x\":\"y\"},\"A2\":\"nope\"}}".utf8))
    try check(hostile == .want(["A1"], base: nil), "a malformed base is dropped, the want stays")

    // The session. A delta phone is asked with what the Mac holds; an old one the old way.
    let dir = temp(); defer { try? FileManager.default.removeItem(at: dir) }
    let rig = SessionRig(dir: dir.appendingPathComponent("a"))
    rig.headsAnswer = heads
    try rig.welcome(v: 1, delta: true); rig.clearSent()
    try check(rig.controls.isEmpty && rig.session.peerDelta, "the phone's delta flag is kept")
    rig.feed(.notes([NoteAnnounce(id: "A1", seq: 5, title: "", digest: String(repeating: "a", count: 64))]))
    try check(rig.controls == [.want(["A1"], base: ["A1": heads])], "a delta phone is asked with what the Mac holds (\(rig.controls))")
    let plain = SessionRig(dir: dir.appendingPathComponent("b"))
    plain.headsAnswer = heads
    try plain.welcome(v: 1, delta: false); plain.clearSent()
    plain.feed(.notes([NoteAnnounce(id: "A1", seq: 5, title: "", digest: String(repeating: "a", count: 64))]))
    try check(plain.controls == [.want(["A1"])], "a phone without delta is asked the old way")
    let none = SessionRig(dir: dir.appendingPathComponent("c"))
    try none.welcome(v: 1, delta: true); none.clearSent()
    none.feed(.notes([NoteAnnounce(id: "A1", seq: 5, title: "", digest: String(repeating: "a", count: 64))]))
    try check(none.controls == [.want(["A1"])], "nothing held: the whole note is wanted")

    // The Mac answers a want with a delta when the host staged one.
    let file = dir.appendingPathComponent("d.bin"); try Data(repeating: 7, count: 756).write(to: file)
    let sha = SHA256.hash(data: Data(repeating: 7, count: 756)).map { String(format: "%02x", $0) }.joined()
    rig.exportAnswer = { _ in .file(url: file, size: 756, sha256: sha, seq: 9, digest: String(repeating: "c", count: 64), ops: 4, delta: true) }
    rig.clearSent()
    rig.feed(.want(["A1"], base: ["A1": heads]))
    try check(rig.exportBases.last! == heads, "the host is handed what the phone holds")
    guard case .file(let o)? = rig.controls.first else { throw CheckFailure(description: "no file offer: \(rig.controls)") }
    try check(o.delta && o.size == 756, "the offer says it is a delta")
    let old = SessionRig(dir: dir.appendingPathComponent("e"))
    try old.welcome(v: 1, delta: false); old.clearSent()
    old.exportAnswer = { _ in .refused("none") }
    old.feed(.want(["A1"], base: ["A1": heads]))
    try check(old.exportBases.last! == nil, "a base from a phone that did not say delta is ignored")

    // A delta from the phone: whole is answered with have; short asks for the whole note; refused does too.
    func receive(_ r: SessionRig, xfer: UInt32, delta: Bool) throws {
        let f = dir.appendingPathComponent("in-\(xfer).bin"); try Data(repeating: 1, count: 40).write(to: f)
        let (o, pieces) = try offer(for: f, id: "A1", seq: 3, xfer: xfer)
        send(r, FileOffer(xfer: o.xfer, id: o.id, seq: o.seq, size: o.size, sha256: o.sha256, chunks: o.chunks, delta: delta), pieces)
    }
    rig.installAnswer = { _, _ in .installed(seq: 3, digest: String(repeating: "d", count: 64), ops: 5) }
    rig.clearSent(); try receive(rig, xfer: 1, delta: true)
    try check(rig.controls == [.have(id: "A1", seq: 3, digest: String(repeating: "d", count: 64), ops: 5)], "a whole delta is answered with have")
    rig.installAnswer = { _, _ in .deltaShort }
    rig.clearSent(); try receive(rig, xfer: 2, delta: true)
    try check(rig.controls == [.fileError(xfer: 2, id: "A1", reason: "delta short"), .want(["A1"])], "a delta that fell short: no have, the phone's slot is freed, the whole note is asked for with no base (\(rig.controls))")
    rig.installAnswer = { _, _ in .refused("import: nope") }
    rig.clearSent(); try receive(rig, xfer: 3, delta: true)
    try check(rig.controls.contains(.want(["A1"])) && rig.controls.contains { if case .fileError = $0 { true } else { false } }, "a refused delta: file_error, then the whole note is asked for")
    rig.installAnswer = { _, _ in .installed(seq: 4, digest: String(repeating: "e", count: 64), ops: 6) }
    rig.clearSent(); try receive(rig, xfer: 4, delta: false)
    rig.clearSent()
    rig.feed(.notes([NoteAnnounce(id: "A1", seq: 9, title: "", digest: String(repeating: "b", count: 64))]))
    try check(rig.controls == [.want(["A1"], base: ["A1": heads])], "a whole copy lifts the whole-note-only rule")

    // The core underneath, on real notes: a phone and the Mac model diverge, and deltas bring them together.
    let model = LibraryModel(folder: dir.appendingPathComponent("lib"))
    let phone = try PhoneNote(dir: dir, title: "Shared")
    let first = try phone.export()
    _ = try model.importCopy(from: first.file)
    try phone.addStroke(id: newId(), channels: SampleChannels(x: [1, 2], y: [1, 2], pressure: [0.5, 0.5], elapsedMs: [0, 4], tilt: nil, orientation: nil),
                        brush: Brush(family: .pen, size: .medium, colour: .ink))
    try phone.rename("Phone side")
    // The phone asks the Mac for a delta; the Mac says what it holds.
    var macHeads: [String: String] = [:]
    model.syncHeads(phone.noteId) { macHeads = $0 }
    try pump("heads") { !macHeads.isEmpty }
    let phoneDelta = dir.appendingPathComponent("phone.delta")
    let receipt = try phone.note.exportDelta(heads: macHeads.map { AuthorHead(authorId: $0.key, newestId: $0.value) }, destinationPath: phoneDelta.path)
    try check(receipt.anchored && receipt.operations == 2, "the phone's delta holds its two new operations (\(receipt))")
    var merged: Result<SyncResult, Error>?
    model.syncIncomingDelta(file: phoneDelta, noteId: phone.noteId, phone: "Test Phone") { merged = $0 }
    try pump("delta merged") { merged != nil }
    guard case .success(let r)? = merged else { throw CheckFailure(description: "delta merge failed: \(String(describing: merged))") }
    try check(r.applied == 2 && r.rejected == 0 && !r.short, "the Mac took both operations (\(r))")
    try check(r.digest == (try phone.note.syncDigest().digest), "the digests agree afterwards")
    // The other way: the Mac's rename comes back to the phone as a delta, and as a copy it is far bigger.
    try model.renameNote(id: phone.noteId, title: "Mac side")
    var export: Result<SyncExport, Error>?
    let phoneHeads = try phone.note.syncHeads().reduce(into: [String: String]()) { $0[$1.authorId] = $1.newestId }
    model.syncExport(noteId: phone.noteId, base: phoneHeads) { export = $0 }
    try pump("delta export") { export != nil }
    guard case .success(let e)? = export else { throw CheckFailure(description: "delta export failed") }
    try check(e.delta, "the Mac exports a delta when the phone says what it holds")
    let deltaMerge = try phone.note.mergeDelta(deltaPath: e.file.path)
    try check(deltaMerge.complete && deltaMerge.report.applied == 1 && deltaMerge.report.rejected.isEmpty, "the phone merges the Mac's delta")
    try check(try phone.note.syncDigest().digest == e.digest, "both sides hold the same operations")
    var whole: Result<SyncExport, Error>?
    model.syncExport(noteId: phone.noteId, base: nil) { whole = $0 }
    try pump("copy export") { whole != nil }
    guard case .success(let w)? = whole else { throw CheckFailure(description: "copy export failed") }
    try check(!w.delta && w.size > e.size, "without a base the whole note goes (\(w.size) bytes against \(e.size))")
    print("delta check: whole copy \(w.size) bytes, delta \(e.size) bytes")
}


// MARK: - Push (latency-v1 T.3)

@MainActor private func pushChecks() throws {
    let held = ["AUTHOR1": "01M7PEC69Z6M1T1WDDDTS8FRSZ"], exported = ["AUTHOR1": "01M7PEC69Z6M1T1WDDDTS8FRT0"]
    let digest = String(repeating: "c", count: 64)
    // The wire: push is written only when used and read back, in both messages.
    let hello = try LinkControl.parse(json: Data("{\"t\":\"hello\",\"v\":1,\"device\":\"x\",\"phone_id\":\"0123456789abcdef0123456789abcdef\",\"token\":null,\"delta\":true,\"push\":true}".utf8))
    try check(hello == .hello(v: 1, device: "x", phoneId: "0123456789abcdef0123456789abcdef", token: nil, delta: true, push: true), "hello reads push")
    try check(dictionary(.hello(v: 1, device: "x", phoneId: "p", token: nil, delta: true))["push"] == nil && dictionary(.welcome(mac: "m", macId: "i", epoch: 1, v: 1, delta: true))["push"] == nil, "without push no field is written")
    try check(try frames(LinkControl.welcome(mac: "m", macId: "i", epoch: 1, v: 1, delta: true, push: true).framed()) == [.control(.welcome(mac: "m", macId: "i", epoch: 1, v: 1, delta: true, push: true))], "welcome with push round trips")

    let dir = temp(); defer { try? FileManager.default.removeItem(at: dir) }
    func stage(_ size: Int, to name: String) throws -> (URL, String) {
        let file = dir.appendingPathComponent(name)
        let bytes = Data(repeating: 7, count: size)
        try bytes.write(to: file)
        return (file, SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined())
    }
    func answerWithFile(_ rig: SessionRig, size: Int = 756, delta: Bool = true) {
        rig.exportAnswer = { _ in
            let n = UUID().uuidString
            guard let (f, sha) = try? stage(size, to: n + ".bin") else { return .refused("stage") }
            return .file(url: f, size: UInt64(size), sha256: sha, seq: 9, digest: digest, ops: 4, delta: delta, heads: exported)
        }
    }

    // A phone that did not say push is never pushed to; one that did, only once the Mac knows what it holds.
    let plain = SessionRig(dir: dir.appendingPathComponent("a"))
    try plain.welcome(v: 1, delta: true); plain.clearSent()
    plain.feed(.want(["A1"], base: ["A1": held]))
    try check(!plain.session.push("A1") && !plain.session.peerPush, "a phone without push is not pushed to")
    let rig = SessionRig(dir: dir.appendingPathComponent("b"))
    try rig.welcome(v: 1, delta: true, push: true)
    try check(rig.session.peerPush && dictionary(rig.controls.first { if case .welcome = $0 { true } else { false } }!)["push"] as? Bool == true, "the Mac says push back to a phone that said it")
    rig.clearSent()
    try check(!rig.session.push("A1"), "heads not known: not pushed, the note is announced instead")

    // A want with a base teaches the heads; the phone's `have` for the same operation set keeps them current.
    answerWithFile(rig)
    rig.feed(.want(["A1"], base: ["A1": held]))
    try check(rig.exportBases.last! == held, "the want's base is handed to the host")
    rig.feed(.have(id: "A1", seq: 9, digest: digest, ops: 4))
    rig.clearSent()
    try check(rig.session.push("A1"), "heads known: pushed")
    try check(rig.exportBases.last! == exported, "the push is exported from what the phone held at the last equal digest (\(String(describing: rig.exportBases.last)))")
    guard case .file(let offer)? = rig.controls.first else { throw CheckFailure(description: "a push sends a file offer with no want: \(rig.controls)") }
    try check(offer.delta && offer.size == 756 && offer.id == "A1", "the pushed file is a delta of the note")
    try check(offer.heads == exported, "the offer says what the Mac held when it exported (remaining-v1 L.4)")

    // Another change while that file is in flight waits for its `have`, and then goes from the new heads.
    try check(rig.session.push("A1"), "a second push while one is in flight is accepted")
    let before = rig.exportBases.count
    rig.clearSent()
    rig.feed(.have(id: "A1", seq: 10, digest: digest, ops: 5))
    try check(rig.exportBases.count == before + 1 && rig.exportBases.last! == exported, "it is sent when the first one is confirmed")
    rig.feed(.have(id: "A1", seq: 11, digest: digest, ops: 6))

    // Whatever else the phone holds, a have means it applied the whole file: it holds what the Mac held when it exported.
    rig.feed(.want(["A2"], base: ["A2": held]))
    rig.feed(.have(id: "A2", seq: 9, digest: String(repeating: "f", count: 64), ops: 9))
    rig.clearSent()
    try check(rig.session.push("A2") && rig.exportBases.last! == exported, "a have teaches the heads whatever its digest says")
    rig.feed(.fileError(xfer: rig.controlsOffer()?.xfer ?? 0, id: "A2", reason: "busy"))
    try check(rig.pushRefused == ["A2"], "a refused push is handed back to be announced")
    try check(!rig.session.push("A2"), "and the heads are forgotten, so the next change is announced")

    // A phone file that says what the phone held: once it is installed, the next push leaves those operations out instead of
    // sending the phone's own typing back to it (remaining-v1 L.4).
    let echo = SessionRig(dir: dir.appendingPathComponent("d"))
    try echo.welcome(v: 1, delta: true, push: true)
    answerWithFile(echo)
    let phoneHeld = ["AUTHOR1": "01M7PEC69Z6M1T1WDDDTS8FRT5", "PHONE": "01M7PEC69Z6M1T1WDDDTS8FRT6"]
    let bytes = Data(repeating: 4, count: 300), sha = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    echo.feed(.file(FileOffer(xfer: 2, id: "A1", seq: 4, size: 300, sha256: sha, chunks: 1, delta: true, heads: phoneHeld)))
    echo.feed(.fileChunk(xfer: 2, index: 0, data: bytes))
    echo.feed(.fileEnd(xfer: 2))
    echo.clearSent()
    try check(echo.session.push("A1") && echo.exportBases.last! == phoneHeld, "the phone's file teaches what it holds (\(String(describing: echo.exportBases.last)))")
    try check(try frames(LinkControl.file(FileOffer(xfer: 2, id: "A1", seq: 4, size: 300, sha256: sha, chunks: 1, delta: true, heads: phoneHeld)).framed())
              == [.control(.file(FileOffer(xfer: 2, id: "A1", seq: 4, size: 300, sha256: sha, chunks: 1, delta: true, heads: phoneHeld)))], "heads round trip")

    // Size decides whether the pen holds a transfer: a small delta goes while the pen is writing, a large copy waits.
    let pen = SessionRig(dir: dir.appendingPathComponent("c"))
    try pen.welcome(v: 1, delta: true, push: true)
    pen.penOn = true
    answerWithFile(pen, size: 756)
    pen.clearSent()
    pen.feed(.want(["A1"], base: ["A1": held]))
    try check(pen.sent.count > 756, "a small delta is sent while the pen is writing (\(pen.sent.count) bytes)")
    pen.feed(.have(id: "A1", seq: 9, digest: digest, ops: 4))
    answerWithFile(pen, size: 70_000, delta: false)
    pen.clearSent()
    pen.feed(.want(["A1"]))
    try check(pen.sent.count < 70_000, "a large copy waits while the pen is writing (\(pen.sent.count) bytes)")
    pen.penOn = false
    pen.tick(after: 0.1)
    try check(pen.sent.count > 70_000, "and goes when the pen has rested (\(pen.sent.count) bytes)")

}

// MARK: - A phone's note, as the phone would export it

private final class PhoneNote {
    let dir: URL
    let library: SyncrLibrary
    let note: Note
    let noteId: String
    let sheetId: String
    let sheetOp: String
    private var exports = 0

    init(dir: URL, title: String, noteId: String = newId()) throws {
        self.dir = dir
        self.noteId = noteId
        library = try openLibrary(rootPath: dir.appendingPathComponent("phone-\(UUID().uuidString)").path)
        sheetId = newId()
        let first = newId()
        _ = try library.createNote(request: CreateNoteRequest(operationId: first, noteId: noteId, firstSheetId: sheetId, title: title, notebook: "Phone", kind: .note))
        note = try library.openNote(noteId: noteId)
        sheetOp = try note.snapshot().sheets.first!.creationOperationId
    }
    deinit { try? note.closeNote() }

    func addStroke(id: String, channels: SampleChannels, brush: Brush) throws {
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [sheetOp],
            body: .marks(mutations: [.addStroke(markId: id, sheetId: sheetId, samples: try encodeSamples(channels: channels), brush: brush)])))
    }
    func deleteStroke(id: String) throws {
        let head = try note.snapshot().strokes.first { $0.id == id }!.head.operationId
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [head],
            body: .marks(mutations: [.deleteStroke(markId: id, base: .operation(operationId: head))])))
    }
    func rename(_ title: String) throws {
        let head = try note.snapshot().metadataHead
        let base: Base = head.confirmedRevision.map { .confirmed(revision: $0) } ?? .operation(operationId: head.operationId)
        _ = try note.submit(command: Command(operationId: newId(), dependencies: [head.operationId], body: .renameNote(title: title, base: base)))
    }
    func export() throws -> (file: URL, seq: UInt64) {
        exports += 1
        let file = dir.appendingPathComponent("\(noteId)-export-\(exports).syncr")
        _ = try note.exportCopy(destinationPath: file.path)
        return (file, try note.snapshot().localSequence)
    }
}

private func offer(for file: URL, id: String, seq: UInt64, xfer: UInt32, chunkSize: Int = 30_000) throws -> (FileOffer, [Data]) {
    let bytes = try Data(contentsOf: file)
    let pieces = stride(from: 0, to: bytes.count, by: chunkSize).map { bytes.subdata(in: $0..<min(bytes.count, $0 + chunkSize)) }
    let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    return (FileOffer(xfer: xfer, id: id, seq: seq, size: UInt64(bytes.count), sha256: hash, chunks: UInt32(pieces.count)), pieces)
}

private func send(_ rig: SessionRig, _ offer: FileOffer, _ pieces: [Data], end: Bool = true) {
    rig.feed(.file(offer))
    for (i, p) in pieces.enumerated() { rig.feed(.fileChunk(xfer: offer.xfer, index: UInt32(i), data: p)) }
    if end { rig.feed(.fileEnd(xfer: offer.xfer)) }
}

// MARK: - Sync

@MainActor private func syncChecks() throws {
    // The want computations. v0: by sequence against the last merged one (or the held one); v1: by digest.
    let ann = [NoteAnnounce(id: "A", seq: 5, title: ""), NoteAnnounce(id: "B", seq: 5, title: ""), NoteAnnounce(id: "C", seq: 5, title: ""),
               NoteAnnounce(id: "D", seq: 5, title: ""), NoteAnnounce(id: "E", seq: 5, title: ""), NoteAnnounce(id: "A", seq: 6, title: "")]
    try check(LinkSync.wantV0(announced: ann, merged: ["C": 4], held: ["B": 5, "C": 5, "D": 9], refused: ["E"]) == ["A", "C", "D"],
              "v0 want: unknown or changed sequences, once, never refused ones; the merged record wins over the held sequence")
    let d1 = String(repeating: "a", count: 64), d2 = String(repeating: "b", count: 64)
    let v1ann = [NoteAnnounce(id: "A", seq: 1, title: "", digest: d1), NoteAnnounce(id: "B", seq: 1, title: "", digest: d1),
                 NoteAnnounce(id: "C", seq: 1, title: "", digest: d1), NoteAnnounce(id: "E", seq: 1, title: "", digest: d1),
                 NoteAnnounce(id: "N", seq: 1, title: "")]
    try check(LinkSync.want(announced: v1ann, digests: ["B": d1, "C": d2], refused: ["E"]) == ["A", "C"],
              "v1 want: missing notes and different digests only, never refused ones or entries without a digest")

    let dir = temp(); defer { try? FileManager.default.removeItem(at: dir) }
    let model = LibraryModel(folder: dir.appendingPathComponent("lib"))
    let phone = try PhoneNote(dir: dir, title: "Phone note")
    let v1 = try phone.export()
    try phone.rename("Renamed on phone")
    let v2 = try phone.export()
    try check(v2.seq > v1.seq, "fixture: the second export has a higher sequence")

    let rig = SessionRig(dir: dir.appendingPathComponent("rig"))
    rig.installAnswer = { offer, file in
        MainActor.assumeIsolated {
            do { let s = try model.importCopy(from: file); return .installed(seq: s.localSequence) }
            catch { return .refused("import: \(error.localizedDescription)") }
        }
    }
    rig.wantAnswer = { notes in MainActor.assumeIsolated {
        LinkSync.wantV0(announced: notes, merged: [:], held: Dictionary(model.notes.map { ($0.id, $0.localSequence) }, uniquingKeysWith: max), refused: [])
    } }
    try rig.welcome(); rig.clearSent()

    // want, then transfer, then have.
    rig.feed(.notes([NoteAnnounce(id: phone.noteId, seq: v1.seq, title: "Phone note")])) 
    try check(rig.controls == [.want([phone.noteId])], "an unknown note is wanted")
    rig.feed(.notes([NoteAnnounce(id: phone.noteId, seq: v1.seq, title: "")]))
    try check(rig.controls == [.want([phone.noteId])], "a note already requested is not requested twice")
    let (o1, p1) = try offer(for: v1.file, id: phone.noteId, seq: v1.seq, xfer: 1)
    try check(p1.count >= 2, "fixture spans several chunks (\(p1.count))")
    rig.clearSent()
    send(rig, o1, p1)
    try check(rig.controls == [.have(id: phone.noteId, seq: v1.seq)], "transfer installs and answers have (\(rig.controls))")
    try check(model.notes.map(\.id) == [phone.noteId] && model.isWritable(phone.noteId), "the note is in the library, and writable on the Mac")
    try check(rig.stagingFiles.isEmpty, "the staged file is gone after install")
    try check(rig.installed.first?.1 == Int(o1.size), "the host was handed the whole verified file")
    try check(!rig.session.transferInProgress, "no transfer left open")

    // Nothing wanted when current; wanted again at a higher sequence even though the first request completed.
    rig.clearSent()
    rig.feed(.notes([NoteAnnounce(id: phone.noteId, seq: v1.seq, title: "")]))
    try check(rig.controls.isEmpty, "a current note is not wanted")
    rig.feed(.notes([NoteAnnounce(id: phone.noteId, seq: v2.seq, title: "")]))
    try check(rig.controls == [.want([phone.noteId])], "a higher sequence is wanted")

    // Hash mismatch: error, nothing left behind, nothing changed; a retry with good bytes works.
    let (o2, p2) = try offer(for: v2.file, id: phone.noteId, seq: v2.seq, xfer: 2)
    var corrupt = p2; corrupt[0][10] ^= 0xFF
    rig.clearSent()
    send(rig, o2, corrupt)
    try check(rig.controls == [.fileError(xfer: 2, id: phone.noteId, reason: "hash")], "corrupt bytes: file_error hash")
    try check(rig.stagingFiles.isEmpty && model.notes.first?.title == "Phone note", "nothing installed, nothing left in staging")
    // Size mismatch (a chunk missing), out-of-order chunk, too many bytes, and a bad offer.
    rig.clearSent()
    send(rig, o2, Array(p2.dropLast()))
    try check(rig.controls == [.fileError(xfer: 2, id: phone.noteId, reason: "size")] && rig.stagingFiles.isEmpty, "a missing chunk: file_error size")
    rig.clearSent()
    rig.feed(.file(o2)); rig.feed(.fileChunk(xfer: 2, index: 1, data: p2[1]))
    try check(rig.controls == [.fileError(xfer: 2, id: phone.noteId, reason: "chunk")] && rig.stagingFiles.isEmpty && !rig.session.transferInProgress, "an out-of-order chunk aborts and cleans up")
    rig.clearSent()
    var tooBig = o2; tooBig.size = UInt64(p2[0].count)
    rig.feed(.file(tooBig)); rig.feed(.fileChunk(xfer: 2, index: 0, data: p2[0] + Data([1])))
    try check(rig.controls == [.fileError(xfer: 2, id: phone.noteId, reason: "chunk")] && rig.stagingFiles.isEmpty, "more bytes than the offer: aborted")
    for (name, bad) in [("oversize", FileOffer(xfer: 3, id: "A", seq: 1, size: LinkLimits.maxFileBytes + 1, sha256: o2.sha256, chunks: 10_000)),
                        ("zero size", FileOffer(xfer: 3, id: "A", seq: 1, size: 0, sha256: o2.sha256, chunks: 1)),
                        ("bad hash", FileOffer(xfer: 3, id: "A", seq: 1, size: 10, sha256: "zz", chunks: 1)),
                        ("too few chunks", FileOffer(xfer: 3, id: "A", seq: 1, size: 200_000, sha256: o2.sha256, chunks: 2)),
                        ("more chunks than bytes", FileOffer(xfer: 3, id: "A", seq: 1, size: 3, sha256: o2.sha256, chunks: 4)),
                        ("bad id", FileOffer(xfer: 3, id: "../x", seq: 1, size: 3, sha256: o2.sha256, chunks: 1))] {
        rig.clearSent(); rig.feed(.file(bad))
        try check(rig.controls == [.fileError(xfer: 3, id: bad.id, reason: "bad offer")] && !rig.session.transferInProgress, "\(name): offer refused")
    }
    // A stale chunk or file_end for nothing is ignored; a second offer while busy is refused without disturbing the first.
    rig.clearSent()
    rig.feed(.fileChunk(xfer: 99, index: 0, data: Data([1]))); rig.feed(.fileEnd(xfer: 99))
    try check(rig.controls.isEmpty, "late chunks of no transfer are ignored")
    rig.feed(.file(o2))
    rig.feed(.file(FileOffer(xfer: 4, id: "Z", seq: 1, size: 10, sha256: o2.sha256, chunks: 1)))
    try check(rig.controls == [.fileError(xfer: 4, id: "Z", reason: "busy")] && rig.session.transferInProgress, "a second offer while busy is refused")
    for (i, p) in p2.enumerated() { rig.feed(.fileChunk(xfer: 2, index: UInt32(i), data: p)) }
    rig.clearSent(); rig.feed(.fileEnd(xfer: 2))
    try check(rig.controls == [.have(id: phone.noteId, seq: v2.seq)] && model.notes.first?.title == "Renamed on phone", "merge-when-newer: the first transfer still completed")
    try check(rig.stagingFiles.isEmpty, "staging empty")
    // An older copy sent anyway: have with the Mac's sequence, library unchanged.
    rig.clearSent()
    let (oOld, pOld) = try offer(for: v1.file, id: phone.noteId, seq: v1.seq, xfer: 5)
    send(rig, oOld, pOld)
    try check(rig.controls == [.have(id: phone.noteId, seq: v2.seq)] && model.notes.first?.title == "Renamed on phone", "an older copy is merged as all duplicates and answered have with the Mac's sequence")
    // The connection dropping mid-transfer leaves nothing.
    rig.feed(.file(o2)); rig.feed(.fileChunk(xfer: 2, index: 0, data: p2[0]))
    try check(!rig.stagingFiles.isEmpty, "a partial transfer is staged while it runs")
    rig.session.connectionClosed()
    try check(rig.stagingFiles.isEmpty && rig.events.last == "closed", "a dropped connection deletes the partial file")

    // A different note under a Mac note's ID (another creation) is refused by the merge; the Mac note is untouched.
    let macId = try model.createNote(title: "Mine", notebook: nil)
    let impostor = try PhoneNote(dir: dir, title: "Impostor", noteId: macId)
    let imp = try impostor.export()
    let rig2 = SessionRig(epoch: 9, dir: dir.appendingPathComponent("rig2"))
    rig2.installAnswer = rig.installAnswer; rig2.wantAnswer = rig.wantAnswer
    try rig2.welcome(); rig2.clearSent()
    rig2.feed(.notes([NoteAnnounce(id: macId, seq: imp.seq + 100, title: "")]))
    let (oImp, pImp) = try offer(for: imp.file, id: macId, seq: imp.seq, xfer: 1)
    send(rig2, oImp, pImp)
    guard case .fileError(1, macId, let reason)? = rig2.controls.last else { throw CheckFailure(description: "Mac note offer is refused (\(rig2.controls))") }
    try check(reason.hasPrefix("import:") && rig2.stagingFiles.isEmpty, "merging a different note's copy is refused: \(reason)")
    try model.open(id: macId)
    try check(model.drawing?.title == "Mine" && model.isWritable(macId), "the Mac note is untouched")

    // Reload in place: the shown note swaps to the newer copy without ever being empty, viewport untouched by the model.
    let dir2 = temp(); defer { try? FileManager.default.removeItem(at: dir2) }
    let m2 = LibraryModel(folder: dir2.appendingPathComponent("lib"))
    _ = try m2.importCopy(from: v1.file)
    try m2.open(id: phone.noteId)
    let before = m2.drawing, generation = m2.generation
    _ = try m2.importCopy(from: v2.file)
    var emptyTurns = 0, swappedIds: Set<String>?
    m2.reloadInPlace(id: phone.noteId) { swappedIds = $0 }
    try pump("reload in place") { if m2.drawing == nil { emptyTurns += 1 }; return swappedIds != nil }
    try check(emptyTurns == 0 && m2.drawing !== before && m2.drawing?.title == "Renamed on phone" && m2.generation > generation, "reload swaps a new drawing in, never an empty one")
    m2.reloadInPlace(id: "someone-else") { _ in swappedIds = ["wrong"] }
    for _ in 0..<100 { RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.002)) }
    try check(swappedIds != ["wrong"], "reloading a note that is not shown does nothing")
}

// MARK: - A Metal rig for mirror and Stream

private let rigWidth = 1200, rigHeight = 750

@MainActor private final class MirrorRig {
    let dir = temp()
    let model: LibraryModel
    let workspace: Workspace
    let live: LiveInkCoordinator
    let renderer: MeshRenderer
    let texture: MTLTexture
    let driver: ReplayDriver
    var viewport = Viewport() { didSet { live.viewChanged(viewport) } }
    var engine: LiveInk { live.engine }

    init() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { throw CheckFailure(description: "Metal device unavailable") }
        model = LibraryModel(folder: dir.appendingPathComponent("lib"))
        workspace = Workspace(model: model)
        workspace.restoreSession()
        live = LiveInkCoordinator(model: model, workspace: workspace, device: device, debug: false)
        renderer = try MeshRenderer(device: device)
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: rigWidth, height: rigHeight, mipmapped: false)
        desc.usage = [.renderTarget, .shaderRead]; desc.storageMode = .shared
        guard let texture = device.makeTexture(descriptor: desc) else { throw CheckFailure(description: "Metal texture unavailable") }
        self.texture = texture
        driver = ReplayDriver(engine: live.engine)
        live.viewport = { [unowned self] in self.viewport }
        // As the window wires it: opening a tab loads the note.
        workspace.onActivate = { [unowned self] id in if let id { model.openAsync(id: id) } else { model.clearDrawing() } }
        live.syncTarget(); live.viewChanged(viewport)
    }
    deinit { try? FileManager.default.removeItem(at: dir) }

    func openAndWait(_ id: String) throws {
        workspace.open(id)
        try pump("note loaded") { !model.loading && model.shownNote == id && model.drawing?.sheetId != nil }
        live.syncTarget(); engine.waitIdle()
    }

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
}

private func inkPixels(_ bytes: [UInt8]) -> Int {
    let b = bytes[0], g = bytes[1], r = bytes[2], a = bytes[3]
    var n = 0
    // Same pixel oracle without allocating an Array per pixel before the latency measurement.
    for i in stride(from: 0, to: bytes.count, by: 4) {
        if bytes[i] != b || bytes[i + 1] != g || bytes[i + 2] != r || bytes[i + 3] != a { n += 1 }
    }
    return n
}

private func previewSteps(_ plan: ReplayPlan) -> [ReplayStep] { plan.steps.filter { $0.preview != nil } }

// MARK: - Mirror

@MainActor private func mirrorChecks() throws {
    let rig = try MirrorRig()
    let phone = try PhoneNote(dir: rig.dir, title: "Mirrored")
    let v1 = try phone.export()
    _ = try rig.model.importCopy(from: v1.file)
    try rig.openAndWait(phone.noteId)
    let copy = rig.model.folder.appendingPathComponent("\(phone.noteId).syncr")
    let bytes = try Data(contentsOf: copy)
    try check(rig.workspace.activeIsWritable, "the phone copy is writable on the Mac (link v1)")
    let strokeId = newId()
    let plan = try ReplayGenerator.plan(kind: .pen, noteId: phone.noteId, sheetId: phone.sheetId, epoch: rig.engine.epoch, view: rig.viewport, strokeId: strokeId)

    // A phone preview still draws over the note, and nothing is written (the phone's own copy is what lands later).
    rig.live.mirrorEnabled = true; rig.engine.waitIdle()
    let steps = previewSteps(plan)
    try rig.feed(steps.prefix(steps.count / 2))
    try check(rig.engine.currentFrame.strokes.count == 1, "mirror: the preview is drawn over the note")
    let partial = inkPixels(try rig.render())
    try check(partial > 500, "mirror: there is ink on screen (\(partial))")
    try check(rig.model.drawing?.strokes.isEmpty == true, "mirror: the drawing is untouched")
    try rig.feed(steps.dropFirst(steps.count / 2))
    try check(inkPixels(try rig.render()) > partial, "mirror: the stroke grows")
    try check(rig.engine.stats.commitsSaved == 0, "mirror: previews write nothing")
    try check(try Data(contentsOf: copy) == bytes, "mirror: the note on disk is byte-identical")
    try check(rig.engine.currentFrame.strokes.count == 1, "mirror: the ended preview is still held, waiting for its copy")
    // A second, ended stroke the next copy will not contain stays through the swap.
    let otherId = newId()
    let other = try ReplayGenerator.plan(kind: .highlighter, noteId: phone.noteId, sheetId: phone.sheetId, epoch: rig.engine.epoch, view: rig.viewport, strokeId: otherId)
    try rig.feed(previewSteps(other).prefix(20))
    try check(rig.engine.currentFrame.strokes.count == 2, "two previews held")

    // The newer copy contains the first stroke. Install it and swap, observing every main-thread turn.
    try phone.addStroke(id: strokeId, channels: plan.channels, brush: Brush(family: .pen, size: .medium, colour: .blue))
    let v2 = try phone.export()
    let old = rig.model.drawing
    _ = try rig.model.importCopy(from: v2.file)
    let inkBefore = inkPixels(try rig.render())
    var both = 0, blank = 0, neither = 0, done = false
    rig.model.reloadInPlace(id: phone.noteId) { ids in rig.engine.finishMirror(strokeIds: ids); done = true }
    try pump("swap") {
        let inDrawing = !(rig.model.drawing?.strokes.isEmpty ?? true)
        let wet = rig.engine.currentFrame.strokes.contains { $0.id == strokeId }
        if inDrawing && wet { both += 1 }
        if !inDrawing && !wet { neither += 1 }
        if rig.model.drawing == nil { blank += 1 }
        return done
    }
    rig.engine.waitIdle()
    try check(both == 0 && blank == 0 && neither == 0, "swap: no turn with the stroke twice, in neither place, or with no drawing (\(both)/\(neither)/\(blank))")
    try check(rig.model.drawing !== old && rig.model.drawing?.strokes.count == 1, "swap: the new copy is shown with its stroke")
    let frame = rig.engine.currentFrame
    try check(!frame.strokes.contains { $0.id == strokeId }, "swap: the mirrored stroke's preview is gone")
    try check(frame.strokes.contains { $0.id == otherId }, "swap: a preview the copy does not contain stays")
    let after = try rig.render(wet: false)
    try check(inkPixels(after) > 500 && inkPixels(after) >= inkBefore / 2, "swap: the saved stroke is on screen (\(inkPixels(after)) px, was \(inkBefore))")
    // Late frames of the swapped stroke cannot bring it back; a Cancel for the other one drops it.
    try rig.feed(steps.suffix(4))
    try check(!rig.engine.currentFrame.strokes.contains { $0.id == strokeId }, "swap: late frames do not resurrect the stroke")
    // The phone saves the other stroke and erases it before the next copy: the copy holds it deleted, and its
    // preview goes with that copy instead of lingering until the hold expires.
    try phone.addStroke(id: otherId, channels: other.channels, brush: Brush(family: .highlighter, size: .medium, colour: .ink))
    try phone.deleteStroke(id: otherId)
    _ = try rig.model.importCopy(from: try phone.export().file)
    var erasedSwap = false
    rig.model.reloadInPlace(id: phone.noteId) { ids in rig.engine.finishMirror(strokeIds: ids); erasedSwap = true }
    try pump("erased swap") { erasedSwap }
    rig.engine.waitIdle()
    try check(!rig.engine.currentFrame.strokes.contains { $0.id == otherId } && rig.model.drawing?.strokes.count == 1,
              "swap: a stroke erased on the phone before the copy leaves no preview behind")
    // Ending the link clears everything.
    rig.live.mirrorEnabled = false; rig.engine.reset(); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.isEmpty, "link ended: the wet layer is cleared")
    // An ended preview with no copy within 10 s just expires.
    rig.live.mirrorEnabled = true; rig.engine.waitIdle()
    let lone = try ReplayGenerator.plan(kind: .pen, noteId: phone.noteId, sheetId: phone.sheetId, epoch: rig.engine.epoch, view: rig.viewport)
    try rig.feed(previewSteps(lone))
    try check(rig.engine.currentFrame.strokes.count == 1, "an ended preview is held")
    rig.engine.tick(atNs: monotonicNs() + 11_000_000_000); rig.engine.waitIdle()
    try check(rig.engine.currentFrame.strokes.isEmpty, "mirror: no copy within the hold, the preview expires")
}

// MARK: - Stream target and viewport revisions

@MainActor private func targetChecks() throws {
    var sent: [LinkControl] = [], recorded: [(UInt32, Viewport)] = [], clock = 0.0
    var scheduled: [(due: Double, block: @MainActor () -> Void)] = []
    var state = TargetSnapshot(note: "N", sheet: "S", writable: true, view: Viewport())
    let p = TargetPublisher()
    p.current = { state }; p.send = { sent.append($0) }; p.recordView = { recorded.append(($0, $1)) }
    p.now = { clock }; p.schedule = { ms, block in scheduled.append((clock + ms, block)) }
    func advance(_ ms: Double) { clock += ms; let due = scheduled.filter { $0.due <= clock }; scheduled.removeAll { $0.due <= clock }; due.forEach { $0.block() } }

    p.changed()
    try check(sent == [.target(TargetInfo(note: "N", sheet: "S", writable: true, rev: 1))] && recorded.count == 1 && recorded[0].0 == 1, "first state goes out at once as rev 1")
    p.changed(); try check(sent.count == 1, "no change, no message")
    // A scroll burst: many changes in a few ms produce one more message, with the final viewport.
    for i in 1...40 { clock += 0.2; state.view = Viewport(left: Float(i) * 10, top: Float(i) * 5, zoom: 1); p.changed() }
    try check(sent.count == 1 && scheduled.count == 1, "a burst is held back, one wake-up scheduled (\(sent.count) sent)")
    advance(60)
    try check(sent.count == 2 && recorded.count == 2, "the trailing state is sent")
    try check(sent[1] == .target(TargetInfo(note: "N", sheet: "S", writable: true, rev: 2)) && recorded[1].0 == 2 && recorded[1].1 == state.view, "it carries the final viewport as rev 2")
    // Idle then one change: immediate. Writability alone does not bump rev.
    advance(500)
    state.writable = false; p.changed()
    try check(sent.last == .target(TargetInfo(note: "N", sheet: "S", writable: false, rev: 2)), "writability change: sent, same rev")
    advance(500)
    state.view.left = 99; p.changed()
    try check(p.rev == 3 && recorded.last?.0 == 3, "a viewport change bumps rev")
    // No note, then a note again.
    advance(500); state = TargetSnapshot(); p.changed()
    try check(sent.last == .target(nil), "no active note: note null")
    advance(500); state = TargetSnapshot(note: "M", sheet: "T", writable: true, view: Viewport()); p.changed()
    try check(sent.last == .target(TargetInfo(note: "M", sheet: "T", writable: true, rev: 4)), "new note: new rev")
    // A new connection hears the state again.
    advance(500); let count = sent.count; p.reset(); p.changed()
    try check(sent.count == count + 1, "after reset the state is sent again")
    // The final state is always sent even if the last change lands inside the window.
    advance(500); state.view.top = 1; p.changed(); advance(10); state.view.top = 2; p.changed(); advance(10); state.view.top = 3; p.changed()
    advance(100)
    try check({ if case .target(let t?)? = sent.last { return t.rev == p.rev } else { return false } }() && p.sent == state, "the last state of a burst is what the phone ends up with")

    // The viewport named by a Begin's rev is used when still known, else the viewport at Begin.
    let rig = try MirrorRig()
    let id = try rig.workspace.newNote()
    try rig.openAndWait(id)
    let sheet = rig.model.drawing!.sheetId!
    let known = Viewport(left: 300, top: 500, zoom: 1), current = Viewport(left: 900, top: 100, zoom: 1)
    rig.engine.recordView(known, rev: 5)
    rig.viewport = current
    rig.engine.waitIdle()
    func minX(_ rev: UInt32) throws -> Float {
        rig.engine.reset(); rig.engine.waitIdle()
        var plan = try ReplayGenerator.plan(kind: .viewRelative, noteId: id, sheetId: sheet, epoch: rig.engine.epoch, view: current)
        plan = ReplayPlan(kind: plan.kind, strokeId: plan.strokeId, steps: plan.steps.map { step in
            guard var f = step.preview, case .begin(let brush, _, let space) = f.body else { return step }
            f.body = .begin(brush: brush, viewportRevision: rev, space: space)
            return ReplayStep(atMs: step.atMs, preview: f, commit: nil)
        }, channels: plan.channels)
        try rig.feed(previewSteps(plan))
        return rig.engine.currentFrame.strokes.flatMap(\.pieces).map(\.minX).min() ?? .nan
    }
    let atKnown = try minX(5), atBegin = try minX(99)
    try check(abs((atKnown - atBegin) - (known.left - current.left)) < 0.5, "rev 5 uses the viewport recorded for it, an unknown rev the one at Begin (\(atKnown - atBegin))")
    rig.engine.clearRecordedViews(); rig.engine.reset(); rig.engine.waitIdle()
    try check(abs((try minX(5)) - atBegin) < 0.5, "after clearing, rev 5 falls back to the viewport at Begin")
}

// MARK: - Fake phone over a real socket

private final class FakePhone: @unchecked Sendable {
    private let nw: NWConnection
    private let queue = DispatchQueue(label: "fake-phone")
    private let lock = NSLock()
    private var inbox: [LinkControl] = []
    private var isClosed = false
    private var wasRefused = false
    private var decoder = LinkFrameDecoder()
    /// This phone's monotonic clock runs this far ahead of the Mac's.
    let clockOffsetMs = 1000.0
    var phoneNowMs: Double { Double(monotonicNs()) / 1e6 + clockOffsetMs }

    init(port: UInt16) {
        let tcp = NWProtocolTCP.Options(); tcp.noDelay = true
        nw = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: NWParameters(tls: nil, tcp: tcp))
        nw.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.markClosed() }
            if case .cancelled = state { self?.markClosed() }
            if case .waiting = state { self?.lock.lock(); self?.wasRefused = true; self?.lock.unlock() }
        }
        nw.start(queue: queue)
        loop()
    }
    private func markClosed() { lock.lock(); isClosed = true; lock.unlock() }
    private func loop() {
        nw.receive(minimumIncompleteLength: 1, maximumLength: 70_000) { [weak self] data, _, complete, error in
            guard let self else { return }
            if let data {
                decoder.append(data)
                while let frame = try? decoder.next() {
                    if case .control(let c) = frame {
                        if case .ping(let id, let at) = c { send(.pong(id: id, at: at, now: UInt64(phoneNowMs))); continue }
                        lock.lock(); inbox.append(c); lock.unlock()
                    }
                }
            }
            if complete || error != nil { markClosed(); return }
            loop()
        }
    }
    func send(_ c: LinkControl) { nw.send(content: c.framed(), completion: .contentProcessed { _ in }) }
    func sendRaw(_ d: Data) { nw.send(content: d, completion: .contentProcessed { _ in }) }
    func sendCore(_ d: Data) { sendRaw(LinkFrameDecoder.frame(core: d)) }
    var closed: Bool { lock.lock(); defer { lock.unlock() }; return isClosed }
    /// The connection could not be made (nothing listening).
    var refused: Bool { lock.lock(); defer { lock.unlock() }; return wasRefused }
    var received: [LinkControl] { lock.lock(); defer { lock.unlock() }; return inbox }
    /// Removes and returns the first received message that matches.
    func take(_ match: (LinkControl) -> Bool) -> LinkControl? {
        lock.lock(); defer { lock.unlock() }
        guard let i = inbox.firstIndex(where: match) else { return nil }
        return inbox.remove(at: i)
    }
    func close() { nw.cancel() }
}

private func isTarget(_ c: LinkControl, note: String, writable: Bool) -> Bool {
    if case .target(let t) = c, let t { return t.note == note && t.writable == writable }
    return false
}

@MainActor private func loopbackEndToEnd() throws {
    let rig = try MirrorRig()
    let model = rig.model, workspace = rig.workspace
    let link = LinkController(model: model, workspace: workspace, live: rig.live,
                              options: .init(port: nil, loopbackOnly: true, advertise: false))
    var shownCode: (phone: String, code: String)?
    link.onPairing = { shownCode = $0 }
    workspace.onChange = { link.stateChanged() }
    model.onChange = { link.stateChanged() }
    defer { link.stop() }
    try check(link.enabled && link.follow, "link and Follow phone are on by default")
    link.start()
    try pump("listening") { link.port != nil }
    guard let port = link.port else { return }
    try check(link.status == .waiting(port: port), "no phone yet: waiting")

    // Pair: wrong code, then the one on the screen.
    let phone = FakePhone(port: port)
    let phoneId = "aabbccddeeff00112233445566778899"
    phone.send(.hello(v: 0, device: "Test Phone", phoneId: phoneId, token: nil))
    var gotPair = false
    try pump("pair request") { if phone.take({ $0 == .pair }) != nil { gotPair = true }; return gotPair && shownCode != nil }
    try check(shownCode?.phone == "Test Phone" && link.status == .pairing(phone: "Test Phone"), "the Mac shows the code with the phone's name")
    let code = shownCode!.code
    phone.send(.code(code == "0000" ? "0001" : "0000"))
    try pump("denied") { phone.take { $0 == .denied(left: 4) } != nil }
    phone.send(.code(code))
    var token = "", epoch: UInt32 = 0
    try pump("paired and welcome") {
        if case .paired(let t)? = phone.take({ if case .paired = $0 { true } else { false } }) { token = t }
        if case .welcome(_, _, let e, _, _, _, _)? = phone.take({ if case .welcome = $0 { true } else { false } }) { epoch = e }
        return !token.isEmpty && epoch != 0
    }
    try check(shownCode == nil, "the code is hidden once paired")
    try pump("connected") { link.status == .connected(phone: "Test Phone") }
    try check(link.store.phone(id: phoneId)?.token == token && link.hasPairedPhones, "the pairing is stored")
    try check(rig.live.engine.epoch == epoch && rig.live.mirrorEnabled, "the live-ink epoch is the welcome's")
    try pump("first target") { phone.take { $0 == .target(nil) } != nil }

    // Clock: the Mac pings and learns the offset (the phone's clock is 1000 ms ahead).
    try pump("clock offset", timeout: 5) { link.status == .connected(phone: "Test Phone") && rig.live.mirrorEnabled && abs((rig.live.engine.clockOffset ?? 0) - phone.clockOffsetMs) < 5 }

    // Sync a fixture note.
    let source = try PhoneNote(dir: rig.dir, title: "Synced note")
    let v1 = try source.export()
    phone.send(.notes([NoteAnnounce(id: source.noteId, seq: v1.seq, title: "Synced note")]))
    try pump("want") { phone.take { $0 == .want([source.noteId]) } != nil }
    let (o1, p1) = try offer(for: v1.file, id: source.noteId, seq: v1.seq, xfer: 1)
    phone.send(.file(o1))
    for (i, piece) in p1.enumerated() { phone.sendRaw(LinkFrameDecoder.fileChunk(xfer: 1, index: UInt32(i), data: piece)) }
    phone.send(.fileEnd(xfer: 1))
    try pump("have") { phone.take { $0 == .have(id: source.noteId, seq: v1.seq) } != nil }
    try check(model.notes.contains { $0.id == source.noteId } && workspace.sidebar.contains { $0.notes.contains { $0.id == source.noteId } }, "the synced note is in the library and the sidebar")
    try check((try? FileManager.default.contentsOfDirectory(atPath: rig.model.folder.appendingPathComponent(".incoming-link").path))?.isEmpty ?? true, "staging area is empty")

    // Follow phone: viewing opens it in a tab.
    phone.send(.viewing(source.noteId))
    try pump("follow") { workspace.active == source.noteId && model.shownNote == source.noteId && model.drawing?.sheetId != nil }
    try pump("target for the copy") { phone.take { isTarget($0, note: source.noteId, writable: true) } != nil }
    try check(link.follow && workspace.tabs.contains(source.noteId), "Follow phone opened the note in a tab")
    // With Follow off the note is not opened; back on, it is.
    workspace.closeTab(source.noteId)
    link.setFollow(false)
    phone.send(.viewing(nil)); phone.send(.viewing(source.noteId))
    try pump("viewing seen") { link.viewing == source.noteId }
    try check(workspace.active == nil, "Follow phone off: the note stays closed")
    link.setFollow(true)
    phone.send(.viewing(source.noteId))
    try pump("follow again") { workspace.active == source.noteId && model.shownNote == source.noteId && model.drawing?.sheetId != nil }
    rig.live.syncTarget(); rig.engine.waitIdle()

    // Mirror a stroke live over the socket. The frames carry the phone's clock in sent_ms.
    let strokeId = newId()
    var view = rig.viewport; view.left = 0
    let plan = try ReplayGenerator.plan(kind: .pen, noteId: source.noteId, sheetId: source.sheetId, epoch: epoch, view: view, strokeId: strokeId)
    let queue = PreviewSendQueue()
    for step in previewSteps(plan) {
        guard var frame = step.preview else { continue }
        frame.sentMs = UInt64(phone.phoneNowMs)
        try queue.pushPreview(frame: frame)
        while let bytes = queue.pop() { phone.sendCore(bytes) }
        Thread.sleep(forTimeInterval: 0.002)
    }
    try pump("mirrored stroke") { rig.engine.currentFrame.strokes.contains { $0.id == strokeId } }
    try check(model.drawing?.strokes.isEmpty == true, "mirror over the socket: nothing written")
    try check(inkPixels(try rig.render()) > 500, "mirror over the socket: ink on screen")
    let taken = rig.engine.take()
    try rig.renderer.render(model.drawing, wet: taken.frame, viewport: rig.viewport, dark: false, target: rig.texture,
                            stage: rig.engine.stageSink(taken.times, sent: taken.sent))
    let latency = rig.engine.phoneLatency.summary()
    try check(latency.count > 0 && latency.p50 > -5 && latency.p50 < 1000, "phone-send to Mac-presented is measured and plausible (n=\(latency.count), p50 \(latency.p50) ms)")
    link.logLatency("check")

    // The newer copy lands: swap, no doubled ink.
    try source.addStroke(id: strokeId, channels: plan.channels, brush: Brush(family: .pen, size: .medium, colour: .blue))
    let v2 = try source.export()
    phone.send(.notes([NoteAnnounce(id: source.noteId, seq: v2.seq, title: "")]))
    try pump("want v2") { phone.take { $0 == .want([source.noteId]) } != nil }
    let (o2, p2) = try offer(for: v2.file, id: source.noteId, seq: v2.seq, xfer: 2)
    phone.send(.file(o2))
    for (i, piece) in p2.enumerated() { phone.sendRaw(LinkFrameDecoder.fileChunk(xfer: 2, index: UInt32(i), data: piece)) }
    var both = 0
    phone.send(.fileEnd(xfer: 2))
    try pump("swap over the socket") {
        let inDrawing = !(model.drawing?.strokes.isEmpty ?? true), wet = rig.engine.currentFrame.strokes.contains { $0.id == strokeId }
        if inDrawing && wet { both += 1 }
        return inDrawing && !wet
    }
    try check(both == 0 && model.drawing?.strokes.count == 1, "the copy replaced the preview with no doubled ink")
    try pump("have v2") { phone.take { $0 == .have(id: source.noteId, seq: v2.seq) } != nil }

    // Tablet Stream: the pad opens, the Mac makes a note, the phone draws into it.
    // A phone note is writable on the Mac now, so with it open the pad would write into it: close it so the pad makes a note.
    workspace.closeTab(source.noteId)
    phone.send(.pad(on: true))
    var target: TargetInfo?
    try pump("stream target") {
        if case .target(let t)? = phone.take({ if case .target(let t) = $0 { t?.writable == true } else { false } }) { target = t }
        return target != nil
    }
    guard let target else { return }
    try check(workspace.active == target.note && model.isWritable(target.note), "pad on: a Mac-created note is open and writable")
    let known = rig.viewport
    let relative = try ReplayGenerator.plan(kind: .viewRelative, noteId: target.note, sheetId: target.sheet, epoch: epoch, view: known)
    for step in relative.steps {
        if var frame = step.preview {
            if case .begin(let brush, _, let space) = frame.body { frame.body = .begin(brush: brush, viewportRevision: target.rev, space: space) }
            frame.sentMs = UInt64(phone.phoneNowMs)
            try queue.pushPreview(frame: frame)
        }
        if let commit = step.commit { try queue.pushCommit(commit: commit) }
        while let bytes = queue.pop() { phone.sendCore(bytes) }
        Thread.sleep(forTimeInterval: 0.002)
    }
    try pump("stream stroke saved") { rig.engine.stats.commitsSaved == 1 }
    try check(model.drawing?.strokes.count == 1 && rig.engine.currentFrame.strokes.isEmpty, "Stream: the stroke is drawn in the Mac note and its preview is gone")
    let macNote = target.note
    try model.open(id: macNote)
    try check(model.drawing?.strokes.count == 1, "Stream: the stroke is on disk in the Mac note")
    phone.send(.pad(on: false))

    // A second connection from the same phone replaces the first.
    let phone2 = FakePhone(port: port)
    phone2.send(.hello(v: 0, device: "Test Phone", phoneId: phoneId, token: token))
    try pump("replacement welcome") { phone2.take({ if case .welcome = $0 { true } else { false } }) != nil }
    try pump("old connection closed") { phone.closed }
    try check(link.status == .connected(phone: "Test Phone"), "still one connected phone")
    // Dropping it recovers to waiting; mirror is off and previews are cleared; reconnecting with the token just works.
    phone2.close()
    try pump("disconnect") { link.status == .waiting(port: port) && !rig.live.mirrorEnabled }
    rig.engine.waitIdle()
    try check(rig.engine.currentFrame.isEmpty, "disconnect clears the live layer")
    let phone3 = FakePhone(port: port)
    phone3.send(.hello(v: 0, device: "Test Phone", phoneId: phoneId, token: token))
    try pump("reconnect") { phone3.take({ if case .welcome = $0 { true } else { false } }) != nil }
    // A hostile peer: bad length closes it, the others are unaffected.
    let hostile = FakePhone(port: port)
    hostile.sendRaw(Data([0xff, 0xff, 0xff, 0xff, 1]))
    try pump("hostile closed") { hostile.closed }
    try check(!phone3.closed && link.status == .connected(phone: "Test Phone"), "a hostile connection does not disturb the paired one")
    // Forget: the phone is told, and its old token no longer works.
    link.forget(phoneId: phoneId)
    try pump("bye unpaired") { phone3.take { $0 == .bye(reason: "unpaired") } != nil }
    try pump("closed after forget") { phone3.closed }
    try check(!link.hasPairedPhones, "forgotten")
    let phone4 = FakePhone(port: port)
    phone4.send(.hello(v: 0, device: "Test Phone", phoneId: phoneId, token: token))
    try pump("pair again") { phone4.take { $0 == .pair } != nil }
    // Link off: the phone is told and the listener goes away.
    link.setEnabled(false)
    try pump("bye link off") { phone4.take { $0 == .bye(reason: "link off") } != nil || phone4.closed }
    try pump("listener closed") { link.port == nil && link.status == .off }
    let refused = FakePhone(port: port)
    try pump("no listener", timeout: 5) { refused.refused || refused.closed }
    link.setEnabled(true)
    try pump("listening again") { link.port != nil }
    try check(link.enabled, "link back on")
}

// MARK: - The pad: pen frame, pictures, the datagram path (rework-v1 §V)

private func padChecks() throws {
    // The frame is fractions of the view: it starts at half the width, centred, at the top, in the pad's shape.
    let view = CGRect(x: -598, y: 0, width: 3200, height: 2283)
    var frame = PadFrame(aspect: 2.05)
    let usable = PadFrame.usable(view)
    try check(usable == CGRect(x: 0, y: 0, width: 2602, height: 2283), "only paper is usable: the view's part left of the canvas is not (\(usable))")
    let first = frame.rect(in: view)
    try check(abs(first.width - 1280) < 0.5 && abs(first.width / first.height - 2.05) < 1e-6 && abs(first.midX - 1301) < 0.5 && first.minY == 0, "default frame \(first)")
    try check(abs(frame.rect(in: view.offsetBy(dx: 598, dy: 0)).width - first.width) < 1e-6, "panning onto more paper does not resize the frame")
    // It follows the Mac's scrolling and zoom, and stays inside the view.
    let scrolled = frame.rect(in: view.offsetBy(dx: 0, dy: 900))
    try check(scrolled.minY == 900 && scrolled.width == first.width, "frame follows a scroll (\(scrolled))")
    let zoomed = frame.rect(in: CGRect(x: 0, y: 0, width: 1600, height: 1100))
    try check(abs(zoomed.width - 640) < 0.5 && zoomed.maxX <= 1600, "frame follows a zoom (\(zoomed))")
    // A request keeps its centre, takes the pad's shape and is limited to the view and to the smallest width.
    let asked = frame.sized(CGRect(x: 300, y: 200, width: 900, height: 400), view: view)
    try check(abs(asked.width - 900) < 1e-6 && abs(asked.midX - 750) < 1e-6 && abs(asked.midY - 400) < 1e-6 && abs(asked.height - 900 / 2.05) < 1e-6, "sized request \(asked)")
    try check(frame.sized(CGRect(x: 0, y: 0, width: 5, height: 5), view: view).width == PadFrame.minWidth, "smallest frame")
    let widest = frame.sized(CGRect(x: 0, y: 0, width: 99_999, height: 10), view: view)
    try check(abs(widest.width - 2602) < 0.5 && widest.minX >= 0 && widest.maxX <= 4800, "largest frame is the usable view (\(widest))")
    try check(frame.sized(CGRect(x: CGFloat.nan, y: 0, width: CGFloat.infinity, height: 1), view: view) == frame.rect(in: view), "a nonsense request changes nothing")
    try check(frame.sized(CGRect(x: -900, y: -900, width: 900, height: 400), view: view).origin == .zero, "a request off the paper is pulled on")
    // Placing it reproduces it; a request past the view's edge says how far to scroll first.
    frame.place(asked, view: view)
    let placed = frame.rect(in: view)
    try check(abs(placed.minX - asked.minX) < 0.01 && abs(placed.minY - asked.minY) < 0.01 && abs(placed.width - asked.width) < 0.01, "placed frame \(placed)")
    let below = CGRect(x: 300, y: 2100, width: 900, height: 439)
    let scroll = PadFrame.scroll(toShow: below, view: view)
    try check(scroll.dx == 0 && abs(scroll.dy - 256) < 0.01, "scroll to show a frame past the bottom (\(scroll))")
    try check(PadFrame.scroll(toShow: asked, view: view) == .zero, "no scroll for a frame in view")
    let after = view.offsetBy(dx: 0, dy: scroll.dy)
    frame.place(below, view: after)
    try check(abs(frame.rect(in: after).maxY - after.maxY) < 0.01, "after the scroll the frame sits at the bottom edge")
    try check(PadFrame.cleanAspect(CGFloat.nan) == 1.6 && PadFrame.cleanAspect(99) == 4 && PadFrame.cleanAspect(0.01) == 0.25, "aspect limits")

    // Two paths, one ordered run: each frame is handed on once, in order, whichever path brings it first.
    func f(_ n: UInt8) -> Data { Data([0x50, n]) }
    var race = PreviewRace()
    try check(race.stream(f(0)) == [f(0)], "the connection alone delivers")
    try check(race.datagram(first: 0, frames: [f(0), f(1)]) == [f(1)], "a datagram brings the next frame early; its repeat of the last is skipped")
    try check(race.stream(f(1)).isEmpty, "the connection's copy of a frame a datagram brought is skipped")
    try check(race.datagram(first: 3, frames: [f(3), f(4)]).isEmpty, "frames past a gap wait")
    try check(race.datagram(first: 3, frames: [f(3), f(4)]).isEmpty, "a repeated datagram changes nothing")
    try check(race.stream(f(2)) == [f(2), f(3), f(4)], "the connection fills the gap and the waiting frames follow in order")
    try check(race.stream(f(3)).isEmpty && race.stream(f(4)).isEmpty && race.stream(f(5)) == [f(5)], "then the connection carries on")
    try check(race.datagram(first: 2, frames: [f(2)]).isEmpty && race.datagram(first: 9_000, frames: [f(9)]).isEmpty, "old and far-future datagrams are ignored")
    try check(race.stream(f(6)) == [f(6)] && race.fromDatagrams == 3, "nothing lost, nothing doubled (\(race.fromDatagrams) from datagrams)")
    var flood = PreviewRace()
    for n in 1...200 { _ = flood.datagram(first: UInt32(n), frames: [f(1)]) }
    try check(flood.stream(f(0)).count <= 1 + PreviewRace.held, "frames held for a gap are bounded")
    let parsed = PreviewRace.parse(Data([0, 0, 1, 2, 0, 2, 0x50, 1, 0, 3, 0x50, 2, 3]))
    try check(parsed?.first == 258 && parsed?.frames == [Data([0x50, 1]), Data([0x50, 2, 3])], "datagram payload parses")
    for bad in [Data(), Data([0, 0, 0, 1]), Data([0, 0, 0, 1, 0, 0]), Data([0, 0, 0, 1, 0, 9, 1]), Data([0, 0, 0, 1, 0, 1, 1, 0])] {
        try check(PreviewRace.parse(bad) == nil, "malformed datagram \(bad.hex) is refused")
    }

    // The session: a v1 phone is told the datagram port; datagrams and the connection deliver each frame once.
    let rig = SessionRig(udp: (50_123, 0x00ff_10aa_bbcc_dd01))
    try rig.welcome(v: 1); rig.live = []
    try check(rig.controls.contains(.udp(port: 50_123, key: "00ff10aabbccdd01")), "welcome is followed by the datagram port and key")
    rig.feed(.preview(f(0)))
    rig.session.receiveDatagram(Data([0, 0, 0, 0, 0, 2, 0x50, 0, 0, 2, 0x50, 1]), at: rig.now)
    rig.feed(.preview(f(1))); rig.feed(.preview(f(2)))
    rig.session.receiveDatagram(Data([0xff]), at: rig.now)
    rig.session.receiveDatagram(Data([0, 0, 0, 3, 0, 2, 0x43, 9]), at: rig.now)  // not a preview frame: never handed on
    try check(rig.live == [f(0), f(1), f(2)] && rig.session.datagramFrames == 2, "session delivers once, in order (\(rig.live.map(\.hex)))")
    let old = SessionRig(udp: (50_123, 7)); try old.welcome()
    try check(!old.controls.contains { if case .udp = $0 { true } else { false } }, "a v0 phone is not told about datagrams")
    // The pad's messages reach the host; an older Mac build's absence of them is the phone's concern.
    rig.feed(.padMode(local: true, pics: true, aspect: 2.05)); rig.feed(.frame(CGRect(x: 1, y: 2, width: 300, height: 150)))
    try check(rig.padModes.count == 1 && rig.padModes[0].aspect == 2.05 && rig.frames == [CGRect(x: 1, y: 2, width: 300, height: 150)], "padmode and frame reach the host")

    // Pointer controls cannot reach a host before pairing; v0 remains unchanged.
    let pointerRig = SessionRig()
    let pointerPacket = PointerPacket(seq: 1, dx: 5, dy: -3, sx: 0, sy: 0, buttons: 0, click: 0)
    pointerRig.feed(.pointerMode(version: 1, on: true)); pointerRig.feed(.pointer(pointerPacket))
    try check(pointerRig.pointerModes.isEmpty && pointerRig.pointerPackets.isEmpty, "pointer input before pairing is ignored")
    try pointerRig.welcome(v: 1)
    try check(pointerRig.controls.contains(.pointerState(version: 1, ready: false, message: "Choose Trackpad on the phone to request Mac control.")), "pointer capability is advertised only after welcome")
    pointerRig.feed(.pointerMode(version: 1, on: true)); pointerRig.feed(.pointer(pointerPacket))
    try check(pointerRig.pointerModes.count == 1 && pointerRig.pointerPackets == [pointerPacket], "authenticated pointer request reaches the consent-gated host")
    let oldPointerRig = SessionRig(); try oldPointerRig.welcome(v: 0)
    oldPointerRig.feed(.pointerMode(version: 1, on: true)); oldPointerRig.feed(.pointer(pointerPacket))
    try check(oldPointerRig.pointerModes.isEmpty && oldPointerRig.pointerPackets.isEmpty, "v0 does not participate in the pointer extension")

    // Wire: the target's additions, the phone's two messages, and the picture.
    var info = TargetInfo(note: "N1", sheet: "S1", writable: true, rev: 3)
    try check(LinkControl.target(info).object.count == 5, "a target without pad additions is the v0 message")
    info.frame = CGRect(x: 650.5, y: 0, width: 1300, height: 634.6); info.view = view; info.pageHeight = 4000.25
    let json = try JSONSerialization.data(withJSONObject: LinkControl.target(info).object)
    guard case .target(let back?) = try LinkControl.parse(json: json) else { throw CheckFailure(description: "target with additions did not parse") }
    try check(back.frame == info.frame && back.view == info.view && abs((back.pageHeight ?? 0) - 4000.3) < 0.001, "target additions round-trip to a tenth (\(back))")
    for c in [LinkControl.padMode(local: false, pics: true, aspect: 1.9), .frame(CGRect(x: 10, y: 20.5, width: 300, height: 150)), .udp(port: 9, key: "0123456789abcdef"),
              .pointerMode(version: 1, on: true), .pointerState(version: 1, ready: false, message: "Allow on Mac"),
              .pointer(PointerPacket(seq: 7, dx: -1.25, dy: 9, sx: 2, sy: -8.5, buttons: 1, click: 3))] {
        try check(try LinkControl.parse(json: JSONSerialization.data(withJSONObject: c.object)) == c, "\(c) round-trips")
    }
    for bad in [#"{"t":"frame","x":0,"y":0,"w":0,"h":1}"#, #"{"t":"frame","x":true,"y":0,"w":1,"h":1}"#, #"{"t":"padmode","local":true,"pics":true,"aspect":0}"#,
                #"{"t":"udp","port":70000,"key":"0123456789abcdef"}"#, #"{"t":"udp","port":9,"key":"xyz"}"#,
                #"{"t":"pointer","v":1,"seq":0,"dx":0,"dy":0,"sx":0,"sy":0,"buttons":0,"click":0}"#,
                #"{"t":"pointer","v":2,"seq":1,"dx":0,"dy":0,"sx":0,"sy":0,"buttons":0,"click":0}"#,
                #"{"t":"pointer","v":1,"seq":1,"dx":true,"dy":0,"sx":0,"sy":0,"buttons":0,"click":0}"#,
                #"{"t":"pointer","v":1,"seq":1,"dx":2049,"dy":0,"sx":0,"sy":0,"buttons":0,"click":0}"#] {
        try check((try? LinkControl.parse(json: Data(bad.utf8))) == nil, "malformed \(bad) is refused")
    }
    guard case .target(let lenient?) = try LinkControl.parse(json: Data(#"{"t":"target","note":"N","sheet":"S","writable":true,"rev":1,"frame":{"x":"a"},"view":5}"#.utf8)) else {
        throw CheckFailure(description: "target with broken additions did not parse")
    }
    try check(lenient.frame == nil && lenient.view == nil, "a broken addition is dropped, the target kept")
    let picture = PadPicture.framed(rev: 9, saved: 4, region: CGRect(x: 650.5, y: 0, width: 1300, height: 634.5), image: Data([0xff, 0xd8, 1]))
    try check(picture?.hex == "0000001d" + "5301" + "00000009" + "00000004" + "4422a000" + "00000000" + "44a28000" + "441ea000" + "ffd801", "picture message bytes (\(picture?.hex ?? "nil"))")
    try check(PadPicture.framed(rev: 1, saved: 0, region: .zero, image: Data(count: PadPicture.maxImage + 1)) == nil && PadPicture.framed(rev: 1, saved: 0, region: .zero, image: Data()) == nil, "an oversize or empty image is not framed")
    // A picture too detailed for one message comes out smaller, never split.
    let side = 1400
    var noise = [UInt8](repeating: 0, count: side * side * 4); var seed: UInt32 = 7
    for i in noise.indices { seed = seed &* 1_664_525 &+ 1_013_904_223; noise[i] = UInt8(seed >> 24) }
    let ctx = CGContext(data: &noise, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue)
    guard let image = ctx?.makeImage(), let jpeg = PadPicture.jpeg(image) else { throw CheckFailure(description: "noise picture did not encode") }
    try check(jpeg.count <= PadPicture.maxImage && jpeg.prefix(2) == Data([0xff, 0xd8]), "a noisy 1400 px picture fits one message (\(jpeg.count) bytes)")
}

// MARK: - Entry

// MARK: - Live frames behind an incoming file (remaining-v1 L.5)

/// A phone sends a 2.6 MB note at 20 Mbit/s (a 65,000-byte chunk every 26 ms) to a Mac whose disk takes 15 ms per chunk, while
/// its pen sends a frame every 16.7 ms on the same connection. Each frame is timed from the moment the connection read it to
/// the moment it reached live ink. With the chunks written on the connection's own queue a frame waits behind a write; with
/// them written on the files queue it does not. The note still arrives whole.
@MainActor private func bulkInputChecks() throws {
    func run(offQueue: Bool) throws -> (p50: Double, p95: Double, max: Double, n: Int, installed: Bool) {
        let connection = DispatchQueue(label: "check.link.connection"), filesQueue = DispatchQueue(label: "check.link.files")
        let sentAt = OSAllocatedUnfairLock(initialState: [UInt32: UInt64]())
        let ages = OSAllocatedUnfairLock(initialState: [Double]())
        let rig = SessionRig(run: { connection.async(execute: $0) }, files: offQueue ? { filesQueue.async(execute: $0) } : nil,
                             diskWrite: { usleep(15_000); try $0.write(contentsOf: $1) })
        rig.onLive = { data in
            guard data.count == 5 else { return }
            let i = data.dropFirst().reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
            if let t = sentAt.withLock({ $0.removeValue(forKey: i) }) { ages.withLock { $0.append(Double(monotonicNs() - t) / 1e6) } }
        }
        try connection.sync { _ = try rig.welcome(v: 1, delta: true) }
        let chunks = 40, size = chunks * 65_000
        let data = Data((0..<size).map { UInt8($0 % 251) })
        let sha = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        connection.sync { rig.feed(.control(.file(FileOffer(xfer: 3, id: "01HBULKNOTE0000000000000000", seq: 2, size: UInt64(size), sha256: sha, chunks: UInt32(chunks), delta: false)))) }
        // Arrivals in time order: chunks at 2.5 MB/s, frames at 60 Hz, each handed to the connection queue when it is due.
        var events: [(at: Double, chunk: Int?, frame: UInt32?)] = (0..<chunks).map { (Double($0) * 26.0, $0, nil) }
        events += (0..<Int(Double(chunks) * 26 / 16.7)).map { (Double($0) * 16.7 + 3, nil, UInt32($0)) }
        events.sort { $0.at < $1.at }
        let start = monotonicNs()
        for e in events {
            let due = start + UInt64(e.at * 1e6)
            while monotonicNs() < due { usleep(200) }
            if let c = e.chunk {
                let piece = data.subdata(in: c * 65_000..<(c + 1) * 65_000)
                connection.async { rig.feed(.fileChunk(xfer: 3, index: UInt32(c), data: piece)) }
            } else if let i = e.frame {
                let now = monotonicNs()
                sentAt.withLock { $0[i] = now }
                connection.async { rig.feed(.preview(Data([0x50, UInt8(i >> 24 & 255), UInt8(i >> 16 & 255), UInt8(i >> 8 & 255), UInt8(i & 255)]))) }
            }
        }
        connection.sync { rig.feed(.control(.fileEnd(xfer: 3))) }
        // The end is checked once the files queue has written everything, then the install replies on the connection queue.
        for _ in 0..<200 where rig.installed.isEmpty { filesQueue.sync {}; connection.sync {}; usleep(10_000) }
        let a = ages.withLock { $0 }.sorted()
        func at(_ p: Double) -> Double { a.isEmpty ? -1 : a[min(a.count - 1, Int(Double(a.count) * p))] }
        return (at(0.5), at(0.95), a.last ?? -1, a.count, rig.installed.first?.1 == size)
    }
    let inline = try run(offQueue: false), off = try run(offQueue: true)
    print(String(format: "  live frames behind an incoming file, disk 15 ms a chunk: on the connection queue p50 %.1f p95 %.1f max %.1f ms (n=%d); on the files queue p50 %.1f p95 %.1f max %.1f ms (n=%d)",
                 inline.p50, inline.p95, inline.max, inline.n, off.p50, off.p95, off.max, off.n))
    try check(inline.installed && off.installed, "the note arrives whole either way")
    try check(off.p95 <= 8, "with the files queue a slow disk adds at most 8 ms p95 to live frames (\(off.p95) ms)")
}

/// Remaining-v1 merge: the connection stops reading while the Mac's own disk is 4 MB behind on an incoming note (L.5) and the
/// watchdog counts any inbound byte as life with a 5 s limit (K.1). A phone that is alive but not being read is not a dead one.
@MainActor private func slowDiskWatchdogChecks() throws {
    func rigWithStuckDisk() throws -> (SessionRig, OSAllocatedUnfairLock<[@Sendable () -> Void]>) {
        let queued = OSAllocatedUnfairLock(initialState: [@Sendable () -> Void]())
        let rig = SessionRig(files: { block in queued.withLock { $0.append(block) } })
        try rig.welcome(v: 1, delta: true)
        rig.feed(.pad(on: true)) // the 5 s limit
        let chunks = 80, size = 65_000
        rig.feed(.control(.file(FileOffer(xfer: 3, id: "01HSTUCKDISK000000000000000", seq: 2, size: UInt64(chunks * size), sha256: String(repeating: "0", count: 64), chunks: UInt32(chunks), delta: false))))
        for c in 0..<70 { rig.feed(.fileChunk(xfer: 3, index: UInt32(c), data: Data(repeating: 7, count: size))) }
        return (rig, queued)
    }
    let (stuck, queued) = try rigWithStuckDisk()
    try check(stuck.session.fileBacklog > LinkSession.maxFileBacklog, "the disk is more than 4 MB behind (\(stuck.session.fileBacklog))")
    for _ in 0..<20 { stuck.tick(after: 0.5) } // 10 s: the connection is not reading, and says nothing
    try check(!stuck.closed, "a phone is not dropped as dead while the Mac's own disk is stuck behind (10 s, active session)")
    let pings = stuck.controls.filter { if case .ping = $0 { true } else { false } }.count
    try check(pings >= 8, "the Mac keeps pinging meanwhile (\(pings))")
    // The disk catches up: the phone gets a full window from here, and one that is then silent is closed as before.
    for block in queued.withLock({ let b = $0; $0 = []; return b }) { block() }
    try check(stuck.session.fileBacklog == 0, "the disk caught up")
    for _ in 0..<8 { stuck.tick(after: 0.5) }
    try check(!stuck.closed, "4 s after the disk caught up the phone still has its window")
    for _ in 0..<4 { stuck.tick(after: 0.5) }
    try check(stuck.closed, "a phone silent for 5 s after the Mac reads again is closed")

    // A disk that never recovers does not keep a link up for ever.
    let (hung, _) = try rigWithStuckDisk()
    for _ in 0..<80 { hung.tick(after: 0.5) } // 40 s
    try check(hung.closed, "a disk stuck for more than 30 s no longer excuses the silence")
}

/// Remaining-v1 merge: the same phone connects again (K.2, the old session is told `bye: replaced`) while the old connection's
/// incoming note is still being written on its files queue (L.5). The old partial file must go once, after its queued writes, and
/// never install; the new connection's transfer of the same note completes and leaves staging empty.
@MainActor private func replacedWhileWritingChecks() throws {
    let queued = OSAllocatedUnfairLock(initialState: [@Sendable () -> Void]())
    let old = SessionRig(files: { block in queued.withLock { $0.append(block) } })
    try old.welcome(v: 1, delta: true)
    let source = old.dir.appendingPathComponent("note.bin")
    try Data((0..<60).map { UInt8($0) }).write(to: source)
    let (o1, pieces) = try offer(for: source, id: "01HREPLACED00000000000000A", seq: 4, xfer: 1, chunkSize: 20)
    old.feed(.file(o1))
    old.feed(.fileChunk(xfer: 1, index: 0, data: pieces[0])) // written later: the disk is behind
    try check(old.stagingFiles.count == 1, "the old connection has its partial file in staging")
    // The new connection of the same phone takes over (a fresh session on the same staging folder, its files queue in order).
    old.clearSent()
    old.session.close(bye: "replaced")
    try check(old.closed && old.controls.contains(.bye(reason: "replaced")), "the old session is closed with bye: replaced")
    try check(old.stagingFiles.count == 1, "the partial file is not removed from under a write still queued")
    let new = SessionRig(dir: old.dir)
    try new.welcome(v: 1, delta: true)
    send(new, o1, pieces)
    try check(new.installed.count == 1, "the new connection's transfer completes (\(new.installed.count))")
    // The old queue now catches up: the write, then the abort, in order.
    for block in queued.withLock({ let b = $0; $0 = []; return b }) { block() }
    try check(old.installed.isEmpty, "the replaced session never installs its partial note")
    try check(old.stagingFiles.isEmpty, "staging is empty: the old partial file is gone and the new one was removed after install (\(old.stagingFiles))")
    try check(old.logs.contains { $0.contains("transfer-aborted") && $0.contains("partial-file-removed=true") }, "the abort is logged once the file is gone")
}

@MainActor func linkChecks() throws {
    let pixels: [[UInt8]] = [[255, 255, 255, 255], [255, 255, 255, 255], [254, 255, 255, 255],
                              [255, 254, 255, 255], [255, 255, 254, 255], [255, 255, 255, 254]]
    try check(inkPixels(pixels.flatMap { $0 }) == 4, "pixel counter compares every BGRA channel")
    try framingChecks()
    try vectorChecks()
    try clockChecks()
    try watchdogChecks()
    try slowDiskWatchdogChecks()
    try replacedWhileWritingChecks()
    try strokeKeepChecks()
    try pairingChecks()
    try syncChecks()
    try deltaChecks()
    try pushChecks()
    try bulkInputChecks()
    try mirrorChecks()
    try targetChecks()
    try padChecks()
    try loopbackEndToEnd()
}
