package dev.syncr.prototype.link

import dev.syncr.core.PreviewFrame
import dev.syncr.core.PreviewSendQueue
import dev.syncr.core.StrokeCommit
import java.io.File

/** A monotonic millisecond clock. Differences only: this is what `sent_ms` and ping `at` carry. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        /** `System.nanoTime` is CLOCK_MONOTONIC on Android, the same base as `MotionEvent.eventTime`. */
        val SYSTEM = Clock { System.nanoTime() / 1_000_000 }
    }
}

/** Where the Mac listens. [macId] is known when it came from discovery (TXT `id`). */
data class Endpoint(val host: String, val port: Int, val name: String? = null, val macId: String? = null) {
    override fun toString() = "$host:$port"
}

/** What pairing leaves on the phone: the Mac's ID and name, and the token it issued. */
data class Pairing(val macId: String, val macName: String, val token: String)

/** The phone's identity and its one pairing, kept by the Android shell in app-private preferences. */
interface PairingStore {
    /** 32 hex characters, generated once per install. */
    fun phoneId(): String
    fun pairing(): Pairing?
    fun save(pairing: Pairing)
    fun forget()
}

/** The phone's notes as the sync sender sees them. Both calls block and may run on any link-side thread. */
interface NoteSource {
    /** Every non-deleted note with its `local_sequence`. */
    fun list(): List<NoteMeta>

    /** The core's `export_copy` of [id] into [dest]; the exported sequence, or null when the note is gone. */
    fun exportCopy(id: String, dest: File): Long?

    /** Link v1: operation-set digests of [ids] (absent when a note is gone). Blocking. */
    fun digests(ids: List<String>): Map<String, NoteDigest> = emptyMap()

    /** Link v1: IDs in this phone's Recently deleted. Blocking. */
    fun trash(): List<String> = emptyList()

    /**
     * Link v1: puts a verified Mac copy of note [id], staged at [file], into the library: installs it, merges it into the
     * note, or rebases the note onto it when the merge rejects operations. Blocking; the caller deletes [file] after.
     */
    fun applyCopy(id: String, file: File): CopyOutcome = CopyOutcome.Refused("not supported")

    /**
     * True while the note on screen is [id] and its editor has a change staged but not yet stored: a merge then is refused, so
     * a Mac file waits for the editor to be quiet instead of failing (remaining-v1 L.4). Cheap; any thread.
     */
    fun editing(id: String): Boolean = false

    /** Delta sync: the newest operation ID per author (author to ID) this phone holds for [id], or null when it has no such note. Blocking. */
    fun heads(id: String): Map<String, String>? = null

    /**
     * Delta sync: writes into [dest] only the operations of [id] that a peer holding [base] (author to newest ID) lacks. The
     * exported sequence, or null when a delta cannot or should not be used (unknown note, no common ground, no smaller than
     * a copy): the caller sends a whole copy then. Blocking.
     */
    fun exportDelta(id: String, base: Map<String, String>, dest: File): Long? = null

    /**
     * Delta sync: merges the verified delta [file] for note [id]. [CopyOutcome.Applied.how] is `delta` when it applied
     * cleanly and left nothing missing, `delta-short` when it did not (rejected operations, or fewer operations from some
     * author than the sender has): the caller then asks for a whole copy. Blocking; the caller deletes [file] after.
     */
    fun applyDelta(id: String, file: File): CopyOutcome = CopyOutcome.Refused("not supported")
}

/** A note's operation set: how many operations and the SHA-256 hex over their sorted IDs. */
data class NoteDigest(val digest: String, val ops: Long)

sealed interface CopyOutcome {
    /** [how] is `installed`, `merged` or `rebased`; [rejected] counts operations of the other side that did not apply. */
    data class Applied(val seq: Long, val digest: NoteDigest, val how: String, val rejected: Int = 0) : CopyOutcome
    data class Refused(val reason: String) : CopyOutcome
}

/** The part of the core's `PreviewSendQueue` the link uses, so schedulers can be tested without the native library. */
interface PreviewOut {
    fun push(frame: PreviewFrame)
    fun commit(commit: StrokeCommit)
    fun pop(): ByteArray?
    fun queuedPreviewBytes(): Long
    fun clearPreview()
}

class CorePreviewOut(private val queue: PreviewSendQueue = PreviewSendQueue()) : PreviewOut {
    override fun push(frame: PreviewFrame) = queue.pushPreview(frame)
    override fun commit(commit: StrokeCommit) = queue.pushCommit(commit)
    override fun pop(): ByteArray? = queue.pop()
    override fun queuedPreviewBytes(): Long = queue.queuedPreviewBytes().toLong()
    override fun clearPreview() = queue.clearPreview()
}

/** Work for the link thread, and for a helper thread that may block (export, hashing). */
interface LinkLoop {
    /** Runs [task] on the link thread, soon. Safe from any thread. */
    fun post(task: Runnable)

    /** Runs [task] off the link thread. */
    fun background(task: Runnable)
}

enum class Phase { IDLE, CONNECTING, NEEDS_CODE, CONNECTED }

/** What the UI shows of a session. [triesLeft] is known after a wrong code; [message] is a short reason for the last failure. */
data class SessionStatus(
    val phase: Phase = Phase.IDLE,
    val macName: String? = null,
    val triesLeft: Int? = null,
    val message: String? = null,
)

/** A rectangle on the note's canvas, in canvas units. */
data class CanvasRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right get() = x + w
    val bottom get() = y + h
}

/**
 * The Mac's active note, from `target`. [noteId] is null when there is none. The pad additions (rework-v1 §V) are null
 * from a Mac that does not send them: [frame] is the pen frame while the pad is in Local mode, [view] the canvas the
 * Mac's window shows, [pageHeight] how far down the page goes.
 */
data class StreamTarget(
    val noteId: String? = null, val sheetId: String? = null, val writable: Boolean = false, val rev: Long = 0,
    val frame: CanvasRect? = null, val view: CanvasRect? = null, val pageHeight: Double? = null,
) {
    /** Stream draws only into a writable note with a sheet. */
    val usable get() = noteId != null && sheetId != null && writable
}
