package dev.syncr.prototype.link

/** What a session reports upward. Called on the link thread. */
interface SessionEvents {
    fun status(status: SessionStatus)
    fun target(target: StreamTarget)
    fun pointer(status: PointerStatus) {}

    /** The session is over on the Mac's say-so or ours. [fatal]: reconnecting cannot help until the user acts. */
    fun close(reason: String, fatal: Boolean)

    /** The Mac also takes preview frames as datagrams on [port], each starting with [key]. */
    fun datagrams(port: Int, key: Long) {}
}

/** What the pad asks of the Mac with `padmode`. */
data class PadWish(val local: Boolean, val pics: Boolean, val aspect: Double, val read: Boolean = false)

/**
 * The client side of one connection, as a state machine (wifi-link-v0 §3). Link thread only; it never touches a socket:
 * it queues control messages on the [SendScheduler] and reacts to what [onControl] is given.
 *
 * hello, then either `welcome` (token accepted) or `pair`, a four-digit [submitCode], `paired` and `welcome`. After
 * welcome: the manifest, ping every 2 s, `viewing` and `pad` as the app sets them.
 */
class LinkSession(
    private val device: String,
    private val store: PairingStore,
    private val target: Endpoint,
    private val scheduler: SendScheduler,
    private val sync: SyncSender,
    private val pump: FeedPump,
    private val feed: PenFeed,
    private val clock: Clock,
    private val latency: LatencyLog,
    private val source: NoteSource,
    private val events: SessionEvents,
    /** The link version this phone speaks: sent in `hello`, and the most it will act on. 0 behaves exactly as wifi-link-v0. */
    private val version: Int = Control.VERSION,
) {
    @Volatile var phase = Phase.CONNECTING
        private set

    /** The open note the app last reported, for the manifest and `viewing`. */
    private var openId: String? = null
    private var viewing: String? = null
    private var padOn = false
    private var peerVersion = 0
    private var pointerWanted = false
    private var pointerStatus = PointerStatus()
    private var pointerSeq = 0L
    private var padWish: PadWish? = null
    /** The pen frame the Mac last reported while the pad was open: asked for again after a reconnect, so the pad resumes where it was. */
    private var padFrame: CanvasRect? = null
    private var sentToken: String? = null
    private var pendingToken: String? = null
    private var macName: String? = null
    private var pingId = 0L
    private var nextPing = 0L

    fun start() {
        // A stored token goes only to the Mac it was issued by; a Mac that is not known yet gets a chance to say so.
        val stored = store.pairing()?.takeIf { target.macId == null || it.macId == target.macId }
        sentToken = stored?.token
        scheduler.sendControl(Control.Hello(device, store.phoneId(), stored?.token, version, delta = true, push = true, keep = version >= 1))
        events.status(SessionStatus(Phase.CONNECTING, stored?.macName ?: target.name))
    }

    fun onControl(c: Control) {
        when (c) {
            is Control.Welcome -> onWelcome(c)
            Control.PairRequest -> {
                if (phase == Phase.CONNECTED) return
                // A token we sent was unknown: the Mac forgot this phone. Ours is useless now.
                if (sentToken != null) { store.forget(); sentToken = null }
                phase = Phase.NEEDS_CODE
                events.status(SessionStatus(Phase.NEEDS_CODE, target.name))
            }
            is Control.Paired -> if (phase == Phase.NEEDS_CODE && c.token.isNotEmpty()) pendingToken = c.token
            is Control.Denied -> {
                if (phase != Phase.NEEDS_CODE) return
                if (c.left <= 0) {
                    events.close("Too many wrong codes. Start again for a new code.", fatal = true)
                } else {
                    events.status(SessionStatus(Phase.NEEDS_CODE, target.name, c.left, "That code was wrong."))
                }
            }
            is Control.Bye -> onBye(c)
            is Control.Ping -> scheduler.sendControl(Control.Pong(c.id, c.at, clock.nowMs()))
            is Control.Pong -> {
                val rtt = clock.nowMs() - c.at
                if (rtt >= 0) latency.roundTrip.add(rtt * 1000)
            }
            is Control.Want -> if (phase == Phase.CONNECTED) sync.onWant(c.ids, c.base)
            is Control.Have -> if (phase == Phase.CONNECTED) { sync.onHave(c.id, c.seq, c.digest); sync.receiver.onPeerHave(c.id, c.digest) }
            is Control.Notes -> if (phase == Phase.CONNECTED) sync.receiver.onNotes(c.notes)
            is Control.File -> if (phase == Phase.CONNECTED) sync.receiver.onFile(c)
            is Control.FileEnd -> if (phase == Phase.CONNECTED) sync.receiver.onFileEnd(c.xfer)
            is Control.FileError -> if (phase == Phase.CONNECTED) sync.onFileError(c.xfer, c.id, c.reason)
            is Control.StrokeAck -> if (phase == Phase.CONNECTED) pump.onAck(c.id, c.ok)
            is Control.StrokeRev -> Unit
            is Control.Target -> if (phase == Phase.CONNECTED) {
                if (padOn) c.frame?.let { padFrame = it }
                events.target(StreamTarget(c.note, c.sheet, c.writable, c.rev, c.frame, c.view, c.pageHeight))
            }
            is Control.Udp -> if (phase == Phase.CONNECTED && version >= 1) {
                events.datagrams(c.port, java.lang.Long.parseUnsignedLong(c.key, 16))
            }
            is Control.PointerState -> if (phase == Phase.CONNECTED && peerVersion >= 1) {
                val discovered = pointerStatus.version == 0
                pointerStatus = PointerStatus(c.v, c.ready && c.v == 1, c.message)
                events.pointer(pointerStatus)
                if (discovered && pointerWanted && c.v == 1) scheduler.sendControl(Control.PointerMode(true))
            }
            is Control.PointerMode, is Control.Pointer -> Unit
            // The Mac never sends these; a confused or hostile one is ignored rather than trusted.
            is Control.Hello, is Control.Code, is Control.Viewing, is Control.Pad, is Control.PadMode, is Control.Frame, is Control.Scroll -> Unit
        }
    }

    private fun onWelcome(c: Control.Welcome) {
        if (c.v !in Control.OLDEST..version) {
            events.close("This Mac app speaks a different link version.", fatal = true)
            return
        }
        peerVersion = c.v
        val token = pendingToken
        val existing = store.pairing()
        if (token != null) store.save(Pairing(c.macId, c.mac, token))
        else if (existing != null && sentToken != null) store.save(existing.copy(macId = c.macId, macName = c.mac))
        pendingToken = null
        macName = c.mac
        phase = Phase.CONNECTED
        pump.epoch = c.epoch
        pump.control = scheduler::sendControl
        // Strokes the Mac has not confirmed go again; the pen is let in only after that, so a new stroke queues behind them.
        pump.goOnline(keep = c.keep && c.v >= 1 && version >= 1)
        feed.enabled = true
        nextPing = clock.nowMs() + PING_MS
        events.status(SessionStatus(Phase.CONNECTED, c.mac))
        sync.onWelcome(source.list(), openId, minOf(c.v, version), c.delta, c.push)
        viewing?.let { scheduler.sendControl(Control.Viewing(it)) }
        if (padOn) {
            scheduler.sendControl(Control.Pad(true)); sendPadWish()
            // The same pen frame as before the drop (the Mac's own default would put it back at the start).
            val frame = padFrame
            if (frame != null && version >= 1 && padWish?.local == true) scheduler.sendControl(Control.Frame(frame))
        }
    }

    private fun sendPadWish() {
        val w = padWish ?: return
        if (version >= 1) scheduler.sendControl(Control.PadMode(w.local, w.pics, w.aspect, w.read))
    }

    private fun onBye(c: Control.Bye) {
        when (c.reason) {
            "unpaired" -> {
                store.forget()
                events.close("The Mac forgot this phone. Connect again to pair.", fatal = true)
            }
            "version" -> events.close("This Mac app speaks a different link version.", fatal = true)
            else -> events.close("The Mac closed the link (${c.reason}).", fatal = false)
        }
    }

    /** The user typed the four digits the Mac shows. False if the session is not asking, or the code is not four digits. */
    fun submitCode(code: String): Boolean {
        if (phase != Phase.NEEDS_CODE || code.length != 4 || !code.all { it in '0'..'9' }) return false
        scheduler.sendControl(Control.Code(code))
        return true
    }

    /** Ping at the next [tick]: the network changed, and the Mac is asked to prove the connection still works. */
    fun pingNow() { nextPing = 0 }

    /** Called often by the link thread: the 2-second ping. */
    fun tick() {
        if (phase != Phase.CONNECTED) return
        val now = clock.nowMs()
        if (now >= nextPing) {
            // A second apart with a pad or the pointer open, so a quiet link shows within the watchdog's few seconds.
            nextPing = now + if (padOn || pointerWanted) PING_ACTIVE_MS else PING_MS
            scheduler.sendControl(Control.Ping(++pingId, now))
        }
    }

    /** A file chunk from the Mac (a note it sends the phone). */
    fun onFileChunk(message: ByteArray) {
        if (phase == Phase.CONNECTED) sync.receiver.onChunk(message)
    }

    /** The app's notes changed; [open] is the note on screen. */
    fun notesChanged(notes: List<NoteMeta>, open: String?) {
        openId = open
        sync.update(notes, open)
    }

    fun setViewing(id: String?) {
        if (viewing == id) return
        viewing = id
        if (phase == Phase.CONNECTED) scheduler.sendControl(Control.Viewing(id))
    }

    fun setPad(on: Boolean) {
        if (padOn == on) return
        padOn = on
        if (!on) padFrame = null
        if (phase == Phase.CONNECTED) { scheduler.sendControl(Control.Pad(on)); if (on) sendPadWish() }
    }

    /** How the pad maps and whether it wants pictures; sent while the pad is open, and again after a reconnect. */
    fun setPadWish(wish: PadWish?) {
        if (padWish == wish) return
        padWish = wish
        if (phase == Phase.CONNECTED && padOn) sendPadWish()
    }

    fun setPointer(on: Boolean) {
        if (pointerWanted == on) return
        pointerWanted = on
        if (phase == Phase.CONNECTED && pointerStatus.version == 1) scheduler.sendControl(Control.PointerMode(on))
    }

    fun pointerInput(input: PointerInput) {
        if (phase == Phase.CONNECTED && pointerWanted && pointerStatus.ready && input.valid()) {
            scheduler.sendControl(Control.Pointer(++pointerSeq, input))
        }
    }

    /** Asks the Mac for this pen frame (a finger gesture on the pad). */
    fun requestFrame(rect: CanvasRect) {
        if (phase == Phase.CONNECTED && padOn && version >= 1) scheduler.sendControl(Control.Frame(rect))
    }

    /** Read on the pad: moves the Mac's view by canvas units and multiplies its zoom. */
    fun requestScroll(dx: Double, dy: Double, zoom: Double) {
        if (phase == Phase.CONNECTED && padOn && version >= 1 && padWish?.read == true) scheduler.sendControl(Control.Scroll(dx, dy, zoom))
    }

    /** The app's wishes survive a reconnect: [viewing] and [pad] are re-sent after the next welcome. */
    fun adopt(viewing: String?, pad: Boolean, open: String?, wish: PadWish? = null, pointer: Boolean = false, frame: CanvasRect? = null) {
        this.padFrame = if (pad) frame else null
        this.pointerWanted = pointer
        this.viewing = viewing
        this.padOn = pad
        this.openId = open
        this.padWish = wish
    }

    companion object {
        const val PING_MS = 2_000L
        const val PING_ACTIVE_MS = 1_000L
    }
}
