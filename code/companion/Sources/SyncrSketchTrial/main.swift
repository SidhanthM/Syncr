import Foundation
import SyncrCompanion

@main struct Trial {
    static func main() async throws {
        if CommandLine.arguments.contains("--rerender-recorded") {
            let root = URL(fileURLWithPath: CommandLine.arguments.last!).standardizedFileURL
            guard root.lastPathComponent == "sketch-trial" else { throw CompanionError.invalid("trial path") }
            for folder in try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
                let file = folder.appendingPathComponent("drawing.json")
                guard let data = try? Data(contentsOf: file), let body = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
                let validated = try ToolValidator.validate(ToolValidator.envelope(nativeCalls: [["name": "draw", "arguments": body]]))
                if case .draw(let drawing) = validated[0] { try SketchRenderer.contactSheet(drawing, at: folder) }
            }
            print("Rerendered validated recordings; no requests made."); return
        }
        guard CommandLine.arguments.contains("--live-manual") else { print("Requires --live-manual; no requests made."); return }
        let root = URL(fileURLWithPath: CommandLine.arguments.last!).standardizedFileURL
        guard root.lastPathComponent == "sketch-trial" else { fatalError("Pass tasks/sketch-trial as final argument") }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        if CommandLine.arguments.contains("--claude-smoke") {
            guard try ClaudeKeychain.read() != nil else { print("SKIP Claude smoke: no Syncr Claude Keychain item."); return }
            let marker = root.appendingPathComponent("claude-smoke.json")
            guard !FileManager.default.fileExists(atPath: marker.path) else { throw CompanionError.unavailable("Claude smoke already attempted.") }
            let entry = ["provider": "claude", "model": ClaudeProvider.readingDefault, "prompt": "Smoke: remain quiet.", "at": ISO8601DateFormatter().string(from: Date())]
            try JSONSerialization.data(withJSONObject: entry, options: [.sortedKeys]).write(to: marker)
            let response = try await ClaudeProvider().send(CompanionRequest(model: ClaudeProvider.readingDefault, input: RequestInput(markdown: "Smoke: remain quiet.", instruction: "Return only stay_quiet.")))
            try JSONEncoder().encode(response.usage).write(to: root.appendingPathComponent("claude-smoke-usage.json"))
            print("Claude smoke completed."); return
        }
        let provider = CodexProvider()
        let models = try await provider.models()
        guard let model = CodexProvider.newestSol(models) else { throw CompanionError.unavailable("No Sol model listed by pi.") }
        let ledger = root.appendingPathComponent("live-calls.jsonl")
        func log(_ entry: [String: Any]) throws {
            let bytes = try JSONSerialization.data(withJSONObject: entry, options: [.sortedKeys]) + Data([10])
            if !FileManager.default.fileExists(atPath: ledger.path) { FileManager.default.createFile(atPath: ledger.path, contents: nil) }
            let handle = try FileHandle(forWritingTo: ledger); defer { try? handle.close() }; try handle.seekToEnd(); try handle.write(contentsOf: bytes)
        }
        let existing = (try? String(contentsOf: ledger))?.split(separator: "\n").count ?? 0
        guard existing == 0 else { throw CompanionError.unavailable("Trial already started; do not accidentally repeat live calls.") }
        print("Model from pi: \(model)")
        let all = ["Smoke: remain quiet and return only stay_quiet."] + SketchTrial.prompts
        for (index, prompt) in all.enumerated() {
            try log(["index": index, "provider": "pi/openai-codex", "model": model, "prompt": prompt, "at": ISO8601DateFormatter().string(from: Date())])
            let folder = root.appendingPathComponent(String(format: "%02d", index))
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            do {
                let input = RequestInput(markdown: prompt, instruction: index == 0 ? prompt : "Use draw only, with 3 to 6 cumulative steps. Quote the entire document. " + prompt)
                let result = try await provider.send(CompanionRequest(model: model, input: input))
                try JSONEncoder().encode(result.usage).write(to: folder.appendingPathComponent("usage.json"))
                var drew = false
                for tool in result.tools {
                    if case .draw(let drawing) = tool {
                        try JSONEncoder().encode(drawing).write(to: folder.appendingPathComponent("drawing.json"))
                        try SketchRenderer.contactSheet(drawing, at: folder); drew = true
                    }
                }
                try (index == 0 ? "Smoke completed\n" : drew ? "Drawing rendered; owner judgment pending\n" : "No drawing returned\n").write(to: folder.appendingPathComponent("result.txt"), atomically: true, encoding: .utf8)
                print("\(index): \(drew ? "rendered" : "completed without drawing")")
            } catch {
                try String(describing: error).write(to: folder.appendingPathComponent("result.txt"), atomically: true, encoding: .utf8)
                print("\(index): \(error)")
            }
        }
    }
}
