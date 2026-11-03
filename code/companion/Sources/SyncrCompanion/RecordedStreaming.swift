import Foundation

public struct RecordedStreamFrame: Sendable {
    public var delay: UInt64; public var event: ProviderStreamEvent
    public init(_ event: ProviderStreamEvent, afterNanoseconds delay: UInt64 = 0) { self.event = event; self.delay = delay }
}
public struct RecordedStreamPass: Sendable {
    public var frames: [RecordedStreamFrame]; public var fails: Bool
    public init(frames: [RecordedStreamFrame], fails: Bool = false) { self.frames = frames; self.fails = fails }
    public init(text: String) { frames = [RecordedStreamFrame(.text(text)), RecordedStreamFrame(.usage(Usage(input: 10, output: 5)))]; fails = false }
}
public final class RecordedStreamProvider: CompanionProvider, @unchecked Sendable {
    private let lock = NSLock(); private var passes: [RecordedStreamPass]; private var requests: [StreamRequest] = []
    public init(passes: [RecordedStreamPass]) { self.passes = passes }
    public var attempts: Int { lock.lock(); defer { lock.unlock() }; return requests.count }
    public var requestedModels: [String] { lock.lock(); defer { lock.unlock() }; return requests.map(\.model) }
    public func signInState() async -> String { "Recorded streaming (offline)" }
    public func models() async throws -> [ProviderModel] { [ProviderModel(id: "recorded", name: "Recorded streaming")] }
    public func send(_ request: CompanionRequest) async throws -> ProviderResponse { throw CompanionError.unavailable("Use stream for recorded v2 fixtures.") }
    public func stream(_ request: StreamRequest) -> AsyncThrowingStream<ProviderStreamEvent, Error> {
        lock.lock(); requests.append(request); let pass = passes.isEmpty ? nil : passes.removeFirst(); lock.unlock()
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    guard let pass else { throw CompanionError.unavailable("No recorded streaming pass left.") }
                    for frame in pass.frames { if frame.delay > 0 { try await Task.sleep(nanoseconds: frame.delay) }; try Task.checkCancellation(); continuation.yield(frame.event) }
                    if pass.fails { throw CompanionError.unavailable("Recorded provider died mid-stream.") }
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
