package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.PreviewBody
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A real LinkClient over real loopback sockets against [FakeMac]; the core is stood in for by fakes (the host-core twin is LoopbackHostTest). */
class LoopbackTest {
    private val mac = FakeMac()
    private val store = MemoryStore()
    private val notes = NotesFixture()
    private val out = FakeOut()
    private val feed = PenFeed()
    private val ids = AtomicInteger()
    private val pool = IdPool({ "stroke-${ids.incrementAndGet()}" })
    private val temp: File = Files.createTempDirectory("syncr-loop-").toFile()
    private val statuses = java.util.concurrent.CopyOnWriteArrayList<SessionStatus>()
    private val targets = java.util.concurrent.CopyOnWriteArrayList<StreamTarget>()
    private val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var client: LinkClient
    private val brush = Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK)

    @Before fun setUp() {
        notes.notes += NoteMeta("note-a", 3, "Fixture A")
        notes.contents["note-a"] = ByteArray(150_000) { (it % 251).toByte() }
        val config = ClientConfig("Test phone", store, notes, Clock.SYSTEM, temp, out, feed, { ByteArray(4) }, { logs += it }, ids = pool)
        client = LinkClient(config, { p -> Endpoint("127.0.0.1", mac.port, p.macName, p.macId) }, object : ClientListener {
            override fun onStatus(status: SessionStatus) { statuses += status }
            override fun onTarget(target: StreamTarget) { targets += target }
        })
        client.notesChanged(notes.notes, null)
        client.start()
    }

    @After fun tearDown() {
        client.stop(wait = true)
        mac.close()
        temp.deleteRecursively()
    }

    private fun lastPhase() = statuses.lastOrNull()?.phase

    private fun pair() {
        client.connect(mac.endpoint)
        assertTrue("asked for a code", mac.await { lastPhase() == Phase.NEEDS_CODE })
        client.submitCode("0000")
        assertTrue(mac.await { statuses.lastOrNull()?.triesLeft == 4 })
        client.submitCode("4242")
        assertTrue("connected", mac.await { lastPhase() == Phase.CONNECTED })
    }

    @Test fun pairThenSyncThenMirrorThenStreamThenRecover() {
        // 1. Pair: wrong code first, then the right one.
        pair()
        assertNotNull(store.current)
        assertEquals(MAC_ID, store.current!!.macId)
        assertNull(mac.hellos.first().token)

        // 2. The manifest goes out, the Mac wants the note, and gets it byte for byte.
        assertTrue("file arrived", mac.await { mac.files.containsKey("note-a") })
        assertArrayEquals(notes.contents["note-a"], mac.files["note-a"])
        assertEquals(3L, mac.haveSeq["note-a"])
        assertEquals(0, temp.listFiles()!!.size)

        // 3. A save while the note is open: announced after the quiet period, then re-sent.
        notes.notes[0] = NoteMeta("note-a", 4, "Fixture A")
        notes.contents["note-a"] = ByteArray(1000) { 9 }
        client.notesChanged(notes.notes, "note-a")
        client.setViewing("note-a")
        assertTrue(mac.await { mac.controls.contains(Control.Viewing("note-a")) })
        assertTrue("re-sync", mac.await(6_000) { mac.haveSeq["note-a"] == 4L })
        assertEquals(1000, mac.files["note-a"]!!.size)

        // 4. Live mirror: preview frames only.
        val mirror = CanvasMirror(feed, pool)
        val strokeId = mirror.begin("note-a", "sheet-a", brush, 1f, 0f, 0f, false, false)!!
        mirror.sample(10f, 20f, .5f, 0f, 0f, 100); mirror.flush()
        mirror.sample(11f, 21f, .5f, 0f, 0f, 108); mirror.flush()
        mirror.end()
        assertTrue("mirror frames", mac.await { mac.previews.size == 4 })
        assertTrue(mac.strokeChunks.isEmpty())
        assertTrue(strokeId.startsWith("stroke-"))

        // 5. Stream: the Mac names a target, the pad writes, the stroke arrives as frames and a durable commit.
        client.setPad(true)
        assertTrue(mac.await { mac.controls.contains(Control.Pad(true)) })
        mac.send(Control.Target("mac-note", "mac-sheet", true, 3))
        assertTrue(mac.await { targets.lastOrNull()?.usable == true })
        val pen = StreamPen(feed, pool, { targets.last() }, { brush })
        assertNull(pen.problem())
        assertTrue(pen.down(100f, 100f, .5f, 0f, 0f, 1000, false, false)); pen.flush()
        pen.move(200f, 200f, .6f, 0f, 0f, 1008); pen.flush()
        pen.up()
        assertTrue("stream frames", mac.await { mac.previews.size == 8 })
        assertTrue("stream commit", mac.await { mac.strokeChunks.size == 1 })
        assertTrue(logs.any { it == "state connected mac=Fake Mac" })
        assertTrue(logs.any { it == "target present=true writable=true rev=3 note=mac-note sheet=mac-shee" })
        assertTrue(logs.any { it.startsWith("stream stroke begin id=") })
        assertTrue(logs.any { it.startsWith("stream stroke end id=") && it.endsWith("samples=2") })
        assertTrue(logs.any { it.startsWith("stream stroke commit queued id=") })
        assertTrue(logs.contains("pad on sent"))

        // 6. The Mac goes away: the phone reconnects by itself with its stored token and no new code.
        val before = mac.connections.get()
        mac.drop()
        assertTrue("reconnect", mac.await(6_000) { mac.connections.get() > before && lastPhase() == Phase.CONNECTED })
        assertEquals(store.current!!.token, mac.hellos.last().token)
        // The new session carries the pad and viewing state again, and the manifest.
        assertTrue(mac.await { mac.controls.toList().takeLast(6).containsAll(listOf(Control.Viewing("note-a"), Control.Pad(true))) })
        client.setPad(false)
        assertTrue(mac.await { mac.controls.contains(Control.Pad(false)) && logs.contains("pad off sent") })
        assertTrue(logs.any { it.startsWith("state disconnected reason=Lost the connection") })
        assertTrue(logs.none { it.contains(store.current!!.token) })
    }

    @Test fun stoppingWhilePadIsOnSendsOffBeforeClosing() {
        pair()
        client.setPad(true)
        assertTrue(mac.await { mac.controls.contains(Control.Pad(true)) })
        client.stop(wait = true)
        assertTrue(mac.await { mac.controls.contains(Control.Pad(false)) })
        assertTrue(logs.contains("pad off sent"))
    }

    @Test fun macThatForgetsUsMakesUsAskForACodeAgain() {
        pair()
        mac.tokens.clear()
        mac.drop()
        assertTrue(mac.await(6_000) { lastPhase() == Phase.NEEDS_CODE })
        assertNull(store.current) // the old token is gone
    }

    @Test fun fiveWrongCodesEndTheAttempt() {
        client.connect(mac.endpoint)
        assertTrue(mac.await { lastPhase() == Phase.NEEDS_CODE })
        repeat(5) { client.submitCode("1111"); Thread.sleep(50) }
        assertTrue(mac.await { lastPhase() == Phase.IDLE })
        assertNull(store.current)
    }

    @Test fun corruptedTransferIsRetriedOnceAndThenSucceeds() {
        mac.corruptNextFile = true
        pair()
        assertTrue(mac.await { mac.files.containsKey("note-a") })
        assertTrue(logs.any { it.contains("retrying once") })
    }

    @Test fun hostileBytesFromTheMacCloseTheConnectionWithoutCrashingTheClient() {
        pair()
        val before = mac.connections.get()
        // A length of four gigabytes.
        mac.rawWrite(byteArrayOf(-1, -1, -1, -1))
        assertTrue(mac.await(6_000) { mac.connections.get() > before && lastPhase() == Phase.CONNECTED })
    }

    @Test fun anUnreachableMacIsReportedAndLocalInkIsUnaffected() {
        val dead = FakeMac().also { it.close() }
        client.connect(dead.endpoint)
        assertTrue(mac.await { statuses.any { it.message?.contains("Couldn't reach") == true || it.phase == Phase.IDLE } })
        // The pen side keeps working with the link down: begin says no, and nothing blocks.
        val mirror = CanvasMirror(feed, pool)
        assertNull(mirror.begin("n", "s", brush, 1f, 0f, 0f, false, false))
        mirror.sample(1f, 1f, .5f, 0f, 0f, 1); mirror.flush(); mirror.end()
    }
}
