package dev.syncr.prototype.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerTapClassifierTest {
    private fun classifier() = FingerTapClassifier(slop = 10f, doubleTapSlop = 50f, maxTapMs = 300, doubleTapMs = 300)

    /** Fingers down at t0, lift together at t1 without moving; returns the final result. */
    private fun FingerTapClassifier.tap(fingers: Int, t0: Long, t1: Long = t0 + 80, x: Float = 100f, y: Float = 100f): FingerTap? {
        for (i in 0 until fingers) down(i, x + i * 40, y, t0 + i * 10, finger = true, first = i == 0)
        var result: FingerTap? = null
        for (i in 0 until fingers) result = up(i, x + i * 40, y, t1 + i * 5)
        return result
    }

    @Test fun twoFingerTapUndoes() = assertEquals(TapCommand.UNDO, classifier().tap(2, 0)?.command)

    @Test fun threeFingerTapRedoes() = assertEquals(TapCommand.REDO, classifier().tap(3, 0)?.command)

    @Test fun singleTapAloneDoesNothing() = assertNull(classifier().tap(1, 0))

    @Test fun doubleTapFits() {
        val c = classifier()
        assertNull(c.tap(1, 0))
        assertEquals(TapCommand.FIT, c.tap(1, 200)?.command)
        // The double tap is consumed: a third tap starts over rather than fitting again.
        assertNull(c.tap(1, 400))
    }

    @Test fun slowSecondTapOrFarSecondTapDoesNotFit() {
        val slow = classifier()
        assertNull(slow.tap(1, 0)); assertNull(slow.tap(1, 900))
        val far = classifier()
        assertNull(far.tap(1, 0, x = 100f)); assertNull(far.tap(1, 150, x = 300f))
    }

    @Test fun movementBeyondSlopIsPanNotTap() {
        val c = classifier()
        c.down(0, 100f, 100f, 0, finger = true, first = true)
        c.down(1, 200f, 100f, 5, finger = true)
        c.move(0, 130f, 100f, 40) // pinch/pan
        assertNull(c.up(0, 130f, 100f, 80))
        assertNull(c.up(1, 200f, 100f, 85))
    }

    @Test fun smallJitterWithinSlopIsStillTap() {
        val c = classifier()
        c.down(0, 100f, 100f, 0, finger = true, first = true)
        c.down(1, 200f, 100f, 5, finger = true)
        c.move(1, 205f, 103f, 40)
        assertNull(c.up(0, 101f, 100f, 80))
        assertEquals(TapCommand.UNDO, c.up(1, 205f, 103f, 85)?.command)
    }

    @Test fun longPressIsNotTap() = assertNull(classifier().tap(2, 0, t1 = 600))

    @Test fun penContactInvalidatesSequenceAndPendingDoubleTap() {
        val c = classifier()
        c.down(0, 100f, 100f, 0, finger = true, first = true)
        c.down(1, 300f, 300f, 10, finger = false) // stylus joins
        assertNull(c.up(1, 300f, 300f, 50))
        assertNull(c.up(0, 100f, 100f, 60))

        assertNull(c.tap(1, 1000))
        c.penContact()
        assertNull(c.tap(1, 1100)) // would have been a double tap
    }

    @Test fun fingerReplacedMidSequenceIsNotThreeFingerTap() {
        val c = classifier()
        c.down(0, 100f, 100f, 0, finger = true, first = true)
        c.down(1, 200f, 100f, 10, finger = true)
        assertNull(c.up(1, 200f, 100f, 40))
        c.down(1, 200f, 100f, 60, finger = true) // re-touch: three downs, never three at once
        assertNull(c.up(1, 200f, 100f, 90))
        assertNull(c.up(0, 100f, 100f, 100))
    }

    @Test fun fourFingersDoNothing() = assertNull(classifier().tap(4, 0))

    @Test fun cancelDropsEverything() {
        val c = classifier()
        assertNull(c.tap(1, 0))
        c.down(0, 100f, 100f, 100, finger = true, first = true)
        c.cancel()
        assertNull(c.up(0, 100f, 100f, 150))
        assertNull(c.tap(1, 200)) // pending first tap was cleared by cancel
    }

    @Test fun eachSequenceEmitsAtMostOneCommand() {
        val c = classifier()
        c.down(0, 100f, 100f, 0, finger = true, first = true)
        c.down(1, 150f, 100f, 5, finger = true)
        val results = listOf(c.up(0, 100f, 100f, 50), c.up(1, 150f, 100f, 55), c.up(1, 150f, 100f, 60))
        assertEquals(listOf(null, TapCommand.UNDO, null), results.map { it?.command })
    }
}
