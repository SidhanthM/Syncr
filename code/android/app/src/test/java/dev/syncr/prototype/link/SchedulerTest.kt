package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.CoordinateSpace
import dev.syncr.core.InkColour
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {
    private val clock = FakeClock()
    private val out = FakeOut()
    private val feed = PenFeed(clock).also { it.enabled = true }
    private val pump = FeedPump(feed, out, clock, { ByteArray(1) })
    private val scheduler = SendScheduler(out, pump)
    private val brush = Brush(BrushFamily.PEN, BrushSize.MEDIUM, InkColour.INK)

    private fun fileOf(chunks: Int): FileStream {
        val f = Files.createTempFile("syncr-sched", ".bin").toFile().also { it.deleteOnExit(); it.writeBytes(ByteArray(Wire.FILE_CHUNK_DATA * chunks)) }
        return FileStream(f, 1, chunks.toLong())
    }

    private fun kind(m: ByteArray?) = m?.let { Wire.kind(it) }

    @Test fun controlBeatsPreviewBeatsFileChunks() {
        scheduler.file = fileOf(3)
        feed.begin("n", "s", "id", brush, CoordinateSpace.CANVAS, 0, false, false, false)
        feed.sample(1f, 2f, .5f, 0f, 0f, 10); feed.flush()
        scheduler.sendControl(Control.Ping(1, 1))
        assertEquals(Wire.KIND_CONTROL, kind(scheduler.next()))
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next())) // Begin
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next())) // Samples
        assertNull(scheduler.next()) // the pen is down: no chunk yet
        feed.end()
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next())) // End
        assertNull(scheduler.next()) // still within the grace after pen-up
        clock.now += 400
        assertEquals(Wire.KIND_FILE, kind(scheduler.next()))
    }

    @Test fun aSmallFileGoesWhileThePenIsWritingALargeOneWaits() {
        feed.begin("n", "s", "id", brush, CoordinateSpace.CANVAS, 0, false, false, false)
        feed.sample(1f, 2f, .5f, 0f, 0f, 10); feed.flush()
        scheduler.file = fileOf(1)
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next())) // the live frames always go first
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next()))
        assertEquals(Wire.KIND_FILE, kind(scheduler.next())) // a delta of a few words does not wait for the pen
        assertEquals(Wire.KIND_CONTROL, kind(scheduler.next())) // file_end
        scheduler.file = fileOf(2)
        assertNull(scheduler.next()) // a larger copy still does
    }

    @Test fun aPenHeldStillDoesNotHoldChunksBackForEver() {
        scheduler.file = fileOf(2)
        feed.begin("n", "s", "id", brush, CoordinateSpace.CANVAS, 0, false, false, false)
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next()))
        clock.now += 2_999
        assertNull(scheduler.next())
        clock.now += 1
        assertEquals(Wire.KIND_FILE, kind(scheduler.next()))
    }

    @Test fun aPreviewArrivingMidTransferWaitsForAtMostOneChunk() {
        scheduler.file = fileOf(5)
        assertEquals(Wire.KIND_FILE, kind(scheduler.next())) // chunk 0 is on the wire
        feed.begin("n", "s", "id", brush, CoordinateSpace.CANVAS, 0, false, false, false)
        feed.sample(1f, 2f, .5f, 0f, 0f, 10); feed.flush()
        // The very next message is the preview, not chunk 1, and chunk 1 waits for the pen to go quiet.
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next()))
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next()))
        assertNull(scheduler.next())
        feed.end()
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next()))
        clock.now += 400
        assertEquals(Wire.KIND_FILE, kind(scheduler.next()))
    }

    @Test fun everyFrameOfAStrokeGoesBeforeTheNextChunk() {
        scheduler.file = fileOf(8)
        val order = ArrayList<Int>()
        order += kind(scheduler.next())!!
        for (batch in 0 until 6) {
            feed.begin("n", "s", "id$batch", brush, CoordinateSpace.CANVAS, 0, false, false, false)
            feed.sample(1f, 2f, .5f, 0f, 0f, 10L); feed.flush(); feed.end()
            order += kind(scheduler.next())!!
            while (true) {
                val k = kind(scheduler.next()) ?: break
                order += k
                if (k == Wire.KIND_FILE) break
            }
        }
        // Between two chunks only previews appear: a chunk never sits in front of a ready preview.
        var chunksInARow = 0
        for (k in order) { if (k == Wire.KIND_FILE) chunksInARow++ else chunksInARow = 0; assertTrue(chunksInARow <= 1) }
    }

    @Test fun durableCommitsGoAfterPreviewAndBeforeFileChunks() {
        scheduler.file = fileOf(2)
        out.commit(dev.syncr.core.StrokeCommit("n", "s", "i", brush, CoordinateSpace.VIEW_RELATIVE, ByteArray(1)))
        feed.begin("n", "s", "id", brush, CoordinateSpace.CANVAS, 0, false, false, false)
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next()))
        assertEquals(Wire.KIND_STROKE, kind(scheduler.next()))
        feed.cancel()
        assertEquals(Wire.KIND_PREVIEW, kind(scheduler.next()))
        clock.now += 400
        assertEquals(Wire.KIND_FILE, kind(scheduler.next()))
    }

    @Test fun fileStreamYieldsChunksThenFileEndThenNothing() {
        val s = fileOf(2)
        assertEquals(Wire.KIND_FILE, kind(s.next()))
        assertEquals(Wire.KIND_FILE, kind(s.next()))
        val end = s.next()!!
        assertEquals(Control.FileEnd(1), Control.parse(end))
        assertTrue(s.finished)
        assertNull(s.next())
    }

    @Test fun pointerMotionHoldsALargeTransferBackAsThePenDoes() {
        scheduler.file = fileOf(3)
        scheduler.sendControl(Control.Pointer(1, PointerInput(dx = 1.0)))
        assertEquals(Wire.KIND_CONTROL, kind(scheduler.next()))
        assertNull(scheduler.next()) // the trackpad is moving: no chunk
        assertTrue(scheduler.pointerActive())
        assertTrue(!scheduler.pointerActive(System.nanoTime() + SendScheduler.INPUT_GRACE_NS))
        scheduler.file = fileOf(1)
        assertEquals(Wire.KIND_FILE, kind(scheduler.next())) // a small one still goes
    }

    /** Runs what it is given only when told to: the files thread, by hand. */
    private class HandRun : java.util.concurrent.Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue += command }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    @Test fun aFileStreamReadsAheadOffTheLinkThreadAndWakesItWhenAChunkIsReady() {
        val f = Files.createTempFile("syncr-ahead", ".bin").toFile().also { it.deleteOnExit(); it.writeBytes(ByteArray(2500) { it.toByte() }) }
        val files = HandRun()
        var woken = 0
        val s = FileStream(f, 4, 3, chunkBytes = 1000, files = files, wake = { woken++ })
        assertNull(s.next()) // nothing read yet: the link thread does not wait for the disk
        files.runAll()
        assertTrue(woken >= 1)
        val sizes = ArrayList<Int>()
        repeat(3) { sizes += Wire.parseFileChunk(s.next() ?: run { files.runAll(); s.next()!! }).data.size }
        assertEquals(listOf(1000, 1000, 500), sizes)
        assertEquals(Control.FileEnd(4), Control.parse(s.next()!!))
        assertTrue(s.finished)
        files.runAll() // the close runs on the files thread too
    }

    @Test fun aReadThatFailsOnTheFilesThreadIsThrownToTheLinkThread() {
        val f = Files.createTempFile("syncr-ahead", ".bin").toFile().also { it.deleteOnExit(); it.writeBytes(ByteArray(10)) }
        val files = HandRun()
        val failing = object : BulkDisk {
            override fun read(file: java.io.RandomAccessFile, buffer: ByteArray, offset: Int, length: Int): Int = throw java.io.IOException("disk")
        }
        val s = FileStream(f, 1, 1, failing, 1000, files)
        assertNull(s.next())
        files.runAll()
        assertTrue(runCatching { s.next() }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun backoffRetriesAtOnceThenHalfOneTwoFourEightTenSecondsAndResets() {
        val b = Backoff { 0.5 } // the middle of the jitter: none
        assertEquals(listOf(0L, 500, 1000, 2000, 4000, 8000, 10_000, 10_000), List(8) { b.nextMs() })
        b.reset()
        assertEquals(0L, b.nextMs())
        assertEquals(500L, b.nextMs())
    }

    @Test fun backoffJitterStaysWithinTwentyPercent() {
        val low = Backoff { 0.0 }; val high = Backoff { 1.0 }
        val lows = List(7) { low.nextMs() }; val highs = List(7) { high.nextMs() }
        assertEquals(listOf(0L, 400, 800, 1600, 3200, 6400, 8000), lows)
        assertEquals(listOf(0L, 600, 1200, 2400, 4800, 9600, 12_000), highs)
    }

    @Test fun latencyStatsPercentiles() {
        val s = LatencyStats()
        assertEquals("n=0", s.summary())
        for (i in 1..100) s.add(i * 1000L)
        assertEquals("n=100 p50=50.0ms p95=95.0ms max=100.0ms", s.summary())
        val log = ArrayList<String>()
        val l = LatencyLog { log += it }
        l.penToWrite.add(2000); l.roundTrip.add(4000)
        l.report("stroke 1")
        assertTrue(log.single().contains("pen-event-to-socket-write n=1"))
        assertTrue(log.single().contains("excludes: digitiser, display scan-out"))
    }
}
