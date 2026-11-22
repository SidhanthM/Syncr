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

    // The provider process is started before the ask: typing warms it, and cancelling leaves the next one starting.
    let script = tmp.appendingPathComponent("pi-speed.py")
    try FileManager.default.copyItem(at: Bundle.module.url(forResource: "pi-stream", withExtension: "py", subdirectory: "Fixtures")!, to: script)
    try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: script.path)
    let pi = RequestInput(markdown: "cache", changed: ByteSpan(start: 0, end: 5), instruction: "Explain cache")
    func cold(_ trace: TraceLines) -> String? { ((json(trace.lines.last ?? "")["facts"]) as? [String: String])?["answer.cold"] }
    let t2 = TraceLines()
    let coldEngine = CompanionEngine(provider: CodexProvider(executable: script.path), models: ModelSettings(gate: "g", answer: "a"), trace: t2.sink)
    _ = try await run(coldEngine, .ask(pi)); check(cold(t2) == "1", "first ask with no typing starts the provider process"); await coldEngine.shutdown()
    let t3 = TraceLines()
    let warmed = CompanionEngine(provider: CodexProvider(executable: script.path), models: ModelSettings(gate: "g", answer: "a"), trace: t3.sink)
    await warmed.configure(mode: .whenAsked, unpinnedNotes: 0); await warmed.activity(at: 0)
    try await Task.sleep(nanoseconds: 400_000_000)
    _ = try await run(warmed, .ask(pi), at: 1); check(cold(t3) == "0", "typing warms the provider process before the ask")
    try await Task.sleep(nanoseconds: 300_000_000)
    var slow = pi; slow.instruction = "SIMULATE_SLOW"
    let slowStream = try await warmed.events(for: .ask(slow), at: 2); let slowReader = Task { for await _ in slowStream {} }
    try await Task.sleep(nanoseconds: 150_000_000); await warmed.activity(at: 3); await slowReader.value   // typing cancels it
    try await Task.sleep(nanoseconds: 600_000_000)
    _ = try await run(warmed, .ask(pi), at: 30); check(cold(t3) == "0", "a cancelled pass leaves a warm process for the next ask")
    await warmed.shutdown()
    print("PASS speed: trace stages bounded and free of text, warm provider process after typing and after cancel")
}
