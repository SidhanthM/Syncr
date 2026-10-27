package dev.syncr.prototype.link

import dev.syncr.core.Brush
import dev.syncr.core.CoordinateSpace
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pen thread's only contact with the link (wifi-link-v0 §4). A fixed ring of preallocated slots: the pen thread
 * writes a slot, publishes it with one volatile store and wakes the link thread; it never waits, never takes a lock and
 * allocates nothing. When the ring is full, sample batches are dropped and counted, so the receiver sees a sample-index
 * gap and drops that preview cleanly; Begin, End and Cancel have a few reserved slots so a stroke can always finish.
 *
 * One producer (the UI thread, in the note canvas or on the pad) and one consumer (the link thread, via [drain]).
 * Samples are real measured points only, never predicted ones.
 */
class PenFeed(private val clock: Clock = Clock.SYSTEM) {
    enum class Kind { BEGIN, SAMPLES, END, CANCEL, HOVER }

    /** One queued event. Fields are only valid inside [drain]'s callback. */
    class Slot {
        var kind = Kind.SAMPLES
        var noteId = ""
        var sheetId = ""
        var strokeId = ""
        var brush: Brush? = null
        var space = CoordinateSpace.CANVAS
        var revision = 0L
        var commit = false
        var hasTilt = false
        var hasOrientation = false
        var firstIndex = 0
        var count = 0
        var total = 0
        /** Event time (monotonic ms) of the last sample here: what the latency log measures from. */
        var eventMs = 0L
        var hoverX = 0f
        var hoverY = 0f
        val x = FloatArray(MAX_BATCH)
        val y = FloatArray(MAX_BATCH)
        val pressure = FloatArray(MAX_BATCH)
        val elapsedMs = IntArray(MAX_BATCH)
        val tilt = FloatArray(MAX_BATCH)
        val orientation = FloatArray(MAX_BATCH)
    }

    private val slots = Array(CAPACITY + RESERVED) { Slot() }
    private val head = AtomicInteger()
    private val tail = AtomicInteger()
    private val dropped = AtomicInteger()

    /** True while a session is welcomed; the pen side checks it once at pen-down. */
    @Volatile var enabled = false

    /** Wakes the link thread; set once by the client. */
    @Volatile var wake: Runnable? = null

    val droppedBatches: Int get() = dropped.get()

    // Producer state: only the pen thread touches these.
    private var open = false
    private var hasTilt = false
    private var hasOrientation = false
    private var sampleIndex = 0
    private var startMs = Long.MIN_VALUE
    private var current: Slot? = null

    /** True when a stroke is open on the pen side. */
    val isOpen: Boolean get() = open

    private fun acquire(reserved: Boolean): Slot? {
        val t = tail.get()
        if (t - head.get() >= if (reserved) slots.size else CAPACITY) return null
        return slots[t % slots.size]
    }

    private fun publish() {
        tail.set(tail.get() + 1)
        wake?.run()
    }

    /** Pen-down. False when the link is not up or no slot was free: this stroke is then not mirrored. */
    fun begin(
        noteId: String, sheetId: String, strokeId: String, brush: Brush, space: CoordinateSpace, revision: Long,
        commit: Boolean, hasTilt: Boolean, hasOrientation: Boolean,
    ): Boolean {
        if (!enabled) return false
        current = null
        val s = acquire(reserved = true) ?: return false
        s.kind = Kind.BEGIN
        s.noteId = noteId; s.sheetId = sheetId; s.strokeId = strokeId
        s.brush = brush; s.space = space; s.revision = revision
        s.commit = commit; s.hasTilt = hasTilt; s.hasOrientation = hasOrientation
        open = true
        this.hasTilt = hasTilt; this.hasOrientation = hasOrientation
        sampleIndex = 0
        startMs = Long.MIN_VALUE
        publish()
        return true
    }

    /** Adds one measured sample ([timeMs] is the event time, monotonic ms). Units are the stroke's coordinate space. */
    fun sample(x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float, timeMs: Long) {
        if (!open) return
        var s = current
        if (s != null && s.count == MAX_BATCH) {
            publishCurrent()
            s = null
        }
        if (s == null) {
            s = acquire(reserved = false)
            if (s == null) {
                // Counted by index only: the next published batch carries a first_index that skips this one.
                sampleIndex++
                dropped.incrementAndGet()
                return
            }
            s.kind = Kind.SAMPLES
            s.firstIndex = sampleIndex
            s.count = 0
            current = s
        }
        if (startMs == Long.MIN_VALUE) startMs = timeMs
        val n = s.count
        s.x[n] = x; s.y[n] = y; s.pressure[n] = pressure
        s.tilt[n] = tilt; s.orientation[n] = orientation
        s.elapsedMs[n] = (timeMs - startMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        s.eventMs = timeMs
        s.count = n + 1
        sampleIndex++
    }

    /** End of one input batch: everything added since the last call goes out as one frame. */
    fun flush() {
        if (current != null) publishCurrent()
    }

    private fun publishCurrent() {
        val s = current ?: return
        current = null
        // The slot was acquired at the tail and nothing else publishes in between.
        publish()
    }

    /** Pen-up. */
    fun end() {
        if (!open) return
        flush()
        open = false
        val s = acquire(reserved = true) ?: return
        s.kind = Kind.END
        s.total = sampleIndex
        publish()
    }

    /** Palm, cancel, pause, lost contact: the stroke is abandoned. */
    fun cancel() {
        if (!open) return
        current = null
        open = false
        val s = acquire(reserved = true) ?: return
        s.kind = Kind.CANCEL
        s.total = sampleIndex
        publish()
    }

    /** A hover position (Stream only). Dropped when the ring is full. */
    fun hover(noteId: String, sheetId: String, x: Float, y: Float, timeMs: Long) {
        if (!enabled) return
        val s = acquire(reserved = false) ?: return
        s.kind = Kind.HOVER
        s.noteId = noteId; s.sheetId = sheetId
        s.hoverX = x; s.hoverY = y; s.eventMs = timeMs
        publish()
    }

    /** Link thread: hands every published slot to [sink], oldest first. */
    fun drain(sink: (Slot) -> Unit) {
        var h = head.get()
        val t = tail.get()
        while (h != t) {
            sink(slots[h % slots.size])
            h++
            head.set(h)
        }
    }

    /** Link thread: forgets everything still queued (a reconnect). The pen side's open stroke is unaffected. */
    fun discard() {
        head.set(tail.get())
    }

    companion object {
        /** Samples per slot; an input batch of more goes out as several frames. */
        const val MAX_BATCH = 64
        const val CAPACITY = 128
        const val RESERVED = 8
    }
}
