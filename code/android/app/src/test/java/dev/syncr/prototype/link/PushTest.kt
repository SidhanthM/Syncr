package dev.syncr.prototype.link

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Latency-v1 T.3 on the phone: a changed note goes to a Mac that takes pushes as just its new operations, unasked. */
class PushTest {
    private val held = mapOf("AUTHOR1" to "01M7PEC69Z6M1T1WDDDTS8FRSZ")
    private val exported = mapOf("AUTHOR1" to "01M7PEC69Z6M1T1WDDDTS8FRT0")
    private val digest = NoteDigest("c".repeat(64), 4)

    private fun connected(push: Boolean = true): SessionHarness {
        val h = SessionHarness()
        h.source.notes += NoteMeta("a", 5, "A")
        h.source.deltas["a"] = ByteArray(756) { 2 }
        h.source.contents["a"] = ByteArray(100_000) { 1 }
        h.source.headsOf["a"] = exported
        h.source.digestsOf["a"] = digest
        h.session.adopt(null, false, "a")
        h.session.start(); h.drain(); h.welcomeV1(push = push); h.loop.runPosted(); h.drain()
        return h
    }

    /** The Mac asks for the note with what it holds, takes the delta and says it now holds the phone's operation set. */
    private fun SessionHarness.teachHeads(equal: Boolean = true) {
        mac(Control.Want(listOf("a"), mapOf("a" to held))); loop.runPosted(); drain()
        mac(Control.Have("a", 5, if (equal) digest.digest else "f".repeat(64), 4)); loop.runPosted()
    }

    private class Sent { var file: Control.File? = null; val bytes = ByteArrayOutputStream(); val controls = ArrayList<Control>() }
    private fun SessionHarness.collect(): Sent {
        val s = Sent()
        while (true) {
            val m = scheduler.next() ?: break
            when (Wire.kind(m)) {
                Wire.KIND_CONTROL -> Control.parse(m)!!.also { c -> s.controls += c; if (c is Control.File) s.file = c }
                Wire.KIND_FILE -> s.bytes.write(Wire.parseFileChunk(m).data)
            }
        }
        return s
    }

    @Test fun theMacIsToldAboutPushInHelloAndWelcomeOnlyWhenItIsUsed() {
        assertFalse(Control.Hello("x", "p".repeat(32), null, 1, delta = true).json().containsKey("push"))
        assertFalse(Control.Welcome(1, "Mac", MAC_ID, 1, delta = true).json().containsKey("push"))
        for (c in listOf(Control.Hello("x", "0123456789abcdef0123456789abcdef", null, 1, delta = true, push = true), Control.Welcome(1, "Mac", MAC_ID, 1, delta = true, push = true))) {
            assertEquals(c, Control.parse(c.encode()))
        }
        assertTrue((Control.parse(Control.Welcome(1, "Mac", MAC_ID, 1, true, true).encode()) as Control.Welcome).push)
    }

    @Test fun theFirstChangeIsAnnouncedBecauseTheMacsHeadsAreNotKnownYet() {
        val h = connected()
        h.sync.update(listOf(NoteMeta("a", 6, "A")), "a")
        h.loop.runPosted()
        assertEquals(Control.Notes(listOf(NoteMeta("a", 6, "A", digest.digest, 4))), h.controls().single())
        assertTrue(h.source.exportedDeltas.isEmpty())
        h.cleanup()
    }

    @Test fun onceTheMacHoldsTheSameSetAChangeIsPushedAsADeltaWithNoWant() {
        val h = connected()
        h.teachHeads()
        h.sync.update(listOf(NoteMeta("a", 6, "A")), "a")
        h.loop.runPosted()
        val sent = h.collect()
        assertEquals("a", sent.file!!.id)
        assertTrue(sent.file!!.delta)
        assertTrue("no announcement goes with it: ${sent.controls}", sent.controls.none { it is Control.Notes })
        // The delta is made from what the phone held when it exported last time, not from the older base the Mac gave.
        assertEquals("a" to exported, h.source.exportedDeltas.last())
        h.cleanup()
    }

    @Test fun aHaveTeachesTheHeadsWhateverItsDigestSaysBecauseTheMacAppliedTheWholeFile() {
        val h = connected()
        h.teachHeads(equal = false)
        h.sync.update(listOf(NoteMeta("a", 6, "A")), "a")
        h.loop.runPosted()
        h.collect()
        assertEquals("a" to exported, h.source.exportedDeltas.last())
        h.cleanup()
    }

    @Test fun aMacThatDidNotSayPushIsOnlyAnnouncedTo() {
        val h = connected(push = false)
        h.teachHeads()
        h.sync.update(listOf(NoteMeta("a", 6, "A")), "a")
        h.loop.runPosted()
        val sent = h.collect()
        assertNull(sent.file)
        assertTrue(sent.controls.any { it is Control.Notes })
        h.cleanup()
    }

    @Test fun aChangeWhileAPushIsInFlightWaitsForItsHaveThenGoes() {
        val h = connected()
        h.teachHeads()
        h.sync.update(listOf(NoteMeta("a", 6, "A")), "a"); h.loop.runPosted()
        val first = h.collect()
        assertTrue(first.file!!.delta)
        h.sync.update(listOf(NoteMeta("a", 7, "A")), "a"); h.loop.runPosted()
        assertNull("one transfer at a time", h.collect().file)
        h.mac(Control.Have("a", 6, digest.digest, 5)); h.loop.runPosted()
        val second = h.collect()
        assertTrue(second.file!!.delta && second.file!!.xfer > first.file!!.xfer)
        h.cleanup()
    }

    @Test fun aRefusedPushIsAnnouncedAndTheHeadsAreForgotten() {
        val h = connected()
        h.teachHeads()
        h.sync.update(listOf(NoteMeta("a", 6, "A")), "a"); h.loop.runPosted()
        val sent = h.collect()
        h.mac(Control.FileError(sent.file!!.xfer, "a", "busy")); h.loop.runPosted()
        assertTrue("the note is announced instead", h.collect().controls.any { it is Control.Notes })
        h.sync.update(listOf(NoteMeta("a", 7, "A")), "a"); h.loop.runPosted()
        val next = h.collect()
        assertNull("no heads, so no push", next.file)
        assertTrue(next.controls.any { it is Control.Notes })
        h.cleanup()
    }

    @Test fun aSmallPushGoesWhileThePenWritesButAWholeCopyWaits() {
        val h = connected()
        h.teachHeads()
        h.feed.enabled = true
        h.feed.begin("a", "s", "stroke", dev.syncr.core.Brush(dev.syncr.core.BrushFamily.PEN, dev.syncr.core.BrushSize.MEDIUM, dev.syncr.core.InkColour.INK), dev.syncr.core.CoordinateSpace.CANVAS, 0, false, false, false)
        h.feed.sample(1f, 2f, .5f, 0f, 0f, 10); h.feed.flush()
        h.sync.update(listOf(NoteMeta("a", 6, "A")), "a"); h.loop.runPosted()
        // Live frames first, then the delta, in the same pass.
        val sent = h.collect()
        assertTrue("the delta went while the pen was down", sent.file!!.delta && sent.bytes.size() == 756)
        h.cleanup()
    }
}
