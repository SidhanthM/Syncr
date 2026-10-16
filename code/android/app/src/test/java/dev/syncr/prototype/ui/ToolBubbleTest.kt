package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBubbleTest {
    private val w = 1080f
    private val h = 2340f
    private val m = 21f
    private val barW = 600f
    private val barH = 150f

    @Test fun bubbleAtTheRightEdgeGrowsLeftWithItsEndCapOnTheBubble() {
        val cx = w - 100f
        val p = barPlacement(cx, 2000f, barW, barH, w, h, m)
        assertFalse(p.growsRight)
        assertEquals(cx + barH / 2 - barW, p.left, 0.01f)
        assertEquals(2000f - barH / 2, p.top, 0.01f)
    }

    @Test fun bubbleAtTheLeftEdgeGrowsRight() {
        val p = barPlacement(100f, 2000f, barW, barH, w, h, m)
        assertTrue(p.growsRight)
        assertEquals(100f - barH / 2, p.left, 0.01f)
    }

    @Test fun withRoomOnBothSidesItGrowsTowardTheMiddle() {
        val wide = 3000f
        assertTrue(barPlacement(1000f, 500f, barW, barH, wide, h, m).growsRight)
        assertFalse(barPlacement(2000f, 500f, barW, barH, wide, h, m).growsRight)
    }

    @Test fun barWiderThanEitherSideTakesTheRoomierSideAndStaysOnScreen() {
        val p = barPlacement(700f, 500f, 1000f, barH, w, h, m)
        assertFalse(p.growsRight)
        assertEquals(m, p.left, 0.01f)
        assertTrue(p.left + 1000f <= w - m + 0.01f)
    }

    @Test fun barWiderThanTheScreenStartsAtTheMargin() {
        assertEquals(m, barPlacement(900f, 500f, 5000f, barH, w, h, m).left, 0.01f)
    }

    @Test fun barIsClampedVerticallyInsideTheMargins() {
        assertEquals(m, barPlacement(100f, 10f, barW, barH, w, h, m).top, 0.01f)
        assertEquals(h - m - barH, barPlacement(100f, h - 5f, barW, barH, w, h, m).top, 0.01f)
    }

    @Test fun itemsAppearOnceThereIsRoomNearestFirstAndAllFinish() {
        assertEquals(0f, itemReveal(0.2f, 0), 0f)
        assertTrue(itemReveal(0.5f, 0) > itemReveal(0.5f, 3))
        for (order in 0..20) assertEquals(1f, itemReveal(1f, order), 0f)
        assertEquals(1f, itemReveal(1f, 0, anchor = true), 0f)
        assertEquals(0f, itemReveal(0f, 0, anchor = true), 0f)
    }

    @Test fun theAnchorItemArrivesAfterItsNeighbours() {
        assertTrue(itemReveal(0.6f, 1) > itemReveal(0.6f, 0, anchor = true))
    }
}
