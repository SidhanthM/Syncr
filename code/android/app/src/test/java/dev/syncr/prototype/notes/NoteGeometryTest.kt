package dev.syncr.prototype.notes

import org.junit.Assert.*
import org.junit.Test

class NoteGeometryTest {
    @Test fun theLegacyColumnClipsInkToItsEdgesAndHasNoBottom() {
        val clip = PagedGeometry(500f).inkClip(10f, 10f)!!
        assertEquals(CanvasRect(0f, 0f, 4800f, Float.POSITIVE_INFINITY), clip)
        assertEquals(listOf(clip), PagedGeometry().paper)
        assertEquals(LegacyPage.WIDTH, PagedGeometry().thumbnailWidth); assertEquals(LegacyPage.VIEW_HEIGHT, PagedGeometry().thumbnailHeight)
    }
    @Test fun aClipTurnsIntoPixelsClampedToTheView() {
        val px = FrontInkPad.canvasClip(SheetMapping(2f, 40f, -100f), CanvasRect(0f, 0f, 100f, 400f), 1000, 1800)
        assertEquals(PxRect(40, 0, 240, 700), px)
        assertEquals(PxRect(0, 0, 1000, 1800), FrontInkPad.canvasClip(SheetMapping(2f, 40f, -100f), null, 1000, 1800))
    }
    @Test fun theCanvasHasNoEdges() {
        val g = CanvasGeometry
        val m = SheetMapping(3f, -1e6f, 1e6f)
        assertEquals(m, g.clamp(m, 1080, 2000))
        assertTrue(g.contains(-1e9f, 1e9f)); assertNull(g.inkClip(-5f, -5f)); assertTrue(g.paper.isEmpty())
        val b = g.flingBounds(m, 1080, 2000)
        assertTrue(b.minX < -1_000_000_000 && b.maxX > 1_000_000_000)
    }
}
