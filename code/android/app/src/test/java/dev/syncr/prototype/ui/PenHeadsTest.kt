package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenHeadsTest {
    private val tools = listOf(PenArtData.FOUNTAIN, PenArtData.BALLPOINT, PenArtData.FINELINER, PenArtData.HIGHLIGHTER,
        PenArtData.ERASER, PenArtData.LASSO, PenArtData.CARET)
    private val all = tools + listOf(PenArtData.UNDO, PenArtData.REDO, PenArtData.FIT, PenArtData.MORE, PenArtData.GRIP)

    @Test fun everyStrandIsWholeTriplesWithALengthAndSaneHalfWidths() {
        all.forEach { art ->
            assertTrue(art.name, art.strands.isNotEmpty())
            art.strands.forEach { s ->
                assertEquals("${art.name} triples", 0, s.xyh.size % 3)
                assertTrue("${art.name} points", s.count >= 4)
                assertTrue("${art.name} length ${s.length}", s.length > 0.5f)
                for (i in 0 until s.count) assertTrue("${art.name} half-width ${s.xyh[i * 3 + 2]}", s.xyh[i * 3 + 2] in 0.015f..0.6f)
            }
        }
    }

    @Test fun theSheetIsSquare() { assertEquals(PenArtData.WIDTH, PenArtData.HEIGHT, 0f) }

    @Test fun everyOutlinePointLiesOnTheSheet() {
        all.forEach { art -> art.strands.forEach { s ->
            val o = PenOutline(s)
            (o.left + o.right).forEach { v -> assertTrue("${art.name} $v", v in 0f..PenArtData.WIDTH) }
        } }
    }

    @Test fun theDecodedDataMatchesWhatTheGeneratorWrote() {
        all.forEach { art ->
            assertEquals("${art.name} point count", art.points, art.strands.sumOf { it.count })
            assertEquals("${art.name} checksum", art.checksum.toLong(), PenArtCodec.checksum(art))
        }
    }

    /** The visible bounds of a glyph: its filled outlines. */
    private fun bounds(art: PenArt): FloatArray {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        art.strands.forEach { s ->
            val o = PenOutline(s)
            for (e in listOf(o.left, o.right)) for (i in 0 until o.count) {
                x0 = minOf(x0, e[i * 2]); x1 = maxOf(x1, e[i * 2]); y0 = minOf(y0, e[i * 2 + 1]); y1 = maxOf(y1, e[i * 2 + 1])
            }
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    @Test fun everyToolIsCentredByItsVisibleBounds() {
        tools.forEach { art ->
            val b = bounds(art)
            assertEquals("${art.name} horizontal centre", PenArtData.WIDTH / 2, (b[0] + b[2]) / 2, 0.25f)
            assertEquals("${art.name} vertical centre", PenArtData.HEIGHT / 2, (b[1] + b[3]) / 2, 0.25f)
        }
    }

    @Test fun aPartialStrandIsAPrefixOfItsPoints() {
        val o = PenOutline(PenArtData.FOUNTAIN.strands.first())
        val whole = o.polygon(1f)
        assertEquals(o.count * 4, whole.size)
        val half = o.polygon(0.5f)
        // The left edge of the half is the start of the whole's left edge, and it closes back along the right.
        for (i in 0 until (o.count / 2 - 1) * 2) assertEquals(o.left[i], half[i], 0f)
        assertTrue(half.size < whole.size && half.size >= 8)
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

    @Test fun theDrawOnGoesStrandByStrandAndEndsComplete() {
        val art = PenArtData.FOUNTAIN
        assertTrue(PenDrawOn.strokeFractions(art, 0f).all { it == 0f })
        assertTrue(PenDrawOn.strokeFractions(art, 1f).all { it == 1f })
        var before = 0f
        for (p in 0..20) {
            val f = PenDrawOn.strokeFractions(art, p / 20f)
            val sum = f.sum()
            assertTrue("never goes backwards", sum >= before); before = sum
            // A strand only starts once the one before it is done.
            for (i in 1 until f.size) if (f[i] > 0f) assertEquals(1f, f[i - 1], 0.0001f)
        }
    }

    @Test fun theDrawOnLastsTheDesignedTime() { assertEquals(260, PenDrawOn.MS) }
}
