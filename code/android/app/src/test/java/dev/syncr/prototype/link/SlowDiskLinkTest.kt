package dev.syncr.prototype.link

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge of remaining-v1 parts K and L. The reader stops reading while this phone's disk is 4 MB behind on an incoming note (L.5),
 * and the watchdog counts inbound bytes as life with a short limit (K.1). A disk stuck that long must not make a live Mac look dead,
 * and the phone must keep proving itself alive to the Mac meanwhile (its pings leave the link thread, never behind the disk).
 * A real [LinkClient] against [FakeMac]; the disk is a [BulkDisk] that blocks until released.
 */
class SlowDiskLinkTest {
    private val mac = FakeMac(version = 1, queuedWrites = true)
    private val temp: File = Files.createTempDirectory("syncr-slowdisk-").toFile()
    private val logs = CopyOnWriteArrayList<String>()
    private val statuses = CopyOnWriteArrayList<SessionStatus>()
    private val clients = ArrayList<LinkClient>()
    private val release = CountDownLatch(1)

    /** Writes of an incoming note wait here (reads are not used: nothing is sent). */
    private val stuckDisk = object : BulkDisk {
        override fun write(out: OutputStream, data: ByteArray) { release.await(30, TimeUnit.SECONDS); out.write(data) }
    }

    @After fun tearDown() {
        release.countDown()
        clients.forEach { it.stop(wait = true) }
        mac.close()
        temp.deleteRecursively()
    }

    private fun client(staleMs: Long, staleActiveMs: Long, forgivenMs: Long = 30_000): LinkClient {
        val config = ClientConfig(
            "Test phone", MemoryStore(), NotesFixture(), Clock.SYSTEM, temp, FakeOut(), PenFeed(), { ByteArray(4) }, { logs += it },
            datagrams = false, staleMs = staleMs, staleActiveMs = staleActiveMs, disk = stuckDisk, diskForgivenMs = forgivenMs,
            backoff = Backoff { 0.5 },
        )
        val c = LinkClient(config, { p -> Endpoint("127.0.0.1", mac.port, p.macName, p.macId) }, object : ClientListener {
            override fun onStatus(status: SessionStatus) { statuses += status }
            override fun onTarget(target: StreamTarget) {}
        })
        clients += c
        c.start()
        c.connect(mac.endpoint)
        assertTrue(mac.await { statuses.lastOrNull()?.phase == Phase.NEEDS_CODE })
        c.submitCode("4242")
        assertTrue(mac.await { statuses.lastOrNull()?.phase == Phase.CONNECTED })
        return c
    }

    /** The Mac sends a note the phone's disk cannot take: more than the 4 MB the reader allows to wait. */
    private fun macSendsNote(size: Int = 6_000_000) {
        val data = ByteArray(size) { (it % 249).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val chunks = (size + Wire.FILE_CHUNK_DATA - 1) / Wire.FILE_CHUNK_DATA
        mac.send(Control.File(77, "note-in", 9, size.toLong(), sha, chunks.toLong(), false))
        Thread {
            for (i in 0 until chunks) {
                val n = minOf(Wire.FILE_CHUNK_DATA, size - i * Wire.FILE_CHUNK_DATA)
                mac.rawWrite(Wire.frame(Wire.fileChunk(77, i.toLong(), data.copyOfRange(i * Wire.FILE_CHUNK_DATA, i * Wire.FILE_CHUNK_DATA + n))))
            }
            mac.send(Control.FileEnd(77))
        }.apply { isDaemon = true; start() }
    }

    private fun pings() = mac.controls.count { it is Control.Ping }
    private fun watchdogClosed() = logs.any { it.startsWith("disconnect cause=watchdog") }

    @Test fun aLiveMacIsNotDeadBecauseThisPhonesDiskIsBehind() {
        // Limits of 3 s (the fake Mac answers the phone's 2 s pings): the disk is stuck for 5.5 s, past both.
        val c = client(staleMs = 3_000, staleActiveMs = 3_000)
        val connections = mac.connections.get()
        macSendsNote()
        Thread.sleep(500) // the reader has filled the backlog and stopped
        val pingsAtStart = pings()
        Thread.sleep(5_000)
        assertTrue("no watchdog close while only the disk is slow: $logs", !watchdogClosed())
        assertEquals("the same connection, not a new one", connections, mac.connections.get())
        assertEquals(Phase.CONNECTED, statuses.last().phase)
        assertTrue("the phone keeps pinging the Mac from the link thread meanwhile (${pings() - pingsAtStart})", pings() - pingsAtStart >= 2)
        // The disk wakes up: the note finishes and the phone is still on the same connection.
        release.countDown()
        Thread.sleep(700)
        assertEquals(connections, mac.connections.get())
        assertTrue(!watchdogClosed())
        // The excuse ends with the stall: a Mac that now goes silent is closed within the usual limit.
        mac.mute = true
        assertTrue("a silent Mac is closed as before once the disk is not the reason", mac.await(6_000) { watchdogClosed() })
        c.stop(wait = true)
    }

    @Test fun aDiskStuckForeverDoesNotKeepALinkUpForEver() {
        client(staleMs = 3_000, staleActiveMs = 3_000, forgivenMs = 1_500)
        macSendsNote()
        assertTrue("the cap ends the excuse: $logs", mac.await(9_000) { watchdogClosed() })
    }
}
