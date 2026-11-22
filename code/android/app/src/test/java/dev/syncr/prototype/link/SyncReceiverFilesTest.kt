package dev.syncr.prototype.link

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remaining-v1 L.5: incoming chunks are written and hashed on the files thread; the link thread only checks their order. */
class SyncReceiverFilesTest {
    private val temp: File = Files.createTempDirectory("syncr-files-").toFile()
    private val clock = FakeClock()
    private val out = FakeOut()
    private val feed = PenFeed(clock)
    private val scheduler = SendScheduler(out, FeedPump(feed, out, clock, { ByteArray(1) }))
    private val loop = FakeLoop()
    private val notes = NotesFixture()
    private val files = ArrayDeque<Runnable>()
    private var disk: BulkDisk = BulkDisk.REAL
    private val receiver = SyncReceiver(notes, scheduler, loop, clock, temp, disk = object : BulkDisk {
        override fun write(out: OutputStream, data: ByteArray) = disk.write(out, data)
    }, files = { files += it })

    @After fun tearDown() { temp.deleteRecursively() }

    private fun sent(): List<Control> = generateSequence { scheduler.next() }.mapNotNull { Control.parse(it) }.toList()

    private fun offer(data: ByteArray, chunk: Int): List<ByteArray> {
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val n = (data.size + chunk - 1) / chunk
        receiver.onFile(Control.File(5, "n1", 2, data.size.toLong(), sha, n.toLong(), false))
        return (0 until n).map { i -> Wire.fileChunk(5, i.toLong(), data.copyOfRange(i * chunk, minOf(data.size, (i + 1) * chunk))) }
    }

    @Test fun chunksWaitForTheFilesThreadAndTheNoteIsAppliedOnlyOnceAllAreWritten() {
        receiver.onWelcome(v1 = true)
        val data = ByteArray(5000) { (it % 7).toByte() }
        offer(data, 2000).forEach(receiver::onChunk)
        assertEquals(5000L, receiver.backlog.get()) // checked and queued, nothing written on the link thread
        receiver.onFileEnd(5)
        loop.runPosted()
        assertTrue(notes.copiesApplied.isEmpty())
        while (files.isNotEmpty()) files.removeFirst().run()
        assertEquals(0L, receiver.backlog.get())
        loop.runPosted()
        assertEquals(listOf("n1"), notes.copiesApplied)
        assertTrue(sent().any { it is Control.Have && it.id == "n1" })
    }

    @Test fun aDeltaRefusedWhileTheEditorStoresIsTriedAgainNotFetchedWhole() {
        // remaining-v1 L.4, found on the emulator: a refusal mid-typing used to ask for the whole note, which was refused too.
        var attempts = 0
        var busy = 0
        val source = object : NoteSource by notes {
            // The editor stages a store just as the first merge runs, and has stored it a moment later.
            override fun editing(id: String) = attempts == 1 && busy++ < 2
            override fun applyDelta(id: String, file: File): CopyOutcome =
                if (++attempts == 1) CopyOutcome.Refused("the note has unsaved changes") else CopyOutcome.Applied(3, NoteDigest("a".repeat(64), 3), "delta")
        }
        val r = SyncReceiver(source, scheduler, loop, clock, temp)
        r.onWelcome(v1 = true, delta = true)
        val data = ByteArray(300) { 1 }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        r.onFile(Control.File(8, "n1", 2, 300, sha, 1, delta = true))
        r.onChunk(Wire.fileChunk(8, 0, data))
        r.onFileEnd(8)
        loop.runPosted()
        val out = sent()
        assertEquals(2, attempts)
        assertTrue("applied on the second try: $out", out.any { it is Control.Have && it.id == "n1" })
        assertTrue("nothing asked for the whole note: $out", out.none { it is Control.Want || it is Control.FileError })
    }

    @Test fun aWriteThatFailsRefusesTheFileAtItsEnd() {
        receiver.onWelcome(v1 = true)
        disk = object : BulkDisk { override fun write(out: OutputStream, data: ByteArray) = throw java.io.IOException("full") }
        offer(ByteArray(3000), 1000).forEach(receiver::onChunk)
        receiver.onFileEnd(5)
        while (files.isNotEmpty()) files.removeFirst().run()
        loop.runPosted()
        while (files.isNotEmpty()) files.removeFirst().run()
        assertTrue(notes.copiesApplied.isEmpty())
        assertTrue(sent().any { it is Control.FileError && it.reason == "write" })
        assertEquals(0, temp.listFiles()!!.size)
        assertTrue(!receiver.busy)
    }
}
