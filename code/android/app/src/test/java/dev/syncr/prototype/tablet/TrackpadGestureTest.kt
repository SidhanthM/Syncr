package dev.syncr.prototype.tablet

import dev.syncr.prototype.link.PointerInput
import org.junit.Assert.*
import org.junit.Test

class TrackpadGestureTest {
    private fun p(id: Int, x: Float, y: Float) = PadContact(id, x, y)

    @Test fun fingerMovesAndTapClicksWithoutAPen() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        g.down(listOf(p(0, 10f, 10f)), 1000)
        g.move(listOf(p(0, 30f, 14f)))
        g.up(emptyList(), 1100)
        assertEquals(listOf(PointerInput(dx = 20.0, dy = 4.0)), sent)
        g.down(listOf(p(0, 50f, 50f)), 2000); g.up(emptyList(), 2100)
        assertEquals(PointerInput(click = 1), sent.last())
    }

    @Test fun twoFingersScrollAndLiftDoesNotTurnIntoPointerMotion() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        g.down(listOf(p(0, 10f, 10f)), 1000)
        g.down(listOf(p(0, 10f, 10f), p(1, 100f, 10f)), 1010)
        g.move(listOf(p(0, 10f, 30f), p(1, 100f, 30f)))
        assertEquals(listOf(PointerInput(sy = 20.0)), sent)
        g.up(listOf(p(0, 10f, 30f)), 1100)
        g.move(listOf(p(0, 40f, 80f))); g.up(emptyList(), 1200)
        assertEquals(listOf(PointerInput(sy = 20.0), PointerInput(scrollEnd = true)), sent)
    }

    @Test fun twoFingerTapRightClicksWithoutCursorJump() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        g.down(listOf(p(0, 10f, 10f)), 1000)
        g.down(listOf(p(0, 10f, 10f), p(1, 100f, 10f)), 1010)
        g.up(listOf(p(0, 10f, 10f)), 1100); g.up(emptyList(), 1110)
        assertEquals(listOf(PointerInput(click = 2)), sent)
    }

    @Test fun doubleTapDragReleasesOnCancelAndCannotResumeFromStaleContact() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        g.down(listOf(p(0, 10f, 10f)), 1000); g.up(emptyList(), 1050)
        g.down(listOf(p(0, 10f, 10f)), 1150)
        assertEquals(1, sent.size) // nothing is pressed until the finger moves or holds
        g.move(listOf(p(0, 30f, 10f)))
        assertEquals(listOf(PointerInput(buttons = 1, click = 3), PointerInput(dx = 20.0, buttons = 1)), sent.drop(1))
        g.heartbeat()
        assertEquals(1, sent.last().buttons)
        g.cancel()
        assertEquals(PointerInput(), sent.last())
        val count = sent.size; g.move(listOf(p(0, 300f, 10f)))
        assertEquals(count, sent.size)
    }

    @Test fun thirdFingerSuppressesClicksAndCursorMotion() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        g.down(listOf(p(0, 10f, 10f)), 1000)
        g.down(listOf(p(0, 10f, 10f), p(1, 30f, 10f), p(2, 50f, 10f)), 1010)
        g.move(listOf(p(0, 10f, 40f), p(1, 30f, 40f), p(2, 50f, 40f)))
        assertTrue(sent.isEmpty())
        g.up(listOf(p(0, 10f, 40f), p(1, 30f, 40f)), 1050); g.up(emptyList(), 1100)
        assertTrue(sent.none { it.dx != 0.0 || it.dy != 0.0 || it.buttons != 0 || it.click != 0 })
    }

    // ---- Two-finger scroll sequences (polish v2, T.1) ----
    private fun scrolling(sent: MutableList<PointerInput>, g: TrackpadGesture, t0: Long = 1000) {
        g.down(listOf(p(0, 10f, 10f)), t0)
        g.down(listOf(p(0, 10f, 10f), p(1, 100f, 10f)), t0 + 4)
    }

    @Test fun fingersLandingMillisecondsApartScrollWithoutCursorMotion() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        scrolling(sent, g)
        g.move(listOf(p(0, 10f, 12f), p(1, 100f, 12f))) // inside slop: swallowed
        g.move(listOf(p(0, 10f, 30f), p(1, 100f, 30f)))
        g.move(listOf(p(0, 10f, 38f), p(1, 100f, 38f)))
        g.up(listOf(p(0, 10f, 38f)), 1100); g.up(emptyList(), 1105)
        assertEquals(listOf(PointerInput(sy = 18.0), PointerInput(sy = 8.0), PointerInput(scrollEnd = true)), sent)
        assertTrue(sent.all { it.dx == 0.0 && it.dy == 0.0 && it.buttons == 0 && it.click == 0 })
    }

    @Test fun firstScrollFrameCarriesOnlyThatFramesMovement() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        scrolling(sent, g)
        g.move(listOf(p(0, 10f, 20f), p(1, 100f, 20f))) // crosses slop with a 10 dp frame
        assertEquals(PointerInput(sy = 10.0), sent.single())
    }

    @Test fun oneFingerLiftingFirstEndsTheScrollOnceAndTheOtherFingerStaysQuiet() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        scrolling(sent, g)
        g.move(listOf(p(0, 10f, 30f), p(1, 100f, 30f)))
        g.up(listOf(p(1, 100f, 30f)), 1100)
        g.move(listOf(p(1, 160f, 90f))); g.move(listOf(p(1, 200f, 120f)))
        g.up(emptyList(), 1300)
        assertEquals(listOf(PointerInput(sy = 20.0), PointerInput(scrollEnd = true)), sent)
    }

    @Test fun thirdFingerBrushPausesScrollAndItResumesWithoutAJump() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        scrolling(sent, g)
        g.move(listOf(p(0, 10f, 30f), p(1, 100f, 30f)))
        g.down(listOf(p(0, 10f, 30f), p(1, 100f, 30f), p(2, 50f, 30f)), 1050)
        g.move(listOf(p(0, 10f, 60f), p(1, 100f, 60f), p(2, 50f, 60f)))
        g.up(listOf(p(0, 10f, 60f), p(1, 100f, 60f)), 1070)
        g.move(listOf(p(0, 10f, 65f), p(1, 100f, 65f)))
        g.up(listOf(p(0, 10f, 65f)), 1200); g.up(emptyList(), 1210)
        assertEquals(listOf(PointerInput(sy = 20.0), PointerInput(sy = 5.0), PointerInput(scrollEnd = true)), sent)
    }

    @Test fun scrollThenImmediateOneFingerMoveStartsFreshWithoutAJump() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        scrolling(sent, g)
        g.move(listOf(p(0, 10f, 30f), p(1, 100f, 30f))); g.up(listOf(p(0, 10f, 30f)), 1100); g.up(emptyList(), 1102)
        sent.clear()
        g.down(listOf(p(0, 200f, 300f)), 1110)
        g.move(listOf(p(0, 203f, 301f)))
        assertEquals(listOf(PointerInput(dx = 3.0, dy = 1.0)), sent)
    }

    @Test fun scrollThenTapIsASingleClickNeverADrag() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        scrolling(sent, g)
        g.move(listOf(p(0, 10f, 30f), p(1, 100f, 30f))); g.up(listOf(p(0, 10f, 30f)), 1100); g.up(emptyList(), 1102)
        sent.clear()
        g.down(listOf(p(0, 10f, 10f)), 1150); g.up(emptyList(), 1200)
        assertEquals(listOf(PointerInput(click = 1)), sent)
        assertTrue(sent.none { it.buttons == 1 })
    }

    @Test fun tapThenImmediateTwoFingerScrollNeverPressesTheButton() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        g.down(listOf(p(0, 10f, 10f)), 1000); g.up(emptyList(), 1050)
        sent.clear()
        scrolling(sent, g, 1100)
        g.move(listOf(p(0, 10f, 30f), p(1, 100f, 30f))); g.up(emptyList(), 1200)
        assertTrue(sent.none { it.buttons == 1 || it.click != 0 })
        assertEquals(PointerInput(scrollEnd = true), sent.last())
    }

    @Test fun twoQuickTapsDoubleClickAndNothingStaysPressed() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        g.down(listOf(p(0, 10f, 10f)), 1000); g.up(emptyList(), 1050)
        g.down(listOf(p(0, 11f, 10f)), 1150); g.up(emptyList(), 1200)
        assertEquals(listOf(PointerInput(click = 1), PointerInput(click = 3)), sent)
    }

    @Test fun holdingTheSecondTapStartsADragEvenWithoutMoving() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        g.down(listOf(p(0, 10f, 10f)), 1000); g.up(emptyList(), 1050)
        g.down(listOf(p(0, 10f, 10f)), 1150); g.heartbeat()
        assertEquals(PointerInput(buttons = 1, click = 3), sent.last())
        g.up(emptyList(), 1600)
        assertEquals(PointerInput(), sent.last())
    }

    @Test fun cancelMidScrollClosesItAndLeavesNothingStuck() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        scrolling(sent, g)
        g.move(listOf(p(0, 10f, 30f), p(1, 100f, 30f)))
        g.cancel()
        assertEquals(PointerInput(scrollEnd = true), sent.last())
        val n = sent.size; g.move(listOf(p(0, 10f, 90f), p(1, 100f, 90f))); g.up(emptyList(), 1500)
        assertEquals(n, sent.size)
    }

    @Test fun twoFingerTapAfterAScrollStillRightClicks() {
        val sent = mutableListOf<PointerInput>(); val g = TrackpadGesture(4f, 300, sent::add)
        scrolling(sent, g)
        g.move(listOf(p(0, 10f, 30f), p(1, 100f, 30f))); g.up(emptyList(), 1100)
        sent.clear()
        scrolling(sent, g, 1200); g.up(listOf(p(0, 10f, 10f)), 1260); g.up(emptyList(), 1265)
        assertEquals(listOf(PointerInput(click = 2)), sent)
    }
}
