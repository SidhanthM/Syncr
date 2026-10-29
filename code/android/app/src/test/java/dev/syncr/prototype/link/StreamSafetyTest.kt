package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.PreviewBody
import dev.syncr.core.SampleChannels
import dev.syncr.prototype.tablet.MacScreen
import dev.syncr.prototype.tablet.MappingMode
import dev.syncr.prototype.tablet.PadRect
import dev.syncr.prototype.tablet.PhoneDpi
import dev.syncr.prototype.tablet.TabletMapper
import org.junit.Assert.*
import org.junit.Test

class StreamSafetyTest {
    private val feed = PenFeed().also { it.enabled = true }
    private val pool = IdPool({ "12345678-stroke-id" }).also { it.topUp() }
    private val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)
    private var target = StreamTarget("note", "sheet", true, 8)
    private val reasons = ArrayList<String>()
    private val logs = ArrayList<String>()
    private val out = FakeOut()
    private val encoded = ArrayList<SampleChannels>()
    private val pump = FeedPump(feed, out, FakeClock(), { encoded += it; byteArrayOf(1) }, logs::add)
    private val pen = StreamPen(feed, pool, { target }, { brush }, reasons::add)

    private fun down() = pen.down(10f, 20f, .5f, 0f, 0f, 100, false, false).also { pen.flush() }

    @Test fun revisionChangeCancelsWholeGestureAndDoesNotRestartUntilNextDown() {
        assertTrue(down())
        target = target.copy(rev = 9)
        pen.move(30f, 40f, .6f, 0f, 0f, 110); pen.flush(); pen.up()
        pump.pump()
        assertEquals(PreviewBody.Cancel, out.frames.last().body)
        assertTrue(out.commits.isEmpty())
        assertEquals(listOf("target changed mid-stroke (including rev)"), reasons)
        assertTrue(down()); pen.up(); pump.pump()
        assertEquals(9u, (out.frames.filter { it.body is PreviewBody.Begin }.last().body as PreviewBody.Begin).viewportRevision)
        assertEquals(1, out.commits.size)
    }

    @Test fun targetOrWritabilityChangeAtUpAlsoCancels() {
        for (next in listOf(target.copy(noteId = "other"), target.copy(sheetId = "other"), target.copy(writable = false))) {
            assertTrue(down())
            val before = target
            target = next
            pen.up(); pump.pump()
            assertEquals(PreviewBody.Cancel, out.frames.last().body)
            assertTrue(out.commits.isEmpty())
            target = before
        }
    }

    @Test fun connectionLostBeforeUpNeverQueuesCommit() {
        assertTrue(down())
        feed.enabled = false
        pen.up(); pump.pump()
        assertTrue(out.commits.isEmpty())
        assertEquals(listOf("connection lost mid-stroke"), reasons)
    }

    @Test fun everyRejectedDownExplainsWhyWithoutLoggingSamplesOrHover() {
        feed.enabled = false
        assertFalse(down())
        feed.enabled = true
        target = StreamTarget()
        assertFalse(down())
        target = StreamTarget("note", "sheet", false, 1)
        assertFalse(down())
        target = target.copy(writable = true)
        val noIds = StreamPen(feed, IdPool({ "unused" }), { target }, { brush }, reasons::add)
        assertFalse(noIds.down(1f, 1f, .5f, 0f, 0f, 1, false, false))
        noIds.move(2f, 2f, .5f, 0f, 0f, 2); noIds.flush(); noIds.up()
        pump.pump()
        assertEquals(listOf("link not connected", "no target", "target not writable", "no stroke ID available"), reasons)
        assertTrue(out.frames.isEmpty())
    }

    @Test fun realHistoricalBatchOrderPressureAndFinalPointArePreserved() {
        assertTrue(pen.down(0f, 0f, -1f, .2f, -.4f, 100, true, true))
        pen.flush()
        // TabletPadView traverses history oldest first, then current, then the final up batch.
        for (i in 1..3) pen.move(i.toFloat(), i.toFloat(), i * .5f, .2f, -.4f, 100L + i * 4)
        pen.flush()
        pen.move(4f, 4f, 0f, .3f, .4f, 116); pen.flush(); pen.up()
        pump.pump()
        val samples = encoded.single()
        assertEquals(listOf(0f, 1f, 2f, 3f, 4f), samples.x)
        assertEquals(listOf(0u, 4u, 8u, 12u, 16u), samples.elapsedMs)
        assertEquals(listOf(0f, .5f, 1f, 1f, 0f), samples.pressure)
        assertEquals(listOf(.2f, .2f, .2f, .2f, .3f), samples.tilt)
        assertEquals(listOf(-.4f, -.4f, -.4f, -.4f, .4f), samples.orientation)
        assertTrue(logs.any { it == "stream stroke begin id=12345678 note=note sheet=sheet rev=8" })
        assertTrue(logs.contains("stream stroke end id=12345678 samples=5"))
        assertTrue(logs.contains("stream stroke commit queued id=12345678"))
    }

    @Test fun resetDropsOldSessionCommitsIncludingRetryBacklog() {
        out.refuseCommits = 1
        assertTrue(down()); pen.up(); pump.pump()
        pump.reset(); pump.pump()
        assertTrue(out.commits.isEmpty())
        assertTrue(down()); pen.up(); pump.pump()
        assertNotNull(out.pop())
        pump.reset()
        assertNull(out.pop())
    }

    @Test fun actualPadMapperAndStreamMappingAgreeInBothOrientations() {
        for (bounds in listOf(PadRect(88.0, 34.0, 2300.0, 1000.0), PadRect(88.0, 34.0, 1000.0, 2300.0))) {
            val mapper = TabletMapper(MappingMode.WHOLE_SCREEN, bounds, PhoneDpi(500.0, 500.0), MacScreen(4800.0, 3000.0))
            val area = mapper.activeArea
            assertEquals(StreamMapping.area(bounds), area)
            assertEquals(0f to 0f, StreamMapping.toView(area, area.left, area.top))
            assertEquals(4800f to 3000f, StreamMapping.toView(area, area.right, area.bottom))
            val center = StreamMapping.toView(area, area.left + area.width / 2, area.top + area.height / 2)
            val moved = StreamMapping.toView(area, area.left + area.width / 2 + 10, area.top + area.height / 2 + 10)
            assertEquals(moved.first - center.first, moved.second - center.second, .001f)
        }
    }
}
