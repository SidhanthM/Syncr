package dev.syncr.prototype.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasViewportTest {
    /** Portrait phone: fit scale 1080 / 1600 = 0.675; pinch range 0.3375..4.05. */
    private fun portrait(lowestInk: Float = 10_000f) = CanvasViewport().apply {
        resize(1080, 2340, oldWidth = 0)
        setLayout(CanvasLayout(lowestInk))
    }

    @Test fun noMappingUntilSizedThenTheDefaultViewShowsTheLeftColumn() {
        val viewport = CanvasViewport()
        viewport.setLayout(CanvasLayout(500f))
        assertNull(viewport.mapping)
        viewport.resize(1080, 2340, oldWidth = 0)
        assertEquals(SheetMapping(0.675f, 0f, 0f), viewport.mapping)
    }

    @Test fun retainedViewportKeepsZoomAndRowButStartsAtTheLeftEdge() {
        val viewport = CanvasViewport()
        viewport.reset(SheetMapping(1.3f, -900f, -400f))
        viewport.resize(2000, 1000, oldWidth = 0) // first size: no previous mapping is kept
        assertEquals(1000f / 1600f, viewport.mapping!!.scale, 1e-6f)
        viewport.reset(SheetMapping(1.3f, -900f, -400f))
        viewport.setLayout(CanvasLayout(10_000f))
        assertEquals(SheetMapping(1.3f, 0f, -400f), viewport.mapping)
        viewport.resize(1000, 2000, oldWidth = 2000)
        assertEquals(1.3f, viewport.mapping!!.scale)
        assertEquals(0f, viewport.mapping!!.tx)
    }

    @Test fun navigateKeepsTheFocusPointStillAndClampsToThePinchRange() {
        val viewport = portrait()
        viewport.navigate(0f, 0f, 2f, 500f, 1000f)
        val zoomed = viewport.mapping!!
        assertEquals(1.35f, zoomed.scale, 1e-5f)
        val before = SheetMapping(0.675f, 0f, 0f).toSheet(500f, 1000f)
        val after = zoomed.toSheet(500f, 1000f)
        assertEquals(before.first, after.first, 1e-3f)
        assertEquals(before.second, after.second, 1e-3f)
        viewport.navigate(0f, 0f, 100f, 500f, 1000f)
        assertEquals(4.05f, viewport.mapping!!.scale, 1e-5f)
        viewport.navigate(0f, 0f, 0.001f, 500f, 1000f)
        assertEquals(0.3375f, viewport.mapping!!.scale, 1e-5f)
        viewport.navigate(-100f, -50f, 1f, 0f, 0f)
        assertEquals(-100f, viewport.mapping!!.tx, 1e-3f)
        assertEquals(-50f, viewport.mapping!!.ty, 1e-3f)
    }

    @Test fun zoomToIgnoresThePinchRangeButNotTheCanvasWidth() {
        val viewport = portrait()
        viewport.zoomTo(8f, 0f, 0f)
        assertEquals(8f, viewport.mapping!!.scale, 1e-5f)
        viewport.zoomTo(0.01f, 0f, 0f)
        assertEquals(1080f / 4800f, viewport.mapping!!.scale, 1e-6f)
    }

    @Test fun pinchEndSnapsToFitOrActualSizeOnlyWhenClose() {
        val viewport = portrait()
        viewport.zoomTo(0.7f, 0f, 0f)
        viewport.snapAfterPinch(0f, 0f)
        assertEquals(0.675f, viewport.mapping!!.scale, 1e-6f)
        viewport.zoomTo(1.05f, 0f, 0f)
        viewport.snapAfterPinch(0f, 0f)
        assertEquals(1f, viewport.mapping!!.scale, 1e-6f)
        viewport.zoomTo(1.5f, 0f, 0f)
        viewport.snapAfterPinch(0f, 0f)
        assertEquals(1.5f, viewport.mapping!!.scale, 1e-6f)
    }

    @Test fun doubleTapZoomsInFromFitOtherwiseTheCallerFits() {
        val viewport = portrait()
        assertTrue(viewport.zoomInFromFit(100f, 100f))
        assertEquals(1.35f, viewport.mapping!!.scale, 1e-5f)
        assertFalse(viewport.zoomInFromFit(100f, 100f))
        assertEquals(1.35f, viewport.mapping!!.scale, 1e-5f)
        viewport.fitColumn()
        assertEquals(0.675f, viewport.mapping!!.scale, 1e-6f)
        assertEquals(0f, viewport.mapping!!.tx)
    }

    @Test fun flingTravelsWithinTheCanvasWidthAndDownToTheLowestInk() {
        val viewport = portrait(lowestInk = 9000f)
        viewport.navigate(-100f, -200f, 1f, 0f, 0f)
        assertEquals(FlingBounds(-100, -200, (1080 - 4800 * 0.675f).toInt(), (-9000 * 0.675f).toInt()), viewport.flingBounds())
        viewport.scrollTo(-1e7f, -1e7f)
        assertEquals(1080f - 4800f * 0.675f, viewport.mapping!!.tx, 1e-3f)
        assertEquals(-9000f * 0.675f, viewport.mapping!!.ty, 1e-3f)
        assertNull(CanvasViewport().flingBounds())
    }

    @Test fun containsOnlyPointsOnTheCanvas() {
        val viewport = portrait()
        assertTrue(viewport.contains(10f, 10f))
        viewport.navigate(0f, 0f, 0.001f, 0f, 0f)
        val right = 4800f * viewport.mapping!!.scale
        assertFalse(viewport.contains(right + 5f, 10f))
        assertFalse(CanvasViewport().contains(10f, 10f))
    }
}

class PanZoomTrackerTest {
    private fun viewport() = CanvasViewport().apply {
        resize(1000, 2000, oldWidth = 0)
        setLayout(CanvasLayout(100_000f))
        navigate(-200f, -500f, 1f, 0f, 0f) // somewhere in the middle, so pans aren't clamped
    }

    @Test fun aFingerWithinTapSlopNeverPansThenPansByTheDeltaFromTheLastAnchor() {
        val viewport = viewport()
        val start = viewport.mapping!!
        val tracker = PanZoomTracker()
        tracker.start()
        tracker.move(floatArrayOf(100f), floatArrayOf(100f), withinTapSlop = true, viewport)
        tracker.move(floatArrayOf(105f), floatArrayOf(103f), withinTapSlop = true, viewport)
        assertEquals(start, viewport.mapping)
        tracker.move(floatArrayOf(125f), floatArrayOf(143f), withinTapSlop = false, viewport)
        assertEquals(start.tx + 20f, viewport.mapping!!.tx, 1e-3f)
        assertEquals(start.ty + 40f, viewport.mapping!!.ty, 1e-3f)
    }

    @Test fun twoFingersZoomBySpreadAboutThePreviousCentroid() {
        val viewport = viewport()
        val start = viewport.mapping!!
        val tracker = PanZoomTracker()
        tracker.start()
        tracker.secondPointerDown()
        tracker.move(floatArrayOf(400f, 600f), floatArrayOf(1000f, 1000f), withinTapSlop = false, viewport)
        assertEquals(start, viewport.mapping) // the first move only anchors
        tracker.move(floatArrayOf(300f, 700f), floatArrayOf(1000f, 1000f), withinTapSlop = false, viewport)
        assertEquals(start.scale * 2f, viewport.mapping!!.scale, 1e-5f)
        val focus = start.toSheet(500f, 1000f)
        val after = viewport.mapping!!.toSheet(500f, 1000f)
        assertEquals(focus.first, after.first, 1e-2f)
        assertEquals(focus.second, after.second, 1e-2f)
        assertTrue(tracker.pinched)
        assertEquals(500f to 1000f, tracker.focus)
    }

    @Test fun reanchoringAfterAPointerChangePreventsAJump() {
        val viewport = viewport()
        val tracker = PanZoomTracker()
        tracker.start()
        tracker.move(floatArrayOf(100f), floatArrayOf(100f), withinTapSlop = false, viewport)
        val before = viewport.mapping
        tracker.reanchor()
        tracker.move(floatArrayOf(900f, 950f), floatArrayOf(900f, 950f), withinTapSlop = false, viewport)
        assertEquals(before, viewport.mapping)
        tracker.start()
        assertFalse(tracker.pinched)
        assertNull(tracker.focus)
    }
}
