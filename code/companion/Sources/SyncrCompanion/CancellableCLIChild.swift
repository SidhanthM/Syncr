import Foundation

final class CancellableCLIChild: @unchecked Sendable {
    private let lock = NSLock(); private var process: Process?; private var cancelled = false
    func launch(_ child: Process) throws {
        lock.lock(); defer { lock.unlock() }
        guard !cancelled else { throw CancellationError() }
        try child.run(); process = child
    }
    func cancel() {
        lock.lock(); cancelled = true; let child = process; lock.unlock()
        if let child, child.isRunning { child.terminate() }
    }
}
