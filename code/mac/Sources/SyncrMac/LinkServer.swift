import Foundation
import Network
import os

// The thin Network.framework shell around `LinkSession`: a TCP listener with Bonjour, and one
// connection object per phone with its own high-priority serial queue. Bytes in go to the frame
// decoder and from there to the session; live frames reach `LiveInk.receive` from this queue
// without a hop through the main thread. No protocol decisions are made here.

final class LinkConnection: @unchecked Sendable {
    let queue: DispatchQueue
    private let nw: NWConnection
    private var decoder = LinkFrameDecoder()
    private var timer: DispatchSourceTimer?
    private var closing = false
    /// Set before `start()`.
    var session: LinkSession!
    /// Called once when the connection is over (peer closed, failed or cancelled).
    var onEnded: (@Sendable () -> Void)?
    private var reported = false

    init(_ nw: NWConnection, label: String) {
        self.nw = nw
        queue = DispatchQueue(label: "dev.syncr.mac.link.\(label)", qos: .userInteractive)
    }

    func start() {
        nw.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .failed, .cancelled: self.ended()
            default: break
            }
        }
        nw.start(queue: queue)
        session.start(atNs: monotonicNs())
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 0.5, repeating: 0.5)
        t.setEventHandler { [weak self] in self?.session.tick(atNs: monotonicNs()) }
        t.resume()
        timer = t
        receiveLoop()
    }

    private func receiveLoop() {
        nw.receive(minimumIncompleteLength: 1, maximumLength: LinkLimits.maxMessage + 4) { [weak self] data, _, complete, error in
            guard let self, !self.closing else { return }
            if let data, !data.isEmpty {
                self.decoder.append(data)
                do {
                    while let frame = try self.decoder.next() {
                        self.session.receive(frame, at: monotonicNs())
                        if self.closing { return }
                    }
                } catch {
                    self.session.protocolError(error)
                    return
                }
            }
            if complete || error != nil { self.ended(); return }
            self.receiveLoop()
        }
    }

    /// Queued behind everything already sent, so a final `bye` goes out first.
    func send(_ framed: Data) {
        guard !closing else { return }
        nw.send(content: framed, completion: .contentProcessed { _ in })
    }

    /// A file chunk: `done` runs on the connection's queue once the socket has taken it, so the next control or preview
    /// message queues behind at most one chunk.
    func sendBulk(_ framed: Data, done: @escaping @Sendable () -> Void) {
        guard !closing else { return }
        nw.send(content: framed, completion: .contentProcessed { [queue] _ in queue.async { done() } })
    }

    func close() {
        guard !closing else { return }
        closing = true
        timer?.cancel(); timer = nil
        let nw = nw
        nw.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in nw.cancel() })
        // A peer that never reads must not keep the socket forever.
        queue.asyncAfter(deadline: .now() + 2) { nw.cancel() }
    }

    private func ended() {
        closing = true
        timer?.cancel(); timer = nil
        // A connection the peer closed must still be cancelled: the listener's connection limit counts it until then.
        nw.cancel()
        if !reported { reported = true; onEnded?() }
        session.connectionClosed()
    }
}

final class LinkServer: @unchecked Sendable {
    struct Config: Sendable {
        /// nil: any free port.
        var port: UInt16?
        /// Bind to the loopback interface only (checks). The app binds per the contract: any interface.
        var loopbackOnly = false
        var advertise = true
        var macName: String
        var macId: String
    }

    private let config: Config
    private let queue = DispatchQueue(label: "dev.syncr.mac.link.listener", qos: .userInitiated)
    private var listener: NWListener?
    private var counter = 0
    /// Open connections; the listener's own limit is not released reliably, so this one is (16 at most).
    private let live = OSAllocatedUnfairLock(initialState: 0)
    /// Called on the listener queue with the port once it is listening.
    var onListening: (@Sendable (UInt16) -> Void)?
    var onFailed: (@Sendable (String) -> Void)?
    /// Builds the session for a new connection (on the connection's queue). The connection is started afterwards.
    var makeSession: (@Sendable (LinkConnection) -> LinkSession)?
    private(set) var port: UInt16?

    init(config: Config) { self.config = config }

    func start() throws {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 10
        let params = NWParameters(tls: nil, tcp: tcp)
        params.allowLocalEndpointReuse = true
        if config.loopbackOnly { params.requiredInterfaceType = .loopback }
        let listener: NWListener
        if let port = config.port, let p = NWEndpoint.Port(rawValue: port) { listener = try NWListener(using: params, on: p) }
        else { listener = try NWListener(using: params) }
        if config.advertise {
            listener.service = NWListener.Service(name: config.macName, type: "_syncr._tcp", domain: nil,
                                                  txtRecord: NWTXTRecord(["v": "1", "name": config.macName, "id": config.macId]))
        }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            switch state {
            case .ready:
                if let p = listener?.port?.rawValue { self?.port = p; self?.onListening?(p) }
            case .failed(let error): self?.onFailed?(error.localizedDescription)
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] nw in
            guard let self, let make = self.makeSession, self.live.withLock({ $0 < 16 }) else { nw.cancel(); return }
            self.live.withLock { $0 += 1 }
            self.counter += 1
            let connection = LinkConnection(nw, label: "\(self.counter)")
            connection.onEnded = { [live = self.live] in live.withLock { $0 -= 1 } }
            connection.queue.async {
                connection.session = make(connection)
                connection.start()
            }
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    func stop() {
        listener?.cancel(); listener = nil; port = nil
    }
}
