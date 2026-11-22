import Foundation

// Wi-Fi link v0 wire format (tasks/contracts/wifi-link-v0.md, section 2 and 3). Pure: bytes in, typed
// messages out, and back. No sockets, no clocks, no UI. Everything here treats its input as hostile.

enum LinkLimits {
    /// Sent in hello and welcome. A Mac accepts a phone at version 0 or 1 (sync-v1.md).
    static let version = 1
    static let oldestVersion = 0
    /// Entries per `notes` message (each is at most about 500 bytes; the message limit is 64 KiB).
    static let notesPerMessage = 80
    static let maxMessage = 65_536
    static let maxFileChunk = 65_000
    static let maxFileBytes: UInt64 = 256 << 20
    static let codeLifetimeNs: UInt64 = 120_000_000_000
    static let codeTries = 5
    static let helloTimeoutNs: UInt64 = 10_000_000_000
    /// A session with nothing to lose (no pad, pointer or transfer) may be quiet this long before the Mac calls the phone gone.
    static let idleTimeoutNs: UInt64 = 12_000_000_000
    /// With a pad, the pointer or a transfer running, a quiet link is caught sooner: any inbound byte counts as life.
    static let activeTimeoutNs: UInt64 = 5_000_000_000
    static let pingIntervalNs: UInt64 = 2_000_000_000
    static let activePingIntervalNs: UInt64 = 1_000_000_000
    /// A watchdog that was itself not running for this long learns nothing about the phone (the Mac was stalled, not the link).
    static let stallGraceNs: UInt64 = 1_500_000_000
    /// While the Mac's own disk is behind on an incoming note it stops reading; the phone's silence over that stretch is the Mac's
    /// doing, so the watchdog counts none of it, for this long (a disk that never recovers still ends the link).
    static let diskForgivenNs: UInt64 = 30_000_000_000
}

enum LinkProtocolError: Error, Equatable, CustomStringConvertible {
    case badLength(Int)
    case unknownKind(UInt8)
    case malformedControl(String)
    case badFileChunk
    var description: String {
        switch self {
        case .badLength(let n): "message length \(n) outside 1...\(LinkLimits.maxMessage)"
        case .unknownKind(let b): "unknown message kind 0x" + String(b, radix: 16)
        case .malformedControl(let why): "malformed control message: \(why)"
        case .badFileChunk: "malformed file chunk"
        }
    }
}

struct NoteAnnounce: Equatable, Sendable {
    var id: String
    var seq: UInt64
    var title: String
    /// Link v1: SHA-256 hex over the note's sorted operation IDs, and how many there are.
    var digest: String? = nil
    var ops: UInt64? = nil
}

struct FileOffer: Equatable, Sendable {
    var xfer: UInt32
    var id: String
    var seq: UInt64
    var size: UInt64
    var sha256: String
    var chunks: UInt32
    /// The file holds only operations (tasks/contracts/sync-delta.md), not a whole note.
    var delta: Bool = false
    /// Optional: the newest operation per author the sender held when it made the file. Once it applies whole, the receiver's
    /// own next delta leaves those out instead of sending them back (remaining-v1 L.4). Older peers ignore it.
    var heads: [String: String]? = nil
}

struct TargetInfo: Equatable, Sendable {
    var note: String
    var sheet: String
    var writable: Bool
    var rev: UInt32
    /// Pad additions (rework-v1 §V.4), all in canvas units: the pen frame while the pad is in Local mode, the Mac's
    /// visible view, and how far down the page goes. Absent for a phone that never asked for them.
    var frame: CGRect? = nil
    var view: CGRect? = nil
    var pageHeight: Double? = nil
}

struct PointerPacket: Equatable, Sendable {
    let seq: UInt64
    let dx: Double
    let dy: Double
    let sx: Double
    let sy: Double
    let buttons: Int
    /// 0: none, 1: left, 2: right, 3: double-left.
    let click: Int
    /// Closes a two-finger scroll (the "ended" phase). Optional `se` on the wire; older phones never send it.
    var scrollEnd = false
}

/// One control message (`t` field). `unknown` keeps the name so it can be logged and ignored.
enum LinkControl: Equatable, Sendable {
    /// `delta`: this side can send and merge deltas. Written only when true. `push`: this side also takes a delta nobody
    /// asked for and sends its own as soon as a note changes (latency-v1 T.3); only meaningful with `delta`.
    /// `keep` (link K.4): the phone keeps each pad stroke until `stroke_ack` and may send it again after a reconnect.
    case hello(v: Int, device: String?, phoneId: String?, token: String?, delta: Bool = false, push: Bool = false, keep: Bool = false)
    /// `v` is the version this connection runs at: the phone's, when it is older than the Mac's (a v0 phone refuses any other).
    /// `keep`: the Mac saw `keep` in hello and will confirm every pad stroke by ID (`stroke_ack`).
    case welcome(mac: String, macId: String, epoch: UInt32, v: Int = LinkLimits.version, delta: Bool = false, push: Bool = false, keep: Bool = false)
    case pair
    case code(String)
    case paired(token: String)
    case denied(left: Int)
    case bye(reason: String)
    case ping(id: UInt64, at: UInt64)
    case pong(id: UInt64, at: UInt64, now: UInt64)
    /// `trash` (v1 phones): IDs in the sender's Recently deleted, never wanted from or pushed to it.
    case notes([NoteAnnounce], trash: [String]? = nil)
    /// `base` (delta peers): per wanted note, the newest operation ID the asker holds from each author (author to ID).
    case want([String], base: [String: [String: String]]? = nil)
    case file(FileOffer)
    case fileEnd(xfer: UInt32)
    /// `digest` and `ops` (v1 peers): the sender's operation set after installing.
    case have(id: String, seq: UInt64, digest: String? = nil, ops: UInt64? = nil)
    case fileError(xfer: UInt32, id: String, reason: String)
    case viewing(String?)
    case pad(on: Bool)
    case target(TargetInfo?)
    /// Phone: how the pad maps (`local`: onto the pen frame, else the whole view), whether it wants pictures of what
    /// it covers, and the pad's width over height.
    case padMode(local: Bool, pics: Bool, aspect: Double)
    /// Phone: asks for this pen frame (canvas units). The Mac clamps it and answers with `target`.
    case frame(CGRect)
    /// Mac: preview frames may also be sent as datagrams to this port, each starting with this key (16 hex characters).
    case udp(port: UInt16, key: String)
    /// Independently versioned pointer extension; only authenticated sessions may enable it.
    case pointerMode(version: Int, on: Bool)
    case pointerState(version: Int, ready: Bool, message: String)
    case pointer(PointerPacket)
    /// Mac: the stroke with this ID is in the note (`ok`), or was refused and will never be (not `ok`): the phone lets go of it.
    case strokeAck(id: String, ok: Bool)
    /// Phone: the stroke it is about to send again was begun at target revision `rev`: the Mac places it with that view.
    case strokeRev(id: String, rev: UInt32)
    case unknown(String)

    // MARK: Encoding

    var object: [String: Any] {
        switch self {
        case .hello(let v, let device, let phoneId, let token, let delta, let push, let keep):
            var o: [String: Any] = ["t": "hello", "v": v, "device": device ?? "", "phone_id": phoneId ?? "", "token": token.map { $0 as Any } ?? NSNull()]
            if delta { o["delta"] = true }
            if push { o["push"] = true }
            if keep { o["keep"] = true }
            return o
        case .welcome(let mac, let macId, let epoch, let v, let delta, let push, let keep):
            var o: [String: Any] = ["t": "welcome", "v": v, "mac": mac, "mac_id": macId, "epoch": epoch]
            if delta { o["delta"] = true }
            if push { o["push"] = true }
            if keep { o["keep"] = true }
            return o
        case .pair: return ["t": "pair"]
        case .code(let code): return ["t": "code", "code": code]
        case .paired(let token): return ["t": "paired", "token": token]
        case .denied(let left): return ["t": "denied", "left": left]
        case .bye(let reason): return ["t": "bye", "reason": reason]
        case .ping(let id, let at): return ["t": "ping", "id": id, "at": at]
        case .pong(let id, let at, let now): return ["t": "pong", "id": id, "at": at, "now": now]
        case .notes(let list, let trash):
            var o: [String: Any] = ["t": "notes", "notes": list.map { n -> [String: Any] in
                var e: [String: Any] = ["id": n.id, "seq": n.seq, "title": n.title]
                if let d = n.digest { e["digest"] = d }
                if let c = n.ops { e["ops"] = c }
                return e
            }]
            if let trash { o["trash"] = trash }
            return o
        case .want(let ids, let base):
            var o: [String: Any] = ["t": "want", "ids": ids]
            if let base, !base.isEmpty { o["base"] = base }
            return o
        case .file(let f):
            var o: [String: Any] = ["t": "file", "xfer": f.xfer, "id": f.id, "seq": f.seq, "size": f.size, "sha256": f.sha256, "chunks": f.chunks]
            if f.delta { o["delta"] = true }
            if let heads = f.heads, !heads.isEmpty { o["heads"] = heads }
            return o
        case .fileEnd(let xfer): return ["t": "file_end", "xfer": xfer]
        case .have(let id, let seq, let digest, let ops):
            var o: [String: Any] = ["t": "have", "id": id, "seq": seq]
            if let digest { o["digest"] = digest }
            if let ops { o["ops"] = ops }
            return o
        case .fileError(let xfer, let id, let reason): return ["t": "file_error", "xfer": xfer, "id": id, "reason": reason]
        case .viewing(let id): return ["t": "viewing", "id": id.map { $0 as Any } ?? NSNull()]
        case .pad(let on): return ["t": "pad", "on": on]
        case .target(let info):
            guard let info else { return ["t": "target", "note": NSNull()] }
            var o: [String: Any] = ["t": "target", "note": info.note, "sheet": info.sheet, "writable": info.writable, "rev": info.rev]
            if let r = info.frame { o["frame"] = Self.rect(r) }
            if let r = info.view { o["view"] = Self.rect(r) }
            if let h = info.pageHeight { o["page"] = ["w": 4800, "h": Self.tenth(h)] }
            return o
        case .padMode(let local, let pics, let aspect): return ["t": "padmode", "local": local, "pics": pics, "aspect": aspect]
        case .frame(let r): return ["t": "frame", "x": Self.tenth(r.minX), "y": Self.tenth(r.minY), "w": Self.tenth(r.width), "h": Self.tenth(r.height)]
        case .udp(let port, let key): return ["t": "udp", "port": Int(port), "key": key]
        case .pointerMode(let v, let on): return ["t": "pointermode", "v": v, "on": on]
        case .pointerState(let v, let ready, let message): return ["t": "pointerstate", "v": v, "ready": ready, "message": message]
        case .pointer(let p):
            var json: [String: Any] = ["t": "pointer", "v": 1, "seq": p.seq, "dx": p.dx, "dy": p.dy, "sx": p.sx, "sy": p.sy, "buttons": p.buttons, "click": p.click]
            if p.scrollEnd { json["se"] = true }
            return json
        case .strokeAck(let id, let ok): return ["t": "stroke_ack", "id": id, "ok": ok]
        case .strokeRev(let id, let rev): return ["t": "stroke_rev", "id": id, "rev": rev]
        case .unknown(let t): return ["t": t]
        }
    }

    private static func tenth(_ v: Double) -> Double { (v * 10).rounded() / 10 }
    private static func rect(_ r: CGRect) -> [String: Any] { ["x": tenth(r.minX), "y": tenth(r.minY), "w": tenth(r.width), "h": tenth(r.height)] }

    /// The message with its 4-byte length prefix and kind byte, ready for the socket.
    func framed() -> Data {
        let json = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{\"t\":\"bye\"}".utf8)
        var out = Data(capacity: json.count + 5)
        let n = UInt32(json.count + 1)
        out.append(contentsOf: [UInt8(n >> 24 & 255), UInt8(n >> 16 & 255), UInt8(n >> 8 & 255), UInt8(n & 255), 0x4A])
        out.append(json)
        return out
    }

    // MARK: Decoding

    /// Parses the JSON body of a control message (without the 0x4A kind byte).
    static func parse(json: Data) throws -> LinkControl {
        guard let any = try? JSONSerialization.jsonObject(with: json), let obj = any as? [String: Any] else {
            throw LinkProtocolError.malformedControl("not a JSON object")
        }
        guard let t = obj["t"] as? String else { throw LinkProtocolError.malformedControl("missing t") }
        func bad(_ what: String) -> LinkProtocolError { .malformedControl("\(t): \(what)") }
        func uint(_ key: String) throws -> UInt64 {
            guard let v = integer(obj[key]) else { throw bad("\(key) is not an unsigned integer") }
            return v
        }
        func u32(_ key: String) throws -> UInt32 {
            guard let v = UInt32(exactly: try uint(key)) else { throw bad("\(key) out of range") }
            return v
        }
        func string(_ key: String) throws -> String {
            guard let s = obj[key] as? String else { throw bad("\(key) is not a string") }
            return s
        }
        func optionalString(_ key: String) throws -> String? {
            if obj[key] == nil || obj[key] is NSNull { return nil }
            return try string(key)
        }
        switch t {
        case "pointermode":
            guard let v = obj["v"].flatMap(integer), v <= 100, let on = obj["on"] as? Bool else { throw bad("pointer mode") }
            return .pointerMode(version: Int(v), on: on)
        case "pointerstate":
            guard let v = obj["v"].flatMap(integer), v <= 100, let ready = obj["ready"] as? Bool, let message = obj["message"] as? String, message.utf8.count <= 1024 else { throw bad("pointer state") }
            return .pointerState(version: Int(v), ready: ready, message: message)
        case "pointer":
            guard obj["v"].flatMap(integer) == 1, let seq = obj["seq"].flatMap(integer), seq > 0,
                  let buttons = obj["buttons"].flatMap(integer), (0...1).contains(buttons),
                  let click = obj["click"].flatMap(integer), (0...3).contains(click),
                  let dx = Self.number(obj["dx"]), let dy = Self.number(obj["dy"]),
                  let sx = Self.number(obj["sx"]), let sy = Self.number(obj["sy"]),
                  [dx, dy, sx, sy].allSatisfy({ $0.isFinite && abs($0) <= 2048 }) else { throw bad("pointer bounds") }
            return .pointer(PointerPacket(seq: seq, dx: dx, dy: dy, sx: sx, sy: sy, buttons: Int(buttons), click: Int(click), scrollEnd: obj["se"] as? Bool ?? false))
        case "hello":
            guard let v = obj["v"].flatMap(integer), let version = Int(exactly: v) else { throw bad("v is not an integer") }
            return .hello(v: version, device: obj["device"] as? String, phoneId: obj["phone_id"] as? String, token: try optionalString("token"),
                          delta: obj["delta"] as? Bool == true, push: obj["push"] as? Bool == true, keep: obj["keep"] as? Bool == true)
        case "welcome": return .welcome(mac: try string("mac"), macId: try string("mac_id"), epoch: try u32("epoch"), v: obj["v"].flatMap(integer).flatMap { Int(exactly: $0) } ?? 0,
                                        delta: obj["delta"] as? Bool == true, push: obj["push"] as? Bool == true, keep: obj["keep"] as? Bool == true)
        case "pair": return .pair
        case "code": return .code(try string("code"))
        case "paired": return .paired(token: try string("token"))
        case "denied": return .denied(left: Int(try u32("left")))
        case "bye": return .bye(reason: (obj["reason"] as? String) ?? "")
        case "ping": return .ping(id: try uint("id"), at: try uint("at"))
        case "pong": return .pong(id: try uint("id"), at: try uint("at"), now: try uint("now"))
        case "notes":
            guard let list = obj["notes"] as? [Any] else { throw bad("notes is not an array") }
            // A bad entry is skipped rather than closing the link: the rest of the list is still good.
            let entries = list.compactMap { item -> NoteAnnounce? in
                guard let e = item as? [String: Any], let id = e["id"] as? String, LinkIds.valid(id),
                      let seq = integer(e["seq"]) else { return nil }
                return NoteAnnounce(id: id, seq: seq, title: (e["title"] as? String) ?? "", digest: Self.digest(e["digest"]), ops: integer(e["ops"]))
            }
            let trash = (obj["trash"] as? [Any]).map { $0.compactMap { ($0 as? String).flatMap { LinkIds.valid($0) ? $0 : nil } } }
            return .notes(entries, trash: trash)
        case "want":
            guard let ids = obj["ids"] as? [Any] else { throw bad("ids is not an array") }
            return .want(ids.compactMap { $0 as? String }, base: Self.base(obj["base"]))
        case "file":
            return .file(FileOffer(xfer: try u32("xfer"), id: try string("id"), seq: try uint("seq"), size: try uint("size"),
                                   sha256: try string("sha256"), chunks: try u32("chunks"), delta: obj["delta"] as? Bool == true,
                                   heads: Self.heads(obj["heads"])))
        case "file_end": return .fileEnd(xfer: try u32("xfer"))
        case "have": return .have(id: try string("id"), seq: try uint("seq"), digest: Self.digest(obj["digest"]), ops: integer(obj["ops"]))
        case "file_error": return .fileError(xfer: try u32("xfer"), id: try string("id"), reason: (obj["reason"] as? String) ?? "")
        case "viewing": return .viewing(try optionalString("id"))
        case "pad":
            guard let on = obj["on"] as? Bool else { throw bad("on is not a boolean") }
            return .pad(on: on)
        case "target":
            guard let note = try optionalString("note") else { return .target(nil) }
            guard let writable = obj["writable"] as? Bool else { throw bad("writable is not a boolean") }
            var info = TargetInfo(note: note, sheet: try string("sheet"), writable: writable, rev: try u32("rev"))
            info.frame = Self.rect(obj["frame"]); info.view = Self.rect(obj["view"])
            info.pageHeight = (obj["page"] as? [String: Any]).flatMap { Self.number($0["h"]) }
            return .target(info)
        case "padmode":
            guard let local = obj["local"] as? Bool, let pics = obj["pics"] as? Bool, let aspect = Self.number(obj["aspect"]), aspect > 0 else { throw bad("local, pics or aspect") }
            return .padMode(local: local, pics: pics, aspect: aspect)
        case "frame":
            guard let r = Self.rect(obj) else { throw bad("x, y, w or h") }
            return .frame(r)
        case "stroke_ack":
            guard let id = obj["id"] as? String, LinkIds.valid(id), let ok = obj["ok"] as? Bool else { throw bad("id or ok") }
            return .strokeAck(id: id, ok: ok)
        case "stroke_rev":
            guard let id = obj["id"] as? String, LinkIds.valid(id) else { throw bad("id") }
            return .strokeRev(id: id, rev: try u32("rev"))
        case "udp":
            guard let port = UInt16(exactly: try uint("port")), LinkIds.validHex(try string("key"), count: 16) else { throw bad("port or key") }
            return .udp(port: port, key: try string("key"))
        default: return .unknown(t)
        }
    }

    /// A finite JSON number (not a boolean), else nil.
    private static func number(_ any: Any?) -> Double? {
        guard let n = any as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { return nil }
        return n.doubleValue
    }

    /// `{"x":…,"y":…,"w":…,"h":…}` with a positive size and values a canvas could hold, else nil.
    private static func rect(_ any: Any?) -> CGRect? {
        guard let o = any as? [String: Any], let x = number(o["x"]), let y = number(o["y"]), let w = number(o["w"]), let h = number(o["h"]),
              w > 0, h > 0, [x, y, w, h].allSatisfy({ abs($0) <= 10_000_000 }) else { return nil }
        return CGRect(x: x, y: y, width: w, height: h)
    }

    /// A SHA-256 digest as 64 hex characters, else nil (a malformed digest reads as "unknown").
    private static func digest(_ any: Any?) -> String? {
        guard let s = any as? String, LinkIds.validHex(s, count: 64) else { return nil }
        return s.lowercased()
    }

    /// A JSON number that is a non-negative integer (not a boolean, not fractional), else nil.
    /// `base` of a `want`: note ID to author to newest operation ID. An addition is never worth closing the link for: a
    /// malformed entry is dropped (the answer is then a whole copy) and entries past the limits are ignored.
    private static func base(_ any: Any?) -> [String: [String: String]]? {
        guard let raw = any as? [String: Any] else { return nil }
        var out: [String: [String: String]] = [:]
        for (note, heads) in raw.prefix(5_000) {
            guard LinkIds.valid(note), let entry = Self.heads(heads) else { continue }
            out[note] = entry
        }
        return out.isEmpty ? nil : out
    }

    /// One author-to-newest-operation map (a `file`'s `heads`); a malformed one is dropped, never fatal.
    private static func heads(_ any: Any?) -> [String: String]? {
        guard let heads = any as? [String: Any], heads.count <= 64 else { return nil }
        var entry: [String: String] = [:]
        for (author, newest) in heads {
            guard author.count <= 64, let newest = newest as? String, newest.count <= 64 else { return nil }
            entry[author] = newest
        }
        return entry.isEmpty ? nil : entry
    }

    private static func integer(_ any: Any?) -> UInt64? {
        guard let n = any as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let d = n.doubleValue
        guard d >= 0 else { return nil }
        // Integers keep their exact value (up to u64::MAX); a float form such as 1e3 must be whole and in range.
        if !CFNumberIsFloatType(n) { return n.uint64Value }
        guard d == d.rounded(), d < 18_446_744_073_709_551_616 else { return nil }
        return n.uint64Value
    }
}

enum LinkIds {
    /// Core IDs are 26-character ULIDs; the link accepts any short token of ID characters.
    static func valid(_ id: String) -> Bool {
        guard (1...64).contains(id.utf8.count) else { return false }
        return id.utf8.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || $0 == 0x2D || $0 == 0x5F }
    }
    static func validHex(_ s: String, count: Int) -> Bool {
        s.utf8.count == count && s.utf8.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) || ($0 >= 0x41 && $0 <= 0x46) }
    }
}

extension Data {
    init?(hex: String) {
        guard hex.utf8.count % 2 == 0 else { return nil }
        var bytes = [UInt8](); bytes.reserveCapacity(hex.utf8.count / 2)
        var high: UInt8?
        for c in hex.utf8 {
            let v: UInt8
            switch c {
            case 0x30...0x39: v = c - 0x30
            case 0x61...0x66: v = c - 0x61 + 10
            case 0x41...0x46: v = c - 0x41 + 10
            default: return nil
            }
            if let h = high { bytes.append(h << 4 | v); high = nil } else { high = v }
        }
        self.init(bytes)
    }
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

/// One decoded message from the stream.
enum LinkFrame: Equatable {
    /// The whole message including the kind byte, as the core wants it.
    case preview(Data)
    case strokeCommit(Data)
    case control(LinkControl)
    case fileChunk(xfer: UInt32, index: UInt32, data: Data)
}

/// Incremental reader of the length-prefixed stream. Feed it whatever arrives; ask for frames until
/// it returns nil. A throw is fatal for the connection (bad length, unknown kind, malformed control).
struct LinkFrameDecoder {
    private var buffer = Data()
    var buffered: Int { buffer.count }

    mutating func append(_ bytes: Data) { buffer.append(bytes) }

    mutating func next() throws -> LinkFrame? {
        guard buffer.count >= 4 else { return nil }
        let s = buffer.startIndex
        let n = Int(buffer[s]) << 24 | Int(buffer[s + 1]) << 16 | Int(buffer[s + 2]) << 8 | Int(buffer[s + 3])
        guard n >= 1, n <= LinkLimits.maxMessage else { throw LinkProtocolError.badLength(n) }
        guard buffer.count >= 4 + n else { return nil }
        let message = Data(buffer[(s + 4)..<(s + 4 + n)])
        buffer.removeSubrange(s..<(s + 4 + n))
        switch message[0] {
        case 0x50: return .preview(message)
        case 0x43: return .strokeCommit(message)
        case 0x4A: return .control(try LinkControl.parse(json: message.dropFirst()))
        case 0x46:
            guard message.count > 9, message.count - 9 <= LinkLimits.maxFileChunk else { throw LinkProtocolError.badFileChunk }
            func u32(_ at: Int) -> UInt32 { UInt32(message[at]) << 24 | UInt32(message[at + 1]) << 16 | UInt32(message[at + 2]) << 8 | UInt32(message[at + 3]) }
            return .fileChunk(xfer: u32(1), index: u32(5), data: Data(message[9...]))
        default: throw LinkProtocolError.unknownKind(message[0])
        }
    }

    /// Frames a file chunk (the phone sends these; used by checks).
    static func fileChunk(xfer: UInt32, index: UInt32, data: Data) -> Data {
        var out = Data()
        let n = UInt32(9 + data.count)
        out.append(contentsOf: [UInt8(n >> 24 & 255), UInt8(n >> 16 & 255), UInt8(n >> 8 & 255), UInt8(n & 255), 0x46])
        for v in [xfer, index] { out.append(contentsOf: [UInt8(v >> 24 & 255), UInt8(v >> 16 & 255), UInt8(v >> 8 & 255), UInt8(v & 255)]) }
        out.append(data)
        return out
    }

    /// Frames a core preview or stroke-commit message (the bytes already start with their kind byte).
    static func frame(core bytes: Data) -> Data {
        var out = Data()
        let n = UInt32(bytes.count)
        out.append(contentsOf: [UInt8(n >> 24 & 255), UInt8(n >> 16 & 255), UInt8(n >> 8 & 255), UInt8(n & 255)])
        out.append(bytes)
        return out
    }
}
