package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.CoordinateSpace
import dev.syncr.core.InkColour
import dev.syncr.core.PreviewBody
import dev.syncr.core.SampleChannels
import dev.syncr.prototype.tablet.PadRect
import dev.syncr.prototype.tablet.TabletMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PenTapTest {
    private val clock = FakeClock()
    private val out = FakeOut()
    private val feed = PenFeed(clock).also { it.enabled = true }
    private val ids = ArrayDeque(listOf("stroke-1", "stroke-2", "stroke-3"))
    private val pool = IdPool({ "gen-${ids.size}" }, capacity = 3).also { pool -> pool.topUp() }
    private val encoded = ArrayList<SampleChannels>()
    private val pump = FeedPump(feed, out, clock, { encoded += it; byteArrayOf(1, 2, 3) }).also { it.epoch = 7 }
    private val brush = Brush(BrushFamily.HIGHLIGHTER, BrushSize.LARGE, InkColour.BLUE)

    private fun samples(i: Int) = out.frames[i].body as PreviewBody.Samples

    // ---- mirror ----

    @Test fun mirrorFramesCarryTheContractIdsSpaceAndCanvasUnits() {
        val mirror = CanvasMirror(feed, pool)
        // The canvas mapping frozen at pen-down: scale 0.5, offset (100, 40).
        val id = mirror.begin("note-1", "sheet-1", brush, 0.5f, 100f, 40f, hasTilt = true, hasOrientation = false)!!
        assertEquals("gen-3", id)
        mirror.sample(300f, 140f, 0.4f, 0.2f, 0f, 1000)
        mirror.sample(310f, 150f, 1.7f, 0.3f, 0f, 1008) // pressure is clamped like the saved stroke's
        mirror.flush()
        mirror.sample(320f, 160f, 0.6f, 0.1f, 0f, 1016)
        mirror.flush()
        mirror.end()
        pump.pump()

        assertEquals(4, out.frames.size) // Begin, one frame per input batch, End
        val begin = out.frames[0]
        assertEquals(7u, begin.epoch)
        assertEquals("note-1", begin.noteId)
        assertEquals("sheet-1", begin.sheetId)
        assertEquals(id, begin.strokeId)
        assertEquals(0u, begin.sequence)
        val b = begin.body as PreviewBody.Begin
        assertEquals(brush, b.brush)
        assertEquals(CoordinateSpace.CANVAS, b.space)

        val first = samples(1)
        assertEquals(0u, first.firstIndex)
        assertEquals(listOf(400f, 420f), first.channels.x) // (screen - tx) / scale
        assertEquals(listOf(200f, 220f), first.channels.y)
        assertEquals(listOf(0.4f, 1.0f), first.channels.pressure)
        assertEquals(listOf(0u, 8u), first.channels.elapsedMs)
        assertEquals(listOf(0.2f, 0.3f), first.channels.tilt)
        assertNull(first.channels.orientation)
        val second = samples(2)
        assertEquals(2u, second.firstIndex)
        assertEquals(listOf(16u), second.channels.elapsedMs)
        assertEquals(PreviewBody.End(3u), out.frames[3].body)

        assertEquals(listOf(0u, 1u, 2u, 3u), out.frames.map { it.sequence })
        assertTrue(out.frames.all { it.strokeId == id && it.noteId == "note-1" && it.sheetId == "sheet-1" })
        // Mirror: preview only, no commit.
        assertTrue(out.commits.isEmpty())
    }

    @Test fun oneInputBatchIsOneFrameEvenWithHistory() {
        val mirror = CanvasMirror(feed, pool)
        mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false)
        repeat(5) { mirror.sample(it.toFloat(), 0f, .5f, 0f, 0f, 1000L + it) }
        mirror.flush()
        pump.pump()
        assertEquals(2, out.frames.size)
        assertEquals(5, samples(1).channels.x.size)
    }

    @Test fun aBatchLargerThanASlotSplitsInOrder() {
        val mirror = CanvasMirror(feed, pool)
        mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false)
        repeat(PenFeed.MAX_BATCH + 6) { mirror.sample(it.toFloat(), 0f, .5f, 0f, 0f, 1000L + it) }
        mirror.flush(); mirror.end()
        pump.pump()
        assertEquals(listOf(0u, PenFeed.MAX_BATCH.toUInt()), listOf(samples(1).firstIndex, samples(2).firstIndex))
        assertEquals(PenFeed.MAX_BATCH, samples(1).channels.x.size)
        assertEquals(6, samples(2).channels.x.size)
        assertEquals(PreviewBody.End((PenFeed.MAX_BATCH + 6).toUInt()), out.frames.last().body)
    }

    @Test fun nothingIsSentWhenTheLinkIsDownOrNoIdIsFree() {
        val mirror = CanvasMirror(feed, pool)
        feed.enabled = false
        assertNull(mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false))
        mirror.sample(1f, 1f, .5f, 0f, 0f, 1); mirror.flush(); mirror.end()
        pump.pump()
        assertTrue(out.frames.isEmpty())
        feed.enabled = true
        repeat(3) { assertTrue(mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false) != null); mirror.end() }
        assertNull(mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false)) // the pool is empty: this stroke is not mirrored
    }

    @Test fun cancelSendsCancelAndLaterSamplesAreIgnored() {
        val mirror = CanvasMirror(feed, pool)
        mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false)
        mirror.sample(1f, 1f, .5f, 0f, 0f, 1); mirror.flush()
        mirror.cancel()
        mirror.sample(2f, 2f, .5f, 0f, 0f, 2); mirror.flush(); mirror.end()
        pump.pump()
        assertEquals(listOf(1, 2, 4), out.frames.map { when (it.body) { is PreviewBody.Begin -> 1; is PreviewBody.Samples -> 2; PreviewBody.Cancel -> 4; else -> 9 } })
    }

    @Test fun aFullRingDropsSamplesCountedByIndexSoTheReceiverSeesAGap() {
        val mirror = CanvasMirror(feed, pool)
        mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false)
        // The link thread is stuck: fill every slot with one-sample batches.
        repeat(PenFeed.CAPACITY + 10) { mirror.sample(it.toFloat(), 0f, .5f, 0f, 0f, 1000L + it); mirror.flush() }
        assertTrue(feed.droppedBatches > 0)
        mirror.end() // End still fits: a few slots are reserved
        pump.pump()
        val last = out.frames.last()
        assertEquals(PreviewBody.End((PenFeed.CAPACITY + 10).toUInt()), last.body)
        // The frames that made it are a prefix, so the receiver's total mismatch drops the preview cleanly.
        val delivered = out.frames.filter { it.body is PreviewBody.Samples }.sumOf { (it.body as PreviewBody.Samples).channels.x.size }
        assertTrue(delivered < PenFeed.CAPACITY + 10)
        // The link thread catches up; new samples carry the right first index, skipping the dropped ones.
        mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false)
        mirror.sample(1f, 1f, .5f, 0f, 0f, 5000); mirror.flush()
        pump.pump()
        val resumed = out.frames.last().body as PreviewBody.Samples
        assertEquals(0u, resumed.firstIndex)
    }

    @Test fun theRingNeverBlocksThePenThreadAndDropsRatherThanWaiting() {
        val mirror = CanvasMirror(feed, pool)
        mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false)
        val start = System.nanoTime()
        repeat(100_000) { mirror.sample(it.toFloat(), 0f, .5f, 0f, 0f, it.toLong()); if (it % 4 == 0) mirror.flush() }
        // No consumer at all: 100k samples return promptly (they would hang if anything waited).
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
        assertTrue(feed.droppedBatches > 0)
    }

    @Test fun theWakeCallbackRunsOncePerPublishedBatch() {
        var wakes = 0
        feed.wake = Runnable { wakes++ }
        val mirror = CanvasMirror(feed, pool)
        mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false)
        mirror.sample(1f, 1f, .5f, 0f, 0f, 1); mirror.sample(2f, 1f, .5f, 0f, 0f, 2); mirror.flush()
        mirror.end()
        assertEquals(3, wakes) // begin, the batch, end
    }

    // ---- Stream ----

    private fun pen(target: StreamTarget) = StreamPen(feed, pool, { target }, { brush })
    private val writable = StreamTarget("mac-note", "mac-sheet", true, 12)

    @Test fun streamStrokeSendsBeginSamplesEndAndADurableCommit() {
        val pen = pen(writable)
        assertTrue(pen.down(100f, 200f, .3f, 0f, 0f, 5000, hasTilt = false, hasOrientation = false))
        pen.flush()
        pen.move(110f, 210f, .5f, 0f, 0f, 5008); pen.move(120f, 220f, .7f, 0f, 0f, 5016); pen.flush()
        pen.up()
        pump.pump()

        val begin = out.frames[0]
        assertEquals("mac-note", begin.noteId)
        assertEquals("mac-sheet", begin.sheetId)
        val b = begin.body as PreviewBody.Begin
        assertEquals(CoordinateSpace.VIEW_RELATIVE, b.space)
        assertEquals(12u, b.viewportRevision)
        assertEquals(brush, b.brush)
        assertEquals(listOf(100f), samples(1).channels.x)
        assertEquals(listOf(110f, 120f), samples(2).channels.x)
        assertEquals(PreviewBody.End(3u), out.frames[3].body)

        val commit = out.commits.single()
        assertEquals("mac-note", commit.noteId)
        assertEquals("mac-sheet", commit.sheetId)
        assertEquals(begin.strokeId, commit.strokeId)
        assertEquals(CoordinateSpace.VIEW_RELATIVE, commit.space)
        assertEquals(brush, commit.brush)
        assertEquals(listOf(1, 2, 3), commit.samples.take(3).map { it.toInt() })
        val whole = encoded.single()
        assertEquals(listOf(100f, 110f, 120f), whole.x)
        assertEquals(listOf(200f, 210f, 220f), whole.y)
        assertEquals(listOf(0u, 8u, 16u), whole.elapsedMs)
        assertEquals(listOf(.3f, .5f, .7f), whole.pressure)
    }

    @Test fun aStreamStrokeThatLostABatchIsCancelledNeverCommittedShort() {
        val pen = pen(writable)
        pen.down(1f, 1f, .5f, 0f, 0f, 1, false, false); pen.flush()
        repeat(PenFeed.CAPACITY + 5) { pen.move(it.toFloat(), 0f, .5f, 0f, 0f, 2L + it); pen.flush() }
        pen.up()
        pump.pump()
        assertTrue(out.commits.isEmpty())
        assertEquals(PreviewBody.Cancel, out.frames.last().body)
    }

    @Test fun aRefusedCommitIsRetriedNeverDropped() {
        out.refuseCommits = 2
        val pen = pen(writable)
        pen.down(1f, 1f, .5f, 0f, 0f, 1, false, false); pen.flush(); pen.up()
        pump.pump()
        assertTrue(out.commits.isEmpty())
        pump.pump()
        assertTrue(out.commits.isEmpty())
        pump.pump()
        assertEquals(1, out.commits.size)
    }

    @Test fun noWritableTargetMeansNothingIsSentAndTheReasonIsGiven() {
        feed.enabled = false
        assertEquals("Waiting for the Mac", pen(writable).problem())
        feed.enabled = true
        assertEquals("No note open on the Mac", pen(StreamTarget()).problem())
        assertEquals("The Mac's note is read-only", pen(StreamTarget("n", "s", false, 1)).problem())
        assertNull(pen(writable).problem())
        for (t in listOf(StreamTarget(), StreamTarget("n", "s", false, 1))) {
            val p = pen(t)
            assertFalse(p.down(1f, 1f, .5f, 0f, 0f, 1, false, false))
            p.move(2f, 2f, .5f, 0f, 0f, 2); p.flush(); p.up()
            p.hover(3f, 3f, 3)
        }
        pump.pump()
        assertTrue(out.frames.isEmpty())
        assertTrue(out.commits.isEmpty())
    }

    @Test fun hoverSendsHoverFramesWithTheirOwnCounterAndNoStrokeId() {
        val pen = pen(writable)
        pen.hover(10f, 20f, 1); pen.hover(11f, 21f, 2)
        pump.pump()
        assertEquals(listOf(0u, 1u), out.frames.map { it.sequence })
        assertTrue(out.frames.all { it.strokeId == null && it.body is PreviewBody.Hover })
        assertEquals(PreviewBody.Hover(11f, 21f), out.frames[1].body)
        // While a stroke is down, hover is not sent.
        pen.down(1f, 1f, .5f, 0f, 0f, 1, false, false); pen.hover(5f, 5f, 3); pen.flush()
        pump.pump()
        assertEquals(2, out.frames.count { it.body is PreviewBody.Hover })
    }

    @Test fun streamCancelSendsCancelAndNoCommit() {
        val pen = pen(writable)
        pen.down(1f, 1f, .5f, 0f, 0f, 1, false, false); pen.flush()
        pen.cancel()
        pump.pump()
        assertEquals(PreviewBody.Cancel, out.frames.last().body)
        assertTrue(out.commits.isEmpty())
    }

    @Test fun aReconnectForgetsTheQueuedRingAndStrokeInProgress() {
        val mirror = CanvasMirror(feed, pool)
        mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false)
        mirror.sample(1f, 1f, .5f, 0f, 0f, 1); mirror.flush()
        pump.reset()
        pump.pump()
        assertTrue(out.frames.isEmpty())
    }

    // ---- mapping ----

    @Test fun streamAreaIsTheLargest4800By3000RectangleCentred() {
        val wide = StreamMapping.area(PadRect(100.0, 20.0, 2000.0, 500.0))
        assertEquals(800.0, wide.width, 1e-9) // height-limited: 500 * 1.6
        assertEquals(500.0, wide.height, 1e-9)
        assertEquals(100.0 + (2000.0 - 800.0) / 2, wide.left, 1e-9)
        assertEquals(20.0, wide.top, 1e-9)
        val tall = StreamMapping.area(PadRect(0.0, 0.0, 800.0, 1000.0))
        assertEquals(800.0, tall.width, 1e-9)
        assertEquals(500.0, tall.height, 1e-9)
        assertEquals(250.0, tall.top, 1e-9)
    }

    @Test fun streamUsesOneScaleBothWaysAndClampsOutsideTheArea() {
        val area = PadRect(100.0, 50.0, 960.0, 600.0)
        assertEquals(0f to 0f, StreamMapping.toView(area, 100.0, 50.0))
        assertEquals(4800f to 3000f, StreamMapping.toView(area, 1060.0, 650.0))
        assertEquals(2400f to 1500f, StreamMapping.toView(area, 580.0, 350.0))
        // One scale: 96 px across is 480 units across and 600 units down is 3000 units down; the ratio is the same.
        val (x, y) = StreamMapping.toView(area, 100.0 + 96, 50.0 + 96)
        assertEquals(x, y, 1e-3f)
        assertEquals(0f to 3000f, StreamMapping.toView(area, -500.0, 9_999.0))
        assertTrue(StreamMapping.contains(area, 580.0, 350.0))
        assertFalse(StreamMapping.contains(area, 99.0, 350.0))
    }

    @Test fun streamMappingAgreesWithThePadMapperOnA4800By3000View() {
        val bounds = PadRect(88.0, 20.0, 2300.0, 1000.0)
        val m = TabletMapper(bounds, 4800.0, 3000.0)
        assertEquals(StreamMapping.area(bounds), m.activeArea)
        for ((px, py) in listOf(500.0 to 300.0, 1234.5 to 678.9, 88.0 to 20.0)) {
            val (u, v) = m.uv(px, py)
            val (x, y) = StreamMapping.toView(m.activeArea, px, py)
            assertEquals(u.coerceIn(0.0, 1.0) * 4800.0, x.toDouble(), 1e-2)
            assertEquals(v.coerceIn(0.0, 1.0) * 3000.0, y.toDouble(), 1e-2)
        }
    }
}
