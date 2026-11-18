import Foundation

/// When the page strip shows the note's title (look-and-ink v1 §4.2). Pure; times are seconds from any steady clock.
/// It needs the page's own title scrolled off. Typing and ink arriving from the pad send it away; the mouse moving, or
/// [quiet] seconds without either, bring it back.
struct StickyTitleState: Equatable {
    static let quiet: TimeInterval = 1.5
    var titleOnScreen = true
    var hidden = false
    /// The last typing or ink, for the idle wait; nil when nothing is pending.
    var busySince: TimeInterval?

    var visible: Bool { !titleOnScreen && !hidden }

    mutating func masthead(onScreen: Bool) { titleOnScreen = onScreen }
    /// Typing or ink arrived: out of the way, and the wait starts over.
    mutating func activity(at now: TimeInterval) { hidden = true; busySince = now }
    /// The mouse moved. A move right at a keystroke (the desk shaking) does not count.
    mutating func mouseMoved(at now: TimeInterval) {
        if let t = busySince, now - t < 0.25 { return }
        hidden = false; busySince = nil
    }
    mutating func tick(at now: TimeInterval) {
        if hidden, let t = busySince, now - t >= Self.quiet { hidden = false; busySince = nil }
    }
    /// Seconds until `tick` would change anything, or nil.
    func wake(at now: TimeInterval) -> TimeInterval? { hidden ? busySince.map { max(0, Self.quiet - (now - $0)) } : nil }
}
