package dev.syncr.prototype.link

import dev.syncr.core.SampleChannels
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

const val SEND_BUFFER_BYTES = 128 * 1024

/** Opens the byte stream. TCP today; the TLS design of `pairing-security.md` slots in here later. */
fun interface Transport {
    fun connect(endpoint: Endpoint, timeoutMs: Int): Socket

    companion object {
        val TCP = Transport { endpoint, timeoutMs ->
            Socket().apply {
                tcpNoDelay = true
                // A small send buffer: a pen frame queues behind at most this much of a note copy, not megabytes.
                sendBufferSize = SEND_BUFFER_BYTES
                connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            }
        }
    }
}

/** What the UI layer wants to hear from the client. Called on the link thread. */
interface ClientListener {
    fun onStatus(status: SessionStatus)
    fun onTarget(target: StreamTarget)
    fun onPointer(status: PointerStatus) {}

    /** A session reached `welcome` on [endpoint]. */
    fun onConnected(endpoint: Endpoint) {}

    /** The Mac asked for these notes (`want`). */
    fun onWanted(ids: List<String>) {}

    /** The Mac confirmed a note with `have`. */
    fun onDelivered(id: String, seq: Long) {}

    /** A picture of what the pad covers (a whole `0x53` message). Called on the reader thread: hand it on, do not decode here. */
    fun onPicture(message: ByteArray) {}
}

class ClientConfig(
    val device: String,
    val store: PairingStore,
    val source: NoteSource,
    val clock: Clock,
    val tempDir: File,
    val out: PreviewOut,
    val feed: PenFeed,
    val encodeSamples: (SampleChannels) -> ByteArray,
    val log: (String) -> Unit,
    val transport: Transport = Transport.TCP,
    val connectTimeoutMs: Int = 3_000,
    val staleMs: Long = 12_000,
    /** Stroke IDs for the pen side, topped up on the link thread. */
    val ids: IdPool? = null,
    /** Read on the link worker, never the pen thread. */
    val latencyContext: () -> String = { "radio=host/unavailable" },
    /** Link version to speak (0: wifi-link-v0 only, for the joined test's old-phone probe). */
    val linkVersion: Int = Control.VERSION,
    /** False keeps every preview frame on the connection alone (tests of the single path). */
    val datagrams: Boolean = true,
)

/**
 * The second path for preview frames. The connection carries every frame in order and is never skipped; this sends the
 * same frames as datagrams as well, each with the one before it, so the Mac can draw on through a stall of the
 * connection (a lost packet holds everything behind it until it is sent again). The Mac hands each frame on once, from
 * whichever path brings it first. Link thread only. A failure only means the connection carries on alone.
 */
internal class DatagramPath(host: java.net.InetAddress, port: Int, private val key: Long) {
    private val socket = java.net.DatagramSocket().apply {
        connect(java.net.InetSocketAddress(host, port))
        // Expedited forwarding: Wi-Fi sends it in its voice queue, ahead of ordinary traffic.
        try { trafficClass = 0xB8 } catch (_: java.net.SocketException) { }
    }
    private var next = 0L
    private var previous: ByteArray? = null
    private var failures = 0
    var sent = 0L
        private set

    /** Call once for every preview message, in the order they go to the connection, before the write. */
    fun send(frame: ByteArray) {
        val seq = next++
        val before = previous
        previous = frame
        if (failures > 8) return
        val datagram = before?.let { Wire.datagram(key, seq - 1, listOf(it, frame)) } ?: Wire.datagram(key, seq, listOf(frame)) ?: return
        try {
            socket.send(java.net.DatagramPacket(datagram, datagram.size))
            sent++
            failures = 0
        } catch (_: IOException) { failures++ }
    }

    fun close() { socket.close() }
}

/**
 * One foreground period of the link: a link thread that connects to the Mac, runs a [LinkSession], and reconnects after
 * 1, 2, 4, 8 then every 10 seconds while the phone is paired. A reader thread turns bytes into messages; everything
 * else, including every write, happens on the link thread. Public calls are safe from any thread and only enqueue.
 *
 * Nothing here is reachable from the pen: strokes arrive through [PenFeed] and are written when the link thread gets to
 * them. A dead or slow Mac fills the ring, which drops preview, and local ink never notices.
 */
class LinkClient(
    private val cfg: ClientConfig,
    /** Where to look for the paired Mac when nothing was chosen by hand (discovery, last address). */
    private val endpointFor: (Pairing) -> Endpoint?,
    private val listener: ClientListener,
) : LinkLoop, SessionEvents {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val inbox = ConcurrentLinkedQueue<Runnable>()
    private val helpers = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "syncr-link-io").apply { isDaemon = true } }
    private val chosen = AtomicReference<Endpoint?>()
    private val backoff = Backoff()

    // Connection state: the link thread owns it; [socket] is also closed from other threads.
    @Volatile private var socket: Socket? = null
    @Volatile private var session: LinkSession? = null
    @Volatile private var lastInbound = 0L
    @Volatile private var welcomed = false
    private var closeReason: String? = null
    private var fatal = false
    @Volatile private var halted = false
    @Volatile private var connectedAt = 0L
    private var connId = 0
    @Volatile private var closing = false

    // What the app wants, kept across connections.
    @Volatile private var appViewing: String? = null
    @Volatile private var appPad = false
    @Volatile private var appPointer = false
    private val pointerQueued = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var appPadWish: PadWish? = null
    private var datagrams: DatagramPath? = null
    @Volatile private var appOpen: String? = null
    @Volatile private var appNotes: List<NoteMeta> = emptyList()

    private val latency = LatencyLog(cfg.latencyContext) { cfg.log(it) }
    private var lastStatusLine: String? = null
    private val pump = FeedPump(cfg.feed, cfg.out, cfg.clock, cfg.encodeSamples, cfg.log)
    private val wake = Runnable { thread?.let { LockSupport.unpark(it) } }

    val latencyLog: LatencyLog get() = latency

    /** The current session's epoch, and how many preview frames the link thread has queued, for the probe. */
    val epoch: Long get() = pump.epoch
    val framesQueued: Int get() = pump.framesQueued
    val feed: PenFeed get() = cfg.feed

    fun start() {
        if (running) return
        running = true
        cfg.feed.wake = wake
        // The pen's frames leave on this thread: it must not wait behind sync or UI work for a core.
        thread = Thread(::run, "syncr-link").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        Thread(::watchdog, "syncr-link-watchdog").apply { isDaemon = true; start() }
    }

    /** Stops the link thread. It winds down on its own; [wait] joins it (tests). */
    fun stop(wait: Boolean = false) {
        running = false
        // The pen's ring is shared with whichever client starts next: hand it back before this thread winds down.
        cfg.feed.wake = null
        cfg.feed.enabled = false
        val stopping = socket
        if (welcomed && stopping != null) {
            // Give the worker a bounded chance to send pad-off. Never wait on the UI/pen thread.
            Thread({
                try { Thread.sleep(200) } catch (_: InterruptedException) { }
                try { stopping.close() } catch (_: IOException) { }
            }, "syncr-link-close").apply { isDaemon = true; start() }
        } else closeSocket()
        wake.run()
        helpers.shutdown()
        if (wait) thread?.join(2_000)
    }

    /** Connect to [endpoint] now (a Mac picked by hand). Replaces a connection to another Mac. */
    fun connect(endpoint: Endpoint) {
        halted = false
        chosen.set(endpoint)
        if (welcomed || session != null) closeSocket()
        wake.run()
    }

    /** The four digits the Mac showed. */
    fun submitCode(code: String) = post { session?.submitCode(code) }

    /** Deletes the stored pairing and drops the connection. */
    fun forget() {
        cfg.store.forget()
        chosen.set(null)
        closeSocket()
        wake.run()
    }

    fun notesChanged(notes: List<NoteMeta>, open: String?) {
        appNotes = notes
        appOpen = open
        post { session?.notesChanged(notes, open) }
    }

    fun setViewing(id: String?) {
        appViewing = id
        post { session?.setViewing(id) }
    }

    fun setPad(on: Boolean) {
        appPad = on
        post { session?.setPad(on) }
    }

    fun setPadWish(wish: PadWish?) {
        appPadWish = wish
        post { session?.setPadWish(wish) }
    }

    fun requestFrame(rect: CanvasRect) = post { session?.requestFrame(rect) }

    fun setPointer(on: Boolean) {
        appPointer = on
        post { session?.setPointer(on) }
    }

    fun pointerInput(input: PointerInput) {
        if (!appPointer || !input.valid()) return
        if (pointerQueued.incrementAndGet() > 64) { pointerQueued.decrementAndGet(); setPointer(false); return }
        val sent = cfg.clock.nowMs()
        val original = session
        post {
            pointerQueued.decrementAndGet()
            // Never replay an old gesture on a new connection or after a transport stall.
            if (original === session && appPointer) {
                if (cfg.clock.nowMs() - sent <= 200) session?.pointerInput(input)
                else setPointer(false)
            }
        }
    }

    // ---- LinkLoop ----

    override fun post(task: Runnable) {
        inbox.add(task)
        wake.run()
    }

    override fun background(task: Runnable) {
        try { helpers.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    // ---- SessionEvents ----

    override fun status(status: SessionStatus) {
        val line = linkStatusLine(status)
        if (line != lastStatusLine) { cfg.log(line); lastStatusLine = line }
        listener.onStatus(status)
    }

    override fun pointer(status: PointerStatus) { listener.onPointer(status) }

    override fun target(target: StreamTarget) {
        cfg.log(targetLine(target))
        listener.onTarget(target)
    }

    override fun close(reason: String, fatal: Boolean) {
        closeReason = reason
        this.fatal = fatal
        closing = true
    }

    // ---- The link thread ----

    private fun run() {
        while (running) {
            val explicit = chosen.getAndSet(null)
            val pairing = cfg.store.pairing()
            val endpoint = explicit ?: if (!halted && pairing != null) endpointFor(pairing) else null
            if (endpoint == null) {
                if (pairing == null || halted) status(listenerIdle())
                else status(SessionStatus(Phase.CONNECTING, pairing.macName, message = "Looking for ${pairing.macName}…"))
                idle(500)
                continue
            }
            val reached = connectOnce(endpoint)
            status(SessionStatus(Phase.IDLE, message = closeReason ?: if (running) "connection closed" else "foreground ended or link disabled"))
            if (!running) break
            if (reached) backoff.reset()
            // A paired phone keeps trying; one that was never paired waits to be told.
            val retry = cfg.store.pairing() != null && !fatal && chosen.get() == null
            if (fatal) halted = true
            if (retry) idle(backoff.nextMs()) else if (!halted) status(SessionStatus(Phase.IDLE, message = closeReason))
            if (!retry) idle(200)
        }
        status(SessionStatus(Phase.IDLE, message = closeReason ?: "foreground ended or link disabled"))
    }

    private fun listenerIdle() = SessionStatus(Phase.IDLE, message = closeReason)

    /** Sleeps up to [ms], but wakes for a user choice or a stop. */
    private fun idle(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (running && chosen.get() == null) {
            drainInbox()
            val left = until - System.nanoTime()
            if (left <= 0) return
            LockSupport.parkNanos(minOf(left, 100_000_000L))
        }
    }

    private fun drainInbox() {
        while (true) {
            val task = inbox.poll() ?: return
            try { task.run() } catch (e: Exception) { cfg.log("link task failed: ${e.javaClass.simpleName}") }
        }
    }

    /** Runs one connection to its end. True when it got as far as `welcome`. */
    private fun connectOnce(endpoint: Endpoint): Boolean {
        closeReason = null
        fatal = false
        closing = false
        welcomed = false
        status(SessionStatus(Phase.CONNECTING, endpoint.name, message = "Connecting to $endpoint…"))
        val s = try {
            cfg.transport.connect(endpoint, cfg.connectTimeoutMs)
        } catch (e: IOException) {
            closeReason = "Couldn't reach $endpoint."
            return false
        }
        try { s.tcpNoDelay = true } catch (_: IOException) { }
        socket = s
        val scheduler = SendScheduler(cfg.out, pump)
        scheduler.wake = wake
        pump.reset()
        val sync = SyncSender(cfg.source, scheduler, this, cfg.clock, cfg.tempDir, cfg.log, listener::onDelivered, listener::onWanted)
        val events = object : SessionEvents {
            override fun status(status: SessionStatus) {
                if (status.phase == Phase.CONNECTED && !welcomed) {
                    welcomed = true
                    listener.onConnected(endpoint)
                }
                this@LinkClient.status(status)
            }
            override fun target(target: StreamTarget) = this@LinkClient.target(target)
            override fun pointer(status: PointerStatus) = this@LinkClient.pointer(status)
            override fun close(reason: String, fatal: Boolean) = this@LinkClient.close(reason, fatal)
            override fun datagrams(port: Int, key: Long) {
                if (!cfg.datagrams || datagrams != null) return
                datagrams = try { DatagramPath(s.inetAddress, port, key) } catch (e: Exception) { null }
                cfg.log(if (datagrams != null) "second path on: preview frames also go as datagrams to port $port" else "second path unavailable; the connection carries on alone")
            }
        }
        val sess = LinkSession(cfg.device, cfg.store, endpoint, scheduler, sync, pump, cfg.feed, cfg.clock, latency, cfg.source, events, cfg.linkVersion)
        sess.adopt(appViewing, appPad, appOpen, appPadWish, appPointer)
        session = sess
        lastInbound = cfg.clock.nowMs()
        val output = try { s.getOutputStream() } catch (e: IOException) { closeSocket(); return false }
        val id = ++connId
        connectedAt = cfg.clock.nowMs()
        val reader = Thread({ readLoop(s, sess, id) }, "syncr-link-reader").apply { isDaemon = true }

        var loggedStrokes = pump.strokesEnded
        var padSentOn = false
        try {
            sess.start()
            reader.start()
            cfg.ids?.topUp()
            while (running && !closing && !s.isClosed) {
                drainInbox()
                sess.tick()
                sync.tick()
                val message = scheduler.next()
                if (message == null) {
                    cfg.ids?.topUp()
                    LockSupport.parkNanos(50_000_000L)
                    continue
                }
                // Preview frames before welcome do not exist, so both ends count them from zero.
                if (Wire.kind(message) == Wire.KIND_PREVIEW) datagrams?.send(message)
                output.write(Wire.frame(message))
                output.flush()
                if (Wire.kind(message) == Wire.KIND_CONTROL) {
                    val control = Control.parse(message)
                    if (control is Control.Pad) {
                        padSentOn = control.on
                        cfg.log("pad ${if (control.on) "on" else "off"} sent")
                    }
                }
                if (Wire.kind(message) == Wire.KIND_PREVIEW) pump.onFrameWritten(message)?.let { latency.penToWrite.add(it) }
                if (pump.strokesEnded != loggedStrokes && cfg.out.queuedPreviewBytes() == 0L) {
                    loggedStrokes = pump.strokesEnded
                    latency.report("stroke $loggedStrokes")
                }
            }
        } catch (e: IOException) {
            if (closeReason == null && running && !closing) closeReason = "Lost the connection to the Mac."
        } catch (e: RuntimeException) {
            cfg.log("link session failed: ${e.javaClass.simpleName}")
            closeReason = "The link hit an error and was closed."
        } finally {
            if (!running && welcomed && padSentOn && !s.isClosed) {
                try {
                    output.write(Wire.frame(Control.Pad(false).encode()))
                    output.flush()
                    cfg.log("pad off sent")
                } catch (_: IOException) { cfg.log("pad off not sent reason=connection closed") }
            }
            // After stop() the ring and the core's queue may already belong to the next client: leave them alone.
            val owner = cfg.feed.wake === wake
            if (owner) cfg.feed.enabled = false
            connId++
            closeSocket()
            datagrams?.let { cfg.log("second path closed datagrams=${it.sent}"); it.close() }
            datagrams = null
            session = null
            sync.onClosed()
            scheduler.clearControl()
            if (owner) pump.reset()
            if (latency.roundTrip.size() + latency.penToWrite.size() > 0) latency.report("session")
        }
        if (closeReason == null && running && welcomed) closeReason = "Lost the connection to the Mac."
        return welcomed
    }

    private fun readLoop(s: Socket, sess: LinkSession, id: Int) {
        try {
            val input = s.getInputStream()
            while (!s.isClosed) {
                val message = Wire.read(input)
                lastInbound = cfg.clock.nowMs()
                if (Wire.kind(message) == Wire.KIND_FILE) {
                    post { if (id == connId) sess.onFileChunk(message) }
                    continue
                }
                if (Wire.kind(message) == Wire.KIND_PICTURE) {
                    listener.onPicture(message)
                    continue
                }
                if (Wire.kind(message) != Wire.KIND_CONTROL) continue // the Mac sends no preview or stroke-commit frames
                post {
                    if (id != connId) return@post // a connection that has since ended
                    try {
                        Control.parse(message)?.let(sess::onControl)
                    } catch (e: LinkProtocolException) {
                        close("The Mac sent something this phone could not read (${e.message}).", fatal = false)
                    }
                }
            }
        } catch (e: LinkProtocolException) {
            post { if (id == connId) close("The Mac sent something this phone could not read (${e.message}).", fatal = false) }
        } catch (_: IOException) {
            post { if (id == connId) { if (closeReason == null) closeReason = "Lost the connection to the Mac."; closing = true } }
        }
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        try { s?.close() } catch (_: IOException) { }
    }

    /** Closes a connection that has gone quiet; the Mac pings every 2 s, so 12 s of nothing is a dead link. */
    private fun watchdog() {
        while (running) {
            try { Thread.sleep(2_000) } catch (_: InterruptedException) { return }
            val now = cfg.clock.nowMs()
            val silent = welcomed && now - lastInbound > cfg.staleMs
            val noAnswer = session?.phase == Phase.CONNECTING && now - connectedAt > cfg.staleMs
            if (socket != null && (silent || noAnswer)) {
                closeReason = "The Mac stopped answering."
                closeSocket()
            }
        }
    }
}
