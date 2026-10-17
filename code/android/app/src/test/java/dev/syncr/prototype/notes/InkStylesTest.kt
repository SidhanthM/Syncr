package dev.syncr.prototype.notes

import dev.syncr.core.*
import org.junit.Assert.*
import org.junit.Test

class InkStylesTest {
    private fun pen(size: BrushSize = BrushSize.MEDIUM) = Brush(BrushFamily.PEN, size, InkColour.INK)
    private fun style(prefs: InkPreferences = InkPreferences(), brush: Brush = pen()) = InkStyles.style(brush, prefs)

    @Test fun defaultIsATaperedPressureFountainPenAtTheStoredWidth() {
        val s = style()
        assertTrue(s.pressureSensitive)
        assertEquals(0.4f, s.pressureLow, 1e-4f); assertEquals(1.6f, s.pressureHigh, 1e-4f)
        assertEquals(brushWidth(pen()), s.nominalWidth(), 0.02f * brushWidth(pen())) // typical pressure draws the stored size
        assertTrue(s.taperStart < 0.5f && s.taperEnd < 0.5f)
        assertTrue(s.speedThinning in 0.75f..0.95f)
        assertEquals(20L, s.smoothingWindowMs)
        assertEquals(InkStyles.EPSILON, s.epsilon, 0f)
    }

    @Test fun pressurePreferenceScalesHowMuchPressureChangesWidth() {
        val spans = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { p -> style(InkPreferences(pressure = p)).let { it.pressureHigh - it.pressureLow } }
        assertEquals(0f, spans.first(), 0f)
        assertFalse(style(InkPreferences(pressure = 0f)).pressureSensitive)
        spans.zipWithNext().forEach { (a, b) -> assertTrue("$spans", b > a) }
        val strongest = style(InkPreferences(pressure = 1f))
        assertTrue(strongest.pressureLow >= 0.12f - 1e-6f && strongest.pressureHigh <= 2f + 1e-6f) // Ink's multiplier bounds
    }

    @Test fun smoothingPreferenceMapsToWindowAndPressureDamping() {
        assertEquals(6L, InkStyles.smoothingWindowMs(0f))
        assertEquals(20L, InkStyles.smoothingWindowMs(0.5f))
        assertEquals(40L, InkStyles.smoothingWindowMs(1f))
        val windows = (0..10).map { InkStyles.smoothingWindowMs(it / 10f) }
        windows.zipWithNext().forEach { (a, b) -> assertTrue("$windows", b >= a) }
        assertTrue(InkStyles.pressureDampingSeconds(1f) > InkStyles.pressureDampingSeconds(0f))
        assertEquals(40L, style(InkPreferences(smoothing = 1f)).smoothingWindowMs)
    }

    @Test fun taperSwitchTurnsBothEndsOff() {
        val off = style(InkPreferences(taper = false))
        assertEquals(1f, off.taperStart, 0f); assertEquals(1f, off.taperEnd, 0f)
        assertTrue(off.pressureSensitive) // pressure is independent of taper
    }

    @Test fun looksDifferAsDescribed() {
        val fountain = style(InkPreferences(penLook = PenLook.FOUNTAIN))
        val ballpoint = style(InkPreferences(penLook = PenLook.BALLPOINT))
        val fineliner = style(InkPreferences(penLook = PenLook.FINELINER, pressure = 1f, taper = true))
        assertTrue(ballpoint.pressureSensitive)
        assertTrue(ballpoint.pressureHigh - ballpoint.pressureLow < (fountain.pressureHigh - fountain.pressureLow) / 2)
        assertTrue(ballpoint.taperStart > fountain.taperStart && ballpoint.taperStart < 1f)
        assertFalse(fineliner.pressureSensitive)
        assertEquals(1f, fineliner.speedThinning, 0f)
        assertEquals(1f, fineliner.taperStart, 0f); assertEquals(1f, fineliner.taperEnd, 0f)
        assertEquals(1f, fineliner.cornerRounding, 0f)
    }

    @Test fun highlighterIsFlatTranslucentChiselWithoutTaper() {
        PenLook.entries.forEach { look ->
            val s = style(InkPreferences(penLook = look, pressure = 1f), Brush(BrushFamily.HIGHLIGHTER, BrushSize.MEDIUM, InkColour.ORANGE))
            assertFalse(s.pressureSensitive)
            assertEquals(1f, s.taperStart, 0f); assertEquals(1f, s.taperEnd, 0f); assertEquals(1f, s.speedThinning, 0f)
            assertTrue(s.discardSelfOverlap)
            assertTrue(s.tipScaleX < s.tipScaleY && s.cornerRounding < 1f)
            assertEquals(brushWidth(Brush(BrushFamily.HIGHLIGHTER, BrushSize.MEDIUM, InkColour.ORANGE)), s.size, 0f)
        }
        assertEquals(0x66, inkColour(Brush(BrushFamily.HIGHLIGHTER, BrushSize.MEDIUM, InkColour.ORANGE)) ushr 24)
    }

    @Test fun sizesStayClearlyOrderedForEveryLookAndPressure() {
        for (family in BrushFamily.entries) for (look in PenLook.entries) for (p in listOf(0f, 0.5f, 1f)) {
            val prefs = InkPreferences(penLook = look, pressure = p)
            val (s, m, l) = BrushSize.entries.map { InkStyles.style(Brush(family, it, InkColour.INK), prefs) }
            val label = "$family $look pressure=$p"
            assertTrue(label, m.nominalWidth() >= 1.5f * s.nominalWidth())
            assertTrue(label, l.nominalWidth() >= 1.5f * m.nominalWidth())
            // Even Small pressed hard stays narrower than Large written normally.
            assertTrue(label, s.widthAt(1f) < l.nominalWidth())
        }
    }

    @Test fun speedAndTaperAreDefinedInPageUnitsSoEverySizeBehavesAlike() {
        val small = style(brush = pen(BrushSize.SMALL)); val large = style(brush = pen(BrushSize.LARGE))
        assertEquals(small.speedStartSizesPerSecond * small.size, large.speedStartSizesPerSecond * large.size, 1e-2f)
        assertEquals(small.speedEndSizesPerSecond * small.size, large.speedEndSizesPerSecond * large.size, 1e-2f)
        // Taper length grows with size but stays visible on Small.
        assertTrue(large.taperStartSizes * large.size > small.taperStartSizes * small.size)
        assertTrue(small.taperStartSizes * small.size >= 15f)
    }

    @Test fun pressureCurveIsGentleAndMonotonic() {
        assertEquals(0f, InkStyles.pressureCurve(0f), 1e-4f); assertEquals(1f, InkStyles.pressureCurve(1f), 1e-4f)
        assertEquals(0.5f, InkStyles.pressureCurve(InkStyles.TYPICAL_PRESSURE), 0.02f)
        val values = (0..20).map { InkStyles.pressureCurve(it / 20f) }
        values.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
        assertTrue(InkStyles.pressureCurve(0.1f) > 0.1f) // light touches already make a usable line
        assertEquals(1f, InkStyles.pressureCurve(1.4f), 1e-4f); assertEquals(0f, InkStyles.pressureCurve(-1f), 1e-4f)
    }

    @Test fun minimumSizeKeepsThumbnailHairlines() {
        assertEquals(9f, InkStyles.style(pen(BrushSize.SMALL), InkPreferences(), minSize = 9f).size, 0f)
        assertEquals(brushWidth(pen(BrushSize.LARGE)), InkStyles.style(pen(BrushSize.LARGE), InkPreferences(), minSize = 1f).size, 0f)
    }
}
