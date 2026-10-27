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

/** The Mac's active note, from `target`. [noteId] is null when there is none. */
data class StreamTarget(val noteId: String? = null, val sheetId: String? = null, val writable: Boolean = false, val rev: Long = 0) {
    /** Stream draws only into a writable note with a sheet. */
    val usable get() = noteId != null && sheetId != null && writable
}
