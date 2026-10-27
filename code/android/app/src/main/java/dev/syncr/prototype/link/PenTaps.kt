package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.CoordinateSpace
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Stroke IDs allocated ahead of time on the link thread, so the pen thread can take one without calling the core
 * (`newId` is a core call). [poll] is lock-free and returns null when the pool is empty: that stroke is then not sent.
 */
class IdPool(private val source: () -> String, private val capacity: Int = 8) {
    private val ids = ConcurrentLinkedQueue<String>()

    fun poll(): String? = ids.poll()

    /** Link thread: refills the pool. */
    fun topUp() {
        while (ids.size < capacity) ids.add(source())
    }
}

/** What the note canvas calls from the pen thread (wifi-link-v0 §3.4). Real measured samples only, never predicted ones. */
interface InkMirror {
    /** Pen-down of an ink stroke. The stroke ID reserved for it, or null when this stroke is not mirrored. */
    fun begin(
        noteId: String, sheetId: String, brush: Brush, scale: Float, tx: Float, ty: Float, hasTilt: Boolean, hasOrientation: Boolean,
    ): String?

    /** One measured sample in screen pixels, with its pressure clamped as the saved stroke's is. [timeMs]: event time. */
    fun sample(screenX: Float, screenY: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long)

    /** End of one input batch: the samples added since the last flush go out as one frame. */
    fun flush()

    /** Pen-up. */
    fun end()

    /** Palm, cancel, pause: abandoned, nothing saved. */
    fun cancel()
}

/**
 * Live mirror for the note canvas: preview frames in Canvas space, with the stroke ID the stroke will be saved under.
 * The frozen canvas mapping of the pen-down turns screen pixels into canvas units, exactly as the saved samples are.
 * No stroke-commit: the note's next synced copy is the durable result.
 */
class CanvasMirror(private val feed: PenFeed, private val ids: IdPool) : InkMirror {
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    override fun begin(
        noteId: String, sheetId: String, brush: Brush, scale: Float, tx: Float, ty: Float, hasTilt: Boolean, hasOrientation: Boolean,
    ): String? {
        if (!feed.enabled) return null
        val id = ids.poll() ?: return null
        this.scale = scale; this.tx = tx; this.ty = ty
        if (!feed.begin(noteId, sheetId, id, brush, CoordinateSpace.CANVAS, 0, commit = false, hasTilt, hasOrientation)) return null
        return id
    }

    override fun sample(screenX: Float, screenY: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long) {
        feed.sample((screenX - tx) / scale, (screenY - ty) / scale, pressure.coerceIn(0f, 1f), tilt, orientation, timeMs)
    }

    override fun flush() = feed.flush()
    override fun end() = feed.end()
    override fun cancel() = feed.cancel()
}

/**
 * The Tablet Stream pen (wifi-link-v0 §3.5): pad samples, already in view-relative units, become Begin, Samples, End
 * (and a durable commit) for the note open on the Mac. With no writable target, or no connection, it sends nothing and
 * [problem] says why.
 */
class StreamPen(
    private val feed: PenFeed,
    private val ids: IdPool,
    private val target: () -> StreamTarget,
    private val brush: () -> Brush,
    private val skipped: (String) -> Unit = {},
    private val disconnectedReason: () -> String = { "link not connected" },
) {
    private var downTarget: StreamTarget? = null

    /** Why the pad cannot write right now, in a few words; null when it can. */
    fun problem(): String? {
        val t = target()
        return when {
            !feed.enabled -> "Waiting for the Mac"
            t.noteId == null -> "No note open on the Mac"
            !t.writable -> "The Mac's note is read-only"
            t.sheetId == null -> "No note open on the Mac"
            else -> null
        }
    }

    /** Pen-down. False when nothing will be sent for this stroke. */
    fun down(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long, hasTilt: Boolean, hasOrientation: Boolean): Boolean {
        if (downTarget != null) cancel()
        val t = target()
        val reason = when {
            !feed.enabled -> disconnectedReason()
            t.noteId == null || t.sheetId == null -> "no target"
            !t.writable -> "target not writable"
            else -> null
        }
        if (reason != null) { skipped(reason); return false }
        val id = ids.poll() ?: run { skipped("no stroke ID available"); return false }
        if (!feed.begin(t.noteId!!, t.sheetId!!, id, brush(), CoordinateSpace.VIEW_RELATIVE, t.rev, commit = true, hasTilt, hasOrientation)) {
            skipped("link disconnected or pen queue full"); return false
        }
        downTarget = t
        feed.sample(x, y, pressure.coerceIn(0f, 1f), tilt, orientation, timeMs)
        return true
    }

    fun move(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long) {
        if (stillWritable()) feed.sample(x, y, pressure.coerceIn(0f, 1f), tilt, orientation, timeMs)
    }

    fun flush() { if (stillWritable()) feed.flush() }
    fun up() {
        if (stillWritable()) { feed.end(); downTarget = null }
    }
    fun cancel() {
        if (downTarget != null) feed.cancel()
        downTarget = null
    }

    /** A new viewport/target or connection invalidates the whole gesture, never commits it against another mapping. */
    private fun stillWritable(): Boolean {
        val frozen = downTarget ?: return false
        if (!feed.enabled || target() != frozen) {
            skipped(if (!feed.enabled) "connection lost mid-stroke" else "target changed mid-stroke (including rev)")
            cancel()
            return false
        }
        return true
    }

    /** The pen hovers over the pad. Hover is a position only; it never writes. */
    fun hover(x: Float, y: Float, timeMs: Long) {
        if (feed.isOpen || problem() != null) return
        val t = target()
        feed.hover(t.noteId ?: return, t.sheetId ?: return, x, y, timeMs)
    }
}
