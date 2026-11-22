package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.CoreException
import dev.syncr.core.CoordinateSpace
import dev.syncr.core.PreviewBody
import dev.syncr.core.PreviewFrame
import dev.syncr.core.SampleChannels
import dev.syncr.core.StrokeCommit

/**
 * The pad strokes the phone keeps until the Mac confirms them by ID (link K.4), so a drop of the connection never loses one and a
 * resend never doubles one. In memory only, and so lost if the app is killed; bounded to [maxStrokes] strokes or [maxBytes] bytes
 * of samples, the oldest going first. One lives as long as the app, across the clients that come and go with the foreground.
 */
class StrokeKeeper(private val maxStrokes: Int = 200, private val maxBytes: Int = 4 * 1024 * 1024) {
    class Kept(val commit: StrokeCommit, val rev: Long, val viewRelative: Boolean) {
        val bytes: Int get() = commit.samples.size
        /** The connection (a counter) this stroke was last queued on; 0 when never. Link thread only. */
        var sentConn = 0
    }

    private val strokes = LinkedHashMap<String, Kept>()
    private var held = 0

    /** True once a Mac has said it confirms strokes by ID; stays so until a Mac says it does not. */
    @Volatile var peerKeeps = false

    private val connections = java.util.concurrent.atomic.AtomicInteger()

    /** A number for the next connection, never used before in this app run (a client made later must not look like an old one). */
    fun nextConnection(): Int = connections.incrementAndGet()

    /** Adds [stroke]; returns the IDs of older strokes dropped to stay within the bounds. */
    @Synchronized fun add(stroke: Kept): List<String> {
        strokes.put(stroke.commit.strokeId, stroke)?.let { held -= it.bytes }
        held += stroke.bytes
        val dropped = ArrayList<String>()
        while (strokes.size > maxStrokes || held > maxBytes) {
            val oldest = strokes.entries.first()
            if (oldest.key == stroke.commit.strokeId) break
            held -= oldest.value.bytes
            strokes.remove(oldest.key)
            dropped += oldest.key
        }
        return dropped
    }

    /** The Mac has the stroke (or refused it): let go. True when it was kept. */
    @Synchronized fun ack(id: String): Boolean = strokes.remove(id)?.also { held -= it.bytes } != null

    /** Oldest first. */
    @Synchronized fun pending(): List<Kept> = strokes.values.toList()

    @get:Synchronized val size: Int get() = strokes.size
    @get:Synchronized val bytes: Int get() = held
}

/**
 * Link thread: turns [PenFeed] slots into preview frames and stroke commits and queues them on the core's send queue.
 * One frame per input batch, in the order they were drawn; the send queue pops them for the socket.
 *
 * Mirror strokes (Canvas space) are preview only. Stream strokes ([PenFeed.Slot.commit]) also keep their samples so
 * the complete stroke goes out as a durable stroke-commit at pen-up; a stroke that lost a batch to a full ring is
 * cancelled instead, never committed short.
 *
 * With a Mac that confirms strokes ([StrokeKeeper.peerKeeps]) a finished Stream stroke is kept until `stroke_ack`, sent again on
 * every new connection until then, and a stroke still being drawn when the connection drops goes on collecting samples (the pen
 * ring is drained even with no connection) and is committed when it ends; it never gets preview frames on a connection it did
 * not begin on.
 */
class FeedPump(
    private val feed: PenFeed,
    private val out: PreviewOut,
    private val clock: Clock,
    private val encodeSamples: (SampleChannels) -> ByteArray,
    private val log: (String) -> Unit = {},
    val keeper: StrokeKeeper = StrokeKeeper(),
) {
    /** The session's epoch (from `welcome`); goes in every frame. */
    var epoch = 0L

    /** Where control messages for the current connection go (the scheduler's); set by the client for each connection. */
    var control: ((Control) -> Unit)? = null
    private var online = false
    private var conn = 0

    /** The Mac confirms strokes by ID, so strokes are kept and outlive a connection. */
    val keeping: Boolean get() = keeper.peerKeeps

    private class Stroke(
        val noteId: String, val sheetId: String, val strokeId: String, val brush: Brush, val space: CoordinateSpace,
        val commit: Boolean, val hasTilt: Boolean, val hasOrientation: Boolean,
        /** The target revision it began at (Stream); the Mac places a stroke sent again by it. */
        val rev: Long = 0,
        /** The connection it began on; 0 when it began with none. Its preview frames go to that connection only. */
        val conn: Int = 0,
    ) {
        var sequence = 0L
        var received = 0
        var dead = false
        var lost = false
        val x = ArrayList<Float>(); val y = ArrayList<Float>(); val pressure = ArrayList<Float>()
        val elapsed = ArrayList<UInt>(); val tilt = ArrayList<Float>(); val orientation = ArrayList<Float>()
    }

    private var stroke: Stroke? = null
    private var hoverSequence = 0L
    private var lastPenMs = Long.MIN_VALUE

    /**
     * True while the pen wrote or hovered within [graceMs] (longer inside an open stroke: a pen held still sends
     * nothing). File chunks wait meanwhile: they share the socket, so whatever is already in its send buffer goes out
     * ahead of the next pen frame.
     */
    fun penActive(graceMs: Long = PEN_GRACE_MS): Boolean =
        lastPenMs != Long.MIN_VALUE && clock.nowMs() - lastPenMs < if (stroke != null) OPEN_STROKE_MS else graceMs
    private val retryCommits = ArrayDeque<StrokeCommit>()

    /** Event times of preview frames pushed and not yet written, for pen-to-write latency. */
    private val pending = ArrayDeque<Long>()

    /** Strokes this pump has finished, for tests and the probe. */
    var strokesEnded = 0
        private set
    var commitsQueued = 0
        private set

    /** Preview frames handed to the send queue (volatile: read by the probe from another thread). */
    @Volatile var framesQueued = 0
        private set

    fun pump() {
        retryCommits()
        feed.drain(::handle)
    }

    /**
     * A connection begins or ends: nothing sent on the old one carries over, and nothing is sent until [goOnline]. Without stroke
     * keeping, a stroke in flight is cancelled and the pen ring emptied. With it they stay: the strokes belong to the pen, and
     * a stroke kept but not confirmed is sent again on the next connection.
     */
    fun reset() {
        online = false
        hoverSequence = 0
        pending.clear()
        retryCommits.clear()
        out.clearPreview()
        // An old connection's unsent commit must not land after reconnect (a kept stroke is sent again at the next welcome).
        while (out.pop() != null) { }
        if (keeping) return
        feed.discard()
        stroke?.takeIf { it.commit }?.let { log("stream stroke cancel id=${it.strokeId.take(8)} samples=${it.received} reason=connection reset") }
        stroke = null
    }

    /**
     * The session reached `welcome` ([keep]: the Mac confirms strokes by ID). Frames and commits go to the Mac from now. With
     * keeping, every stroke the Mac has not confirmed goes again: a view-relative one is preceded by `stroke_rev`, so it is placed
     * by the revision it began at, and the Mac saves a given stroke ID once.
     */
    fun goOnline(keep: Boolean) {
        keeper.peerKeeps = keep
        online = true
        conn = keeper.nextConnection()
        if (!keep) return
        for (k in keeper.pending()) sendKept(k)
    }

    private fun sendKept(k: StrokeKeeper.Kept) {
        if (k.sentConn == conn) return
        if (k.viewRelative) control?.invoke(Control.StrokeRev(k.commit.strokeId, k.rev))
        k.sentConn = conn
        queueCommit(k.commit, again = true)
    }

    /** The Mac has this stroke or refused it for good: let go of it. */
    fun onAck(id: String, ok: Boolean) {
        if (keeper.ack(id)) log("stream stroke ${if (ok) "confirmed" else "refused by the Mac"} id=${id.take(8)} kept=${keeper.size}")
    }

    /** Called after each preview frame is written; returns microseconds from pen event to this write, if it can tell. */
    fun onFrameWritten(message: ByteArray): Long? {
        if (Wire.kind(message) != Wire.KIND_PREVIEW || message.size < 3 || message[2].toInt() != BODY_SAMPLES) return null
        val event = pending.removeFirstOrNull() ?: return null
        if (out.queuedPreviewBytes() == 0L) pending.clear() // frames may have merged or dropped: don't drift
        return (clock.nowMs() - event) * 1000
    }

    private fun handle(slot: PenFeed.Slot) {
        lastPenMs = clock.nowMs()
        when (slot.kind) {
            PenFeed.Kind.BEGIN -> begin(slot)
            PenFeed.Kind.SAMPLES -> samples(slot)
            PenFeed.Kind.END -> end(slot)
            PenFeed.Kind.CANCEL -> cancel(slot)
            PenFeed.Kind.HOVER -> hover(slot)
        }
    }

    private fun begin(slot: PenFeed.Slot) {
        val brush = slot.brush ?: return
        // A mirror stroke (preview only) has nothing to keep: with no connection it is simply not drawn on the Mac.
        val s = Stroke(slot.noteId, slot.sheetId, slot.strokeId, brush, slot.space, slot.commit, slot.hasTilt, slot.hasOrientation,
            rev = slot.revision, conn = if (online) conn else 0)
        if (keeping && !online && !slot.commit) s.dead = true
        stroke = s
        if (s.commit) log("stream stroke begin id=${s.strokeId.take(8)} note=${s.noteId.take(8)} sheet=${s.sheetId.take(8)} rev=${slot.revision}")
        push(s, PreviewBody.Begin(brush, slot.revision.toUInt(), slot.space))
    }

    private fun samples(slot: PenFeed.Slot) {
        val s = stroke ?: return
        if (s.dead) return
        if (slot.firstIndex != s.received) s.lost = true
        val n = slot.count
        val channels = SampleChannels(
            slot.x.slice(0 until n), slot.y.slice(0 until n), slot.pressure.slice(0 until n),
            (0 until n).map { slot.elapsedMs[it].toUInt() },
            if (s.hasTilt) slot.tilt.slice(0 until n) else null,
            if (s.hasOrientation) slot.orientation.slice(0 until n) else null,
        )
        if (!push(s, PreviewBody.Samples(slot.firstIndex.toUInt(), channels))) return
        if (live(s)) {
            pending.addLast(slot.eventMs)
            if (pending.size > MAX_PENDING) pending.removeFirst()
        }
        if (s.commit && !s.lost) {
            s.x += channels.x; s.y += channels.y; s.pressure += channels.pressure; s.elapsed += channels.elapsedMs
            channels.tilt?.let { s.tilt += it }
            channels.orientation?.let { s.orientation += it }
        }
        s.received = slot.firstIndex + n
    }

    private fun end(slot: PenFeed.Slot) {
        val s = stroke ?: return
        stroke = null
        if (s.dead) return
        if (slot.total != s.received) s.lost = true // the tail was dropped: no gap shows in first_index
        if (s.commit && s.lost) {
            // A durable stroke must be whole. Say so on the preview and save nothing.
            log("stream stroke cancel id=${s.strokeId.take(8)} samples=${slot.total} reason=pen queue lost a batch; not committed")
            push(s, PreviewBody.Cancel)
            return
        }
        if (!push(s, PreviewBody.End(slot.total.toUInt()))) return
        strokesEnded++
        if (s.commit) log("stream stroke end id=${s.strokeId.take(8)} samples=${slot.total}")
        if (s.commit && s.x.isNotEmpty()) commit(s)
    }

    private fun commit(s: Stroke) {
        try {
            val channels = SampleChannels(
                s.x, s.y, s.pressure, s.elapsed, if (s.hasTilt) s.tilt else null, if (s.hasOrientation) s.orientation else null,
            )
            val commit = StrokeCommit(s.noteId, s.sheetId, s.strokeId, s.brush, s.space, encodeSamples(channels))
            if (keeping) {
                val k = StrokeKeeper.Kept(commit, s.rev, s.space == CoordinateSpace.VIEW_RELATIVE)
                for (id in keeper.add(k)) log("kept stroke dropped id=${id.take(8)} reason=more than ${keeper.size} kept or over the byte bound")
                if (online) sendKept(k) else log("stream stroke kept id=${s.strokeId.take(8)} reason=no connection; it is sent when the link is back")
            } else queueCommit(commit)
        } catch (e: CoreException) {
            log("stroke commit not built: ${e.javaClass.simpleName}")
        }
    }

    private fun queueCommit(commit: StrokeCommit, again: Boolean = false) {
        try {
            out.commit(commit)
            commitsQueued++
            log("stream stroke commit queued id=${commit.strokeId.take(8)}${if (again) " again=true" else ""}")
        } catch (e: CoreException.ResourceLimit) {
            if (e.retryable) {
                retryCommits += commit // the durable backlog is full: try again after the socket drains
                log("stream stroke commit waiting id=${commit.strokeId.take(8)} reason=durable queue full")
            }
            else log("stroke commit too large; not sent")
        } catch (e: CoreException) {
            log("stroke commit refused: ${e.javaClass.simpleName}")
        }
    }

    private fun retryCommits() {
        var n = retryCommits.size
        while (n-- > 0) {
            val commit = retryCommits.removeFirst()
            try {
                out.commit(commit)
                commitsQueued++
                log("stream stroke commit queued id=${commit.strokeId.take(8)}")
            } catch (e: CoreException.ResourceLimit) {
                if (e.retryable) { retryCommits.addFirst(commit); return }
                log("stroke commit too large; not sent")
            } catch (e: CoreException) {
                log("stroke commit refused: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun cancel(slot: PenFeed.Slot) {
        val s = stroke ?: return
        stroke = null
        if (s.commit) log("stream stroke cancel id=${s.strokeId.take(8)} samples=${slot.total} reason=input cancelled; not committed")
        if (!s.dead) push(s, PreviewBody.Cancel)
    }

    private fun hover(slot: PenFeed.Slot) {
        if (keeping && !online) return
        frame(slot.noteId, slot.sheetId, null, hoverSequence++, PreviewBody.Hover(slot.hoverX, slot.hoverY))
    }

    /**
     * Preview frames go only on the connection the stroke began on. A Mac that does not confirm strokes gets today's behaviour: a
     * connection that ends cancels what was in flight, so there is no stroke from an older connection to tell apart.
     */
    private fun live(s: Stroke) = !keeping || (online && s.conn == conn)

    private fun push(s: Stroke, body: PreviewBody): Boolean {
        if (!live(s)) return true
        val ok = frame(s.noteId, s.sheetId, s.strokeId, s.sequence, body)
        if (!ok) {
            // The core refused (bad value); abandon the stroke rather than send a broken one.
            s.dead = true
            if (s.commit) log("stream stroke not sent id=${s.strokeId.take(8)} reason=preview refused")
            if (body !is PreviewBody.Cancel) frame(s.noteId, s.sheetId, s.strokeId, s.sequence + 1, PreviewBody.Cancel)
        }
        s.sequence++
        return ok
    }

    private fun frame(noteId: String, sheetId: String, strokeId: String?, sequence: Long, body: PreviewBody): Boolean = try {
        out.push(PreviewFrame(epoch.toUInt(), noteId, sheetId, strokeId, sequence.toUInt(), clock.nowMs().toULong(), body))
        framesQueued++
        true
    } catch (e: CoreException) {
        log("preview frame refused: ${e.javaClass.simpleName}")
        false
    }

    private companion object {
        const val BODY_SAMPLES = 2
        const val MAX_PENDING = 512
        const val PEN_GRACE_MS = 400L
        const val OPEN_STROKE_MS = 3_000L
    }
}
