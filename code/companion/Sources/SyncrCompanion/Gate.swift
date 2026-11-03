import Foundation

public struct ModelSettings: Codable, Equatable, Sendable {
    public var gate: String; public var answer: String
    public init(gate: String, answer: String) { self.gate = gate; self.answer = answer }
    public static func defaults(from models: [ProviderModel]) throws -> ModelSettings {
        let ids = models.map(\.id).filter { !$0.contains("embedding") && !$0.contains("audio") && !$0.contains("realtime") && !$0.contains("image") && !$0.contains("tts") && !$0.contains("transcribe") && !$0.contains("moderation") && !$0.contains("search") }
        guard !ids.isEmpty else { throw CompanionError.unavailable("No text models offered by this provider.") }
        func newest(_ values: [String]) -> String? { values.sorted { $0.compare($1, options: .numeric) == .orderedDescending }.first }
        func family(_ names: [String]) -> String? {
            for name in names { if let found = newest(ids.filter { $0.lowercased().contains(name) }) { return found } }; return nil
        }
        let priced = models.filter { ids.contains($0.id) && ($0.inputCost ?? 0) > 0 && ($0.outputCost ?? 0) > 0 }
        let cheapest = priced.sorted { a, b in
            let ca = a.inputCost! + a.outputCost!, cb = b.inputCost! + b.outputCost!
            return ca == cb ? a.id.compare(b.id, options: .numeric) == .orderedDescending : ca < cb
        }.first?.id
        let gate = cheapest ?? family(["nano", "haiku", "spark", "luna", "mini", "terra"]) ?? newest(ids)!
        let answer = family(["sol", "fable", "mythos", "opus", "pro", "astra", "sonnet"]) ?? newest(ids)!
        return ModelSettings(gate: gate, answer: answer)
    }
}
public struct GateDecision: Codable, Equatable, Sendable {
    public var decision: String; public var reason: String; public var kind: String
    public var speaks: Bool { decision == "speak" }
    public init(decision: String, reason: String, kind: String) { self.decision = decision; self.reason = reason; self.kind = kind }
    public static func decode(_ text: String) throws -> GateDecision {
        let data = Data(text.utf8); guard data.count <= 2048 else { throw CompanionError.invalid("gate size") }
        try StrictJSON.check(data)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], Set(root.keys) == ["decision", "reason", "kind"] else { throw CompanionError.invalid("gate fields") }
        let value = try JSONDecoder().decode(Self.self, from: data)
        guard ["quiet", "speak"].contains(value.decision), ["question", "explanation", "picture"].contains(value.kind), !value.reason.isEmpty, value.reason.count <= 240, !value.reason.contains("\n"), !value.reason.contains("\0") else { throw CompanionError.invalid("gate decision") }; return value
    }
}
public enum FeedbackAction: String, Codable, Sendable { case dismiss, pin, addToNote }
public struct LocalLogEntry: Codable, Sendable {
    public var time: String = ISO8601DateFormatter().string(from: Date())
    public var event: String; public var passage: String
    public var itemID: String? = nil; public var action: FeedbackAction? = nil
    public var model: String? = nil; public var gate: GateDecision? = nil; public var seconds: Double? = nil
    public var findings: [PictureFinding]? = nil
}
public actor LocalDecisionLog {
    public let url: URL
    public init(stateFolder: URL) { url = stateFolder.appendingPathComponent("companion-decisions.jsonl") }
    public func append(_ entry: LocalLogEntry) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]; var data = try encoder.encode(entry); data.append(10)
        if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600]) }
        let handle = try FileHandle(forWritingTo: url); defer { try? handle.close() }; try handle.seekToEnd(); try handle.write(contentsOf: data)
    }
    public func recordFeedback(action: FeedbackAction, passage: String, itemID: String) throws {
        guard passage.utf8.count <= 16384, itemID.utf8.count <= 128 else { throw CompanionError.invalid("feedback size") }
        try append(LocalLogEntry(event: "feedback", passage: passage, itemID: itemID, action: action))
    }
}
