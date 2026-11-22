package dev.syncr.prototype.link

/**
 * p50 / p95 / max of the last [capacity] values. Two of them are kept: pen event to socket write, and ping round trip
 * (wifi-link-v0 §3.2). The Mac separately reports phone-send to Mac-presented.
 */
class LatencyStats(private val capacity: Int = 4096) {
    private val values = LongArray(capacity)
    private var count = 0
    private var next = 0

    @Synchronized fun add(micros: Long) {
        values[next] = micros
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized fun clear() { count = 0; next = 0 }

    @Synchronized fun size() = count

    /** "n=12 p50=0.8ms p95=2.1ms max=3.4ms", or "n=0". */
    @Synchronized fun summary(): String {
        if (count == 0) return "n=0"
        val sorted = values.copyOf(count).also { it.sort() }
        fun at(p: Double) = sorted[((count - 1) * p).toInt()]
        return "n=$count p50=${ms(at(0.5))} p95=${ms(at(0.95))} max=${ms(sorted[count - 1])}"
    }

    private fun ms(micros: Long) = "%.1fms".format(java.util.Locale.ROOT, micros / 1000.0)
}

class LatencyLog(private val context: () -> String = { "radio=host/unavailable" }, private val log: (String) -> Unit) {
    val penToWrite = LatencyStats()
    val roundTrip = LatencyStats()

    /** Logged at the end of a stroke and of a session. */
    fun report(label: String) {
        log("latency $label: pen-event-to-socket-write ${penToWrite.summary()} | round-trip ${roundTrip.summary()} | " +
            "${context()} | excludes: digitiser, display scan-out, Mac decode and present (the Mac logs phone-send to Mac-presented)")
    }
}

/**
 * Debug builds only (remaining-v1 L.2): where typed text spends its time on the phone, per stage, from the real editor and
 * link. Sending: key (the editor's text changed) to commit (stored durably) to sent (its transfer's last message written).
 * Receiving: received (a Mac file offer for the note) to applied (merged into the library) to published (the note's text
 * layer swapped in a scene built after the merge). Bounded: at most [PENDING] keys and [STAGES] samples per stage, no text.
 * [report] gives one line; the app logs it under `SyncrText` when the debug driver asks.
 */
object TextTrace {
    @Volatile var enabled = false
    private const val PENDING = 512
    private const val STAGES = 1024
    private val lock = Any()
    private val keys = ArrayDeque<Long>()
    private val committed = ArrayDeque<Long>()
    private val waitingSend = ArrayDeque<Pair<Long, Long>>() // key time, commit time
    private var sending: String? = null
    /** Per note: when a Mac file for it was offered, and when it was merged (0 until then). */
    private val inbound = HashMap<String, LongArray>()
    val keyToCommit = LatencyStats(STAGES); val commitToSent = LatencyStats(STAGES); val keyToSent = LatencyStats(STAGES)
    val receivedToApplied = LatencyStats(STAGES); val appliedToPublished = LatencyStats(STAGES); val receivedToPublished = LatencyStats(STAGES)
    val receivedToMerge = LatencyStats(STAGES); val mergeToApplied = LatencyStats(STAGES)
    /** Inside one store of typed text: waiting for the repository to be idle, the save itself, the whole store. */
    val storeSettle = LatencyStats(STAGES); val storeSave = LatencyStats(STAGES); val storeTotal = LatencyStats(STAGES)

    fun store(settleUs: Long, saveUs: Long, totalUs: Long) {
        if (!enabled) return
        storeSettle.add(settleUs); storeSave.add(saveUs); storeTotal.add(totalUs)
    }

    private fun now() = System.nanoTime()
    private fun us(from: Long, to: Long) = (to - from) / 1000

    /** The editor's text changed by a keystroke (main thread). */
    fun key() { if (!enabled) return; synchronized(lock) { keys.addLast(now()); while (keys.size > PENDING) keys.removeFirst() } }

    /** Stores of typed text since the last reset (remaining-v1 L.3's commits per second). */
    @Volatile var commits = 0
        private set

    /** The editor's text, with every key so far, is stored. */
    fun commit() {
        if (!enabled) return
        commits++
        val t = now()
        synchronized(lock) {
            while (keys.isNotEmpty()) { val k = keys.removeFirst(); keyToCommit.add(us(k, t)); waitingSend.addLast(k to t) }
            while (waitingSend.size > PENDING) waitingSend.removeFirst()
        }
    }

    /** A transfer of [noteId] starts (link thread): the keys committed by now travel in it. */
    fun sending(noteId: String) { if (!enabled) return; synchronized(lock) { if (waitingSend.isNotEmpty()) { sending = noteId; committed.clear(); waitingSend.forEach { committed.addLast(it.second) } } } }

    /** The transfer of [noteId] has had its last message written (link thread). */
    fun sent(noteId: String) {
        if (!enabled) return
        val t = now()
        synchronized(lock) {
            if (sending != noteId) return
            var n = committed.size
            while (n-- > 0 && waitingSend.isNotEmpty()) {
                val (k, c) = waitingSend.removeFirst()
                commitToSent.add(us(c, t)); keyToSent.add(us(k, t))
            }
            committed.clear(); sending = null
        }
    }

    fun received(noteId: String) { if (enabled) synchronized(lock) { if (inbound.size < PENDING) inbound[noteId] = longArrayOf(now(), 0L) } }

    /** The merge of a Mac file for [noteId] begins (after its bytes were checked and any merge before it finished). */
    fun merging(noteId: String) {
        if (!enabled) return
        val t = now()
        synchronized(lock) { inbound[noteId]?.let { if (it.size == 2) inbound[noteId] = longArrayOf(it[0], it[1], t) } }
    }

    fun applied(noteId: String) {
        if (!enabled) return
        val t = now()
        synchronized(lock) {
            inbound[noteId]?.let {
                if (it[1] == 0L) {
                    it[1] = t; receivedToApplied.add(us(it[0], t))
                    if (it.size == 3) { receivedToMerge.add(us(it[0], it[2])); mergeToApplied.add(us(it[2], t)) }
                }
            }
        }
    }

    /**
     * The text layer swapped in a scene of [noteId] (main thread); [requested] is when that scene was asked for. A scene asked
     * for before the file was offered is laid out from older text and does not count.
     */
    fun published(noteId: String?, requested: Long) {
        if (!enabled || noteId == null) return
        val t = now()
        synchronized(lock) {
            val r = inbound[noteId] ?: return
            if (requested < r[0]) return
            inbound.remove(noteId)
            receivedToPublished.add(us(r[0], t))
            if (r[1] != 0L) appliedToPublished.add(us(r[1], t))
        }
    }

    fun reset() = synchronized(lock) {
        keys.clear(); committed.clear(); waitingSend.clear(); inbound.clear(); sending = null; commits = 0
        listOf(keyToCommit, commitToSent, keyToSent, receivedToApplied, appliedToPublished, receivedToPublished, receivedToMerge, mergeToApplied,
            storeSettle, storeSave, storeTotal).forEach { it.clear() }
    }

    fun report() = "text-trace commits=$commits key-to-commit ${keyToCommit.summary()} (store ${storeTotal.summary()}: settle ${storeSettle.summary()}, save ${storeSave.summary()}) | commit-to-sent ${commitToSent.summary()} | key-to-sent ${keyToSent.summary()} | " +
        "received-to-applied ${receivedToApplied.summary()} (received-to-merge ${receivedToMerge.summary()}, merge-to-applied ${mergeToApplied.summary()}) | applied-to-published ${appliedToPublished.summary()} | received-to-published ${receivedToPublished.summary()}"
}
