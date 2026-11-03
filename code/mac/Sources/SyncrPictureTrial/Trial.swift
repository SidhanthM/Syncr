import AppKit
import Foundation
import SyncrCore
import SyncrCompanion

/// Counts every actual provider stream, including repair, before it starts. No prompt contents or secrets.
final class TrialProvider: CompanionProvider, @unchecked Sendable {
    let base: any CompanionProvider
    let root: URL
    let output: URL
    let label: String
    let model: String
    private let lock = NSLock()
    private var call = 0
    var startNumber: Int { get { call } set { call = newValue } }
    init(base: any CompanionProvider, root: URL, output: URL, label: String, model: String) { self.base = base; self.root = root; self.output = output; self.label = label; self.model = model }
    func signInState() async -> String { await base.signInState() }
    func models() async throws -> [ProviderModel] { try await base.models() }
    func send(_ request: CompanionRequest) async throws -> ProviderResponse { try await base.send(request) }
    func streamPreview(_ request: StreamRequest) throws -> String { try base.streamPreview(request) }
    func cancelStream() async { await base.cancelStream() }
    func shutdown() async { await base.shutdown() }
    func stream(_ request: StreamRequest) -> AsyncThrowingStream<ProviderStreamEvent, Error> {
        lock.lock(); call += 1; let number = call; lock.unlock()
        let purpose: String
        switch request.purpose { case .answer: purpose = "answer"; case .gate: purpose = "gate"; case .repair: purpose = "repair" }
        do {
            let ledger = root.appendingPathComponent("live-calls.jsonl")
            let existing = (try? String(contentsOf: ledger,encoding: .utf8)) ?? ""
            let count = existing.split(separator: "\n").filter { $0.contains("\"agent\":\"mac-companion-2\"") }.count
            guard count < 40 else { throw CompanionError.unavailable("mac-companion-2 live-call allowance (40) exhausted") }
            let entry = ["time": ISO8601DateFormatter().string(from: Date()), "agent": "mac-companion-2", "model": model, "purpose": "trial-" + label + " " + purpose]
            var data = try JSONSerialization.data(withJSONObject: entry,options: [.sortedKeys]); data.append(10)
            if !FileManager.default.fileExists(atPath: ledger.path) { FileManager.default.createFile(atPath: ledger.path,contents: nil) }
            let handle = try FileHandle(forWritingTo: ledger); try handle.seekToEnd(); try handle.write(contentsOf: data); try handle.close()
        } catch { return AsyncThrowingStream { $0.finish(throwing: error) } }
        let raw = output.appendingPathComponent("raw-\(number)-\(purpose).txt")
        let base = self.base
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    var text = ""
                    for try await event in base.stream(request) {
                        if case .text(let delta) = event { text += delta }
                        continuation.yield(event)
                    }
                    try text.write(to: raw,atomically: true,encoding: .utf8); continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

/// First pass replays an answer already paid for in an earlier live run (no new call, nothing in the ledger); every later pass,
/// the repair, goes live through the counted provider.
final class ReplayThenLive: CompanionProvider, @unchecked Sendable {
    private let saved: String; private let live: TrialProvider; private let lock = NSLock(); private var used = false
    init(saved: String, live: TrialProvider) { self.saved = saved; self.live = live; live.startNumber = 1 }
    func signInState() async -> String { await live.signInState() }
    func models() async throws -> [ProviderModel] { try await live.models() }
    func send(_ request: CompanionRequest) async throws -> ProviderResponse { try await live.send(request) }
    func streamPreview(_ request: StreamRequest) throws -> String { try live.streamPreview(request) }
    func cancelStream() async { await live.cancelStream() }
    func shutdown() async { await live.shutdown() }
    func stream(_ request: StreamRequest) -> AsyncThrowingStream<ProviderStreamEvent, Error> {
        lock.lock(); let first = !used; used = true; lock.unlock()
        if !first { return live.stream(request) }
        let text = saved
        return AsyncThrowingStream { continuation in
            continuation.yield(.text(text)); continuation.yield(.usage(Usage(input: 0, output: 0))); continuation.finish()
        }
    }
}

@main struct PictureTrial {
    struct Result { var firstText: Double?; var firstStep: Double?; var finalPicture: Double?; var last = 0.0; var commentary: [String] = []; var pictures: [(String,String)] = []; var failures: [String] = []; var fallback = false }

    @MainActor static func main() async throws {
        _ = NSApplication.shared
        let args = CommandLine.arguments, live = args.contains("--live")
        if let at = args.firstIndex(of: "--patterns") { try renderPatterns(into: URL(fileURLWithPath: args[at+1],isDirectory: true), clean: args.contains("--clean")); return }
        if let at = args.firstIndex(of: "--inspect") {
            // Text items of a stored picture: the core's conservative bounds beside the measured glyph box.
            let list = try CorePictureInspector().displayList(svg: try String(contentsOfFile: args[at+1],encoding: .utf8))
            print("canvas \(list.width)x\(list.height)")
            for (n,step) in ([("always",list.always)] + list.steps.map { ("step \($0.number)",$0.items) }) {
                for item in step where item.text != nil {
                    let l = PictureRenderer.layout(item.text!,token: item.font ?? "font-voice",size: item.fontSize,anchor: item.anchor,baseline: item.baseline ?? "alphabetic")
                    print("\(n) \"\(item.text!)\" x=\(item.x) y=\(item.y) size=\(item.fontSize) \(item.anchor) core-bounds=\(item.bounds.map { String(format: "%.0f..%.0f x %.0f..%.0f",$0.minX,$0.maxX,$0.minY,$0.maxY) } ?? "-") measured-w=\(String(format: "%.0f",l.width))")
                }
            }
            return
        }
        let root = URL(fileURLWithPath: args.firstIndex(of: "--output").map { args[$0+1] } ?? "../../tasks/sketch-trial-2",isDirectory: true).standardizedFileURL
        try FileManager.default.createDirectory(at: root,withIntermediateDirectories: true)
        let prompts = try JSONDecoder().decode([String].self,from: Data(contentsOf: root.appendingPathComponent("prompts.json")))
        let only = args.firstIndex(of: "--only").flatMap { Int(args[$0+1]) }
        let model = live ? (CodexProvider.newestSol(try await CodexProvider().models()) ?? "") : "recorded"
        guard !model.isEmpty else { throw CompanionError.unavailable("No Sol model offered by pi") }
        let indices = only.map { [$0] } ?? (live ? Array(1...prompts.count) : [1])
        PictureRenderer.handDrawn = true
        let replayUpTo = args.firstIndex(of: "--replay-saved").flatMap { Int(args[$0+1]) } ?? 0
        for index in indices {
            let prompt = prompts[index-1], folder = root.appendingPathComponent(String(format: "%02d",index))
            // An answer already obtained in an earlier live run is replayed, not paid for again; its first-run timings are kept.
            var saved: String?, firstRun: Data?
            if live, index <= replayUpTo { saved = try? String(contentsOf: folder.appendingPathComponent("raw-1-answer.txt"),encoding: .utf8); firstRun = try? Data(contentsOf: folder.appendingPathComponent("timings-run1.json")) ?? Data(contentsOf: folder.appendingPathComponent("timings.json")) }
            let image13 = try? Data(contentsOf: folder.appendingPathComponent("pasted-skeleton.png"))
            _ = image13
            try? FileManager.default.removeItem(at: folder)
            try FileManager.default.createDirectory(at: folder,withIntermediateDirectories: true)
            var input = RequestInput(markdown: prompt,selection: ByteSpan(start: 0,end: prompt.utf8.count),instruction: "Explain this passage using a step-by-step picture, then two or three sentences on how to read it. " + prompt)
            if index == 13 {
                let image = skeleton()
                try image.write(to: folder.appendingPathComponent("pasted-skeleton.png"))
                input.images = [NoteImage(id: "pasted-skeleton",data: image)]
            }
            let provider: any CompanionProvider
            if live {
                let counted = TrialProvider(base: CodexProvider(),root: root,output: folder,label: String(format: "%02d",index),model: model)
                if let saved {
                    provider = ReplayThenLive(saved: saved,live: counted)
                    try saved.write(to: folder.appendingPathComponent("raw-1-answer.txt"),atomically: true,encoding: .utf8)
                    if let firstRun { try firstRun.write(to: folder.appendingPathComponent("timings-run1.json")) }
                } else { provider = counted }
            }
            else {
                let header = String(decoding: try JSONSerialization.data(withJSONObject: ["type":"picture","quote":prompt,"title":"Recorded picture"]),as: UTF8.self)
                let fixture = "<svg viewBox=\"0 0 320 100\"><g data-step=\"1\" data-caption=\"Connect the two points.\"><line x1=\"30\" y1=\"50\" x2=\"280\" y2=\"50\" stroke=\"var(--voice)\"/><text x=\"30\" y=\"30\" fill=\"var(--ink)\" font-size=\"12\" font-family=\"var(--font-voice)\">A to B</text></g></svg>"
                provider = RecordedStreamProvider(passes: [RecordedStreamPass(text: header+"\n"+fixture+"\n")])
            }
            let engine = CompanionEngine(provider: provider,models: ModelSettings(gate: model,answer: model),inspector: CorePictureInspector(),stateFolder: folder)
            await engine.configure(mode: .whenAsked,unpinnedNotes: 0)
            let start = ProcessInfo.processInfo.systemUptime
            var r = Result()
            let events = try await engine.events(for: .ask(input),at: start)
            for await event in events {
                let elapsed = ProcessInfo.processInfo.systemUptime-start; r.last = elapsed
                switch event {
                case .firstToken(let seconds): r.firstText = seconds
                case .item(.margin(_,_,let text)): r.commentary.append(text)
                case .pictureStep(_,_,_): if r.firstStep == nil { r.firstStep = elapsed }
                case .item(.picture(_,_,let title,let svg)): r.pictures.append((title,svg)); r.finalPicture = elapsed
                case .pictureReplace(_,let svg,let findings): if svg == nil, findings.contains(where: { $0.rule == "fallback" }) || r.pictures.isEmpty { r.fallback = true }
                case .failed(let message): r.failures.append(message)
                default: break
                }
            }
            if let wire = await engine.lastRequest { try wire.write(to: folder.appendingPathComponent("last-request.txt"),atomically: true,encoding: .utf8) }
            await engine.shutdown()
            let findings = checkFindings(folder)
            // A rejected candidate is kept as evidence too, labelled as such, rather than quietly omitted.
            var rejected = false
            if r.pictures.isEmpty, live {
                let files = try FileManager.default.contentsOfDirectory(at: folder,includingPropertiesForKeys: nil).filter { $0.lastPathComponent.hasPrefix("raw-") && !$0.lastPathComponent.contains("repair") }.sorted { $0.path < $1.path }
                for file in files {
                    var parser = ItemStreamParser(input: input)
                    try? parser.append(try String(contentsOf: file,encoding: .utf8)) { event in
                        if case .item(.picture(_,_,let title,let svg)) = event { r.pictures.append(("Rejected candidate: "+title,svg)); rejected = true }
                    }
                }
            }
            var summary = r.pictures.isEmpty ? "No picture." : ""
            for (variant,hand) in [("",true),("-clean",false)] where hand || index == 6 {
                var steps: [(URL,String)] = []
                for (pictureIndex,pair) in r.pictures.enumerated() {
                    do {
                        let clean = try CorePictureInspector().filter(svg: pair.1), picture = try CorePictureInspector.mac(svg: clean)
                        if hand { try clean.write(to: folder.appendingPathComponent("picture-\(pictureIndex+1).svg"),atomically: true,encoding: .utf8) }
                        for step in 1...max(1,picture.steps.count) {
                            let url = folder.appendingPathComponent("picture-\(pictureIndex+1)-step-\(step)\(variant).png")
                            if let png = PictureRenderer.image(picture,through: step,dark: true,handDrawn: hand)?.representation(using: .png,properties: [:]) { try png.write(to: url); steps.append((url,picture.steps.indices.contains(step-1) ? picture.steps[step-1].caption : "")) }
                        }
                    } catch { r.failures.append("Rendering/filter: \(error)") }
                }
                try contactSheet(steps,title: (rejected ? "REJECTED BY CHECK. " : "") + prompt + (hand ? "" : "   [clean pen]"),failures: r.failures).write(to: folder.appendingPathComponent("contact-sheet\(variant).png"))
                if hand { summary = "\(steps.count) steps" }
            }
            let live2 = live
            let result: [String:Any] = ["model":model,"live":live2,"secondsToFirstText":r.firstText as Any? ?? NSNull(),"secondsToFirstStep":r.firstStep as Any? ?? NSNull(),"secondsToFinalPicture":r.finalPicture as Any? ?? NSNull(),"secondsToLast":r.last,"acceptedPictures":rejected ? 0 : r.pictures.count,"rejectedCandidate":rejected,"fellBackToWords":r.fallback,"steps":summary,"failures":r.failures,"answerReplayedFromRun1":saved != nil]
            try JSONSerialization.data(withJSONObject: result,options: [.prettyPrinted,.sortedKeys]).write(to: folder.appendingPathComponent("timings.json"))
            try JSONSerialization.data(withJSONObject: findings,options: [.prettyPrinted,.sortedKeys]).write(to: folder.appendingPathComponent("findings.json"))
            try (r.commentary.joined(separator: "\n\n") + "\n").write(to: folder.appendingPathComponent("commentary.md"),atomically: true,encoding: .utf8)
            print("trial \(index) model=\(model) text=\(r.firstText ?? -1) first-step=\(r.firstStep ?? -1) last=\(r.last) \(summary) rejected=\(rejected) fallback=\(r.fallback) checks=\(findings.map { ($0["rules"] as? [String] ?? []).count })")
            fflush(stdout)
        }
        var index = "# Picture trial 2\n\nPictures stay off by default. Dark theme, hand-drawn pen (prompt 06 also has a clean-pen sheet). The first trial's contact sheets are not in the repository, so that column is empty. Check findings before and after the one repair are in each folder's `findings.json`; times are software stream delivery, not display scan-out.\n\n| # | Prompt | First trial | v2 contact sheet | Seconds to first step / last |\n|---|---|---|---|---|\n"
        for (i,prompt) in prompts.enumerated() {
            let name = String(format: "%02d",i+1)
            var times = "-"
            if let data = try? Data(contentsOf: root.appendingPathComponent("\(name)/timings.json")), let t = try? JSONSerialization.jsonObject(with: data) as? [String:Any] {
                func f(_ k: String) -> String { (t[k] as? Double).map { String(format: "%.1f", $0) } ?? "none" }
                times = "\(f("secondsToFirstStep")) / \(f("secondsToLast"))" + ((t["fellBackToWords"] as? Bool) == true ? " (words only)" : "") + ((t["rejectedCandidate"] as? Bool) == true ? " (rejected)" : "")
            }
            index += "| \(i+1) | \(prompt) | not kept | <img src=\"\(name)/contact-sheet.png\" width=\"360\">\(i == 5 ? " <img src=\"06/contact-sheet-clean.png\" width=\"360\">" : "") [commentary](\(name)/commentary.md) [findings](\(name)/findings.json) | \(times) |\n"
        }
        if only == nil { try index.write(to: root.appendingPathComponent("index.md"),atomically: true,encoding: .utf8) }
    }
    /// The engine's own log, in order: the first `picture-check` is the picture as written, the second (if any) after the one repair.
    static func checkFindings(_ folder: URL) -> [[String:Any]] {
        guard let text = try? String(contentsOf: folder.appendingPathComponent("companion-decisions.jsonl"),encoding: .utf8) else { return [] }
        var rounds: [[String:Any]] = []
        for line in text.split(separator: "\n") {
            guard let data = line.data(using: .utf8), let object = try? JSONSerialization.jsonObject(with: data) as? [String:Any], object["event"] as? String == "picture-check" else { continue }
            let findings = object["findings"] as? [[String:Any]] ?? []
            rounds.append(["round": rounds.count == 0 ? "as written" : "after repair","count": findings.count,"rules": findings.compactMap { $0["rule"] as? String },"findings": findings])
        }
        return rounds
    }
    /// Renders the engine's twelve bundled patterns through the core filter, last step, dark theme: a renderer check with no model.
    @MainActor static func renderPatterns(into folder: URL, clean: Bool) throws {
        try FileManager.default.createDirectory(at: folder,withIntermediateDirectories: true)
        for pattern in PatternBook.all {
            let picture = try CorePictureInspector.mac(svg: try CorePictureInspector().filter(svg: pattern.svg))
            guard let png = PictureRenderer.image(picture,through: picture.steps.count,dark: true,handDrawn: !clean)?.representation(using: .png,properties: [:]) else { continue }
            try png.write(to: folder.appendingPathComponent(pattern.id+(clean ? "-clean" : "-hand")+".png"))
        }
        print("rendered \(PatternBook.all.count) patterns into \(folder.path)")
    }
    /// The pasted problem picture: the skeleton H-N(-)-CH2-C≡C-CH3 drawn with AppKit, plain black on white as a screenshot would be.
    @MainActor static func skeleton() -> Data {
        let image = NSImage(size: CGSize(width: 1000,height: 260),flipped: true) { rect in
            NSColor.white.setFill(); rect.fill()
            let font = NSFont.systemFont(ofSize: 64), small = NSFont.systemFont(ofSize: 36)
            func label(_ text: String,_ x: CGFloat,_ y: CGFloat,_ f: NSFont = font) { (text as NSString).draw(at: CGPoint(x: x,y: y),withAttributes: [.font: f,.foregroundColor: NSColor.black]) }
            func bond(_ x1: CGFloat,_ x2: CGFloat,_ count: Int) {
                NSColor.black.setStroke()
                for i in 0..<count { let y = 118 + CGFloat(i - (count-1)/2) * 12 - (count == 2 ? 6 : 0); let p = NSBezierPath(); p.lineWidth = 4; p.move(to: CGPoint(x: x1,y: y)); p.line(to: CGPoint(x: x2,y: y)); p.stroke() }
            }
            label("H",60,85); bond(115,190,1); label("N",195,85); label("−",240,70,small); bond(255,330,1)
            label("CH₂",335,85); bond(450,525,1); label("C",530,85); bond(580,680,3); label("C",685,85); bond(740,815,1); label("CH₃",820,85,font)
            label("Orbital diagrams for each non-H atom?",60,200,small)
            return true
        }
        return NSBitmapImageRep(data: image.tiffRepresentation!)!.representation(using: .png,properties: [:])!
    }
    @MainActor static func contactSheet(_ steps: [(URL,String)], title: String, failures: [String]) throws -> Data {
        let columns = min(4,max(1,steps.count)), rows = max(1,Int(ceil(Double(steps.count)/Double(columns))))
        let size = CGSize(width: columns*350,height: rows*540+100)
        let image = NSImage(size: size,flipped: true) { rect in
            NSColor(srgbRed: 0.07,green: 0.07,blue: 0.07,alpha: 1).setFill(); rect.fill()
            (title as NSString).draw(in: CGRect(x: 16,y: 10,width: size.width-32,height: 70),withAttributes: [.font:NSFont.systemFont(ofSize: 15),.foregroundColor:NSColor.white])
            for (index,step) in steps.enumerated() {
                let x = CGFloat(index%columns)*350, y = CGFloat(index/columns)*540+90
                if let source = NSImage(contentsOf: step.0) {
                    let ratio = min(330/source.size.width,450/source.size.height)
                    source.draw(in: CGRect(x: x+10,y: y,width: source.size.width*ratio,height: source.size.height*ratio),from: .zero,operation: .sourceOver,fraction: 1,respectFlipped: true,hints: nil)
                }
                ("\(index+1). "+step.1 as NSString).draw(in: CGRect(x: x+12,y: y+460,width: 326,height: 65),withAttributes: [.font:NSFont.systemFont(ofSize: 13),.foregroundColor:NSColor.lightGray])
            }
            if steps.isEmpty { ("No renderable picture. "+failures.joined(separator: "; ") as NSString).draw(in: CGRect(x: 16,y: 110,width: size.width-32,height: 400),withAttributes: [.font:NSFont.systemFont(ofSize: 14),.foregroundColor:NSColor.white]) }
            return true
        }
        return NSBitmapImageRep(data: image.tiffRepresentation!)!.representation(using: .png,properties: [:])!
    }
}
