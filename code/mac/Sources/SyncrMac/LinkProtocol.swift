import Foundation

// Wi-Fi link v0 wire format (tasks/contracts/wifi-link-v0.md, section 2 and 3). Pure: bytes in, typed
// messages out, and back. No sockets, no clocks, no UI. Everything here treats its input as hostile.

enum LinkLimits {
    static let version = 0
    static let maxMessage = 65_536
    static let maxFileChunk = 65_000
    static let maxFileBytes: UInt64 = 256 << 20
    static let codeLifetimeNs: UInt64 = 120_000_000_000
    static let codeTries = 5
    static let helloTimeoutNs: UInt64 = 10_000_000_000
    static let idleTimeoutNs: UInt64 = 20_000_000_000
    static let pingIntervalNs: UInt64 = 2_000_000_000
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
}

struct FileOffer: Equatable, Sendable {
    var xfer: UInt32
    var id: String
    var seq: UInt64
    var size: UInt64
    var sha256: String
    var chunks: UInt32
}

struct TargetInfo: Equatable, Sendable {
    var note: String
    var sheet: String
    var writable: Bool
    var rev: UInt32
}

/// One control message (`t` field). `unknown` keeps the name so it can be logged and ignored.
enum LinkControl: Equatable, Sendable {
    case hello(v: Int, device: String?, phoneId: String?, token: String?)
    case welcome(mac: String, macId: String, epoch: UInt32)
    case pair
    case code(String)
    case paired(token: String)
    case denied(left: Int)
    case bye(reason: String)
    case ping(id: UInt64, at: UInt64)
    case pong(id: UInt64, at: UInt64, now: UInt64)
    case notes([NoteAnnounce])
    case want([String])
    case file(FileOffer)
    case fileEnd(xfer: UInt32)
    case have(id: String, seq: UInt64)
    case fileError(xfer: UInt32, id: String, reason: String)
    case viewing(String?)
    case pad(on: Bool)
    case target(TargetInfo?)
    case unknown(String)

    // MARK: Encoding

    var object: [String: Any] {
        switch self {
        case .hello(let v, let device, let phoneId, let token):
            return ["t": "hello", "v": v, "device": device ?? "", "phone_id": phoneId ?? "", "token": token.map { $0 as Any } ?? NSNull()]
        case .welcome(let mac, let macId, let epoch): return ["t": "welcome", "v": LinkLimits.version, "mac": mac, "mac_id": macId, "epoch": epoch]
        case .pair: return ["t": "pair"]
        case .code(let code): return ["t": "code", "code": code]
        case .paired(let token): return ["t": "paired", "token": token]
        case .denied(let left): return ["t": "denied", "left": left]
        case .bye(let reason): return ["t": "bye", "reason": reason]
        case .ping(let id, let at): return ["t": "ping", "id": id, "at": at]
        case .pong(let id, let at, let now): return ["t": "pong", "id": id, "at": at, "now": now]
        case .notes(let list): return ["t": "notes", "notes": list.map { ["id": $0.id, "seq": $0.seq, "title": $0.title] as [String: Any] }]
        case .want(let ids): return ["t": "want", "ids": ids]
        case .file(let o): return ["t": "file", "xfer": o.xfer, "id": o.id, "seq": o.seq, "size": o.size, "sha256": o.sha256, "chunks": o.chunks]
        case .fileEnd(let xfer): return ["t": "file_end", "xfer": xfer]
        case .have(let id, let seq): return ["t": "have", "id": id, "seq": seq]
        case .fileError(let xfer, let id, let reason): return ["t": "file_error", "xfer": xfer, "id": id, "reason": reason]
        case .viewing(let id): return ["t": "viewing", "id": id.map { $0 as Any } ?? NSNull()]
        case .pad(let on): return ["t": "pad", "on": on]
        case .target(let info):
            guard let info else { return ["t": "target", "note": NSNull()] }
            return ["t": "target", "note": info.note, "sheet": info.sheet, "writable": info.writable, "rev": info.rev]
        case .unknown(let t): return ["t": t]
        }
    }

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
        case "hello":
            guard let v = obj["v"].flatMap(integer), let version = Int(exactly: v) else { throw bad("v is not an integer") }
            return .hello(v: version, device: obj["device"] as? String, phoneId: obj["phone_id"] as? String, token: try optionalString("token"))
        case "welcome": return .welcome(mac: try string("mac"), macId: try string("mac_id"), epoch: try u32("epoch"))
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
            return .notes(list.compactMap { item -> NoteAnnounce? in
                guard let e = item as? [String: Any], let id = e["id"] as? String, LinkIds.valid(id),
                      let seq = integer(e["seq"]) else { return nil }
                return NoteAnnounce(id: id, seq: seq, title: (e["title"] as? String) ?? "")
            })
        case "want":
            guard let ids = obj["ids"] as? [Any] else { throw bad("ids is not an array") }
            return .want(ids.compactMap { $0 as? String })
        case "file":
            return .file(FileOffer(xfer: try u32("xfer"), id: try string("id"), seq: try uint("seq"), size: try uint("size"),
                                   sha256: try string("sha256"), chunks: try u32("chunks")))
        case "file_end": return .fileEnd(xfer: try u32("xfer"))
        case "have": return .have(id: try string("id"), seq: try uint("seq"))
        case "file_error": return .fileError(xfer: try u32("xfer"), id: try string("id"), reason: (obj["reason"] as? String) ?? "")
        case "viewing": return .viewing(try optionalString("id"))
        case "pad":
            guard let on = obj["on"] as? Bool else { throw bad("on is not a boolean") }
            return .pad(on: on)
        case "target":
            guard let note = try optionalString("note") else { return .target(nil) }
            guard let writable = obj["writable"] as? Bool else { throw bad("writable is not a boolean") }
            return .target(TargetInfo(note: note, sheet: try string("sheet"), writable: writable, rev: try u32("rev")))
        default: return .unknown(t)
        }
    }

    /// A JSON number that is a non-negative integer (not a boolean, not fractional), else nil.
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
