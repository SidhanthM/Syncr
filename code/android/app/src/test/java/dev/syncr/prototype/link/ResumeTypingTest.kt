package dev.syncr.prototype.link

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge of remaining-v1 parts K (resume after a drop) and L (typed text): a connection that is made again while the phone is typing
 * into a note. The new session knows nothing of what the Mac confirmed on the old one, so what follows must not turn into fetching
 * the whole note, refusing it mid-typing and asking again; the text converges both ways with deltas.
 */
class ResumeTypingTest {
    private val macHeld = mapOf("AUTHOR1" to "01M7PEC69Z6M1T1WDDDTS8FRSZ")
    private val typed = mapOf("AUTHOR1" to "01M7PEC69Z6M1T1WDDDTS8FRT3")
    private val phoneDigest = NoteDigest("c".repeat(64), 9)

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

    /** A phone that has just been welcomed again, with text typed into note "a" during the outage and the editor mid-store. */
    private fun resumed(busyChecks: Int): SessionHarness {
        val h = SessionHarness()
        h.source.notes += NoteMeta("a", 7, "A")
        h.source.deltas["a"] = ByteArray(400) { 2 }
        h.source.contents["a"] = ByteArray(100_000) { 1 }
        h.source.headsOf["a"] = typed
        h.source.digestsOf["a"] = phoneDigest
        var busy = busyChecks
        h.source.editingNow = { busy-- > 0 }
        h.session.adopt(null, false, "a")
        h.session.start(); h.drain(); h.welcomeV1(push = true); h.loop.runPosted(); h.drain()
        return h
    }

    @Test fun aNewConnectionWhileTypingFetchesOnlyWhatTheMacHoldsAsADeltaAndConverges() {
        val h = resumed(busyChecks = 3)
        // The Mac announces the note: its digest differs (it holds something the phone lacks, or only less). The phone asks for it
        // with the heads it holds, never as a plain request for the whole note.
        h.mac(Control.Notes(listOf(NoteMeta("a", 5, "A", "d".repeat(64), 4)))); h.loop.runPosted()
        val first = h.collect()
        val want = first.controls.filterIsInstance<Control.Want>()
        assertTrue("the phone asks for the note, with its heads: ${first.controls}", want.size == 1 && want[0].base != null)
        // The Mac's delta arrives while the editor is mid-store: merged once the editor is quiet, not refused, nothing asked twice.
        val bytes = ByteArray(300) { 3 }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        h.mac(Control.File(9, "a", 6, 300, sha, 1, delta = true))
        h.session.onFileChunk(Wire.fileChunk(9, 0, bytes))
        h.mac(Control.FileEnd(9)); h.loop.runPosted()
        assertEquals("applied as a delta once the editor was quiet", listOf("a" to 300L), h.source.deltasApplied)
        val after = h.collect()
        assertTrue("the phone says it holds the result: ${after.controls}", after.controls.any { it is Control.Have && it.id == "a" })
        assertTrue("no whole-note fetch, no refusal: ${after.controls}", after.controls.none { it is Control.Want || it is Control.FileError })
        // And the text typed during the outage goes to the Mac: its want (with what it holds) is answered with a delta.
        h.mac(Control.Want(listOf("a"), mapOf("a" to macHeld))); h.loop.runPosted()
        val pushed = h.collect()
        assertNotNull("the typed text is sent", pushed.file)
        assertTrue("as a delta", pushed.file!!.delta)
        assertEquals("from what the Mac holds", "a" to macHeld, h.source.exportedDeltas.last())
        h.cleanup()
    }

    @Test fun anEditorThatNeverGoesQuietAnswersBusyAndFetchesNothingMore() {
        val h = resumed(busyChecks = Int.MAX_VALUE)
        h.mac(Control.Notes(listOf(NoteMeta("a", 5, "A", "d".repeat(64), 4)))); h.loop.runPosted(); h.collect()
        val bytes = ByteArray(300) { 3 }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        h.source.deltaOutcome = CopyOutcome.Refused("the note has unsaved changes")
        h.mac(Control.File(9, "a", 6, 300, sha, 1, delta = true))
        h.session.onFileChunk(Wire.fileChunk(9, 0, bytes))
        h.mac(Control.FileEnd(9)); h.loop.runPosted()
        val sent = h.collect()
        assertTrue("busy, and nothing is fetched whole: ${sent.controls}", sent.controls.any { it is Control.FileError && it.reason == "busy" } && sent.controls.none { it is Control.Want })
        h.cleanup()
    }
}
