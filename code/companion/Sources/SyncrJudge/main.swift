import Foundation
import Darwin
import SyncrCompanion

struct RecordedJudgmentReply: Decodable {
    var id: String; var decision: String; var kind: String; var text: String?; var pattern: String?
}
final class LiveLedger: @unchecked Sendable {
    let url: URL; let prefix: String; let lock = NSLock(); var calls = 0; let maximum: Int
    init(url: URL, prefix: String, maximum: Int) { self.url = url; self.prefix = prefix; self.maximum = maximum }
    func remaining() -> Int {
        let lines = ((try? String(contentsOf: url)) ?? "").split(separator: "\n")
        let count = lines.filter { line in
            guard let data = String(line).data(using: .utf8), let row = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
            return row["agent"] as? String == "engine" && (row["purpose"] as? String ?? "").hasPrefix(prefix)
        }.count
        return max(0, maximum - count)
    }
    func record(_ label: String, _ model: String) throws {
        lock.lock(); defer { lock.unlock() }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let existing = (try? String(contentsOf: url)) ?? ""
        let count = existing.split(separator:"\n").filter { line in
            guard let data = String(line).data(using:.utf8), let row = try? JSONSerialization.jsonObject(with:data) as? [String:Any] else { return false }
            return row["agent"] as? String == "engine" && (row["purpose"] as? String ?? "").hasPrefix(prefix)
        }.count
        guard count < maximum else { throw CompanionError.unavailable("Live-call limit reached for \(prefix).") }
        let row = ["time": ISO8601DateFormatter().string(from:Date()),"agent":"engine","model":model,"purpose":prefix + "/" + label]
        var data = try JSONSerialization.data(withJSONObject:row,options:[.sortedKeys]); data.append(10)
        let fd = Darwin.open(url.path,O_WRONLY | O_CREAT | O_APPEND,0o600)
        guard fd >= 0 else { throw CompanionError.unavailable("Cannot append live-call ledger.") }; defer { Darwin.close(fd) }
        let written = data.withUnsafeBytes { Darwin.write(fd,$0.baseAddress,data.count) }
        guard written == data.count else { throw CompanionError.unavailable("Short ledger write.") }; calls += 1
    }
}
// A production core adapter may expose this JSON stdin/stdout interface to the command.
// No XML parser or pretend production inspector lives in this executable.
struct CommandPictureInspector: PictureInspector {
    let executable: String
    func inspect(_ svg: String) throws -> (String, PictureDisplayList) {
        let p = Process(); let input = Pipe(); let output = Pipe()
        _ = fcntl(input.fileHandleForWriting.fileDescriptor, F_SETNOSIGPIPE, 1)
        p.executableURL = URL(fileURLWithPath:executable); p.standardInput = input; p.standardOutput = output; p.standardError = FileHandle.nullDevice
        let timer = DispatchSource.makeTimerSource(queue:DispatchQueue.global(qos:.utility)); timer.schedule(deadline:.now()+10); timer.setEventHandler { if p.isRunning { p.terminate() } }; timer.resume(); defer { timer.cancel() }
        try p.run(); defer { if p.isRunning { p.terminate() } }
        try input.fileHandleForWriting.write(contentsOf:JSONSerialization.data(withJSONObject:["svg":svg])); try input.fileHandleForWriting.close()
        var data=Data()
        while true { let chunk=output.fileHandleForReading.availableData; if chunk.isEmpty { break }; data.append(chunk); guard data.count <= 2*1024*1024 else { throw CompanionError.invalid("inspector output limit") } }
        p.waitUntilExit(); guard p.terminationStatus == 0 else { throw CompanionError.invalid("inspector rejected picture") }
        struct Result: Decodable { var svg: String; var displayList: PictureDisplayList }
        let r=try JSONDecoder().decode(Result.self,from:data); return (r.svg,r.displayList)
    }
    func filter(svg: String) throws -> String { try inspect(svg).0 }
    func displayList(svg: String) throws -> PictureDisplayList { try inspect(svg).1 }
}
@main struct Judge {
    static func main() async {
        do { try await run() }
        catch { fputs("SyncrJudge: \(error)\n", stderr); exit(1) }
    }
    static func run() async throws {
        let args=Array(CommandLine.arguments.dropFirst())
        func value(_ flag: String) -> String? { guard let i=args.firstIndex(of:flag), i+1 < args.count else { return nil }; return args[i+1] }
        if args.contains("--help") {
            print("SyncrJudge [--route recorded|codex|claude|openai] [--live-manual] [--set folder] [--output file] [--gate-model id] [--answer-model id] [--replay result.json] [--inspector executable] [--smoke]\nLive runs are explicitly opted in, ledgered and bounded. Default is offline. --smoke permits at most two Codex answer calls across the ledger. The inspector reads {svg} on stdin and writes {svg,displayList} on stdout. Missing inspector means picture pass rate is not measured."); return
        }
        let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let folder=value("--set").map { URL(fileURLWithPath:$0) } ?? root.appendingPathComponent("Judgment")
        let route=value("--route") ?? "recorded"
        let repo=root.deletingLastPathComponent().deletingLastPathComponent()
        let ledgerURL=repo.appendingPathComponent("tasks/sketch-trial-2/live-calls.jsonl")
        let output=value("--output").map { URL(fileURLWithPath:$0) } ?? folder.appendingPathComponent("Results/\(ISO8601DateFormatter().string(from:Date()).replacingOccurrences(of:":",with:"-"))-\(route).json")
        guard !FileManager.default.fileExists(atPath:output.path) else { throw CompanionError.unavailable("Refusing to overwrite a judgment result.") }
        if args.contains("--smoke") {
            guard route == "codex", args.contains("--live-manual") else { throw CompanionError.unavailable("Smoke requires --route codex --live-manual.") }
            try await smoke(output:output,ledger:LiveLedger(url:ledgerURL,prefix:"E1-smoke",maximum:2)); return
        }
        let cases=try JudgmentCase.load(folder:folder)
        let provider: CompanionProvider; let models: ModelSettings; var ledger: LiveLedger?
        if let replay=value("--replay") {
            let saved=try JSONDecoder().decode(JudgmentResult.self,from:Data(contentsOf:URL(fileURLWithPath:replay)))
            var passes:[RecordedStreamPass]=[]
            for c in cases {
                guard let row=saved.observations.first(where:{$0.id == c.id}) else { throw CompanionError.invalid("replay missing case") }
                passes.append(RecordedStreamPass(text:row.gateText))
                if row.gate?.speaks == true { passes.append(RecordedStreamPass(text:row.answerText)) }
            }
            provider=RecordedStreamProvider(passes:passes); models=saved.models
        } else if route == "recorded" {
            let replies=try JSONDecoder().decode([RecordedJudgmentReply].self,from:Data(contentsOf:folder.appendingPathComponent("recorded.json")))
            var passes:[RecordedStreamPass]=[]
            func json(_ obj:[String:Any]) throws -> String { String(decoding:try JSONSerialization.data(withJSONObject:obj,options:[.sortedKeys]),as:UTF8.self)+"\n" }
            for c in cases {
                guard let r=replies.first(where:{$0.id == c.id}) else { throw CompanionError.invalid("recorded missing case") }
                passes.append(RecordedStreamPass(text:try json(["decision":r.decision,"reason":"Recorded functional fixture, not a model judgment.","kind":r.kind])))
                if r.decision == "speak" {
                    var text=""
                    if let id=r.pattern, let p=PatternBook.all.first(where:{$0.id == id}) { text += try json(["type":"picture","quote":c.passage,"title":p.description]) + p.svg + "\n" }
                    text += try json(["type":"margin","quote":c.passage,"kind":r.kind == "picture" ? "explanation":r.kind,"text":r.text ?? "The example shows the structure. Read it in order."])
                    passes.append(RecordedStreamPass(text:text))
                }
            }
            provider=RecordedStreamProvider(passes:passes); models=ModelSettings(gate:"recorded-gate",answer:"recorded-answer")
        } else {
            guard args.contains("--live-manual") else { throw CompanionError.unavailable("Live route requires --live-manual.") }
            switch route { case "codex": provider=CodexProvider(); case "claude":provider=ClaudeProvider(); case "openai":provider=OpenAIProvider(); default:throw CompanionError.invalid("judge route") }
            let defaults=try ModelSettings.defaults(from:await provider.models())
            models=ModelSettings(gate:value("--gate-model") ?? defaults.gate,answer:value("--answer-model") ?? defaults.answer)
            ledger=LiveLedger(url:ledgerURL,prefix:"J1-judge",maximum:60)
        }
        let inspector=value("--inspector").map { CommandPictureInspector(executable:$0) }
        let runner=JudgmentRunner(provider:provider,models:models,inspector:inspector)
        let callLedger=ledger
        let result=await runner.run(cases:cases,route:value("--replay") == nil ? route:"replay") { label,model in try callLedger?.record(label,model) }
        await provider.shutdown()
        try result.write(to:output)
        print(result.summary); print("result: \(output.path)")
        if result.observations.contains(where:{!$0.errors.isEmpty}) { print("Some cases failed; inspect the result errors before claiming quality.") }
    }
    static func smoke(output: URL, ledger: LiveLedger) async throws {
        let traceURL = output.deletingPathExtension().appendingPathExtension("rpc.jsonl")
        try FileManager.default.createDirectory(at: traceURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: traceURL.path, contents: nil)
        let provider=CodexProvider(trace: { line in
            if let handle = try? FileHandle(forWritingTo: traceURL) { defer { try? handle.close() }; _ = try? handle.seekToEnd(); try? handle.write(contentsOf: Data((line + "\n").utf8)) }
        }); let models=try ModelSettings.defaults(from:await provider.models())
        var records:[[String:Any]]=[]
        let remaining = ledger.remaining()
        guard remaining > 0 else { throw CompanionError.unavailable("Both E1 smoke calls already used.") }
        for offset in 0..<remaining {
            let i = 3 - remaining + offset
            let passage="A cache is always fresh even after its source changes."
            let input=RequestInput(markdown:passage,instruction:"Ask one short question about the contradiction. Output one margin item, no picture.")
            let request=try StreamRequest(model:models.answer,input:input,patterns:[])
            try ledger.record("margin-\(i)",models.answer)
            let start=Date(); var firstToken:Double?; var firstItem:Double?; var raw=""; var parser=ItemStreamParser(input:input); var usage=Usage(reported:false)
            for try await event in provider.stream(request) {
                switch event {
                case .usage(let u):usage=u
                case .text(let text):
                    raw+=text; if firstToken == nil && !text.isEmpty { firstToken=Date().timeIntervalSince(start) }
                    try parser.append(text) { event in if case .item = event, firstItem == nil { firstItem=Date().timeIntervalSince(start) } }
                }
            }
            try parser.finish { event in if case .item = event, firstItem == nil { firstItem=Date().timeIntervalSince(start) } }
            let row:[String:Any]=["model":models.answer,"firstTokenSeconds":firstToken ?? -1,"firstItemSeconds":firstItem ?? -1,"totalSeconds":Date().timeIntervalSince(start),"response":raw,"usage":["input":usage.input,"output":usage.output,"cacheRead":usage.cacheRead,"cacheWrite":usage.cacheWrite]]
            records.append(row); print("Codex smoke \(i): first token \(firstToken ?? -1)s; first complete item \(firstItem ?? -1)s")
        }
        await provider.shutdown()
        try FileManager.default.createDirectory(at:output.deletingLastPathComponent(),withIntermediateDirectories:true)
        try JSONSerialization.data(withJSONObject:records,options:[.sortedKeys,.prettyPrinted]).write(to:output)
    }
}
