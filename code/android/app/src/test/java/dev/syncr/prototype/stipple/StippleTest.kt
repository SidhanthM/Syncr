package dev.syncr.prototype.stipple

import dev.syncr.prototype.ui.theme.Motion
import org.junit.Assert.*
import org.junit.Test

class StippleTest {
    /** The Mac's `Dither.threshold(x, y)` values, from `code/mac/Sources/SyncrMac/Dither.swift` (a 4 x 4 Bayer, plus a half step, over 16). */
    private val mac = listOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    ).map { (it + 0.5) / 16 }

    @Test fun thresholdsEqualTheMacs() {
        for (y in 0 until 4) for (x in 0 until 4) assertEquals("($x,$y)", mac[y * 4 + x], Stipple.threshold(x, y), 0.0)
    }

    @Test fun thePatternRepeatsEveryFourCells() {
        for (y in 0 until 12) for (x in 0 until 12) assertEquals(Stipple.threshold(x % 4, y % 4), Stipple.threshold(x, y), 0.0)
    }

    @Test fun everyThresholdIsUsedOnceAndStaysInsideZeroToOne() {
        val all = (0 until 16).map { Stipple.threshold(it % 4, it / 4) }
        assertEquals(16, all.toSet().size)
        assertTrue(all.all { it > 0.0 && it < 1.0 })
    }

    @Test fun aDissolveRunsFromNothingToEverythingFromTheTopDown() {
        val c = 12; val r = 20
        assertTrue(Stipple.mask(c, r, 0).none { it })
        assertTrue(Stipple.mask(c, r, Motion.STIPPLE_FRAMES).all { it })
        val half = Stipple.mask(c, r, Motion.STIPPLE_FRAMES / 2)
        val top = (0 until c * 4).count { half[it] }; val bottom = (c * (r - 4) until c * r).count { half[it] }
        assertTrue("top rows show before bottom rows: $top vs $bottom", top > bottom)
        // Cells only ever appear: each step shows everything the one before showed.
        for (k in 1..Motion.STIPPLE_FRAMES) {
            val before = Stipple.mask(c, r, k - 1); val after = Stipple.mask(c, r, k)
            for (i in before.indices) if (before[i]) assertTrue(after[i])
        }
    }

    @Test fun leavingIsTheSameFramesBackwardsInTwoThirdsOfTheTime() {
        assertEquals(Motion.REVEAL_MS * 2 / 3, Motion.LEAVE_MS)
        for (k in 0..10) {
            val p = k / 10f
            assertEquals(Stipple.frame(1f - p), Stipple.frame(p, leaving = true))
        }
        assertEquals(Motion.STIPPLE_FRAMES, Stipple.frame(0f, leaving = true))
        assertEquals(0, Stipple.frame(1f, leaving = true))
    }

    @Test fun aCellIsAboutThreeDpWhateverTheZoom() {
        // 3 dp at density 3.5 is 10.5 px; at 0.5 px per canvas unit that is 21 units, at 2 px per unit 5.25.
        assertEquals(21f, Stipple.cellUnits(0.5f, 3.5f), 0.01f)
        assertEquals(5.25f, Stipple.cellUnits(2f, 3.5f), 0.01f)
        assertEquals(64f, Stipple.cellUnits(0.001f, 3.5f), 0f)
    }

    @Test fun theWaitingBandIsDensestAtItsMiddleAndLoops() {
        assertTrue(Stipple.band(0.5) > Stipple.band(0.2))
        assertEquals(Stipple.band(0.3), Stipple.band(1.3), 1e-9)
        assertEquals(0.0, Stipple.band(0.0), 1e-9)
    }
}
