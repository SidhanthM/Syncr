import Foundation

/// The sticky title's visibility rule (look-and-ink v1 §4.2), with the clock passed in.
func stickyTitleChecks() throws {
    var s = StickyTitleState()
    try check(!s.visible, "the title shows nothing while the page's own title is on screen")
    s.masthead(onScreen: false)
    try check(s.visible, "it shows once the page title has scrolled off")
    s.activity(at: 10)
    try check(!s.visible, "typing or pad ink hides it")
    try check(s.wake(at: 10.5) == 1.0, "it waits the rest of the quiet time")
    s.tick(at: 11.4); try check(!s.visible, "still hidden before the quiet time is up")
    s.tick(at: 11.5); try check(s.visible, "back after 1.5 s of quiet")
    s.activity(at: 20); s.activity(at: 21)
    s.tick(at: 22.4); try check(!s.visible, "more ink restarts the wait")
    s.tick(at: 22.5); try check(s.visible, "and it returns 1.5 s after the last of it")
    s.activity(at: 30); s.mouseMoved(at: 30.1)
    try check(!s.visible, "a nudge of the mouse right at a keystroke does not bring it back")
    s.mouseMoved(at: 31)
    try check(s.visible && s.wake(at: 31) == nil, "a real mouse move brings it back at once")
    s.activity(at: 40); s.masthead(onScreen: true); s.tick(at: 50)
    try check(!s.visible, "never shows while the page title is on screen")
    try check(StickyTitleState.quiet == 1.5, "the quiet time is 1.5 s")
}
