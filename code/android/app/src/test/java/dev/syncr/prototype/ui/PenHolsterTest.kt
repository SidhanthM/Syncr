package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mobile polish B.2: the holster logic over fake broadcasts. Never verified on hardware. */
class PenHolsterTest {
    @Test fun insertAndRemoveBroadcastsAreRead() {
        assertEquals(true, PenHolster.inserted("com.samsung.pen.INSERT", true))
        assertEquals(false, PenHolster.inserted("com.samsung.pen.INSERT", false))
    }
    @Test fun otherBroadcastsAndMissingExtrasAreIgnored() {
        assertNull(PenHolster.inserted("android.intent.action.BATTERY_LOW", true))
        assertNull(PenHolster.inserted("com.samsung.pen.INSERT", null))
        assertNull(PenHolster.inserted(null, true))
    }
    @Test fun penAwayShowsTypeAndTakingItOutReturnsToTheLastPenTool() {
        val highlighter = ToolState(brush = ToolState().brush.copy(family = dev.syncr.core.BrushFamily.HIGHLIGHTER))
        val away = highlighter.holster(true)
        assertTrue(away.typing); assertFalse(away.pen); assertFalse(away.highlighter)
        val back = away.holster(false)
        assertFalse(back.typing); assertTrue(back.highlighter)
    }
    @Test fun withoutAnyBroadcastTheToolStateIsAsBefore() {
        val t = ToolState()
        assertFalse(t.typing); assertTrue(t.pen)
    }
}
