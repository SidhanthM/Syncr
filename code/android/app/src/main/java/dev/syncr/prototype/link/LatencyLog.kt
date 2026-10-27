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
