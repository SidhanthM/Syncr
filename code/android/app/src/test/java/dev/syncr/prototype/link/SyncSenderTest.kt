package dev.syncr.prototype.link

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSenderTest {
    private fun SessionHarness.connected(vararg notes: NoteMeta, open: String? = null): SessionHarness {
        source.notes += notes
        session.adopt(null, false, open)
        session.start(); drain(); welcome(); drain()
        return this
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Collects one transfer as the Mac would: the `file`, its chunks in order, the `file_end`. */
    private class Received {
        var file: Control.File? = null
        val chunks = ArrayList<Wire.FileChunk>()
        var ended = false
        fun data() = ByteArrayOutputStream().also { o -> chunks.forEach { o.write(it.data) } }.toByteArray()
    }

    private fun SessionHarness.receive(): Received {
        val r = Received()
        while (true) {
            val m = scheduler.next() ?: break
            when (Wire.kind(m)) {
                Wire.KIND_CONTROL -> when (val c = Control.parse(m)) {
                    is Control.File -> r.file = c
                    is Control.FileEnd -> r.ended = true
                    else -> Unit
                }
                Wire.KIND_FILE -> r.chunks += Wire.parseFileChunk(m)
            }
        }
        return r
    }

    @Test fun manifestListsEveryNoteAfterWelcome() {
        val h = SessionHarness()
        h.source.notes += listOf(NoteMeta("a", 1, "A"), NoteMeta("b", 2, "B"))
        h.session.start(); h.drain(); h.welcome()
        assertEquals(Control.Notes(listOf(NoteMeta("a", 1, "A"), NoteMeta("b", 2, "B"))), h.controls().single())
        h.cleanup()
    }

    @Test fun hugeManifestsSplitIntoFramesThatFit() {
        val h = SessionHarness()
        repeat(3000) { h.source.notes += NoteMeta("%026d".format(it), it.toLong(), "Title ".repeat(30) + it) }
        h.session.start(); h.drain(); h.welcome()
        var count = 0
        while (true) {
            val m = h.scheduler.next() ?: break
            assertTrue(m.size <= Wire.MAX_MESSAGE)
            count += (Control.parse(m) as Control.Notes).notes.size
        }
        assertEquals(3000, count)
        h.cleanup()
    }

    @Test fun wantStartsATransferWithCorrectSizeHashAndChunks() {
        val data = ByteArray(150_001) { (it * 31).toByte() }
        val h = SessionHarness().connected(NoteMeta("a", 5, "A"))
        h.source.contents["a"] = data
        h.mac(Control.Want(listOf("a")))
        h.loop.runPosted()
        val r = h.receive()
        val file = r.file!!
        assertEquals(1L, file.xfer)
        assertEquals("a", file.id)
        assertEquals(5L, file.seq)
        assertEquals(150_001L, file.size)
        assertEquals(sha(data), file.sha256)
        assertEquals(3L, file.chunks) // 65,000 + 65,000 + 20,001
        assertEquals(listOf(0L, 1L, 2L), r.chunks.map { it.index })
        assertEquals(listOf(65_000, 65_000, 20_001), r.chunks.map { it.data.size })
        assertTrue(r.chunks.all { it.transfer == 1L })
        assertArrayEquals(data, r.data())
        assertTrue(r.ended)
        // The temp file is gone once it is sent.
        h.sync.tick()
        assertEquals(0, h.temp.listFiles()!!.size)
        h.mac(Control.Have("a", 5))
        assertEquals(listOf("a" to 5L), h.sync.delivered)
        assertTrue(!h.sync.busy)
        h.cleanup()
    }

    @Test fun exactMultipleOfChunkSizeAndEmptyEdgeCases() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"))
        h.source.contents["a"] = ByteArray(130_000) { 1 }
        h.mac(Control.Want(listOf("a"))); h.loop.runPosted()
        val r = h.receive()
        assertEquals(2L, r.file!!.chunks)
        assertEquals(2, r.chunks.size)
        h.cleanup()
    }

    @Test fun oneTransferAtATimeInTheOrderTheMacWanted() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"), NoteMeta("b", 1, "B"))
        h.source.contents["a"] = ByteArray(10) { 1 }
        h.source.contents["b"] = ByteArray(10) { 2 }
        h.mac(Control.Want(listOf("a", "b", "a"))); h.loop.runPosted()
        val first = h.receive()
        assertEquals("a", first.file!!.id)
        // b waits for the Mac's answer about a.
        assertTrue(h.sync.busy)
        h.mac(Control.Have("a", 1)); h.loop.runPosted()
        val second = h.receive()
        assertEquals("b", second.file!!.id)
        assertEquals(2L, second.file!!.xfer)
        h.cleanup()
    }

    @Test fun fileErrorRetriesOnceWithAFreshExportThenGivesUp() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"))
        h.source.contents["a"] = ByteArray(10) { 7 }
        h.mac(Control.Want(listOf("a"))); h.loop.runPosted()
        val first = h.receive()
        h.mac(Control.FileError(first.file!!.xfer, "a", "hash")); h.loop.runPosted()
        val retry = h.receive()
        assertEquals("a", retry.file!!.id)
        assertEquals(first.file!!.xfer + 1, retry.file!!.xfer)
        assertArrayEquals(ByteArray(10) { 7 }, retry.data())
        h.mac(Control.FileError(retry.file!!.xfer, "a", "hash")); h.loop.runPosted()
        assertTrue(h.receive().file == null) // once per connection
        assertTrue(!h.sync.busy)
        assertEquals(0, h.temp.listFiles()!!.size)
        h.cleanup()
    }

    @Test fun fileErrorForAnUnknownTransferIsIgnored() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"))
        h.source.contents["a"] = ByteArray(10)
        h.mac(Control.Want(listOf("a"))); h.loop.runPosted(); h.receive()
        h.mac(Control.FileError(99, "a", "hash")); h.loop.runPosted()
        assertTrue(h.sync.busy)
        h.cleanup()
    }

    @Test fun wantForNotesWeDoNotHaveIsIgnored() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"))
        h.mac(Control.Want(listOf("zzz"))); h.loop.runPosted()
        assertTrue(h.receive().file == null)
        assertTrue(!h.sync.busy)
        h.cleanup()
    }

    @Test fun anExportThatFailsIsSkippedAndTheNextWantedNoteGoesOut() {
        val h = SessionHarness().connected(NoteMeta("gone", 1, "G"), NoteMeta("b", 1, "B"))
        h.source.contents["b"] = ByteArray(5)
        h.mac(Control.Want(listOf("gone", "b"))); h.loop.runPosted()
        assertEquals("b", h.receive().file!!.id)
        h.cleanup()
    }

    @Test fun aClosedConnectionDropsTheTransferAndItsTempFile() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"))
        h.source.contents["a"] = ByteArray(200_000)
        h.mac(Control.Want(listOf("a"))); h.loop.runPosted()
        h.scheduler.next(); h.scheduler.next() // the file message, one chunk
        h.sync.onClosed()
        assertEquals(0, h.temp.listFiles()!!.size)
        assertTrue(!h.sync.busy)
        h.cleanup()
    }

    @Test fun anExportFinishingAfterTheConnectionClosedIsDiscarded() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"))
        h.source.contents["a"] = ByteArray(10)
        h.mac(Control.Want(listOf("a")))
        h.sync.onClosed()
        h.loop.runPosted()
        assertEquals(0, h.temp.listFiles()!!.size)
        assertTrue(h.receive().file == null)
        h.cleanup()
    }

    // ---- change notices ----

    @Test fun anOpenNotesSavesAreAnnouncedAtOnceEachOnce() {
        val a1 = NoteMeta("a", 1, "A")
        val h = SessionHarness().connected(a1, open = "a")
        h.sync.update(listOf(NoteMeta("a", 2, "A")), "a")
        assertEquals(Control.Notes(listOf(NoteMeta("a", 2, "A"))), h.controls().single())
        h.sync.update(listOf(NoteMeta("a", 3, "A")), "a")
        assertEquals(Control.Notes(listOf(NoteMeta("a", 3, "A"))), h.controls().single())
        // The same list again is silent, however long it is left.
        h.sync.update(listOf(NoteMeta("a", 3, "A")), "a")
        h.clock.now += 5_000
        h.sync.tick()
        assertTrue(h.controls().isEmpty())
        h.cleanup()
    }

    @Test fun closingTheNoteAnnouncesAtOnce() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"), open = "a")
        h.sync.update(listOf(NoteMeta("a", 2, "A")), "a")
        assertEquals(Control.Notes(listOf(NoteMeta("a", 2, "A"))), h.controls().single())
        // The library list may still carry the old sequence when the note closes: the Mac was already told.
        h.sync.update(listOf(NoteMeta("a", 1, "A")), null)
        h.sync.update(listOf(NoteMeta("a", 2, "A")), null)
        assertTrue(h.controls().isEmpty())
        h.cleanup()
    }

    @Test fun aChangeToAClosedNoteOrANewNoteGoesOutAtOnce() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"))
        h.sync.update(listOf(NoteMeta("a", 4, "A"), NoteMeta("n", 1, "New")), null)
        assertEquals(Control.Notes(listOf(NoteMeta("a", 4, "A"), NoteMeta("n", 1, "New"))), h.controls().single())
        // An unchanged list is silent.
        h.sync.update(listOf(NoteMeta("a", 4, "A"), NoteMeta("n", 1, "New")), null)
        assertTrue(h.controls().isEmpty())
        h.cleanup()
    }

    @Test fun nothingIsSentWhileDisconnected() {
        val h = SessionHarness()
        h.sync.update(listOf(NoteMeta("a", 4, "A")), null)
        assertTrue(h.controls().isEmpty())
        h.cleanup()
    }

    @Test fun aReconnectSendsTheWholeManifestAgain() {
        val h = SessionHarness().connected(NoteMeta("a", 1, "A"))
        h.sync.onClosed()
        h.sync.onWelcome(listOf(NoteMeta("a", 1, "A")), null)
        assertEquals(Control.Notes(listOf(NoteMeta("a", 1, "A"))), h.controls().single())
        h.cleanup()
    }
}
