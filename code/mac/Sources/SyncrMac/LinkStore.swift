import Foundation
import Security

/// A phone this Mac has paired with. The token is a shared secret: it is stored in the app-state
/// folder and never logged or shown.
struct PairedPhone: Codable, Equatable, Sendable {
    var phoneId: String
    var name: String
    var token: String
}

/// Paired phones, `<library>/.mac/paired-phones.json`. Thread-safe: connection queues read it while the
/// main thread edits it. A missing or corrupt file means no phones are paired.
final class PairedPhoneStore: @unchecked Sendable {
    static let fileName = "paired-phones.json"
    private let lock = NSLock()
    private let file: URL
    private var phones: [PairedPhone]

    init(folder: URL) {
        file = folder.appendingPathComponent(Self.fileName)
        phones = (try? JSONDecoder().decode([PairedPhone].self, from: Data(contentsOf: file))) ?? []
    }

    var all: [PairedPhone] { lock.lock(); defer { lock.unlock() }; return phones }
    func phone(id: String) -> PairedPhone? { all.first { $0.phoneId == id } }

    func add(_ phone: PairedPhone) {
        lock.lock(); defer { lock.unlock() }
        phones.removeAll { $0.phoneId == phone.phoneId }
        phones.append(phone)
        save()
    }

    @discardableResult func forget(id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let before = phones.count
        phones.removeAll { $0.phoneId == id }
        if phones.count != before { save() }
        return phones.count != before
    }

    private func save() {
        let fm = FileManager.default
        try? fm.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(phones) else { return }
        try? data.write(to: file, options: .atomic)
        try? fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }

    /// 32 random bytes from the system generator, as 64 hex characters.
    static func newToken() -> String { randomHex(bytes: 32) }

    static func randomHex(bytes count: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: count)
        if SecRandomCopyBytes(kSecRandomDefault, count, &bytes) != errSecSuccess {
            var rng = SystemRandomNumberGenerator()
            for i in 0..<count { bytes[i] = UInt8.random(in: 0...255, using: &rng) }
        }
        return Data(bytes).hex
    }

    /// Four random digits, zero padded.
    static func newCode() -> String {
        var rng = SystemRandomNumberGenerator()
        return String(format: "%04d", Int.random(in: 0...9999, using: &rng))
    }

    /// Compares without stopping at the first difference.
    static func sameToken(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<x.count { diff |= x[i] ^ y[i] }
        return diff == 0
    }
}

/// Small persistent switches and this Mac's link identity, `<library>/.mac/link.json`.
struct LinkSettings: Codable, Equatable, Sendable {
    var enabled = true
    var follow = true
    var macId = PairedPhoneStore.randomHex(bytes: 16)
    static let fileName = "link.json"

    static func load(_ read: (String) -> Data?) -> LinkSettings {
        guard let data = read(fileName), let value = try? JSONDecoder().decode(LinkSettings.self, from: data),
              LinkIds.validHex(value.macId, count: 32) else { return LinkSettings() }
        return value
    }
}

/// Clock offset between the phone's monotonic clock and ours, from ping and pong round trips. The
/// sample with the lowest round trip is the least disturbed by queueing, so it gives the offset
/// (`offset = pong.now - (at + rtt / 2)`, phone minus Mac). Only the most recent samples count, so a
/// slow drift is followed rather than frozen at the first sample.
struct ClockSync: Equatable, Sendable {
    struct Sample: Equatable, Sendable { var rttMs: Double; var offsetMs: Double }
    static let window = 32
    private(set) var samples: [Sample] = []

    /// `sentMs`: our monotonic ms when the ping went out. `rttMs`: round trip. `remoteNowMs`: the phone's
    /// monotonic ms when it answered.
    mutating func add(sentMs: Double, rttMs: Double, remoteNowMs: Double) {
        guard rttMs >= 0, rttMs.isFinite, remoteNowMs.isFinite else { return }
        samples.append(Sample(rttMs: rttMs, offsetMs: remoteNowMs - (sentMs + rttMs / 2)))
        if samples.count > Self.window { samples.removeFirst(samples.count - Self.window) }
    }

    var best: Sample? { samples.min { $0.rttMs < $1.rttMs } }
    var offsetMs: Double? { best?.offsetMs }
    var latestRttMs: Double? { samples.last?.rttMs }

    /// A phone-clock timestamp on our clock (ms).
    func macMs(fromPhoneMs ms: Double) -> Double? { offsetMs.map { ms - $0 } }
}
