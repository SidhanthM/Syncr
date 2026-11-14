package dev.syncr.prototype.link

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Mac to phone note sync (sync-v1.md). Link thread only, except [LinkLoop.background] blocks.
 *
 * - `notes` from the Mac: ask (`want`) for every note the phone lacks or holds a different operation set of, unless it is in
 *   the phone's Recently deleted.
 * - `file`, chunks, `file_end`: one transfer at a time, to a temp file; size and SHA-256 are checked; then the core installs,
 *   merges or rebases (off the link thread) and the phone answers `have` with its own digest.
 * - `have` from the Mac: if its digest differs from the phone's, ask for the note.
 * - The same note and operation set is not fetched more than [LIMIT] times a minute; a held-back request is retried when
 *   the minute has passed.
 */
class SyncReceiver(
    private val source: NoteSource,
    private val scheduler: SendScheduler,
    private val loop: LinkLoop,
    private val clock: Clock,
    private val tempDir: File,
    private val log: (String) -> Unit = {},
    /** Told when a Mac copy was put into the library, with what happened. */
    private val onApplied: (id: String, outcome: CopyOutcome) -> Unit = { _, _ -> },
) {
    private class Inbound(val offer: Control.File, val file: File, val out: FileOutputStream) {
        val hash: MessageDigest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        var next = 0L
    }

    private var active = false
    /** The Mac understands deltas: a `want` for a note the phone holds says what it has. */
    private var delta = false
    /** Notes whose next request must be for a whole copy, because a delta fell short. */
    private val fullOnly = HashSet<String>()
    private var generation = 0
    private var inbound: Inbound? = null
    private var applying = false
    private val requested = HashMap<String, String>()
    private val stamps = HashMap<String, ArrayList<Long>>()
    private val held = LinkedHashMap<String, NoteMeta>()
    private var lastRetry = 0L

    /** Transfers applied and files refused, for tests and the probe. */
    val applied = ArrayList<Pair<String, CopyOutcome>>()

    fun onWelcome(v1: Boolean, delta: Boolean = false) {
        onClosed()
        active = v1
        this.delta = v1 && delta
        generation++
    }

    fun onClosed() {
        active = false
        generation++
        inbound?.let { discard(it) }
        inbound = null
        applying = false
        requested.clear()
        held.clear()
        fullOnly.clear()
    }

    // ---- notes from the Mac ----

    fun onNotes(notes: List<NoteMeta>) {
        if (!active || notes.isEmpty()) return
        val mine = generation
        loop.background {
            val candidates = try {
                val trash = source.trash().toSet()
                notes.filter { it.digest != null && it.id !in trash }
            } catch (e: Exception) { log("trash read failed: ${e.javaClass.simpleName}"); emptyList() }
            val own = try { source.digests(candidates.map { it.id }) } catch (e: Exception) { log("digest read failed: ${e.javaClass.simpleName}"); null }
            loop.post { if (mine == generation && own != null) candidates.filter { own[it.id]?.digest != it.digest }.forEach(::request) }
        }
    }

    /** The Mac installed one of the phone's notes and reports its operation set. */
    fun onPeerHave(id: String, digest: String?) {
        if (!active || digest == null) return
        val mine = generation
        loop.background {
            val own = try { source.digests(listOf(id))[id]?.digest } catch (e: Exception) { null }
            loop.post { if (mine == generation && own != null && own != digest) request(NoteMeta(id, 0, "", digest), force = true) }
        }
    }

    private fun request(note: NoteMeta, force: Boolean = false) {
        val key = "${note.id}|${note.digest}"
        if (!force && requested[note.id] == key) return
        val now = clock.nowMs()
        val list = stamps.getOrPut(key) { ArrayList() }
        list.removeAll { now - it >= WINDOW_MS }
        if (list.size >= LIMIT) {
            if (held.put(note.id, note) == null) log("guard id=${note.id} direction=in held-back")
            return
        }
        list += now
        held.remove(note.id)
        requested[note.id] = key
        if (!delta || note.id in fullOnly) return scheduler.sendControl(Control.Want(listOf(note.id)))
        // Say what the phone already holds so the Mac can send just the rest. No answer (no such note) asks for it whole.
        val mine = generation
        loop.background {
            val heads = try { source.heads(note.id) } catch (e: Exception) { log("heads read failed: ${e.javaClass.simpleName}"); null }
            loop.post {
                if (mine != generation) return@post
                scheduler.sendControl(Control.Want(listOf(note.id), heads?.takeIf { it.isNotEmpty() }?.let { mapOf(note.id to it) }))
            }
        }
    }

    /** Retries requests the loop guard held back, once their minute has passed. */
    fun tick() {
        if (held.isEmpty()) return
        val now = clock.nowMs()
        if (now - lastRetry < 1_000) return
        lastRetry = now
        for (note in held.values.toList()) {
            val list = stamps["${note.id}|${note.digest}"] ?: continue
            if (list.count { now - it < WINDOW_MS } < LIMIT) request(note, force = true)
        }
    }

    // ---- a transfer ----

    fun onFile(offer: Control.File) {
        if (!active) return
        val minChunks = (offer.size + Wire.FILE_CHUNK_DATA - 1) / Wire.FILE_CHUNK_DATA
        if (inbound != null || applying) return refuse(offer, "busy")
        if (offer.id.isEmpty() || offer.size < 1 || offer.size > Wire.MAX_FILE_BYTES || offer.chunks < minChunks ||
            offer.chunks > offer.size || offer.sha256.length != 64 || !offer.sha256.all { it in '0'..'9' || it in 'a'..'f' }
        ) return refuse(offer, "bad offer")
        try {
            tempDir.mkdirs()
            val file = File(tempDir, "in-${offer.xfer}.syncr")
            file.delete()
            inbound = Inbound(offer, file, FileOutputStream(file))
        } catch (e: java.io.IOException) {
            refuse(offer, "staging")
        }
    }

    fun onChunk(message: ByteArray) {
        val t = inbound ?: return
        val chunk = try { Wire.parseFileChunk(message) } catch (e: LinkProtocolException) { return fail(t, "chunk") }
        if (chunk.transfer != t.offer.xfer) return // a late chunk of an abandoned transfer
        if (chunk.index != t.next || chunk.index >= t.offer.chunks || t.received + chunk.data.size > t.offer.size) return fail(t, "chunk")
        try { t.out.write(chunk.data) } catch (e: java.io.IOException) { return fail(t, "write") }
        t.hash.update(chunk.data)
        t.received += chunk.data.size
        t.next++
    }

    fun onFileEnd(xfer: Long) {
        val t = inbound?.takeIf { it.offer.xfer == xfer } ?: return
        inbound = null
        try { t.out.close() } catch (_: java.io.IOException) { }
        if (t.next != t.offer.chunks || t.received != t.offer.size) { discard(t); return refuse(t.offer, "size") }
        if (t.hash.digest().joinToString("") { "%02x".format(it) } != t.offer.sha256) { discard(t); return refuse(t.offer, "hash") }
        applying = true
        val mine = generation
        val id = t.offer.id
        loop.background {
            val outcome = try {
                if (t.offer.delta) source.applyDelta(id, t.file) else source.applyCopy(id, t.file)
            } catch (e: Exception) {
                CopyOutcome.Refused("${e.javaClass.simpleName}")
            } finally {
                t.file.delete()
            }
            loop.post {
                if (mine != generation) return@post
                applying = false
                requested.remove(id)
                if (!t.offer.delta) fullOnly.remove(id)
                // A delta that fell short (or could not be applied) is never trusted: ask for the whole note.
                val short = t.offer.delta && (outcome is CopyOutcome.Refused || (outcome as CopyOutcome.Applied).how != "delta")
                if (short) {
                    log("delta-short id=$id outcome=${if (outcome is CopyOutcome.Applied) "${outcome.how} rejected=${outcome.rejected}" else "refused"}; asking for the whole note")
                    fullOnly += id
                    // Free the sender's transfer slot, or its queue waits behind this one for the whole-note request.
                    scheduler.sendControl(Control.FileError(t.offer.xfer, id, SyncSender.DELTA_SHORT))
                    applied += id to outcome
                    onApplied(id, outcome)
                    return@post request(NoteMeta(id, 0, ""), force = true)
                }
                when (outcome) {
                    is CopyOutcome.Applied -> {
                        log("copy-${outcome.how} id=$id seq=${outcome.seq} rejected=${outcome.rejected}")
                        scheduler.sendControl(Control.Have(id, outcome.seq, outcome.digest.digest, outcome.digest.ops))
                    }
                    is CopyOutcome.Refused -> {
                        log("copy-refused id=$id reason=${outcome.reason}")
                        scheduler.sendControl(Control.FileError(t.offer.xfer, id, "apply: ${outcome.reason}".take(200)))
                    }
                }
                applied += id to outcome
                onApplied(id, outcome)
            }
        }
    }

    private fun fail(t: Inbound, reason: String) {
        inbound = null
        discard(t)
        refuse(t.offer, reason)
    }

    private fun refuse(offer: Control.File, reason: String) {
        requested.remove(offer.id)
        log("file-error id=${offer.id} reason=$reason")
        scheduler.sendControl(Control.FileError(offer.xfer, offer.id, reason))
    }

    private fun discard(t: Inbound) {
        try { t.out.close() } catch (_: java.io.IOException) { }
        t.file.delete()
    }

    val busy get() = inbound != null || applying

    companion object {
        const val LIMIT = 3
        const val WINDOW_MS = 60_000L
    }
}
