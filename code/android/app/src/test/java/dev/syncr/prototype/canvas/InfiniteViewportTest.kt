package dev.syncr.prototype.canvas

import dev.syncr.core.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfiniteViewportTest {
    private fun view() = InfiniteViewport().apply { resize(1080, 2340) }

    @Test fun oneHundredPercentIsOneXnotesPxPerDevicePx() {
        val v = view()
        assertEquals(1f / XN, v.mapping().scale, 1e-6f)
        assertEquals(1600.0 / 1240.157, XN.toDouble(), 1e-6)
    }

    @Test fun zoomRunsFromTwoPercentToSixtyFourHundred() {
        val v = view()
        v.zoomAround(0f, 0f, 1e-6); assertEquals(0.02, v.zoom, 1e-12)
        v.zoomAround(0f, 0f, 1e6); assertEquals(64.0, v.zoom, 1e-12)
    }

    @Test fun zoomKeepsTheFocusPointStill() {
        val v = view()
        v.set(1.0, 5000.0, -3000.0)
        val before = v.toCanvasX(300f) to v.toCanvasY(700f)
        v.zoomAround(300f, 700f, 7.3)
        assertEquals(before.first, v.toCanvasX(300f), 1e-6)
        assertEquals(before.second, v.toCanvasY(700f), 1e-6)
    }

    @Test fun panIsUnbounded() {
        val v = view()
        repeat(200) { v.navigate(-1000f, 1000f, 1f, 0f, 0f) }
        assertTrue(v.scrollX > 100_000 && v.scrollY < -100_000)
        // A far view maps without losing the canvas point under a pixel.
        assertEquals(v.scrollX + 10 / v.scale, v.toCanvasX(10f), 1e-6)
    }

    @Test fun stepsAreOneAndAQuarter() {
        val v = view()
        v.step(true); assertEquals(1.25, v.zoom, 1e-9)
        v.step(false); v.step(false); assertEquals(1.0 / 1.25, v.zoom, 1e-9)
        val c = v.centerX to v.centerY
        v.step(true); assertEquals(c.first, v.centerX, 1e-6); assertEquals(c.second, v.centerY, 1e-6)
    }

    @Test fun fitAllPadsTheContentByFortyEightXnotesPxAndCentresIt() {
        val v = view()
        val box = UnitRect(1000.0, 2000.0, 3000.0, 2600.0)
        v.fitAll(box)
        val pad = 48 * XN.toDouble()
        val seen = v.visible()
        // Width limits here: the padded box exactly spans the width.
        assertEquals(box.left - pad, seen.left, 1e-3)
        assertEquals(box.right + pad, seen.right, 1e-3)
        assertEquals(box.centerY, (seen.top + seen.bottom) / 2, 1e-3)
    }

    @Test fun fitAllOnAnEmptyCanvasIsOneHundredPercentAtTheOrigin() {
        val v = view()
        v.zoomAround(0f, 0f, 9.0); v.centerOn(500.0, 500.0)
        v.fitAll(null)
        assertEquals(1.0, v.zoom, 1e-12)
        assertEquals(0.0, v.centerX, 1e-6); assertEquals(0.0, v.centerY, 1e-6)
    }

    @Test fun fitAllNeverLeavesTheZoomRange() {
        val v = view()
        v.fitAll(UnitRect(0.0, 0.0, 1e9, 1e9)); assertEquals(0.02, v.zoom, 1e-12)
        v.fitAll(UnitRect(0.0, 0.0, 0.001, 0.001)); assertTrue(v.zoom in 0.02..64.0 && v.zoom > 1.0)
    }

    @Test fun aWaypointKeepsCentreAndZoomAcrossScreenSizes() {
        val a = view(); a.set(2.5, 800.0, 900.0)
        val w = a.toWaypoint("Desk")
        val b = InfiniteViewport().apply { resize(2000, 1200) }
        b.apply(w)
        assertEquals(a.centerX, b.centerX, 1e-3); assertEquals(a.centerY, b.centerY, 1e-3)
        assertEquals(2.5, b.zoom, 1e-6)
    }

    @Test fun resizeKeepsTheCentre() {
        val v = view(); v.set(1.0, 100.0, 200.0)
        val c = v.centerX to v.centerY
        v.resize(1000, 700)
        assertEquals(c.first, v.centerX, 1e-6); assertEquals(c.second, v.centerY, 1e-6)
    }

    @Test fun savedViewRoundTripsAndRejectsJunk() {
        val s = SavedView(3.5, -1234.5, 99.25)
        assertEquals(s, SavedView.decode(s.encode()))
        assertNull(SavedView.decode("1,2")); assertNull(SavedView.decode("x,1,2")); assertNull(SavedView.decode("0,1,2")); assertNull(SavedView.decode(null))
        assertNull(SavedView.decode("NaN,1,2"))
    }

    @Test fun waypointsFollowTheCoreRules() {
        val a = Waypoint("Desk", 1f, 2f, 1f)
        val list = CanvasWaypoints.saved(emptyList(), a)!!
        val again = CanvasWaypoints.saved(list + Waypoint("Other", 0f, 0f, 1f), Waypoint("DESK", 9f, 9f, 2f))!!
        assertEquals(listOf("DESK", "Other"), again.map { it.name }) // replaced in place
        assertNull(CanvasWaypoints.saved(list, Waypoint("  ", 0f, 0f, 1f)))
        assertNull(CanvasWaypoints.saved(list, Waypoint("z", 0f, 0f, 0f)))
        assertEquals(48, CanvasWaypoints.saved(list, Waypoint("x".repeat(60), 0f, 0f, 1f))!!.last().name.length)
        val full = (1..100).map { Waypoint("w$it", 0f, 0f, 1f) }
        assertNull(CanvasWaypoints.saved(full, Waypoint("new", 0f, 0f, 1f)))
        assertEquals(100, CanvasWaypoints.saved(full, Waypoint("W7", 1f, 1f, 1f))!!.size)
        assertEquals("View 2", CanvasWaypoints.nextName(list))
        assertEquals(emptyList<Waypoint>(), CanvasWaypoints.removed(list, "Desk"))
    }
}
