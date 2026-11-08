package dev.syncr.prototype.tablet

import dev.syncr.prototype.link.Phase
import dev.syncr.prototype.link.StreamTarget

/** Missing preference means automatic; an explicit selection (including Pointer) always wins. */
fun defaultTabletLink(choice: TabletLink?, enabled: Boolean, paired: Boolean): TabletLink =
    choice ?: if (enabled && paired) TabletLink.STREAM else TabletLink.POINTER

/** Fingers never move Stream's fixed letterbox; only Pointer's Actual-size window can be dragged. */
fun tabletWindowDragAllowed(stream: Boolean, penDown: Boolean, mode: MappingMode, fingers: Int): Boolean =
    !stream && !penDown && mode == MappingMode.ACTUAL_SIZE && fingers == 2

/**
 * Local mode: two fingers move and resize the pen frame. Never with the pen on the glass or just after it lifts (a
 * resting hand), and only once the Mac has said where the frame is.
 */
fun tabletFrameGestureAllowed(local: Boolean, penDown: Boolean, eventTime: Long, lastPenUp: Long, fingers: Int): Boolean =
    local && !penDown && fingers == 2 && eventTime - lastPenUp >= 300

/** Keep a resting palm from activating the new caption control just after pen-up. */
fun tabletCaptionTapAllowed(penDown: Boolean, eventTime: Long, lastPenUp: Long): Boolean =
    !penDown && eventTime - lastPenUp >= 300

enum class StreamAction(val label: String) {
    ENABLE("Turn on Mac link"), PAIR("Open Mac link settings"), POINTER("Use Bluetooth pointer"),
}

data class StreamPadState(val message: String, val problem: String?, val action: StreamAction? = null)

/** One message and at most one useful action, shared by the pad and the setup sheet. */
fun streamPadState(enabled: Boolean, paired: Boolean, phase: Phase, mac: String?, target: StreamTarget): StreamPadState = when {
    !enabled -> StreamPadState("Mac link is off", "Mac link is off", StreamAction.ENABLE)
    phase == Phase.NEEDS_CODE || (!paired && phase != Phase.CONNECTED) ->
        StreamPadState("Pair your Mac in Mac link settings", "Mac is not paired", StreamAction.PAIR)
    phase != Phase.CONNECTED ->
        StreamPadState("Waiting for ${mac ?: "the Mac"}. Is the Syncr Mac app running?", "Mac is not connected", StreamAction.POINTER)
    !target.usable -> StreamPadState("Waiting for a writable note on the Mac", "No writable target yet")
    else -> StreamPadState("${mac ?: "Mac"} · Ink is saved in the Mac's note", null)
}
