package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.CoordinateSpace
import dev.syncr.core.InkColour
import dev.syncr.core.PreviewBody
import dev.syncr.core.StrokeCommit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link K.4 without a socket: strokes kept until the Mac confirms them, sent again on every new connection, once. */
class StrokeKeepTest {
    private val h = SessionHarness()
    private val brush = Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK)

    @After fun tearDown() = h.cleanup()

    private fun commit(id: String, bytes: Int = 4) = StrokeCommit("n", "s", id, brush, CoordinateSpace.CANVAS, ByteArray(bytes))

    @Test fun keeperDropsTheOldestPastItsBounds() {
        val k = StrokeKeeper(maxStrokes = 3, maxBytes = 100)
        for (i in 1..3) assertTrue(k.add(StrokeKeeper.Kept(commit("s$i"), 0, false)).isEmpty())
        assertEquals(listOf("s1"), k.add(StrokeKeeper.Kept(commit("s4"), 0, false)))
        assertEquals(listOf("s2", "s3", "s4"), k.pending().map { it.commit.strokeId })
        // Bytes: strokes of 40 bytes, 100 allowed: the third pushes the first out, and a huge one pushes the rest out but stays itself.
        val b = StrokeKeeper(maxStrokes = 10, maxBytes = 100)
        assertTrue(b.add(StrokeKeeper.Kept(commit("a", 40), 0, false)).isEmpty())
        assertTrue(b.add(StrokeKeeper.Kept(commit("b", 40), 0, false)).isEmpty())
        assertEquals(listOf("a"), b.add(StrokeKeeper.Kept(commit("c", 40), 0, false)))
        assertEquals(listOf("b", "c"), b.add(StrokeKeeper.Kept(commit("huge", 500), 0, false)).sorted())
        assertEquals(listOf("huge"), b.pending().map { it.commit.strokeId })
        assertTrue(b.ack("huge")); assertFalse(b.ack("huge")); assertEquals(0, b.size); assertEquals(0, b.bytes)
    }

    @Test fun theDefaultBoundsAreTwoHundredStrokesAndFourMegabytes() {
        val k = StrokeKeeper()
        for (i in 1..250) k.add(StrokeKeeper.Kept(commit("s$i"), 0, false))
        assertEquals(200, k.size)
        assertEquals("s51", k.pending().first().commit.strokeId)
        val big = StrokeKeeper()
        for (i in 1..10) big.add(StrokeKeeper.Kept(commit("b$i", 1_000_000), 0, false))
        assertEquals(4, big.size) // 4 MB is 4 x 1,000,000 bytes at most, never over
        assertTrue(big.bytes <= 4 * 1024 * 1024)
    }

    /** Draws one stream stroke through the pen ring and the pump. */
    private fun draw(id: String, space: CoordinateSpace = CoordinateSpace.VIEW_RELATIVE, rev: Long = 9, samples: Int = 3, end: Boolean = true) {
        assertTrue(h.feed.begin("n", "s", id, brush, space, rev, commit = true, hasTilt = false, hasOrientation = false))
        for (i in 0 until samples) h.feed.sample(10f + i, 20f, .5f, 0f, 0f, 100L + i * 4)
        h.feed.flush()
        if (end) h.feed.end()
        h.pump.pump()
    }

    private fun controls() = h.controls()

    @Test fun aStrokeIsKeptUntilConfirmedAndSentAgainOnEveryNewConnection() {
        h.pump.control = h.scheduler::sendControl
        h.pump.goOnline(keep = true)
        h.feed.enabled = true
        draw("S1", rev = 9)
        assertEquals(1, h.pump.commitsQueued)
        assertEquals(1, h.pump.keeper.size)
        // The connection drops with the stroke unconfirmed, and comes back: it goes again, with its revision first.
        h.drain()
        h.pump.reset()
        h.pump.goOnline(keep = true)
        val resent = controls()
        assertTrue("stroke_rev before the commit: $resent", resent.contains(Control.StrokeRev("S1", 9)))
        assertEquals(2, h.pump.commitsQueued)
        // Confirmed: a third connection sends nothing.
        h.pump.onAck("S1", true)
        assertEquals(0, h.pump.keeper.size)
        h.pump.reset(); h.pump.goOnline(keep = true)
        assertEquals(2, h.pump.commitsQueued)
        // Within one connection a stroke is queued once however often the pump runs.
        h.pump.pump(); h.pump.pump()
        assertEquals(2, h.pump.commitsQueued)
    }

    @Test fun aCanvasStrokeNeedsNoRevision() {
        h.pump.control = h.scheduler::sendControl
        h.pump.goOnline(keep = true); h.feed.enabled = true
        draw("C1", space = CoordinateSpace.CANVAS)
        h.drain(); h.pump.reset(); h.pump.goOnline(keep = true)
        assertTrue(controls().none { it is Control.StrokeRev })
        assertEquals(2, h.pump.commitsQueued)
    }

    @Test fun aStrokeDrawnWithNoConnectionIsKeptAndNeverPreviewed() {
        h.pump.control = h.scheduler::sendControl
        h.pump.goOnline(keep = true); h.feed.enabled = true
        h.pump.reset() // the link is down; the pen is still accepted
        val frames = h.out.frames.size
        draw("D1", rev = 4)
        assertEquals("no preview with no connection", frames, h.out.frames.size)
        assertEquals(0, h.pump.commitsQueued)
        assertEquals(1, h.pump.keeper.size)
        h.pump.goOnline(keep = true)
        assertTrue(controls().contains(Control.StrokeRev("D1", 4)))
        assertEquals(1, h.pump.commitsQueued)
    }

    @Test fun aStrokeStillBeingDrawnWhenTheLinkDropsKeepsDrawingAndIsSentWhenItEnds() {
        h.pump.control = h.scheduler::sendControl
        h.pump.goOnline(keep = true); h.feed.enabled = true
        draw("M1", rev = 7, samples = 2, end = false)
        assertTrue("its Begin and samples went out", h.out.frames.any { it.body is PreviewBody.Begin })
        val before = h.out.frames.size
        h.drain()
        h.pump.reset() // the drop, mid-stroke
        for (i in 0 until 3) h.feed.sample(40f + i, 20f, .5f, 0f, 0f, 200L + i * 4)
        h.feed.flush(); h.feed.end()
        h.pump.pump()
        assertEquals("nothing more is previewed on a connection it did not begin on", before, h.out.frames.size)
        assertEquals(1, h.pump.keeper.size)
        h.pump.goOnline(keep = true)
        assertTrue(controls().contains(Control.StrokeRev("M1", 7)))
        assertEquals(1, h.pump.commitsQueued)
        // All five samples are in the kept stroke.
        assertEquals(1, h.out.commits.size)
    }

    @Test fun aPeerThatDoesNotConfirmGetsTodaysBehaviour() {
        h.pump.control = h.scheduler::sendControl
        h.pump.goOnline(keep = false); h.feed.enabled = true
        draw("O1")
        assertEquals(1, h.pump.commitsQueued)
        assertEquals("nothing is kept for a Mac that never confirms", 0, h.pump.keeper.size)
        h.drain(); h.pump.reset(); h.pump.goOnline(keep = false)
        assertEquals("and nothing is sent again", 1, h.pump.commitsQueued)
        // A stroke in flight at a reset is cancelled, as before.
        draw("O2", end = false)
        h.pump.reset()
        h.feed.end(); h.pump.pump()
        assertEquals("a stroke cut off by the drop was not committed", 1, h.out.commits.size)
    }

    @Test fun aMirrorStrokeIsNeverKept() {
        h.pump.control = h.scheduler::sendControl
        h.pump.goOnline(keep = true); h.feed.enabled = true
        assertTrue(h.feed.begin("n", "s", "P1", brush, CoordinateSpace.CANVAS, 0, commit = false, hasTilt = false, hasOrientation = false))
        h.feed.sample(1f, 1f, .5f, 0f, 0f, 1); h.feed.flush(); h.feed.end(); h.pump.pump()
        assertEquals(0, h.pump.keeper.size)
        assertEquals(0, h.pump.commitsQueued)
    }
}
