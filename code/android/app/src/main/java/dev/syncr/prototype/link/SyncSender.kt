package dev.syncr.prototype.link

import java.io.File
import java.security.MessageDigest

/**
 * Phone to Mac note sync (wifi-link-v0 §3.3). Link thread only, except [LinkLoop.background] blocks.
 *
 * - After `welcome`: every non-deleted note, as `notes` messages.
 * - A changed note is announced at most 400 ms after its first unannounced save while it is open (later saves in
 *   that window ride along, so continuous writing or erasing still reaches the Mac), and at once when it is closed.
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
) {
    private class Transfer(val xfer: Long, val id: String, val seq: Long, val file: File, val stream: FileStream, val delta: Boolean = false) {
        var sentAt = 0L
    }

    /** Mac to phone sync; the session routes the Mac's `notes`, `file`, chunks and `file_end` to it. */
    val receiver = SyncReceiver(source, scheduler, loop, clock, tempDir, log)

    private val known = LinkedHashMap<String, NoteMeta>()
    private val announced = HashMap<String, Long>()
    private class Due(val at: Long, val meta: NoteMeta)
    private val due = HashMap<String, Due>()
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
    /** What the Mac said it holds, per wanted note; used once, so a retry always sends a whole copy. */
    private val bases = HashMap<String, Map<String, String>>()

    /** Transfers the Mac confirmed with `have`, for tests and the probe. */
    val delivered = ArrayList<Pair<String, Long>>()

    val busy get() = preparing || active != null

    /** After `welcome`: a clean slate for this connection, then the whole manifest. */
    fun onWelcome(notes: List<NoteMeta>, open: String?, version: Int = 0, delta: Boolean = false) {
        reset()
        connected = true
        v1 = version >= 1
        this.delta = v1 && delta
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
        due.clear()
        announced.clear()
        retried.clear()
    }

    /** The phone's notes changed. [open] is the note on screen, whose seq goes out within [ANNOUNCE_MS] of its first change. */
    fun update(notes: List<NoteMeta>, open: String?) {
        val now = clock.nowMs()
        val closed = openId?.takeIf { it != open }
        openId = open
        known.clear()
        notes.forEach { known[it.id] = it }
        if (!connected) return
        announced.keys.retainAll(known.keys)
        val immediate = ArrayList<NoteMeta>()
        // A note just closed goes out at once, with the values it had when it closed (the library list may lag).
        val flushed = closed?.let { due.remove(it) }
        for (n in notes) {
            if (flushed != null && n.id == flushed.meta.id) continue
            if (announced[n.id] == n.seq) {
                due.remove(n.id)
            } else if (n.id == open) {
                // Keep the first deadline: a stream of saves must not push the Mac's copy back indefinitely.
                due[n.id] = Due(due[n.id]?.at ?: (now + ANNOUNCE_MS), n)
            } else {
                due.remove(n.id)
                immediate += n
            }
        }
        flushed?.takeIf { it.meta.id in known }?.let { immediate += it.meta }
        due.keys.retainAll(known.keys)
        announce(immediate)
    }

    /** Called often: sends notices whose quiet period is over and watches the transfer. */
    fun tick() {
        receiver.tick()
        val now = clock.nowMs()
        if (due.isNotEmpty()) {
            val ready = due.values.filter { it.at <= now }.map { it.meta }
            ready.forEach { due.remove(it.id) }
            announce(ready)
        }
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
        sendNotes(notes)
        notes.forEach { announced[it.id] = it.seq }
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
            if (delta) base?.get(id)?.let { bases[id] = it }
        }
        startNext()
    }

    fun onHave(id: String, seq: Long) {
        delivered += id to seq
        onDelivered(id, seq)
        if (active?.id == id) {
            active?.let { discard(it) }
            active = null
        }
        startNext()
    }

    fun onFileError(xfer: Long, id: String, reason: String) {
        val t = active
        if (t == null || t.xfer != xfer) return
        discard(t)
        active = null
        scheduler.file = null
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
        loop.background {
            var file: File? = null
            var result: Prepared? = null
            try {
                tempDir.mkdirs()
                file = File(tempDir, "xfer-$xfer.syncr")
                // Only what the Mac lacks, when it said what it has and a delta is worth it; else the whole note.
                var asDelta = false
                var seq = base?.let { source.exportDelta(id, it, file) }
                if (seq != null && file.isFile) asDelta = true else { file.delete(); seq = source.exportCopy(id, file) }
                if (seq != null && file.isFile) result = Prepared(seq, file.length(), sha256(file), asDelta)
            } catch (e: Exception) {
                log("export of $id failed: ${e.javaClass.simpleName}")
            }
            val done = file
            loop.post { onPrepared(mine, id, xfer, done, result) }
        }
    }

    private class Prepared(val seq: Long, val size: Long, val sha256: String, val delta: Boolean)

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
        val chunks = (result.size + Wire.FILE_CHUNK_DATA - 1) / Wire.FILE_CHUNK_DATA
        val stream = FileStream(file, xfer, chunks)
        scheduler.sendControl(Control.File(xfer, id, result.seq, result.size, result.sha256, chunks, result.delta))
        scheduler.file = stream
        active = Transfer(xfer, id, result.seq, file, stream, result.delta)
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
        const val ANNOUNCE_MS = 400L
        /** `file_error` reason: a delta fell short; the sender is released and the whole note is asked for separately. */
        const val DELTA_SHORT = "delta short"
        const val ACK_TIMEOUT_MS = 30_000L
        private const val MAX_TITLE = 200
        private const val BATCH_BYTES = 48_000
        private const val MAX_TRASH = 1_500
    }
}
