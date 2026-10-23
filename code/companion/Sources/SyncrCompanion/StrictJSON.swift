import Foundation

/// Foundation accepts duplicate object keys. Reject them before its decoder can erase
/// evidence of ambiguity; also bound nesting before walking any model-controlled tree.
struct StrictJSON {
    private var bytes: [UInt8]
    private var offset = 0
    static func check(_ data: Data) throws {
        var parser = StrictJSON(bytes: Array(data)); try parser.value(depth: 0); parser.space()
        guard parser.offset == parser.bytes.count else { throw CompanionError.invalid("trailing JSON") }
    }
    private mutating func space() { while offset < bytes.count && [9, 10, 13, 32].contains(bytes[offset]) { offset += 1 } }
    private mutating func take(_ byte: UInt8) -> Bool { space(); if offset < bytes.count && bytes[offset] == byte { offset += 1; return true }; return false }
    private mutating func string() throws -> String {
        space(); let start = offset
        guard take(34) else { throw CompanionError.invalid("JSON string") }
        while offset < bytes.count {
            let b = bytes[offset]; offset += 1
            if b == 92 { guard offset < bytes.count else { break }; offset += 1 }
            else if b == 34 { return try JSONDecoder().decode(String.self, from: Data(bytes[start..<offset])) }
        }
        throw CompanionError.invalid("unfinished JSON string")
    }
    private mutating func value(depth: Int) throws {
        guard depth <= 32 else { throw CompanionError.invalid("JSON nesting limit") }
        space(); guard offset < bytes.count else { throw CompanionError.invalid("missing JSON value") }
        if take(123) {
            var keys = Set<String>(); if take(125) { return }
            repeat {
                let key = try string()
                guard keys.insert(key).inserted, take(58) else { throw CompanionError.invalid("duplicate JSON key or missing colon") }
                try value(depth: depth + 1)
                if take(125) { return }
            } while take(44)
            throw CompanionError.invalid("JSON object")
        }
        if take(91) {
            if take(93) { return }
            repeat { try value(depth: depth + 1); if take(93) { return } } while take(44)
            throw CompanionError.invalid("JSON array")
        }
        if bytes[offset] == 34 { _ = try string(); return }
        let start = offset
        while offset < bytes.count && ![9,10,13,32,44,93,125].contains(bytes[offset]) { offset += 1 }
        guard offset > start else { throw CompanionError.invalid("JSON value") }
        // JSONSerialization below validates the literal/number grammar.
    }
}
