package dev.syncr.prototype.tablet

import dev.syncr.prototype.link.PointerInput
import org.junit.Assert.*
import org.junit.Test

class TrackpadResponseTest {
    @Test fun defaultsAreFasterButSlowCorrectionsDoNotAccelerate() {
        val response = TrackpadResponse()
        response.reset(1000)
        assertEquals(3.0, response.apply(PointerInput(dx = 1.0), 1100).dx, 1e-9)
        assertEquals(4.0, response.apply(PointerInput(sy = -1.0), 1100).sy * -1, 1e-9)
    }

    @Test fun accelerationBoostsFastSwipesWithoutChangingTheirDirection() {
        val slow = TrackpadResponse().apply { reset(1000) }.apply(PointerInput(dx = 1.0, dy = -0.5), 1100)
        val fast = TrackpadResponse().apply { reset(1000) }.apply(PointerInput(dx = 30.0, dy = -15.0), 1010)
        assertTrue(fast.dx / 30 > slow.dx)
        assertEquals(-0.5, fast.dy / fast.dx, 1e-9)
        val scroll = TrackpadResponse().apply { reset(1000) }.apply(PointerInput(sx = -30.0, sy = 15.0), 1010)
        assertTrue(scroll.sx < -30 * 4)
        assertEquals(-0.5, scroll.sy / scroll.sx, 1e-9)
    }

    @Test fun accelerationCanBeDisabledAndEachSettingIsIndependent() {
        val tuning = TrackpadTuning(pointerSpeed = 2f, pointerAcceleration = 0f, scrollSpeed = 7f, scrollAcceleration = 0f)
        val response = TrackpadResponse(tuning)
        response.reset(1000)
        assertEquals(PointerInput(dx = 60.0, dy = -20.0, buttons = 1),
            response.apply(PointerInput(dx = 30.0, dy = -10.0, buttons = 1), 1001))
        assertEquals(PointerInput(sx = -210.0, sy = 70.0), response.apply(PointerInput(sx = -30.0, sy = 10.0), 1001))
    }

    @Test fun sampleRateDoesNotChangeTravelAtTheSamePhysicalVelocity() {
        fun travel(interval: Long, count: Int, scroll: Boolean): Double {
            val response = TrackpadResponse().apply { reset(1000) }
            return (1..count).sumOf { n ->
                val input = if (scroll) PointerInput(sy = interval.toDouble()) else PointerInput(dx = interval.toDouble())
                val out = response.apply(input, 1000 + n * interval)
                if (scroll) out.sy else out.dx
            }
        }
        for (scroll in listOf(false, true)) assertEquals(travel(10, 12, scroll), travel(20, 6, scroll), 1e-8)
    }

    @Test fun cancellingOrReanchoringClearsFastMotionHistory() {
        val response = TrackpadResponse().apply { reset(1000) }
        response.apply(PointerInput(dx = 100.0), 1001)
        response.reset(2000)
        assertEquals(3.0, response.apply(PointerInput(dx = 1.0), 2100).dx, 1e-9)
        response.apply(PointerInput(sy = 100.0), 2101)
        response.reset(3000)
        assertEquals(4.0, response.apply(PointerInput(sy = 1.0), 3100).sy, 1e-9)
    }

    @Test fun clickDragReleaseAndHeartbeatFieldsAreNeverScaled() {
        val response = TrackpadResponse()
        for (input in listOf(PointerInput(click = 1), PointerInput(click = 2), PointerInput(buttons = 1, click = 3),
            PointerInput(buttons = 1), PointerInput())) assertEquals(input, response.apply(input, 1000))
        val drag = response.apply(PointerInput(dx = 10.0, buttons = 1, click = 3), 1016)
        assertEquals(1, drag.buttons); assertEquals(3, drag.click)
    }

    @Test fun boostedMotionRemainsFiniteBoundedAndDirectionPreserving() {
        val response = TrackpadResponse(TrackpadTuning(8f, 2f, 12f, 2f))
        val out = response.apply(PointerInput(dx = 1000.0, dy = -500.0, sx = -1000.0, sy = 250.0), 1)
        assertTrue(out.valid())
        assertEquals(2048.0, out.dx, 1e-9)
        assertEquals(-0.5, out.dy / out.dx, 1e-9)
        assertEquals(-0.25, out.sy / out.sx, 1e-9)
        assertTrue(response.apply(PointerInput(dx = Double.NaN, sy = Double.POSITIVE_INFINITY), 2).valid())
    }

    @Test fun preferenceChangesResetVelocityAndNonMonotonicEventsDoNotAmplifyMotion() {
        val response = TrackpadResponse().apply { reset(1000) }
        response.apply(PointerInput(dx = 100.0), 1001)
        response.tuning = TrackpadTuning(pointerSpeed = 2f)
        assertEquals(2.0, response.apply(PointerInput(dx = 1.0), 1002).dx, 1e-9)
        assertEquals(2.0, response.apply(PointerInput(dx = 1.0), 1002).dx, 1e-9)
        assertEquals(2.0, response.apply(PointerInput(dx = 1.0), 1000).dx, 1e-9)
    }

    @Test fun rangesClampCorruptValuesAndMapSliderEndpoints() {
        assertEquals(TrackpadTuning(), TrackpadTuning(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN).normalized())
        val clamped = TrackpadTuning(-100f, 100f, 100f, -100f).normalized()
        assertEquals(0.5f, clamped.pointerSpeed); assertEquals(2f, clamped.pointerAcceleration)
        assertEquals(12f, clamped.scrollSpeed); assertEquals(0f, clamped.scrollAcceleration)
        for (range in listOf(TrackpadTuning.POINTER_SPEED, TrackpadTuning.POINTER_ACCELERATION,
            TrackpadTuning.SCROLL_SPEED, TrackpadTuning.SCROLL_ACCELERATION)) {
            assertEquals(range.min, range.at(0f)); assertEquals(range.max, range.at(1f))
            assertEquals(0f, range.fraction(range.min)); assertEquals(1f, range.fraction(range.max))
            assertEquals(range.default, range.at(Float.NaN))
            assertEquals(range.default, range.at(range.fraction(range.default)), 1e-6f)
        }
    }
}
