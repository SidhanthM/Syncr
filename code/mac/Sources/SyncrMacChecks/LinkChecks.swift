import CryptoKit
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
    rig.tick(after: 25)
    try check(rig.closed, "a silent phone is dropped")
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
    var exportAnswer: (String) -> ExportOutcome = { _ in .refused("none") }
    var haveSeen: [(String, String)] = []
    var haveDiffers = false
    var logs: [String] = []
    var wantAnswer: ([NoteAnnounce]) -> [String] = { $0.map(\.id) }
    var installAnswer: (FileOffer, URL) -> InstallOutcome = { _, _ in .installed(seq: 1) }
    var installed: [(FileOffer, Int)] = []
    let phoneId = "0123456789abcdef0123456789abcdef"
    var macId = "fedcba9876543210fedcba9876543210"

    var controls: [LinkControl] { controlsList(sent) }

    init(store: PairedPhoneStore? = nil, epoch: UInt32 = 7, dir: URL? = nil) {
        let d = dir ?? temp()
        self.dir = d
        self.store = store ?? PairedPhoneStore(folder: d.appendingPathComponent("state"))
        session = LinkSession(config: .init(macName: "Test Mac", macId: macId, epoch: epoch, stagingDir: d.appendingPathComponent("staging")),
                              store: self.store, io: LinkSession.IO(
            send: { [unowned self] in sent.append($0) },
            close: { [unowned self] in closed = true },
            liveReceive: { [unowned self] data, _ in live.append(data) },
            liveReset: { [unowned self] epoch in resetEpochs.append(epoch); events.append("reset") },
            run: { $0() }, sendBulk: nil))
        session.host = self
        session.start(atNs: now)
    }
    deinit { try? FileManager.default.removeItem(at: dir) }

    var stagingFiles: [String] { (try? FileManager.default.contentsOfDirectory(atPath: dir.appendingPathComponent("staging").path)) ?? [] }

    func feed(_ c: LinkControl) { session.receive(.control(c), at: now) }
    func feed(_ f: LinkFrame) { session.receive(f, at: now) }
    func tick(after seconds: Double) { now += UInt64(seconds * 1e9); session.tick(atNs: now) }
    func hello(token: String? = nil, v: Int = 0, device: String? = "Galaxy S23 Ultra") {
        feed(.hello(v: v, device: device, phoneId: phoneId, token: token))
    }
    func clearSent() { sent = Data() }

    /// Pairs and welcomes through the normal path. Returns the token.
    @discardableResult func welcome() throws -> String {
        hello()
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
    func sessionExport(_ session: LinkSession, id: String, reply: @escaping @Sendable (ExportOutcome) -> Void) { exportRequests.append(id); reply(exportAnswer(id)) }
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
        _ = try library.createNote(request: CreateNoteRequest(operationId: first, noteId: noteId, firstSheetId: sheetId, title: title, notebook: "Phone"))
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
        if case .welcome(_, _, let e, _)? = phone.take({ if case .welcome = $0 { true } else { false } }) { epoch = e }
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

// MARK: - Entry

@MainActor func linkChecks() throws {
    let pixels: [[UInt8]] = [[255, 255, 255, 255], [255, 255, 255, 255], [254, 255, 255, 255],
                              [255, 254, 255, 255], [255, 255, 254, 255], [255, 255, 255, 254]]
    try check(inkPixels(pixels.flatMap { $0 }) == 4, "pixel counter compares every BGRA channel")
    try framingChecks()
    try vectorChecks()
    try clockChecks()
    try pairingChecks()
    try syncChecks()
    try mirrorChecks()
    try targetChecks()
    try loopbackEndToEnd()
}
