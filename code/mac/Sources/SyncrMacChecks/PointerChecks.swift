import AppKit
import Foundation

/// Scroll frames from the phone, as the Mac turns them into trackpad-style events. Nothing here posts an event.
@MainActor func pointerScrollChecks() throws {
    var frames: [ScrollFrame] = []
    var natural = true
    let scroll = ScrollEmitter(natural: { natural }) { frames.append($0) }

    // A gesture is began, changed..., ended, in whole pixels, with the first frame carrying its delta.
    scroll.add(sx: 0, sy: 20, now: 1.00); scroll.add(sx: 0, sy: 8, now: 1.016); scroll.add(sx: 0, sy: -3, now: 1.032); scroll.end()
    try check(frames == [ScrollFrame(phase: .began, dx: 0, dy: 20), ScrollFrame(phase: .changed, dx: 0, dy: 8),
                         ScrollFrame(phase: .changed, dx: 0, dy: -3), ScrollFrame(phase: .ended, dx: 0, dy: 0)], "scroll runs began, changed, ended (\(frames))")
    try check(!scroll.active, "ended scroll is inactive")

    // Sub-pixel motion accumulates instead of being lost, and is dropped at the end instead of leaking into the next gesture.
    frames = []
    scroll.add(sx: 0, sy: 0.4, now: 2.0); scroll.add(sx: 0, sy: 0.4, now: 2.016); scroll.add(sx: 0, sy: 0.4, now: 2.032)
    try check(frames == [ScrollFrame(phase: .began, dx: 0, dy: 1)], "fractions add up to a pixel (\(frames))")
    scroll.end(); frames = []
    scroll.add(sx: 0, sy: 0.7, now: 3.0); scroll.end()
    try check(frames.isEmpty, "a gesture that never reached a pixel emits nothing, not even an end (\(frames))")
    scroll.add(sx: 0, sy: 0.4, now: 4.0)
    try check(frames.isEmpty, "the last gesture's remainder is not carried over")
    scroll.end()

    // Both axes, and a Mac set to non-natural scrolling flips them.
    frames = []
    scroll.add(sx: 5, sy: -6, now: 5.0); scroll.end()
    try check(frames.first == ScrollFrame(phase: .began, dx: 5, dy: -6), "both axes pass through in natural mode")
    natural = false; frames = []
    scroll.add(sx: 5, sy: -6, now: 6.0); scroll.add(sx: 1, sy: 1, now: 6.01); scroll.end()
    try check(frames.prefix(2) == [ScrollFrame(phase: .began, dx: -5, dy: 6), ScrollFrame(phase: .changed, dx: -1, dy: -1)], "non-natural direction flips the whole gesture")
    natural = true

    // A lost end packet cannot leave a scroll hanging: quiet for 150 ms ends it once; ending again is a no-op.
    frames = []
    scroll.add(sx: 0, sy: 10, now: 7.0)
    scroll.tick(now: 7.10); try check(scroll.active && frames.count == 1, "still scrolling inside the idle window")
    scroll.tick(now: 7.20); scroll.tick(now: 7.30); scroll.end()
    try check(frames.map(\.phase) == [.began, .ended], "idle timeout ends the scroll exactly once (\(frames))")

    // The event itself: continuous pixel scroll at the cursor, phase set, no momentum.
    let point = CGPoint(x: 321, y: 123)
    for (frame, phase) in [(ScrollFrame(phase: .began, dx: 3, dy: -9), 1), (ScrollFrame(phase: .changed, dx: 0, dy: 4), 2), (ScrollFrame(phase: .ended, dx: 0, dy: 0), 4)] {
        guard let event = RemotePointer.scrollEvent(frame, at: point, source: nil) else { throw CheckFailure(description: "scroll event was not built") }
        try check(event.type == .scrollWheel && event.getIntegerValueField(.scrollWheelEventIsContinuous) == 1, "scroll event is continuous")
        try check(event.getIntegerValueField(.scrollWheelEventScrollPhase) == Int64(phase), "phase \(phase) is set")
        try check(event.getIntegerValueField(.scrollWheelEventMomentumPhase) == 0, "no momentum phase")
        try check(event.getIntegerValueField(.scrollWheelEventPointDeltaAxis1) == Int64(frame.dy) && event.getIntegerValueField(.scrollWheelEventPointDeltaAxis2) == Int64(frame.dx), "pixel deltas carry through (axis1 vertical, axis2 horizontal)")
        try check(event.location == point, "event sits at the cursor")
    }

    // Wire: `se` is optional, round-trips, and a packet without it is an ordinary one.
    let end = PointerPacket(seq: 9, dx: 0, dy: 0, sx: 0, sy: 0, buttons: 0, click: 0, scrollEnd: true)
    try check(try LinkControl.parse(json: JSONSerialization.data(withJSONObject: LinkControl.pointer(end).object)) == .pointer(end), "scroll end round-trips")
    try check(LinkControl.pointer(PointerPacket(seq: 1, dx: 1, dy: 0, sx: 0, sy: 0, buttons: 0, click: 0)).object["se"] == nil, "ordinary packets carry no se key")
    guard case .pointer(let old) = try LinkControl.parse(json: Data(#"{"t":"pointer","v":1,"seq":2,"dx":0,"dy":0,"sx":3,"sy":4,"buttons":0,"click":0}"#.utf8)) else { throw CheckFailure(description: "legacy pointer packet did not parse") }
    try check(!old.scrollEnd && old.sy == 4, "a packet from a phone without se is still a plain scroll")
}

/// Link K.3: after a drop an approved Trackpad session may be taken up again, once, by the same phone, for a short while.
func pointerResumeChecks() throws {
    var resume = PointerResume()
    try check(!resume.take(phone: "A", at: 0), "a phone that never dropped under an approved session asks for consent")
    resume.dropped(phone: "A", at: 100)
    try check(!resume.take(phone: "B", at: 101), "another phone is not covered")
    try check(resume.take(phone: "A", at: 110), "the same phone, ten seconds later, takes it up again")
    try check(!resume.take(phone: "A", at: 111), "and only once")
    resume.dropped(phone: "A", at: 200)
    try check(!resume.take(phone: "A", at: 200 + PointerResume.window + 1), "after the window it asks again")
    resume.dropped(phone: "A", at: 300); resume.forget(phone: "A")
    try check(!resume.take(phone: "A", at: 301), "a phone that turned Trackpad off or was forgotten is not resumed")
    resume.dropped(phone: "A", at: 400); resume.forgetAll()
    try check(!resume.take(phone: "A", at: 401), "turning the link off forgets every resume")
}
