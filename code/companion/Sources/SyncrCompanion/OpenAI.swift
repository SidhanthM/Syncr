import Foundation
import Security

public enum OpenAIKeychain {
    private static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "dev.syncr.companion.openai", kSecAttrAccount as String: "api-key"] }
    public static func read() throws -> String? {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?; let status = SecItemCopyMatching(q as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data, let key = String(data: data, encoding: .utf8) else { throw CompanionError.transport(Int(status)) }; return key
    }
    public static func set(_ key: String) throws {
        guard !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, key.utf8.count <= 4096, !key.contains("\n") else { throw CompanionError.invalid("key") }
        let data = Data(key.utf8); let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var q = query; q[kSecValueData as String] = data; q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let result = SecItemAdd(q as CFDictionary, nil); guard result == errSecSuccess else { throw CompanionError.transport(Int(result)) }
        } else if status != errSecSuccess { throw CompanionError.transport(Int(status)) }
    }
    public static func delete() throws { let status = SecItemDelete(query as CFDictionary); guard status == errSecSuccess || status == errSecItemNotFound else { throw CompanionError.transport(Int(status)) } }
}
public struct OpenAIProvider: CompanionProvider {
    private let transport: HTTPTransport; private let key: @Sendable () throws -> String?
    public init(transport: HTTPTransport = URLSessionTransport(), key: @escaping @Sendable () throws -> String? = { try OpenAIKeychain.read() }) { self.transport = transport; self.key = key }
    public func signInState() async -> String { ((try? key()) ?? nil) == nil ? "No OpenAI API key" : "OpenAI API key in Keychain" }
    private func request(_ path: String, body: Data? = nil) throws -> URLRequest {
        guard let key = try key() else { throw CompanionError.unavailable("Set an OpenAI API key in Settings.") }
        var r = URLRequest(url: URL(string: "https://api.openai.com/v1/" + path)!); r.httpMethod = body == nil ? "GET" : "POST"; r.httpBody = body
        r.setValue("Bearer " + key, forHTTPHeaderField: "authorization"); r.setValue("application/json", forHTTPHeaderField: "content-type"); return r
    }
    public func models() async throws -> [ProviderModel] {
        let (data, status) = try await transport.send(request("models")); guard status == 200 else { throw CompanionError.transport(status) }
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], let rows = root["data"] as? [[String: Any]] else { throw CompanionError.invalid("OpenAI models") }
        return rows.compactMap { row in (row["id"] as? String).map { ProviderModel(id: $0, name: $0) } }.sorted { $0.id < $1.id }
    }
    public static func streamingBody(_ r: StreamRequest, preview: Bool = false) throws -> Data {
        var content: [[String: Any]] = [["type": "input_text", "text": r.prompt]]
        for image in r.images { content.append(["type": "input_image", "image_url": preview ? image.summary : "data:\(image.mimeType);base64,\(image.data.base64EncodedString())"]) }
        var body: [String: Any] = ["model": r.model, "instructions": r.system, "input": [["role": "user", "content": content]], "stream": true, "store": false, "max_output_tokens": r.purpose == .gate ? 512 : 12000]
        if r.input.web { body["tools"] = [["type": "web_search"]] }
        return try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
    }
    public func streamPreview(_ r: StreamRequest) throws -> String {
        "POST https://api.openai.com/v1/responses\ncontent-type: application/json\nauthorization: [Keychain credential omitted]\n" + String(decoding: try Self.streamingBody(r, preview: true), as: UTF8.self)
    }
    public func stream(_ r: StreamRequest) -> AsyncThrowingStream<ProviderStreamEvent, Error> {
        do { return ProviderSSE.stream(transport: transport, request: try request("responses", body: Self.streamingBody(r)), route: .openAI) }
        catch { return AsyncThrowingStream { $0.finish(throwing: error) } }
    }
    public func wirePreview(_ r: CompanionRequest) throws -> String { try StreamRequest(model: r.model, input: r.input).wirePreview }
    public func send(_ r: CompanionRequest) async throws -> ProviderResponse {
        // Legacy callers use the same validated v2 stream, translated only for the v1-compatible items.
        let request = try StreamRequest(model: r.model, input: r.input); var parser = ItemStreamParser(input: r.input); var tools: [ValidatedTool] = []; var usage = Usage(reported: false)
        for try await event in stream(request) {
            switch event {
            case .usage(let value): usage = value
            case .text(let text): try parser.append(text) { event in
                guard case .item(let item) = event else { return }
                switch item {
                case .quiet: tools.append(.stayQuiet)
                case .margin(let quote, let kind, let text): tools.append(.marginNote(quote: quote, kind: kind, text: text, sources: []))
                case .reply(let id, let text): tools.append(.reply(threadID: id, text: text, sources: []))
                default: break
                }
            }
            }
        }
        try parser.finish { event in
            guard case .item(let item) = event else { return }
            switch item {
            case .quiet: tools.append(.stayQuiet)
            case .margin(let quote, let kind, let text): tools.append(.marginNote(quote: quote, kind: kind, text: text, sources: []))
            case .reply(let id, let text): tools.append(.reply(threadID: id, text: text, sources: []))
            default: break
            }
        }
        return ProviderResponse(tools: tools, usage: usage)
    }
}
