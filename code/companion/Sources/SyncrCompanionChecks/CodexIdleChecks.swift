import Foundation
import Darwin
import SyncrCompanion

/// Real owned subprocesses, but only the offline Python protocol fixture (no pi/auth/network).
func codexIdleChecks(tmp: URL) async throws {
    let fixture = Bundle.module.url(forResource: "pi-stream", withExtension: "py", subdirectory: "Fixtures")!
    let script = tmp.appendingPathComponent("pi-idle.py")
    // A bounded slow pass completes after the injected idle timeout, unlike the cancellation fixture.
    let code = try String(contentsOf: fixture, encoding: .utf8)
        .replacingOccurrences(of: "time.sleep(30)", with: "time.sleep(.35)")
        .replacingOccurrences(of: "history = []\nfor line", with: """
        with open(os.path.join(os.path.dirname(__file__), 'idle-children.jsonl'), 'a') as log:
            log.write(json.dumps({'pid':os.getpid(), 'cwd':os.getcwd()}) + '\\n')
        history = []
        for line
        """)
    try code.write(to: script, atomically: true, encoding: .utf8)
    try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: script.path)
    let rpc = PersistentCodexRPC(executable: script.path, idleTimeout: 0.2)
    let request = try StreamRequest(model: "fixture", input: RequestInput(markdown: "cache"))
    let slow = try StreamRequest(model: "fixture", input: RequestInput(markdown: "cache", instruction: "SIMULATE_SLOW"))
    func collect(_ stream: AsyncThrowingStream<ProviderStreamEvent, Error>) async throws -> Int32 {
        var pid: Int32 = 0
        for try await event in stream { if case .usage(let usage) = event { pid = Int32(usage.input) } }
        check(pid > 0, "idle fixture reports its PID"); return pid
    }
    func gone(_ pid: Int32) -> Bool { kill(pid, 0) == -1 && errno == ESRCH }

    let first = try await collect(rpc.stream(request))
    let stale = rpc.capturedIdleRetirement()!
    let active = rpc.stream(slow) // Admission invalidates idle ownership synchronously.
    stale() // Simulate an already-dequeued old callback, even though its DispatchWorkItem was cancelled.
    let activeReader = Task { try await collect(active) }
    try await Task.sleep(nanoseconds: 280_000_000)
    check(!gone(first), "active child survives beyond the idle timeout")
    let afterActive = try await activeReader.value
    check(afterActive == first, "active/new request was not evicted")
    stale() // Now idle again, but a different timer generation owns it.
    let reused = try await collect(rpc.stream(request))
    check(reused == first, "stale timer cannot retire a newly idle generation")
    let replacementStale = rpc.capturedIdleRetirement()!
    try await Task.sleep(nanoseconds: 450_000_000)
    check(gone(first), "idle child terminated and reaped")
    let restarted = try await collect(rpc.stream(request))
    check(restarted != first, "next request lazily starts a replacement child")
    replacementStale()
    let survived = try await collect(rpc.stream(request))
    check(survived == restarted, "stale timer cannot terminate replacement child")

    let cancelledStream = rpc.stream(slow)
    let cancelReader = Task { () -> Bool in
        do { _ = try await collect(cancelledStream); return false } catch { return true }
    }
    try await Task.sleep(nanoseconds: 50_000_000)
    await rpc.cancelCurrent()
    let cancelled = await cancelReader.value
    check(cancelled, "cancel interrupts the active idle-managed child")
    try await Task.sleep(nanoseconds: 50_000_000)
    check(gone(restarted) && rpc.capturedIdleRetirement() == nil, "cancel reaps child and leaves no idle timer")
    let final = try await collect(rpc.stream(request))
    let shutdownStale = rpc.capturedIdleRetirement()!
    await rpc.shutdown()
    try await Task.sleep(nanoseconds: 50_000_000)
    shutdownStale()
    check(gone(final) && rpc.capturedIdleRetirement() == nil, "shutdown cleans child and timer")

    // Shutdown immediately after admission, including the start/publication race.
    let early = rpc.stream(slow)
    let earlyReader = Task { () -> Bool in
        do { _ = try await collect(early); return false } catch { return true }
    }
    await rpc.shutdown()
    let stoppedEarly = await earlyReader.value
    check(stoppedEarly && rpc.capturedIdleRetirement() == nil, "shutdown before start cannot leave a replacement child")
    let children = try String(contentsOf: tmp.appendingPathComponent("idle-children.jsonl"), encoding: .utf8)
    for line in children.split(separator: "\n") {
        let record = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
        check(gone(Int32(record["pid"] as! Int)), "all owned children reaped")
        check(!FileManager.default.fileExists(atPath: record["cwd"] as! String), "owned temporary cwd removed")
    }
    print("PASS Codex idle: active survives; idle children 1 -> 0; lazy restart; stale timer/generation/replacement; cancel/shutdown/start-race cleanup, reaped children/removed cwd")
}
