package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenHeadsTest {
    private val all = listOf(PenArtData.FOUNTAIN, PenArtData.BALLPOINT, PenArtData.FINELINER, PenArtData.HIGHLIGHTER,
        PenArtData.ERASER, PenArtData.LASSO, PenArtData.CARET)

    @Test fun everyCurveIsAStartPointThenWholeCubics() {
        all.forEach { art ->
            assertTrue(art.name, art.strokes.isNotEmpty())
            art.strokes.forEach { s -> assertEquals("${art.name} curve", 0, (s.curve.size - 2) % 6); assertTrue(s.length > 0.5f); assertTrue(s.width in 1f..2.5f) }
        }
    }

    @Test fun everythingStaysOnTheSheet() {
        all.forEach { art -> art.strokes.map { it.curve }.forEach { c ->
            c.forEachIndexed { i, v -> assertTrue("${art.name} $v", v in -0.5f..(if (i % 2 == 0) PenArtData.WIDTH else PenArtData.HEIGHT) + 0.5f) } } }
    }

    @Test fun strokesAreOneConstantWeightLikeTheLineIcons() {
        all.forEach { art -> art.strokes.forEach { assertEquals(art.name, 2f, it.width, 0f) } }
    }

    /** The visual bounding box (stroke half-width included) of a head, sampled along its cubics. */
    private fun bounds(art: PenArt): FloatArray {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        art.strokes.forEach { s ->
            val c = s.curve
            var px = c[0]; var py = c[1]
            var i = 2
            while (i + 5 < c.size) {
                for (k in 0..32) {
                    val t = k / 32f; val u = 1 - t
                    val x = u * u * u * px + 3 * u * u * t * c[i] + 3 * u * t * t * c[i + 2] + t * t * t * c[i + 4]
                    val y = u * u * u * py + 3 * u * u * t * c[i + 1] + 3 * u * t * t * c[i + 3] + t * t * t * c[i + 5]
                    x0 = minOf(x0, x - s.width / 2); x1 = maxOf(x1, x + s.width / 2); y0 = minOf(y0, y - s.width / 2); y1 = maxOf(y1, y + s.width / 2)
                }
                px = c[i + 4]; py = c[i + 5]; i += 6
            }
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    @Test fun everyHeadIsCentredByItsVisualBounds() {
        all.forEach { art ->
            val b = bounds(art)
            assertEquals("${art.name} horizontal centre", PenArtData.WIDTH / 2, (b[0] + b[2]) / 2, 0.5f)
            assertEquals("${art.name} vertical centre", PenArtData.HEIGHT / 2, (b[1] + b[3]) / 2, 0.5f)
            assertTrue("${art.name} fits the 20 x 44 live area", b[2] - b[0] <= 24f && b[3] - b[1] <= 44f)
        }
    }

    @Test fun theSamplesReadTheSizeAndTaper() {
        assertTrue(SampleStroke.widthDp(2f) < SampleStroke.widthDp(4f) && SampleStroke.widthDp(4f) < SampleStroke.widthDp(8f))
        assertEquals(1f, SampleStroke.taper(0.5f, 0.3f, 0.3f), 0f)
        assertEquals(0.3f, SampleStroke.taper(0f, 0.3f, 0.3f), 0.001f)
        assertEquals(0.3f, SampleStroke.taper(1f, 0.3f, 0.3f), 0.001f)
    }

    @Test fun inksAreNamedInWords() {
        assertEquals("soft blue", dev.syncr.core.Oklch(0.8f, 0.08f, 250f).inkName())
        assertEquals("deep red", dev.syncr.core.Oklch(0.35f, 0.15f, 25f).inkName())
        assertEquals("grey", dev.syncr.core.Oklch(0.6f, 0.0f, 0f).inkName())
        assertEquals("green", dev.syncr.core.Oklch(0.6f, 0.15f, 140f).inkName())
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

    @Test fun theDrawOnLastsTheDesignedTime() { assertEquals(260, PenDrawOn.MS) }
}
