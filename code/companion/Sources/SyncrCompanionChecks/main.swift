import Foundation
import SyncrCompanion

func check(_ value: Bool, _ label: String) { precondition(value, label) }
func rejects(_ data: Data, _ label: String) {
    do { _ = try ToolValidator.validate(data); fatalError("accepted \(label)") } catch {}
}
struct CaptureHTTP: HTTPTransport {
    let capture: Capture
    func send(_ request: URLRequest) async throws -> (Data, Int) {
        await capture.record(request)
        return (Data("{\"content\":[{\"type\":\"tool_use\",\"name\":\"stay_quiet\",\"input\":{}}],\"usage\":{\"input_tokens\":8,\"output_tokens\":2,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":1},\"stop_reason\":\"tool_use\"}".utf8), 200)
    }
}
actor Capture {
    var requests: [URLRequest] = []
    func record(_ r: URLRequest) { requests.append(r) }
    func count() -> Int { requests.count }
}
actor PausedProvider: CompanionProvider {
    let recorded: RecordedProvider
    var entered = false
    var continuation: CheckedContinuation<Void,Never>?
    init(_ fixture: Data) { recorded = RecordedProvider(responses:[fixture]) }
    func signInState() async -> String { "recorded" }
    func models() async throws -> [ProviderModel] { [] }
    func send(_ request: CompanionRequest) async throws -> ProviderResponse {
        await withCheckedContinuation { continuation = $0; entered = true }
        return try await recorded.send(request)
    }
    func release() { continuation?.resume(); continuation = nil }
}
@main struct Checks {
    static func main() async throws {
        if CommandLine.arguments.contains("--codex-idle") {
            let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-codex-idle-checks-" + UUID().uuidString)
            try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: tmp) }
            try await codexIdleChecks(tmp: tmp)
            return
        }
        let fixture = try Data(contentsOf: Bundle.module.url(forResource: "responses", withExtension: "json", subdirectory: "Fixtures")!)
        let provider = RecordedProvider(responses: Array(repeating: fixture, count: 8))
        let request = try CompanionRequest(model: "recorded", input: RequestInput(markdown: "A cache is always fresh.", changed: ByteSpan(start: 2, end: 7), selection: ByteSpan(start: 0, end: 23)))
        check(request.user.contains("<changed>cache</changed>"), "changed span markers")
        check(request.user.contains("<selection>"), "selection markers")
        let result = try await provider.send(request)
        check(result.tools.count == 1 && result.usage.total == 150, "recorded tool and usage")
        check(PassageFinder.find("cache is", in: "é cache is fresh") == ByteSpan(start: 3, end: 11), "UTF8 exact anchor")
        check(PassageFinder.find("cache is fresh", in: "cache \n is\t fresh") == ByteSpan(start: 0, end: 17), "whitespace matching")
        check(PassageFinder.find("not present", in: "cache") == nil, "no-match drop")
        check(PassageFinder.find(" \n ", in: "cache") == nil, "empty quote drop")
        rejects(Data("{\"calls\":[{\"name\":\"write_file\",\"arguments\":{}}]}".utf8), "unknown tool")
        rejects(Data("{\"calls\":[{\"name\":\"stay_quiet\",\"arguments\":{\"extra\":1}}]}".utf8), "unknown field")
        rejects(Data(repeating: 32, count: 65537), "oversized")
        rejects(Data("```json\n{}\n```".utf8), "markdown output")
        rejects(Data("{\"calls\":[],\"calls\":[{\"name\":\"stay_quiet\",\"arguments\":{}}]}".utf8), "duplicate JSON key")
        let draw: [String: Any] = ["quote": "cache", "title": "Flow", "width": 500, "height": 300, "steps": [["caption": "Connect", "primitives": [["type": "line", "points": [["x": -5, "y": 20], ["x": 9999, "y": 25]]], ["type": "label", "x": 30, "y": 30, "text": "Cache", "size": 999]]]]]
        let drawingData = try ToolValidator.envelope(nativeCalls: [["name": "draw", "arguments": draw]])
        let drawingTools = try ToolValidator.validate(drawingData)
        guard case .draw(let drawing) = drawingTools[0] else { fatalError() }
        check(drawing.steps[0].primitives[0].points?.first?.x == 0 && drawing.steps[0].primitives[0].points?.last?.x == 500, "coordinate clamping")
        check(drawing.steps[0].primitives[1].size == 40, "font clamping")
        var bad = draw; bad["steps"] = Array(repeating: ["caption": "", "primitives": []] as [String: Any], count: 13)
        rejects(try ToolValidator.envelope(nativeCalls: [["name": "draw", "arguments": bad]]), "step count")
        bad = draw; bad["width"] = true
        rejects(try ToolValidator.envelope(nativeCalls: [["name": "draw", "arguments": bad]]), "boolean number")
        let inkData = try ToolValidator.envelope(nativeCalls:[["name":"margin_note","arguments":["region":["x":0.5,"y":0.2,"width":0.25,"height":0.3],"kind":"question","text":"What connects these ink boxes?","sources":[]]]])
        let inkTools = try ToolValidator.validate(inkData)
        check(ResponsePlan.additions(inkTools,markdown:"",threadIDs:[],drawingsEnabled:false).isEmpty,"no image means no canvas anchor")
        let inkPlan = ResponsePlan.additions(inkTools,markdown:"",threadIDs:[],drawingsEnabled:false,pageRegion:CanvasRegion(x:0,y:100,width:3200,height:1800))
        if case .canvasMargin(let region,_,_,_) = inkPlan.first { check(region.x == 1600 && region.y == 460 && region.width == 800,"normalized image region maps to canvas") } else { fatalError("canvas margin dropped") }
        rejects(try ToolValidator.envelope(nativeCalls:[["name":"margin_note","arguments":["quote":"cache","region":["x":0,"y":0,"width":1,"height":1],"kind":"flag","text":"both forbidden","sources":[]]]]),"ambiguous quote plus region")
        let dropped = ResponsePlan.additions([.marginNote(quote: "invented", kind: "flag", text: "No", sources: []), .reply(threadID: "invented", text: "No", sources: []), .draw(drawing)], markdown: "cache", threadIDs: [], drawingsEnabled: false)
        check(dropped.isEmpty, "unmatched passages, unknown threads and experimental drawings dropped")
        let capped = ResponsePlan.additions(result.tools + result.tools, markdown: request.input.markdown, threadIDs: [], drawingsEnabled: false, readingAlong: true, unpinnedNotes: 2)
        check(capped.count == 1, "one margin per reading pass")
        let blocked = ResponsePlan.additions(result.tools, markdown: request.input.markdown, threadIDs: [], drawingsEnabled: false, readingAlong: true, unpinnedNotes: 3)
        check(blocked.isEmpty, "read along screen cap at application boundary")
        let positions = MarginLayout.positions(anchors: [("a", ByteSpan(start: 0, end: 3), 100), ("b", ByteSpan(start: 0, end: 3), 100), ("c", nil, 80)], lines: [MarginLine(span: ByteSpan(start: 0, end: 10), top: 260)])
        check(positions[0].top == 260 && positions[1].top == 378 && positions[2].detached, "margin passage and collision layout")
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-companion-checks-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: tmp) }
        try SketchRenderer.contactSheet(drawing, at: tmp)
        check(FileManager.default.fileExists(atPath: tmp.appendingPathComponent("contact-sheet.png").path), "renderer")
        let readers = try Reader.load(at: tmp.appendingPathComponent("readers.json"))
        check(readers.count == 4 && Reader.tellPresets.count == 4, "starters and presets")
        let reloaded = try Reader.load(at: tmp.appendingPathComponent("readers.json"))
        check(reloaded == readers, "reader round trip")
        var trigger = TriggerMachine(); trigger.mode = .thinksWithYou
        trigger.activity(at: 0); check(!trigger.tick(at: 2.99), "idle minimum"); check(trigger.tick(at: 3), "idle pass")
        trigger.activity(at: 4); check(!trigger.tick(at: 25), "in flight")
        trigger.finish(); check(trigger.tick(at: 25), "edits during flight kept")
        trigger.finish(); trigger.activity(at: 26); check(!trigger.tick(at: 30), "20 seconds minimum")
        trigger.undismissedUnpinned = 3; check(!trigger.tick(at: 46), "screen cap")
        trigger.undismissedUnpinned = 2; trigger.budgetReached = true; check(!trigger.tick(at: 46), "budget stops reading")
        var meter = BudgetMeter(day: "2026-11-14", tokenLimit: 150)
        meter.record(result.usage, day: "2026-11-14"); check(meter.tokens == 150 && meter.reached, "exact usage")
        meter.rollover(day: "2026-11-15"); check(!meter.reached && meter.tokens == 0, "day reset")
        // Acceptance 1: injected transport records EVERY attempt, with 10 minutes of typing.
        let capture = Capture(); let claude = ClaudeProvider(transport: CaptureHTTP(capture: capture), key: { "test-placeholder-not-a-credential" })
        let session = CompanionSession(budget: BudgetMeter(day: BudgetMeter.dayKey()))
        await session.configure(mode: .off, notes: 0)
        for tick in 0...600 {
            await session.activity(at: Double(tick))
            _ = try await session.send(provider: claude, request: request, at: Double(tick), readingAlong: true)
            do { _ = try await session.send(provider: claude, request: request, at: Double(tick)); fatalError("Off ask accepted") } catch CompanionError.off {}
        }
        let offCount = await capture.count(); check(offCount == 0, "Off: zero injected transport attempts in 600 seconds")
        await session.configure(mode: .whenAsked, notes: 0)
        let asked = try await session.send(provider: claude, request: request, at: 601)
        check(asked?.usage.total == 14, "Claude reported all token categories")
        let onCount = await capture.count(); check(onCount == 1, "transport capture actually records enabled attempts")
        check(CodexProvider.newestSol([ProviderModel(id: "gpt-6-sol", name: ""), ProviderModel(id: "gpt-6.1-sol", name: ""), ProviderModel(id: "gpt-5.6-sol", name: "")]) == "gpt-6.1-sol", "newest discovered Sol")
        let script = tmp.appendingPathComponent("pi-rpc.py")
        try FileManager.default.copyItem(at: Bundle.module.url(forResource: "pi-rpc", withExtension: "py", subdirectory: "Fixtures")!, to: script)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: script.path)
        let recordedRPC = CodexProvider(executable: script.path)
        let discovered = try await recordedRPC.models()
        check(discovered.count == 1 && discovered[0].id == "gpt-6.1-sol", "RPC provider model filtering")
        let replay = try await recordedRPC.send(request)
        check(replay.tools == [.stayQuiet] && replay.usage.total == 34, "recorded RPC lifecycle and usage")
        let wire = try recordedRPC.wirePreview(request)
        check(wire.contains("additionalProperties") && wire.contains("set_auto_retry"), "strict schema and retry-free preview")
        let cli = Data("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"{\\\"calls\\\":[{\\\"name\\\":\\\"stay_quiet\\\",\\\"arguments\\\":{}}]}\"}}\n{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":30,\"cached_input_tokens\":10,\"output_tokens\":4}}\n".utf8)
        let cliResult = try CodexCLIProvider.decode(cli, web: false)
        check(cliResult.tools == [.stayQuiet] && cliResult.usage.total == 34 && cliResult.usage.cacheRead == 10, "recorded Codex CLI fallback and non-double-counted cached usage")
        // The host can poll eligibility without constructing prompts, images or consuming a pass.
        let scripted = CompanionSession(budget:BudgetMeter(day:BudgetMeter.dayKey(),tokenLimit:300))
        let scriptedProvider = RecordedProvider(responses:Array(repeating:fixture,count:4))
        await scripted.configure(mode:.thinksWithYou,notes:0)
        await scripted.activity(at:0)
        check(!(await scripted.ready(at:2.99)), "readiness idle minimum")
        check(await scripted.ready(at:3), "readiness does not consume pass")
        check(await scripted.ready(at:3), "repeated readiness is pure")
        _ = try await scripted.send(provider:scriptedProvider,request:request,at:3,readingAlong:true)
        check(!(await scripted.ready(at:24)), "unchanged document never rereads")
        await scripted.activity(at:10)
        check(!(await scripted.ready(at:22.99)), "20-second gate after prior pass")
        await scripted.idleActivity(at:22)
        check(!(await scripted.ready(at:24)), "ink activity postpones idle")
        _ = try await scripted.send(provider:scriptedProvider,request:request,at:25,readingAlong:true)
        await scripted.activity(at:26)
        check(!(await scripted.ready(at:50)), "reported tokens stop reading exactly at limit")
        do { _ = try await scripted.send(provider:scriptedProvider,request:request,at:50); fatalError("unconfirmed budget accepted") } catch CompanionError.budget {}
        _ = try await scripted.send(provider:scriptedProvider,request:request,at:50,confirmedBudget:true)
        await scripted.setLimits(tokens:10000,requests:100)
        await scripted.reset(); await scripted.configure(mode:.thinksWithYou,notes:0)
        check(!(await scripted.ready(at:100)), "switching notes does not reuse old dirty state")
        let delayed = PausedProvider(fixture)
        let flight = CompanionSession(budget:BudgetMeter(day:BudgetMeter.dayKey()))
        await flight.configure(mode:.whenAsked,notes:0)
        let work = Task { try await flight.send(provider:delayed,request:request,at:0) }
        while !(await delayed.entered) { await Task.yield() }
        check(await flight.isInFlight(), "flight indicator tracks actual transport")
        await flight.configure(mode:.off,notes:0); await delayed.release()
        let late = try await work.value
        check(late == nil, "turning Off drops in-flight response")
        check((await flight.meter()).tokens == 150, "discarded in-flight response still meters reported cost")
        check(!(await flight.isInFlight()), "flight completes after discard")
        check(SketchTrial.prompts.count == 12, "fixed sketch set")
        try await runV2Checks(tmp: tmp)
        try await runSpeedChecks(tmp: tmp)
        print("PASS companion: recorded tools, hostile output, clamping, anchors, readers, renderer, triggers, budgets, Claude capture; Off 600s: 0 transport attempts; On: 1")
    }
}
