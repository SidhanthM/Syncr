package dev.syncr.prototype.link

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delta sync on the phone (sync-delta.md): the wire additions, the sender's choice and the receiver's fallbacks. */
class DeltaSyncTest {
    private val hex64 = "ab".repeat(32)
    private val heads = mapOf("AUTHOR1" to "01M7PEC69Z6M1T1WDDDTS8FRSZ")

    private fun connected(delta: Boolean = true, vararg notes: NoteMeta): SessionHarness {
        val h = SessionHarness()
        h.source.notes += notes
        h.session.start(); h.drain(); h.welcomeV1(delta = delta); h.loop.runPosted(); h.drain()
        return h
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** One transfer as the Mac would collect it: the `file`, then its chunks and the bytes behind them. */
    private class Sent {
        var file: Control.File? = null
        val bytes = ByteArrayOutputStream()
        var wire = 0L
    }

    private fun SessionHarness.collect(): Sent {
        val s = Sent()
        while (true) {
            val m = scheduler.next() ?: break
            s.wire += m.size + 4
            when (Wire.kind(m)) {
                Wire.KIND_CONTROL -> (Control.parse(m) as? Control.File)?.let { s.file = it }
                Wire.KIND_FILE -> s.bytes.write(Wire.parseFileChunk(m).data)
            }
        }
        return s
    }

    // ---- the wire ----

    @Test fun deltaAdditionsAreWrittenOnlyWhenUsedAndSurviveARoundTrip() {
        val plainHello = Control.Hello("Phone", "0123456789abcdef0123456789abcdef", null).json()
        assertFalse(plainHello.containsKey("delta"))
        val plainWant = Control.Want(listOf("a")).json()
        assertEquals(setOf("t", "ids"), plainWant.keys)
        assertFalse(Control.File(1, "a", 1, 1, hex64, 1).json().containsKey("delta"))
        assertFalse(Control.Welcome(1, "Mac", MAC_ID, 1).json().containsKey("delta"))
        val all = listOf(
            Control.Hello("Phone", "0123456789abcdef0123456789abcdef", null, 1, true),
            Control.Welcome(1, "Mac", MAC_ID, 5, true),
            Control.Want(listOf("a", "b"), mapOf("a" to heads)),
            Control.File(2, "a", 7, 700, hex64, 1, true),
        )
        for (c in all) assertEquals(c, Control.parse(c.encode()))
    }

    @Test fun anOldPeersMessagesReadAsNoDelta() {
        val hello = Control.parse(Control.Hello("Phone", "0123456789abcdef0123456789abcdef", null, 1).encode()) as Control.Hello
        assertFalse(hello.delta)
        val want = Control.parse(Control.Want(listOf("a")).encode()) as Control.Want
        assertNull(want.base)
        assertFalse((Control.parse(Control.File(1, "a", 1, 1, hex64, 1).encode()) as Control.File).delta)
    }

    @Test fun aMalformedBaseIsDroppedNotFatal() {
        fun want(base: Any?): Control.Want {
            val body = Json.write(mapOf("t" to "want", "ids" to listOf("a"), "base" to base))
            val bytes = ByteArray(1 + body.size).also { it[0] = Wire.KIND_CONTROL.toByte(); System.arraycopy(body, 0, it, 1, body.size) }
            return Control.parse(bytes) as Control.Want
        }
        assertNull(want("nope").base)
        assertNull(want(listOf(1, 2)).base)
        assertNull(want(mapOf("a" to "x")).base)
        assertNull(want(mapOf("a" to mapOf("author" to 5L))).base)
        assertEquals(mapOf("a" to heads), want(mapOf("a" to heads, "b" to "junk")).base)
    }

    // ---- the sender ----

    @Test fun aWantWithWhatTheMacHasIsAnsweredWithJustADelta() {
        val h = connected(true, NoteMeta("a", 5, "A"))
        h.source.contents["a"] = ByteArray(10_000_000) { 1 }
        h.source.deltas["a"] = ByteArray(756) { 2 }
        h.mac(Control.Want(listOf("a"), mapOf("a" to heads)))
        h.loop.runPosted()
        val sent = h.collect()
        assertEquals(listOf("a" to heads), h.source.exportedDeltas)
        assertTrue(sent.file!!.delta)
        assertEquals(756L, sent.file!!.size)
        assertEquals(sha(h.source.deltas["a"]!!), sent.file!!.sha256)
        assertArrayEquals(h.source.deltas["a"], sent.bytes.toByteArray())
        h.cleanup()
    }

    @Test fun noDeltaIsOfferedToAMacThatDidNotSayItUnderstandsOne() {
        val h = connected(false, NoteMeta("a", 5, "A"))
        h.source.contents["a"] = ByteArray(1000) { 1 }
        h.source.deltas["a"] = ByteArray(10)
        h.mac(Control.Want(listOf("a"), mapOf("a" to heads)))
        h.loop.runPosted()
        val sent = h.collect()
        assertTrue(h.source.exportedDeltas.isEmpty())
        assertFalse(sent.file!!.delta)
        assertEquals(1000L, sent.file!!.size)
        h.cleanup()
    }

    @Test fun whenTheCoreWillNotMakeADeltaTheWholeNoteGoes() {
        val h = connected(true, NoteMeta("a", 5, "A"))
        h.source.contents["a"] = ByteArray(1000) { 1 }
        h.mac(Control.Want(listOf("a"), mapOf("a" to heads))) // no delta available in the fixture: unanchored or too big
        h.loop.runPosted()
        val sent = h.collect()
        assertEquals(1, h.source.exportedDeltas.size)
        assertFalse(sent.file!!.delta)
        assertEquals(1000L, sent.file!!.size)
        h.cleanup()
    }

    @Test fun aRefusedDeltaIsRetriedAsAWholeNote() {
        val h = connected(true, NoteMeta("a", 5, "A"))
        h.source.contents["a"] = ByteArray(1000) { 1 }
        h.source.deltas["a"] = ByteArray(10)
        h.mac(Control.Want(listOf("a"), mapOf("a" to heads)))
        h.loop.runPosted()
        val first = h.collect().file!!
        assertTrue(first.delta)
        h.mac(Control.FileError(first.xfer, "a", "apply: no"))
        h.loop.runPosted()
        val again = h.collect().file!!
        assertFalse(again.delta) // the base was used up: the retry is a copy
        assertEquals(1000L, again.size)
        assertEquals(1, h.source.exportedDeltas.size)
        h.cleanup()
    }

    @Test fun aDeltaShortNoticeFreesTheSlotWithoutRetryingAndTheNextWantGetsTheWholeNote() {
        val h = connected(true, NoteMeta("a", 5, "A"))
        h.source.contents["a"] = ByteArray(1000) { 1 }
        h.source.deltas["a"] = ByteArray(10)
        h.mac(Control.Want(listOf("a"), mapOf("a" to heads)))
        h.loop.runPosted()
        val first = h.collect().file!!
        h.mac(Control.FileError(first.xfer, "a", SyncSender.DELTA_SHORT))
        h.loop.runPosted()
        assertTrue(h.collect().file == null) // no automatic retry
        h.mac(Control.Want(listOf("a")))
        h.loop.runPosted()
        val whole = h.collect().file!!
        assertFalse(whole.delta)
        assertEquals(1000L, whole.size)
        h.cleanup()
    }

    @Test fun oneAnnounceOfATenMegabyteNoteCostsAFewKilobytesNotTenMegabytes() {
        // Sizes measured by the core test `a_one_stroke_change_to_a_ten_megabyte_note...`: 10,145,792 bytes whole, 756 as a delta.
        val whole = ByteArray(10_145_792) { (it * 7).toByte() }
        val small = ByteArray(756) { (it * 3).toByte() }
        fun cost(withBase: Boolean): Long {
            val h = connected(true, NoteMeta("a", 5, "A"))
            h.source.contents["a"] = whole
            h.source.deltas["a"] = small
            h.mac(Control.Want(listOf("a"), if (withBase) mapOf("a" to heads) else null))
            h.loop.runPosted()
            var wire = 0L
            while (true) {
                val sent = h.collect()
                wire += sent.wire
                val file = sent.file ?: break
                if (sent.bytes.size().toLong() >= file.size) break
                // more chunks are paced behind the socket; drain until the file ends
            }
            h.cleanup()
            return wire
        }
        val before = cost(false)
        val after = cost(true)
        println("bytes on the wire for one announce answered: whole copy $before, delta $after")
        assertTrue("whole copy is at least 10 MB: $before", before >= 10_145_792)
        assertTrue("delta is under 2 KB: $after", after < 2_000)
    }

    // ---- the receiver ----

    private fun SessionHarness.announced(id: String = "a", digest: String = hex64) {
        mac(Control.Notes(listOf(NoteMeta(id, 3, "A", digest, 3))))
        loop.runPosted(); loop.runPosted()
    }

    @Test fun aNoteThePhoneHoldsIsWantedWithWhatItHas() {
        val h = connected(true)
        h.source.headsOf["a"] = heads
        h.announced()
        assertEquals(Control.Want(listOf("a"), mapOf("a" to heads)), h.controls().single())
        h.cleanup()
    }

    @Test fun aNoteThePhoneLacksIsWantedWhole() {
        val h = connected(true)
        h.announced()
        assertEquals(Control.Want(listOf("a")), h.controls().single())
        h.cleanup()
    }

    @Test fun aMacWithoutDeltasIsAskedTheOldWay() {
        val h = connected(false)
        h.source.headsOf["a"] = heads
        h.announced()
        assertEquals(Control.Want(listOf("a")), h.controls().single())
        h.cleanup()
    }

    private fun SessionHarness.deliver(xfer: Long, id: String, bytes: ByteArray, delta: Boolean) {
        sync.receiver.onFile(Control.File(xfer, id, 3, bytes.size.toLong(), sha(bytes), 1, delta))
        sync.receiver.onChunk(Wire.fileChunk(xfer, 0, bytes))
        sync.receiver.onFileEnd(xfer)
        loop.runPosted()
    }

    @Test fun aDeltaThatAppliedWholeIsAnsweredWithHave() {
        val h = connected(true)
        h.source.headsOf["a"] = heads
        h.announced(); h.drain()
        h.deliver(1, "a", ByteArray(40) { 5 }, delta = true)
        assertEquals(listOf("a" to 40L), h.source.deltasApplied)
        assertTrue(h.source.copiesApplied.isEmpty())
        val have = h.controls().single() as Control.Have
        assertEquals("a", have.id)
        h.cleanup()
    }

    @Test fun aDeltaThatFellShortIsNotTrustedTheWholeNoteIsAskedFor() {
        val h = connected(true)
        h.source.headsOf["a"] = heads
        h.source.deltaOutcome = CopyOutcome.Applied(1, NoteDigest("0".repeat(64), 1), "delta-short", 0)
        h.announced(); h.drain()
        h.deliver(1, "a", ByteArray(40) { 5 }, delta = true)
        h.loop.runPosted()
        val sent = h.controls()
        // No Have. The sender's transfer slot is freed first (or its queue would wait behind it), then the note is asked for whole.
        assertEquals(listOf(Control.FileError(1, "a", SyncSender.DELTA_SHORT), Control.Want(listOf("a"))), sent)
        // The whole note then arrives and is applied as a copy.
        h.deliver(2, "a", ByteArray(50) { 6 }, delta = false)
        assertEquals(listOf("a"), h.source.copiesApplied)
        assertTrue(h.controls().single() is Control.Have)
        h.cleanup()
    }

    @Test fun aDeltaThePhoneCouldNotApplyIsRefusedAndTheWholeNoteIsAskedFor() {
        val h = connected(true)
        h.source.headsOf["a"] = heads
        h.source.deltaOutcome = CopyOutcome.Refused("no such note")
        h.announced(); h.drain()
        h.deliver(1, "a", ByteArray(40) { 5 }, delta = true)
        h.loop.runPosted()
        val sent = h.controls()
        assertTrue(sent.any { it is Control.Want && it.base == null && it.ids == listOf("a") })
        h.cleanup()
    }

    @Test fun aWholeCopyStillGoesThroughApplyCopyWhateverTheCapability() {
        val h = connected(true)
        h.announced(); h.drain()
        h.deliver(1, "a", ByteArray(40) { 5 }, delta = false)
        assertEquals(listOf("a"), h.source.copiesApplied)
        assertTrue(h.source.deltasApplied.isEmpty())
        h.cleanup()
    }
}
