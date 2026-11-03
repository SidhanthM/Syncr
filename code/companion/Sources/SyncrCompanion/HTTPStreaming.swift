import Foundation

public enum HTTPStreamChunk: Sendable { case status(Int), data(Data) }
public struct SSEDecoder {
    private var pending = Data(); private var lines: [String] = []; private var size = 0
    public init() {}
    public mutating func append(_ data: Data) throws -> [Data] {
        size += data.count; guard size <= 2 * 1024 * 1024 else { throw CompanionError.invalid("SSE size") }
        pending.append(data); var events: [Data] = []
        while let lf = pending.firstIndex(of: 10) {
            var bytes = Data(pending[..<lf]); pending.removeSubrange(...lf)
            if bytes.last == 13 { bytes.removeLast() }
            guard let line = String(data: bytes, encoding: .utf8) else { throw CompanionError.invalid("SSE UTF8") }
            if line.isEmpty {
                if !lines.isEmpty { events.append(Data(lines.joined(separator: "\n").utf8)); lines = [] }
            } else if line.hasPrefix("data:") { var value = String(line.dropFirst(5)); if value.first == " " { value.removeFirst() }; lines.append(value) }
        }
        return events
    }
    public func finish() throws { guard pending.isEmpty, lines.isEmpty else { throw CompanionError.invalid("truncated SSE") } }
}
public extension HTTPTransport {
    func stream(_ request: URLRequest) -> AsyncThrowingStream<HTTPStreamChunk, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do { let (data, status) = try await send(request); continuation.yield(.status(status)); continuation.yield(.data(data)); continuation.finish() }
                catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
public extension URLSessionTransport {
    func stream(_ request: URLRequest) -> AsyncThrowingStream<HTTPStreamChunk, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                let config = URLSessionConfiguration.ephemeral
                config.timeoutIntervalForRequest = 120; config.timeoutIntervalForResource = 180
                let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
                do {
                    let (bytes, response) = try await session.bytes(for: request)
                    continuation.yield(.status((response as? HTTPURLResponse)?.statusCode ?? 0))
                    var line = Data(); var size = 0
                    for try await byte in bytes {
                        try Task.checkCancellation(); line.append(byte); size += 1
                        guard size <= 2 * 1024 * 1024 else { throw CompanionError.invalid("HTTP stream size") }
                        if byte == 10 { continuation.yield(.data(line)); line.removeAll(keepingCapacity: true) }
                    }
                    if !line.isEmpty { continuation.yield(.data(line)) }
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
public enum SSEProviderRoute: Sendable { case claude, openAI }
public enum ProviderSSE {
    public static func stream(transport: HTTPTransport, request: URLRequest, route: SSEProviderRoute) -> AsyncThrowingStream<ProviderStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    var decoder = SSEDecoder(); var usage = Usage(reported: false); var completed = false; var statusSeen = false
                    for try await chunk in transport.stream(request) {
                        try Task.checkCancellation()
                        switch chunk {
                        case .status(let code): guard code == 200 else { throw CompanionError.transport(code) }; statusSeen = true
                        case .data(let data):
                            guard statusSeen else { throw CompanionError.invalid("missing HTTP status") }
                            for event in try decoder.append(data) {
                                guard !completed else { throw CompanionError.invalid("data after terminal SSE") }
                                try StrictJSON.check(event)
                                guard let obj = try JSONSerialization.jsonObject(with: event) as? [String: Any], let type = obj["type"] as? String else { throw CompanionError.invalid("SSE event") }
                                if type == "error" || type == "response.failed" || type == "response.incomplete" { throw CompanionError.unavailable("Provider stream failed or was incomplete.") }
                                switch route {
                                case .claude:
                                    if type == "content_block_delta", let delta = obj["delta"] as? [String: Any], delta["type"] as? String == "text_delta", let text = delta["text"] as? String { continuation.yield(.text(text)) }
                                    if type == "message_start", let message = obj["message"] as? [String: Any], let u = message["usage"] as? [String: Any] { usage = claudeUsage(u, previous: usage) }
                                    if type == "message_delta" {
                                        if let delta = obj["delta"] as? [String: Any], let stop = delta["stop_reason"] as? String, !["end_turn", "stop_sequence"].contains(stop) { throw CompanionError.invalid("incomplete Claude turn") }
                                        if let u = obj["usage"] as? [String: Any] { usage = claudeUsage(u, previous: usage) }
                                    }
                                    if type == "message_stop" { completed = true; continuation.yield(.usage(usage)) }
                                case .openAI:
                                    if type == "response.output_text.delta", let text = obj["delta"] as? String { continuation.yield(.text(text)) }
                                    if type == "response.completed" {
                                        guard let response = obj["response"] as? [String: Any], response["status"] as? String == "completed" else { throw CompanionError.invalid("OpenAI completion") }
                                        if let u = response["usage"] as? [String: Any] {
                                            let cached = (u["input_tokens_details"] as? [String: Any])?["cached_tokens"] as? Int ?? 0
                                            usage = Usage(input: max(0, (u["input_tokens"] as? Int ?? 0) - cached), output: u["output_tokens"] as? Int ?? 0, cacheRead: cached, reported: u["input_tokens"] != nil)
                                        }
                                        continuation.yield(.usage(usage)); completed = true
                                    }
                                }
                            }
                        }
                    }
                    try decoder.finish(); guard completed else { throw CompanionError.unavailable("Provider closed before terminal SSE event.") }
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
    private static func claudeUsage(_ u: [String: Any], previous p: Usage) -> Usage {
        Usage(input: u["input_tokens"] as? Int ?? p.input, output: u["output_tokens"] as? Int ?? p.output, cacheRead: u["cache_read_input_tokens"] as? Int ?? p.cacheRead, cacheWrite: u["cache_creation_input_tokens"] as? Int ?? p.cacheWrite, reported: p.reported || u["input_tokens"] != nil)
    }
}
