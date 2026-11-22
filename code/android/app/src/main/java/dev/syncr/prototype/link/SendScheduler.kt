package dev.syncr.prototype.link

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/** Link thread: the next message of a file transfer, in order: chunks, then `file_end`. */
interface FileSource {
    /** The next whole message, unframed, or null when the transfer has nothing more to send. */
    fun next(): ByteArray?
    val finished: Boolean
    /** Small enough to go while the pen is writing (a few typed words), behind the live frames that queue ahead of it. */
    val small: Boolean get() = false
    fun close()
}

/**
 * Reads [file] in chunks of [chunkBytes], one per [next], then yields the `file_end` control message. With [files], every
 * chunk is read ahead on that thread so the link thread never waits for the disk (remaining-v1 L.5): [next] returns null
 * until the next chunk is ready, and [wake] runs when it is. A read that fails is thrown from [next], as without [files].
 */
class FileStream(
    private val file: File,
    private val xfer: Long,
    val chunks: Long,
    private val disk: BulkDisk = BulkDisk.REAL,
    private val chunkBytes: Int = Wire.FILE_CHUNK_DATA,
    private val files: Executor? = null,
    private val wake: Runnable? = null,
) : FileSource {
    // The reading side: the link thread without [files], else the files thread.
    private var raf: RandomAccessFile? = null
    private val buffer = ByteArray(chunkBytes)
    private var readIndex = 0L

    // The link thread's side.
    private var index = 0L
    private var endSent = false

    private val lock = Any()
    private val ready = ArrayDeque<ByteArray>()
    private var reading = false
    private var failure: java.io.IOException? = null
    @Volatile private var closed = false

    override val finished get() = endSent
    override val small get() = chunks <= 1

    override fun next(): ByteArray? {
        if (endSent) return null
        if (index >= chunks) {
            endSent = true
            close()
            return Control.FileEnd(xfer).encode()
        }
        if (files == null) return read().also { index++ }
        synchronized(lock) {
            failure?.let { throw it }
            val message = ready.removeFirstOrNull()
            if (message != null) index++
            readAhead()
            return message
        }
    }

    /** Under [lock]: starts reading the next chunk unless one is being read or enough are ready. */
    private fun readAhead() {
        if (reading || closed || failure != null || readIndex >= chunks || ready.size >= AHEAD) return
        reading = true
        files!!.execute {
            val message = try { if (closed) null else read() } catch (e: java.io.IOException) { synchronized(lock) { failure = e }; null }
            synchronized(lock) {
                reading = false
                if (message != null && !closed) { ready.addLast(message); readAhead() }
            }
            wake?.run()
        }
    }

    private fun read(): ByteArray {
        val r = raf ?: RandomAccessFile(file, "r").also { raf = it }
        var n = 0
        while (n < buffer.size) {
            val got = disk.read(r, buffer, n, buffer.size - n)
            if (got < 0) break
            n += got
        }
        return Wire.fileChunk(xfer, readIndex++, buffer, n)
    }

    override fun close() {
        closed = true
        val shut = Runnable { raf?.close(); raf = null }
        // After any read in flight on the files thread, never under it.
        if (files != null) try { files.execute(shut) } catch (_: java.util.concurrent.RejectedExecutionException) { } else shut.run()
    }

    private companion object {
        /** Chunks read ahead: one on the wire while the next waits. */
        const val AHEAD = 2
    }
}

/**
 * Link thread: picks the next message to write, by wifi-link-v0 §2's priority. Control first, then preview and
 * stroke-commit frames (pumped from the pen's ring into the core's send queue, then popped), then file chunks. Called
 * once per write, so a preview that arrives while a chunk is being written waits for that one chunk and no more.
 * No chunk of a large file is started while the pen is writing or hovering ([FeedPump.penActive]) or pointer motion went out
 * within [INPUT_GRACE_NS]: the link loop looks again shortly. A small file (one chunk, a delta of typed words) goes at once,
 * still behind every control and live frame.
 */
class SendScheduler(private val out: PreviewOut, private val pump: FeedPump) {
    private data class Pending(val command: Control, val at: Long = System.nanoTime())
    private val control = ConcurrentLinkedQueue<Pending>()

    /** The transfer in flight, if any; set by the sync sender after its `file` message is queued. */
    @Volatile var file: FileSource? = null

    @Volatile var wake: Runnable? = null

    /** When the last pointer packet was queued (`System.nanoTime`); 0 for never. */
    @Volatile private var pointerAt = 0L

    /** True while pointer motion is flowing: a large transfer's next chunk waits, as it does for the pen. */
    fun pointerActive(now: Long = System.nanoTime()) = pointerAt != 0L && now - pointerAt < INPUT_GRACE_NS

    fun sendControl(c: Control) {
        if (c is Control.Pointer) pointerAt = System.nanoTime()
        if (c is Control.PointerMode && !c.on) control.removeIf { it.command is Control.Pointer }
        if (c is Control.Pointer && control.count { it.command is Control.Pointer } >= 64) {
            control.removeIf { it.command is Control.Pointer }
            control.add(Pending(Control.PointerMode(false))) // Stop rather than replay stale clicks/drags.
        } else control.add(Pending(c))
        wake?.run()
    }

    /** The next message, unframed, or null when there is nothing to send. */
    fun next(): ByteArray? {
        control.poll()?.let {
            if (it.command is Control.Pointer && System.nanoTime() - it.at > 200_000_000L) {
                control.removeIf { entry -> entry.command is Control.Pointer }
                return Control.PointerMode(false).encode()
            }
            return it.command.encode()
        }
        pump.pump()
        out.pop()?.let { return it }
        val f = file ?: return null
        if ((pump.penActive() || pointerActive()) && !f.small) return null
        val message = f.next()
        if (f.finished) file = null
        return message
    }

    fun clearControl() = control.clear()

    companion object {
        /** Bytes of a note in each chunk this phone sends: at 20 Mbit/s one takes 6.5 ms, the most a pen frame waits behind it. */
        const val CHUNK_BYTES = 16 * 1024
        const val INPUT_GRACE_NS = 400_000_000L
    }
}
