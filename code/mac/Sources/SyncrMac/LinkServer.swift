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

    // The datagram path for preview frames: the same port number over UDP. Each datagram starts with a session's key.
    private var datagrams: NWListener?
    private let udpQueue = DispatchQueue(label: "dev.syncr.mac.link.udp", qos: .userInteractive)
    private var flows: [NWConnection] = []
    private let routes = OSAllocatedUnfairLock(initialState: [UInt64: @Sendable (Data, UInt64) -> Void]())
    private let udpReady = OSAllocatedUnfairLock(initialState: UInt16?.none)
    /// The UDP port, once it is listening; nil when it could not be opened (the link then runs as before).
    var udpPort: UInt16? { udpReady.withLock { $0 } }

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
                if let p = listener?.port?.rawValue { self?.port = p; self?.startDatagrams(on: p); self?.onListening?(p) }
            case .failed(let error): self?.onFailed?(error.localizedDescription)
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] nw in
            guard let self, let make = self.makeSession, self.live.withLock({ $0 < 16 }) else { nw.cancel(); return }
            self.live.withLock { $0 += 1 }
            self.counter += 1
            let connection = LinkConnection(nw, label: "\(self.counter)")
            let routes = self.routes
            let key = OSAllocatedUnfairLock(initialState: UInt64(0))
            connection.onEnded = { [live = self.live] in
                live.withLock { $0 -= 1 }
                let k = key.withLock { $0 }
                if k != 0 { routes.withLock { $0[k] = nil } }
            }
            connection.queue.async {
                connection.session = make(connection)
                let k = connection.session.config.udpKey
                if k != 0, connection.session.config.udpPort != nil {
                    key.withLock { $0 = k }
                    routes.withLock { $0[k] = { [weak connection] payload, ns in
                        connection?.queue.async { connection?.session.receiveDatagram(payload, at: ns) }
                    } }
                }
                connection.start()
            }
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    /// A failure here only means there is no second path. `datagrams` and `flows` belong to `udpQueue`.
    private func startDatagrams(on port: UInt16) { udpQueue.async { [self] in openDatagrams(on: port) } }

    private func openDatagrams(on port: UInt16) {
        guard datagrams == nil, let p = NWEndpoint.Port(rawValue: port) else { return }
        let params = NWParameters.udp
        params.allowLocalEndpointReuse = true
        params.serviceClass = .interactiveVideo
        if config.loopbackOnly { params.requiredInterfaceType = .loopback }
        guard let listener = try? NWListener(using: params, on: p) else { return }
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.udpReady.withLock { $0 = port }
            case .failed, .cancelled: self?.udpReady.withLock { $0 = nil }
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] flow in
            guard let self else { flow.cancel(); return }
            // One flow per phone address and port; a reconnecting phone starts another. Keep the newest few.
            self.flows.append(flow)
            while self.flows.count > 8 { self.flows.removeFirst().cancel() }
            flow.start(queue: self.udpQueue)
            self.receive(on: flow)
        }
        datagrams = listener
        listener.start(queue: udpQueue)
    }

    private func receive(on flow: NWConnection) {
        flow.receiveMessage { [weak self] data, _, _, error in
            guard let self, error == nil else { flow.cancel(); return }
            if let data, data.count > 8, data.count <= 1500 {
                let key = data.prefix(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
                let handler = self.routes.withLock { $0[key] }
                handler?(Data(data.dropFirst(8)), monotonicNs())
            }
            self.receive(on: flow)
        }
    }

    func stop() {
        listener?.cancel(); listener = nil; port = nil
        udpQueue.async { [self] in
            datagrams?.cancel(); datagrams = nil
            flows.forEach { $0.cancel() }; flows = []
        }
        udpReady.withLock { $0 = nil }
    }
}
