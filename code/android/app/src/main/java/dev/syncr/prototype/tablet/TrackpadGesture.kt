package dev.syncr.prototype.tablet

import dev.syncr.prototype.link.PointerInput
import kotlin.math.hypot

internal data class PadContact(val id: Int, val x: Float, val y: Float)

/**
 * Finger-first relative trackpad. Contact-count changes always reanchor, never move the cursor.
 *
 * A second tap that lands soon after a first is only a *candidate* drag: nothing is pressed until the finger moves
 * past slop or is held (see [heartbeat]). A second finger landing first (a scroll starting right after a tap) drops
 * the candidate untouched, and a quick second tap is a double click. Two-finger scrolling is closed with an explicit
 * [PointerInput.scrollEnd] when a finger lifts, a drag starts or the gesture is cancelled.
 */
internal class TrackpadGesture(private val slop: Float, private val doubleTapMs: Long, private val emit: (PointerInput) -> Unit) {
    private var previous = emptyList<PadContact>()
    private var origins = emptyMap<Int, PadContact>()
    private var began = 0L
    private var maxContacts = 0
    private var moved = false
    private var dragCandidate = false
    private var dragging = false
    private var scrolling = false
    private var lastTap = Long.MIN_VALUE / 2
    private var lastTapPoint: PadContact? = null

    fun down(points: List<PadContact>, time: Long) {
        if (previous.isEmpty()) {
            began = time; moved = false; maxContacts = 0; scrolling = false
            val last = lastTapPoint
            dragCandidate = points.size == 1 && time - lastTap in 0..doubleTapMs && last != null &&
                hypot(points[0].x - last.x, points[0].y - last.y) < slop * 4
            dragging = false
            origins = points.associateBy { it.id }
        } else {
            if (dragging) emit(PointerInput())
            dragging = false; dragCandidate = false
            origins = origins + points.associateBy { it.id }
        }
        maxContacts = maxOf(maxContacts, points.size)
        previous = points
    }

    fun move(points: List<PadContact>) {
        if (points.isEmpty()) return
        if (points.map { it.id } != previous.map { it.id }) { previous = points; return }
        if (points.any { p -> origins[p.id]?.let { hypot(p.x - it.x, p.y - it.y) > slop } == true }) moved = true
        val dx = (points.sumOf { it.x.toDouble() } - previous.sumOf { it.x.toDouble() }) / points.size
        val dy = (points.sumOf { it.y.toDouble() } - previous.sumOf { it.y.toDouble() }) / points.size
        if (points.size == 1 && maxContacts == 1) {
            if (dragCandidate) { if (!moved) { previous = points; return }; startDrag() }
            if (dx != 0.0 || dy != 0.0) emit(PointerInput(dx = dx.coerceIn(-2048.0, 2048.0), dy = dy.coerceIn(-2048.0, 2048.0), buttons = if (dragging) 1 else 0))
        } else if (points.size == 2 && moved) {
            // A third finger brushing past only pauses this; once back to two it carries on from where it is.
            if (dx != 0.0 || dy != 0.0) { scrolling = true; emit(PointerInput(sx = dx.coerceIn(-2048.0, 2048.0), sy = dy.coerceIn(-2048.0, 2048.0))) }
        }
        previous = points
    }

    fun up(remaining: List<PadContact>, time: Long) {
        if (remaining.isNotEmpty()) {
            if (remaining.size < 2) endScroll()
            previous = remaining; return
        }
        endScroll()
        if (dragging) emit(PointerInput())
        else if (!moved && time - began < 300) {
            if (maxContacts == 1) {
                // A quick second tap is a double click; a first one is a click and may start a drag next time.
                if (dragCandidate) emit(PointerInput(click = 3)) else { emit(PointerInput(click = 1)); lastTap = time; lastTapPoint = previous.firstOrNull() }
            } else if (maxContacts == 2) emit(PointerInput(click = 2))
        }
        if (dragging || dragCandidate || moved || maxContacts != 1) { lastTap = Long.MIN_VALUE / 2; lastTapPoint = null }
        previous = emptyList(); origins = emptyMap(); dragging = false; dragCandidate = false
    }

    /** Holding the second tap still turns it into a drag; a drag already running is kept alive. */
    fun heartbeat() {
        if (dragCandidate && previous.size == 1 && maxContacts == 1) startDrag()
        else if (dragging) emit(PointerInput(buttons = 1))
    }

    fun cancel() {
        endScroll()
        if (dragging) emit(PointerInput())
        previous = emptyList(); origins = emptyMap(); dragging = false; dragCandidate = false; maxContacts = 0
        lastTap = Long.MIN_VALUE / 2; lastTapPoint = null
    }

    private fun startDrag() { dragCandidate = false; dragging = true; emit(PointerInput(buttons = 1, click = 3)) }
    private fun endScroll() { if (scrolling) { scrolling = false; emit(PointerInput(scrollEnd = true)) } }
}
