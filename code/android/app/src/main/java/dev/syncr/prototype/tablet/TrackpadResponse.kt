package dev.syncr.prototype.tablet

import dev.syncr.prototype.link.PointerInput
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The same bounds/defaults drive preferences, custom sliders and the motion response. */
data class TrackpadRange(val min: Float, val max: Float, val default: Float) {
    fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(min, max) else default
    fun fraction(value: Float): Float = (clamp(value) - min) / (max - min)
    fun at(fraction: Float): Float = if (fraction.isFinite()) min + fraction.coerceIn(0f, 1f) * (max - min) else default
}

data class TrackpadTuning(
    val pointerSpeed: Float = POINTER_SPEED.default,
    val pointerAcceleration: Float = POINTER_ACCELERATION.default,
    val scrollSpeed: Float = SCROLL_SPEED.default,
    val scrollAcceleration: Float = SCROLL_ACCELERATION.default,
) {
    fun normalized() = copy(pointerSpeed = POINTER_SPEED.clamp(pointerSpeed),
        pointerAcceleration = POINTER_ACCELERATION.clamp(pointerAcceleration),
        scrollSpeed = SCROLL_SPEED.clamp(scrollSpeed), scrollAcceleration = SCROLL_ACCELERATION.clamp(scrollAcceleration))

    companion object {
        val POINTER_SPEED = TrackpadRange(0.5f, 8f, 3f)
        val POINTER_ACCELERATION = TrackpadRange(0f, 2f, 0.75f)
        val SCROLL_SPEED = TrackpadRange(0.5f, 12f, 4f)
        val SCROLL_ACCELERATION = TrackpadRange(0f, 2f, 0.5f)
    }
}

/**
 * Applied only to outbound Trackpad motion, never to touch slop, trails, clicks or pen ink.
 * Velocity is logical dp/second, not distance per packet, so screen density and touch sample rate
 * do not set the acceleration. Only velocity is smoothed; positions are emitted immediately.
 */
internal class TrackpadResponse(tuning: TrackpadTuning = TrackpadTuning()) {
    private val pointer = VelocityCurve()
    private val scroll = VelocityCurve()
    var tuning: TrackpadTuning = tuning.normalized()
        set(value) {
            val next = value.normalized()
            if (field != next) { field = next; reset() }
        }

    fun reset(time: Long? = null) { pointer.reset(time); scroll.reset(time) }

    fun apply(input: PointerInput, time: Long): PointerInput {
        fun safe(value: Double) = if (value.isFinite()) value.coerceIn(-LIMIT, LIMIT) else 0.0
        val dx = safe(input.dx); val dy = safe(input.dy); val sx = safe(input.sx); val sy = safe(input.sy)
        val pointerGain = gain(pointer, dx, dy, time, tuning.pointerSpeed, tuning.pointerAcceleration)
        val scrollGain = gain(scroll, sx, sy, time, tuning.scrollSpeed, tuning.scrollAcceleration)
        // Preserve direction at the protocol limit instead of clipping just one axis.
        return input.copy(dx = (dx * pointerGain).coerceIn(-LIMIT, LIMIT), dy = (dy * pointerGain).coerceIn(-LIMIT, LIMIT),
            sx = (sx * scrollGain).coerceIn(-LIMIT, LIMIT), sy = (sy * scrollGain).coerceIn(-LIMIT, LIMIT))
    }

    private fun gain(curve: VelocityCurve, x: Double, y: Double, time: Long, speed: Float, acceleration: Float): Double {
        val extent = max(abs(x), abs(y))
        if (extent == 0.0) return 1.0 // A click/release/heartbeat must not change velocity history.
        return min(curve.gain(hypot(x, y), time, speed, acceleration), LIMIT / extent)
    }

    private class VelocityCurve {
        private var previousTime: Long? = null
        private var velocity: Double? = null
        fun reset(time: Long?) { previousTime = time; velocity = null }

        fun gain(distance: Double, time: Long, speed: Float, acceleration: Float): Double {
            val elapsed = previousTime?.let { time - it } ?: 16L
            previousTime = time
            val raw = if (elapsed > 0) distance * 1000.0 / elapsed else 0.0
            val before = velocity
            val smoothed = if (before == null || elapsed > 150 || elapsed <= 0) raw
                else before + (raw - before) * (1.0 - exp(-elapsed / 32.0))
            velocity = smoothed
            // Slow corrections stay at base speed. Brisk swipes smoothly add up to the chosen boost.
            val t = ((smoothed - 80.0) / (1600.0 - 80.0)).coerceIn(0.0, 1.0)
            val boost = t * t * (3.0 - 2.0 * t)
            return speed.toDouble() * (1.0 + acceleration * boost)
        }
    }

    private companion object { const val LIMIT = 2048.0 }
}
