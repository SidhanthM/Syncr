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
    val connectTimeoutMs: Int = 1_500,
    /** Silence that ends an idle connection (the Mac pings every 2 s). */
    val staleMs: Long = 12_000,
    /** Silence that ends a connection with a pad, the pointer or a note transfer open: any inbound byte counts as life. */
    val staleActiveMs: Long = 5_000,
    /** A `hello` the Mac never answers. */
    val helloAnswerMs: Long = 5_000,
    /** After a network change the Mac gets this long to answer a ping before the connection is called dead. */
    val probeMs: Long = 2_500,
    /** A dropped connection that comes back within this long changes nothing the owner sees; later it reads "Reconnecting". */
    val statusHoldMs: Long = 2_000,
    /** The status shown to the owner changes at most this often. */
    val statusGapMs: Long = 1_000,
    val backoff: Backoff = Backoff(),
    /** Pad strokes kept until the Mac confirms them; the app shares one across the clients that come and go with the foreground. */
    val keeper: StrokeKeeper = StrokeKeeper(),
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
 * at once, 0.5, 1, 2, 4, 8 then every 10 seconds while the phone is paired, and at once whenever [kick] says the network changed. A reader thread turns bytes into messages; everything
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
    private val backoff = cfg.backoff
    @Volatile private var kicked = false
    @Volatile private var probeAt = 0L
    /** Why the connection ended, for the one line it leaves. */
    @Volatile private var cause = ""
    private val gate = StatusGate()

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
    /** The pen frame the Mac last reported while the pad was open: the pad asks for it again after a reconnect. */
    @Volatile private var appFrame: CanvasRect? = null
    @Volatile private var currentScheduler: SendScheduler? = null
    @Volatile private var lastFileInbound = 0L
    private var datagrams: DatagramPath? = null
    @Volatile private var appOpen: String? = null
    @Volatile private var appNotes: List<NoteMeta> = emptyList()

    private val latency = LatencyLog(cfg.latencyContext) { cfg.log(it) }
    private var lastStatusLine: String? = null
    private val pump = FeedPump(cfg.feed, cfg.out, cfg.clock, cfg.encodeSamples, cfg.log, cfg.keeper)
    private val wake = Runnable { thread?.let { LockSupport.unpark(it) } }

    val latencyLog: LatencyLog get() = latency

    /** The current session's epoch, and how many preview frames the link thread has queued, for the probe. */
    val epoch: Long get() = pump.epoch
    val framesQueued: Int get() = pump.framesQueued
    val feed: PenFeed get() = cfg.feed

    /** Pad strokes sent and not yet confirmed by the Mac, for the probe. */
    val keptStrokes: Int get() = cfg.keeper.size

    /** The Mac confirms strokes, so a drop does not lose the pad's ink and the app may keep its pad target through one. */
    val resumable: Boolean get() = cfg.keeper.peerKeeps

    fun start() {
        if (running) return
        running = true
        // After a foreground that ended, a stroke drawn while this client finds the Mac again is kept like any other.
        cfg.feed.enabled = cfg.keeper.peerKeeps
        cfg.feed.wake = wake
        // The pen's frames leave on this thread: it must not wait behind sync or UI work for a core.
        thread = Thread(::run, "syncr-link").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        Thread(::watchdog, "syncr-link-watchdog").apply { isDaemon = true; start() }
    }

    /** Stops the link thread. It winds down on its own; [wait] joins it (tests). */
    fun stop(wait: Boolean = false) {
        running = false
        if (cause.isEmpty()) cause = "stopped"
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
        if (welcomed || session != null) { cause = "replaced by a Mac chosen by hand"; closeSocket() }
        wake.run()
    }

    /**
     * The network changed under the link (Wi-Fi came back, the address or route changed), or the app came to the front. Waiting
     * out a retry delay is then pointless: connect at once. A connection that is up is asked to prove itself: the Mac gets
     * [ClientConfig.probeMs] to answer a ping, or the connection is closed and made again.
     */
    fun networkChanged() {
        if (welcomed) {
            probeAt = cfg.clock.nowMs()
            post { session?.pingNow() }
        } else kick()
    }

    /** Retry now, whatever the delay is. */
    fun kick() {
        kicked = true
        wake.run()
    }

    /** The four digits the Mac showed. */
    fun submitCode(code: String) = post { session?.submitCode(code) }

    /** Deletes the stored pairing and drops the connection. */
    fun forget() {
        cfg.store.forget()
        chosen.set(null)
        cause = "forgotten"
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
        if (!on) appFrame = null
        post { session?.setPad(on) }
    }

    /** The pen frame the pad had before this client was made (the app came back to the front): asked for again once the Mac welcomes the pad. */
    fun resumeFrame(rect: CanvasRect?) { appFrame = rect }

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

    override fun status(status: SessionStatus) = gate.offer(status)

    /**
     * What the owner is told about the connection, calmed (link K.5): a connection that dropped and is back within
     * [ClientConfig.statusHoldMs] shows no change at all; a longer one shows one "Reconnecting" status until it is back, however
     * often the attempts fail and restart under it; and nothing changes more than once per [ClientConfig.statusGapMs]. A state the
     * owner must act on (a code, a refusal, a stop) is shown at once. Link thread only.
     */
    private inner class StatusGate {
        private var shown: SessionStatus? = null
        private var shownAt = 0L
        private var pending: SessionStatus? = null
        /** When a connection the owner saw as up was lost; 0 while it is up or was never up. */
        private var downSince = 0L
        private var macName: String? = null

        fun offer(s: SessionStatus, immediate: Boolean = false) {
            val now = cfg.clock.nowMs()
            if (s.macName != null) macName = s.macName
            if (s.phase == Phase.CONNECTED) { pending = null; downSince = 0; publish(s, now); return }
            if (immediate || s.phase == Phase.NEEDS_CODE || shown == null) { pending = null; downSince = 0; publish(s, now); return }
            if (shown?.phase == Phase.CONNECTED && downSince == 0L) downSince = now
            pending = s
            tick()
        }

        fun tick() {
            val p = pending ?: return
            val now = cfg.clock.nowMs()
            if (downSince != 0L) {
                if (now - downSince < cfg.statusHoldMs) return
                pending = null
                publish(SessionStatus(Phase.CONNECTING, macName, message = "Reconnecting to ${macName ?: "the Mac"}…"), now)
            } else if (now - shownAt >= cfg.statusGapMs) { pending = null; publish(p, now) }
        }

        private fun publish(s: SessionStatus, now: Long) {
            if (s == shown) return
            shown = s; shownAt = now
            val line = linkStatusLine(s)
            if (line != lastStatusLine) { cfg.log(line); lastStatusLine = line }
            listener.onStatus(s)
        }
    }

    override fun pointer(status: PointerStatus) { listener.onPointer(status) }

    override fun target(target: StreamTarget) {
        if (appPad) target.frame?.let { appFrame = it }
        cfg.log(targetLine(target))
        listener.onTarget(target)
    }

    override fun close(reason: String, fatal: Boolean) {
        closeReason = reason
        this.fatal = fatal
        if (cause.isEmpty()) cause = if (fatal) "fatal: $reason" else "bye: $reason"
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
                kicked = false // nothing to connect to yet: a kick has no use, and left set it would end the wait below at once
                idle(500)
                continue
            }
            val startedAt = cfg.clock.nowMs()
            val reached = connectOnce(endpoint)
            if (!running) break
            // A welcome proves the path works, so the ladder starts again: at once after a session that lasted, after half a second
            // after one that dropped straight away. Only attempts that fail climb it.
            if (reached) backoff.afterWelcomedSession(cfg.clock.nowMs() - startedAt >= Backoff.STABLE_MS)
            // A paired phone keeps trying; one that was never paired waits to be told.
            val retry = cfg.store.pairing() != null && !fatal && chosen.get() == null
            if (fatal) halted = true
            status(SessionStatus(Phase.IDLE, message = closeReason ?: "connection closed"), immediate = fatal || !retry)
            if (retry) {
                val wait = if (kicked) { kicked = false; backoff.reset(); 0L } else backoff.nextMs()
                if (wait > 0) cfg.log("reconnect in ${wait}ms")
                idle(wait)
                // Woken by a kick part-way through the wait: the network changed, so the ladder starts again.
                if (kicked) { kicked = false; backoff.reset() }
            } else {
                if (!halted) status(SessionStatus(Phase.IDLE, message = closeReason), immediate = true)
                kicked = false
                idle(200)
            }
        }
        status(SessionStatus(Phase.IDLE, message = closeReason ?: "foreground ended or link disabled"), immediate = true)
    }

    private fun status(status: SessionStatus, immediate: Boolean) = gate.offer(status, immediate)

    private fun listenerIdle() = SessionStatus(Phase.IDLE, message = closeReason)

    /** Sleeps up to [ms], but wakes for a user choice, a [kick] or a stop. Meanwhile the pen is still heard (strokes are kept). */
    private fun idle(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (running && chosen.get() == null && !kicked) {
            drainInbox()
            gate.tick()
            if (cfg.keeper.peerKeeps) { cfg.ids?.topUp(); pump.pump() }
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

    /** Writes to the socket, from the link thread, and now and then the reader's pong: one message is never torn by another. */
    private class Writer(private val out: java.io.OutputStream) {
        private val lock = java.util.concurrent.locks.ReentrantLock()

        fun write(message: ByteArray) {
            lock.lock()
            try { out.write(Wire.frame(message)); out.flush() } finally { lock.unlock() }
        }

        /** From another thread, and only when the link thread is not mid-write (a pong must not wait behind a stalled write). */
        fun tryWrite(message: ByteArray): Boolean {
            if (!lock.tryLock()) return false
            return try { out.write(Wire.frame(message)); out.flush(); true } catch (_: IOException) { false } finally { lock.unlock() }
        }
    }

    /** Counts every byte that arrives, not every whole message: a long copy on a slow link is alive. */
    private inner class LifeInput(private val inner: java.io.InputStream) : java.io.InputStream() {
        override fun read(): Int = inner.read().also { if (it >= 0) lastInbound = cfg.clock.nowMs() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) lastInbound = cfg.clock.nowMs() }
        override fun available(): Int = inner.available()
    }

    /** Runs one connection to its end. True when it got as far as `welcome`. */
    private fun connectOnce(endpoint: Endpoint): Boolean {
        closeReason = null
        fatal = false
        closing = false
        welcomed = false
        cause = ""
        probeAt = 0
        status(SessionStatus(Phase.CONNECTING, endpoint.name, message = "Connecting to $endpoint…"))
        val s = try {
            cfg.transport.connect(endpoint, cfg.connectTimeoutMs)
        } catch (e: IOException) {
            closeReason = "Couldn't reach $endpoint."
            cfg.log("disconnect cause=connect ${e.javaClass.simpleName} to $endpoint")
            return false
        }
        try { s.tcpNoDelay = true } catch (_: IOException) { }
        socket = s
        val scheduler = SendScheduler(cfg.out, pump)
        currentScheduler = scheduler
        scheduler.wake = wake
        pump.reset()
        pump.control = scheduler::sendControl
        val sync = SyncSender(cfg.source, scheduler, this, cfg.clock, cfg.tempDir, cfg.log, listener::onDelivered, listener::onWanted)
        val events = object : SessionEvents {
            override fun status(status: SessionStatus) {
                if (status.phase == Phase.CONNECTED && !welcomed) {
                    welcomed = true
                    kicked = false
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
        sess.adopt(appViewing, appPad, appOpen, appPadWish, appPointer, appFrame)
        session = sess
        lastInbound = cfg.clock.nowMs()
        val writer = try { Writer(s.getOutputStream()) } catch (e: IOException) { closeSocket(); return false }
        val id = ++connId
        connectedAt = cfg.clock.nowMs()
        val reader = Thread({ readLoop(s, sess, id, writer) }, "syncr-link-reader").apply { isDaemon = true }

        var loggedStrokes = pump.strokesEnded
        var padSentOn = false
        try {
            sess.start()
            reader.start()
            cfg.ids?.topUp()
            while (running && !closing && !s.isClosed) {
                drainInbox()
                gate.tick()
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
                writer.write(message)
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
            if (cause.isEmpty()) cause = "io: ${e.javaClass.simpleName}"
            if (closeReason == null && running && !closing) closeReason = "Lost the connection to the Mac."
        } catch (e: RuntimeException) {
            cfg.log("link session failed: ${e.javaClass.simpleName}")
            if (cause.isEmpty()) cause = "error: ${e.javaClass.simpleName}"
            closeReason = "The link hit an error and was closed."
        } finally {
            if (!running && welcomed && padSentOn && !s.isClosed) {
                try {
                    writer.write(Control.Pad(false).encode())
                    cfg.log("pad off sent")
                } catch (_: IOException) { cfg.log("pad off not sent reason=connection closed") }
            }
            if (welcomed) logDisconnect(scheduler)
            // After stop() the ring and the core's queue may already belong to the next client: leave them alone.
            val owner = cfg.feed.wake === wake
            // A Mac that confirms strokes lets the pad go on collecting them while the link is made again.
            if (owner) cfg.feed.enabled = running && cfg.keeper.peerKeeps
            connId++
            closeSocket()
            datagrams?.let { cfg.log("second path closed datagrams=${it.sent}"); it.close() }
            datagrams = null
            session = null
            currentScheduler = null
            sync.onClosed()
            scheduler.clearControl()
            if (owner) pump.reset()
            if (latency.roundTrip.size() + latency.penToWrite.size() > 0) latency.report("session")
        }
        if (closeReason == null && running && welcomed) closeReason = "Lost the connection to the Mac."
        return welcomed
    }

    /** The one line a lost connection leaves (K.7): why, how long it had been quiet, what was waiting, and how the radio was. No content. */
    private fun logDisconnect(scheduler: SendScheduler) {
        val now = cfg.clock.nowMs()
        val why = cause.ifEmpty { if (running) "unknown" else "stopped" }
        cfg.log(
            "disconnect cause=$why since-inbound=${now - lastInbound}ms up=${now - connectedAt}ms " +
                "queued=control/${scheduler.queuedControl()} preview/${cfg.out.queuedPreviewBytes()}B file/${scheduler.queuedFileChunks()}chunks " +
                "kept-strokes=${cfg.keeper.size} ${cfg.latencyContext()}",
        )
    }

    private fun readLoop(s: Socket, sess: LinkSession, id: Int, writer: Writer) {
        try {
            val input = LifeInput(s.getInputStream())
            while (!s.isClosed) {
                val message = Wire.read(input)
                lastInbound = cfg.clock.nowMs()
                if (Wire.kind(message) == Wire.KIND_FILE) {
                    lastFileInbound = lastInbound
                    post { if (id == connId) sess.onFileChunk(message) }
                    continue
                }
                if (Wire.kind(message) == Wire.KIND_PICTURE) {
                    listener.onPicture(message)
                    continue
                }
                if (Wire.kind(message) != Wire.KIND_CONTROL) continue // the Mac sends no preview or stroke-commit frames
                var parsed: Control? = null
                try { parsed = Control.parse(message) } catch (e: LinkProtocolException) {
                    post { if (id == connId) { if (cause.isEmpty()) cause = "protocol"; close("The Mac sent something this phone could not read (${e.message}).", fatal = false) } }
                }
                val control = parsed ?: continue
                // A ping is answered here, not on the link thread: a long merge or a busy queue there must not look like a dead phone.
                if (control is Control.Ping && welcomed && writer.tryWrite(Control.Pong(control.id, control.at, cfg.clock.nowMs()).encode())) continue
                post {
                    if (id != connId) return@post // a connection that has since ended
                    sess.onControl(control)
                }
            }
        } catch (e: LinkProtocolException) {
            post { if (id == connId) { if (cause.isEmpty()) cause = "protocol"; close("The Mac sent something this phone could not read (${e.message}).", fatal = false) } }
        } catch (e: IOException) {
            post { if (id == connId) { if (cause.isEmpty()) cause = "io: ${e.javaClass.simpleName}"; if (closeReason == null) closeReason = "Lost the connection to the Mac."; closing = true } }
        }
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        try { s?.close() } catch (_: IOException) { }
    }

    /**
     * Closes a connection that has gone quiet. The Mac pings every 2 s (every second while a pad or the pointer is open): with
     * one of those open, or a note on its way, [ClientConfig.staleActiveMs] of silence is a dead link, otherwise
     * [ClientConfig.staleMs]. Any byte received counts. A pass of this thread that came late says nothing about the Mac (the
     * whole app was stalled), so that stretch is forgiven.
     */
    private fun watchdog() {
        var last = cfg.clock.nowMs()
        while (running) {
            try { Thread.sleep(WATCH_MS) } catch (_: InterruptedException) { return }
            val now = cfg.clock.nowMs()
            val late = now - last > 1_500
            last = now
            if (late) { lastInbound = maxOf(lastInbound, now - WATCH_MS); continue }
            if (socket == null) continue
            val active = appPad || appPointer || session?.let { cfg.feed.enabled && pump.penActive() } == true || hasTransfer()
            val limit = if (active) cfg.staleActiveMs else cfg.staleMs
            val silent = welcomed && now - lastInbound > limit
            val noAnswer = session?.phase == Phase.CONNECTING && now - connectedAt > cfg.helloAnswerMs
            val probe = probeAt
            val unproven = probe != 0L && now - probe > cfg.probeMs && lastInbound < probe
            if (silent || noAnswer || unproven) {
                cause = if (unproven) "network changed, no answer" else "watchdog"
                closeReason = "The Mac stopped answering."
                closeSocket()
            }
        }
    }

    /** A note is on its way in either direction: the link has something to lose. */
    private fun hasTransfer(): Boolean = currentScheduler?.file != null || cfg.clock.nowMs() - lastFileInbound < 3_000

    private companion object {
        const val WATCH_MS = 250L
    }
}
