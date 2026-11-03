import Foundation

public struct JudgmentCase: Codable, Sendable {
    public var id: String; public var domain: String; public var markdown: String; public var passage: String; public var expected: String; public var tell: String
    public init(id: String, domain: String, markdown: String, passage: String, expected: String, tell: String = "") { self.id = id; self.domain = domain; self.markdown = markdown; self.passage = passage; self.expected = expected; self.tell = tell }
    public static func load(folder: URL) throws -> [JudgmentCase] {
        let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).filter { $0.pathExtension == "json" && $0.lastPathComponent != "recorded.json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        let cases = try files.flatMap { try JSONDecoder().decode([Self].self, from: Data(contentsOf: $0)) }.sorted { $0.id < $1.id }
        guard cases.count >= 20, Set(cases.map(\.id)).count == cases.count,
              cases.allSatisfy({ ["quiet", "ask", "explain", "draw"].contains($0.expected) && PassageFinder.find($0.passage, in: $0.markdown) != nil }) else { throw CompanionError.invalid("judgment set (20+ unique cases with real passages required)") }; return cases
    }
}
public struct JudgmentObservation: Codable, Sendable {
    public var id: String; public var expected: String
    public var gate: GateDecision?; public var gateText: String = ""; public var answerText: String = ""
    public var gateSeconds: Double?; public var firstTokenSeconds: Double?; public var firstItemSeconds: Double?
    public var actualKind: String?; public var rightPassage: Bool?; public var pictureChecks: [Bool] = []
    public var uninspectedPictures = 0; public var errors: [String] = []; public var usage = Usage()
}
public struct JudgmentMeasure: Codable, Sendable {
    public var correct: Int; public var total: Int
    public var rate: Double? { total == 0 ? nil : Double(correct) / Double(total) }
    public var description: String { total == 0 ? "not measured" : "\(correct)/\(total) (\(Int((rate ?? 0)*100))%)" }
}
public struct JudgmentResult: Codable, Sendable {
    public var time = ISO8601DateFormatter().string(from: Date())
    public var route: String; public var models: ModelSettings; public var observations: [JudgmentObservation]
    public var quietWhenExpected: JudgmentMeasure { measure(observations.filter { $0.expected == "quiet" }) { $0.gate?.speaks == false } }
    public var spokeWhenExpected: JudgmentMeasure { measure(observations.filter { $0.expected != "quiet" }) { $0.gate?.speaks == true } }
    public var rightKind: JudgmentMeasure { measure(observations.filter { $0.expected != "quiet" }) { $0.actualKind == ["ask":"question","explain":"explanation","draw":"picture"][$0.expected] } }
    public var rightPassage: JudgmentMeasure { measure(observations.filter { $0.expected != "quiet" }) { $0.rightPassage == true } }
    public var picturePassRate: JudgmentMeasure { let checks = observations.flatMap(\.pictureChecks); return JudgmentMeasure(correct: checks.filter { $0 }.count, total: checks.count) }
    private func measure(_ rows: [JudgmentObservation], _ test: (JudgmentObservation) -> Bool) -> JudgmentMeasure { JudgmentMeasure(correct: rows.filter(test).count, total: rows.count) }
    public var summary: String {
        "quiet when expected: \(quietWhenExpected.description)\nspoke when expected: \(spokeWhenExpected.description)\nright kind: \(rightKind.description)\nright passage: \(rightPassage.description)\npicture check pass rate: \(picturePassRate.description); uninspected: \(observations.reduce(0) { $0 + $1.uninspectedPictures })\nerrors: \(observations.reduce(0) { $0 + $1.errors.count })"
    }
    public func write(to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .prettyPrinted]
        // Include numerators/denominators as well as raw observations in the durable result.
        let data = try encoder.encode(self); var root = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        root["measures"] = ["quietWhenExpected": [quietWhenExpected.correct,quietWhenExpected.total], "spokeWhenExpected": [spokeWhenExpected.correct,spokeWhenExpected.total], "rightKind": [rightKind.correct,rightKind.total], "rightPassage": [rightPassage.correct,rightPassage.total], "picturePassRate": [picturePassRate.correct,picturePassRate.total]]
        root["summary"] = summary
        root["passagePolicy"] = "Every emitted quote must resolve within the expected passage's UTF-8 span (a precise subquote is allowed)."
        try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys,.prettyPrinted]).write(to: url, options: .atomic)
    }
}
public struct JudgmentRunner: Sendable {
    public let provider: CompanionProvider; public let models: ModelSettings; public let inspector: PictureInspector?
    public init(provider: CompanionProvider, models: ModelSettings, inspector: PictureInspector? = nil) { self.provider = provider; self.models = models; self.inspector = inspector }
    public func run(cases: [JudgmentCase], route: String, beforeCall: @Sendable (String, String) throws -> Void = { _,_ in }) async -> JudgmentResult {
        var observations: [JudgmentObservation] = []
        for c in cases {
            var row = JudgmentObservation(id: c.id, expected: c.expected)
            var input = RequestInput(tell: c.tell, markdown: c.markdown, changed: PassageFinder.find(c.passage, in: c.markdown), instruction: "Read along with the changed passage. Speak only if worthwhile.")
            do {
                let gate = try StreamRequest(model: models.gate, input: input, purpose: .gate, patterns: [])
                try beforeCall(c.id + "/gate", models.gate); let start = Date()
                for try await event in provider.stream(gate) {
                    switch event { case .text(let t): row.gateText += t; guard row.gateText.utf8.count <= 2048 else { throw CompanionError.invalid("judge gate size") }; case .usage(let u): add(u, to: &row.usage) }
                }
                row.gateSeconds = Date().timeIntervalSince(start)
                row.gate = try GateDecision.decode(row.gateText)
                if row.gate?.speaks == true {
                    input.instruction = "Read along: give one \(row.gate!.kind) about the changed passage; quote that passage."
                    let answer = try StreamRequest(model: models.answer, input: input)
                    try beforeCall(c.id + "/answer", models.answer); let start = Date(); var parser = ItemStreamParser(input: input); var items: [StreamItem] = []
                    for try await event in provider.stream(answer) {
                        switch event {
                        case .usage(let u): add(u, to: &row.usage)
                        case .text(let t):
                            if row.firstTokenSeconds == nil && !t.isEmpty { row.firstTokenSeconds = Date().timeIntervalSince(start) }
                            row.answerText += t
                            try parser.append(t) { parsed in
                                if case .item(let item) = parsed { if row.firstItemSeconds == nil { row.firstItemSeconds = Date().timeIntervalSince(start) }; items.append(item) }
                            }
                        }
                    }
                    try parser.finish { parsed in
                        if case .item(let item) = parsed { if row.firstItemSeconds == nil { row.firstItemSeconds = Date().timeIntervalSince(start) }; items.append(item) }
                    }
                    var passages: [String] = []; var kinds: [String] = []
                    let commentary = items.compactMap { item -> String? in if case .margin(_,_,let text) = item { return text }; return nil }.joined(separator: " ")
                    for item in items {
                        switch item {
                        case .margin(let quote, let kind, _): passages.append(quote); kinds.append(kind == "flag" ? "question" : kind)
                        case .picture(_, let quote, _, let svg):
                            passages.append(quote); kinds.append("picture")
                            if let inspector {
                                do { let clean = try inspector.filter(svg: svg); let findings = PictureCheck.inspect(try inspector.displayList(svg: clean), commentary: commentary); row.pictureChecks.append(findings.isEmpty) }
                                catch { row.pictureChecks.append(false) }
                            } else { row.uninspectedPictures += 1 }
                        default: break
                        }
                    }
                    row.actualKind = kinds.contains("picture") ? "picture" : kinds.first
                    let expectedSpan = PassageFinder.find(c.passage, in: c.markdown)
                    row.rightPassage = !passages.isEmpty && passages.allSatisfy { quote in
                        guard let actual = PassageFinder.find(quote, in: c.markdown), let expected = expectedSpan else { return false }
                        return actual.start >= expected.start && actual.end <= expected.end
                    }
                }
            } catch { row.errors.append(String(describing: error)) }
            observations.append(row)
        }
        return JudgmentResult(route: route, models: models, observations: observations)
    }
    private func add(_ u: Usage, to total: inout Usage) { total.input += u.input; total.output += u.output; total.cacheRead += u.cacheRead; total.cacheWrite += u.cacheWrite; total.reported = total.reported && u.reported }
}
