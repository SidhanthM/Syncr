import Foundation
import Darwin

/// No credentials are read: pi owns OAuth, refresh, and transport. The child has no tools,
/// extensions, MCP, repository context or persisted session. JSON text is validated like native calls.
public struct CodexProvider: CompanionProvider, Sendable {
    public let executable: String
    private let streamingRPC: PersistentCodexRPC
    public init(executable: String = CodexProvider.locatePi(), trace: (@Sendable (String) -> Void)? = nil) { self.executable = executable; streamingRPC = PersistentCodexRPC(executable: executable, trace: trace) }
    public func stream(_ request: StreamRequest) -> AsyncThrowingStream<ProviderStreamEvent, Error> {
        if request.input.web || !FileManager.default.isExecutableFile(atPath: executable) { return CodexCLIProvider().stream(request) }
        return streamingRPC.stream(request)
    }
    public func cancelStream() async { await streamingRPC.cancelCurrent() }
    public func shutdown() async { await streamingRPC.shutdown() }
    public func streamPreview(_ request: StreamRequest) throws -> String {
        if request.input.web || !FileManager.default.isExecutableFile(atPath: executable) { return try CodexCLIProvider().streamPreview(request) }
        let object: [String: Any] = ["executable": executable, "mode": "rpc", "persistent": true, "noSession": true, "tools": [], "extensions": [], "mcp": false, "systemPrompt": PersistentCodexRPC.system, "resetPerPass": "new_session", "provider": "openai-codex", "model": request.model, "thinking": "low", "autoRetry": false, "autoCompaction": false, "prompt": request.system + "\n\n" + request.prompt, "images": request.images.map(\.summary)]
        return String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .prettyPrinted]), as: UTF8.self)
    }
    public static func locatePi() -> String {
        let paths = (ProcessInfo.processInfo.environment["PATH"] ?? "/usr/local/bin:/opt/homebrew/bin:/usr/bin").split(separator: ":").map(String.init) + [FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".local/bin").path]
        return paths.map { $0 + "/pi" }.first { FileManager.default.isExecutableFile(atPath: $0) } ?? "/opt/homebrew/bin/pi"
    }
    public func signInState() async -> String {
        if !FileManager.default.isExecutableFile(atPath: executable) { return await CodexCLIProvider().signInState() }
        do {
            let data = try await Self.command(executable, ["auth", "check", "--provider", "openai-codex", "--no-refresh"], timeout: 30)
            return String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines) == "ready" ? "Signed in with Codex (pi)" : "Sign in with pi /login"
        } catch { return "Sign in with pi /login" }
    }
    public func models() async throws -> [ProviderModel] {
        if !FileManager.default.isExecutableFile(atPath: executable) { return try await CodexCLIProvider().models() }
        let data = try await rpc(commands: [["id": "models", "type": "get_available_models"]], until: "models", model: nil)
        guard let result = data.last, let root = result["data"] as? [String: Any], let rows = root["models"] as? [[String: Any]] else { throw CompanionError.invalid("pi model list") }
        return rows.filter { $0["provider"] as? String == "openai-codex" }.compactMap { row in
            guard let id = row["id"] as? String else { return nil }
            let cost = row["cost"] as? [String: Any]
            return ProviderModel(id: id, name: row["name"] as? String ?? id, inputCost: cost?["input"] as? Double, outputCost: cost?["output"] as? Double)
        }
    }
    public static func newestSol(_ models: [ProviderModel]) -> String? {
        models.filter { $0.id.lowercased().contains("sol") }.sorted { $0.id.compare($1.id, options: .numeric) == .orderedDescending }.first?.id
    }
    public func wirePreview(_ r: CompanionRequest) throws -> String {
        if r.input.web || !FileManager.default.isExecutableFile(atPath: executable) { return try CodexCLIProvider().wirePreview(r) }
        var prompt: [String: Any] = ["id": "ask", "type": "prompt", "message": r.user]
        if !r.input.attachments.isEmpty { prompt["images"] = r.input.attachments.map { ["type": "image", "mimeType": $0.mimeType, "data": $0.summary] } }
        let object: [String: Any] = ["executable": executable, "provider": "openai-codex", "model": r.model, "thinking": "low", "noSession": true, "tools": [], "extensions": [], "mcp": false, "systemPrompt": r.system + "\n" + ToolSchemas.strictJSONInstruction, "stdin": [["id": "retry", "type": "set_auto_retry", "enabled": false], ["id": "compact", "type": "set_auto_compaction", "enabled": false], prompt]]
        return String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .prettyPrinted]), as: UTF8.self)
    }
    public func send(_ r: CompanionRequest) async throws -> ProviderResponse {
        if r.input.web || !FileManager.default.isExecutableFile(atPath: executable) { return try await CodexCLIProvider().send(r) }
        var prompt: [String: Any] = ["id": "ask", "type": "prompt", "message": r.user]
        if !r.input.attachments.isEmpty { prompt["images"] = r.input.attachments.map { ["type": "image", "mimeType": $0.mimeType, "data": $0.data.base64EncodedString()] } }
        let records = try await rpc(commands: [["id": "retry", "type": "set_auto_retry", "enabled": false], ["id": "compact", "type": "set_auto_compaction", "enabled": false], prompt], until: nil, model: r.model, system: r.system + "\n" + ToolSchemas.strictJSONInstruction)
        var text = ""; var total = Usage()
        for record in records where record["type"] as? String == "message_end" {
            guard let message = record["message"] as? [String: Any], message["role"] as? String == "assistant" else { continue }
            guard message["stopReason"] as? String != "error", message["stopReason"] as? String != "aborted" else { throw CompanionError.unavailable("Codex request failed; check pi sign-in and model availability.") }
            if let blocks = message["content"] as? [[String: Any]] { text += blocks.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined() }
            if let usage = message["usage"] as? [String: Any] {
                total.input += usage["input"] as? Int ?? 0; total.output += usage["output"] as? Int ?? 0
                total.cacheRead += usage["cacheRead"] as? Int ?? 0; total.cacheWrite += usage["cacheWrite"] as? Int ?? 0
            } else { total.reported = false }
        }
        return ProviderResponse(tools: try ToolValidator.validate(Data(text.utf8), web: false), usage: total)
    }
    public static func command(_ executable: String, _ arguments: [String], timeout: TimeInterval, input: Data? = nil, cwd: URL? = nil) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                do {
                    let process = Process(); process.executableURL = URL(fileURLWithPath: executable); process.arguments = arguments; process.currentDirectoryURL = cwd
                    let stdin = Pipe(); process.standardInput = stdin
                    _ = fcntl(stdin.fileHandleForWriting.fileDescriptor, F_SETNOSIGPIPE, 1)
                    let output = Pipe(); process.standardOutput = output; process.standardError = FileHandle.nullDevice
                    let timer = DispatchSource.makeTimerSource(); timer.schedule(deadline: .now() + timeout)
                    timer.setEventHandler { if process.isRunning { process.terminate() } }; timer.resume(); defer { timer.cancel() }
                    try process.run()
                    if let input { try stdin.fileHandleForWriting.write(contentsOf: input) }; try stdin.fileHandleForWriting.close()
                    var data = Data()
                    while true {
                        let chunk = output.fileHandleForReading.availableData; if chunk.isEmpty { break }; data.append(chunk)
                        guard data.count <= 16 * 1024 * 1024 else { process.terminate(); throw CompanionError.invalid("subprocess output limit") }
                    }
                    process.waitUntilExit()
                    guard process.terminationStatus == 0 else { throw CompanionError.transport(Int(process.terminationStatus)) }
                    continuation.resume(returning: data)
                } catch { continuation.resume(throwing: error) }
            }
        }
    }
    private func rpc(commands: [[String: Any]], until responseID: String?, model: String?, system: String = "You are Syncr.") async throws -> [[String: Any]] {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                let process = Process(); let stdin = Pipe(); let stdout = Pipe()
                _ = fcntl(stdin.fileHandleForWriting.fileDescriptor, F_SETNOSIGPIPE, 1)
                let cwd = FileManager.default.temporaryDirectory.appendingPathComponent("syncr-companion-" + UUID().uuidString)
                do {
                    try FileManager.default.createDirectory(at: cwd, withIntermediateDirectories: true)
                    defer { try? FileManager.default.removeItem(at: cwd) }
                    process.executableURL = URL(fileURLWithPath: executable); process.currentDirectoryURL = cwd
                    process.arguments = ["--mode", "rpc", "--no-session", "--no-tools", "--no-extensions", "--no-mcp", "--no-skills", "--no-prompt-templates", "--no-themes", "--no-context-files", "--offline", "--system-prompt", system]
                    if let model { process.arguments! += ["--provider", "openai-codex", "--model", model, "--thinking", "low"] }
                    process.standardInput = stdin; process.standardOutput = stdout; process.standardError = FileHandle.nullDevice
                    let timer = DispatchSource.makeTimerSource(); timer.schedule(deadline: .now() + 180)
                    timer.setEventHandler { if process.isRunning { process.terminate() } }; timer.resume(); defer { timer.cancel() }
                    try process.run()
                    defer { try? stdin.fileHandleForWriting.close(); if process.isRunning { process.terminate() }; process.waitUntilExit() }
                    for command in commands {
                        var data = try JSONSerialization.data(withJSONObject: command, options: [.sortedKeys]); data.append(10)
                        try stdin.fileHandleForWriting.write(contentsOf: data)
                    }
                    var pending = Data(); var records: [[String: Any]] = []; var size = 0; var done = false
                    while !done {
                        let chunk = stdout.fileHandleForReading.availableData
                        guard !chunk.isEmpty else { throw CompanionError.unavailable("pi exited before completing the request.") }
                        size += chunk.count
                        guard size <= 16 * 1024 * 1024 else { throw CompanionError.invalid("pi stream limit") }
                        pending.append(chunk)
                        while let lf = pending.firstIndex(of: 10) {
                            let line = pending[..<lf]; pending.removeSubrange(...lf)
                            guard !line.isEmpty, let record = try JSONSerialization.jsonObject(with: line) as? [String: Any] else { continue }
                            if record["type"] as? String == "response", record["success"] as? Bool == false { throw CompanionError.unavailable("pi rejected a companion command.") }
                            if record["type"] as? String == "message_end" || (responseID != nil && record["id"] as? String == responseID) { records.append(record) }
                            if responseID != nil && record["id"] as? String == responseID { done = true }
                            if responseID == nil && record["type"] as? String == "agent_settled" { done = true }
                        }
                    }
                    continuation.resume(returning: records)
                } catch { if process.isRunning { process.terminate() }; continuation.resume(throwing: error) }
            }
        }
    }
}
