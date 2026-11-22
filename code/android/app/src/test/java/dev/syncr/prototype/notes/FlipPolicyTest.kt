package dev.syncr.prototype.notes

import org.junit.Assert.*
import org.junit.Test

class FlipPolicyTest {
    private val w = 1080f; private val d = 3f
    private fun decide(pull: Float, v: Float = 0f, age: Long = 500, last: Boolean = false, zoom: Float = 100f) = FlipPolicy.decide(pull, v, age, last, w, d, zoom)

    @Test fun pullIsTheLargerOfSeventyFiveDpAndNinePercentAndGrowsInTheMiddleZooms() {
        assertEquals(225f, FlipPolicy.pullPx(1080f, 3f, 100f), 0.01f)
        assertEquals(270f, FlipPolicy.pullPx(1080f, 3f, 125f), 0.01f)
        assertEquals(270f, FlipPolicy.pullPx(1080f, 3f, 250f), 0.01f)
        assertEquals(225f, FlipPolicy.pullPx(1080f, 3f, 251f), 0.01f)
        assertEquals(0.09f * 2000f, FlipPolicy.pullPx(2000f, 1f, 100f), 0.01f) // wide view, 9% beats 75 px
    }
    @Test fun aSlowDragFlipsOnlyPastThePull() {
        assertEquals(Flip.STAY, decide(224f))
        assertEquals(Flip.NEXT, decide(226f))
        assertEquals(Flip.PREVIOUS, decide(-226f))
    }
    @Test fun aFlickNeedsFiftyFivePercentOfThePull() {
        val need = 0.55f * 225f
        assertEquals(Flip.NEXT, decide(need + 1f, v = 850f * d, age = 100))
        assertEquals(Flip.STAY, decide(need - 1f, v = 850f * d, age = 100))
        assertEquals(Flip.STAY, decide(need + 1f, v = 849f * d, age = 100)) // not fast enough for a flick
        assertEquals(Flip.STAY, decide(need + 1f, v = 850f * d, age = 181)) // motion too old to be a flick
    }
    @Test fun aReversalVetoesTheFlip() {
        assertEquals(Flip.STAY, decide(400f, v = -170f * d, age = 100))
        assertEquals(Flip.NEXT, decide(400f, v = -169f * d, age = 100))
        assertEquals(Flip.NEXT, decide(400f, v = -500f * d, age = 181)) // an old reversal does not count
    }
    @Test fun pastTheLastPageAddingNeedsTwentyTwoPercentOfTheView() {
        assertEquals(Flip.STAY, decide(0.22f * w - 1f, last = true))
        assertEquals(Flip.ADD, decide(0.22f * w + 1f, last = true))
        assertEquals(Flip.PREVIOUS, decide(-300f, last = true))
    }
    @Test fun theVerticalPullRubberBandsByHalfUpTo320AndArmsAt250() {
        assertEquals(50f, FlipPolicy.stretch(100f), 0f)
        assertEquals(320f, FlipPolicy.stretch(10_000f), 0f)
        assertEquals(0f, FlipPolicy.stretch(-5f), 0f)
        assertFalse(FlipPolicy.armed(499f)); assertTrue(FlipPolicy.armed(500f))
    }
}
