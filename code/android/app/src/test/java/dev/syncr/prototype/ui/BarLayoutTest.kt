package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The note's tool bar fits inside the screen minus a 16 dp gutter each side, More included. */
class BarLayoutTest {
    private fun check(screen: Float) {
        val edges = BarLayout.edges(screen)
        assertEquals(BarLayout.ITEMS + 2, edges.size)
        val bar = BarLayout.width(screen)
        assertTrue("bar $bar wider than ${screen - 2 * BarLayout.GUTTER} at $screen dp", bar <= screen - 2 * BarLayout.GUTTER + 0.001f)
        for ((i, e) in edges.withIndex()) {
            assertTrue("item $i starts left of the bar at $screen dp", e.start >= 0f)
            assertTrue("item $i ends at ${e.endInclusive}, outside $bar at $screen dp", e.endInclusive <= bar)
            if (i > 0) assertTrue("item $i overlaps the one before", e.start >= edges[i - 1].endInclusive - 0.001f)
        }
        // More, the last button, is wholly inside the gutter-limited width.
        assertTrue(edges.last().endInclusive + BarLayout.GUTTER <= screen)
    }

    @Test fun fitsAt360() = check(360f)
    @Test fun fitsAt411() = check(411f)
    @Test fun fitsAtWideScreens() = check(600f)
    @Test fun buttonsKeepAtLeastThirtyFourDp() {
        assertTrue(BarLayout.itemWidth(360f) >= BarLayout.MIN_ITEM)
        assertEquals(BarLayout.MAX_ITEM, BarLayout.itemWidth(600f), 0.001f)
    }
}
