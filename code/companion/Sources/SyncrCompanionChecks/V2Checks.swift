import Foundation
import SyncrCompanion

struct StreamHTTPFixture: HTTPTransport {
    let chunks: [Data]; let capture: Capture
    func send(_ r: URLRequest) async throws -> (Data, Int) { await capture.record(r); return (chunks.reduce(Data(), +), 200) }
    func stream(_ r: URLRequest) -> AsyncThrowingStream<HTTPStreamChunk, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                await capture.record(r); continuation.yield(.status(200))
                for chunk in chunks { try? await Task.sleep(nanoseconds: 1_000_000); if Task.isCancelled { break }; continuation.yield(.data(chunk)) }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
func runV2Checks(tmp: URL) async throws {
    let input = RequestInput(markdown: "cache", changed: ByteSpan(start: 0, end: 5), instruction: "Explain cache")
    let margin = "{\"type\":\"margin\",\"quote\":\"cache\",\"kind\":\"question\",\"text\":\"When is it invalidated?\"}\n"
    let oldInput=try JSONDecoder().decode(RequestInput.self,from:Data("{\"markdown\":\"cache\"}".utf8))
    check(oldInput.images.isEmpty && oldInput.pagePNG == nil,"old request JSON remains decodable")
    var parser = ItemStreamParser(input: input); var parsed: [ParsedStreamEvent] = []
    for char in margin { try parser.append(String(char)) { parsed.append($0) } }; try parser.finish()
    check(parsed.filter { if case .item = $0 { return true }; return false }.count == 1, "incremental item one character at a time")
    check(parsed.contains { if case .textPreview(let p) = $0 { return p?.text == "When" }; return false }, "margin text previews before complete JSON")
    check(parsed.contains { if case .textPreview(nil) = $0 { return true }; return false }, "complete item clears preview")
    var eofParser = ItemStreamParser(input: input); var eofItems: [ParsedStreamEvent] = []
    try eofParser.append(String(margin.dropLast())) { eofItems.append($0) }
    try eofParser.finish { eofItems.append($0) }
    check(eofItems.contains { if case .item(.margin) = $0 { return true }; return false }, "successful provider EOF delimits final complete header")
    let longMargin = "{\"type\":\"margin\",\"quote\":\"cache\",\"kind\":\"explanation\",\"text\":\"" + Array(repeating: "word", count: 100).joined(separator: " ") + "\"}\n"
    var askParser = ItemStreamParser(input: input, marginWords: 150); var askItems: [ParsedStreamEvent] = []
    try askParser.append(longMargin) { askItems.append($0) }
    check(askItems.contains { if case .item(.margin) = $0 { return true }; return false }, "direct ask accepts a 100-word margin")
    do { var strict = ItemStreamParser(input: input); try strict.append(longMargin) { _ in }; fatalError("read-along accepted a 100-word margin") } catch {}
    // Asking for a longer reply lifts the length limit only; the thread must still exist.
    var longAsk = input; longAsk.instruction = "Reply with more than 150 words"
    do { var p = ItemStreamParser(input: longAsk); try p.append("{\"type\":\"reply\",\"threadID\":\"invented\",\"text\":\"No\"}\n") { _ in }; fatalError("reply to an unknown thread accepted") } catch {}
    var items: [ParsedStreamEvent] = []; parser = ItemStreamParser(input: input)
    do { try parser.append(margin + "{\"type\":\"evil\"}\n") { items.append($0) }; fatalError("malformed stream accepted") } catch {}
    check(items.count == 1, "complete valid item preserved in malformed chunk")
    for text in ["{\"type\":\"quiet\",\"type\":\"quiet\"}\n", "{\"type\":\"quiet\"}\n" + margin, margin.replacingOccurrences(of: "cache", with: "invented"), margin.replacingOccurrences(of: "question", with: "evil")] {
        var parser = ItemStreamParser(input: input)
        do { try parser.append(text) { _ in }; try parser.finish(); fatalError("invalid v2 stream accepted") } catch {}
    }
    let inspector = ReferencePictureInspector()
    check(PatternBook.all.count >= 12 && PatternBook.closest(to: "queens board undo").first?.id == "board", "pattern book and deterministic scorer")
    for pattern in PatternBook.all {
        let clean = try inspector.filter(svg: pattern.svg); let list = try inspector.displayList(svg: clean)
        let findings = PictureCheck.inspect(list)
        check(findings.isEmpty, "pattern \(pattern.id): \(findings)")
        check(try inspector.displayList(svg: clean) == list, "deterministic fixture display list")
    }
    let label = PictureDrawItem(id: "label", text: "Test", x: 20, y: 30)
    let line = PictureDrawItem(id: "line", points: [PicturePoint(10,60),PicturePoint(200,60)], stroke: "ink")
    func list(_ items: [PictureDrawItem], caption: String = "A test.") -> PictureDisplayList { PictureDisplayList(height: 100, steps: [PictureStep(number: 1, caption: caption, items: items)]) }
    let seeds: [(String, PictureDisplayList)] = [
        ("overlap", list([label, PictureDrawItem(id: "other", text: "Crowded", x: 22, y: 30)])),
        ("bounds", list([label, PictureDrawItem(id: "outside", points: [PicturePoint(-10,70),PicturePoint(100,70)])])),
        ("empty", list([])),
        ("legibility", list([PictureDrawItem(id: "tiny", text: "Tiny", x: 20, y: 30, fontSize: 9),line])),
        ("truth", list([label,line], caption: "The shaded mark.")),
        ("crowding", list((0..<17).map { PictureDrawItem(id: "dot\($0)", points: [PicturePoint(Double(10+$0),70)], fill: "ink") }))]
    for (rule, seed) in seeds { check(PictureCheck.inspect(seed).contains { $0.rule == rule }, "seed caught: \(rule)") }
    check(PictureCheck.inspect(list([label,line], caption: "Find ‘Missing’. ")).contains { $0.rule == "truth" }, "named label truth")
    check(PictureCheck.inspect(list([label,PictureDrawItem(id: "through", points: [PicturePoint(0,25),PicturePoint(100,25)],stroke:"ink")])).contains { $0.rule == "overlap" }, "line-label overlap")
    check(!PictureCheck.acceptable([PictureFinding(rule:"bounds",step:1,items:[],detail:"outside")]), "major findings rejected")
    try PatternBook.addPinned(svg: PatternBook.all[0].svg, description: "Pinned board", tags: ["pinned"], at: tmp, inspector: inspector)
    try PatternBook.addPinned(svg: PatternBook.all[0].svg, description: "Pinned board", tags: ["pinned"], at: tmp, inspector: inspector)
    check(try PatternBook.loadPinned(at: tmp).count == 1, "checked pinned patterns deduplicate")
    let arrowhead=PictureDrawItem(id:"head",points:[PicturePoint(20,70),PicturePoint(25,75),PicturePoint(20,80)],stroke:"ink")
    check(PictureCheck.inspect(list([label,arrowhead],caption:"The arrow points right.")).isEmpty,"arrow truth after core marker flattening")
    let style = try StreamRequest(model: "test", input: input)
    check(style.prefix.contains("<svg") && style.context.contains("Unchanged") && style.changed.contains("cache"), "cache order")
    let modelDefaults = try ModelSettings.defaults(from: [ProviderModel(id:"gpt-6.1-terra",name:""),ProviderModel(id:"gpt-6.1-sol",name:"")])
    check(modelDefaults.gate == "gpt-6.1-terra" && modelDefaults.answer == "gpt-6.1-sol", "defaults only from catalog")

    // Recorded provider SSE, byte boundaries split arbitrarily, including UTF-8 and CRLF.
    func sse(_ value: [String: Any]) throws -> String { "data: " + String(decoding: try JSONSerialization.data(withJSONObject:value),as:UTF8.self) + "\r\n\r\n" }
    let claudeSSE = try sse(["type":"message_start","message":["usage":["input_tokens":10,"cache_read_input_tokens":3,"cache_creation_input_tokens":2]]]) + sse(["type":"content_block_delta","delta":["type":"text_delta","text":margin]]) + sse(["type":"message_delta","delta":["stop_reason":"end_turn"],"usage":["output_tokens":5]]) + sse(["type":"message_stop"])
    let openSSE = try sse(["type":"response.output_text.delta","delta":margin]) + sse(["type":"response.completed","response":["status":"completed","usage":["input_tokens":13,"output_tokens":5,"input_tokens_details":["cached_tokens":3]]]])
    for (route, text) in [(SSEProviderRoute.claude,claudeSSE),(.openAI,openSSE)] {
        let capture = Capture(); let http = StreamHTTPFixture(chunks: Data(text.utf8).map { Data([$0]) },capture:capture)
        let provider: CompanionProvider = route == .claude ? ClaudeProvider(transport:http,key:{"fixture-not-a-key"}) : OpenAIProvider(transport:http,key:{"fixture-not-a-key"})
        var output = ""; var usage = Usage()
        for try await e in provider.stream(style) { switch e { case .text(let t): output += t; case .usage(let u): usage = u } }
        check(output == margin && usage.cacheRead == 3 && usage.output == 5, "\(route) streaming text/usage")
        check(await capture.count() == 1, "one HTTP stream request")
        let broken = StreamHTTPFixture(chunks:[Data(text.prefix(text.count/2).utf8)],capture:capture)
        do { for try await _ in ProviderSSE.stream(transport:broken,request:URLRequest(url:URL(string:"http://127.0.0.1")!),route:route) {}; fatalError("truncated SSE accepted") } catch {}
    }
    var imageInput = input; imageInput.images = [NoteImage(id:"fixture",data:Data([1,2,3]))]
    let imageRequest = try StreamRequest(model:"test",input:imageInput)
    let imagePreview = try ClaudeProvider(key:{nil}).streamPreview(imageRequest)
    check(imagePreview.contains("sha256=") && !imagePreview.contains("AQID"), "image preview hash without bytes")
    imageInput.pagePNG=Data([1,2,3]); let legacyImage=try CompanionRequest(model:"test",input:imageInput)
    check(!(try ClaudeProvider(key:{nil}).wirePreview(legacyImage)).contains("AQID"),"legacy Claude preview omits image bytes")
    check(!(try CodexProvider().wirePreview(legacyImage)).contains("AQID"),"legacy pi preview omits image bytes")
    check(!(try CodexCLIProvider().wirePreview(legacyImage)).contains("AQID"),"legacy native Codex preview omits image bytes")

    func collect(_ engine: CompanionEngine, _ event: EngineEvent, at: Double = 3) async throws -> [EngineOutput] {
        var result: [EngineOutput] = []
        for await value in try await engine.events(for:event,at:at) { result.append(value) }; return result
    }
    let recorded = RecordedStreamProvider(passes:[RecordedStreamPass(frames:[RecordedStreamFrame(.text(String(margin.prefix(15)))),RecordedStreamFrame(.text(String(margin.dropFirst(15))),afterNanoseconds:5_000_000),RecordedStreamFrame(.text("{\"type\":")),],fails:true),RecordedStreamPass(text:margin)])
    let engine = CompanionEngine(provider:recorded,models:ModelSettings(gate:"small",answer:"large"),stateFolder:tmp)
    let died = try await collect(engine,.ask(input))
    check(died.contains { if case .item(.margin) = $0 { return true }; return false } && died.contains { if case .failed = $0 { return true }; return false }, "provider death preserves complete margin only")
    let restarted = try await collect(engine,.ask(input),at:30)
    check(restarted.contains { if case .finished = $0 { return true }; return false }, "next pass after provider death")
    let stagedProvider=RecordedStreamProvider(passes:[RecordedStreamPass(frames:[RecordedStreamFrame(.text(margin)),RecordedStreamFrame(.text("{\"type\":")),RecordedStreamFrame(.text("unfinished"),afterNanoseconds:300_000_000)])])
    let stagedEngine=CompanionEngine(provider:stagedProvider,models:ModelSettings(gate:"s",answer:"a"))
    let stagedEvents=try await stagedEngine.events(for:.ask(input),at:0)
    let stagingHost=Task { var staged:[StreamItem]=[]; var stored:[StreamItem]=[]
        for await value in stagedEvents { switch value { case .item(let item):staged.append(item);await stagedEngine.activity(at:1);case .cancelled:staged=[];case .finished:stored+=staged;default:break } }; return stored
    }
    check(await stagingHost.value == [],"host staging discards even complete pass items on typing cancellation")
    let slow = RecordedStreamProvider(passes:[RecordedStreamPass(frames:[RecordedStreamFrame(.text("{\"type\":\"margin\"")),RecordedStreamFrame(.text(margin),afterNanoseconds:300_000_000)])])
    let slowEngine = CompanionEngine(provider:slow,models:ModelSettings(gate:"s",answer:"a"))
    let stream = try await slowEngine.events(for:.ask(input),at:0)
    let reader = Task { var result:[EngineOutput]=[]; for await value in stream { result.append(value) }; return result }
    try await Task.sleep(nanoseconds:20_000_000)
    do { _ = try await slowEngine.events(for:.ask(input),at:1); fatalError("overlapping engine pass") } catch {}
    await slowEngine.activity(at:1)
    let cancelled = await reader.value
    check(cancelled.contains { if case .cancelled = $0 { return true }; return false } && !cancelled.contains { if case .item = $0 { return true }; return false }, "cancel mid-item applies nothing")

    // A scripted hour: changed each ten seconds, pause eligibility still at least twenty seconds apart.
    var scripts: [RecordedStreamPass] = []
    for i in 0..<180 { scripts.append(RecordedStreamPass(text:"{\"decision\":\"\(i % 3 == 0 ? "speak":"quiet")\",\"reason\":\"recorded\",\"kind\":\"question\"}\n")); if i % 3 == 0 { scripts.append(RecordedStreamPass(text:margin)) } }
    let hourProvider = RecordedStreamProvider(passes:scripts)
    let hour = CompanionEngine(provider:hourProvider,models:ModelSettings(gate:"small",answer:"strong"),stateFolder:tmp)
    await hour.configure(mode:.thinksWithYou,unpinnedNotes:0)
    var times:[Int]=[]; var answers=0
    for t in 0..<3600 {
        if t % 10 == 0 { await hour.activity(at:Double(t)) }
        let values = try await collect(hour,.pause(input),at:Double(t))
        if values.contains(where:{ if case .gate = $0 { return true }; return false }) { times.append(t) }
        answers += values.filter { if case .item(.margin) = $0 { return true }; return false }.count
    }
    check(times.count == 180 && zip(times,times.dropFirst()).allSatisfy { $1-$0 >= 20 } && times.allSatisfy { $0 % 10 >= 3 }, "hour gate timing")
    check(answers == 60 && hourProvider.attempts == 240 && hourProvider.requestedModels.filter { $0 == "strong" }.count == 60, "hour answers only on speak")
    await hour.configure(mode:.off,unpinnedNotes:0)
    _ = try await collect(hour,.pause(input),at:3700)
    do { _ = try await collect(hour,.ask(input),at:3700); fatalError("Off accepted") } catch CompanionError.off {}
    check(hourProvider.attempts == 240,"Off zero further attempts")
    let cappedProvider=RecordedStreamProvider(passes:[RecordedStreamPass(text:"{\"decision\":\"speak\",\"reason\":\"test\",\"kind\":\"question\"}\n")])
    let cappedEngine=CompanionEngine(provider:cappedProvider,models:ModelSettings(gate:"s",answer:"a"),budget:BudgetMeter(day:BudgetMeter.dayKey(),tokenLimit:1))
    await cappedEngine.configure(mode:.thinksWithYou,unpinnedNotes:3); await cappedEngine.activity(at:0)
    _ = try await collect(cappedEngine,.pause(input),at:3); check(cappedProvider.attempts == 0,"engine screen cap prevents even gate construction")
    await cappedEngine.configure(mode:.thinksWithYou,unpinnedNotes:0)
    _ = try await collect(cappedEngine,.pause(input),at:3); check(cappedProvider.attempts == 1,"gate budget cannot silently launch answer")
    let invalidGate=RecordedStreamProvider(passes:[RecordedStreamPass(text:"{\"decision\":\"maybe\",\"reason\":\"test\",\"kind\":\"question\"}\n")])
    let invalidEngine=CompanionEngine(provider:invalidGate,models:ModelSettings(gate:"s",answer:"a"))
    await invalidEngine.configure(mode:.thinksWithYou,unpinnedNotes:0); await invalidEngine.activity(at:0)
    _ = try await collect(invalidEngine,.pause(input),at:3); check(invalidGate.attempts == 1,"invalid gate never launches strong answer")
    let feedback = LocalDecisionLog(stateFolder:tmp)
    for action in [FeedbackAction.dismiss,.pin,.addToNote] { try await feedback.recordFeedback(action:action,passage:"cache",itemID:"fixture") }
    let log = try String(contentsOf:tmp.appendingPathComponent("companion-decisions.jsonl"))
    check(log.split(separator:"\n").count >= 183 && log.contains("addToNote"),"local decisions and feedback JSONL")

    let pattern = PatternBook.all.first { $0.id == "table" }!
    let formatted = "{\"type\":\"picture\",\"quote\":\"cache\",\"title\":\"Table\"}\n" + pattern.svg.replacingOccurrences(of:"</g>",with:"</g>\n") + "\n" + margin
    parser = ItemStreamParser(input:input); parsed=[]
    try parser.append(formatted) { parsed.append($0) }; try parser.finish()
    check(parsed.filter { if case .pictureStep = $0 { return true }; return false }.count == 2,"step groups stream on close")
    let brokenSVG = pattern.svg.replacingOccurrences(of:"font-size=\"12\"",with:"font-size=\"9\"")
    let brokenFormatted = formatted.replacingOccurrences(of:pattern.svg.replacingOccurrences(of:"</g>",with:"</g>\n"),with:brokenSVG)
    let repairProvider = RecordedStreamProvider(passes:[RecordedStreamPass(text:brokenFormatted),RecordedStreamPass(text:formatted)])
    let repairing = CompanionEngine(provider:repairProvider,models:ModelSettings(gate:"s",answer:"a"),inspector:inspector,stateFolder:tmp)
    let repaired = try await collect(repairing,.ask(input))
    check(repairProvider.attempts == 2 && repaired.contains { if case .item(.picture) = $0 { return true }; return false },"one repair yields checked picture")
    let badRepairProvider = RecordedStreamProvider(passes:[RecordedStreamPass(text:brokenFormatted),RecordedStreamPass(text:brokenFormatted)])
    let failedPicture = try await collect(CompanionEngine(provider:badRepairProvider,models:ModelSettings(gate:"s",answer:"a"),inspector:inspector),.ask(input))
    check(badRepairProvider.attempts == 2 && !failedPicture.contains { if case .item(.picture) = $0 { return true }; return false },"one failed repair falls back, no third call")

    // Failed pictures may not add a second read-along margin, even when prose preceded the SVG.
    for hasInspector in [false, true] {
        for hasLead in [false, true] {
            let gate = RecordedStreamPass(text:"{\"decision\":\"speak\",\"reason\":\"picture helps\",\"kind\":\"picture\"}\n")
            var passes = [gate, RecordedStreamPass(text:(hasLead ? margin : "") + brokenFormatted)]
            if hasInspector { passes.append(RecordedStreamPass(text:brokenFormatted)) }
            let provider = RecordedStreamProvider(passes:passes)
            let engine = CompanionEngine(provider:provider,models:ModelSettings(gate:"s",answer:"a"),inspector:hasInspector ? inspector : nil)
            await engine.configure(mode:.thinksWithYou,unpinnedNotes:0); await engine.activity(at:0)
            let output = try await collect(engine,.pause(input))
            check(output.filter { if case .item(.margin) = $0 { return true }; return false }.count == 1,"failed picture respects one read-along margin")
            check(provider.attempts == (hasInspector ? 3 : 2),"picture fallback has at most one repair")
            if hasLead {
                check(output.contains { if case .pictureReplace(_,nil,let findings) = $0 { return findings.contains { $0.rule == "fallback" && $0.detail.contains("did not come out") } }; return false },"fallback warning accompanies existing prose without adding a note")
            }
        }
    }

    let partial=String(formatted.prefix(upTo:formatted.range(of:"<g data-step=\"2\"")!.lowerBound))
    let stepProvider=RecordedStreamProvider(passes:[RecordedStreamPass(frames:[RecordedStreamFrame(.text(partial)),RecordedStreamFrame(.text("unfinished"),afterNanoseconds:500_000_000)])])
    let stepEngine=CompanionEngine(provider:stepProvider,models:ModelSettings(gate:"s",answer:"a"),inspector:inspector)
    let stepStream=try await stepEngine.events(for:.ask(input),at:0)
    let stepReader=Task { var result:[EngineOutput]=[]; for await v in stepStream { result.append(v) }; return result }
    try await Task.sleep(nanoseconds:30_000_000); await stepEngine.cancel()
    let cancelledSteps=await stepReader.value
    check(cancelledSteps.contains { if case .pictureStep = $0 { return true }; return false } && cancelledSteps.contains { if case .pictureReplace(_,nil,_) = $0 { return true }; return false },"cancel removes complete provisional steps, stores no partial picture")

    // Notebook round 2, part A: long-form answers on an ask thread, and named steps.
    func replyLine(_ id: String, _ text: String) throws -> String {
        // Header order as the prompt gives it (type, threadID, text); previews depend on text coming last.
        func j(_ v: String) throws -> String { String(decoding: try JSONSerialization.data(withJSONObject: v, options: [.fragmentsAllowed]), as: UTF8.self) }
        return "{\"type\":\"reply\",\"threadID\":\(try j(id)),\"text\":\(try j(text))}\n"
    }
    func stepNames(_ out: [EngineOutput]) -> [String] { out.compactMap { if case .step(let s) = $0 { return s.rawValue }; return nil } }
    let askID = "ask-thread-1"
    let askThread = Conversation(id: askID, quote: "cache", kind: "question", text: "Run me through caching", reader: "r", state: "open", messages: [ConversationMessage(who: "You", text: "Run me through caching")])
    var askInput = input; askInput.threads = [askThread]; askInput.askThreadID = askID
    check(try JSONDecoder().decode(RequestInput.self, from: JSONEncoder().encode(askInput)).askThreadID == askID, "askThreadID round-trips")
    let answer = "Two sum asks for two indices whose values add to the target.\n\n1. Walk the array once.\n2. Keep a dictionary from value to index.\n3. If target minus the current value is in it, return both indices.\n\n```python\ndef two_sum(nums, target):\n    seen = {}\n    for i, n in enumerate(nums):\n        if target - n in seen:\n            return [seen[target - n], i]\n        seen[n] = i\n```\n\nUse `seen` for O(1) lookups; the whole pass is O(n)."
    let answerLine = try replyLine(askID, answer)
    let answerProvider = RecordedStreamProvider(passes: [RecordedStreamPass(frames: stride(from: 0, to: answerLine.count, by: 40).map { RecordedStreamFrame(.text(String(answerLine.dropFirst($0).prefix(40)))) } + [RecordedStreamFrame(.usage(Usage(input: 10, output: 5)))])])
    let answerEngine = CompanionEngine(provider: answerProvider, models: ModelSettings(gate: "s", answer: "a"))
    let answerOut = try await collect(answerEngine, .ask(askInput))
    let replies: [String] = answerOut.compactMap { if case .item(.reply(let id, let t)) = $0, id == askID { return t }; return nil }
    check(replies == [answer] && replies[0].contains("```python") && replies[0].contains("1. Walk"), "ask thread answer with list and code block arrives intact")
    check(!answerOut.contains { if case .item(.margin) = $0 { return true }; return false } && answerOut.contains { if case .finished = $0 { return true }; return false }, "ask thread answer is reply items, no margin")
    let growing: [String] = answerOut.compactMap { if case .textPreview(let p?) = $0, p.threadID == askID, p.kind == "reply" { return p.text }; return nil }
    check(growing.count > 3 && growing.first!.count < growing.last!.count && zip(growing, growing.dropFirst()).allSatisfy { $1.hasPrefix($0) } && answer.hasPrefix(growing.last!), "reply text previews grow while streaming")
    check(stepNames(answerOut) == ["thinking", "writing"], "steps for a text-only ask: \(stepNames(answerOut))")
    let sent = await answerEngine.lastRequest ?? ""
    check(sent.contains("Asked directly in thread \(askID)") && sent.contains("at most about 400 words"), "ask thread instruction in the request")
    // Hard bound: 600 and 700 words are kept, 701 is not, and what came before survives.
    for (n, kept) in [(600, true), (700, true), (701, false)] {
        let provider = RecordedStreamProvider(passes: [RecordedStreamPass(text: try replyLine(askID, "First part.") + (try replyLine(askID, Array(repeating: "word", count: n).joined(separator: " "))))])
        let out = try await collect(CompanionEngine(provider: provider, models: ModelSettings(gate: "s", answer: "a")), .ask(askInput))
        let got = out.compactMap { if case .item(.reply(_, let t)) = $0 { return t }; return nil }
        check(kept ? got.count == 2 : got == ["First part."] && out.contains { if case .failed = $0 { return true }; return false }, "ask reply bound at \(n) words")
    }
    var tooBig = ItemStreamParser(input: askInput)
    do { try tooBig.append(try replyLine(askID, String(repeating: "a", count: ItemStreamParser.askReplyByteLimit + 1))) { _ in }; fatalError("oversized reply accepted") } catch {}
    var otherThread = ItemStreamParser(input: askInput)
    do { try otherThread.append(try replyLine("elsewhere", "No.")) { _ in }; fatalError("reply to unknown thread accepted") } catch {}
    var unthreaded = ItemStreamParser(input: input)
    do { try unthreaded.append(try replyLine(askID, "No.")) { _ in }; fatalError("reply on a thread the request lacks accepted") } catch {}
    // An ask without a thread keeps today's behaviour.
    let plainEngine = CompanionEngine(provider: RecordedStreamProvider(passes: [RecordedStreamPass(text: longMargin)]), models: ModelSettings(gate: "s", answer: "a"))
    let plainOut = try await collect(plainEngine, .ask(input))
    let plainSent = await plainEngine.lastRequest ?? ""
    check(plainOut.contains { if case .item(.margin) = $0 { return true }; return false } && plainSent.contains("Asked directly: answer in margin items"), "ask without a thread unchanged")
    // Steps for an ask with a picture and one repair, and for a read-along pass.
    let stepProvider2 = RecordedStreamProvider(passes: [RecordedStreamPass(text: try replyLine(askID, "Here is the board.") + brokenFormatted), RecordedStreamPass(text: formatted)])
    let stepOut = try await collect(CompanionEngine(provider: stepProvider2, models: ModelSettings(gate: "s", answer: "a"), inspector: inspector), .ask(askInput))
    check(stepNames(stepOut) == ["thinking", "writing", "sketching", "writing", "checking", "fixing", "checking"], "steps in order for an ask with a picture and a repair: \(stepNames(stepOut))")
    check(stepOut.contains { if case .item(.picture) = $0 { return true }; return false } && stepOut.contains { if case .item(.reply) = $0 { return true }; return false }, "ask thread answer plus repaired picture")
    let readProvider = RecordedStreamProvider(passes: [RecordedStreamPass(text: "{\"decision\":\"speak\",\"reason\":\"r\",\"kind\":\"question\"}\n"), RecordedStreamPass(text: margin)])
    let readEngine = CompanionEngine(provider: readProvider, models: ModelSettings(gate: "s", answer: "a"))
    await readEngine.configure(mode: .thinksWithYou, unpinnedNotes: 0); await readEngine.activity(at: 0)
    let readOut = try await collect(readEngine, .pause(askInput), at: 3)
    check(stepNames(readOut) == ["deciding", "thinking", "writing"] && readOut.contains { if case .item(.margin) = $0 { return true }; return false }, "read-along steps: \(stepNames(readOut))")
    let readSent = await readEngine.lastRequest ?? ""
    check(!readSent.contains("Asked directly") && !readSent.contains("Asked directly in thread"), "read-along ignores askThreadID")
    var readLong = ItemStreamParser(input: askInput)
    do { try readLong.append(longMargin) { _ in }; fatalError("margin over 60 words accepted next to an ask thread") } catch {}

    // Live confirmation, only when asked for (SYNCR_LIVE_ASK=<ledger path>): one real ask on the two-sum note.
    if let ledger = ProcessInfo.processInfo.environment["SYNCR_LIVE_ASK"] {
        let note = "Run me through the most basic leetcode problem, twosum"
        let provider = CodexProvider(); let models = try await provider.models()
        guard let model = CodexProvider.newestSol(models) else { fatalError("no Sol model") }
        let entry = try JSONSerialization.data(withJSONObject: ["agent": "engine-3", "model": model, "purpose": "two-sum ask on thread", "time": ISO8601DateFormatter().string(from: Date())], options: [.sortedKeys]) + Data([10])
        if let h = FileHandle(forWritingAtPath: ledger) { try h.seekToEnd(); try h.write(contentsOf: entry); try h.close() }
        let thread = Conversation(id: "live-ask", quote: note, kind: "question", text: note, reader: "r", state: "open", messages: [ConversationMessage(who: "You", text: note)])
        let liveInput = RequestInput(markdown: note, selection: ByteSpan(start: 0, end: note.utf8.count), threads: [thread], instruction: note, askThreadID: "live-ask")
        let engine = CompanionEngine(provider: provider, models: ModelSettings(gate: model, answer: model), inspector: ReferencePictureInspector())
        let out = try await collect(engine, .ask(liveInput), at: 0)
        let text = out.compactMap { if case .item(.reply(_, let t)) = $0 { return t }; return nil }
        print("LIVE steps:", stepNames(out), "replies:", text.count, "words:", text.map { ItemStreamParser.words($0) }, "codeBlock:", text.contains { $0.contains("```") }, "failed:", out.contains { if case .failed(let m) = $0 { print(m); return true }; return false })
        print("LIVE text:\n" + text.joined(separator: "\n---\n"))
    }
    let setFolder=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Judgment")
    let cases=try JudgmentCase.load(folder:setFolder); check(cases.count == 24,"authored judgment set")
    var judgePasses:[RecordedStreamPass]=[]; var seededFalseAlarm=false
    for c in cases {
        let falseAlarm=c.expected == "quiet" && !seededFalseAlarm
        if falseAlarm { seededFalseAlarm=true }
        let speak=c.expected != "quiet" || falseAlarm
        let kind=["ask":"question","explain":"explanation","draw":"picture"][c.expected] ?? "question"
        let gate=["decision":speak ? "speak":"quiet","reason":"scripted scoring fixture","kind":kind]
        judgePasses.append(RecordedStreamPass(text:String(decoding:try JSONSerialization.data(withJSONObject:gate),as:UTF8.self)))
        if speak {
            let obj:[String:Any]=["type":"margin","quote":c.passage,"kind":kind == "picture" ? "explanation":kind,"text":"The table shows the cases. Read each row in order."]
            let margin=String(decoding:try JSONSerialization.data(withJSONObject:obj),as:UTF8.self)+"\n"
            let header:[String:Any]=["type":"picture","quote":c.passage,"title":"Cases"]
            let answer=kind == "picture" ? String(decoding:try JSONSerialization.data(withJSONObject:header),as:UTF8.self)+"\n"+pattern.svg+"\n"+margin : margin
            judgePasses.append(RecordedStreamPass(text:answer))
        }
    }
    let scored=await JudgmentRunner(provider:RecordedStreamProvider(passes:judgePasses),models:ModelSettings(gate:"s",answer:"a"),inspector:inspector).run(cases:cases,route:"scorer-test")
    check(scored.quietWhenExpected.correct == 11 && scored.quietWhenExpected.total == 12 && scored.spokeWhenExpected.correct == 12 && scored.rightKind.correct == 12 && scored.rightPassage.correct == 12 && scored.picturePassRate.correct == 4,"judgment measures including a seeded false alarm and picture checks")
    try scored.write(to:tmp.appendingPathComponent("score.json"))

    let rpcData=try String(contentsOf:Bundle.module.url(forResource:"codex-smoke-rpc",withExtension:"jsonl",subdirectory:"Fixtures")!)
    let smokeInput=RequestInput(markdown:"A cache is always fresh even after its source changes.")
    var realParser=ItemStreamParser(input:smokeInput); var realItems:[StreamItem]=[]
    for line in rpcData.split(separator:"\n") {
        let row=try JSONSerialization.jsonObject(with:Data(line.utf8)) as! [String:Any]
        if let e=row["assistantMessageEvent"] as? [String:Any], e["type"] as? String == "text_delta", let text=e["delta"] as? String {
            try await Task.sleep(nanoseconds:100_000)
            try realParser.append(text) { if case .item(let item) = $0 { realItems.append(item) } }
        }
    }
    try realParser.finish { if case .item(let item) = $0 { realItems.append(item) } }
    check(realItems.count == 1,"recorded real pi exchange replays with terminal EOF")
    let webScript=tmp.appendingPathComponent("codex-stream.py")
    try FileManager.default.copyItem(at:Bundle.module.url(forResource:"codex-stream",withExtension:"py",subdirectory:"Fixtures")!,to:webScript)
    try FileManager.default.setAttributes([.posixPermissions:0o700],ofItemAtPath:webScript.path)
    var webInput=input; webInput.web=true
    var webText=""; var webUsage=Usage()
    let webProvider=CodexCLIProvider(executable:webScript.path)
    for try await event in webProvider.stream(try StreamRequest(model:"fixture",input:webInput)) { switch event { case .text(let t):webText += t;case .usage(let u):webUsage=u } }
    check(webText.contains("source-backed") && webUsage.cacheRead == 3 && webUsage.input == 17,"native Codex Web fallback record streaming and exact cache usage")
    let webEngine=CompanionEngine(provider:webProvider,models:ModelSettings(gate:"s",answer:"a"))
    webInput.instruction="SIMULATE_SLOW"
    let webEvents=try await webEngine.events(for:.ask(webInput),at:0)
    let webReader=Task { for await _ in webEvents {} }
    try await Task.sleep(nanoseconds:100_000_000); await webEngine.cancel(); await webReader.value

    // Persistent pi protocol fixture proves same PID, fresh history, death/restart and abort.
    let script = tmp.appendingPathComponent("pi-stream.py")
    try FileManager.default.copyItem(at:Bundle.module.url(forResource:"pi-stream",withExtension:"py",subdirectory:"Fixtures")!,to:script)
    try FileManager.default.setAttributes([.posixPermissions:0o700],ofItemAtPath:script.path)
    let pi = CodexProvider(executable:script.path)
    func piPass(_ input: RequestInput) async throws -> Usage {
        var usage=Usage(); var text=""
        for try await e in pi.stream(try StreamRequest(model:"fixture",input:input)) { switch e { case .text(let t): text+=t; case .usage(let u):usage=u } }
        check(text == margin,"pi text deltas"); return usage
    }
    let pid1 = try await piPass(input); try await Task.sleep(nanoseconds:5_000_000)
    let pid2 = try await piPass(input)
    check(pid1.input == pid2.input,"pi process kept running; fresh session before every prompt")
    var dieInput=input; dieInput.instruction="SIMULATE_DIE"
    do { _ = try await piPass(dieInput); fatalError("pi death accepted") } catch {}
    try await Task.sleep(nanoseconds:5_000_000)
    let pid3=try await piPass(input); check(pid3.input != pid1.input,"pi restart after exit")
    var slowInput=input; slowInput.instruction="SIMULATE_SLOW"
    let piReader=Task { do { _ = try await piPass(slowInput) } catch {} }
    try await Task.sleep(nanoseconds:100_000_000); await pi.cancelStream(); await piReader.value
    try await Task.sleep(nanoseconds:5_000_000)
    _ = try await piPass(input); await pi.shutdown()
    let stallScript=tmp.appendingPathComponent("pi-stall.py")
    try FileManager.default.copyItem(at:Bundle.module.url(forResource:"pi-stall",withExtension:"py",subdirectory:"Fixtures")!,to:stallScript)
    try FileManager.default.setAttributes([.posixPermissions:0o700],ofItemAtPath:stallScript.path)
    let stalled=CompanionEngine(provider:CodexProvider(executable:stallScript.path),models:ModelSettings(gate:"s",answer:"a"))
    let blocked=try await stalled.events(for:.ask(RequestInput(markdown:String(repeating:"a",count:500000))),at:0)
    let blockedReader=Task { for await _ in blocked {} }
    try await Task.sleep(nanoseconds:150_000_000)
    let cancelStart=Date(); await stalled.cancel(); await blockedReader.value
    check(Date().timeIntervalSince(cancelStart) < 2,"cancellation unblocks a full stdin pipe, not just text generation")
    await stalled.shutdown()
    try await codexIdleChecks(tmp: tmp)
    print("PASS v2: incremental/strict parser, both SSE routes, persistent pi reset/death/restart/abort, cancellation; hour=180 gates/60 answers; 12 patterns, six seeded picture faults, one repair, local feedback")
}
