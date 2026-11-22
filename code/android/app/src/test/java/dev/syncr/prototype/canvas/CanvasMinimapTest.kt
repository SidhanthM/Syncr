package dev.syncr.prototype.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasMinimapTest {
    private val map = CanvasMinimap()

    @Test fun thePanelIsFourteenFromTheBottomRight() {
        val p = map.panel(1080, 2340, density = 1f)
        assertEquals(1080f - 14f, p.right, 0f); assertEquals(2340f - 14f, p.bottom, 0f)
        assertEquals(168f, p.width(), 0f); assertEquals(120f, p.height(), 0f)
        val inset = map.panel(1080, 2340, insetRight = 10f, insetBottom = 100f)
        assertEquals(1080f - 24f, inset.right, 0f); assertEquals(2340f - 114f, inset.bottom, 0f)
    }

    @Test fun theExtentIsTheUnionOfContentAndViewAndNeverDegenerate() {
        val view = UnitRect(0.0, 0.0, 100.0, 200.0)
        assertEquals(UnitRect(0.0, 0.0, 100.0, 200.0), map.extent(null, view))
        val u = map.extent(UnitRect(-50.0, 10.0, 40.0, 500.0), view)
        assertEquals(-50.0, u.left, 1e-9); assertEquals(500.0, u.bottom, 1e-9); assertEquals(100.0, u.right, 1e-9)
        val dot = map.extent(UnitRect(5.0, 5.0, 5.0, 5.0), UnitRect(5.0, 5.0, 5.0, 5.0))
        assertTrue(dot.width >= 1.0 && dot.height >= 1.0)
    }

    @Test fun scaleLeavesEightPixelsAround() {
        val panel = PxRect(0f, 0f, 168f, 120f)
        val e = UnitRect(0.0, 0.0, 1520.0, 1040.0)
        assertEquals(min(152.0 / 1520, 104.0 / 1040), map.scaleFor(e, panel), 1e-12)
    }

    @Test fun aTapMapsBackToTheCanvasPointItShows() {
        val panel = map.panel(1080, 2340)
        val e = UnitRect(-3000.0, -1000.0, 9000.0, 4000.0)
        val x = map.toPanelX(1234.0, e, panel); val y = map.toPanelY(-222.0, e, panel)
        val (cx, cy) = map.toCanvas(x, y, e, panel)
        assertEquals(1234.0, cx, 0.5 / map.scaleFor(e, panel)); assertEquals(-222.0, cy, 0.5 / map.scaleFor(e, panel))
        val mid = map.toCanvas(panel.centerX(), panel.centerY(), e, panel)
        assertEquals(e.centerX, mid.first, 1e-6)
    }

    private fun min(a: Double, b: Double) = kotlin.math.min(a, b)
}
