package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.BrushFamily
import dev.syncr.core.BrushSize
import dev.syncr.core.InkColour
import dev.syncr.core.PreviewBody
import dev.syncr.core.PreviewFrame
import dev.syncr.core.StrokeCommit
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remaining-v1 L.5: does a pen frame or a pointer packet wait behind a note transfer? A real [LinkClient] against [FakeMac]
 * over a throttled socket and a slow disk. The socket is a link of [RATE] bytes a second that holds at most [unsentBytes] the
 * Mac has not yet read (the kernel's send queue: Android doubles SO_SNDBUF, and the app caps unsent bytes with
 * TCP_NOTSENT_LOWAT, see [LinkTuning]); the disk takes [DISK_MS] for every chunk read or written. Every pen batch and
 * pointer packet is timed from the moment the test hands it to the phone to the moment the Mac has read all of it: its age.
 *
 * Workloads, each printed as one `INPUT-AGE` line per kind of input: idle; a large note going to the Mac; a large note
 * coming from the Mac; the Mac not reading for 3 s (a stalled peer). Pass/fail is the contract's: sync adds at most 8 ms to
 * the p95 age of pen frames and pointer packets, and nothing is lost or doubled.
 */
class InputBehindBulkTest {
    private val temp: File = Files.createTempDirectory("syncr-bulk-").toFile()
    private val clients = ArrayList<LinkClient>()
    private val macs = ArrayList<FakeMac>()
    private val brush = Brush(BrushFamily.PEN, BrushSize.SMALL, InkColour.INK)

    @After fun tearDown() {
        clients.forEach { it.stop(wait = true) }
        macs.forEach { it.close() }
        temp.deleteRecursively()
    }

    // ---------------------------------------------------------------- the throttled socket and the slow disk

    /**
     * Writes go into a queue of at most [limit] unsent bytes (a full queue blocks the writer, as a full kernel send queue
     * does); a pump moves them to the real socket at [rate] bytes a second, and not at all while [stalledUntil] is ahead.
     */
    private class ThrottledSocket(private val rate: Long, private val limit: Int, private val stalledUntil: AtomicLong) : Socket() {
        private val lock = Object()
        private val pending = java.util.ArrayDeque<ByteArray>()
        private var queued = 0
        @Volatile private var dead = false

        override fun getOutputStream(): OutputStream {
            val inner = super.getOutputStream()
            Thread({ pump(inner) }, "throttle-pump").apply { isDaemon = true; start() }
            return object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    var at = off; var left = len
                    while (left > 0) synchronized(lock) {
                        while (queued >= limit && !dead) lock.wait()
                        if (dead) throw IOException("closed")
                        val n = minOf(left, limit - queued)
                        pending.addLast(b.copyOfRange(at, at + n)); queued += n
                        at += n; left -= n
                        lock.notifyAll()
                    }
                }
            }
        }

        private fun pump(out: OutputStream) {
            var free = System.nanoTime()
            try {
                while (!dead) {
                    val piece = synchronized(lock) {
                        while (pending.isEmpty() && !dead) lock.wait()
                        if (dead) return
                        val head = pending.removeFirst()
                        if (head.size > SLICE) { pending.addFirst(head.copyOfRange(SLICE, head.size)); head.copyOf(SLICE) } else head
                    }
                    while (stalledUntil.get() > System.nanoTime()) LockSupport.parkNanos(1_000_000)
                    free = maxOf(free, System.nanoTime()) + piece.size * 1_000_000_000L / rate
                    while (System.nanoTime() < free) LockSupport.parkNanos(free - System.nanoTime())
                    out.write(piece); out.flush()
                    synchronized(lock) { queued -= piece.size; lock.notifyAll() }
                }
            } catch (_: Exception) { }
            finally { synchronized(lock) { dead = true; lock.notifyAll() } }
        }

        override fun close() { synchronized(lock) { dead = true; lock.notifyAll() }; super.close() }
    }

    private class SlowDisk(private val ms: Long) : BulkDisk {
        override fun read(file: RandomAccessFile, buffer: ByteArray, offset: Int, length: Int): Int {
            Thread.sleep(ms); return file.read(buffer, offset, length)
        }
        override fun write(out: OutputStream, data: ByteArray) { Thread.sleep(ms); out.write(data) }
    }

    /** The core's send queue, standing in: a Samples frame carries its stroke and first sample index so the Mac can time it. */
    private class TimedOut : PreviewOut {
        private val previews = ArrayDeque<ByteArray>()
        private val durable = ArrayDeque<ByteArray>()
        @Synchronized override fun push(frame: PreviewFrame) {
            val body = frame.body
            previews += if (body is PreviewBody.Samples) {
                java.nio.ByteBuffer.allocate(11).put(Wire.KIND_PREVIEW.toByte()).put(1).put(2)
                    .putInt(frame.strokeId.hashCode()).putInt(body.firstIndex.toInt()).array()
            } else byteArrayOf(Wire.KIND_PREVIEW.toByte(), 1, 1)
        }
        @Synchronized override fun commit(commit: StrokeCommit) { durable += byteArrayOf(Wire.KIND_STROKE.toByte(), 1, 1) }
        @Synchronized override fun pop(): ByteArray? = previews.removeFirstOrNull() ?: durable.removeFirstOrNull()
        @Synchronized override fun queuedPreviewBytes() = previews.sumOf { it.size }.toLong()
        @Synchronized override fun clearPreview() = previews.clear()
    }

    // ---------------------------------------------------------------- one rig: a phone and a Mac

    private inner class Rig(val unsentBytes: Int, diskMs: Long) {
        val mac = FakeMac(version = 1, queuedWrites = true).also { macs += it }
        val notes = NotesFixture()
        val feed = PenFeed()
        val stalledUntil = AtomicLong()
        private val ids = AtomicInteger()
        val pool = IdPool({ "stroke-${ids.incrementAndGet()}" })
        val logs = CopyOnWriteArrayList<String>()
        @Volatile var connected = false
        @Volatile var pointerReady = false
        val client: LinkClient

        /** When each pen batch and pointer packet was handed to the phone, and how old each was when the Mac had read it. */
        val penSent = ConcurrentHashMap<Long, Long>()
        val pointerSent = ConcurrentHashMap<Long, Long>()
        val penAges = CopyOnWriteArrayList<Long>()
        val pointerAges = CopyOnWriteArrayList<Long>()
        val pointerSeen = ConcurrentHashMap.newKeySet<Long>()
        val pointerRepeats = AtomicInteger()
        /** Strokes the phone would not begin (not connected, or no stroke ID ready). */
        val skipped = AtomicInteger()

        init {
            mac.acceptPreviewFrame = { m ->
                if (m.size == 11 && m[2].toInt() == 2) {
                    val b = java.nio.ByteBuffer.wrap(m, 3, 8)
                    penSent.remove(key(b.int, b.int))?.let { penAges += System.nanoTime() - it }
                }
            }
            mac.hook = { c ->
                if (c is Control.Pointer) {
                    val k = (c.input.dx * 1000 + c.input.dy).toLong()
                    if (!pointerSeen.add(k)) pointerRepeats.incrementAndGet()
                    pointerSent[k]?.let { pointerAges += System.nanoTime() - it }
                }
            }
            val transport = Transport { e, timeout ->
                ThrottledSocket(RATE, unsentBytes, stalledUntil).apply { tcpNoDelay = true; connect(InetSocketAddress(e.host, e.port), timeout) }
            }
            val config = ClientConfig("Test phone", MemoryStore(), notes, Clock.SYSTEM, File(temp, "out-${macs.size}"), TimedOut(), feed,
                { ByteArray(4) }, { logs += it }, transport = transport, ids = pool, datagrams = false, disk = SlowDisk(diskMs))
            client = LinkClient(config, { p -> Endpoint("127.0.0.1", mac.port, p.macName, p.macId) }, object : ClientListener {
                override fun onStatus(status: SessionStatus) { connected = status.phase == Phase.CONNECTED }
                override fun onTarget(target: StreamTarget) {}
                override fun onPointer(status: PointerStatus) { pointerReady = status.ready }
            })
            clients += client
            client.notesChanged(notes.notes, null)
            client.start()
            client.connect(mac.endpoint)
            assertTrue("asked for a code", mac.await { mac.hellos.isNotEmpty() })
            client.submitCode("4242")
            assertTrue("connected", mac.await { connected })
            client.setViewing("note-view")
            client.setPointer(true)
            mac.send(Control.PointerState(1, true, "ready"))
            assertTrue("pointer ready", mac.await { pointerReady })
        }

        fun key(stroke: Int, index: Int) = (stroke.toLong() shl 32) or (index.toLong() and 0xffffffffL)

        /** [count] strokes of 1 s (240 samples at 240 Hz, 60 batches a second), [gapMs] apart. */
        fun pen(count: Int, gapMs: Long) {
            val mirror = CanvasMirror(feed, pool)
            repeat(count) {
                val id = mirror.begin("note-view", "sheet", brush, 1f, 0f, 0f, false, false) ?: run { skipped.incrementAndGet(); null } ?: return@repeat
                val start = System.nanoTime()
                for (batch in 0 until 60) {
                    val due = start + (batch + 1) * 1_000_000_000L / 60
                    while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime())
                    for (i in 0 until 4) mirror.sample(10f + batch, 20f + i, .5f, 0f, 0f, System.nanoTime() / 1_000_000)
                    penSent[key(id.hashCode(), batch * 4)] = System.nanoTime()
                    mirror.flush()
                }
                mirror.end()
                Thread.sleep(gapMs)
            }
        }

        private var nextPointer = 0
        /** Pointer motion at 120 packets a second for [ms]. */
        fun pointer(ms: Long) {
            val start = System.nanoTime(); var n = 0
            while (System.nanoTime() - start < ms * 1_000_000) {
                val due = start + n * 1_000_000_000L / 120
                while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime())
                val k = nextPointer++
                pointerSent[k.toLong()] = System.nanoTime()
                client.pointerInput(PointerInput(dx = (k / 1000).toDouble(), dy = (k % 1000).toDouble()))
                n++
            }
        }

        /** The Mac sends this phone a file of [size] bytes in 65,000-byte chunks at [RATE], on its own thread. */
        fun macSends(size: Int): Thread {
            val data = ByteArray(size) { (it % 249).toByte() }
            val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            val chunks = (size + Wire.FILE_CHUNK_DATA - 1) / Wire.FILE_CHUNK_DATA
            return Thread {
                mac.send(Control.File(77, "note-in", 9, size.toLong(), sha, chunks.toLong(), false))
                val start = System.nanoTime(); var sent = 0L
                for (i in 0 until chunks) {
                    val n = minOf(Wire.FILE_CHUNK_DATA, size - i * Wire.FILE_CHUNK_DATA)
                    val due = start + sent * 1_000_000_000L / RATE
                    while (System.nanoTime() < due) LockSupport.parkNanos(due - System.nanoTime())
                    mac.rawWrite(Wire.frame(Wire.fileChunk(77, i.toLong(), data.copyOfRange(i * Wire.FILE_CHUNK_DATA, i * Wire.FILE_CHUNK_DATA + n))))
                    sent += n
                }
                mac.send(Control.FileEnd(77))
            }.apply { isDaemon = true; start() }
        }

        /** The phone holds a large note the Mac lacks: it goes out at once, and is still going for a while. */
        fun phoneSends(size: Int) {
            notes.notes += NoteMeta("note-out", 2, "Large")
            notes.contents["note-out"] = ByteArray(size) { (it % 251).toByte() }
            client.notesChanged(notes.notes, null)
            assertTrue("the transfer started", mac.await { mac.controls.any { it is Control.File && it.id == "note-out" } })
            Thread.sleep(300)
        }
    }

    private fun pct(v: List<Long>, p: Double): Double = if (v.isEmpty()) -1.0 else v.sorted()[minOf(v.size - 1, (v.size * p).toInt())] / 1e6
    private class Ages(val p50: Double, val p95: Double, val max: Double, val n: Int)
    private fun ages(v: List<Long>) = Ages(pct(v, 0.5), pct(v, 0.95), (v.maxOrNull() ?: 0L) / 1e6, v.size)
    private fun line(name: String, kind: String, a: Ages, sent: Int) =
        println("INPUT-AGE workload=$name input=$kind p50=%.1f p95=%.1f max=%.1f ms delivered=${a.n}/$sent rate=${RATE / 1000}kB/s".format(java.util.Locale.ROOT, a.p50, a.p95, a.max))

    /** One workload on a fresh rig; prints and returns (pen, pointer) ages. */
    private fun run(name: String, unsent: Int = UNSENT_BYTES, setup: (Rig) -> Unit = {}, during: (Rig) -> Unit = {}): Pair<Ages, Ages> {
        val rig = Rig(unsent, DISK_MS)
        setup(rig)
        val stall = Thread { during(rig) }.apply { start() }
        rig.pen(STROKES, GAP_MS)
        if (System.getenv("SYNCR_BULK_LOGS") == "1") Thread.getAllStackTraces().filter { it.key.name.startsWith("syncr-link") || it.key.name.startsWith("fake-mac") || it.key.name.startsWith("throttle") }
            .forEach { (t, st) -> println("STACK $name ${t.name} " + st.take(6).joinToString(" < ")) }
        rig.pointer(POINTER_MS)
        stall.join()
        Thread.sleep(400)
        val pen = ages(rig.penAges); val pointer = ages(rig.pointerAges)
        line(name, "pen", pen, STROKES * 60)
        line(name, "pointer", pointer, rig.nextPointerCount())
        println("INPUT-AGE workload=$name strokes-not-begun=${rig.skipped.get()} pen-frames-unseen=${rig.penSent.size}")
        if (System.getenv("SYNCR_BULK_LOGS") == "1") rig.logs.forEach { println("LOG $name $it") }
        assertTrue("$name: a pointer packet reached the Mac twice", rig.pointerRepeats.get() == 0)
        rig.client.stop(wait = true); rig.mac.close()
        return pen to pointer
    }

    private fun Rig.nextPointerCount() = pointerSent.size

    /** About 45 s of real time: run by the latency harness (`code/shared/latency/run.sh bulk`) or with SYNCR_BULK_TEST=1. */
    @Test fun inputAgeIdleUnderBulkAndWithAStalledPeer() {
        org.junit.Assume.assumeTrue(System.getenv("SYNCR_BULK_TEST") == "1")
        val (idlePen, idlePointer) = run("idle")
        val (outPen, outPointer) = run("bulk-out", setup = { it.phoneSends(24_000_000) })
        val (inPen, inPointer) = run("bulk-in", setup = { it.macSends(16_000_000) })
        // The Mac stops reading for 3 s mid-stroke, and for 1 s while the trackpad moves (gesture safety: stale pointer motion
        // is dropped and pointer mode ends, never replayed).
        run("stalled-peer", during = {
            Thread.sleep(700); it.stalledUntil.set(System.nanoTime() + 3_000_000_000L)
            Thread.sleep(STROKES * (1_000 + GAP_MS) - 700 + 500); it.stalledUntil.set(System.nanoTime() + 1_000_000_000L)
        })
        for ((name, pen, pointer) in listOf(Triple("bulk-out", outPen, outPointer), Triple("bulk-in", inPen, inPointer))) {
            assertTrue("$name: pen p95 ${pen.p95} ms vs idle ${idlePen.p95}", !ENFORCE || pen.p95 <= idlePen.p95 + 8)
            assertTrue("$name: pointer p95 ${pointer.p95} ms vs idle ${idlePointer.p95}", !ENFORCE || pointer.p95 <= idlePointer.p95 + 8)
            assertTrue("$name: pen frames lost (${pen.n} of ${STROKES * 60})", !ENFORCE || pen.n == STROKES * 60)
        }
    }

    companion object {
        /** 20 Mbit/s: a busy home Wi-Fi link, not a fast one. */
        val RATE = (System.getenv("SYNCR_BULK_RATE_KBPS")?.toLong() ?: 2_500L) * 1000
        /** What the kernel holds unsent ahead of a new write: see [LinkTuning.UNSENT_BYTES]. */
        val UNSENT_BYTES = System.getenv("SYNCR_BULK_UNSENT")?.toInt() ?: LinkTuning.UNSENT_BYTES
        val DISK_MS = System.getenv("SYNCR_BULK_DISK_MS")?.toLong() ?: 15L
        const val STROKES = 5
        const val GAP_MS = 600L
        const val POINTER_MS = 2_000L
        const val SLICE = 1024
        val ENFORCE = System.getenv("SYNCR_BULK_REPORT_ONLY") != "1"
    }
}
