package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenHeadsTest {
    private val all = listOf(PenArtData.FOUNTAIN, PenArtData.BALLPOINT, PenArtData.FINELINER, PenArtData.HIGHLIGHTER,
        PenArtData.ERASER, PenArtData.LASSO, PenArtData.CARET)

    @Test fun everyCurveIsAStartPointThenWholeCubics() {
        all.forEach { art ->
            assertTrue(art.name, art.strokes.isNotEmpty())
            art.strokes.forEach { s -> assertEquals("${art.name} curve", 0, (s.curve.size - 2) % 6); assertTrue(s.length > 0.5f); assertTrue(s.width in 1f..2.5f) }
            art.ink.forEach { assertEquals(0, (it.size - 2) % 6) }
        }
    }

    @Test fun everythingStaysOnTheSheet() {
        all.forEach { art -> (art.strokes.map { it.curve } + art.ink).forEach { c ->
            c.forEachIndexed { i, v -> assertTrue("${art.name} $v", v in -0.5f..(if (i % 2 == 0) PenArtData.WIDTH else PenArtData.HEIGHT) + 0.5f) } } }
    }

    @Test fun inkBearingHeadsHaveAnInkPart() {
        Head.entries.forEach { assertEquals(it.name, it.inkLook, it.art.ink.isNotEmpty()) }
    }

    @Test fun theDrawOnGoesStrokeByStrokeAndEndsComplete() {
        val art = PenArtData.FOUNTAIN
        assertTrue(PenDrawOn.strokeFractions(art, 0f).all { it == 0f })
        assertTrue(PenDrawOn.strokeFractions(art, 1f).all { it == 1f })
        var before = 0f
        for (p in 0..20) {
            val f = PenDrawOn.strokeFractions(art, p / 20f)
            val sum = f.sum()
            assertTrue("never goes backwards", sum >= before); before = sum
            // A stroke only starts once the one before it is done.
            for (i in 1 until f.size) if (f[i] > 0f) assertEquals(1f, f[i - 1], 0.0001f)
        }
    }

    @Test fun theInkDissolvesInAfterTheLinesHaveStarted() {
        assertEquals(0f, PenDrawOn.inkProgress(0f), 0f)
        assertEquals(1f, PenDrawOn.inkProgress(1f), 0f)
        assertTrue(PenDrawOn.inkProgress(0.5f) in 0.05f..0.95f)
        assertFalse(PenDrawOn.cellShown(0, 0, 0f))
        assertTrue(PenDrawOn.cellShown(3, 2, 1f))
    }

    @Test fun theDrawOnLastsTheDesignedTime() { assertEquals(260, PenDrawOn.MS) }
}
