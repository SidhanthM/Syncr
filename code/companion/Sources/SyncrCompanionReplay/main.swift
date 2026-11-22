import Foundation
import SyncrCompanion

// Replay harness: the real engine and the real pi transport (PersistentCodexRPC) against a fake `pi` that replays
// recorded answers with recorded timing. It separates the engine's own time from the provider's:
//   engine time = (ask to first thing shown) - (recorded provider time on the way there).
// With --provider-scale 0 (default) the provider answers instantly, so the whole total is engine time.
// pi's own boot and session-reset costs are modelled from measurements of the real pi (see --pi-cold / --pi-session).
//
//   swift run SyncrCompanionReplay [--runs N] [--gaps 0,2] [--pi-cold S] [--pi-session S] [--provider-scale X]
//                                  [--only name] [--json out.json]
//   swift run SyncrCompanionReplay --live N --ledger <live-calls.jsonl> [--picture]   (real provider, at most 20 calls; see Live.swift)

struct Scenario: Decodable { var name, source, kind, markdown, passage, tell, response, gateText: String; var firstTokenSeconds, gateSeconds: Double }
struct Fixtures: Decodable { var charsPerSecond: Double; var chunkChars: Int; var scenarios: [Scenario] }
struct Options {
    var runs = 5, gaps = [0.0, 2.0]; var piCold = 1.26, piSession = 0.053, scale = 0.0; var only: String?; var json: String?; var live = 0; var ledger: String?; var picture = false
    init() {
        var it = CommandLine.arguments.dropFirst().makeIterator()
        while let a = it.next() {
            switch a {
            case "--runs": runs = Int(it.next() ?? "") ?? runs
            case "--gaps": gaps = (it.next() ?? "").split(separator: ",").compactMap { Double($0) }
            case "--pi-cold": piCold = Double(it.next() ?? "") ?? piCold
            case "--pi-session": piSession = Double(it.next() ?? "") ?? piSession
            case "--provider-scale": scale = Double(it.next() ?? "") ?? scale
            case "--only": only = it.next()
            case "--json": json = it.next()
            case "--live": live = Int(it.next() ?? "") ?? 0
            case "--ledger": ledger = it.next()
            case "--picture": picture = true
            default: FileHandle.standardError.write(Data("unknown option \(a)\n".utf8)); exit(2)
            }
        }
    }
}
struct Event: Decodable { var t: Double; var ev: String; var i: Int?; var gate: Bool? }
struct Sample { var total, engine, before, stepTotal, stepEngine: Double?; var trace: [String: Double] }
func now() -> Double { Date().timeIntervalSince1970 }
func median(_ v: [Double]) -> Double? { v.isEmpty ? nil : v.sorted()[v.count / 2] }

/// Which recorded chunk first makes `text` or a picture step possible, by feeding the recording to the parser alone.
func triggerChunks(_ s: Scenario, chunk: Int) -> (text: Int?, step: Int?) {
    let reading = s.kind == "read"
    let input = RequestInput(markdown: s.markdown, instruction: "x")
    var parser = ItemStreamParser(input: input, marginWords: reading ? 60 : 150)
    var text: Int?; var step: Int?; let chars = Array(s.response); var i = 0, index = 0
    while i < chars.count {
        let part = String(chars[i..<min(i + chunk, chars.count)]); i += chunk
        try? parser.append(part) { e in
            switch e {
            case .textPreview(let p?) where !p.text.isEmpty: if text == nil { text = index }
            case .item(.margin), .item(.reply): if text == nil { text = index }
            case .pictureStep: if step == nil { step = index }
            default: break
            }
        }
        index += 1
    }
    return (text, step)
}

func engineEvents(_ s: Scenario) -> EngineEvent {
    let input = RequestInput(tell: s.tell, markdown: s.markdown, instruction: s.kind == "read" ? "Say what you think about this passage." : "Asked directly: explain.", web: false)
    return s.kind == "read" ? .pause(input) : .ask(input)
}

actor Runner {
    let options: Options, fixtures: Fixtures, script: URL, work: URL
    var counter = 0, clock = 1000.0   // simulated seconds: a read-along needs 20 s between passes, which the replay should not wait for
    init(_ o: Options, _ f: Fixtures, _ script: URL, _ work: URL) { options = o; fixtures = f; self.script = script; self.work = work }

    var configFile: URL?; var traceFile: URL?
    /// Before an engine is made: its fake-pi config file and trace file (the engine reads the trace path when it is created).
    func beginEngine() {
        counter += 1
        configFile = work.appendingPathComponent("config-\(counter).json"); traceFile = work.appendingPathComponent("trace-\(counter).jsonl")
        setenv("FAKE_PI_CONFIG", configFile!.path, 1); setenv("SYNCR_COMPANION_TRACE", traceFile!.path, 1)
    }
    /// Per ask: point the fake pi at a fresh event log and this scenario's recording.
    func configure(_ s: Scenario) throws -> (log: URL, trace: URL) {
        counter += 1
        let log = work.appendingPathComponent("pi-\(counter).log"), scale = options.scale
        let config: [String: Any] = ["log": log.path, "coldSeconds": options.piCold, "sessionSeconds": options.piSession, "response": s.response, "gateText": s.gateText,
            "firstTokenSeconds": s.firstTokenSeconds * scale, "gateSeconds": s.gateSeconds * scale, "chunkChars": fixtures.chunkChars, "charsPerSecond": fixtures.charsPerSecond, "providerScale": scale]
        try JSONSerialization.data(withJSONObject: config).write(to: configFile!, options: .atomic)
        return (log, traceFile!)
    }

    /// One ask on `engine`. `cancelAtFirstText`: type (cancel) as soon as text shows, to leave the child dead for the next ask.
    func ask(_ s: Scenario, engine: CompanionEngine, cancelAtFirstText: Bool = false, trigger: (text: Int?, step: Int?)) async throws -> Sample {
        let (log, trace) = try configure(s)
        let event = engineEvents(s)
        clock += 40; let t = clock
        if s.kind == "read" { await engine.activity(at: t - 5) }
        let start = now()
        let stream = try await engine.events(for: event, at: t)
        var firstText: Double?, firstStep: Double?, finished = false
        for await out in stream {
            switch out {
            case .textPreview(let p?) where !p.text.isEmpty: if firstText == nil { firstText = now() }
            case .item(.margin), .item(.reply): if firstText == nil { firstText = now() }
            case .pictureStep: if firstStep == nil { firstStep = now() }
            case .finished: finished = true
            case .failed(let m): print("  pass failed: \(m)")
            default: break
            }
            if cancelAtFirstText, firstText != nil { await engine.activity(at: 0); break }
        }
        _ = finished
        try? await Task.sleep(nanoseconds: 50_000_000)
        let events = ((try? String(contentsOf: log)) ?? "").split(separator: "\n").compactMap { try? JSONDecoder().decode(Event.self, from: Data($0.utf8)) }
        let prompts = events.filter { $0.ev == "prompt_rx" && $0.gate == false }, gatePrompt = events.first { $0.ev == "prompt_rx" && $0.gate == true }
        let chunks = events.filter { $0.ev == "chunk" && $0.gate == false }
        let answerPrompt = prompts.first?.t
        // Provider time on the way to the measured thing: the gate's whole answer, plus the answer up to the triggering chunk.
        let gateProvider: Double = s.kind == "read" ? ((events.last { $0.ev == "chunk" && $0.gate == true }?.t ?? 0) - (gatePrompt?.t ?? 0)) : 0
        func sample(_ shown: Double?, _ k: Int?) -> (Double?, Double?, Double?) {
            guard let shown, let k, let answerPrompt, let c = chunks.first(where: { $0.i == k }) else { return (nil, nil, nil) }
            let total = shown - start
            return (total, total - gateProvider - (c.t - answerPrompt), answerPrompt - start)
        }
        let text = sample(firstText, trigger.text), step = sample(firstStep, trigger.step)
        var tr: [String: Double] = [:]
        if let line = ((try? String(contentsOf: trace)) ?? "").split(separator: "\n").last, let o = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any], let ms = o["ms"] as? [String: Double] { tr = ms }
        return Sample(total: text.0, engine: text.1, before: text.2, stepTotal: step.0, stepEngine: step.1, trace: tr)
    }
}
struct Row { var scenario, condition: String; var gap: Double; var text: [Sample]; var step: [Sample] }
let options = Options()
guard let fixtureURL = Bundle.module.url(forResource: "scenarios", withExtension: "json", subdirectory: "Fixtures"),
      let scriptURL = Bundle.module.url(forResource: "fake-pi", withExtension: "py", subdirectory: "Fixtures") else { fatalError("fixtures missing") }
let fixtures = try JSONDecoder().decode(Fixtures.self, from: Data(contentsOf: fixtureURL))
let work = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-replay-" + UUID().uuidString)
try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
defer { if ProcessInfo.processInfo.environment["REPLAY_KEEP"] == nil { try? FileManager.default.removeItem(at: work) } else { print("kept \(work.path)") } }
let script = work.appendingPathComponent("fake-pi.py"); try FileManager.default.copyItem(at: scriptURL, to: script)
try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: script.path)
let runner = Runner(options, fixtures, script, work)
if options.live > 0 { await liveRun(options, fixtures) }
func makeEngine(_ s: Scenario) -> CompanionEngine {
    CompanionEngine(provider: CodexProvider(executable: script.path), models: ModelSettings(gate: "gate-model", answer: "answer-model"), inspector: ReferencePictureInspector(), stateFolder: nil)
}
func ms(_ v: Double?) -> String { v.map { String(format: "%.0f", $0 * 1000) } ?? "-" }
func summary(_ v: [Double]) -> String { v.isEmpty ? "-" : "\(ms(median(v)))/\(ms(v.max()))" }

print("replay: runs=\(options.runs) pi-cold=\(options.piCold)s pi-session=\(options.piSession)s provider-scale=\(options.scale)")
print("columns are p50/max in ms. total = ask to first thing shown; engine = total minus recorded provider time; before = ask to prompt on the wire")
var rows: [Row] = []; var providers: [String: [String: Double]] = [:]
let conditions = ["cold", "warm", "after-cancel"]
for scenario in fixtures.scenarios where options.only == nil || options.only == scenario.name {
    let trig = triggerChunks(scenario, chunk: fixtures.chunkChars)
    // Recorded provider time to the same two moments: first-token (or gate) time plus the synthetic pace up to the triggering chunk.
    let pace = Double(fixtures.chunkChars) / fixtures.charsPerSecond, lead = scenario.firstTokenSeconds + (scenario.kind == "read" ? scenario.gateSeconds + Double(scenario.gateText.count / fixtures.chunkChars) * pace : 0)
    let providerText = trig.text.map { lead + Double($0) * pace }, providerStep = trig.step.map { lead + Double($0) * pace }
    print("\(scenario.name): recorded provider time to first text \(ms(providerText)) ms, to first picture step \(ms(providerStep)) ms (first token \(ms(scenario.firstTokenSeconds)) ms)")
    providers[scenario.name] = ["text": providerText ?? -1, "step": providerStep ?? -1]
    for gap in options.gaps {
        for condition in conditions {
            var texts: [Sample] = [], steps: [Sample] = []
            for _ in 0..<options.runs {
                await runner.beginEngine(); try? await runner.configure(scenario)
                let engine = makeEngine(scenario)
                await engine.configure(mode: scenario.kind == "read" ? .thinksWithYou : .whenAsked, unpinnedNotes: 0)
                await engine.activity(at: 0)      // the host reports typing before an ask
                var sample: Sample
                do {
                    if gap > 0 { try await Task.sleep(nanoseconds: UInt64(gap * 1e9)) }
                    switch condition {
                    case "cold": sample = try await runner.ask(scenario, engine: engine, trigger: trig)
                    case "warm":
                        _ = try await runner.ask(scenario, engine: engine, trigger: trig)
                        await engine.idleActivity(at: 0)
                        if gap > 0 { try await Task.sleep(nanoseconds: UInt64(gap * 1e9)) }
                        sample = try await runner.ask(scenario, engine: engine, trigger: trig)
                    default:
                        _ = try await runner.ask(scenario, engine: engine, cancelAtFirstText: true, trigger: trig)
                        if gap > 0 { try await Task.sleep(nanoseconds: UInt64(gap * 1e9)) }
                        sample = try await runner.ask(scenario, engine: engine, trigger: trig)
                    }
                    texts.append(sample)
                    if sample.stepTotal != nil { steps.append(sample) }
                } catch { print("  run failed: \(error)") }
                await engine.shutdown()
            }
            rows.append(Row(scenario: scenario.name, condition: condition, gap: gap, text: texts, step: steps))
            let tt = texts.compactMap(\.total), te = texts.compactMap(\.engine), tb = texts.compactMap(\.before)
            let st = steps.compactMap(\.stepTotal), se = steps.compactMap(\.stepEngine)
            print(String(format: "%-12@ %-13@ gap %.0fs  text total %@  engine %@  before %@   step total %@  engine %@  (n=%d/%d)", scenario.name as NSString, condition as NSString, gap, summary(tt), summary(te), summary(tb), summary(st), summary(se), texts.count, steps.count))
        }
    }
}
if let path = options.json {
    let out = rows.map { r -> [String: Any] in
        ["scenario": r.scenario, "condition": r.condition, "gap": r.gap, "textTotal": r.text.compactMap(\.total), "textEngine": r.text.compactMap(\.engine), "before": r.text.compactMap(\.before),
         "stepTotal": r.step.compactMap(\.stepTotal), "stepEngine": r.step.compactMap(\.stepEngine),
         "traceMedianMs": Dictionary(uniqueKeysWithValues: Set(r.text.flatMap { $0.trace.keys }.map { $0 }).compactMap { k in median(r.text.compactMap { $0.trace[k] }).map { (k, $0) } })]
    }
    try JSONSerialization.data(withJSONObject: ["options": ["runs": options.runs, "piCold": options.piCold, "piSession": options.piSession, "providerScale": options.scale], "providerSeconds": providers, "rows": out], options: [.prettyPrinted, .sortedKeys]).write(to: URL(fileURLWithPath: path))
}
