import Foundation
import Darwin

/// What the Mac's QR code holds (remaining-v1 P.1): `syncr://pair?h=<address>&p=<port>&m=<mac_id>&n=<name>&e=<unix seconds>`.
/// Where this Mac listens and who it is, never a secret: the four digits shown once a phone has connected are what pairs.
/// `e` is when the Mac stops offering the code; the phone refuses a link past it. Pure, so the checks decode what the screen shows.
struct PairingLink: Equatable, Sendable {
    var host: String
    var port: UInt16
    var macId: String
    var name: String
    var expires: UInt64?

    /// How long a code is offered.
    static let lifetime: TimeInterval = 120

    private static let nameAllowed: CharacterSet = {
        var set = CharacterSet.alphanumerics
        set.insert(charactersIn: "-._~")
        return set
    }()

    var url: String {
        var s = "syncr://pair?h=\(host)&p=\(port)&m=\(macId)&n=\(name.addingPercentEncoding(withAllowedCharacters: Self.nameAllowed) ?? "")"
        if let expires { s += "&e=\(expires)" }
        return s
    }

    /// Reads a link back; nil unless it is a well-formed pairing link. The phone's own parser is stricter about where the host may be.
    static func parse(_ text: String) -> PairingLink? {
        let head = "syncr://pair?"
        guard text.lowercased().hasPrefix(head) else { return nil }
        var query: [String: String] = [:]
        for part in text.dropFirst(head.count).split(separator: "&", omittingEmptySubsequences: true) {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { return nil }
            let key = String(part[part.startIndex..<eq]), value = String(part[part.index(after: eq)...])
            guard query[key] == nil, let decoded = value.removingPercentEncoding else { return nil }
            query[key] = decoded
        }
        guard let host = query["h"], !host.isEmpty, let port = query["p"].flatMap({ UInt16($0) }), port > 0,
              let macId = query["m"], LinkIds.validHex(macId, count: 32) else { return nil }
        return PairingLink(host: host, port: port, macId: macId.lowercased(), name: query["n"] ?? "", expires: query["e"].flatMap { UInt64($0) })
    }

    /// This Mac's address on the local network: the first private IPv4 of an up Ethernet or Wi-Fi interface (`en*`), Wi-Fi first.
    static func localAddress() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var found: [(name: String, address: String)] = []
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let a = p.pointee
            guard let sa = a.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET), Int32(a.ifa_flags) & IFF_UP != 0, Int32(a.ifa_flags) & IFF_LOOPBACK == 0 else { continue }
            let name = String(cString: a.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &addr, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil else { continue }
            let text = String(cString: buffer)
            if isPrivate(text) { found.append((name, text)) }
        }
        return found.sorted { $0.name < $1.name }.first?.address
    }

    static func isPrivate(_ address: String) -> Bool {
        let n = address.split(separator: ".").compactMap { Int($0) }
        guard n.count == 4 else { return false }
        return n[0] == 10 || (n[0] == 172 && (16...31).contains(n[1])) || (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254)
    }
}
