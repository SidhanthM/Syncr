package dev.syncr.prototype.link

import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Mac for tests: listens on 127.0.0.1, pairs with a four-digit code, asks for every note it lacks, checks each file's size
 * and hash, and records everything else. It speaks the wire from `wifi-link-v0.md` using the same codec, and nothing of the
 * phone's session logic, so a mistake in one shows up as a disagreement with the other.
 */
class FakeMac(
    private val code: String = "4242",
    private val macName: String = "Fake Mac",
    private val macId: String = MAC_ID,
    /** The link version `welcome` says (1: the phone takes pointer input and files from the Mac). */
    private val version: Int = 0,
    /** Writes leave from a thread of their own, as the real Mac's sends are queued: a Mac sending a big file still reads. */
    private val queuedWrites: Boolean = false,
) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val endpoint get() = Endpoint("127.0.0.1", port, macName, macId)

    /** Every control message the phone sent, in order. */
    val controls = LinkedBlockingQueue<Control>()
    val previews = LinkedBlockingQueue<ByteArray>()
    val strokeChunks = LinkedBlockingQueue<ByteArray>()
    val files = ConcurrentHashMap<String, ByteArray>()
    val haveSeq = ConcurrentHashMap<String, Long>()
    val tokens: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val connections = AtomicInteger()
    val hellos = LinkedBlockingQueue<Control.Hello>()

    @Volatile var epoch = 0L
    @Volatile var corruptNextFile = false
    @Volatile var acceptPreviewFrame: (ByteArray) -> Unit = {}
    @Volatile var acceptStrokeChunk: (ByteArray) -> Unit = {}
    @Volatile var hook: (Control) -> Unit = {}
    @Volatile private var conn: Socket? = null
    private var out: BufferedOutputStream? = null
    private val lock = Any()
    private var tries = 5
    private var transfer: Triple<Control.File, java.io.ByteArrayOutputStream, IntArray>? = null

    init {
        Thread({ acceptLoop() }, "fake-mac-accept").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s = try { server.accept() } catch (_: IOException) { return }
            conn?.let { try { it.close() } catch (_: IOException) { } }
            s.tcpNoDelay = true
            conn = s
            connections.incrementAndGet()
            Thread({ serve(s) }, "fake-mac-conn").apply { isDaemon = true; start() }
        }
    }

    fun send(c: Control) = rawWrite(Wire.frame(c.encode()))

    /** Writes raw bytes, bypassing framing, to test how the phone handles garbage. */
    fun rawWrite(bytes: ByteArray) {
        if (queuedWrites) { outbox.put(bytes); return }
        write(bytes)
    }

    private fun write(bytes: ByteArray) = synchronized(lock) {
        val o = out ?: return
        try { o.write(bytes); o.flush() } catch (_: IOException) { }
    }

    private val outbox = LinkedBlockingQueue<ByteArray>()
    init {
        if (queuedWrites) Thread({ while (true) write(outbox.take()) }, "fake-mac-writer").apply { isDaemon = true; start() }
    }

    fun drop() { conn?.let { try { it.close() } catch (_: IOException) { } } }

    private fun serve(s: Socket) {
        synchronized(lock) { out = BufferedOutputStream(s.getOutputStream()) }
        tries = 5
        transfer = null
        val input = s.getInputStream()
        try {
            while (true) {
                val m = Wire.read(input)
                when (Wire.kind(m)) {
                    Wire.KIND_PREVIEW -> { previews += m; acceptPreviewFrame(m) }
                    Wire.KIND_STROKE -> { strokeChunks += m; acceptStrokeChunk(m) }
                    Wire.KIND_FILE -> onChunk(Wire.parseFileChunk(m))
                    Wire.KIND_CONTROL -> Control.parse(m)?.let { controls += it; onControl(it) }
                }
            }
        } catch (_: Exception) {
            try { s.close() } catch (_: IOException) { }
        }
    }

    private fun welcome() {
        epoch++
        send(Control.Welcome(version, macName, macId, epoch))
    }

    private fun onControl(c: Control) {
        when (c) {
            is Control.Hello -> {
                hellos += c
                if (c.token != null && c.token in tokens) welcome() else send(Control.PairRequest)
            }
            is Control.Code -> {
                if (c.code == code) {
                    val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                    tokens += token
                    send(Control.Paired(token))
                    welcome()
                } else {
                    tries--
                    send(Control.Denied(tries))
                    if (tries <= 0) drop()
                }
            }
            is Control.Ping -> send(Control.Pong(c.id, c.at, System.nanoTime() / 1_000_000))
            is Control.Notes -> {
                val want = c.notes.filter { (haveSeq[it.id] ?: -1) < it.seq }.map { it.id }
                if (want.isNotEmpty()) send(Control.Want(want))
            }
            is Control.File -> transfer = Triple(c, java.io.ByteArrayOutputStream(), intArrayOf(0))
            is Control.FileEnd -> finishTransfer(c)
            else -> Unit
        }
        hook(c)
    }

    private fun onChunk(chunk: Wire.FileChunk) {
        val (file, bytes, next) = transfer ?: return
        if (chunk.transfer != file.xfer || chunk.index != next[0].toLong()) { transfer = null; return }
        next[0]++
        bytes.write(chunk.data)
    }

    private fun finishTransfer(end: Control.FileEnd) {
        val (file, bytes, next) = transfer ?: return
        transfer = null
        if (end.xfer != file.xfer) return
        val data = bytes.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val corrupt = corruptNextFile.also { corruptNextFile = false }
        if (corrupt || data.size.toLong() != file.size || next[0].toLong() != file.chunks || sha != file.sha256) {
            send(Control.FileError(file.xfer, file.id, "hash"))
        } else {
            files[file.id] = data
            haveSeq[file.id] = file.seq
            send(Control.Have(file.id, file.seq))
        }
    }

    /** Waits for [condition], polling; false on timeout. */
    fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < until) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    override fun close() {
        try { server.close() } catch (_: IOException) { }
        drop()
    }
}
