import Foundation

public enum EngineEvent: Sendable { case ask(RequestInput), reply(RequestInput), pause(RequestInput) }
/// The named stage a pass is in. Reported through `EngineOutput.step`, at each change only.
public enum CompanionStep: String, Sendable, Equatable { case deciding, thinking, writing, sketching, checking, fixing }
public enum EngineOutput: Sendable {
    case step(CompanionStep)
    case firstToken(seconds: Double)
    case textPreview(StreamTextPreview?)
    case item(StreamItem)
    case pictureStep(id: String, svg: String, step: Int)
    case pictureReplace(id: String, svg: String?, findings: [PictureFinding])
    case usage(Usage)
    case gate(GateDecision, seconds: Double)
    case finished, cancelled, failed(String)
}
public actor CompanionEngine {
    private let provider: CompanionProvider; private let inspector: PictureInspector?; private let log: LocalDecisionLog?; private let stateFolder: URL?
    public var models: ModelSettings
    private var trigger = TriggerMachine(); private var budget: BudgetMeter
    private var task: Task<Void, Never>?; private var active: UUID?; private var cancelling = false; private var readingPass = false
    private var sink: AsyncStream<EngineOutput>.Continuation?
    private var previews = Set<String>(); private var lastStep: CompanionStep?
    public private(set) var lastRequest: String?
    private let traceSink: TraceSink?; private var pass: PassTrace?
    public init(provider: CompanionProvider, models: ModelSettings, inspector: PictureInspector? = nil, stateFolder: URL? = nil, budget: BudgetMeter = BudgetMeter(day: BudgetMeter.dayKey()), trace: TraceSink? = TraceSink.fromEnvironment()) {
        traceSink = trace; self.provider = provider; self.models = models; self.inspector = inspector; self.budget = budget; self.stateFolder = stateFolder
        log = stateFolder.map { LocalDecisionLog(stateFolder: $0) }
    }
    public func configure(mode: CompanionMode, unpinnedNotes: Int) async {
        trigger.mode = mode; trigger.undismissedUnpinned = unpinnedNotes
        if mode == .off || (readingPass && (mode != .thinksWithYou || unpinnedNotes >= 3)) { await cancel() }
    }
    public func activity(at time: TimeInterval) async { trigger.activity(at: time); await cancel() }
    public func idleActivity(at time: TimeInterval) { trigger.idleActivity(at: time) }
    public func meter() -> BudgetMeter { budget.rollover(day: BudgetMeter.dayKey()); return budget }
    public func setLimits(tokens: Int, requests: Int) { budget.tokenLimit = max(1, tokens); budget.requestLimit = max(1, requests) }
    public func setModels(_ value: ModelSettings) async { await cancel(); models = value }
    public func cancel() async {
        guard active != nil else { return }
        active = nil; readingPass = false; cancelling = true; task?.cancel(); task = nil; endTrace("cancelled")
        for id in previews { sink?.yield(.pictureReplace(id: id, svg: nil, findings: [])) }; previews = []
        sink?.yield(.textPreview(nil)); sink?.yield(.cancelled); sink?.finish(); sink = nil; trigger.finish()
        await provider.cancelStream(); cancelling = false
    }
    public func shutdown() async { await cancel(); await provider.shutdown() }
    public func events(for event: EngineEvent, at time: TimeInterval, confirmedBudget: Bool = false) throws -> AsyncStream<EngineOutput> {
        let input: RequestInput; let reading: Bool
        switch event { case .ask(let v), .reply(let v): input = v; reading = false; case .pause(let v): input = v; reading = true }
        guard !cancelling else {
            if reading { return AsyncStream { $0.finish() } }
            throw CompanionError.unavailable("The previous pass is being cancelled.")
        }
        budget.rollover(day: BudgetMeter.dayKey()); trigger.budgetReached = budget.reached
        if reading {
            guard trigger.tick(at: time) else { return AsyncStream { $0.finish() } }
        } else { try trigger.ask(at: time, confirmedBudget: confirmedBudget) }
        let id = UUID(); active = id; readingPass = reading; lastStep = nil
        pass = traceSink == nil ? nil : PassTrace(purpose: reading ? "read" : "ask"); pass?.mark(.ask)
        return AsyncStream { continuation in
            sink = continuation
            continuation.onTermination = { [weak self] reason in if case .cancelled = reason { Task { await self?.cancelIfActive(id) } } }
            task = Task { await self.run(id: id, input: input, reading: reading) }
        }
    }
    private func cancelIfActive(_ id: UUID) async { if active == id { await cancel() } }
    private func alive(_ id: UUID) throws { guard active == id, !Task.isCancelled else { throw CancellationError() } }
    private func emit(_ event: EngineOutput, id: UUID) throws {
        try alive(id)
        switch event {
        case .textPreview(let preview?) where !preview.text.isEmpty: pass?.mark(.textShown)
        case .item(.margin), .item(.reply): pass?.mark(.textShown)
        case .pictureStep: pass?.mark(.pictureStepShown)
        default: break
        }
        sink?.yield(event)
    }
    private func endTrace(_ outcome: String) {
        guard let pass else { return }
        pass.mark(.done); pass.fact("outcome", outcome); traceSink?.write(pass.line()); self.pass = nil
    }
    private func step(_ value: CompanionStep, id: UUID) throws { guard lastStep != value else { return }; try emit(.step(value), id: id); lastStep = value }
    private func run(id: UUID, input original: RequestInput, reading: Bool) async {
        do {
            var input = original; pass?.mark(.started)
            if reading {
                try step(.deciding, id: id)
                let start = Date(); var gateInput = input; gateInput.web = false; gateInput.pagePNG = nil; gateInput.images = []
                var request = try StreamRequest(model: models.gate, input: gateInput, purpose: .gate, patterns: [])
                request.trace = pass?.leg("gate"); request.trace?.mark(.requestBuilt)
                let text = try await collect(request, id: id, limit: 2048)
                let decision = try GateDecision.decode(text); let elapsed = Date().timeIntervalSince(start)
                try await log?.append(LocalLogEntry(event: "gate", passage: selected(input), model: models.gate, gate: decision, seconds: elapsed))
                try emit(.gate(decision, seconds: elapsed), id: id)
                if !decision.speaks { try emit(.item(.quiet), id: id); finish(id); return }
                // Budget includes the gate. Never silently run an answer beyond its limit.
                if budget.reached { throw CompanionError.budget }
                input.instruction = "Read along: add at most one margin note. Give a \(decision.kind) about this passage. " + input.instruction
            } else if let thread = input.askThreadID {
                input.instruction = "Asked directly in thread \(thread): answer with reply items whose threadID is \"\(thread)\", not margin items. At most about 400 words in all: short paragraphs, lists, inline code and fenced code blocks with a language tag; split a longer walkthrough into several reply items in order. Add a picture only if it helps; margin items only for brief side notes of at most 60 words. " + input.instruction
            } else {
                input.instruction = "Asked directly: answer in margin items of at most 150 words each, kind explanation; split a longer walkthrough into several in order. " + input.instruction
            }
            let pinned = try stateFolder.map { try PatternBook.loadPinned(at: $0) } ?? []
            let patterns = PatternBook.closest(to: input.instruction, additional: pinned)
            var request = try StreamRequest(model: models.answer, input: input, patterns: patterns)
            request.trace = pass?.leg("answer"); request.trace?.mark(.requestBuilt)
            var parser = ItemStreamParser(input: input, marginWords: reading || input.askThreadID != nil ? 60 : 150); var pending: [StreamItem] = []; var deferred = false; var marginCount = 0
            let start = Date(); var first = true; var metered = false
            lastRequest = try provider.streamPreview(request)
            try step(.thinking, id: id)
            do {
                for try await event in provider.stream(request) {
                    try alive(id)
                    switch event {
                    case .usage(let usage): budget.record(usage, day: BudgetMeter.dayKey()); metered = true; try emit(.usage(usage), id: id)
                    case .text(let text):
                        if first && !text.isEmpty { first = false; request.trace?.mark(.firstByte); try emit(.firstToken(seconds: Date().timeIntervalSince(start)), id: id) }
                        var parsed: [ParsedStreamEvent] = []
                        do { try parser.append(text) { parsed.append($0) } }
                        catch {
                            // Preserve all complete items from this very chunk before reporting malformed trailing input.
                            try await consume(parsed, id: id, reading: reading, pending: &pending, deferred: &deferred, marginCount: &marginCount)
                            throw error
                        }
                        try await consume(parsed, id: id, reading: reading, pending: &pending, deferred: &deferred, marginCount: &marginCount)
                    }
                }
                var final: [ParsedStreamEvent] = []
                try parser.finish { final.append($0) }
                try await consume(final, id: id, reading: reading, pending: &pending, deferred: &deferred, marginCount: &marginCount)
            } catch {
                if !metered { budget.record(Usage(reported: false), day: BudgetMeter.dayKey()) }
                throw error
            }
            if !metered { budget.record(Usage(reported: false), day: BudgetMeter.dayKey()) }
            let commentary = pending.compactMap { item -> String? in if case .margin(_, _, let t) = item { return t }; return nil }.joined(separator: " ")
            for item in pending {
                if case .picture(let pictureID, let quote, let title, let svg) = item {
                    if try await finishPicture(id: id, pictureID: pictureID, quote: quote, title: title, svg: svg, commentary: commentary, input: input, allowFallbackMargin: !reading || marginCount < 1) { marginCount += 1 }
                } else if !reading || marginCount < 1 {
                    try emit(.item(item), id: id)
                    if case .margin = item { marginCount += 1 }
                }
            }
            finish(id)
        } catch is CancellationError {
            if active == id { await cancel() }
        } catch {
            guard active == id else { return }
            for pictureID in previews { sink?.yield(.pictureReplace(id: pictureID, svg: nil, findings: [])) }; previews = []
            sink?.yield(.failed(String(describing: error))); finish(id, success: false)
        }
    }
    private func consume(_ parsed: [ParsedStreamEvent], id: UUID, reading: Bool, pending: inout [StreamItem], deferred: inout Bool, marginCount: inout Int) async throws {
        for value in parsed {
            switch value {
            case .textPreview(let preview): if preview != nil { try self.step(.writing, id: id) }; try emit(.textPreview(preview), id: id)
            case .pictureStart: try step(.sketching, id: id)
            case .pictureStep(let pictureID, let svg, let step):
                pass?.mark(.pictureStepParsed)
                if let inspector, let clean = try? inspector.filter(svg: svg), let list = try? inspector.displayList(svg: clean), PictureCheck.acceptable(PictureCheck.inspect(list)) {
                    previews.insert(pictureID); try emit(.pictureStep(id: pictureID, svg: clean, step: step), id: id)
                }
            case .item(let item):
                switch item { case .margin, .reply: try step(.writing, id: id); default: break }
                if case .picture = item { deferred = true }
                if deferred { pending.append(item) }
                else if !reading || marginCount < 1 {
                    try emit(.item(item), id: id)
                    if case .margin = item { marginCount += 1 }
                }
            }
        }
    }
    private func collect(_ request: StreamRequest, id: UUID, limit: Int = 262144) async throws -> String {
        lastRequest = try provider.streamPreview(request); var text = ""; var metered = false
        do {
            for try await event in provider.stream(request) {
                try alive(id)
                switch event {
                case .text(let delta): text += delta; guard text.utf8.count <= limit else { throw CompanionError.invalid("collected stream limit") }
                case .usage(let usage): budget.record(usage, day: BudgetMeter.dayKey()); metered = true; try emit(.usage(usage), id: id)
                }
            }
        } catch { if !metered { budget.record(Usage(reported: false), day: BudgetMeter.dayKey()) }; throw error }
        if !metered { budget.record(Usage(reported: false), day: BudgetMeter.dayKey()) }; return text
    }
    private func finishPicture(id: UUID, pictureID: String, quote: String, title: String, svg: String, commentary: String, input: RequestInput, allowFallbackMargin: Bool) async throws -> Bool {
        guard let inspector else {
            let message = "A picture did not come out: the picture inspector is unavailable."
            try emit(.pictureReplace(id: pictureID, svg: nil, findings: [PictureFinding(rule: "fallback", step: 0, items: [], detail: message)]), id: id)
            if allowFallbackMargin { try emit(.item(.margin(quote: quote, kind: "explanation", text: message)), id: id) }; return allowFallbackMargin
        }
        var clean = svg; var findings: [PictureFinding] = []
        for round in 0...1 {
            try step(.checking, id: id)
            do { clean = try inspector.filter(svg: clean); findings = PictureCheck.inspect(try inspector.displayList(svg: clean), commentary: commentary) }
            catch { findings = [PictureFinding(rule: "filter", step: 0, items: [], detail: "Picture filter rejected the SVG.")] }
            try await log?.append(LocalLogEntry(event: "picture-check", passage: quote, itemID: pictureID, findings: findings))
            if findings.isEmpty || round == 1 { break }
            try step(.fixing, id: id)
            var repair = input
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            repair.instruction = "Repair this SVG once. Return only a picture header with quote \(quote) and the repaired raw SVG; no other items. Findings: \(String(decoding: try encoder.encode(findings), as: UTF8.self))\nOriginal SVG:\n\(clean)\nCommentary that must remain true:\n\(commentary)"
            let result: String
            do { result = try await collect(StreamRequest(model: models.answer, input: repair, purpose: .repair), id: id) }
            catch is CancellationError { throw CancellationError() }
            catch { findings.append(PictureFinding(rule: "repair", step: 0, items: [], detail: "Repair provider failed.")); break }
            var parser = ItemStreamParser(input: input); var pictures: [String] = []
            do {
                try parser.append(result) { event in if case .item(.picture(_, let repairedQuote, _, let svg)) = event, repairedQuote == quote { pictures.append(svg) } }
                try parser.finish { event in if case .item(.picture(_, let repairedQuote, _, let svg)) = event, repairedQuote == quote { pictures.append(svg) } }; guard pictures.count == 1 else { throw CompanionError.invalid("repair picture count") }; clean = pictures[0]
            } catch { findings = [PictureFinding(rule: "repair", step: 0, items: [], detail: "Repair did not return one valid picture.")]; break }
        }
        previews.remove(pictureID)
        if PictureCheck.acceptable(findings) {
            try emit(.pictureReplace(id: pictureID, svg: clean, findings: findings), id: id)
            try emit(.item(.picture(id: pictureID, quote: quote, title: title, svg: clean)), id: id)
            return false
        } else {
            let message = "A picture did not come out reliably. Read the explanation in words instead."
            if !allowFallbackMargin { findings.append(PictureFinding(rule: "fallback", step: 0, items: [], detail: message)) }
            try emit(.pictureReplace(id: pictureID, svg: nil, findings: findings), id: id)
            if allowFallbackMargin { try emit(.item(.margin(quote: quote, kind: "explanation", text: message)), id: id) }
            return allowFallbackMargin
        }
    }
    private func finish(_ id: UUID, success: Bool = true) {
        guard active == id else { return }
        endTrace(success ? "finished" : "failed")
        sink?.yield(.textPreview(nil))
        if success { sink?.yield(.finished) }; sink?.finish(); sink = nil; active = nil; readingPass = false; task = nil; previews = []; trigger.finish()
    }
    private func selected(_ input: RequestInput) -> String {
        let bytes = Array(input.markdown.utf8)
        if let span = input.changed ?? input.selection, span.start >= 0, span.end <= bytes.count, span.end >= span.start { return String(decoding: bytes[span.start..<span.end], as: UTF8.self) }
        return String(input.markdown.suffix(2400))
    }
}
