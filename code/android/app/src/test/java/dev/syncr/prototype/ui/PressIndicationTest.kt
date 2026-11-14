package dev.syncr.prototype.ui

import dev.syncr.prototype.ui.theme.pressFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressIndicationTest {
    @Test fun squareishElementsGetADisc() {
        assertTrue(pressFill(48f, 48f, 16f).circle)
        assertEquals(21f, pressFill(42f, 42f, 16f).radius, 0f)
        assertTrue(pressFill(60f, 56f, 16f).circle)
    }

    @Test fun rowsAndCardsKeepTheirOwnCorners() {
        val row = pressFill(360f, 56f, 16f)
        assertFalse(row.circle); assertEquals(16f, row.radius, 0f)
        assertEquals(10f, pressFill(300f, 20f, 16f).radius, 0f)
    }

    @Test fun emptyBoundsDrawNoDisc() = assertFalse(pressFill(0f, 0f, 16f).circle)
}
