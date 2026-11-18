package dev.syncr.prototype.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleBandTest {
    private val scrolledOff = TitleBand().masthead(false)

    @Test fun showsOnlyOnceTheMastheadIsGone() {
        assertFalse(TitleBand().visible)
        assertTrue(scrolledOff.visible)
        assertFalse(scrolledOff.masthead(true).visible)
    }
    @Test fun hidesOnPenDownAndReturnsAfterQuiet() {
        val down = scrolledOff.penDown()
        assertFalse(down.visible)
        val up = down.penUp(1000, underBand = false)
        assertFalse(up.tick(2499).visible)
        assertTrue(up.tick(2500).visible)
        assertEquals(1500L, up.wakeInMs(1000))
    }
    @Test fun aNewStrokeRestartsTheWait() {
        val s = scrolledOff.penDown().penUp(0, false).penDown().penUp(1400, false)
        assertFalse(s.tick(2000).visible)
        assertTrue(s.tick(2900).visible)
    }
    @Test fun neverReturnsWhileThePenIsDown() {
        val s = scrolledOff.penDown()
        assertFalse(s.scrolledUp().visible)
        assertFalse(s.tick(100000).visible)
        assertNull(s.wakeInMs(0))
    }
    @Test fun staysHiddenWhenTheLastInkIsUnderTheBand() {
        val s = scrolledOff.penDown().penUp(0, underBand = true)
        assertFalse(s.tick(60000).visible)
        assertNull(s.wakeInMs(0))
        assertTrue(s.scrolledUp().visible)
        assertTrue(s.statusTapped().visible)
    }
    @Test fun typingKeepsItHiddenUntilItClosesAndTheWaitPasses() {
        val typing = scrolledOff.typingOpened()
        assertFalse(typing.scrolledUp().visible)
        assertFalse(typing.statusTapped().visible)
        assertFalse(typing.tick(100000).visible)
        val closed = typing.typingClosed(5000, underBand = false)
        assertFalse(closed.tick(6000).visible)
        assertTrue(closed.tick(6500).visible)
    }
    @Test fun typingUnderTheBandNeedsAScrollOrTap() {
        val closed = scrolledOff.typingOpened().typingClosed(0, underBand = true)
        assertFalse(closed.tick(99999).visible)
        assertTrue(closed.scrolledUp().visible)
    }
    @Test fun scrollingDownHidesAndDoesNotComeBackOnItsOwn() {
        val s = scrolledOff.scrolledDown()
        assertFalse(s.visible)
        assertFalse(s.tick(99999).visible)
        assertTrue(s.scrolledUp().visible)
        assertTrue(s.statusTapped().visible)
    }
    @Test fun aPenUpWhileTypingDoesNotArmTheTimer() {
        val s = scrolledOff.typingOpened().penDown().penUp(0, false)
        assertFalse(s.tick(99999).visible)
    }
}
