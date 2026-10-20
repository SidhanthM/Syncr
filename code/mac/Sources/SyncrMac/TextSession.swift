import Foundation

/// Retained across tabs: a failed write cannot discard what the user typed. Saving is asynchronous;
/// the callback is main-thread only. A pause coalesces to one changed-middle splice in the adapter.
@MainActor final class TextSession: TextDocument {
    private(set) var text: String
    let writable: Bool
    private(set) var savedText: String
    private(set) var saving = false
    private(set) var failure: String?
    var save: ((String, @escaping @MainActor (Result<Void, Error>) -> Void) -> Void)?
    var undo: ((Bool) -> Void)?
    var onState: (() -> Void)?
    private var waiters: [@MainActor (Bool) -> Void] = []
    private var again = false
    var dirty: Bool { !text.utf8.elementsEqual(savedText.utf8) }
    var status: String { failure.map { "Not saved: " + $0 } ?? (saving ? "Saving…" : dirty ? "Unsaved" : "Saved") }
    init(text: String, writable: Bool) { self.text = text; savedText = text; self.writable = writable }
    func replace(with value: String) {
        guard writable, !text.utf8.elementsEqual(value.utf8) else { return }
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
    func history(redo: Bool) { flush { [weak self] ok in if ok { self?.undo?(redo) } } }
}
