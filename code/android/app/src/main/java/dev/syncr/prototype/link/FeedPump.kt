package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.CoreException
import dev.syncr.core.CoordinateSpace
import dev.syncr.core.PreviewBody
import dev.syncr.core.PreviewFrame
import dev.syncr.core.SampleChannels
import dev.syncr.core.StrokeCommit

/**
 * Link thread: turns [PenFeed] slots into preview frames and stroke commits and queues them on the core's send queue.
 * One frame per input batch, in the order they were drawn; the send queue pops them for the socket.
 *
 * Mirror strokes (Canvas space) are preview only. Stream strokes ([PenFeed.Slot.commit]) also keep their samples so
 * the complete stroke goes out as a durable stroke-commit at pen-up; a stroke that lost a batch to a full ring is
 * cancelled instead, never committed short.
 */
class FeedPump(
    private val feed: PenFeed,
    private val out: PreviewOut,
    private val clock: Clock,
    private val encodeSamples: (SampleChannels) -> ByteArray,
    private val log: (String) -> Unit = {},
) {
    /** The session's epoch (from `welcome`); goes in every frame. */
    var epoch = 0L

    private class Stroke(
        val noteId: String, val sheetId: String, val strokeId: String, val brush: Brush, val space: CoordinateSpace,
        val commit: Boolean, val hasTilt: Boolean, val hasOrientation: Boolean,
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

    /** A new connection: nothing from the old one carries over. */
    fun reset() {
        feed.discard()
        stroke?.takeIf { it.commit }?.let { log("stream stroke cancel id=${it.strokeId.take(8)} samples=${it.received} reason=connection reset") }
        stroke = null
        retryCommits.clear()
        hoverSequence = 0
        pending.clear()
        out.clearPreview()
        // Stream is session-only. An old connection's unsent commit must not land after reconnect.
        while (out.pop() != null) { }
    }

    /** Called after each preview frame is written; returns microseconds from pen event to this write, if it can tell. */
    fun onFrameWritten(message: ByteArray): Long? {
        if (Wire.kind(message) != Wire.KIND_PREVIEW || message.size < 3 || message[2].toInt() != BODY_SAMPLES) return null
        val event = pending.removeFirstOrNull() ?: return null
        if (out.queuedPreviewBytes() == 0L) pending.clear() // frames may have merged or dropped: don't drift
        return (clock.nowMs() - event) * 1000
    }

    private fun handle(slot: PenFeed.Slot) {
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
        val s = Stroke(slot.noteId, slot.sheetId, slot.strokeId, brush, slot.space, slot.commit, slot.hasTilt, slot.hasOrientation)
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
        pending.addLast(slot.eventMs)
        if (pending.size > MAX_PENDING) pending.removeFirst()
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
            queueCommit(commit)
        } catch (e: CoreException) {
            log("stroke commit not built: ${e.javaClass.simpleName}")
        }
    }

    private fun queueCommit(commit: StrokeCommit) {
        try {
            out.commit(commit)
            commitsQueued++
            log("stream stroke commit queued id=${commit.strokeId.take(8)}")
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
        frame(slot.noteId, slot.sheetId, null, hoverSequence++, PreviewBody.Hover(slot.hoverX, slot.hoverY))
    }

    private fun push(s: Stroke, body: PreviewBody): Boolean {
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
    }
}
