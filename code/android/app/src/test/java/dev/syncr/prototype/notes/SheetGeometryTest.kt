package dev.syncr.prototype.notes

import dev.syncr.core.Affine
import dev.syncr.core.SampleChannels
import org.junit.Assert.*
import org.junit.Test

class SheetGeometryTest {
    @Test fun canvasMappingIsUniformAndRoundTripsInBothOrientations() {
        for ((width, height) in listOf(1000 to 1600, 1920 to 1080, 1600 to 1000)) {
            val mapping = Viewport.defaultView(CanvasLayout(), width, height)
            val zero = mapping.toScreen(0f, 0f); val far = mapping.toScreen(4800f, 3000f)
            assertEquals(0f to 0f, zero)
            assertEquals((far.first - zero.first) / 4800f, (far.second - zero.second) / 3000f, .0001f)
            val point = mapping.toScreen(3400f, 12000f)
            assertEquals(3400f, mapping.toSheet(point.first, point.second).first, .001f)
            assertEquals(12000f, mapping.toSheet(point.first, point.second).second, .001f)
        }
    }
    @Test fun realSamplesPreserveOrderPressureTimeAndFreezeMapping() {
        var mapping = SheetMapping(2f, 100f, 200f)
        val gesture = MeasuredGesture(mapping, hasTilt = true, hasOrientation = true)
        assertTrue(gesture.add(120f, 240f, .3f, 100, .2f, -.4f))
        mapping = mapping.navigate(100f, 40f, 2f, 0f, 0f)
        assertTrue(gesture.add(140f, 280f, .9f, 110, .5f, .8f))
        val samples = gesture.channels()!!
        assertEquals(listOf(10f, 20f), samples.x); assertEquals(listOf(20f, 40f), samples.y)
        assertEquals(listOf(.3f, .9f), samples.pressure); assertEquals(listOf(0u, 10u), samples.elapsedMs)
        assertEquals(listOf(.2f, .5f), samples.tilt); assertEquals(listOf(-.4f, .8f), samples.orientation)
        assertNotEquals(20f, mapping.toSheet(140f, 280f).first)
    }
    @Test fun sampleLimitAndCancelNeverReturnTruncatedInk() {
        val gesture = MeasuredGesture(SheetMapping(1f, 0f, 0f), limit = 2)
        assertTrue(gesture.add(1f, 1f, 1f, 0)); assertTrue(gesture.add(2f, 2f, 1f, 1))
        assertFalse(gesture.add(3f, 3f, 1f, 2)); assertTrue(gesture.cancelled); assertNull(gesture.channels())
        assertFalse(gesture.add(4f, 4f, 1f, 3))
        val cancelled = MeasuredGesture(SheetMapping(1f, 0f, 0f))
        cancelled.add(1f, 1f, 1f, 0); cancelled.cancel(); assertNull(cancelled.channels())
    }
    @Test fun invalidOrTimeReversedSampleCancelsWholeGesture() {
        for ((x, time) in listOf(Float.NaN to 110L, 2f to 90L)) {
            val gesture = MeasuredGesture(SheetMapping(1f, 0f, 0f))
            gesture.add(1f, 1f, .5f, 100)
            assertFalse(gesture.add(x, 2f, .5f, time)); assertNull(gesture.channels())
        }
    }
    @Test fun affineUsesSharedConventionAndEraserSweepHitsBetweenSparseEvents() {
        val affine = Affine(2.0, 1.0, 3.0, 4.0, 5.0, 6.0)
        assertEquals(13f to 15f, transformedPoint(1f, 2f, affine))
        val line = SampleChannels(listOf(50f, 50f), listOf(0f, 100f), listOf(1f, 1f), listOf(0u, 10u), null, null)
        val identity = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        assertTrue(hitsSweep(0f to 50f, 100f to 50f, 1f, line, identity))
        assertFalse(hitsSweep(0f to 200f, 100f to 200f, 1f, line, identity))
        assertTrue(hitsStroke(50f, 60f, 1f, line, identity))
    }
}
