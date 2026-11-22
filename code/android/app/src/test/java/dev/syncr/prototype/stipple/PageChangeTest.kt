package dev.syncr.prototype.stipple

import dev.syncr.prototype.notes.TextLine
import dev.syncr.prototype.stipple.PageChange.Phase
import dev.syncr.prototype.ui.theme.Motion
import org.junit.Assert.*
import org.junit.Test

/** Editor-v2 part R on the phone: what leaves, what arrives, how the text below moves, and in what order it plays. */
class PageChangeTest {
    private fun lines(vararg tops: Float) = tops.mapIndexed { i, t -> TextLine(i * 10, i * 10 + 9, t, t + 40f) }
    private val text = "a\nb\nc"

    @Test fun nothingChangedIsNoPlan() =
        assertNull(PageChange.plan(setOf("x"), setOf("x"), text, text, lines(0f, 50f), lines(0f, 50f)))

    @Test fun anAnswerArrivingOpensRoomForTheTextBelow() {
        val plan = PageChange.plan(setOf("m"), setOf("m", "a"), text, text, lines(260f, 320f, 380f), lines(260f, 500f, 560f))!!
        assertEquals(setOf("a"), plan.arriving); assertTrue(plan.leaving.isEmpty())
        assertEquals(500f, plan.fromY, 0f); assertEquals(180f, plan.delta, 0f)
        assertTrue(plan.glides)
    }

    @Test fun anAnswerDismissedClosesTheRoomItLeft() {
        val plan = PageChange.plan(setOf("m", "a"), setOf("m"), text, text, lines(260f, 500f, 560f), lines(260f, 320f, 380f))!!
        assertEquals(setOf("a"), plan.leaving)
        assertEquals(320f, plan.fromY, 0f); assertEquals(-180f, plan.delta, 0f)
    }

    @Test fun aMarginNoteMovesNoText() {
        val plan = PageChange.plan(emptySet(), setOf("n"), text, text, lines(260f, 320f), lines(260f, 320f))!!
        assertFalse(plan.glides)
        assertEquals(setOf("n"), plan.arriving)
    }

    @Test fun textThatWasEditedDoesNotGlide() {
        val plan = PageChange.plan(emptySet(), setOf("n"), text, "a\nb\nc\nd", lines(260f, 320f), lines(260f, 320f, 380f))!!
        assertFalse(plan.glides)
    }

    @Test fun twoDifferentShiftsAreNotOneGlide() {
        val plan = PageChange.plan(emptySet(), setOf("n"), text, text, lines(260f, 320f, 380f), lines(260f, 400f, 560f))!!
        assertFalse(plan.glides)
    }

    @Test fun theOrderIsLeaveThenGlideThenArrive() {
        val t = PageChange.Timeline(leaves = true, glides = true, arrives = true)
        assertEquals(Motion.LEAVE_MS + Motion.SLOW_MS + Motion.REVEAL_MS, t.totalMs)
        assertEquals(Phase.LEAVE, t.at(0).phase)
        assertEquals(Phase.GLIDE, t.at(Motion.LEAVE_MS.toLong()).phase)
        assertEquals(Phase.ARRIVE, t.at((Motion.LEAVE_MS + Motion.SLOW_MS).toLong()).phase)
        assertEquals(Phase.DONE, t.at(t.totalMs.toLong()).phase)
        assertEquals(0.5f, t.at(Motion.LEAVE_MS / 2L).progress, 0.01f)
    }

    @Test fun phasesWithNothingToDoTakeNoTime() {
        val arrive = PageChange.Timeline(leaves = false, glides = false, arrives = true)
        assertEquals(Phase.ARRIVE, arrive.at(0).phase)
        assertEquals(Motion.REVEAL_MS, arrive.totalMs)
        val leave = PageChange.Timeline(leaves = true, glides = true, arrives = false)
        assertEquals(Motion.LEAVE_MS + Motion.SLOW_MS, leave.totalMs)
    }

    /** Leaving takes two thirds of arriving (editor-v2 §8). */
    @Test fun leavingIsTwoThirdsOfArriving() = assertEquals(Motion.REVEAL_MS * 2 / 3, Motion.LEAVE_MS)

    @Test fun aFrozenDissolveShowsTheLeavingPartFirst() {
        assertEquals(PageChange.Step(Phase.LEAVE, .4f), PageChange.Timeline(true, true, true).frozen(.4f))
        assertEquals(PageChange.Step(Phase.ARRIVE, .4f), PageChange.Timeline(false, true, true).frozen(.4f))
    }
}
