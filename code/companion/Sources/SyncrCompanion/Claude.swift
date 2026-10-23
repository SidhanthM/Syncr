import Foundation
import Security

public enum ClaudeKeychain {
    private static let service = "dev.syncr.companion.claude"
    private static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "api-key"] }
    public static func read() throws -> String? {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?; let status = SecItemCopyMatching(q as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data, let key = String(data: data, encoding: .utf8) else { throw CompanionError.transport(Int(status)) }
        return key
    }
    public static func set(_ key: String) throws {
        guard !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, key.utf8.count <= 4096, !key.contains("\n") else { throw CompanionError.invalid("key") }
        let data = Data(key.utf8)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var q = query; q[kSecValueData as String] = data; q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let result = SecItemAdd(q as CFDictionary, nil)
            guard result == errSecSuccess else { throw CompanionError.transport(Int(result)) }
        } else if status != errSecSuccess { throw CompanionError.transport(Int(status)) }
    }
    public static func delete() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw CompanionError.transport(Int(status)) }
    }
}
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, Int)
}
public struct URLSessionTransport: HTTPTransport {
    public init() {}
    public func send(_ request: URLRequest) async throws -> (Data, Int) {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 120; config.timeoutIntervalForResource = 180
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request)
        guard data.count <= 2 * 1024 * 1024 else { throw CompanionError.invalid("provider response too large") }
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }
}
public struct ClaudeProvider: CompanionProvider {
    public static let readingDefault = "claude-sonnet-5-5"
    public static let askingDefault = "claude-fable-5-1"
    private let transport: HTTPTransport
    private let key: @Sendable () throws -> String?
    public init(transport: HTTPTransport = URLSessionTransport(), key: @escaping @Sendable () throws -> String? = { try ClaudeKeychain.read() }) { self.transport = transport; self.key = key }
    public func signInState() async -> String { ((try? key()) ?? nil) == nil ? "No Claude API key" : "Claude API key in Keychain" }
    private func request(_ path: String, body: Data? = nil) throws -> URLRequest {
        guard let value = try key() else { throw CompanionError.unavailable("Set a Claude API key in Settings.") }
        var r = URLRequest(url: URL(string: "https://api.anthropic.com/v1/" + path)!)
        r.httpMethod = body == nil ? "GET" : "POST"; r.httpBody = body
        r.setValue(value, forHTTPHeaderField: "x-api-key")
        r.setValue("2026-10-21", forHTTPHeaderField: "anthropic-version")
        r.setValue("application/json", forHTTPHeaderField: "content-type")
        return r
    }
    public func models() async throws -> [ProviderModel] {
        var models: [ProviderModel] = []; var after: String?
        for _ in 0..<100 {
            let suffix = after.map { "&after_id=" + ($0.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "") } ?? ""
            let (data, status) = try await transport.send(request("models?limit=100" + suffix))
            guard status == 200 else { throw CompanionError.transport(status) }
            guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], let rows = root["data"] as? [[String: Any]] else { throw CompanionError.invalid("model list") }
            for row in rows { if let id = row["id"] as? String { models.append(ProviderModel(id: id, name: row["display_name"] as? String ?? id)) } }
            if root["has_more"] as? Bool != true { return models }
            guard let last = root["last_id"] as? String, last != after else { throw CompanionError.invalid("model pagination") }; after = last
        }
        throw CompanionError.invalid("model page limit")
    }
    public static func body(_ r: CompanionRequest) throws -> Data {
        var content: [[String: Any]] = [["type": "text", "text": r.user]]
        if let image = r.input.pagePNG { content.append(["type": "image", "source": ["type": "base64", "media_type": "image/png", "data": image.base64EncodedString()]]) }
        var tools = ToolSchemas.tools
        if r.input.web { tools.append(["type": "web_search_20260318", "name": "web_search", "max_uses": 3, "allowed_callers": ["direct"]]) }
        return try JSONSerialization.data(withJSONObject: ["model": r.model, "max_tokens": 12000, "system": r.system, "messages": [["role": "user", "content": content]], "tools": tools], options: [.sortedKeys])
    }
    public func wirePreview(_ r: CompanionRequest) throws -> String {
        "POST https://api.anthropic.com/v1/messages\nanthropic-version: 2026-10-21\ncontent-type: application/json\nx-api-key: [Keychain credential omitted]\n\n" + String(decoding: try Self.body(r), as: UTF8.self)
    }
    public func send(_ r: CompanionRequest) async throws -> ProviderResponse {
        let (data, status) = try await transport.send(request("messages", body: Self.body(r)))
        guard status == 200 else { throw CompanionError.transport(status) }
        return try Self.decode(data, web: r.input.web)
    }
    public static func decode(_ data: Data, web: Bool) throws -> ProviderResponse {
        guard data.count <= 2 * 1024 * 1024 else { throw CompanionError.invalid("Claude response limit") }
        try StrictJSON.check(data)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], let content = root["content"] as? [[String: Any]], root["stop_reason"] as? String != "max_tokens", root["stop_reason"] as? String != "pause_turn" else { throw CompanionError.invalid("Claude response") }
        let calls = content.filter { $0["type"] as? String == "tool_use" }.map { ["name": $0["name"] ?? NSNull(), "arguments": $0["input"] ?? NSNull()] }
        let usage = root["usage"] as? [String: Any] ?? [:]
        let value = Usage(input: usage["input_tokens"] as? Int ?? 0, output: usage["output_tokens"] as? Int ?? 0, cacheRead: usage["cache_read_input_tokens"] as? Int ?? 0, cacheWrite: usage["cache_creation_input_tokens"] as? Int ?? 0, reported: usage["input_tokens"] != nil)
        return ProviderResponse(tools: try ToolValidator.validate(ToolValidator.envelope(nativeCalls: calls), web: web), usage: value)
    }
}

public actor RecordedProvider: CompanionProvider {
    private var responses: [Data]; public private(set) var attempts = 0
    private let usage: Usage
    public init(responses: [Data], usage: Usage = Usage(input: 120, output: 30)) { self.responses = responses; self.usage = usage }
    public func signInState() async -> String { "Recorded (offline)" }
    public func models() async throws -> [ProviderModel] { [ProviderModel(id: "recorded", name: "Recorded fixture")] }
    public func send(_ r: CompanionRequest) async throws -> ProviderResponse {
        attempts += 1
        guard !responses.isEmpty else { throw CompanionError.unavailable("No recorded responses left.") }
        let data = responses.removeFirst()
        return ProviderResponse(tools: try ToolValidator.validate(data, web: r.input.web), usage: usage)
    }
}
