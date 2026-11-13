package dev.syncr.prototype.link

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue

/** Link thread: the next message of a file transfer, in order: chunks, then `file_end`. */
interface FileSource {
    /** The next whole message, unframed, or null when the transfer has nothing more to send. */
    fun next(): ByteArray?
    val finished: Boolean
    fun close()
}

/** Reads [file] in 65,000-byte chunks, one per [next], then yields the `file_end` control message. */
class FileStream(private val file: File, private val xfer: Long, val chunks: Long) : FileSource {
    private var raf: RandomAccessFile? = null
    private var index = 0L
    private var endSent = false
    private val buffer = ByteArray(Wire.FILE_CHUNK_DATA)

    override val finished get() = endSent

    override fun next(): ByteArray? {
        if (endSent) return null
        if (index >= chunks) {
            endSent = true
            close()
            return Control.FileEnd(xfer).encode()
        }
        val r = raf ?: RandomAccessFile(file, "r").also { raf = it }
        var n = 0
        while (n < buffer.size) {
            val got = r.read(buffer, n, buffer.size - n)
            if (got < 0) break
            n += got
        }
        return Wire.fileChunk(xfer, index++, buffer, n)
    }

    override fun close() {
        raf?.close()
        raf = null
    }
}

/**
 * Link thread: picks the next message to write, by wifi-link-v0 §2's priority. Control first, then preview and
 * stroke-commit frames (pumped from the pen's ring into the core's send queue, then popped), then file chunks. Called
 * once per write, so a preview that arrives while a chunk is being written waits for that one chunk and no more.
 * No chunk is started while the pen is writing or hovering ([FeedPump.penActive]): the link loop looks again shortly.
 */
class SendScheduler(private val out: PreviewOut, private val pump: FeedPump) {
    private data class Pending(val command: Control, val at: Long = System.nanoTime())
    private val control = ConcurrentLinkedQueue<Pending>()

    /** The transfer in flight, if any; set by the sync sender after its `file` message is queued. */
    @Volatile var file: FileSource? = null

    @Volatile var wake: Runnable? = null

    fun sendControl(c: Control) {
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
        if (pump.penActive()) return null
        val f = file ?: return null
        val message = f.next()
        if (f.finished) file = null
        return message
    }

    fun clearControl() = control.clear()
}
