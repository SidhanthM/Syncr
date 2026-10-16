package dev.syncr.prototype.notes

import kotlin.math.hypot

enum class TapCommand { UNDO, REDO, FIT }
data class FingerTap(val command: TapCommand, val x: Float, val y: Float)

/**
 * Arbitrates finger taps against pan/pinch (design §8.4): two-finger tap = undo, three-finger tap
 * = redo, single-finger double-tap = fit. A touch sequence (first down to last up) is a tap only
 * if it is short, every pointer stays within [slop], all pointers were fingers and no pen was in
 * contact. Each sequence yields at most one command; the first tap of a double-tap yields none.
 * Pure: fed with primitives so it can be unit tested without MotionEvent.
 */
class FingerTapClassifier(
    private val slop: Float,
    private val doubleTapSlop: Float,
    private val maxTapMs: Long = 300,
    private val doubleTapMs: Long = 300,
) {
    private val starts = HashMap<Int, Pair<Float, Float>>()
    private var active = false
    private var valid = false
    private var startTime = 0L
    private var downs = 0
    private var down = 0
    private var maxDown = 0
    private var firstTap: Triple<Long, Float, Float>? = null // up time, x, y

    /** [finger] is false for stylus/eraser/mouse or any pointer while a pen stroke is active. */
    /** [first] marks ACTION_DOWN; it always starts a new sequence (a still-open one was lost). */
    fun down(id: Int, x: Float, y: Float, time: Long, finger: Boolean, first: Boolean = false) {
        if (first && active) firstTap = null
        if (!active || first) { active = true; valid = true; startTime = time; downs = 0; down = 0; maxDown = 0; starts.clear() }
        if (!finger) penContact()
        starts[id] = x to y
        downs++; down++; maxDown = maxOf(maxDown, down)
        if (time - startTime > maxTapMs) valid = false
    }

    fun move(id: Int, x: Float, y: Float, time: Long) {
        if (!active) return
        val start = starts[id] ?: return
        if (hypot(x - start.first, y - start.second) > slop || time - startTime > maxTapMs) valid = false
    }

    /** Returns a command only when the last pointer lifts and the whole sequence was a clean tap. */
    fun up(id: Int, x: Float, y: Float, time: Long): FingerTap? {
        if (!active) return null
        move(id, x, y, time)
        down--
        if (down > 0) return null
        active = false
        // A finger that lifted and touched again inflates downs past the simultaneous count: not a tap.
        if (!valid || time - startTime > maxTapMs || downs != maxDown) { firstTap = null; return null }
        val (sx, sy) = starts[id] ?: (x to y)
        return when (downs) {
            2 -> { firstTap = null; FingerTap(TapCommand.UNDO, sx, sy) }
            3 -> { firstTap = null; FingerTap(TapCommand.REDO, sx, sy) }
            1 -> {
                val previous = firstTap
                if (previous != null && startTime - previous.first <= doubleTapMs &&
                    hypot(sx - previous.second, sy - previous.third) <= doubleTapSlop) {
                    firstTap = null; FingerTap(TapCommand.FIT, sx, sy)
                } else { firstTap = Triple(time, sx, sy); null }
            }
            else -> { firstTap = null; null }
        }
    }

    /** Any pen contact invalidates the current sequence and a pending first tap. */
    fun penContact() { valid = false; firstTap = null }

    fun cancel() { active = false; valid = false; firstTap = null; starts.clear(); down = 0 }
}
