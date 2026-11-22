import AppKit
// Accessibility exposes legacy C globals without Swift concurrency annotations.
@preconcurrency import ApplicationServices

/// One frame of a trackpad-style scroll: whole pixels, with the phase apps use to tell a gesture's start and end.
struct ScrollFrame: Equatable {
    enum Phase: Int64 { case began = 1, changed = 2, ended = 4 }  // kCGScrollPhase*
    var phase: Phase
    var dx: Int32
    var dy: Int32
}

/// Turns the phone's per-frame scroll deltas into began, changed, ended frames. Sub-pixel remainders carry between
/// frames inside one gesture and are dropped at its end. A gesture that goes quiet is ended by `tick`, so a lost end
/// packet cannot leave an app waiting in mid-scroll. `natural` is asked once per gesture.
final class ScrollEmitter {
    static let idle = 0.15
    private let emit: (ScrollFrame) -> Void
    private let natural: () -> Bool
    private var remainder = CGPoint.zero
    private var sign: CGFloat = 1
    private var lastInput = 0.0
    /// A began frame has gone out and no ended frame has.
    private(set) var active = false
    private var pending = false

    init(natural: @escaping () -> Bool, emit: @escaping (ScrollFrame) -> Void) { self.natural = natural; self.emit = emit }

    /// Finger down the pad (positive dy) moves the page down, as on a Mac trackpad with natural scrolling.
    static func systemNatural() -> Bool { UserDefaults.standard.object(forKey: "com.apple.swipescrolldirection") as? Bool ?? true }

    func add(sx: Double, sy: Double, now: Double) {
        if !pending && !active { pending = true; remainder = .zero; sign = natural() ? 1 : -1 }
        lastInput = now
        remainder.x += CGFloat(sx) * sign; remainder.y += CGFloat(sy) * sign
        let x = Int32(remainder.x.rounded(.towardZero)), y = Int32(remainder.y.rounded(.towardZero))
        guard x != 0 || y != 0 else { return }
        remainder.x -= CGFloat(x); remainder.y -= CGFloat(y)
        emit(ScrollFrame(phase: active ? .changed : .began, dx: x, dy: y))
        active = true
    }

    func end() {
        if active { emit(ScrollFrame(phase: .ended, dx: 0, dy: 0)) }
        active = false; pending = false; remainder = .zero
    }

    func tick(now: Double) { if (active || pending) && now - lastInput > Self.idle { end() } }
}

/// Which phones may take Trackpad control up again without the consent sheet: only one whose approved session ended because the
/// link dropped (not because the phone or the owner turned it off), only for `window` seconds after that, and once. Pure, so it is
/// checked without a screen. The Mac still requires Accessibility, and every button is released when the session ends.
struct PointerResume {
    static let window: Double = 30
    private var until: [String: Double] = [:]

    mutating func dropped(phone: String, at now: Double) { until[phone] = now + Self.window }
    mutating func forget(phone: String) { until[phone] = nil }
    mutating func forgetAll() { until = [:] }
    /// True once for a phone that dropped less than `window` seconds ago.
    mutating func take(phone: String, at now: Double) -> Bool {
        guard let limit = until.removeValue(forKey: phone) else { return false }
        return now <= limit
    }
}

/// Only the explicitly approved paired connection may inject pointer events. No keyboard input.
@MainActor final class RemotePointer {
    private var resume = PointerResume()
    private weak var owner: LinkSession?
    private weak var pending: LinkSession?
    private var consent: NSAlert?
    private var consentID = UUID()
    private var leftDown = false
    private var dragClickCount = 1
    private lazy var scroll = ScrollEmitter(natural: ScrollEmitter.systemNatural) { [weak self] frame in self?.postScroll(frame) }
    /// Replaced by the checks so scroll frames can be read instead of posted.
    var postScroll: (ScrollFrame) -> Void = { RemotePointer.post($0) }
    private var sequence: UInt64 = 0
    private var lastInput = CACurrentMediaTime()
    private var timer: Timer?

    func request(_ session: LinkSession, version: Int, on: Bool) {
        guard version == 1 else { report(session, false, "Update both apps: unsupported pointer version."); return }
        if !on {
            if let id = session.phoneId { resume.forget(phone: id) }
            stop(session); report(session, false, "Trackpad is off."); return
        }
        if owner === session { report(session, true, "Trackpad connected"); return }
        if pending === session { return }
        // The link dropped under an approved session a moment ago and the same phone is back: no sheet, buttons all released.
        if owner == nil, pending == nil, let id = session.phoneId, resume.take(phone: id, at: CACurrentMediaTime()), AXIsProcessTrusted() {
            grant(session); return
        }
        guard owner == nil && pending == nil else { report(session, false, "Another phone controls the Mac."); return }
        pending = session
        let requestID = UUID(); consentID = requestID
        report(session, false, "Allow Trackpad control on the Mac.")
        let alert = NSAlert()
        alert.messageText = "Allow \(session.phoneName) to control the pointer?"
        alert.informativeText = "This paired phone will move the cursor, click and scroll across Mac apps over Wi-Fi. No keyboard access. Use only on trusted Wi-Fi: the existing Mac link is not encrypted. Leaving Trackpad or disconnecting stops control."
        alert.addButton(withTitle: "Allow")
        alert.addButton(withTitle: "Not now")
        consent = alert
        let finish: @MainActor @Sendable (NSApplication.ModalResponse) -> Void = { [weak self, weak session] response in
            guard let self, let session, self.pending === session, self.consentID == requestID else { return }
            self.pending = nil; self.consent = nil
            guard response == .alertFirstButtonReturn else { self.report(session, false, "Mac declined Trackpad control."); return }
            guard AXIsProcessTrusted() else {
                let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
                _ = AXIsProcessTrustedWithOptions(options)
                self.report(session, false, "Enable Syncr in Mac System Settings → Privacy & Security → Accessibility, then retry Trackpad.")
                return
            }
            self.grant(session)
        }
        if let window = NSApp.keyWindow ?? NSApp.mainWindow { alert.beginSheetModal(for: window, completionHandler: finish) }
        else { finish(alert.runModal()) }
    }

    private func grant(_ session: LinkSession) {
        owner = session; sequence = 0; lastInput = CACurrentMediaTime()
        report(session, true, "Trackpad connected")
        let timer = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkLease() }
        }
        self.timer = timer; RunLoop.main.add(timer, forMode: .common)
    }

    func receive(_ incoming: PointerPacket, from session: LinkSession) {
        guard owner === session, incoming.seq > sequence else { return }
        // A packet that sat in the socket through a Mac stall keeps only what is safe late: a release, the end of a scroll.
        let packet = incoming.droppingLateMotion(buttonDown: leftDown)
        guard AXIsProcessTrusted() else { stop(session); report(session, false, "Accessibility permission was removed."); return }
        sequence = packet.seq; lastInput = CACurrentMediaTime()
        // Moving, pressing or clicking is never part of a scroll: close it first so the page settles before the cursor acts.
        if packet.dx != 0 || packet.dy != 0 || packet.buttons != 0 || packet.click != 0 { scroll.end() }
        let source = CGEventSource(stateID: .hidSystemState)
        let current = CGEvent(source: nil)?.location ?? .zero
        let wanted = CGPoint(x: current.x + packet.dx, y: current.y + packet.dy)
        let point = nearestScreenPoint(wanted)
        if packet.dx != 0 || packet.dy != 0 {
            mouse(leftDown ? .leftMouseDragged : .mouseMoved, point, .left, source: source, dx: packet.dx, dy: packet.dy)
        }
        let pressed = packet.buttons == 1
        let wasDown = leftDown
        if pressed != leftDown {
            if pressed { dragClickCount = packet.click == 3 ? 2 : 1 }
            mouse(pressed ? .leftMouseDown : .leftMouseUp, point, .left, source: source, count: dragClickCount)
            leftDown = pressed
        }
        if !leftDown && !wasDown && packet.click != 0 {
            let button: CGMouseButton = packet.click == 2 ? .right : .left
            let count = packet.click == 3 ? 2 : 1
            for i in 1...count {
                mouse(button == .right ? .rightMouseDown : .leftMouseDown, point, button, source: source, count: i)
                mouse(button == .right ? .rightMouseUp : .leftMouseUp, point, button, source: source, count: i)
            }
        }
        if packet.sx != 0 || packet.sy != 0 { scroll.add(sx: packet.sx, sy: packet.sy, now: lastInput) }
        if packet.scrollEnd { scroll.end() }
    }

    /// Ends the session and releases every button and scroll. `resumable`: the link dropped under it, so the same phone may take it
    /// up again for a short while without asking.
    func stop(_ session: LinkSession, resumable: Bool = false) {
        if resumable, owner === session, let id = session.phoneId { resume.dropped(phone: id, at: CACurrentMediaTime()) }
        if pending === session {
            pending = nil; consentID = UUID()
            if let window = consent?.window {
                if let parent = window.sheetParent { parent.endSheet(window, returnCode: .cancel) }
                else if window.isVisible { NSApp.abortModal(); window.orderOut(nil) }
            }
            consent = nil
        }
        guard owner === session else { return }
        scroll.end(); releaseButton(); owner = nil; timer?.invalidate(); timer = nil
    }

    func forget(phone: String) { resume.forget(phone: phone) }

    func stopAll() {
        resume.forgetAll()
        if let pending { stop(pending) }
        if let owner { stop(owner) }
        releaseButton()
    }

    private func checkLease() {
        guard let owner else { timer?.invalidate(); timer = nil; scroll.end(); releaseButton(); return }
        scroll.tick(now: CACurrentMediaTime())
        if leftDown && CACurrentMediaTime() - lastInput > 1.2 {
            stop(owner)
            report(owner, false, "Trackpad paused after an input timeout. Retry to continue.")
        }
    }

    /// A continuous (pixel) scroll event at the cursor, phased like a real trackpad's so apps scroll smoothly and settle.
    static func scrollEvent(_ frame: ScrollFrame, at point: CGPoint, source: CGEventSource?) -> CGEvent? {
        guard let event = CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
                                  wheel1: frame.dy, wheel2: frame.dx, wheel3: 0) else { return nil }
        event.location = point
        event.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        event.setIntegerValueField(.scrollWheelEventScrollPhase, value: frame.phase.rawValue)
        event.setIntegerValueField(.scrollWheelEventMomentumPhase, value: 0)
        return event
    }

    private static func post(_ frame: ScrollFrame) {
        let source = CGEventSource(stateID: .hidSystemState)
        scrollEvent(frame, at: CGEvent(source: nil)?.location ?? .zero, source: source)?.post(tap: .cghidEventTap)
    }

    private func releaseButton() {
        if leftDown { mouse(.leftMouseUp, CGEvent(source: nil)?.location ?? .zero, .left, source: CGEventSource(stateID: .hidSystemState), count: dragClickCount) }
        leftDown = false
    }

    private func mouse(_ type: CGEventType, _ point: CGPoint, _ button: CGMouseButton, source: CGEventSource?, dx: Double = 0, dy: Double = 0, count: Int = 1) {
        guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: point, mouseButton: button) else { return }
        event.setIntegerValueField(.mouseEventClickState, value: Int64(count))
        event.setIntegerValueField(.mouseEventDeltaX, value: Int64(dx.rounded()))
        event.setIntegerValueField(.mouseEventDeltaY, value: Int64(dy.rounded()))
        event.post(tap: .cghidEventTap)
    }

    private func nearestScreenPoint(_ point: CGPoint) -> CGPoint {
        var displays = [CGDirectDisplayID](repeating: 0, count: 32); var count: UInt32 = 0
        guard CGGetActiveDisplayList(32, &displays, &count) == .success else { return point }
        let rects = displays.prefix(Int(count)).map(CGDisplayBounds)
        if rects.contains(where: { $0.contains(point) }) { return point }
        return rects.map { r in CGPoint(x: min(max(point.x, r.minX), r.maxX - 1), y: min(max(point.y, r.minY), r.maxY - 1)) }
            .min { hypot($0.x - point.x, $0.y - point.y) < hypot($1.x - point.x, $1.y - point.y) } ?? point
    }

    private func report(_ session: LinkSession, _ ready: Bool, _ message: String) {
        session.onExecutor { session.send(.pointerState(version: 1, ready: ready, message: message)) }
    }
}
