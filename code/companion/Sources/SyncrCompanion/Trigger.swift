import Foundation

public struct TriggerMachine {
    public var mode: CompanionMode = .whenAsked
    public var undismissedUnpinned = 0
    public var budgetReached = false
    public private(set) var inFlight = false
    public private(set) var changed = false
    public private(set) var lastActivity: TimeInterval = -.infinity
    public private(set) var lastPass: TimeInterval = -.infinity
    private var generation: UInt64 = 0
    private var sentGeneration: UInt64 = 0
    public init() {}
    public mutating func activity(at time: TimeInterval) { lastActivity = time; changed = true; generation &+= 1 }
    public mutating func idleActivity(at time: TimeInterval) { lastActivity = time }
    public func ready(at time: TimeInterval) -> Bool {
        mode == .thinksWithYou && changed && !inFlight && !budgetReached && undismissedUnpinned < 3 && time - lastActivity >= 3 && time - lastPass >= 20
    }
    public mutating func tick(at time: TimeInterval) -> Bool {
        guard ready(at: time) else { return false }
        begin(time); return true
    }
    public mutating func ask(at time: TimeInterval, confirmedBudget: Bool = false) throws {
        guard mode != .off else { throw CompanionError.off }
        guard !inFlight else { throw CompanionError.unavailable("A request is already in flight.") }
        guard !budgetReached || confirmedBudget else { throw CompanionError.budget }
        begin(time)
    }
    private mutating func begin(_ time: TimeInterval) { inFlight = true; lastPass = time; sentGeneration = generation }
    public mutating func finish() { inFlight = false; changed = generation != sentGeneration }
}
public struct BudgetMeter: Codable, Sendable {
    public var day: String
    public var tokenLimit: Int; public var requestLimit: Int
    public private(set) var tokens: Int = 0; public private(set) var requests: Int = 0
    public private(set) var unmeteredRequests: Int = 0
    public var reached: Bool { tokens >= tokenLimit || unmeteredRequests >= requestLimit }
    public init(day: String, tokenLimit: Int = 100000, requestLimit: Int = 100) {
        self.day = day; self.tokenLimit = max(1, tokenLimit); self.requestLimit = max(1, requestLimit)
    }
    public mutating func record(_ usage: Usage, day newDay: String) {
        rollover(day: newDay); requests += 1
        if usage.reported { tokens += usage.total } else { unmeteredRequests += 1 }
    }
    public mutating func rollover(day newDay: String) { if day != newDay { day = newDay; tokens = 0; requests = 0; unmeteredRequests = 0 } }
    public static func dayKey(_ date: Date = Date()) -> String { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f.string(from: date) }
}

/// This gate precedes provider.send, including process launch. Completion is applied by the host
/// only when the note, mode and request generation still match. No provider work belongs on a pen path.
public actor CompanionSession {
    private var machine = TriggerMachine()
    private var budget: BudgetMeter
    public private(set) var attempts = 0
    public private(set) var lastRequest: RequestRecord?
    public init(budget: BudgetMeter) { self.budget = budget }
    public func configure(mode: CompanionMode, notes: Int) { machine.mode = mode; machine.undismissedUnpinned = notes }
    public func reset() { machine = TriggerMachine(); machine.mode = .off }
    public func activity(at time: TimeInterval) { machine.activity(at: time) }
    public func idleActivity(at time: TimeInterval) { machine.idleActivity(at: time) }
    public func ready(at time: TimeInterval) -> Bool {
        budget.rollover(day: BudgetMeter.dayKey()); machine.budgetReached = budget.reached
        return machine.ready(at: time)
    }
    public func isInFlight() -> Bool { machine.inFlight }
    public func setLimits(tokens: Int, requests: Int) { budget.tokenLimit = max(1,tokens); budget.requestLimit = max(1,requests) }
    public func meter() -> BudgetMeter { budget.rollover(day: BudgetMeter.dayKey()); return budget }
    public func send(provider: CompanionProvider, request: CompanionRequest, at time: TimeInterval, readingAlong: Bool = false, confirmedBudget: Bool = false) async throws -> ProviderResponse? {
        budget.rollover(day: BudgetMeter.dayKey()); machine.budgetReached = budget.reached
        if readingAlong { guard machine.tick(at: time) else { return nil } }
        else { try machine.ask(at: time, confirmedBudget: confirmedBudget) }
        defer { machine.finish() }
        attempts += 1
        lastRequest = RequestRecord(model: request.model, system: request.system, user: request.user, pagePNG: request.input.pagePNG, web: request.input.web, wire: try provider.wirePreview(request), toolsJSON: String(decoding: try JSONSerialization.data(withJSONObject: ToolSchemas.tools, options: [.sortedKeys]), as: UTF8.self))
        let response = try await provider.send(request)
        budget.record(response.usage, day: BudgetMeter.dayKey())
        guard machine.mode != .off else { return nil }
        if readingAlong {
            let notes = response.tools.filter { switch $0 { case .marginNote, .canvasNote: return true; default: return false } }
            return ProviderResponse(tools: machine.undismissedUnpinned < 3 ? Array(notes.prefix(1)) : [], usage: response.usage)
        }
        return response
    }
}
public struct RequestRecord: Codable, Sendable {
    public var model: String; public var system: String; public var user: String; public var pagePNG: Data?; public var web: Bool; public var wire: String; public var toolsJSON: String
}
