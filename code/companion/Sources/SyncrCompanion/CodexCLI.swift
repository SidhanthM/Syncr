import Foundation

/// Fallback when pi is absent, and for native subscription Web search. Codex owns the login.
/// User config, sessions, MCP/plugins/hooks, filesystem tools and agent spawning are disabled.
public struct CodexCLIProvider: CompanionProvider, Sendable {
    public let executable: String
    public init(executable: String = "/opt/homebrew/bin/codex") { self.executable = executable }
    public func signInState() async -> String {
        do { _ = try await CodexProvider.command(executable, ["login", "status"], timeout: 15); return "Signed in with Codex CLI" }
        catch { return "Sign in with codex login" }
    }
    private static let disabled = ["shell_tool", "unified_exec", "apps", "plugins", "hooks", "browser_use", "computer_use", "image_generation", "multi_agent", "view_image", "code_mode_host", "shell_snapshot", "workspace_dependencies", "tool_suggest", "sleep_tool", "unbounded_connection_retries"]
    private func arguments(_ r: CompanionRequest) -> [String] {
        var args = ["exec", "--json", "--ephemeral", "--ignore-user-config", "--ignore-rules", "--skip-git-repo-check", "--sandbox", "read-only", "--model", r.model, "-c", "web_search=\"\(r.input.web ? "live" : "disabled")\"", "-c", "model_reasoning_effort=\"low\"", "-c", "model_provider=\"openai\"", "-c", "check_for_update_on_startup=false", "-c", "project_doc_max_bytes=0", "-c", "model_providers.openai.request_max_retries=0", "-c", "model_stream_max_retries=0"]
        for feature in Self.disabled { args += ["--disable", feature] }
        return args
    }
    private func prompt(_ r: CompanionRequest) -> String { r.system + "\n" + ToolSchemas.strictJSONInstruction + "\n\n" + r.user }
    public func wirePreview(_ r: CompanionRequest) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: ["executable": executable, "arguments": arguments(r) + ["-"], "stdin": prompt(r), "pagePNG": r.input.pagePNG?.base64EncodedString() as Any? ?? NSNull()], options: [.sortedKeys, .prettyPrinted]), as: UTF8.self)
    }
    public func send(_ r: CompanionRequest) async throws -> ProviderResponse {
        let cwd = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-codex-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: cwd, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: cwd) }
        var args = arguments(r)
        if let image = r.input.pagePNG { let file = cwd.appendingPathComponent("page.png"); try image.write(to: file); args += ["--image", file.path] }
        args.append("-")
        let data = try await CodexProvider.command(executable, args, timeout: 180, input: Data(prompt(r).utf8), cwd: cwd)
        return try Self.decode(data, web: r.input.web)
    }
    public static func decode(_ data: Data, web: Bool) throws -> ProviderResponse {
        guard data.count <= 16 * 1024 * 1024 else { throw CompanionError.invalid("Codex output limit") }
        var final = ""; var usage = Usage(reported: false); var completed = false
        for line in data.split(separator: 10) {
            try StrictJSON.check(Data(line))
            guard let root = try JSONSerialization.jsonObject(with: line) as? [String: Any], let type = root["type"] as? String else { throw CompanionError.invalid("Codex event") }
            if type == "turn.failed" || type == "error" { throw CompanionError.unavailable("Codex CLI request failed.") }
            if type == "item.completed", let item = root["item"] as? [String: Any], item["type"] as? String == "agent_message" { final = item["text"] as? String ?? "" }
            if type == "turn.completed" {
                completed = true
                if let u = root["usage"] as? [String: Any] {
                    let cached = u["cached_input_tokens"] as? Int ?? 0
                    usage = Usage(input: max(0, (u["input_tokens"] as? Int ?? 0) - cached), output: u["output_tokens"] as? Int ?? 0, cacheRead: cached)
                }
            }
        }
        guard completed else { throw CompanionError.invalid("incomplete Codex turn") }
        return ProviderResponse(tools: try ToolValidator.validate(Data(final.utf8), web: web), usage: usage)
    }
    public func models() async throws -> [ProviderModel] {
        let executable = self.executable
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                let p = Process(); let input = Pipe(); let output = Pipe()
                do {
                    p.executableURL = URL(fileURLWithPath: executable); p.arguments = ["app-server", "--stdio", "--disable", "apps", "--disable", "plugins", "--disable", "hooks"]
                    p.currentDirectoryURL = FileManager.default.temporaryDirectory; p.standardInput = input; p.standardOutput = output; p.standardError = FileHandle.nullDevice
                    let timer = DispatchSource.makeTimerSource(); timer.schedule(deadline: .now() + 30); timer.setEventHandler { if p.isRunning { p.terminate() } }; timer.resume(); defer { timer.cancel() }
                    try p.run(); defer { try? input.fileHandleForWriting.close(); if p.isRunning { p.terminate() }; p.waitUntilExit() }
                    func send(_ object: [String: Any]) throws { try input.fileHandleForWriting.write(contentsOf: JSONSerialization.data(withJSONObject: object) + Data([10])) }
                    var pending = Data(); var received = 0
                    func response(_ id: Int) throws -> [String: Any] {
                        while true {
                            if let lf = pending.firstIndex(of: 10) {
                                let line = pending[..<lf]; pending.removeSubrange(...lf)
                                guard let r = try JSONSerialization.jsonObject(with: line) as? [String: Any] else { continue }
                                if r["id"] as? Int == id {
                                    guard r["error"] == nil, let result = r["result"] as? [String: Any] else { throw CompanionError.invalid("Codex catalog response") }
                                    return result
                                }
                            } else {
                                let chunk = output.fileHandleForReading.availableData
                                received += chunk.count
                                guard !chunk.isEmpty, received <= 4 * 1024 * 1024 else { throw CompanionError.invalid("Codex catalog stream") }; pending.append(chunk)
                            }
                        }
                    }
                    try send(["id": 1, "method": "initialize", "params": ["clientInfo": ["name": "syncr-companion", "version": "1"]]])
                    _ = try response(1); try send(["method": "initialized"])
                    var models: [ProviderModel] = []; var cursor: String?
                    for page in 0..<100 {
                        var params: [String: Any] = ["limit": 100, "includeHidden": true]; if let cursor { params["cursor"] = cursor }
                        try send(["id": page + 2, "method": "model/list", "params": params]); let r = try response(page + 2)
                        guard let rows = r["data"] as? [[String: Any]] else { throw CompanionError.invalid("Codex catalog rows") }
                        models += rows.compactMap { row in guard let id = row["model"] as? String else { return nil }; return ProviderModel(id: id, name: row["displayName"] as? String ?? id) }
                        guard let next = r["nextCursor"] as? String else { continuation.resume(returning: models); return }
                        guard next != cursor else { throw CompanionError.invalid("Codex catalog cursor") }; cursor = next
                    }
                    throw CompanionError.invalid("Codex catalog page limit")
                } catch { if p.isRunning { p.terminate() }; continuation.resume(throwing: error) }
            }
        }
    }
}
