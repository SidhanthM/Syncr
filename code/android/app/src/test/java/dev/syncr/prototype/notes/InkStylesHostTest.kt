package dev.syncr.prototype.notes

import dev.syncr.core.*
import org.junit.Assert.*
import org.junit.Test

/** Needs the host core (`-PsyncrNativeHost=true`), like every test that calls it. The stored brush alone decides how a stroke looks; the core owns the mapping, these tests pin what the phone relies on. */
class InkStylesHostTest {
    private fun pen(size: BrushSize = BrushSize.MEDIUM) = Brush(BrushFamily.PEN, size, InkColour.INK)

    /** A brush as the phone stores it under [prefs]. */
    private fun drawn(prefs: InkPreferences = InkPreferences(), brush: Brush = pen()) = prefs.drawn(brush)
    private fun style(prefs: InkPreferences = InkPreferences(), brush: Brush = pen()) = InkStyles.style(drawn(prefs, brush))

    @Test fun aStrokeWithoutLookOrFeelIsTheHistoricalMacFountain() {
        val s = InkStyles.style(pen())
        assertTrue(s.pressureSensitive)
        assertEquals(0.4f, s.pressureLow, 1e-4f); assertEquals(1.6f, s.pressureHigh, 1e-4f)
        assertEquals(20L, s.smoothingWindowMs)
        assertEquals(0.018f, s.pressureDampingSeconds, 1e-6f)
        assertTrue(s.taperStart < 0.5f && s.taperEnd < 0.5f)
        assertEquals(InkStyles.EPSILON, s.epsilon, 0f)
        assertEquals(brushWidth(pen()), s.nominalWidth(), 0.02f * brushWidth(pen()))
    }

    @Test fun defaultPreferencesStoreTheTunedFeel() {
        val stored = drawn()
        assertEquals(PenFeel(50u, 50u, true), stored.feel)
        assertNull(stored.look) // Fountain is the absence of a look
        val s = InkStyles.style(stored)
        assertEquals(8L, s.smoothingWindowMs)
        assertEquals(0.009f, s.pressureDampingSeconds, 1e-6f)
        assertEquals(0.4f, s.pressureLow, 1e-4f); assertEquals(1.6f, s.pressureHigh, 1e-4f)
        assertEquals(0.018f, InkStyles.SPEED_DAMPING_SECONDS, 0f)
        assertTrue(InkPreferences().predictPenMotion)
    }

    @Test fun changingSettingsNeverRestylesAStrokeThatIsAlreadyStored() {
        val before = drawn(InkPreferences(penLook = PenLook.FOUNTAIN, pressure = 0.5f))
        val styleBefore = InkStyles.style(before)
        // The writer changes pen, pressure, smoothing and taper afterwards.
        val changed = InkPreferences(penLook = PenLook.FINELINER, pressure = 1f, smoothing = 1f, taper = false)
        val after = drawn(changed)
        assertNotEquals(before, after)
        assertEquals(styleBefore, InkStyles.style(before)) // same stored brush, same style: no preference is read
        assertEquals(PenLook.FOUNTAIN, InkTool.of(before).look)
        assertNotEquals(styleBefore, InkStyles.style(after)) // the new stroke does differ
    }

    @Test fun pressurePreferenceIsStoredAndScalesHowMuchPressureChangesWidth() {
        val spans = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { p -> style(InkPreferences(pressure = p)).let { it.pressureHigh - it.pressureLow } }
        assertEquals(0f, spans.first(), 0f)
        assertFalse(style(InkPreferences(pressure = 0f)).pressureSensitive)
        spans.zipWithNext().forEach { (a, b) -> assertTrue("$spans", b > a) }
        val strongest = style(InkPreferences(pressure = 1f))
        assertTrue(strongest.pressureLow >= 0.12f - 1e-6f && strongest.pressureHigh <= 2f + 1e-6f) // Ink's multiplier bounds
    }

    @Test fun smoothingPreferenceMapsToWindowAndPressureDamping() {
        assertEquals(1L, style(InkPreferences(smoothing = 0f)).smoothingWindowMs) // raw: Ink needs a positive window
        assertEquals(8L, style(InkPreferences(smoothing = 0.5f)).smoothingWindowMs)
        assertEquals(24L, style(InkPreferences(smoothing = 1f)).smoothingWindowMs)
        val windows = (0..10).map { style(InkPreferences(smoothing = it / 10f)).smoothingWindowMs }
        windows.zipWithNext().forEach { (a, b) -> assertTrue("$windows", b >= a) }
        assertTrue(style(InkPreferences(smoothing = 1f)).pressureDampingSeconds > style(InkPreferences(smoothing = 0f)).pressureDampingSeconds)
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

    @Test fun theLookIsStoredWithTheStrokeAndOnlyOnPens() {
        assertEquals(PenLook.BALLPOINT, drawn(InkPreferences(penLook = PenLook.BALLPOINT)).look)
        val highlighter = drawn(InkPreferences(penLook = PenLook.BALLPOINT), Brush(BrushFamily.HIGHLIGHTER, BrushSize.MEDIUM, InkColour.ORANGE))
        assertNull(highlighter.look)
        assertEquals(InkTool.HIGHLIGHTER, InkTool.of(highlighter))
        assertEquals(InkTool.BALLPOINT, InkTool.of(drawn(InkPreferences(penLook = PenLook.BALLPOINT))))
    }

    @Test fun highlighterIsFlatTranslucentChiselWithoutTaper() {
        PenLook.entries.forEach { look ->
            val brush = Brush(BrushFamily.HIGHLIGHTER, BrushSize.MEDIUM, InkColour.ORANGE)
            val s = style(InkPreferences(penLook = look, pressure = 1f), brush)
            assertFalse(s.pressureSensitive)
            assertEquals(1f, s.taperStart, 0f); assertEquals(1f, s.taperEnd, 0f); assertEquals(1f, s.speedThinning, 0f)
            assertTrue(s.discardSelfOverlap)
            assertTrue(s.tipScaleX < s.tipScaleY && s.cornerRounding < 1f)
            assertEquals(brushWidth(brush), s.size, 0f)
        }
        assertEquals(0x66, inkColour(Brush(BrushFamily.HIGHLIGHTER, BrushSize.MEDIUM, InkColour.ORANGE)) ushr 24)
    }

    @Test fun sizesStayClearlyOrderedForEveryLookAndPressure() {
        for (family in BrushFamily.entries) for (look in PenLook.entries) for (p in listOf(0f, 0.5f, 1f)) {
            val prefs = InkPreferences(penLook = look, pressure = p)
            val (s, m, l) = BrushSize.entries.map { style(prefs, Brush(family, it, InkColour.INK)) }
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
        assertEquals(9f, InkStyles.style(pen(BrushSize.SMALL), minSize = 9f).size, 0f)
        assertEquals(brushWidth(pen(BrushSize.LARGE)), InkStyles.style(pen(BrushSize.LARGE), minSize = 1f).size, 0f)
    }

    @Test fun theBrushOfARememberedToolCarriesItsInkSizeLookAndFeel() {
        val custom = Oklch(0.62f, 0.19f, 143.5f)
        val prefs = InkPreferences(penLook = PenLook.BALLPOINT)
            .withInk(InkTool.BALLPOINT, Ink.Custom(custom)).withSize(InkTool.BALLPOINT, BrushSize.LARGE)
        val brush = prefs.brushFor(InkTool.BALLPOINT)
        assertEquals(BrushFamily.PEN, brush.family); assertEquals(BrushSize.LARGE, brush.size)
        assertEquals(custom, brush.customInk); assertEquals(PenLook.BALLPOINT, brush.look)
        assertEquals(Ink.Custom(custom), brush.ink)
        // Another tool keeps its own ink.
        assertEquals(InkColour.ORANGE, prefs.brushFor(InkTool.HIGHLIGHTER).colour)
        assertEquals(InkColour.INK, prefs.brushFor(InkTool.FOUNTAIN).colour)
    }

    private fun curve(): ByteArray {
        val n = 40
        return encodeSamples(SampleChannels(
            List(n) { 100f + it * 9f }, List(n) { 300f + Math.sin(it * 0.25).toFloat() * 60f }, List(n) { 0.2f + 0.6f * it / n },
            List(n) { (it * 8).toUInt() }, null, null,
        ))
    }

    private val identity = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    private fun mesh(brush: Brush) = tessellateStroke(curve(), defaultStrokeStyle(brush), identity)

    @Test fun switchingPensLeavesAnExistingStrokesMeshByteIdentical() {
        val stored = InkPreferences().drawn(pen())
        val before = mesh(stored)
        // The writer switches to other pens, inks and feels; the stored stroke has nothing to read them from.
        for (look in PenLook.entries) mesh(InkPreferences(penLook = look, pressure = 1f, smoothing = 0f, taper = false).drawn(pen()))
        val after = mesh(stored)
        assertArrayEquals(before.vertices, after.vertices)
        assertArrayEquals(before.indices, after.indices)
    }

    @Test fun aBallpointStrokeFromThePhoneTessellatesLikeTheMacDrawsIt() {
        // The Mac tessellates from the brush that arrives over the wire: look and feel included.
        val brush = InkPreferences(penLook = PenLook.BALLPOINT).drawn(pen())
        val commit = StrokeCommit(newId(), newId(), newId(), brush, CoordinateSpace.CANVAS, curve())
        val reassembler = StrokeReassembler()
        var received: StrokeCommit? = null
        for (frame in encodeStrokeCommit(commit)) received = reassembler.accept(frame, 1uL) ?: received
        assertEquals(brush, received!!.brush)
        val local = mesh(brush)
        val remote = mesh(received.brush)
        assertArrayEquals(local.vertices, remote.vertices)
        assertFalse(local.vertices.contentEquals(mesh(InkPreferences().drawn(pen())).vertices)) // and it is not a fountain stroke
    }
}
