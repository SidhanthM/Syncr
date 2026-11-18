import Foundation

/// When typed text is committed (latency-v1 T.2). A throttle, not a debounce: the first keystroke after a quiet spell is
/// committed at once, then at most one commit per `minGap` while typing goes on, and no unsaved keystroke waits longer
/// than `maxWait`. All times in seconds on one clock; `lastCommit` 0 means never.
enum TypingThrottle {
    static let minGap = 0.15, maxWait = 0.25
    static func delay(now: Double, dirtySince: Double, lastCommit: Double) -> Double {
        max(0, min(lastCommit + minGap - now, dirtySince + maxWait - now))
    }
}

/// A main-queue timer the system does not coalesce or stretch (a typing deadline is 150 ms, so a late timer is felt). Runs
/// `work` on the main queue after `seconds` unless it was cancelled; at zero it just hops to the next turn.
enum StrictTimer {
    static func run(after seconds: Double, _ work: DispatchWorkItem) {
        guard seconds > 0 else { DispatchQueue.main.async(execute: work); return }
        let timer = DispatchSource.makeTimerSource(flags: .strict, queue: .main)
        timer.schedule(deadline: .now() + seconds, leeway: .nanoseconds(0))
        timer.setEventHandler { if !work.isCancelled { work.perform() }; timer.cancel() }
        timer.resume()
    }
}

/// Retained across tabs: a failed write cannot discard what the user typed. Saving is asynchronous;
/// the callback is main-thread only. A pause coalesces to one changed-middle splice in the adapter.
@MainActor final class TextSession: TextDocument {
    private(set) var text: String
    let writable: Bool
    private(set) var savedText: String
    private(set) var saving = false
    private(set) var failure: String?
    var save: ((String, @escaping @MainActor (Result<Void, Error>) -> Void) -> Void)?
    /// Undoes (or redoes) one step; `group` holds the operation IDs of the typing run being taken back, so the step covers all of
    /// them. The completion says whether the first target was in the group (it is not when something else came after the typing).
    var undo: ((Bool, Set<String>, @escaping @MainActor (Bool) -> Void) -> Void)?
    var onState: (() -> Void)?
    private var waiters: [@MainActor (Bool) -> Void] = []
    private var again = false
    /// Typing runs, newest last, each the IDs of the operations its commits made (oldest first). Text is committed every
    /// ~150 ms while typing goes on, but one undo step takes back the whole run, as one pause's worth did before.
    private var runs: [[String]] = [[]]
    private var redoRuns: [[String]] = []
    private var lastKeyAt = 0.0
    private var savingRun = 0
    /// Keystrokes closer together than this are one run.
    static var runGap = 0.3
    private static func uptime() -> Double { Double(DispatchTime.now().uptimeNanoseconds) / 1e9 }
    /// The save in flight made operation `id` (called by the writer before it reports back).
    func recordCommit(_ id: String) { if savingRun < runs.count { runs[savingRun].append(id) } }
    var dirty: Bool { !text.utf8.elementsEqual(savedText.utf8) }
    var status: String { failure.map { "Not saved: " + $0 } ?? (saving ? "Saving…" : dirty ? "Unsaved" : "Saved") }
    init(text: String, writable: Bool) { self.text = text; savedText = text; self.writable = writable }
    func replace(with value: String) {
        guard writable, !text.utf8.elementsEqual(value.utf8) else { return }
        let now = Self.uptime()
        if now - lastKeyAt > Self.runGap, !(runs.last ?? []).isEmpty { runs.append([]) }
        lastKeyAt = now; redoRuns = []
        text = value; failure = nil; onState?()
    }
    func adopt(_ value: String) {
        guard !dirty, !saving, !text.utf8.elementsEqual(value.utf8) else { return }
        text = value; savedText = value; failure = nil; onState?()
    }
    func flush() { flush(done: nil) }
    func flush(done: (@MainActor (Bool) -> Void)?) {
        if let done { waiters.append(done) }
        if saving { again = true; return }
        guard dirty else { finish(true); return }
        guard writable, let save else { finish(false); return }
        let submitted = text
        savingRun = runs.count - 1
        saving = true; onState?()
        save(submitted) { [weak self] result in
            guard let self else { return }
            self.saving = false
            switch result {
            case .success:
                self.savedText = submitted; self.failure = nil
                let repeatSave = self.again; self.again = false
                if self.dirty && (repeatSave || !self.waiters.isEmpty) { self.flush(); return }
                if !self.dirty { self.finish(true) }
            case .failure(let error): self.failure = error.localizedDescription; self.again = false; self.finish(false)
            }
            self.onState?()
        }
    }
    private func finish(_ ok: Bool) { let callbacks = waiters; waiters = []; callbacks.forEach { $0(ok) } }
    func history(redo: Bool) {
        flush { [weak self] ok in
            guard let self, ok else { return }
            let group = Set(redo ? self.redoRuns.last ?? [] : self.runs.last { !$0.isEmpty } ?? [])
            self.undo?(redo, group) { [weak self] matched in
                guard let self, matched, !group.isEmpty else { return }
                // The run moves to the other stack, and what is typed next starts a run of its own.
                if redo, let run = self.redoRuns.popLast() { self.runs.append(run) }
                else if !redo, let at = self.runs.lastIndex(where: { !$0.isEmpty }) { self.redoRuns.append(self.runs.remove(at: at)) }
                if self.runs.isEmpty || !(self.runs.last ?? []).isEmpty { self.runs.append([]) }
                self.lastKeyAt = 0
            }
        }
    }
}
