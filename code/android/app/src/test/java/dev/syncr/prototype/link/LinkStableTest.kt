package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.CoordinateSpace
import dev.syncr.core.InkColour
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link part K on the host: a real [LinkClient] over loopback against [FakeMac], with short limits so each test takes a second or
 * two. The limits themselves (5 s active, 12 s idle) are what the joined fault test (FaultTest) runs against the real Mac.
 */
class LinkStableTest {
    private val mac = FakeMac()
    private val store = MemoryStore()
    private val notes = NotesFixture()
    private val out = FakeOut()
    private val feed = PenFeed()
    private val keeper = StrokeKeeper()
    private val ids = AtomicInteger()
    private val pool = IdPool({ "stroke-${ids.incrementAndGet()}" })
    private val temp: File = Files.createTempDirectory("syncr-stable-").toFile()
    private val statuses = CopyOnWriteArrayList<Pair<Long, SessionStatus>>()
    private val logs = CopyOnWriteArrayList<String>()
    private val targets = CopyOnWriteArrayList<StreamTarget>()
    private val connects = CopyOnWriteArrayList<Long>()
    private val clients = ArrayList<LinkClient>()
    private val brush = Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK)

    /** A transport that can be made to fail, like a Wi-Fi that is off. */
    private class SwitchTransport : Transport {
        val down = AtomicBoolean(false)
        val attempts = CopyOnWriteArrayList<Long>()
        override fun connect(endpoint: Endpoint, timeoutMs: Int): Socket {
            attempts += System.nanoTime() / 1_000_000
            if (down.get()) throw IOException("network is unreachable")
            return Transport.TCP.connect(endpoint, timeoutMs)
        }
    }

    private val transport = SwitchTransport()

    private fun client(
        staleMs: Long = 12_000, staleActiveMs: Long = 5_000, backoff: Backoff = Backoff { 0.5 }, hold: Long = 2_000, gap: Long = 1_000,
        statusHook: (SessionStatus) -> Unit = {}, version: Int = 1,
    ): LinkClient {
        val config = ClientConfig(
            "Test phone", store, notes, Clock.SYSTEM, temp, out, feed, { ByteArray(4) }, { logs += it }, transport = transport, ids = pool,
            staleMs = staleMs, staleActiveMs = staleActiveMs, backoff = backoff, statusHoldMs = hold, statusGapMs = gap, keeper = keeper, linkVersion = version,
        )
        val c = LinkClient(config, { p -> Endpoint("127.0.0.1", mac.port, p.macName, p.macId) }, object : ClientListener {
            override fun onStatus(status: SessionStatus) { statuses += System.nanoTime() / 1_000_000 to status; statusHook(status) }
            override fun onTarget(target: StreamTarget) { targets += target }
            override fun onConnected(endpoint: Endpoint) { connects += System.nanoTime() / 1_000_000 }
        })
        clients += c
        return c
    }

    @After fun tearDown() {
        clients.forEach { it.stop(wait = true) }
        mac.close()
        temp.deleteRecursively()
    }

    private fun phase() = statuses.lastOrNull()?.second?.phase

    private fun pair(c: LinkClient) {
        c.start()
        c.connect(mac.endpoint)
        assertTrue(mac.await { phase() == Phase.NEEDS_CODE })
        c.submitCode("4242")
        assertTrue(mac.await { phase() == Phase.CONNECTED })
    }

    // ---- K.1: noticing a dead link, never a slow one

    @Test fun aQuietLinkWithAPadOpenIsClosedSoonerThanAnIdleOne() {
        val c = client(staleMs = 3_000, staleActiveMs = 800)
        pair(c)
        val before = mac.connections.get()
        // Idle: 1.5 s of silence (the fake Mac pings no one) is not yet a dead link.
        Thread.sleep(1_500)
        assertEquals("an idle link silent for 1.5 s stays", before, mac.connections.get())
        c.setPad(true)
        assertTrue(mac.await { mac.controls.contains(Control.Pad(true)) })
        // The phone's own pings are answered by the fake Mac, so silence needs the Mac to stop talking: it does not answer from here.
        mac.hook = { }
        mac.mute = true
        assertTrue("a pad link silent for a second is closed", mac.await(4_000) { logs.any { it.startsWith("disconnect cause=watchdog") } })
    }

    @Test fun anyInboundByteIsLifeEvenHalfAMessage() {
        val c = client(staleMs = 3_000, staleActiveMs = 700)
        pair(c)
        c.setPad(true)
        assertTrue(mac.await { mac.controls.contains(Control.Pad(true)) })
        mac.mute = true
        val before = mac.connections.get()
        // A long message arrives a few bytes at a time: no whole message for 2.5 s, but bytes every 300 ms.
        val message = Wire.frame(Control.Ping(1, 1).encode())
        val first = message.copyOfRange(0, 3)
        mac.rawWrite(first)
        for (i in 3 until message.size - 1) { Thread.sleep(300); mac.rawWrite(byteArrayOf(message[i])); if (i > 8) break }
        assertEquals("slow bytes kept the link alive", before, mac.connections.get())
        assertTrue(logs.none { it.startsWith("disconnect cause=watchdog") })
    }

    @Test fun aPingIsAnsweredWhileTheLinkThreadIsBlocked() {
        var blocked = false
        val blockedFor = 1_500L
        lateinit var c: LinkClient
        c = client(statusHook = { s -> if (s.phase == Phase.CONNECTED && !blocked) { blocked = true; Thread.sleep(blockedFor) } })
        c.start(); c.connect(mac.endpoint)
        assertTrue(mac.await { phase() == Phase.NEEDS_CODE })
        c.submitCode("4242")
        assertTrue("welcome reached the Mac's side", mac.await { mac.epoch > 0 })
        // The listener is now blocking the link thread (it runs the status callback). The Mac pings: the pong comes from the reader.
        Thread.sleep(200)
        val started = System.nanoTime()
        mac.send(Control.Ping(99, 5))
        assertTrue("a pong while the link thread was blocked", mac.await(1_000) { mac.controls.any { it is Control.Pong && (it as Control.Pong).id == 99L } })
        assertTrue("answered well inside the block", (System.nanoTime() - started) / 1_000_000 < blockedFor)
    }

    // ---- K.2: coming back fast

    @Test fun theFirstRetryIsAtOnceThenAFlappingLinkWaitsHalfASecond() {
        val c = client(backoff = Backoff { 0.5 })
        pair(c)
        val t0 = mac.connections.get()
        val drop1 = System.nanoTime() / 1_000_000
        mac.drop()
        assertTrue(mac.await(3_000) { mac.connections.get() > t0 && connects.size >= 2 })
        val back1 = connects[1] - drop1
        assertTrue("the first retry is at once ($back1 ms)", back1 < 400)
        // Dropped again straight away (the second session did not last): the next wait is the ladder's 0.5 s, not the first's none.
        val drop2 = System.nanoTime() / 1_000_000
        mac.drop()
        assertTrue(mac.await(4_000) { connects.size >= 3 })
        val back2 = connects[2] - drop2
        assertTrue("the second retry waits about half a second ($back2 ms)", back2 in 400..1_200)
    }

    @Test fun aNetworkChangeCutsThroughTheBackoffAndAnUnansweredProbeClosesAConnection() {
        val c = client(backoff = Backoff { 0.5 })
        pair(c)
        // The Wi-Fi goes: every attempt fails fast. After a few the wait is seconds long.
        transport.down.set(true)
        mac.drop()
        assertTrue(mac.await(6_000) { transport.attempts.size >= 4 })
        val seen = transport.attempts.size
        transport.down.set(false)
        val at = System.nanoTime() / 1_000_000
        c.networkChanged()
        assertTrue("connected within a moment of the network coming back", mac.await(1_500) { connects.size >= 2 })
        assertTrue("(${connects.last() - at} ms)", connects.last() - at < 1_000)
        assertTrue(seen >= 4)
    }

    @Test fun aLinkThatFlapsIsRetriedHalfASecondApartAtMostAndNeverBacksOff() {
        val c = client(backoff = Backoff { 0.5 })
        pair(c)
        // Quick sessions in a row: each was welcomed, so the path works. Each retry waits half a second and the wait never climbs.
        repeat(4) {
            val n = mac.connections.get()
            val at = System.nanoTime() / 1_000_000
            mac.drop()
            assertTrue(mac.await(4_000) { mac.connections.get() > n })
            val waited = System.nanoTime() / 1_000_000 - at
            assertTrue("retry ${it + 1} waited $waited ms", waited in 0..1_200)
        }
    }

    @Test fun onlyFailedAttemptsClimbTheLadder() {
        val b = Backoff { 0.5 }
        b.afterWelcomedSession(stable = true)
        assertEquals(0L, b.nextMs())
        b.afterWelcomedSession(stable = false) // the first drop straight after a welcome: at once
        assertEquals(0L, b.nextMs())
        b.afterWelcomedSession(stable = false) // and again: half a second
        assertEquals(500L, b.nextMs())
        assertEquals(1_000L, b.nextMs()) // that attempt failed: now it climbs
        b.afterWelcomedSession(stable = false) // a welcome in between starts it again
        assertEquals(500L, b.nextMs())
        b.afterWelcomedSession(stable = true)
        assertEquals(0L, b.nextMs())
    }

    // ---- K.5: calm status

    @Test fun aDropThatHealsAtOnceChangesNothingTheOwnerSees() {
        val c = client(hold = 1_500)
        pair(c)
        val shown = statuses.size
        val n = mac.connections.get()
        mac.drop()
        assertTrue(mac.await(3_000) { mac.connections.get() > n })
        Thread.sleep(300)
        assertEquals("no status change for a quick reconnect: ${statuses.drop(shown).map { it.second }}", shown, statuses.size)
    }

    @Test fun aLongOutageShowsOneReconnectingAndNeverFlaps() {
        val c = client(hold = 600, gap = 400)
        pair(c)
        val shown = statuses.size
        transport.down.set(true)
        mac.drop()
        Thread.sleep(2_500)
        transport.down.set(false)
        c.networkChanged()
        assertTrue(mac.await(3_000) { phase() == Phase.CONNECTED && statuses.size > shown })
        val after = statuses.drop(shown)
        assertEquals("one Reconnecting, then connected: ${after.map { it.second }}", 2, after.size)
        assertTrue(after[0].second.message!!.startsWith("Reconnecting"))
        assertEquals(Phase.CONNECTING, after[0].second.phase)
    }

    // ---- K.7: one line per disconnect

    @Test fun aDisconnectLeavesOneLineWithItsCauseAndNoContent() {
        val c = client()
        pair(c)
        val n = mac.connections.get()
        mac.drop()
        assertTrue(mac.await(3_000) { mac.connections.get() > n })
        val line = logs.first { it.startsWith("disconnect cause=") }
        assertTrue(line, Regex("""disconnect cause=\S+( \S+)* since-inbound=\d+ms up=\d+ms queued=control/\d+ preview/\d+B file/\d+chunks kept-strokes=\d+""").containsMatchIn(line))
        assertEquals(1, logs.count { it.startsWith("disconnect cause=") })
        assertFalse(line.contains(store.current!!.token))
    }

    // ---- K.4 and K.3: strokes kept until confirmed, and the pad's state resumes

    @Test fun aStrokeDrawnWithNoConnectionIsKeptAndSentOnceTheLinkIsBack() {
        mac.keepStrokes = true
        val c = client()
        pair(c)
        c.setPad(true)
        mac.send(Control.Target("mac-note", "mac-sheet", true, 5))
        assertTrue(mac.await { targets.lastOrNull()?.usable == true })
        assertTrue("the Mac is told strokes are kept", mac.hellos.last().keep)
        // The link goes; strokes drawn meanwhile are not refused.
        transport.down.set(true)
        mac.drop()
        assertTrue(mac.await(3_000) { logs.any { it.startsWith("disconnect cause=") } })
        val pen = StreamPen(feed, pool, { targets.last() }, { brush })
        assertTrue("the pad still takes a stroke with no connection", pen.down(100f, 100f, .5f, 0f, 0f, 1000, false, false, CoordinateSpace.VIEW_RELATIVE))
        pen.flush(); pen.move(200f, 200f, .6f, 0f, 0f, 1008); pen.flush(); pen.up()
        assertTrue("kept for later", mac.await { logs.any { it.startsWith("stream stroke kept id=") } })
        assertEquals(1, c.keptStrokes)
        transport.down.set(false)
        c.networkChanged()
        assertTrue("sent again with its revision after the link is back", mac.await(4_000) { mac.controls.any { it is Control.StrokeRev && (it as Control.StrokeRev).rev == 5L } })
        assertTrue(mac.await(4_000) { mac.strokeChunks.isNotEmpty() })
        // The Mac confirms: the phone lets go, and nothing is sent again on the next reconnect.
        val id = (mac.controls.first { it is Control.StrokeRev } as Control.StrokeRev).id
        mac.send(Control.StrokeAck(id, true))
        assertTrue(mac.await { c.keptStrokes == 0 })
        val chunks = mac.strokeChunks.size
        val n = mac.connections.get()
        mac.drop()
        assertTrue(mac.await(3_000) { mac.connections.get() > n })
        Thread.sleep(300)
        assertEquals("a confirmed stroke is not sent again", chunks, mac.strokeChunks.size)
    }

    @Test fun thePadFrameComesBackWithTheConnection() {
        mac.keepStrokes = true
        val c = client()
        pair(c)
        c.setPad(true)
        c.setPadWish(PadWish(local = true, pics = false, aspect = 1.6))
        mac.send(Control.Target("mac-note", "mac-sheet", true, 1, frame = CanvasRect(900.0, 700.0, 1600.0, 1000.0)))
        assertTrue(mac.await { targets.lastOrNull()?.frame != null })
        val n = mac.connections.get()
        mac.drop()
        assertTrue(mac.await(3_000) { mac.connections.get() > n })
        assertTrue("the pad is opened again with its mode and frame", mac.await(3_000) {
            val tail = mac.controls.toList().takeLast(16)
            tail.contains(Control.Pad(true)) && tail.any { it is Control.PadMode } && tail.contains(Control.Frame(CanvasRect(900.0, 700.0, 1600.0, 1000.0)))
        })
    }

    @Test fun aMacThatDoesNotKeepStrokesGetsTodaysBehaviour() {
        val c = client(version = 0)
        pair(c)
        assertFalse(mac.hellos.last().keep)
        assertFalse(keeper.peerKeeps)
    }
}
