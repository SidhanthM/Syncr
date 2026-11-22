import Foundation

/// A stage a pass goes through, ask to first thing shown. Names are fixed, so a trace line is bounded and can never carry text.
public enum TraceStage: String, Sendable, CaseIterable {
    /// The host called `events(for:)`.
    case ask
    /// The pass task started (after the trigger accepted the ask).
    case started
    /// The request for this leg is built (prompt assembled, examples chosen).
    case requestBuilt = "request_built"
    /// The provider's `stream` was called.
    case providerStart = "provider_start"
    /// The provider child is running and its session is reset (cold: it had to be started).
    case childReady = "child_ready"
    /// The prompt is on the wire.
    case promptSent = "prompt_sent"
    /// The first record of any kind came back after the prompt.
    case firstRecord = "first_record"
    /// The provider handed over its first text delta.
    case firstText = "first_text"
    /// The engine received that first delta.
    case firstByte = "first_byte"
    /// The engine emitted the first visible text (preview or item) for the host.
    case textShown = "text_shown"
    /// The parser produced the first picture step.
    case pictureStepParsed = "picture_step_parsed"
    /// The engine emitted the first picture step for the host.
    case pictureStepShown = "picture_step_shown"
    case done
}

/// Per-pass stage clock. Only the first time each stage is reached is kept, so a pass holds at most
/// `TraceStage.allCases.count` numbers per request leg (gate, answer, repair). It never sees prompt, note or answer text.
public final class PassTrace: @unchecked Sendable {
    private let lock = NSLock()
    private let origin = DispatchTime.now().uptimeNanoseconds
    private var marks: [String: Double] = [:]
    private var facts: [String: String] = [:]
    private let purpose: String
    public init(purpose: String) { self.purpose = purpose }
    public func mark(_ stage: TraceStage, leg: String = "") {
        let key = leg.isEmpty ? stage.rawValue : leg + "." + stage.rawValue
        let ms = Double(DispatchTime.now().uptimeNanoseconds &- origin) / 1_000_000
        lock.lock(); if marks[key] == nil { marks[key] = ms }; lock.unlock()
    }
    /// A short label or number (`cold`, `promptBytes`, `outcome`); numbers and fixed words only. The first value for a key is kept.
    public func fact(_ key: String, _ value: String) { lock.lock(); if facts.count < 16, facts[key] == nil { facts[key] = String(value.prefix(32)) }; lock.unlock() }
    public func leg(_ name: String) -> TraceLeg { TraceLeg(pass: self, name: name) }
    public func stage(_ key: String) -> Double? { lock.lock(); defer { lock.unlock() }; return marks[key] }
    /// One JSON line: `{"v":1,"purpose":"ask","ms":{...},"facts":{...}}`, times in milliseconds since `ask`.
    public func line() -> String {
        lock.lock(); let marks = marks, facts = facts; lock.unlock()
        let rounded = marks.mapValues { ($0 * 10).rounded() / 10 }
        let object: [String: Any] = ["v": 1, "purpose": purpose, "ms": rounded, "facts": facts]
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }
}

/// One provider request inside a pass, with its own stage-name prefix (`gate`, `answer`, `repair`).
public struct TraceLeg: Sendable {
    let pass: PassTrace; let name: String
    public func mark(_ stage: TraceStage) { pass.mark(stage, leg: name) }
    public func fact(_ key: String, _ value: String) { pass.fact(name + "." + key, value) }
}

/// Opt-in trace output. Off unless a sink is given or `SYNCR_COMPANION_TRACE` names a file; then one line is appended per pass.
public struct TraceSink: Sendable {
    public let write: @Sendable (String) -> Void
    public init(_ write: @escaping @Sendable (String) -> Void) { self.write = write }
    public static func file(_ url: URL) -> TraceSink {
        let lock = NSLock()
        return TraceSink { line in
            lock.lock(); defer { lock.unlock() }
            guard let data = (line + "\n").data(using: .utf8) else { return }
            if let handle = try? FileHandle(forWritingTo: url) { defer { try? handle.close() }; _ = try? handle.seekToEnd(); try? handle.write(contentsOf: data) }
            else { try? data.write(to: url) }
        }
    }
    public static func fromEnvironment() -> TraceSink? {
        guard let path = ProcessInfo.processInfo.environment["SYNCR_COMPANION_TRACE"], !path.isEmpty else { return nil }
        return .file(URL(fileURLWithPath: path))
    }
}
