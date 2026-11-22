package dev.syncr.prototype.link

import java.io.File
import java.security.MessageDigest

/**
 * Phone to Mac note sync (wifi-link-v0 §3.3). Link thread only, except [LinkLoop.background] blocks.
 *
 * - After `welcome`: every non-deleted note, as `notes` messages.
 * - A changed note is told to the Mac at once (latency-v1 T.3). A Mac that takes pushes and is known to hold everything
 *   older gets just the new operations as a delta nobody asked for; any other Mac gets a `notes` announcement and asks.
 * - On `want`: `export_copy` into [tempDir] off the link thread, SHA-256, then `file`, chunks, `file_end`, one transfer at
 *   a time; the temp file is deleted once sent. `have` completes it; `file_error` retries once per connection.
 */
class SyncSender(
    private val source: NoteSource,
    private val scheduler: SendScheduler,
    private val loop: LinkLoop,
    private val clock: Clock,
    private val tempDir: File,
    private val log: (String) -> Unit = {},
    /** Told when the Mac confirms a note with `have`; the probe and tests watch it. */
    private val onDelivered: (id: String, seq: Long) -> Unit = { _, _ -> },
    /** Told when the Mac asks for notes. */
    private val onWanted: (List<String>) -> Unit = {},
    /** Told when a transfer starts: the note, the bytes of its file and whether it is a delta. */
    private val onSent: (id: String, bytes: Long, delta: Boolean) -> Unit = { _, _, _ -> },
    private val disk: BulkDisk = BulkDisk.REAL,
    /** Where transfer files are read and written, off the link thread; null does it on the caller's thread (tests). */
    private val files: java.util.concurrent.Executor? = null,
) {
    private class Transfer(val xfer: Long, val id: String, val seq: Long, val file: File, val stream: FileStream, val delta: Boolean = false,
        /** What the phone held when it started to export this (never newer than the file), and whether the Mac did not ask for it. */
        val heads: Map<String, String> = emptyMap(), val pushed: Boolean = false) {
        var sentAt = 0L
    }

    /** Mac to phone sync; the session routes the Mac's `notes`, `file`, chunks and `file_end` to it. */
    val receiver = SyncReceiver(source, scheduler, loop, clock, tempDir, log, disk = disk, files = files)

    private val known = LinkedHashMap<String, NoteMeta>()
    private val announced = HashMap<String, Long>()
    private val wanted = ArrayDeque<String>()
    private val retried = HashSet<String>()
    private var openId: String? = null
    private var connected = false
    private var preparing = false
    private var active: Transfer? = null
    private var nextXfer = 1L
    private var generation = 0
    /** The Mac speaks link v1: announcements carry digests and the phone's trash. */
    private var v1 = false
    /** The Mac understands deltas: its `want` may carry what it already has, and a `file` may be a delta. */
    private var delta = false
    /** The Mac takes a delta nobody asked for. */
    private var push = false
    /**
     * What the Mac is known to hold, per note: the newest operation per author. Learned from a `want`'s base and, once the Mac
     * confirms a transfer with `have`, from what the phone held when it exported (the Mac then holds all of it); dropped at any
     * refusal. A wrong one only makes a push fall short.
     */
    private val peerHeads = HashMap<String, Map<String, String>>()
    /** Notes whose next transfer is a push, and notes to push once the transfer in flight is confirmed. */
    private val pushing = HashSet<String>()
    private val pushLater = HashSet<String>()
    /** What the Mac said it holds, per wanted note; used once, so a retry always sends a whole copy. */
    private val bases = HashMap<String, Map<String, String>>()

    /** Transfers the Mac confirmed with `have`, for tests and the probe. */
    val delivered = ArrayList<Pair<String, Long>>()

    val busy get() = preparing || active != null

    /** After `welcome`: a clean slate for this connection, then the whole manifest. */
    fun onWelcome(notes: List<NoteMeta>, open: String?, version: Int = 0, delta: Boolean = false, push: Boolean = false) {
        reset()
        connected = true
        v1 = version >= 1
        this.delta = v1 && delta
        this.push = this.delta && push
        generation++
        receiver.onWelcome(v1, this.delta)
        known.clear()
        notes.forEach { known[it.id] = it }
        openId = open
        sendNotes(notes, first = true)
        notes.forEach { announced[it.id] = it.seq }
    }

    /** The connection ended: abandon the transfer and its temp file. */
    fun onClosed() {
        connected = false
        generation++
        reset()
        receiver.onClosed()
    }

    private fun reset() {
        active?.let { discard(it) }
        active = null
        scheduler.file?.close()
        scheduler.file = null
        preparing = false
        wanted.clear()
        bases.clear()
        peerHeads.clear(); pushing.clear(); pushLater.clear()
        announced.clear()
        retried.clear()
    }

    /** The phone's notes changed. [open] is the note on screen. Every changed note goes out at once. */
    fun update(notes: List<NoteMeta>, open: String?) {
        openId = open
        known.clear()
        notes.forEach { known[it.id] = it }
        if (!connected) return
        announced.keys.retainAll(known.keys)
        // A list that still carries an older sequence (the library lags a save behind) says nothing new.
        announce(notes.filter { n -> announced[n.id]?.let { n.seq > it } ?: true })
    }

    /** Called often: sends notices whose quiet period is over and watches the transfer. */
    fun tick() {
        receiver.tick()
        val now = clock.nowMs()
        active?.let { t ->
            if (t.sentAt == 0L && t.stream.finished) {
                t.sentAt = now
                discard(t, keepRecord = true)
            } else if (t.sentAt != 0L && now - t.sentAt > ACK_TIMEOUT_MS) {
                log("no answer for transfer ${t.xfer} of ${t.id}; moving on")
                active = null
                startNext()
            }
        }
    }

    private fun announce(notes: List<NoteMeta>) {
        if (notes.isEmpty()) return
        val rest = notes.filterNot(::pushNote)
        notes.forEach { announced[it.id] = it.seq }
        sendNotes(rest)
    }

    /**
     * Sends [n]'s new operations unasked when the Mac is known to hold everything older (latency-v1 T.3), as if it had asked.
     * False when it cannot be pushed: the note is announced and the Mac asks.
     */
    private fun pushNote(n: NoteMeta): Boolean {
        if (!push || !connected || n.id !in known) return false
        val heads = peerHeads[n.id] ?: return false
        if (active?.id == n.id) { pushLater += n.id; return true }
        if (n.id !in wanted) wanted += n.id
        bases.putIfAbsent(n.id, heads)
        pushing += n.id
        startNext()
        return true
    }

    /**
     * Link v1: digests (and, on the first message of a connection, the phone's trash) are read off the link thread, then the
     * announcement goes out in the order it was asked for. A link v0 Mac gets the plain manifest of wifi-link-v0.
     */
    private fun sendNotes(notes: List<NoteMeta>, first: Boolean = false) {
        if (!v1) return sendBatches(notes)
        val mine = generation
        loop.background {
            val digests = try { source.digests(notes.map { it.id }) } catch (e: Exception) { log("digest read failed: ${e.javaClass.simpleName}"); emptyMap() }
            val trash = if (first) try { source.trash() } catch (e: Exception) { log("trash read failed: ${e.javaClass.simpleName}"); emptyList() } else null
            loop.post {
                if (mine != generation || !connected) return@post
                // The trash goes first and alone, so a long list never competes with the notes for the message limit.
                if (trash != null) scheduler.sendControl(Control.Notes(emptyList(), trash.take(MAX_TRASH)))
                sendBatches(notes.map { n -> digests[n.id]?.let { n.copy(digest = it.digest, ops = it.ops) } ?: n })
            }
        }
    }

    /** Titles are for display; long ones are cut so a manifest of any size splits into frames that fit. */
    private fun sendBatches(notes: List<NoteMeta>) {
        var batch = ArrayList<NoteMeta>()
        var size = 0
        for (n in notes) {
            val trimmed = if (n.title.length > MAX_TITLE) n.copy(title = n.title.take(MAX_TITLE)) else n
            val cost = 160 + trimmed.id.length + trimmed.title.length * 6 // worst-case JSON escaping, plus a digest
            if (batch.isNotEmpty() && size + cost > BATCH_BYTES) {
                scheduler.sendControl(Control.Notes(batch))
                batch = ArrayList(); size = 0
            }
            batch += trimmed
            size += cost
        }
        if (batch.isNotEmpty()) scheduler.sendControl(Control.Notes(batch))
    }

    fun onWant(ids: List<String>, base: Map<String, Map<String, String>>? = null) {
        onWanted(ids)
        for (id in ids) {
            if (id in known && id !in wanted && active?.id != id) wanted += id
            if (delta) base?.get(id)?.let { bases[id] = it; peerHeads[id] = it }
        }
        startNext()
    }

    fun onHave(id: String, seq: Long, digest: String? = null) {
        delivered += id to seq
        onDelivered(id, seq)
        active?.takeIf { it.id == id }?.let { t ->
            // The Mac applied the whole file, so it holds everything the phone held when the export began.
            if (t.heads.isNotEmpty()) peerHeads[id] = t.heads
            discard(t)
            active = null
        }
        if (pushLater.remove(id)) known[id]?.let { pushNote(it) }
        startNext()
    }

    fun onFileError(xfer: Long, id: String, reason: String) {
        val t = active
        if (t == null || t.xfer != xfer) return
        discard(t)
        active = null
        scheduler.file = null
        peerHeads.remove(id)
        // A push the Mac never asked for was refused: it has not heard the note changed, so tell it the ordinary way.
        if (t.pushed) known[id]?.let { sendNotes(listOf(it)) }
        // The receiver is not refusing: it holds the delta and is asking for the whole note, so only free the slot.
        if (reason.startsWith(DELTA_SHORT)) return startNext()
        if (retried.add(id)) {
            log("transfer ${t.xfer} of $id refused ($reason); retrying once")
            wanted.addFirst(id)
        } else {
            log("transfer of $id refused again ($reason); giving up until it changes")
        }
        startNext()
    }

    private fun startNext() {
        if (!connected || preparing || active != null) return
        val id = wanted.removeFirstOrNull() ?: return
        if (id !in known) return startNext()
        preparing = true
        val xfer = nextXfer++
        val mine = generation
        val base = bases.remove(id)
        val pushed = pushing.remove(id)
        loop.background {
            var file: File? = null
            var result: Prepared? = null
            var heads: Map<String, String> = emptyMap()
            try {
                tempDir.mkdirs()
                file = File(tempDir, "xfer-$xfer.syncr")
                // Only what the Mac lacks, when it said what it has and a delta is worth it; else the whole note.
                // Read first: a save during the export makes this older than the file, which is safe, never newer.
                if (push) heads = try { source.heads(id).orEmpty() } catch (e: Exception) { emptyMap() }
                var asDelta = false
                var seq = base?.let { source.exportDelta(id, it, file) }
                if (seq != null && file.isFile) asDelta = true else { file.delete(); seq = source.exportCopy(id, file) }
                if (seq != null && file.isFile) result = Prepared(seq, file.length(), sha256(file), asDelta, heads, pushed)
            } catch (e: Exception) {
                log("export of $id failed: ${e.javaClass.simpleName}")
            }
            val done = file
            loop.post { onPrepared(mine, id, xfer, done, result) }
        }
    }

    private class Prepared(val seq: Long, val size: Long, val sha256: String, val delta: Boolean, val heads: Map<String, String>, val pushed: Boolean = false)

    private fun onPrepared(mine: Int, id: String, xfer: Long, file: File?, result: Prepared?) {
        if (mine != generation || !connected) {
            file?.delete()
            return
        }
        preparing = false
        if (result == null || file == null || result.size > Wire.MAX_FILE_BYTES) {
            file?.delete()
            if (result != null) log("note $id is ${result.size} bytes, over the limit; not sent")
            return startNext()
        }
        // Small chunks: a pen frame or pointer packet that comes up mid-transfer waits behind one at most (remaining-v1 L.5).
        val chunks = (result.size + SendScheduler.CHUNK_BYTES - 1) / SendScheduler.CHUNK_BYTES
        val stream = FileStream(file, xfer, chunks, disk, SendScheduler.CHUNK_BYTES, files, scheduler.wake)
        scheduler.sendControl(Control.File(xfer, id, result.seq, result.size, result.sha256, chunks, result.delta))
        scheduler.file = stream
        active = Transfer(xfer, id, result.seq, file, stream, result.delta, result.heads, result.pushed)
        onSent(id, result.size, result.delta)
    }

    private fun discard(t: Transfer, keepRecord: Boolean = false) {
        t.stream.close()
        t.file.delete()
        if (!keepRecord && scheduler.file === t.stream) scheduler.file = null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** `file_error` reason: a delta fell short; the sender is released and the whole note is asked for separately. */
        const val DELTA_SHORT = "delta short"
        const val ACK_TIMEOUT_MS = 30_000L
        private const val MAX_TITLE = 200
        private const val BATCH_BYTES = 48_000
        private const val MAX_TRASH = 1_500
    }
}
