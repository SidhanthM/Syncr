import Foundation
import SyncrCompanion

/// A sink that keeps the lines the engine's trace writes.
final class TraceLines: @unchecked Sendable {
    private let lock = NSLock(); private var values: [String] = []
    var sink: TraceSink { TraceSink { [self] line in lock.lock(); values.append(line); lock.unlock() } }
    var lines: [String] { lock.lock(); defer { lock.unlock() }; return values }
}
/// Passes its filter at once and holds the geometry check until told, to prove a step is shown before its check finishes.

func runSpeedChecks(tmp: URL) async throws {
    func run(_ engine: CompanionEngine, _ event: EngineEvent, at time: TimeInterval = 0) async throws -> [EngineOutput] {
        var out: [EngineOutput] = []; for await v in try await engine.events(for: event, at: time) { out.append(v) }; return out
    }
    func json(_ line: String) -> [String: Any] { (try? JSONSerialization.jsonObject(with: Data(line.utf8))) as? [String: Any] ?? [:] }
    let note = "A zebra-quartz claim about the cache."
    let margin = "{\"type\":\"margin\",\"quote\":\"zebra-quartz claim\",\"kind\":\"question\",\"text\":\"Which walrus-lantern invalidates it?\"}\n"
    let input = RequestInput(markdown: note, selection: ByteSpan(start: 2, end: 20), instruction: "Explain marmalade-sonata")

    // The trace: opt-in, fixed stages, numbers only.
    let lines = TraceLines()
    let traced = CompanionEngine(provider: RecordedStreamProvider(passes: [RecordedStreamPass(text: margin)]), models: ModelSettings(gate: "g", answer: "a"), trace: lines.sink)
    await traced.configure(mode: .whenAsked, unpinnedNotes: 0)
    let output = try await run(traced, .ask(input))
    check(output.contains { if case .finished = $0 { return true }; return false }, "traced pass finishes")
    check(lines.lines.count == 1, "one trace line per pass")
    let line = lines.lines[0], parsed = json(line), stages = parsed["ms"] as? [String: Double] ?? [:]
    check(["ask", "started", "answer.request_built", "answer.first_byte", "text_shown", "done"].allSatisfy { stages[$0] != nil }, "trace has the engine stages")
    check(stages["ask"] == 0 && stages["done"]! >= stages["text_shown"]! && stages["text_shown"]! >= stages["answer.first_byte"]!, "trace stages are in order")
    check(line.utf8.count < 1500 && (parsed["facts"] as? [String: String])?["outcome"] == "finished", "trace line is bounded and carries the outcome")
    for secret in ["zebra", "quartz", "walrus", "lantern", "marmalade", "sonata", "cache", "Explain"] { check(!line.contains(secret), "trace carries no note, prompt or answer text (\(secret))") }
    let quiet = CompanionEngine(provider: RecordedStreamProvider(passes: [RecordedStreamPass(text: margin)]), models: ModelSettings(gate: "g", answer: "a"), trace: nil)
    await quiet.configure(mode: .whenAsked, unpinnedNotes: 0)
    check(try await run(quiet, .ask(input)).contains { if case .finished = $0 { return true }; return false }, "pass without a trace sink is unchanged")

    print("PASS speed: trace stages bounded and free of text")
}
