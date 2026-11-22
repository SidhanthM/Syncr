import Foundation
import SyncrCompanion

/// Real provider, real engine, the same trace. Each ask is one live model call: at most 20 per run, each appended to the ledger
/// before it is made. Prints per-ask first text and first picture step plus the engine's own stage times (from the trace),
/// then p50 and max. Without a signed-in pi it stops before any call.
func liveRun(_ options: Options, _ fixtures: Fixtures) async -> Never {
    func stop(_ message: String, _ code: Int32) -> Never { print(message); exit(code) }
    guard (1...20).contains(options.live), let ledger = options.ledger else { stop("--live needs 1...20 and --ledger <live-calls.jsonl>", 2) }
    let provider = CodexProvider()
    let state = await provider.signInState()
    guard state == "Signed in with Codex (pi)" else { stop("not signed in (\(state)); no call made. Run `pi auth login openai-codex`.", 3) }
    guard let model = CodexProvider.newestSol((try? await provider.models()) ?? []) else { stop("pi offers no Sol model; no call made.", 3) }
    let scenario = fixtures.scenarios.first { $0.name == (options.picture ? "ask-picture" : "ask-text") }!
    let trace = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-live-trace-" + UUID().uuidString + ".jsonl")
    defer { try? FileManager.default.removeItem(at: trace) }
    var texts: [Double] = [], steps: [Double] = [], engines: [Double] = []
    var clock = 0.0
    // One engine for the run, as in the app: the first ask may be cold; typing before each ask warms the next.
    let engine = CompanionEngine(provider: provider, models: ModelSettings(gate: model, answer: model), inspector: ReferencePictureInspector(), trace: .file(trace))
    await engine.configure(mode: .whenAsked, unpinnedNotes: 0)
    for n in 1...options.live {
        await engine.activity(at: clock)
        try? await Task.sleep(nanoseconds: UInt64(max(options.gaps.last ?? 2, 0) * 1e9))
        let entry: [String: Any] = ["time": ISO8601DateFormatter().string(from: Date()), "agent": "companion-speed", "model": model, "purpose": "speed \(scenario.name) \(n)/\(options.live)"]
        if let data = try? JSONSerialization.data(withJSONObject: entry, options: [.sortedKeys]), let line = String(data: data, encoding: .utf8) {
            let url = URL(fileURLWithPath: ledger); try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            if let handle = try? FileHandle(forWritingTo: url) { _ = try? handle.seekToEnd(); try? handle.write(contentsOf: Data((line + "\n").utf8)); try? handle.close() } else { try? Data((line + "\n").utf8).write(to: url) }
        }
        let input = RequestInput(tell: scenario.tell, markdown: scenario.markdown, selection: nil, instruction: options.picture ? "Explain this passage using a step-by-step picture, then two or three sentences on how to read it." : "Asked directly: explain.")
        clock += 40
        let start = Date(); var text: Double?, step: Double?
        do {
            for await out in try await engine.events(for: .ask(input), at: clock) {
                switch out {
                case .textPreview(let p?) where !p.text.isEmpty: if text == nil { text = Date().timeIntervalSince(start) }
                case .item(.margin), .item(.reply): if text == nil { text = Date().timeIntervalSince(start) }
                case .pictureStep: if step == nil { step = Date().timeIntervalSince(start) }
                case .failed(let m): print("  ask \(n) failed: \(m)")
                default: break
                }
            }
        } catch { print("  ask \(n) not started: \(error)") }
        try? await Task.sleep(nanoseconds: 100_000_000)
        var engineSide = "-"
        if let line = ((try? String(contentsOf: trace)) ?? "").split(separator: "\n").last, let o = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any], let ms = o["ms"] as? [String: Double] {
            let sent = ms["answer.prompt_sent"] ?? 0, firstText = ms["answer.first_text"], shown = ms["text_shown"]
            engineSide = String(format: "before-prompt %.0f ms, first-delta-to-shown %.0f ms, provider %.0f ms", sent, (shown ?? 0) - (firstText ?? 0), (firstText ?? sent) - sent)
            engines.append(sent / 1000)
        }
        print(String(format: "ask %2d  text %@  step %@   %@", n, text.map { String(format: "%.0f ms", $0 * 1000) } ?? "-", step.map { String(format: "%.0f ms", $0 * 1000) } ?? "-", engineSide))
        if let text { texts.append(text) }; if let step { steps.append(step) }
    }
    func line(_ v: [Double]) -> String { v.isEmpty ? "-" : String(format: "p50 %.0f ms, max %.0f ms (n=%d)", median(v)! * 1000, v.max()! * 1000, v.count) }
    print("first text: \(line(texts))\nfirst picture step: \(line(steps))\nengine before prompt: \(line(engines))")
    await engine.shutdown()
    exit(0)
}
